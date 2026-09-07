//! Delivery comparison over the frozen absolute-coordinate reader and production.
//! Fixture conversion is only for identical-data measurement, never vault migration.
use super::*;
use crate::search::{absolute_baseline, decode_cursor, items, Execution};

fn open(path: &std::path::Path) -> Connection {
    let db = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap();
    crate::search_text::register(&db).unwrap();
    db
}
fn convert(old: &Connection, file: &std::path::Path) {
    let db = Connection::open(file).unwrap();
    crate::search_text::register(&db).unwrap();
    crate::index::create_position_fixture(&db).unwrap();
    db.execute_batch("BEGIN").unwrap();
    for table in [
        "namespaces",
        "predicate_kinds",
        "fact_sources",
        "index_predicates",
    ] {
        let mut stmt = old.prepare(&format!("SELECT * FROM {table}")).unwrap();
        let count = stmt.column_count();
        let mut put = db
            .prepare(&format!(
                "INSERT INTO {table} VALUES({})",
                vec!["?"; count].join(",")
            ))
            .unwrap();
        for row in stmt
            .query_map([], |r| {
                (0..count)
                    .map(|i| r.get::<_, Value>(i))
                    .collect::<rusqlite::Result<Vec<_>>>()
            })
            .unwrap()
        {
            put.execute(params_from_iter(row.unwrap())).unwrap();
        }
    }
    let mut sources = BTreeMap::new();
    for row in old
        .prepare("SELECT id,rel_path,source_version,layout FROM source_maps ORDER BY id")
        .unwrap()
        .query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
            ))
        })
        .unwrap()
    {
        let (id, path, version, layout) = row.unwrap();
        db.prepare_cached(
            "INSERT INTO source_maps(id,rel_path,source_version,layout) VALUES(?1,?2,?3,?4)",
        )
        .unwrap()
        .execute(params![id, path, version, layout])
        .unwrap();
        sources.insert(path, (id, version));
    }
    let targets: BTreeMap<(i64, i64, i64), i64> = db
        .prepare("SELECT source_id,kind,local_key,id FROM index_targets")
        .unwrap()
        .query_map([], |r| Ok(((r.get(0)?, r.get(1)?, r.get(2)?), r.get(3)?)))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap();
    let old_targets: BTreeMap<i64, (i64, i64, i64)> = old
        .prepare("SELECT id,source_id,kind,local_key FROM index_targets")
        .unwrap()
        .query_map([], |r| Ok((r.get(0)?, (r.get(1)?, r.get(2)?, r.get(3)?))))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap();
    for row in old
        .prepare("SELECT id,rel_path,line,field,text,folded FROM lines")
        .unwrap()
        .query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, Option<i64>>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, String>(4)?,
                r.get::<_, String>(5)?,
            ))
        })
        .unwrap()
    {
        let (id, path, line, field, text, folded) = row.unwrap();
        let target = if field == "body" {
            line.and_then(|n| sources.get(&path).and_then(|s| targets.get(&(s.0, 1, n))))
                .copied()
        } else {
            None
        };
        db.prepare_cached("INSERT INTO text_rows(id,rel_path,line,field,text,folded,target_id) VALUES(?1,?2,?3,?4,?5,?6,?7)").unwrap().execute(params![id,path,line,field,text,folded,target]).unwrap();
    }
    for row in old
        .prepare("SELECT id,rel_path,source_version,line,item_key FROM fact_locations")
        .unwrap()
        .query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, Option<String>>(2)?,
                r.get::<_, Option<i64>>(3)?,
                r.get::<_, Option<i64>>(4)?,
            ))
        })
        .unwrap()
    {
        let (id, path, version, line, item) = row.unwrap();
        db.prepare_cached(
            "INSERT OR IGNORE INTO fact_frames(rel_path,source_version) VALUES(?1,?2)",
        )
        .unwrap()
        .execute(params![path, version])
        .unwrap();
        let frame:i64=db.prepare_cached("SELECT id FROM fact_frames WHERE rel_path=?1 AND COALESCE(source_version,'')=COALESCE(?2,'')").unwrap().query_row(params![path,version],|r|r.get(0)).unwrap();
        let target = sources
            .get(&path)
            .filter(|s| Some(&s.1) == version.as_ref())
            .and_then(|s| match (line, item) {
                (None, None) => targets.get(&(s.0, 0, 0)),
                (Some(n), None) => targets.get(&(s.0, 1, n)),
                (None, Some(n)) => targets.get(&(s.0, 2, n)),
                _ => None,
            })
            .copied();
        db.prepare_cached("INSERT INTO fact_location_slots(id,frame_id,target_id,line,item_key) VALUES(?1,?2,?3,?4,?5)").unwrap().execute(params![id,frame,target,line.map(|n|if target.is_some(){0}else{n}),item.map(|n|if target.is_some(){0}else{n})]).unwrap();
    }
    for row in old
        .prepare("SELECT id,location_id,predicate_id,target_id,value,num,typed FROM fact_values")
        .unwrap()
        .query_map([], |r| {
            (0..7)
                .map(|i| r.get::<_, Value>(i))
                .collect::<rusqlite::Result<Vec<_>>>()
        })
        .unwrap()
    {
        let mut row = row.unwrap();
        if let Value::Integer(id) = row[3] {
            row[3] = Value::Integer(targets[&old_targets[&id]]);
        }
        db.prepare_cached("INSERT INTO fact_values VALUES(?1,?2,?3,?4,?5,?6,?7)")
            .unwrap()
            .execute(params_from_iter(row))
            .unwrap();
    }
    db.execute_batch("COMMIT; VACUUM").unwrap();
}

#[test]
#[ignore = "isolated schema-12/schema-13 comparison: GILUNE_COMPACT_DB and GILUNE_MATRIX_DIR"]
fn compare_position_delivery() {
    let out = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_DIR").unwrap());
    let folder = tempfile::Builder::new()
        .prefix("position-delivery-")
        .tempdir_in(&out)
        .unwrap()
        .keep();
    let old = open(std::path::Path::new(
        &std::env::var("GILUNE_COMPACT_DB").unwrap(),
    ));
    old.execute("VACUUM INTO ?1", [folder.join("old.db").to_str().unwrap()])
        .unwrap();
    drop(old);
    let old = Connection::open(folder.join("old.db")).unwrap();
    crate::search_text::register(&old).unwrap();
    super::frames::normalise(&old);
    convert(&old, &folder.join("new.db"));
    drop(old);
    let current = open(&folder.join("new.db"));
    current
        .execute(
            "ATTACH DATABASE ?1 AS old",
            [folder.join("old.db").to_str().unwrap()],
        )
        .unwrap();
    for cols in ["rel_path,line,item_key,source_version,namespace,predicate,value,num,typed"] {
        for (a, b) in [("main", "old"), ("old", "main")] {
            let n:i64=current.query_row(&format!("SELECT count(*) FROM (SELECT {cols} FROM {a}.facts EXCEPT SELECT {cols} FROM {b}.facts)"),[],|r|r.get(0)).unwrap();
            assert_eq!(n, 0);
        }
    }
    drop(current);
    let cases: Vec<Json> =
        serde_json::from_slice(&std::fs::read(out.join("dsl-cases.json")).unwrap()).unwrap();
    let selected = std::env::var("GILUNE_POSITION_CASES").unwrap_or_else(|_| "*".into());
    let mut results = vec![];
    for case in cases
        .iter()
        .filter(|c| selected == "*" || selected.split(',').any(|s| Some(s) == c["name"].as_str()))
    {
        let mut q = request(case);
        if q.clauses.iter().any(|c| c.residual.is_some()) {
            println!("POSITION_UNSUPPORTED {}", case["name"]);
            continue;
        }
        q.page_size = std::env::var("GILUNE_POSITION_PAGE_SIZE")
            .ok()
            .map(|s| s.parse().unwrap())
            .unwrap_or(50);
        let pages: usize = std::env::var("GILUNE_POSITION_PAGE_NUMBER")
            .ok()
            .map(|s| s.parse().unwrap())
            .unwrap_or(1);
        let mut cursors = vec![];
        let mut expected = None;
        for name in ["old", "new"] {
            let db = open(&folder.join(format!("{name}.db")));
            let mut after = None;
            let run = |a, mode| {
                if name == "old" {
                    absolute_baseline::execute(&db, &q, a, mode).unwrap()
                } else {
                    items::execute(
                        &db,
                        &q,
                        None,
                        "positions".into(),
                        "positions".into(),
                        a,
                        vec![],
                        mode,
                    )
                    .unwrap()
                }
            };
            for _ in 1..pages {
                let p = run(after, Execution::Page);
                after = decode_cursor(p.next_cursor.as_deref(), "positions", "positions").unwrap();
                assert!(after.is_some());
            }
            let mut actual = run(after.clone(), Execution::Complete);
            let more = actual.next_cursor.take().is_some();
            if let Some((ref old, old_more)) = expected {
                assert_eq!(&actual, old, "{}", case["name"]);
                assert_eq!(more, old_more);
            } else {
                expected = Some((actual, more));
            }
            cursors.push(after);
        }
        let mut samples = vec![];
        for sample in 0..5 {
            for n in if sample % 2 == 0 { [0, 1] } else { [1, 0] } {
                let name = ["old", "new"][n];
                let db = open(&folder.join(format!("{name}.db")));
                db.execute_batch("BEGIN").unwrap();
                let run = |mode| {
                    if n == 0 {
                        absolute_baseline::execute(&db, &q, cursors[n].clone(), mode).unwrap()
                    } else {
                        items::execute(
                            &db,
                            &q,
                            None,
                            "positions".into(),
                            "positions".into(),
                            cursors[n].clone(),
                            vec![],
                            mode,
                        )
                        .unwrap()
                    }
                };
                run(Execution::Complete);
                crate::probe::reset_file_reads();
                let (complete, p) = time(|| run(Execution::Complete));
                let stages = if crate::search::target_query::supports(&q) {
                    Some(if n == 0 {
                        absolute_baseline::timings()
                    } else {
                        crate::search::target_query::TIMINGS.with(|t| t.get())
                    })
                } else {
                    None
                };
                let (page, _) = time(|| run(Execution::Page));
                assert_eq!(p.hits, expected.as_ref().unwrap().0.hits);
                assert_eq!(crate::probe::file_reads(), 0);
                samples.push(json!({"model":name,"sample":sample,"complete_ms":ms(complete),"page_ms":ms(page),"stages_ms":stages}));
            }
        }
        let result = json!({"name":case["name"],"dsl":case["dsl"],"sort":q.sort_predicate,"compile_ms":case["compile_ms"],"page_size":q.page_size,"page_number":pages,"total":expected.unwrap().0.total_hits,"samples":samples});
        println!("POSITION_QUERY {result}");
        results.push(result);
    }
    let sizes: Vec<_> = ["old", "new"]
        .iter()
        .map(|n| {
            std::fs::metadata(folder.join(format!("{n}.db")))
                .unwrap()
                .len()
        })
        .collect();
    std::fs::write(
        folder.join("results.json"),
        serde_json::to_vec_pretty(&json!({"sizes":sizes,"queries":results})).unwrap(),
    )
    .unwrap();
    println!("POSITION_OUTPUT {}", folder.display());
}
