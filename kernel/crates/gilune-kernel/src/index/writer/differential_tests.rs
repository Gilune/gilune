use super::*;
use gilune_document::TextSnapshot;
use rusqlite::types::Value;

fn db() -> Connection {
    let db = Connection::open_in_memory().unwrap();
    crate::search_text::register(&db).unwrap();
    db.execute_batch(SCHEMA).unwrap();
    db
}

fn source(db: &Connection, text: &str) -> TextSnapshot {
    let snapshot = TextSnapshot::new(text.into()).unwrap();
    db.execute("INSERT INTO source_maps(rel_path,source_version,layout) VALUES('a.md',?1,?2) ON CONFLICT(rel_path) DO UPDATE SET source_version=excluded.source_version,layout=excluded.layout",params![snapshot.version(),scoped::encode_layout(snapshot.layout())]).unwrap();
    snapshot
}

fn rows(snapshot: &TextSnapshot) -> Vec<FactRow> {
    snapshot
        .layout()
        .items
        .iter()
        .enumerate()
        .map(|(i, item)| FactRow {
            line: None,
            item_key: Some(item.key as i64),
            source_version: Some(snapshot.version().into()),
            predicate: "fixture.value".into(),
            value: format!("value {i}"),
            num: None,
            typed: true,
        })
        .collect()
}

fn contents(db: &Connection) -> Vec<Vec<Value>> {
    db.prepare("SELECT namespace,rel_path,line,item_key,source_version,predicate,value,num,typed,t.kind,t.local_key FROM facts f LEFT JOIN index_targets t ON t.id=f.target_id ORDER BY namespace,f.rowid").unwrap()
        .query_map([], |r| (0..11).map(|i|r.get(i)).collect()).unwrap().collect::<rusqlite::Result<_>>().unwrap()
}

fn ids(db: &Connection, table: &str) -> Vec<i64> {
    db.prepare(&format!("SELECT id FROM {table} ORDER BY id"))
        .unwrap()
        .query_map([], |r| r.get(0))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap()
}

#[test]
fn one_value_change_retains_fact_rows_and_unchanged_targets_but_advances_provenance() {
    let db = db();
    let text = "- entry 2026-09-01\n".repeat(1000);
    let snapshot = source(&db, &text);
    let facts = rows(&snapshot);
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    replace_facts(&db, "fixture", "a.md", &facts, Some(snapshot.version())).unwrap();
    db.execute_batch("COMMIT").unwrap();
    let before_ids = ids(&db, "fact_values");
    let before_targets = ids(&db, "index_targets");
    db.execute_batch("CREATE TABLE target_writes(kind); CREATE TRIGGER track_target_insert AFTER INSERT ON target_slots BEGIN INSERT INTO target_writes VALUES('insert'); END; CREATE TRIGGER track_target_update AFTER UPDATE ON target_slots BEGIN INSERT INTO target_writes VALUES('update'); END; CREATE TRIGGER track_target_delete AFTER DELETE ON target_slots BEGIN INSERT INTO target_writes VALUES('delete'); END;").unwrap();
    let next = source(&db, &text.replacen("2026-09-01", "2026-09-02", 1));
    assert_eq!(ids(&db, "index_targets"), before_targets);
    assert_eq!(
        db.query_row("SELECT count(*) FROM target_writes", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
    assert_eq!(
        db.query_row("SELECT count(target_id) FROM fact_values", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
    let mut changed = rows(&next);
    changed[0].value = "changed".into();
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    let result = replace_facts(&db, "fixture", "a.md", &changed, Some(next.version())).unwrap();
    assert_eq!(
        result,
        Changes {
            binding_only: 999,
            values_changed: 1,
            ..Changes::default()
        }
    );
    db.execute_batch("COMMIT").unwrap();
    assert_eq!(ids(&db, "fact_values"), before_ids);
    assert_eq!(
        db.query_row(
            "SELECT count(*) FROM facts WHERE source_version=?1 AND target_id IS NOT NULL",
            [next.version()],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        1000
    );
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    assert_eq!(
        replace_facts(&db, "fixture", "a.md", &changed, Some(next.version())).unwrap(),
        Changes {
            unchanged: 1000,
            ..Changes::default()
        }
    );
    db.execute_batch("COMMIT").unwrap();
}

#[test]
fn ordered_duplicates_legacy_typed_and_resized_batches_match_full_replacement() {
    let differential = db();
    let full = db();
    let text = "- outer 😀\r\n  - child\r\n- other\r\n";
    let snapshot = source(&differential, text);
    source(&full, text);
    let mut batch = rows(&snapshot);
    batch.push(FactRow {
        line: None,
        item_key: None,
        source_version: None,
        predicate: "fixture.value".into(),
        value: "legacy".into(),
        num: None,
        typed: true,
    });
    batch.push(FactRow {
        line: None,
        item_key: None,
        source_version: Some("stale".into()),
        predicate: "fixture.number".into(),
        value: "invalid".into(),
        num: None,
        typed: false,
    });
    batch.push(FactRow {
        line: None,
        item_key: None,
        source_version: Some(snapshot.version().into()),
        predicate: "fixture.number".into(),
        value: "-2.5".into(),
        num: Some(-2.5),
        typed: true,
    });
    batch.push(FactRow {
        line: None,
        item_key: Some(0),
        source_version: Some(snapshot.version().into()),
        predicate: "fixture.value".into(),
        value: "value 0".into(),
        num: None,
        typed: true,
    });
    for step in 0..5 {
        for db in [&differential, &full] {
            db.execute_batch("BEGIN IMMEDIATE").unwrap();
        }
        for namespace in ["first", "second"] {
            replace_facts(
                &differential,
                namespace,
                "a.md",
                &batch,
                Some(snapshot.version()),
            )
            .unwrap();
            forget_path(&full, namespace, "a.md").unwrap();
            put_facts(&full, namespace, "a.md", &batch, Some(snapshot.version())).unwrap();
        }
        for db in [&differential, &full] {
            db.execute_batch("COMMIT").unwrap();
        }
        assert_eq!(contents(&differential), contents(&full), "step {step}");
        match step {
            0 => batch.reverse(),
            1 => {
                batch.remove(1);
            }
            2 => batch = rows(&snapshot),
            _ => batch.clear(),
        }
    }
    assert_eq!(
        differential
            .query_row("SELECT count(*) FROM fact_sources", [], |r| r
                .get::<_, i64>(0))
            .unwrap(),
        2,
        "empty successful batches still have receipts"
    );
}

#[test]
fn producer_failure_rolls_back_changes_and_does_not_promote_another_producer() {
    let db = db();
    let old = source(&db, "- old\n- two\n");
    let batch = rows(&old);
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    for namespace in ["first", "second"] {
        replace_facts(&db, namespace, "a.md", &batch, Some(old.version())).unwrap();
    }
    db.execute_batch("COMMIT").unwrap();
    let new = source(&db, "prefix\n- new\n- two\n");
    let mut next = rows(&new);
    let before = contents(&db);
    let locations = ids(&db, "fact_locations");
    db.execute_batch("CREATE TRIGGER reject_value BEFORE UPDATE OF value ON fact_values WHEN NEW.value='reject' BEGIN SELECT RAISE(ABORT,'fixture failure'); END; BEGIN IMMEDIATE;").unwrap();
    next[0].value = "accepted".into();
    next[1].value = "reject".into();
    assert!(replace_facts(&db, "first", "a.md", &next, Some(new.version())).is_err());
    db.execute_batch("ROLLBACK").unwrap();
    assert_eq!(contents(&db), before);
    assert_eq!(ids(&db, "fact_locations"), locations);
    assert_eq!(
        db.query_row(
            "SELECT source_version FROM fact_sources WHERE namespace='first'",
            [],
            |r| r.get::<_, String>(0)
        )
        .unwrap(),
        old.version()
    );
    next[1].value = "accepted too".into();
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    replace_facts(&db, "first", "a.md", &next, Some(new.version())).unwrap();
    db.execute_batch("COMMIT").unwrap();
    assert_eq!(
        db.query_row(
            "SELECT count(target_id) FROM facts WHERE namespace='second'",
            [],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        0
    );
    assert_eq!(
        db.query_row(
            "SELECT count(*) FROM facts WHERE namespace='second' AND source_version=?1",
            [old.version()],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        2
    );
    assert!(replace_facts(&db, "first", "a.md", &[], None).is_err());
}

#[test]
fn incremental_target_layout_matches_parser_after_structural_and_position_changes() {
    let db = db();
    for text in [
        "- parent\n  - child\n- other\n",
        "heading 😀\r\n- parent\r\n  - child\r\n- other\r\n",
        "```\n- parent\n```\n- child\n",
        "plain",
        "",
    ] {
        source(&db, text);
        for (a, b) in [
            ("index_targets", "source_target_layout"),
            ("source_target_layout", "index_targets"),
        ] {
            let sql=format!("SELECT count(*) FROM (SELECT source_id,kind,local_key,map_version,start_byte,end_byte,own_ranges FROM {a} EXCEPT SELECT source_id,kind,local_key,map_version,start_byte,end_byte,own_ranges FROM {b})");
            assert_eq!(
                db.query_row(&sql, [], |r| r.get::<_, i64>(0)).unwrap(),
                0,
                "{text}"
            );
        }
    }
}
