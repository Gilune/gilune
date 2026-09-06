//! Isolate full compact fact/target replacement; not a production maintenance API.
use super::*;

fn rows(db: &Connection, sql: &str) -> Vec<Vec<Value>> {
    let mut statement = db.prepare(sql).unwrap();
    let columns = statement.column_count();
    statement
        .query_map([], |r| (0..columns).map(|i| r.get(i)).collect())
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap()
}

#[test]
#[ignore = "manual complete-fact write component: GILUNE_SHAPE_DIR"]
fn profile_compact_ten_thousand_task_replacement() {
    let root = std::path::PathBuf::from(std::env::var("GILUNE_SHAPE_DIR").unwrap());
    let origin = Connection::open_with_flags(
        root.join("items-10000/.gilune/local/index.db"),
        OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    let sources = rows(
        &origin,
        "SELECT id,rel_path,source_version,layout FROM source_maps ORDER BY id",
    );
    let targets = rows(&origin, "SELECT id,source_id,kind,local_key,map_version,start_byte,end_byte,own_ranges FROM index_targets ORDER BY id");
    let predicates = rows(
        &origin,
        "SELECT id,namespace,predicate FROM index_predicates ORDER BY id",
    );
    let facts = rows(
        &origin,
        "SELECT rowid,target_id,predicate_id,value,num,typed FROM facts ORDER BY rowid",
    );
    assert_eq!(sources.len(), 1);
    assert_eq!(
        targets.iter().filter(|r| r[2] == Value::Integer(2)).count(),
        10000
    );
    assert!(facts
        .iter()
        .all(|r| r[1] != Value::Null && r[2] != Value::Null));
    let source_id = match sources[0][0] {
        Value::Integer(n) => n,
        _ => unreachable!(),
    };
    let first_task = targets.iter().find(|r| r[2] == Value::Integer(2)).unwrap()[0].clone();
    let due = predicates
        .iter()
        .find(|r| r[2] == Value::Text(DUE.into()))
        .unwrap()[0]
        .clone();
    let changed_row = facts
        .iter()
        .position(|r| r[1] == first_task && r[2] == due)
        .unwrap();
    assert_eq!(facts[changed_row][3], Value::Text("2026-09-01".into()));
    let mut changed = facts.clone();
    changed[changed_row][3] = Value::Text("2026-10-01".into());
    let stride = targets
        .iter()
        .map(|r| match r[0] {
            Value::Integer(n) => n,
            _ => unreachable!(),
        })
        .max()
        .unwrap()
        + 1;
    let run = tempfile::Builder::new()
        .prefix("write-")
        .tempdir_in(&root)
        .unwrap()
        .keep();
    let db = Connection::open(run.join("compact.db")).unwrap();
    crate::search_text::register(&db).unwrap();
    db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;
        CREATE TABLE source_maps(id INTEGER PRIMARY KEY,rel_path TEXT NOT NULL UNIQUE,source_version TEXT NOT NULL,layout TEXT NOT NULL);
        CREATE TABLE index_targets(id INTEGER PRIMARY KEY,source_id INTEGER NOT NULL REFERENCES source_maps(id),kind INTEGER NOT NULL,local_key INTEGER NOT NULL,map_version INTEGER NOT NULL,start_byte INTEGER NOT NULL,end_byte INTEGER NOT NULL,own_ranges TEXT NOT NULL,UNIQUE(source_id,kind,local_key));
        CREATE TABLE index_predicates(id INTEGER PRIMARY KEY,namespace TEXT NOT NULL,predicate TEXT NOT NULL,UNIQUE(namespace,predicate));
        CREATE TABLE compact_facts(id INTEGER PRIMARY KEY,target_id INTEGER NOT NULL REFERENCES index_targets(id),predicate_id INTEGER NOT NULL REFERENCES index_predicates(id),value TEXT NOT NULL,num REAL,typed INTEGER NOT NULL);
        CREATE INDEX compact_target_value ON compact_facts(target_id,predicate_id,value);
        CREATE INDEX compact_predicate_value ON compact_facts(predicate_id,value,target_id);
        CREATE INDEX compact_predicate_number ON compact_facts(predicate_id,num,target_id);
        CREATE INDEX compact_predicate_folded ON compact_facts(predicate_id,gilune_fold(value),target_id);
        CREATE TABLE state(generation INTEGER NOT NULL); INSERT INTO state VALUES(1);
        BEGIN;").unwrap();
    let insert = |sql: &str, values: &[Value]| {
        db.prepare_cached(sql)
            .unwrap()
            .execute(params_from_iter(values))
            .unwrap();
    };
    for r in &sources {
        insert("INSERT INTO source_maps VALUES(?,?,?,?)", r);
    }
    for r in &predicates {
        insert("INSERT INTO index_predicates VALUES(?,?,?)", r);
    }
    for r in &targets {
        insert("INSERT INTO index_targets VALUES(?,?,?,?,?,?,?,?)", r);
    }
    for r in &facts {
        insert("INSERT INTO compact_facts VALUES(?,?,?,?,?,?)", r);
    }
    db.execute_batch("COMMIT; ANALYZE; PRAGMA wal_checkpoint(TRUNCATE)")
        .unwrap();
    let mut samples = vec![];
    for sample in 0..7 {
        let input = if sample % 2 == 0 { &changed } else { &facts };
        let shift = (sample + 1) * stride;
        let mut delete_ms = 0.0;
        let mut target_ms = 0.0;
        let mut fact_ms = 0.0;
        let mut commit_ms = 0.0;
        let (elapsed, ()) = time(|| {
            db.execute_batch("BEGIN IMMEDIATE").unwrap();
            delete_ms = ms(time(|| {
                db.execute("DELETE FROM compact_facts WHERE target_id IN (SELECT id FROM index_targets WHERE source_id=?)", [source_id]).unwrap();
                db.execute("DELETE FROM index_targets WHERE source_id=?", [source_id]).unwrap();
            }).0);
            target_ms = ms(time(|| {
                db.execute(
                    "UPDATE source_maps SET source_version=?,layout=? WHERE id=?",
                    params![
                        format!("derived-fixture-{sample}"),
                        sources[0][3],
                        source_id
                    ],
                )
                .unwrap();
                for row in &targets {
                    let mut r = row.clone();
                    if let Value::Integer(n) = &mut r[0] {
                        *n += shift;
                    }
                    insert("INSERT INTO index_targets VALUES(?,?,?,?,?,?,?,?)", &r);
                }
            })
            .0);
            fact_ms = ms(time(|| {
                for row in input {
                    let mut r = row.clone();
                    if let Value::Integer(n) = &mut r[1] {
                        *n += shift;
                    }
                    insert("INSERT INTO compact_facts VALUES(?,?,?,?,?,?)", &r);
                }
            })
            .0);
            commit_ms = ms(time(|| {
                db.execute_batch("UPDATE state SET generation=generation+1; COMMIT")
                    .unwrap()
            })
            .0);
        });
        // Compare all data, not just counts. Only one date and revision-qualified target IDs change.
        let mut actual = rows(
            &db,
            "SELECT id,target_id,predicate_id,value,num,typed FROM compact_facts ORDER BY id",
        );
        for r in &mut actual {
            if let Value::Integer(n) = &mut r[1] {
                *n -= shift;
            }
        }
        assert_eq!(&actual, input);
        let mut actual_targets = rows(&db, "SELECT id,source_id,kind,local_key,map_version,start_byte,end_byte,own_ranges FROM index_targets ORDER BY id");
        for r in &mut actual_targets {
            if let Value::Integer(n) = &mut r[0] {
                *n -= shift;
            }
        }
        assert_eq!(actual_targets, targets);
        assert_eq!(
            db.query_row("SELECT generation FROM state", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            sample + 2
        );
        assert!(rows(&db, "PRAGMA foreign_key_check").is_empty());
        let result = json!({"sample":sample,"total_ms":ms(elapsed),"delete_ms":delete_ms,"targets_map_ms":target_ms,"facts_indexes_ms":fact_ms,"commit_ms":commit_ms});
        println!("COMPACT_WRITE {result}");
        samples.push(result);
    }
    let result = json!({"tasks":10000,"targets":targets.len(),"facts":facts.len(),"fact_indexes":4,"samples":samples,
        "limits":"Full compact fact and target replacement only, from pre-extracted data; includes source-map write, foreign keys, four fact indexes, generation and WAL FULL commit. Excludes source reading/reconciliation, parsing/plugins, FFI, fact validation, text/FTS and producer receipts. No production maintenance API or complete-edit latency claim."});
    std::fs::write(
        run.join("results.json"),
        serde_json::to_vec_pretty(&result).unwrap(),
    )
    .unwrap();
    println!("COMPACT_WRITE_OUTPUT {}", run.display());
}
