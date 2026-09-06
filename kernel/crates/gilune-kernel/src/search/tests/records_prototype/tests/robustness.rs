use super::*;

fn readings_query(id: usize) -> Query {
    Query {
        relation: id,
        conditions: vec![],
        related: vec![],
        order: 1,
    }
}

#[test]
fn numeric_ranges_and_multiple_records_preserve_witness_scope() {
    let (mut registry, base, _) = unrelated();
    let readings = registry
        .register(Schema {
            owner: "fixture.samples",
            name: "samples",
            fields: vec![Field {
                name: "value",
                kind: Kind::Number,
                nullable: false,
            }],
            many: true,
            indexes: vec![vec![0]],
        })
        .unwrap();
    let db = Connection::open_in_memory().unwrap();
    registry.install(&db);
    db.execute_batch("INSERT INTO sources VALUES(1,'a.md','v1'); INSERT INTO targets VALUES(1,1,1,10),(2,1,1,20),(3,1,1,30);").unwrap();
    for (target, value) in [(1, -2.5), (2, 2.0), (3, 10.0)] {
        registry
            .insert(
                &db,
                "fixture.readings",
                base,
                target,
                &[text("room"), Value::Real(value)],
            )
            .unwrap();
    }
    for (target, value) in [(1, 0.0), (1, 100.0), (2, 50.0)] {
        registry
            .insert(
                &db,
                "fixture.samples",
                readings,
                target,
                &[Value::Real(value)],
            )
            .unwrap();
    }
    for invalid in [
        Value::Real(f64::NAN),
        Value::Real(f64::INFINITY),
        text("50"),
        Value::Null,
    ] {
        assert!(registry
            .insert(&db, "fixture.samples", readings, 3, &[invalid])
            .is_err());
    }
    let mut q = readings_query(base);
    let ids = |q: &Query| {
        q.page(&db, &registry, None, 50)
            .unwrap()
            .rows
            .into_iter()
            .map(|r| r.target)
            .collect::<Vec<_>>()
    };
    assert_eq!(ids(&q), vec![1, 2, 3]); // Numeric ordering, not lexical 10 before 2.
    let ge = Condition {
        field: 0,
        op: Op::Ge,
        values: vec![Value::Real(40.0)],
    };
    let lt = Condition {
        field: 0,
        op: Op::Lt,
        values: vec![Value::Real(60.0)],
    };
    q.related = vec![Related {
        relation: readings,
        conditions: vec![ge.clone(), lt.clone()],
        negated: false,
    }];
    assert_eq!(ids(&q), vec![2]); // One sample must satisfy both bounds.
    q.related = vec![
        Related {
            relation: readings,
            conditions: vec![ge],
            negated: false,
        },
        Related {
            relation: readings,
            conditions: vec![lt],
            negated: false,
        },
    ];
    assert_eq!(ids(&q), vec![1, 2]); // Separate existential conditions can use different samples.
    q.related[1].negated = true;
    assert!(ids(&q).is_empty());
    q.related.clear();
    q.conditions = vec![
        Condition {
            field: 1,
            op: Op::Ge,
            values: vec![Value::Real(2.0)],
        },
        Condition {
            field: 1,
            op: Op::Lt,
            values: vec![Value::Real(10.0)],
        },
    ];
    assert_eq!(ids(&q), vec![2]);
    assert_eq!(q.count(&db, &registry).unwrap(), (1, 1));
}

#[test]
fn wal_reader_snapshot_writer_commit_and_cursor_expiry_are_coherent() {
    let (registry, id, _) = unrelated();
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("index.db");
    let reader = Connection::open(&path).unwrap();
    reader.execute_batch("PRAGMA journal_mode=WAL").unwrap();
    registry.install(&reader);
    reader.execute_batch("INSERT INTO sources VALUES(1,'a.md','v1'); INSERT INTO targets VALUES(1,1,1,10),(2,1,1,20); INSERT INTO r0 VALUES(1,'room',1),(2,'room',2);").unwrap();
    let writer = Connection::open(&path).unwrap();
    writer.execute_batch("PRAGMA foreign_keys=ON").unwrap();
    let q = readings_query(id);
    let page = q.page(&reader, &registry, None, 1).unwrap();
    let tx = reader.unchecked_transaction().unwrap();
    let before: String = tx
        .query_row("SELECT version FROM sources", [], |r| r.get(0))
        .unwrap();
    assert_eq!(before, "v1"); // Establish the read snapshot before the other connection commits.
    std::thread::spawn(move || {
        writer.execute_batch("BEGIN IMMEDIATE; UPDATE sources SET version='v2'; DELETE FROM targets WHERE id=1; UPDATE state SET generation=2; COMMIT;").unwrap();
    }).join().unwrap();
    let (sql, params) = q.sql(None, true);
    let old_count: i64 = tx
        .query_row(&sql, params_from_iter(params), |r| r.get(0))
        .unwrap();
    assert_eq!(old_count, 2);
    assert_eq!(
        tx.query_row("SELECT generation FROM state", [], |r| r.get::<_, i64>(0))
            .unwrap(),
        1
    );
    tx.commit().unwrap();
    assert_eq!(q.count(&reader, &registry).unwrap(), (2, 1));
    assert_eq!(
        q.page(&reader, &registry, page.next.as_ref(), 1)
            .err()
            .unwrap(),
        "expired cursor"
    );
    assert!(reader.is_autocommit());
    reader
        .execute_batch(
            "BEGIN IMMEDIATE; DELETE FROM sources; UPDATE state SET generation=3; ROLLBACK;",
        )
        .unwrap();
    assert_eq!(q.count(&reader, &registry).unwrap(), (2, 1));
    assert_eq!(
        reader
            .query_row("SELECT version FROM sources", [], |r| r.get::<_, String>(0))
            .unwrap(),
        "v2"
    );
}

#[test]
fn interrupted_query_releases_snapshot_and_connection_is_reusable() {
    use rusqlite::functions::FunctionFlags;
    use std::sync::{mpsc, Mutex};
    let (registry, id, db) = unrelated();
    db.execute_batch("INSERT INTO r0 VALUES(1,'room',1),(2,'room',2); ALTER TABLE r0 RENAME TO actual_r0; CREATE VIEW r0 AS SELECT target,f0,f1 FROM actual_r0 WHERE query_gate();").unwrap();
    let (entered_tx, entered_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    let gate = Mutex::new(Some((entered_tx, release_rx)));
    db.create_scalar_function("query_gate", 0, FunctionFlags::SQLITE_UTF8, move |_| {
        if let Some((entered, release)) = gate.lock().unwrap().take() {
            entered.send(()).unwrap();
            release
                .recv_timeout(std::time::Duration::from_secs(10))
                .unwrap();
        }
        Ok(true)
    })
    .unwrap();
    let interrupt = db.get_interrupt_handle();
    let thread = std::thread::spawn(move || {
        entered_rx
            .recv_timeout(std::time::Duration::from_secs(10))
            .unwrap();
        interrupt.interrupt();
        release_tx.send(()).unwrap();
    });
    let q = readings_query(id);
    let error = q.count(&db, &registry).unwrap_err();
    thread.join().unwrap();
    assert!(error.contains("interrupt"), "{error}");
    assert!(db.is_autocommit());
    assert_eq!(q.count(&db, &registry).unwrap(), (1, 2));
    assert_eq!(q.page(&db, &registry, None, 2).unwrap().rows.len(), 2);
}
