use super::*;
use gilune_document::TextSnapshot;
use rusqlite::types::Value;

fn fixture(text: &str) -> (Connection, TextSnapshot) {
    let db = Connection::open_in_memory().unwrap();
    crate::search_text::register(&db).unwrap();
    db.execute_batch(SCHEMA).unwrap();
    let snapshot = TextSnapshot::new(text.into()).unwrap();
    for path in ["a.md", "b.md"] {
        db.execute(
            "INSERT INTO source_maps(rel_path,source_version,layout) VALUES(?,?,?)",
            params![
                path,
                snapshot.version(),
                scoped::encode_layout(snapshot.layout())
            ],
        )
        .unwrap();
    }
    (db, snapshot)
}

fn fact(
    version: Option<&str>,
    line: Option<i64>,
    item: Option<i64>,
    predicate: &str,
    value: &str,
) -> FactRow {
    FactRow {
        line,
        item_key: item,
        source_version: version.map(Into::into),
        predicate: predicate.into(),
        value: value.into(),
        num: None,
        typed: true,
    }
}

fn contents(db: &Connection) -> Vec<Vec<Value>> {
    let mut stmt = db.prepare("SELECT f.namespace,f.predicate,f.value,f.num,f.typed,f.line,f.item_key,f.source_version,t.kind,t.local_key,s.rel_path,s.source_version,p.namespace,p.predicate FROM facts f LEFT JOIN index_targets t ON t.id=f.target_id LEFT JOIN source_maps s ON s.id=t.source_id LEFT JOIN index_predicates p ON p.id=f.predicate_id ORDER BY f.rowid").unwrap();
    stmt.query_map([], |r| (0..14).map(|i| r.get(i)).collect())
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap()
}

#[test]
fn batch_and_compatibility_inserts_have_identical_owned_source_bindings() {
    let text = "---\r\ntitle: test\r\n---\r\n- Parent 😀\r\n  - Child é\r\n";
    let (fast, snapshot) = fixture(text);
    let (compat, _) = fixture(text);

    let key = snapshot.layout().items[0].key as i64;
    let line = i64::from(snapshot.layout().lines[0].number);
    let mut facts = vec![
        fact(Some(snapshot.version()), None, None, "shared.name", "root"),
        fact(
            Some(snapshot.version()),
            Some(line),
            None,
            "shared.name",
            "line",
        ),
        fact(
            Some(snapshot.version()),
            None,
            Some(key),
            "shared.name",
            "item",
        ),
        fact(
            Some(snapshot.version()),
            None,
            Some(key),
            "shared.name",
            "item",
        ),
        fact(None, None, Some(key), "shared.name", "legacy"),
        fact(Some("older"), None, Some(key), "shared.name", "stale"),
        fact(
            Some(snapshot.version()),
            Some(line),
            Some(key),
            "shared.name",
            "mixed-anchor",
        ),
        fact(
            Some(snapshot.version()),
            None,
            Some(i64::MAX),
            "shared.name",
            "missing-anchor",
        ),
        fact(Some(snapshot.version()), None, None, "other.number", "-2.5"),
        fact(
            Some(snapshot.version()),
            None,
            None,
            "other.number",
            "invalid",
        ),
    ];
    facts[8].num = Some(-2.5);
    facts[9].typed = false;
    for db in [&fast, &compat] {
        db.execute_batch("CREATE TABLE fixture_updates(n); CREATE TRIGGER fixture_update AFTER UPDATE ON fact_values BEGIN INSERT INTO fixture_updates VALUES(1); END; BEGIN IMMEDIATE").unwrap();
    }
    for namespace in ["fixture.one", "fixture.two"] {
        put_facts(&fast, namespace, "b.md", &facts, Some(snapshot.version())).unwrap();
        for f in &facts {
            compat.execute("INSERT INTO facts(rel_path,line,item_key,source_version,namespace,predicate,value,num,typed) VALUES(?,?,?,?,?,?,?,?,?)",params!["b.md",f.line,f.item_key,f.source_version,namespace,f.predicate,f.value,f.num,f.typed]).unwrap();
        }
    }
    for db in [&fast, &compat] {
        db.execute_batch("COMMIT").unwrap();
    }
    assert_eq!(contents(&fast), contents(&compat));
    assert_eq!(
        fast.query_row("SELECT count(*) FROM fixture_updates", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0,
        "direct batch inserts must not rewrite every fact through the compatibility trigger"
    );
    assert_eq!(
        compat
            .query_row("SELECT count(*) FROM fixture_updates", [], |r| r
                .get::<_, usize>(0))
            .unwrap(),
        0
    );
    assert_eq!(
        fast.query_row(
            "SELECT count(*) FROM index_predicates WHERE predicate='shared.name'",
            [],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        2,
        "identical predicate text retains distinct owning namespaces"
    );
    assert_eq!(fast.query_row("SELECT count(target_id) FROM facts WHERE value IN ('legacy','stale','mixed-anchor','missing-anchor')",[],|r|r.get::<_,i64>(0)).unwrap(),0);
}

#[test]
fn large_batches_and_later_source_replacements_resolve_only_current_targets() {
    let (db, snapshot) = fixture(&"- entry\n".repeat(150));
    let facts: Vec<_> = snapshot
        .layout()
        .items
        .iter()
        .map(|i| {
            fact(
                Some(snapshot.version()),
                None,
                Some(i.key as i64),
                "fixture.present",
                "yes",
            )
        })
        .collect();
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    put_facts(&db, "fixture", "b.md", &facts, Some(snapshot.version())).unwrap();
    db.execute_batch("COMMIT").unwrap();
    assert_eq!(db.query_row("SELECT count(*) FROM facts f JOIN index_targets t ON t.id=f.target_id JOIN source_maps s ON s.id=t.source_id WHERE s.rel_path='b.md'",[],|r|r.get::<_,usize>(0)).unwrap(),150);
    let old: Vec<i64> = db
        .prepare("SELECT target_id FROM facts ORDER BY rowid")
        .unwrap()
        .query_map([], |r| r.get(0))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap();
    // Replacing the layout can reuse source/version while changing private IDs.
    db.execute(
        "UPDATE source_maps SET layout=layout WHERE rel_path='b.md'",
        [],
    )
    .unwrap();
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    forget_path(&db, "fixture", "b.md").unwrap();
    put_facts(&db, "fixture", "b.md", &facts, Some(snapshot.version())).unwrap();
    db.execute_batch("COMMIT").unwrap();
    let now: Vec<i64> = db
        .prepare("SELECT target_id FROM facts ORDER BY rowid")
        .unwrap()
        .query_map([], |r| r.get(0))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap();
    assert!(
        now.iter().all(|id| !old.contains(id)),
        "bindings are not reused across transactions"
    );
}

#[test]
fn failed_batch_rolls_back_facts_dictionary_and_receipt_together() {
    let (db, snapshot) = fixture("- entry\n");
    let original = [fact(
        Some(snapshot.version()),
        None,
        None,
        "fixture.old",
        "kept",
    )];
    assert!(put_facts(&db, "fixture", "a.md", &original, Some(snapshot.version())).is_err());
    db.execute_batch("BEGIN IMMEDIATE").unwrap();
    put_facts(&db, "fixture", "a.md", &original, Some(snapshot.version())).unwrap();
    db.execute_batch("COMMIT").unwrap();
    let before = contents(&db);
    db.execute_batch("CREATE TRIGGER fixture_refuse BEFORE INSERT ON fact_values WHEN NEW.value='reject' BEGIN SELECT RAISE(ABORT,'fixture failure'); END; BEGIN IMMEDIATE").unwrap();
    forget_path(&db, "fixture", "a.md").unwrap();
    let changed = [
        fact(
            Some(snapshot.version()),
            None,
            None,
            "fixture.new",
            "accepted",
        ),
        fact(
            Some(snapshot.version()),
            None,
            None,
            "fixture.new",
            "reject",
        ),
    ];
    assert!(put_facts(&db, "fixture", "a.md", &changed, Some(snapshot.version())).is_err());
    db.execute_batch("ROLLBACK").unwrap();
    assert_eq!(contents(&db), before);
    assert_eq!(
        db.query_row(
            "SELECT count(*) FROM index_predicates WHERE predicate='fixture.new'",
            [],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        0
    );
    assert_eq!(
        db.query_row(
            "SELECT source_version FROM fact_sources WHERE namespace='fixture' AND rel_path='a.md'",
            [],
            |r| r.get::<_, String>(0)
        )
        .unwrap(),
        snapshot.version()
    );
}
