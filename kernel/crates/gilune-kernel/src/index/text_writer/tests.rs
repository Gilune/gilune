use super::*;
use gilune_document::TextSnapshot;

fn db() -> Connection {
    let db = Connection::open_in_memory().unwrap();
    crate::search_text::register(&db).unwrap();
    db.execute_batch(SCHEMA).unwrap();
    db
}

fn source(text: &str) -> (String, String) {
    let snapshot = TextSnapshot::new(text.into()).unwrap();
    (
        snapshot.version().into(),
        scoped::encode_layout(snapshot.layout()),
    )
}

fn apply(db: &Connection, text: &str, incremental: bool) -> Changes {
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    let rows = text_rows("a.md", text);
    let src = source(text);
    let changes = if incremental {
        replace(db, "a.md", &rows, Some(&src)).unwrap()
    } else {
        forget_path(db, TEXT_NAMESPACE, "a.md").unwrap();
        put(db, TEXT_NAMESPACE, "a.md", &Rows::Lines(rows, Some(src))).unwrap();
        Changes::default()
    };
    db.execute_batch("COMMIT").unwrap();
    changes
}

fn contents(db: &Connection) -> Vec<(String, Option<i64>, String, String, String)> {
    db.prepare(
        "SELECT rel_path,line,field,text,folded FROM lines ORDER BY rel_path,field,line,text",
    )
    .unwrap()
    .query_map([], |r| {
        Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
    })
    .unwrap()
    .collect::<rusqlite::Result<_>>()
    .unwrap()
}

fn hits(db: &Connection, term: &str) -> Vec<(Option<i64>, String, String)> {
    db.prepare("SELECT l.line,l.field,l.text FROM lines_fts f JOIN lines l ON l.id=f.rowid WHERE lines_fts MATCH ?1 ORDER BY l.field,l.line,l.text")
        .unwrap()
        .query_map([term], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap()
}

fn integrity(db: &Connection) {
    // rank=1 checks FTS against its external content table, not only internally.
    db.execute(
        "INSERT INTO lines_fts(lines_fts,rank) VALUES('integrity-check',1)",
        [],
    )
    .unwrap();
}

#[test]
fn one_changed_line_only_replaces_one_fts_entry_in_large_source() {
    let db = db();
    let before: String = (0..10_000)
        .map(|i| format!("- task {i} due:2026-09-01\n"))
        .collect();
    apply(&db, &before, true);
    let old_id: i64 = db
        .query_row(
            "SELECT id FROM lines WHERE field='body' AND line=1",
            [],
            |r| r.get(0),
        )
        .unwrap();
    let after = before.replace("task 5000 due:2026-09-01", "task 5000 due:2026-09-02");
    let changed = apply(&db, &after, true);
    assert_eq!(
        changed,
        Changes {
            retained: 10_002,
            moved: 0,
            inserted: 1,
            deleted: 1
        }
    );
    assert_eq!(
        old_id,
        db.query_row(
            "SELECT id FROM lines WHERE field='body' AND line=1",
            [],
            |r| r.get::<_, i64>(0)
        )
        .unwrap()
    );
    assert_eq!(
        apply(&db, &after, true),
        Changes {
            retained: 10_003,
            ..Changes::default()
        }
    );
    integrity(&db);
}

#[test]
fn shifted_duplicate_and_unicode_lines_match_full_replacement_fts() {
    let incremental = db();
    let full = db();
    for text in [
        "---\r\ntitle: Café\r\n---\r\n- duplicate 😀\r\n- duplicate 😀\r\n  - child\r\n",
        "prefix\r\n---\r\ntitle: Café\r\n---\r\n- duplicate 😀\r\n- duplicate 😀\r\n  - child\r\n",
        "prefix\n- duplicate 😀\n\n```\n- child\n```\n- duplicate 😀\n",
        "- CHILD\n- duplicate 😀\n- duplicate 😀\n- duplicate 😀\n",
        "- duplicate 😀\n- CHILD\n",
        "",
    ] {
        apply(&incremental, text, true);
        apply(&full, text, false);
        assert_eq!(contents(&incremental), contents(&full));
        for term in ["duplicate", "child", "prefix", "café", "absent"] {
            assert_eq!(
                hits(&incremental, term),
                hits(&full, term),
                "{term}: {text}"
            );
        }
        integrity(&incremental);
    }
}

#[test]
fn inserted_prefix_moves_existing_rows_without_rewriting_their_fts() {
    let db = db();
    apply(&db, "- alpha\n- duplicate\n- duplicate\n", true);
    let changes = apply(&db, "prefix\n- alpha\n- duplicate\n- duplicate\n", true);
    assert_eq!(changes.inserted, 1);
    assert_eq!(changes.deleted, 0);
    assert_eq!(changes.moved, 3);
    assert_eq!(
        hits(&db, "alpha"),
        vec![(Some(2), "body".into(), "- alpha".into())]
    );
    integrity(&db);
}

#[test]
fn replacement_keeps_stale_facts_detached_and_rollback_restores_everything() {
    let db = db();
    let before = "- alpha\n";
    apply(&db, before, true);
    let original = source(before);
    db.execute("INSERT INTO facts(rel_path,item_key,source_version,namespace,predicate,value) VALUES('a.md',0,?1,'fixture','fixture.value','alpha')", [&original.0]).unwrap();
    let target: i64 = db
        .query_row("SELECT target_id FROM facts", [], |r| r.get(0))
        .unwrap();
    // Reprocessing exactly the same source retains existing target bindings.
    apply(&db, before, true);
    assert_eq!(
        target,
        db.query_row("SELECT target_id FROM facts", [], |r| r.get::<_, i64>(0))
            .unwrap()
    );
    let old_rows = contents(&db);
    db.execute_batch("CREATE TRIGGER fail_new_text BEFORE INSERT ON text_rows WHEN NEW.text='fail' BEGIN SELECT RAISE(ABORT,'fixture failure'); END; BEGIN IMMEDIATE;").unwrap();
    assert!(replace(
        &db,
        "a.md",
        &text_rows("a.md", "fail"),
        Some(&source("fail"))
    )
    .is_err());
    db.execute_batch("ROLLBACK").unwrap();
    assert_eq!(old_rows, contents(&db));
    assert_eq!(
        target,
        db.query_row("SELECT target_id FROM facts", [], |r| r.get::<_, i64>(0))
            .unwrap()
    );
    integrity(&db);
    apply(&db, "prefix\n- alpha\n", true);
    assert_eq!(
        db.query_row("SELECT target_id FROM facts", [], |r| r
            .get::<_, Option<i64>>(0))
            .unwrap(),
        None
    );
    assert_eq!(
        db.query_row("SELECT source_version FROM facts", [], |r| r
            .get::<_, String>(0))
            .unwrap(),
        original.0
    );
    // A source-less legacy input must also remove the previous source map.
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    replace(&db, "a.md", &text_rows("a.md", "legacy"), None).unwrap();
    db.execute_batch("COMMIT").unwrap();
    assert_eq!(
        db.query_row("SELECT count(*) FROM source_maps", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
}

#[test]
fn differential_changes_are_scoped_and_require_a_transaction() {
    let db = db();
    apply(&db, "- alpha\n", true);
    let old_rows = contents(&db);
    assert!(replace(&db, "a.md", &[], None).is_err());
    assert_eq!(old_rows, contents(&db));
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    replace(
        &db,
        "b.md",
        &text_rows("b.md", "other"),
        Some(&source("other")),
    )
    .unwrap();
    db.execute_batch("COMMIT").unwrap();
    apply(&db, "", true);
    assert_eq!(
        db.query_row(
            "SELECT text FROM lines WHERE rel_path='b.md' AND field='body'",
            [],
            |r| r.get::<_, String>(0)
        )
        .unwrap(),
        "other"
    );
    integrity(&db);
}

#[test]
#[ignore = "manual release-mode differential text maintenance comparison"]
fn compare_differential_text_maintenance() {
    use std::time::Instant;
    let temporary = tempfile::tempdir().unwrap();
    for count in [100, 1_000, 10_000] {
        let baseline = Connection::open(temporary.path().join(format!("full-{count}.db"))).unwrap();
        let differential =
            Connection::open(temporary.path().join(format!("diff-{count}.db"))).unwrap();
        for db in [&baseline, &differential] {
            crate::search_text::register(db).unwrap();
            db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;")
                .unwrap();
            db.execute_batch(SCHEMA).unwrap();
        }
        let initial: String = (0..count)
            .map(|i| format!("- entry {i} date:2026-09-01 tag:example\n"))
            .collect();
        let changed = initial.replace("entry 50 date:2026-09-01", "entry 50 date:2026-10-01");
        let shifted = format!("New introductory paragraph.\n{initial}");
        for (kind, text) in [("one_value", changed), ("insert_prefix", shifted)] {
            apply(&baseline, &initial, false);
            apply(&differential, &initial, true);
            let mut samples = Vec::new();
            for trial in 0..5 {
                let mut full_ms = 0.0;
                let mut diff_ms = 0.0;
                let mut changes = Changes::default();
                // Alternate execution order to avoid always favouring one path.
                for incremental in if trial % 2 == 0 {
                    [false, true]
                } else {
                    [true, false]
                } {
                    let started = Instant::now();
                    let result = apply(
                        if incremental {
                            &differential
                        } else {
                            &baseline
                        },
                        &text,
                        incremental,
                    );
                    let ms = started.elapsed().as_secs_f64() * 1000.0;
                    if incremental {
                        diff_ms = ms;
                        changes = result;
                    } else {
                        full_ms = ms;
                    }
                }
                assert_eq!(contents(&baseline), contents(&differential));
                integrity(&differential);
                samples.push(serde_json::json!({"trial":trial,"full_ms":full_ms,"differential_ms":diff_ms,
                    "retained":changes.retained,"moved":changes.moved,"inserted":changes.inserted,"deleted":changes.deleted}));
                apply(&baseline, &initial, false);
                apply(&differential, &initial, true);
            }
            println!(
                "TEXT_MAINTENANCE {}",
                serde_json::json!({"items":count,"edit":kind,"samples":samples})
            );
        }
    }
}
