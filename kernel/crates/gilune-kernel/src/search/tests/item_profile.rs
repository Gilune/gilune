//! Read-only diagnostic over the actual Kotlin Tasks + People benchmark index.
//! Domain strings occur only in this explicit integration fixture, never dispatch.
use super::*;
use crate::index::{ConstraintOp, FactLocality};
use rusqlite::{OpenFlags, StatementStatus};
use std::time::{Duration, Instant};

pub(super) fn request() -> SearchRequest {
    // Exact clauses printed by CrossPluginSearchBenchmarkTest.compileSearch.
    let clauses = [
        ("org.memory.tasks.state", ConstraintOp::Exists, ""),
        ("org.memory.tasks.state", ConstraintOp::Eq, "open"),
        (
            "org.memory.people.connection",
            ConstraintOp::Eq,
            "[\"assign\",\"alice\"]",
        ),
        ("org.memory.tasks.due", ConstraintOp::Gt, "2026-08-31"),
        ("org.memory.tasks.due", ConstraintOp::Lt, "2026-10-01"),
    ]
    .into_iter()
    .map(|(predicate, op, value)| SearchClause {
        constraints: vec![Constraint::FactAt {
            predicate: predicate.into(),
            op,
            value: value.into(),
            locality: FactLocality::Exact,
        }],
        negated: false,
        case_sensitive: false,
        residual: None,
        never: false,
    })
    .collect();
    SearchRequest {
        clauses,
        line_mode: true,
        extension_candidates: false,
        group_by_note: false,
        body_needles: vec![],
        name_needles: vec![],
        sort_predicate: String::new(),
        required_namespaces: vec!["org.memory.people".into(), "org.memory.tasks".into()],
        extension_key: String::new(),
        page_size: 50,
        max_lines: 1,
        cursor: None,
    }
}

fn median(mut values: Vec<Duration>) -> Duration {
    values.sort();
    values[values.len() / 2]
}

#[test]
#[ignore = "manual read-only profile; set GILUNE_CROSS_PLUGIN_INDEX to the Kotlin fixture index.db"]
fn profile_real_cross_plugin_item_query() {
    let path = std::env::var("GILUNE_CROSS_PLUGIN_INDEX").expect("run the Kotlin fixture first");
    let db = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap();
    crate::search_text::register(&db).unwrap();
    db.execute_batch("BEGIN").unwrap();
    let q = request();
    let mut required = q.required_namespaces.clone();
    required.push(index::FRONT_MATTER_NAMESPACE.into());
    required.sort();
    let (sql, params) = super::sql(&q, &required);
    println!("SQL={sql}\nPARAMS={params:?}");
    let plan = format!("EXPLAIN QUERY PLAN {sql}");
    for row in db
        .prepare(&plan)
        .unwrap()
        .query_map(params_from_iter(params.clone()), |r| {
            Ok((
                r.get::<_, i32>(0)?,
                r.get::<_, i32>(1)?,
                r.get::<_, String>(3)?,
            ))
        })
        .unwrap()
    {
        println!("PLAN {:?}", row.unwrap());
    }
    let mut composition = vec![];
    let mut selection = vec![];
    let mut whole = vec![];
    for sample in 0..6 {
        let start = Instant::now();
        let (sql, params) = super::sql(&q, &required);
        let compose = start.elapsed();
        let start = Instant::now();
        let mut stmt = db.prepare(&sql).unwrap();
        let mut rows = stmt.query(params_from_iter(params)).unwrap();
        let mut count = 0;
        while let Some(row) = rows.next().unwrap() {
            let _: String = row.get(0).unwrap();
            let _: u64 = row.get(1).unwrap();
            count += 1;
        }
        let select = start.elapsed();
        drop(rows);
        assert_eq!(count, 17_500);
        if sample == 5 {
            println!(
                "SQL rows={count} vm_steps={} fullscan_steps={} sorts={}",
                stmt.get_status(StatementStatus::VmStep),
                stmt.get_status(StatementStatus::FullscanStep),
                stmt.get_status(StatementStatus::Sort)
            );
        }
        crate::probe::reset_file_reads();
        let start = Instant::now();
        let page = super::legacy_search(
            &db,
            &q,
            None,
            "fixture".into(),
            "fixture".into(),
            None,
            vec![],
        )
        .unwrap();
        let elapsed = start.elapsed();
        assert!(page.index_complete, "{:?}", page.issues);
        assert_eq!(page.total_hits, 17_500);
        assert_eq!(page.hits.len(), 50);
        assert_eq!(crate::probe::file_reads(), 0);
        if sample > 0 {
            composition.push(compose);
            selection.push(select);
            whole.push(elapsed);
        }
    }
    println!("NATIVE SQL_COMPOSITION median={:?}", median(composition));
    println!("NATIVE SQL_SELECTION median={:?}", median(selection));
    println!(
        "NATIVE ITEM_SERVICE median={:?} source_reads=0 (excludes Store entry and FFI)",
        median(whole)
    );
    db.execute_batch("ROLLBACK").unwrap();
}
