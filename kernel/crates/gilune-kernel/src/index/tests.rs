//! Evidence for ADR 0020 § 8.3 and § 8.4, and for the second-order
//! cases each rule dragged in behind it.
//!
//! Predicates here are `test.colour`, `test.weight`, `test.status`. No
//! domain word appears anywhere in the kernel, and a test that reached
//! for one would be the first crack in that (constitution rule 4).

use super::*;
use crate::store::Store;
use std::sync::atomic::{AtomicUsize, Ordering};

#[test]
fn a_failed_read_keeps_existing_rows_and_retries_without_another_file_edit() {
    let (_dir, store) = store();
    std::fs::write(store.vault().join("read.md"), "preserved words").expect("write");
    store.reconcile().expect("index");
    let epoch = store.change_epoch().expect("epoch");
    let before = store
        .with_index(|db| cursor_of(db, TEXT_NAMESPACE))
        .expect("cursor");
    std::fs::write(
        store.vault().join("read.md"),
        "preserved words and pending change",
    )
    .expect("edit");
    // Publish the edit without maintenance, so the read refusal is injected
    // after a real pending change, beyond the already indexed prefix.
    store
        .conn()
        .execute_batch("BEGIN IMMEDIATE;")
        .expect("begin");
    crate::changes::record(
        store.conn(),
        crate::changes::Change {
            kind: ChangeKind::Modified,
            rel_path: "read.md",
            previous_rel_path: None,
            object_id: None,
            revision_id: None,
            cause: crate::changes::ChangeCause::ExternalEdit,
        },
    )
    .expect("feed");
    store.conn().execute_batch("COMMIT;").expect("commit");
    let page = store
        .changes_since(&Cursor { epoch, seq: before }, 100)
        .expect("feed");
    // A portable injected I/O refusal exercises exactly the read-failure path.
    let mut refused = Notes::default();
    refused
        .0
        .insert("read.md".into(), Err("file temporarily locked".into()));
    let mut plan = store
        .plan(TEXT_NAMESPACE, Source::Text, &page.changes, &mut refused)
        .expect("plan");
    plan.expected_cursor = store
        .with_index(|db| cursor_of(db, TEXT_NAMESPACE))
        .expect("cursor");
    plan.expected_version = 0;
    assert!(plan.stopped);
    store
        .commit_plan(TEXT_NAMESPACE, plan, &mut IndexPass::default())
        .expect("record retry");
    assert_eq!(notes(&store, &[text("preserved")]), vec!["read.md"]);
    assert!(notes(&store, &[text("pending")]).is_empty());
    assert_eq!(
        store
            .with_index(|db| cursor_of(db, TEXT_NAMESPACE))
            .expect("cursor"),
        before,
        "a read refusal cannot regress or advance the acknowledged prefix"
    );
    assert!(store
        .index_status()
        .expect("status")
        .namespaces
        .iter()
        .any(|n| n.last_error.is_some()));
    store.advance_index().expect("retry");
    assert_eq!(notes(&store, &[text("preserved")]), vec!["read.md"]);
    assert_eq!(notes(&store, &[text("pending")]), vec!["read.md"]);
    assert!(store
        .index_status()
        .expect("status")
        .namespaces
        .iter()
        .all(|n| n.last_error.is_none()));
}

#[test]
fn a_stale_maintenance_plan_cannot_replace_a_newer_handles_rows_or_cursor() {
    let (_dir, first) = store();
    std::fs::write(first.vault().join("race.md"), "old value").expect("write");
    first.reconcile().expect("index");
    let expected = first
        .with_index(|db| cursor_of(db, TEXT_NAMESPACE))
        .expect("cursor");
    let stale = Plan {
        expected_cursor: expected,
        expected_version: 0,
        cursor: expected,
        failure: None,
        stopped: false,
        steps: vec![(
            expected,
            Step::Put {
                rel_path: "race.md".into(),
                rows: Rows::Lines(text_rows("race.md", "old value"), None),
            },
        )],
    };
    let second = Store::open(first.vault()).expect("second handle");
    std::fs::write(first.vault().join("race.md"), "newer value").expect("edit");
    second.reconcile().expect("newer pass");
    assert!(!first
        .commit_plan(TEXT_NAMESPACE, stale, &mut IndexPass::default())
        .expect("reject stale"));
    assert_eq!(notes(&first, &[text("newer")]), vec!["race.md"]);
    assert!(
        first
            .with_index(|db| cursor_of(db, TEXT_NAMESPACE))
            .expect("cursor")
            > expected
    );
}

#[test]
#[cfg(windows)]
fn a_windows_file_lock_during_rebuild_is_reported_and_retried_after_unlock() {
    use std::os::windows::fs::OpenOptionsExt;
    let (_dir, store) = store();
    std::fs::write(store.vault().join("locked.md"), "recoverable").expect("write");
    store.reconcile().expect("index");
    let lock = std::fs::OpenOptions::new()
        .read(true)
        .share_mode(0)
        .open(store.vault().join("locked.md"))
        .expect("lock");
    let pass = store.rebuild_index(None).expect("rebuild");
    assert!(!pass.failures.is_empty());
    drop(lock);
    store.reconcile().expect("retry without editing");
    assert_eq!(notes(&store, &[text("recoverable")]), vec!["locked.md"]);
}

fn store() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().expect("tempdir");
    let store = Store::open(dir.path()).expect("open");
    (dir, store)
}

#[test]
fn a_pass_cannot_acknowledge_an_edit_newer_than_its_cached_note() {
    let (_dir, first) = store();
    std::fs::write(first.vault().join("race.md"), "colour=old").expect("write");
    first.reconcile().expect("initial index");
    let (indexer, _) = Marks::new("test.colour", "colour=");
    first
        .register_indexer("test.colours", 1, &[], indexer)
        .expect("register");
    let registration = first.indexers.borrow()[0].clone();
    let tip = first
        .with_index(|db| cursor_of(db, TEXT_NAMESPACE))
        .expect("tip");
    let epoch = first.change_epoch().expect("epoch");
    let mut cached = Notes::default();
    cached.of(&first, "race.md").expect("cache old bytes");
    let second = Store::open(first.vault()).expect("second handle");
    std::fs::write(first.vault().join("race.md"), "colour=newer").expect("edit");
    second.reconcile().expect("record edit during first pass");
    first
        .advance_namespace(
            "test.colours",
            Source::Plugin(&registration),
            &epoch,
            tip,
            &mut cached,
            &mut IndexPass::default(),
        )
        .expect("finish bounded pass");
    assert_eq!(
        first
            .with_index(|db| cursor_of(db, "test.colours"))
            .expect("cursor"),
        tip
    );
    first.advance_index().expect("next pass reads new bytes");
    assert_eq!(
        notes(&first, &[eq("test.colour", "newer")]),
        vec!["race.md"]
    );
}

#[test]
fn an_old_handles_indexer_cannot_write_under_a_new_versions_label() {
    let (_dir, first) = store();
    let (old, _) = Marks::new("test.colour", "old=");
    first
        .register_indexer("test.colours", 1, &[], old)
        .expect("old registration");
    std::fs::write(first.vault().join("version.md"), "old=red new=blue").expect("write");
    first.reconcile().expect("old pass");
    let second = Store::open(first.vault()).expect("second handle");
    let (new, _) = Marks::new("test.colour", "new=");
    second
        .register_indexer("test.colours", 2, &[], new)
        .expect("new registration");
    second.advance_index().expect("new pass");
    std::fs::write(first.vault().join("version.md"), "old=green new=black").expect("edit");
    first.reconcile().expect("old handle observes edit");
    assert!(!first
        .advance_index()
        .expect("refused old version")
        .failures
        .is_empty());
    assert!(notes(&first, &[eq("test.colour", "green")]).is_empty());
    second.advance_index().expect("new handle catches up");
    assert_eq!(
        notes(&first, &[eq("test.colour", "black")]),
        vec!["version.md"]
    );
}

/// A test indexer that reads `<marker><value>` out of a note's body:
/// `colour=red` on the third line of the body becomes
/// `test.colour = red` anchored to line 3.
struct Marks {
    predicate: String,
    marker: String,
    /// A fact about the note rather than about a line.
    note_level: bool,
    calls: Arc<AtomicUsize>,
    /// A path this indexer will not read, which is a foreign
    /// implementation throwing.
    refuse: Option<String>,
}

impl Marks {
    fn new(predicate: &str, marker: &str) -> (Arc<Marks>, Arc<AtomicUsize>) {
        let calls = Arc::new(AtomicUsize::new(0));
        let indexer = Arc::new(Marks {
            predicate: predicate.to_string(),
            marker: marker.to_string(),
            note_level: false,
            calls: calls.clone(),
            refuse: None,
        });
        (indexer, calls)
    }

    fn note_level(predicate: &str, marker: &str) -> Arc<Marks> {
        Arc::new(Marks {
            predicate: predicate.to_string(),
            marker: marker.to_string(),
            note_level: true,
            calls: Arc::new(AtomicUsize::new(0)),
            refuse: None,
        })
    }

    fn refusing(predicate: &str, marker: &str, rel_path: &str) -> (Arc<Marks>, Arc<AtomicUsize>) {
        let calls = Arc::new(AtomicUsize::new(0));
        let indexer = Arc::new(Marks {
            predicate: predicate.to_string(),
            marker: marker.to_string(),
            note_level: false,
            calls: calls.clone(),
            refuse: Some(rel_path.to_string()),
        });
        (indexer, calls)
    }
}

impl NoteIndexer for Marks {
    fn index(&self, rel_path: &str, text: &str) -> std::result::Result<Vec<Fact>, String> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if self.refuse.as_deref() == Some(rel_path) {
            return Err(format!("this indexer will not read {rel_path}"));
        }
        let mut facts = Vec::new();
        for (index, line) in text.split('\n').enumerate() {
            let Some(at) = line.find(&self.marker) else {
                continue;
            };
            let value = line[at + self.marker.len()..]
                .split_whitespace()
                .next()
                .unwrap_or_default()
                .to_string();
            if value.is_empty() {
                continue;
            }
            facts.push(Fact {
                line: (!self.note_level).then_some(index as u32 + 1),
                predicate: self.predicate.clone(),
                value,
            });
        }
        Ok(facts)
    }
}

/// An indexer that anchors everything to a line the note does not have.
struct BeyondTheEnd;

impl NoteIndexer for BeyondTheEnd {
    fn index(&self, _rel_path: &str, _text: &str) -> std::result::Result<Vec<Fact>, String> {
        Ok(vec![Fact {
            line: Some(9_999),
            predicate: "test.colour".to_string(),
            value: "green".to_string(),
        }])
    }
}

fn kinds(predicate: &str, kind: ValueKind) -> Vec<PredicateKind> {
    vec![PredicateKind {
        predicate: predicate.to_string(),
        kind,
    }]
}

/// A note captured and projected, answered by the path it landed at.
fn note(store: &Store, text: &str) -> String {
    let receipt = store
        .capture_bytes("txt", text.as_bytes())
        .expect("capture");
    store.project().expect("project");
    receipt.rel_path.replace(".txt", ".md")
}

fn body(store: &Store, rel_path: &str) -> String {
    let text = std::fs::read_to_string(store.vault().join(rel_path)).expect("read the note");
    body_of(&text)
}

/// Rewrite a note's prose the way a person editing it in another app
/// would, and let reconciliation take it in.
fn rewrite(store: &Store, rel_path: &str, prose: &str) {
    let path = store.vault().join(rel_path);
    let text = std::fs::read_to_string(&path).expect("read the note");
    let head = text
        .split_inclusive("\n---\n")
        .next()
        .expect("front matter")
        .to_string();
    std::fs::write(&path, format!("{head}{prose}")).expect("write the note");
    store.reconcile().expect("reconcile");
}

fn eq(predicate: &str, value: &str) -> Constraint {
    Constraint::Fact {
        predicate: predicate.to_string(),
        op: ConstraintOp::Eq,
        value: value.to_string(),
    }
}

fn text(query: &str) -> Constraint {
    Constraint::Text {
        target: TextTarget::Any,
        query: query.to_string(),
    }
}

fn lines(store: &Store, constraints: &[Constraint]) -> Vec<(String, u32)> {
    store
        .query(constraints, QueryScope::Line, 100)
        .expect("query")
        .into_iter()
        .map(|anchor| (anchor.rel_path, anchor.line.unwrap_or_default()))
        .collect()
}

/// What one namespace said about a note. The kernel reads a note's front
/// matter into its own namespace beside whatever a plugin contributes, so
/// a test about a plugin's facts asks for that plugin's facts.
fn said(store: &Store, rel_path: &str, namespace: &str) -> Vec<crate::index::IndexedFact> {
    store
        .facts_of(rel_path)
        .expect("facts")
        .into_iter()
        .filter(|fact| fact.namespace == namespace)
        .collect()
}

fn notes(store: &Store, constraints: &[Constraint]) -> Vec<String> {
    store
        .query(constraints, QueryScope::Note, 100)
        .expect("query")
        .into_iter()
        .map(|anchor| anchor.rel_path)
        .collect()
}

/// Everything the index holds, as one comparable string. Deliberately
/// not a hash: when the property test fails, the difference should be
/// readable.
fn snapshot(store: &Store) -> String {
    store
        .with_index(|db| {
            let mut out = String::new();
            {
                let mut stmt = db.prepare(
                    "SELECT namespace, rel_path, IFNULL(line, -1), predicate, value,
                            IFNULL(num, 0), typed
                       FROM facts ORDER BY 1, 2, 3, 4, 5",
                )?;
                let rows = stmt.query_map([], |row| {
                    Ok(format!(
                        "fact {} {} {} {} {} {} {}\n",
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                        row.get::<_, f64>(5)?,
                        row.get::<_, i64>(6)?,
                    ))
                })?;
                for row in rows {
                    out.push_str(&row?);
                }
            }
            {
                let mut stmt = db.prepare(
                    "SELECT rel_path, IFNULL(line, -1), field, text FROM lines
                      ORDER BY 1, 2, 3, 4",
                )?;
                let rows = stmt.query_map([], |row| {
                    Ok(format!(
                        "line {} {} {} {}\n",
                        row.get::<_, String>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                    ))
                })?;
                for row in rows {
                    out.push_str(&row?);
                }
            }
            Ok(out)
        })
        .expect("snapshot")
}

// ------------------------------------------------------ the database itself

#[test]
fn fts5_with_the_trigram_tokenizer_is_in_the_sqlite_this_kernel_bundles() {
    // The whole full-text half of ADR 0020 § 2.3 rests on this being
    // compiled in, and on `trigram` — which is where the substring
    // semantics the shipped search language already has come from.
    let conn = rusqlite::Connection::open_in_memory().expect("memory database");
    conn.execute_batch(
        "CREATE VIRTUAL TABLE probe USING fts5(text, tokenize='trigram');
         INSERT INTO probe(text) VALUES ('The Plasterer Arrives');",
    )
    .expect("fts5 with the trigram tokenizer");
    let found: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM probe WHERE probe MATCH '\"aster\"'",
            [],
            |row| row.get(0),
        )
        .expect("substring match");
    assert_eq!(
        found, 1,
        "trigram matches inside a word, which is what a substring search is"
    );
    let folded: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM probe WHERE probe MATCH '\"PLASTERER\"'",
            [],
            |row| row.get(0),
        )
        .expect("case-folded match");
    assert_eq!(
        folded, 1,
        "and it folds case, as every search in the app does"
    );
}

#[test]
fn the_kernels_own_text_namespace_is_not_a_plugins_to_register() {
    let (_dir, store) = store();
    let (colour, _) = Marks::new("test.colour", "colour=");
    assert!(store
        .register_indexer(TEXT_NAMESPACE, 1, &[], colour.clone())
        .is_err());
    assert!(store.register_indexer("  ", 1, &[], colour).is_err());
}

// ------------------------------------------------ two indexers, one anchor

#[test]
fn two_indexers_that_share_nothing_join_on_one_line() {
    // ADR 0020 § 8.3, and the reason facts are a kernel table: neither
    // of these has heard of the other, and nobody wrote the join.
    let (_dir, store) = store();
    let (colour, colour_calls) = Marks::new("test.colour", "colour=");
    let (weight, weight_calls) = Marks::new("test.weight", "weight=");
    store
        .register_indexer(
            "test.colours",
            1,
            &kinds("test.colour", ValueKind::Text),
            colour,
        )
        .expect("register");
    store
        .register_indexer(
            "test.weights",
            1,
            &kinds("test.weight", ValueKind::Number),
            weight,
        )
        .expect("register");

    let rel = note(
        &store,
        "first line\ncolour=red weight=12 the line both describe\ncolour=blue elsewhere",
    );
    assert_eq!(colour_calls.load(Ordering::SeqCst), 1);
    assert_eq!(weight_calls.load(Ordering::SeqCst), 1);

    assert_eq!(
        lines(&store, &[eq("test.colour", "red"), eq("test.weight", "12")]),
        vec![(rel.clone(), 2)],
        "exactly the line both describe, and no other"
    );
    assert_eq!(
        notes(&store, &[eq("test.colour", "red"), eq("test.weight", "12")]),
        vec![rel.clone()]
    );
    // The second-order half: the two facts on different lines of one
    // note are not a hit, because a join on the anchor is a join on the
    // anchor. `colour=blue` is line 3 and there is no weight there.
    assert!(
        lines(
            &store,
            &[eq("test.colour", "blue"), eq("test.weight", "12")]
        )
        .is_empty(),
        "a note that holds both is not a line that holds both"
    );
    assert!(notes(
        &store,
        &[eq("test.colour", "blue"), eq("test.weight", "12")]
    )
    .is_empty());
}

#[test]
fn a_note_level_fact_satisfies_every_line_of_its_note() {
    let (_dir, store) = store();
    let (colour, _) = Marks::new("test.colour", "colour=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    store
        .register_indexer(
            "test.statuses",
            1,
            &[],
            Marks::note_level("test.status", "status="),
        )
        .expect("register");

    let rel = note(&store, "status=draft\ncolour=red one\ncolour=red two");
    let both = [eq("test.status", "draft"), eq("test.colour", "red")];
    assert_eq!(
        lines(&store, &both),
        vec![(rel.clone(), 2), (rel.clone(), 3)],
        "a fact about the note holds on every line of it"
    );
    assert_eq!(notes(&store, &both), vec![rel.clone()]);

    // And the second-order case the note scope exists for: a query made
    // only of note-level facts names no line, so it answers nothing at
    // line scope and answers the note at note scope.
    let whole = [eq("test.status", "draft")];
    assert!(
        lines(&store, &whole).is_empty(),
        "a question about a note is not a question about a line"
    );
    assert_eq!(notes(&store, &whole), vec![rel]);
}

#[test]
fn removing_a_plugin_takes_its_namespace_and_leaves_every_other() {
    let (_dir, store) = store();
    let (colour, _) = Marks::new("test.colour", "colour=");
    let (weight, _) = Marks::new("test.weight", "weight=");
    store
        .register_plugin(&crate::plugins::PluginDescriptor {
            plugin_id: "test.colours".to_string(),
            name: "Colours".to_string(),
            class: crate::plugins::PluginClass::Index,
            profile: crate::plugins::PluginProfile::Bundled,
            required: false,
            processor_id: None,
            subscribes: None,
            emits: Vec::new(),
            capabilities: Vec::new(),
            contributions: Vec::new(),
        })
        .expect("register the plugin");
    store
        .register_indexer("test.colours.lines", 1, &[], colour)
        .expect("register");
    store
        .register_indexer("test.weights", 1, &[], weight)
        .expect("register");
    let rel = note(&store, "colour=red weight=12");
    assert_eq!(
        lines(&store, &[eq("test.colour", "red")]),
        vec![(rel.clone(), 1)]
    );

    store.remove_plugin("test.colours").expect("remove");

    assert!(
        lines(&store, &[eq("test.colour", "red")]).is_empty(),
        "a namespace under the plugin's id goes with the plugin"
    );
    assert_eq!(
        lines(&store, &[eq("test.weight", "12")]),
        vec![(rel.clone(), 1)],
        "and nobody else's facts move"
    );
    assert_eq!(
        notes(&store, &[text("colour")]),
        vec![rel.clone()],
        "the note itself is untouched, text index included"
    );
    assert!(std::fs::read_to_string(store.vault().join(&rel))
        .expect("the note is still there")
        .contains("colour=red"));
}

#[test]
fn a_namespace_that_merely_starts_with_the_same_letters_is_not_the_plugins() {
    // The second-order half of the removal rule: `test.coloursextra` is
    // not a namespace of `test.colours`, and a prefix match that used
    // LIKE would have taken it.
    let (_dir, store) = store();
    let (mine, _) = Marks::new("test.colour", "colour=");
    let (theirs, _) = Marks::new("test.weight", "weight=");
    store
        .register_plugin(&crate::plugins::PluginDescriptor {
            plugin_id: "test.colours".to_string(),
            name: "Colours".to_string(),
            class: crate::plugins::PluginClass::Index,
            profile: crate::plugins::PluginProfile::Bundled,
            required: false,
            processor_id: None,
            subscribes: None,
            emits: Vec::new(),
            capabilities: Vec::new(),
            contributions: Vec::new(),
        })
        .expect("register the plugin");
    store
        .register_indexer("test.colours", 1, &[], mine)
        .expect("register");
    store
        .register_indexer("test.coloursextra", 1, &[], theirs)
        .expect("register");
    let rel = note(&store, "colour=red weight=12");

    store.remove_plugin("test.colours").expect("remove");
    assert!(lines(&store, &[eq("test.colour", "red")]).is_empty());
    assert_eq!(
        lines(&store, &[eq("test.weight", "12")]),
        vec![(rel, 1)],
        "a namespace whose name merely begins the same way belongs to somebody else"
    );
}

#[test]
fn a_version_change_re_derives_one_namespace_and_asks_the_other_nothing() {
    let (_dir, store) = store();
    let (colour, colour_calls) = Marks::new("test.colour", "colour=");
    let (weight, weight_calls) = Marks::new("test.weight", "weight=");
    store
        .register_indexer("test.colours", 1, &[], colour.clone())
        .expect("register");
    store
        .register_indexer("test.weights", 1, &[], weight)
        .expect("register");
    let rel = note(&store, "colour=red weight=12");
    assert_eq!(colour_calls.load(Ordering::SeqCst), 1);
    assert_eq!(weight_calls.load(Ordering::SeqCst), 1);

    store
        .register_indexer("test.colours", 2, &[], colour)
        .expect("a new version of the same indexer");
    assert!(
        lines(&store, &[eq("test.colour", "red")]).is_empty(),
        "the old version's facts are gone the moment the version changes"
    );
    let pass = store.advance_index().expect("advance");
    assert_eq!(pass.indexed, 1, "one namespace re-derived, over one note");
    assert_eq!(colour_calls.load(Ordering::SeqCst), 2);
    assert_eq!(
        weight_calls.load(Ordering::SeqCst),
        1,
        "the observer's whole point: the other indexer was not asked anything"
    );
    assert_eq!(
        lines(&store, &[eq("test.colour", "red")]),
        vec![(rel.clone(), 1)]
    );
    assert_eq!(lines(&store, &[eq("test.weight", "12")]), vec![(rel, 1)]);
}

// ----------------------------------------------- processes and namespaces

#[test]
fn a_store_with_no_indexer_never_touches_another_namespaces_facts() {
    // The command line's shape: it projects and reconciles like anything
    // else, and it carries no plugin implementation at all.
    let dir = tempfile::tempdir().expect("tempdir");
    let app = Store::open(dir.path()).expect("open");
    let (colour, calls) = Marks::new("test.colour", "colour=");
    app.register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    let rel = note(&app, "colour=red");
    assert_eq!(calls.load(Ordering::SeqCst), 1);

    let tool = Store::open(dir.path()).expect("a second handle, with no indexers");
    tool.capture_bytes("txt", b"colour=blue from the tool")
        .expect("capture");
    tool.project().expect("project");
    tool.reconcile().expect("reconcile");

    assert_eq!(
        calls.load(Ordering::SeqCst),
        1,
        "a process with no implementation ran none"
    );
    assert_eq!(
        lines(&tool, &[eq("test.colour", "red")]),
        vec![(rel, 1)],
        "and deleted nothing it could not have re-derived"
    );
    assert!(
        lines(&tool, &[eq("test.colour", "blue")]).is_empty(),
        "the tool's own note is simply not indexed by a namespace it cannot run"
    );
    // The app catches up when it next passes, because the namespace's
    // cursor is where the app left it and not where the tool got to.
    app.reconcile().expect("reconcile");
    assert_eq!(calls.load(Ordering::SeqCst), 2);
    assert_eq!(lines(&app, &[eq("test.colour", "blue")]).len(), 1);
}

#[test]
fn the_kernels_text_index_is_maintained_by_whichever_process_passes() {
    let dir = tempfile::tempdir().expect("tempdir");
    let app = Store::open(dir.path()).expect("open");
    let tool = Store::open(dir.path()).expect("open");
    let rel = note(&tool, "the tool wrote this sentence");
    assert_eq!(
        notes(&app, &[text("wrote this")]),
        vec![rel],
        "a process with no plugin still keeps the text index current"
    );
}

#[test]
fn a_second_handle_opens_the_index_that_is_there_rather_than_building_another() {
    // The rule the epoch and version check exists to enforce, and the
    // one it is easiest to break silently: a stamp written as a number
    // and read back as text compares unequal for ever, so every handle
    // quietly discards the last one's work and nothing looks wrong.
    let dir = tempfile::tempdir().expect("tempdir");
    let (colour, calls) = Marks::new("test.colour", "colour=");
    let first = Store::open(dir.path()).expect("open");
    first
        .register_indexer("test.colours", 1, &[], colour.clone())
        .expect("register");
    note(&first, "colour=red");
    assert_eq!(calls.load(Ordering::SeqCst), 1);

    let second = Store::open(dir.path()).expect("open");
    second
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    second.reconcile().expect("reconcile");
    assert_eq!(
        calls.load(Ordering::SeqCst),
        1,
        "the second handle found the index already caught up"
    );
    assert_eq!(
        second.index_status().expect("status").epoch,
        first.index_status().expect("status").epoch
    );
}

#[test]
fn two_processes_each_maintain_their_own_namespace_and_neither_wipes_the_other() {
    let dir = tempfile::tempdir().expect("tempdir");
    let one = Store::open(dir.path()).expect("open");
    let two = Store::open(dir.path()).expect("open");
    let (colour, _) = Marks::new("test.colour", "colour=");
    let (weight, _) = Marks::new("test.weight", "weight=");
    one.register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    two.register_indexer("test.weights", 1, &[], weight)
        .expect("register");

    let rel = note(&one, "colour=red weight=12");
    two.reconcile().expect("the second process passes too");

    assert_eq!(
        lines(&one, &[eq("test.colour", "red")]),
        vec![(rel.clone(), 1)]
    );
    assert_eq!(
        lines(&two, &[eq("test.weight", "12")]),
        vec![(rel.clone(), 1)]
    );
    assert_eq!(
        lines(&one, &[eq("test.colour", "red"), eq("test.weight", "12")]),
        vec![(rel, 1)],
        "two processes, two namespaces, one anchor, and the join still runs"
    );
}

// ---------------------------------------------------- the cost of a change

#[test]
fn one_edit_costs_one_indexer_call_and_one_notes_worth_of_text_rows() {
    // ADR 0020 § 8.5, in the small: an observer counts, and reads are
    // counted too — nothing may be read that the feed did not name.
    let (_dir, store) = store();
    let (colour, calls) = Marks::new("test.colour", "colour=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    let first = note(&store, "colour=red one");
    let second = note(&store, "colour=blue two");
    let third = note(&store, "colour=green three");
    assert_eq!(calls.load(Ordering::SeqCst), 3);
    // One settling pass, for the reason K2's own tests take one: a file
    // written and recorded inside the same clock tick is racy by git's
    // rule and is hashed until its record is re-armed.
    store.reconcile().expect("settle");

    let before = store.index_status().expect("status").lines;
    crate::probe::reset_file_reads();
    rewrite(&store, &second, "colour=black two, again\nand another line");
    assert_eq!(
        calls.load(Ordering::SeqCst),
        4,
        "one edit, one indexer call, whatever the size of the vault"
    );
    assert_eq!(
        crate::probe::file_reads(),
        2,
        "the edited note read twice — once by reconciliation, once by the \
         index — and nothing else read at all: the index never reads a file \
         the feed did not name"
    );
    assert_eq!(
        store.index_status().expect("status").lines,
        before + 1,
        "and only that note's lines were replaced"
    );
    assert_eq!(
        lines(&store, &[eq("test.colour", "black")]),
        vec![(second, 1)]
    );
    assert_eq!(
        lines(&store, &[eq("test.colour", "red")]).len(),
        1,
        "{first} kept"
    );
    assert_eq!(
        lines(&store, &[eq("test.colour", "green")]).len(),
        1,
        "{third} kept"
    );
}

#[test]
fn several_edits_to_one_note_in_one_pass_are_one_indexer_call() {
    // The second-order half: the index derives from what is on disk now,
    // so a note that changed five times before anybody caught up is read
    // once, not five times.
    let (_dir, store) = store();
    let rel = note(&store, "the first words");
    let (colour, calls) = Marks::new("test.colour", "colour=");
    for round in 0..5 {
        rewrite(&store, &rel, &format!("colour=red round {round} of it"));
    }
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    let pass = store.advance_index().expect("advance");
    assert_eq!(pass.indexed, 1);
    assert_eq!(
        calls.load(Ordering::SeqCst),
        1,
        "six changes to one note, one reading of it"
    );
    assert_eq!(lines(&store, &[eq("test.colour", "red")]), vec![(rel, 1)]);
}

#[test]
fn a_note_that_only_moved_carries_its_facts_across_without_being_read_again() {
    let (_dir, store) = store();
    let (colour, calls) = Marks::new("test.colour", "colour=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    let rel = note(&store, "colour=red the words do not change");
    assert_eq!(calls.load(Ordering::SeqCst), 1);

    let moved = "Moved/elsewhere.md";
    std::fs::create_dir_all(store.vault().join("Moved")).expect("folder");
    std::fs::rename(store.vault().join(&rel), store.vault().join(moved)).expect("move");
    let summary = store.reconcile().expect("reconcile");
    assert_eq!(summary.renames, 1);

    assert_eq!(
        calls.load(Ordering::SeqCst),
        1,
        "a move changes where a note is, not what it says"
    );
    assert_eq!(
        lines(&store, &[eq("test.colour", "red")]),
        vec![(moved.to_string(), 1)],
        "and the facts followed it"
    );
    assert_eq!(
        notes(&store, &[text("do not change")]),
        vec![moved.to_string()],
        "the text index followed too, name and path included"
    );
    assert_eq!(
        notes(
            &store,
            &[Constraint::Text {
                target: TextTarget::Path,
                query: "Moved/".to_string()
            }]
        ),
        vec![moved.to_string()]
    );
}

#[test]
fn a_note_edited_after_it_moved_in_the_same_batch_is_read_again() {
    // The second-order case a re-key drags in. Rows carried across by a
    // re-key are as old as the pass that wrote them, so treating the new
    // path as up to date would make an edit later in the same batch
    // invisible — for ever, because the change has already gone past.
    //
    // Both changes reach one batch because a second process makes them:
    // its passes advance the kernel's text index and leave this
    // namespace's cursor exactly where the first process left it.
    let dir = tempfile::tempdir().expect("tempdir");
    let app = Store::open(dir.path()).expect("open");
    let (colour, calls) = Marks::new("test.colour", "colour=");
    app.register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    let rel = note(&app, "colour=red before any of this");
    assert_eq!(calls.load(Ordering::SeqCst), 1);

    let tool = Store::open(dir.path()).expect("a second process");
    let moved = "Moved/elsewhere.md";
    std::fs::create_dir_all(tool.vault().join("Moved")).expect("folder");
    std::fs::rename(tool.vault().join(&rel), tool.vault().join(moved)).expect("move");
    tool.reconcile().expect("reconcile the move");
    rewrite(&tool, moved, "colour=black and then somebody edited it");
    assert_eq!(
        calls.load(Ordering::SeqCst),
        1,
        "the other process carries no implementation of this namespace"
    );

    app.advance_index().expect("one batch holding both changes");
    assert_eq!(
        lines(&app, &[eq("test.colour", "black")]),
        vec![(moved.to_string(), 1)],
        "the edit after the move was read"
    );
    assert!(
        lines(&app, &[eq("test.colour", "red")]).is_empty(),
        "and what the note used to say is not still on the record"
    );
}

#[test]
fn a_removed_note_leaves_nothing_behind_in_any_namespace() {
    let (_dir, store) = store();
    let (colour, _) = Marks::new("test.colour", "colour=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    let rel = note(&store, "colour=red will not last");
    std::fs::remove_file(store.vault().join(&rel)).expect("delete");
    store.reconcile().expect("reconcile");

    assert!(lines(&store, &[eq("test.colour", "red")]).is_empty());
    assert!(notes(&store, &[text("will not last")]).is_empty());
    assert!(store.facts_of(&rel).expect("facts").is_empty());
}

// ------------------------------------------------- a foreign implementation

#[test]
fn an_indexer_that_refuses_stops_its_own_namespace_and_nothing_else() {
    let (_dir, store) = store();
    let first = note(&store, "colour=red weight=1 the first");
    let refused = note(&store, "colour=blue weight=2 the second");
    let third = note(&store, "colour=green weight=3 the third");

    let (colour, colour_calls) = Marks::refusing("test.colour", "colour=", &refused);
    let (weight, _) = Marks::new("test.weight", "weight=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    store
        .register_indexer("test.weights", 1, &[], weight)
        .expect("register");

    let pass = store
        .advance_index()
        .expect("the pass completes rather than failing");
    assert_eq!(pass.failures.len(), 1, "{:?}", pass.failures);
    assert!(pass.failures[0].starts_with("test.colours:"));
    assert!(colour_calls.load(Ordering::SeqCst) >= 2);

    // Everything before the note it refused is indexed; the note itself
    // and everything after it is not.
    assert_eq!(lines(&store, &[eq("test.colour", "red")]).len(), 1);
    assert!(lines(&store, &[eq("test.colour", "blue")]).is_empty());
    assert!(
        lines(&store, &[eq("test.colour", "green")]).is_empty(),
        "the cursor stopped at the note it could not read"
    );
    // And the namespace beside it finished the whole feed.
    assert_eq!(lines(&store, &[eq("test.weight", "1")]).len(), 1);
    assert_eq!(lines(&store, &[eq("test.weight", "2")]).len(), 1);
    assert_eq!(lines(&store, &[eq("test.weight", "3")]).len(), 1);

    let status = store.index_status().expect("status");
    let stuck = status
        .namespaces
        .iter()
        .find(|n| n.namespace == "test.colours")
        .expect("the namespace");
    assert!(stuck.last_error.is_some(), "the refusal is on the record");

    // Second order: the cursor did not move past the note, so a pass
    // after the indexer stops refusing picks up exactly where it was.
    let (willing, _) = Marks::new("test.colour", "colour=");
    store
        .register_indexer("test.colours", 1, &[], willing)
        .expect("the same version, a working implementation");
    let pass = store.advance_index().expect("advance");
    assert!(pass.failures.is_empty());
    assert_eq!(lines(&store, &[eq("test.colour", "blue")]).len(), 1);
    assert_eq!(lines(&store, &[eq("test.colour", "green")]).len(), 1);
    assert_eq!(
        lines(&store, &[eq("test.colour", "red")]).len(),
        1,
        "{first} kept"
    );
    assert!(
        store
            .index_status()
            .expect("status")
            .namespaces
            .iter()
            .find(|n| n.namespace == "test.colours")
            .expect("the namespace")
            .last_error
            .is_none(),
        "and the recorded refusal clears when the namespace catches up"
    );
    assert!(!third.is_empty(), "the third note existed all along");
}

#[test]
fn a_line_beyond_the_end_of_the_note_is_stored_exactly_as_the_plugin_gave_it() {
    // Decided rather than defaulted: the kernel never interprets a fact,
    // and a line number is the plugin's claim about the note it was
    // handed. Counting the note's lines to police that claim would be
    // the kernel reading the plugin's mind; the anchor is answered as it
    // was given and a consumer bounds-checks the file it opens.
    let (_dir, store) = store();
    store
        .register_indexer("test.beyond", 1, &[], Arc::new(BeyondTheEnd))
        .expect("register");
    let rel = note(&store, "one line only");
    assert_eq!(
        lines(&store, &[eq("test.colour", "green")]),
        vec![(rel.clone(), 9_999)]
    );
    assert_eq!(
        said(&store, &rel, "test.beyond")[0].line,
        Some(9_999),
        "and it reads back as what was said, not as something tidied"
    );
}

// ------------------------------------------------- the database's own life

#[test]
fn an_index_built_against_another_vault_is_rebuilt_rather_than_served() {
    let one = tempfile::tempdir().expect("tempdir");
    let two = tempfile::tempdir().expect("tempdir");
    {
        let store = Store::open(one.path()).expect("open");
        note(&store, "words that belong to the first vault");
    }
    {
        let store = Store::open(two.path()).expect("open");
        note(&store, "words that belong to the second vault");
    }
    std::fs::copy(
        one.path().join(".gilune/local/index.db"),
        two.path().join(".gilune/local/index.db"),
    )
    .expect("swap one vault's index into another");

    let store = Store::open(two.path()).expect("open");
    store.reconcile().expect("reconcile");
    assert!(
        notes(&store, &[text("first vault")]).is_empty(),
        "an index that answers from another vault's history is worse than none"
    );
    assert_eq!(notes(&store, &[text("second vault")]).len(), 1);
}

#[test]
fn an_index_deleted_between_sessions_is_rebuilt_on_the_next_pass() {
    let dir = tempfile::tempdir().expect("tempdir");
    let rel = {
        let store = Store::open(dir.path()).expect("open");
        note(&store, "words to find again afterwards")
    };
    for name in ["index.db", "index.db-wal", "index.db-shm"] {
        let _ = std::fs::remove_file(dir.path().join(".gilune/local").join(name));
    }

    let store = Store::open(dir.path()).expect("open");
    store.reconcile().expect("reconcile");
    assert_eq!(
        notes(&store, &[text("find again")]),
        vec![rel],
        "deleting it costs a rebuild and nothing else"
    );
}

#[test]
fn a_file_that_is_not_a_database_at_all_is_thrown_away_and_rebuilt() {
    let dir = tempfile::tempdir().expect("tempdir");
    let rel = {
        let store = Store::open(dir.path()).expect("open");
        note(&store, "words to find again afterwards")
    };
    for name in ["index.db-wal", "index.db-shm"] {
        let _ = std::fs::remove_file(dir.path().join(".gilune/local").join(name));
    }
    std::fs::write(
        dir.path().join(".gilune/local/index.db"),
        b"this is not a database and never was",
    )
    .expect("write rubbish where the index was");

    let store = Store::open(dir.path()).expect("open");
    store.reconcile().expect("reconcile");
    assert_eq!(notes(&store, &[text("find again")]), vec![rel]);
}

#[test]
fn a_cursor_the_feed_refuses_wipes_that_namespace_and_rebuilds_it_alone() {
    let (_dir, store) = store();
    let (colour, colour_calls) = Marks::new("test.colour", "colour=");
    let (weight, weight_calls) = Marks::new("test.weight", "weight=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    store
        .register_indexer("test.weights", 1, &[], weight)
        .expect("register");
    let rel = note(&store, "colour=red weight=12");
    assert_eq!(colour_calls.load(Ordering::SeqCst), 1);
    assert_eq!(weight_calls.load(Ordering::SeqCst), 1);

    // A reader ahead of the vault it is reading — the state a restored
    // backup of the kernel database leaves behind.
    store
        .with_index(|db| {
            db.execute(
                "UPDATE namespaces SET cursor_seq = 9999 WHERE namespace = 'test.colours'",
                [],
            )?;
            Ok(())
        })
        .expect("put the cursor past the end");

    let pass = store.advance_index().expect("advance");
    assert!(pass.failures.is_empty());
    assert_eq!(
        colour_calls.load(Ordering::SeqCst),
        2,
        "rebuilt from the beginning"
    );
    assert_eq!(
        weight_calls.load(Ordering::SeqCst),
        1,
        "and only that namespace was rebuilt"
    );
    assert_eq!(
        lines(&store, &[eq("test.colour", "red")]),
        vec![(rel.clone(), 1)]
    );
    assert_eq!(lines(&store, &[eq("test.weight", "12")]), vec![(rel, 1)]);
}

// ------------------------------------------------------------ value typing

#[test]
fn a_number_range_is_answered_from_the_numeric_shadow_and_not_from_the_text() {
    let (_dir, store) = store();
    let (weight, _) = Marks::new("test.weight", "weight=");
    store
        .register_indexer(
            "test.weights",
            1,
            &kinds("test.weight", ValueKind::Number),
            weight,
        )
        .expect("register");
    let rel = note(&store, "weight=9 nine\nweight=10 ten\nweight=100 a hundred");

    let over = Constraint::Fact {
        predicate: "test.weight".to_string(),
        op: ConstraintOp::Ge,
        value: "10".to_string(),
    };
    assert_eq!(
        lines(&store, &[over]),
        vec![(rel.clone(), 2), (rel.clone(), 3)],
        "ten and a hundred, and not nine — which text order would have put first"
    );
    let under = Constraint::Fact {
        predicate: "test.weight".to_string(),
        op: ConstraintOp::Lt,
        value: "10".to_string(),
    };
    assert_eq!(lines(&store, &[under]), vec![(rel, 1)]);
}

#[test]
fn a_value_that_will_not_take_its_declared_kind_stays_findable_and_orders_nowhere() {
    let (_dir, store) = store();
    let (weight, _) = Marks::new("test.weight", "weight=");
    let (due, _) = Marks::new("test.due", "due=");
    store
        .register_indexer(
            "test.weights",
            1,
            &kinds("test.weight", ValueKind::Number),
            weight,
        )
        .expect("register");
    store
        .register_indexer("test.dates", 1, &kinds("test.due", ValueKind::Date), due)
        .expect("register");
    let rel = note(
        &store,
        "weight=heavy not a number\nweight=3 a number\ndue=tomorow a spelling\ndue=2026-09-10 a date",
    );

    assert_eq!(
        lines(&store, &[eq("test.weight", "heavy")]),
        vec![(rel.clone(), 1)],
        "somebody looking for what they actually wrote finds it"
    );
    let heavy_or_more = Constraint::Fact {
        predicate: "test.weight".to_string(),
        op: ConstraintOp::Ge,
        value: "1".to_string(),
    };
    assert_eq!(
        lines(&store, &[heavy_or_more]),
        vec![(rel.clone(), 2)],
        "and it takes no part in an ordered comparison, where it would sort as nonsense"
    );

    assert_eq!(
        lines(&store, &[eq("test.due", "tomorow")]),
        vec![(rel.clone(), 3)]
    );
    let this_month = Constraint::Fact {
        predicate: "test.due".to_string(),
        op: ConstraintOp::Ge,
        value: "2026-09-01".to_string(),
    };
    assert_eq!(
        lines(&store, &[this_month]),
        vec![(rel, 4)],
        "a malformed date would have sorted after every real one"
    );
}

#[test]
fn dates_are_normalised_so_that_reading_them_in_order_reads_them_in_time() {
    let (_dir, store) = store();
    let (due, _) = Marks::new("test.due", "due=");
    store
        .register_indexer("test.dates", 1, &kinds("test.due", ValueKind::Date), due)
        .expect("register");
    let rel = note(&store, "due=2026-09-10T09:00:00 a moment on a day");
    assert_eq!(
        said(&store, &rel, "test.dates")[0].value,
        "2026-09-10",
        "day granularity: a value that needs a time of day declares itself text"
    );
    assert_eq!(
        lines(&store, &[eq("test.due", "2026-09-10")]),
        vec![(rel, 1)]
    );
}

// ------------------------------------------------------------- the operators

#[test]
fn prefix_and_contains_and_exists_and_the_two_that_only_narrow() {
    let (_dir, store) = store();
    let (colour, _) = Marks::new("test.colour", "colour=");
    let (weight, _) = Marks::new("test.weight", "weight=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    store
        .register_indexer("test.weights", 1, &[], weight)
        .expect("register");
    let rel = note(
        &store,
        "colour=reddish weight=1 one\ncolour=blue weight=2 two\ncolour=red three",
    );
    let fact = |op, value: &str| Constraint::Fact {
        predicate: "test.colour".to_string(),
        op,
        value: value.to_string(),
    };

    assert_eq!(
        lines(&store, &[fact(ConstraintOp::Prefix, "red")]),
        vec![(rel.clone(), 1), (rel.clone(), 3)]
    );
    assert_eq!(
        lines(&store, &[fact(ConstraintOp::Contains, "edd")]),
        vec![(rel.clone(), 1)]
    );
    assert_eq!(
        lines(&store, &[fact(ConstraintOp::Exists, "")]),
        vec![(rel.clone(), 1), (rel.clone(), 2), (rel.clone(), 3)]
    );

    // Ne is "no fact with that value at this anchor", and it narrows
    // what another constraint found rather than naming anchors itself.
    assert_eq!(
        lines(
            &store,
            &[
                Constraint::Fact {
                    predicate: "test.weight".to_string(),
                    op: ConstraintOp::Exists,
                    value: String::new()
                },
                fact(ConstraintOp::Ne, "blue")
            ]
        ),
        vec![(rel.clone(), 1)],
        "line two has a weight and is the blue one"
    );
    assert!(
        lines(&store, &[fact(ConstraintOp::Ne, "blue")]).is_empty(),
        "a query with nothing positive in it answers nothing, not the vault"
    );

    // NotExists is the one `done:none` needs: no fact of that predicate
    // at the anchor at all.
    assert_eq!(
        lines(
            &store,
            &[
                fact(ConstraintOp::Exists, ""),
                Constraint::Fact {
                    predicate: "test.weight".to_string(),
                    op: ConstraintOp::NotExists,
                    value: String::new()
                }
            ]
        ),
        vec![(rel, 3)],
        "the only coloured line with no weight beside it"
    );
}

#[test]
fn ne_asks_about_the_value_and_not_about_the_predicate() {
    // The second-order case the definition had to choose between: a
    // predicate with two values at one anchor. "No fact with that value"
    // excludes the anchor; "some fact with another value" would have
    // kept it, and `-colour:red` would then have matched a line that
    // says it is red.
    let (_dir, store) = store();
    let (colour, _) = Marks::new("test.colour", "colour=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    let rel = note(
        &store,
        "colour=red colour=blue both on one line\ncolour=green alone",
    );
    let all = Constraint::Fact {
        predicate: "test.colour".to_string(),
        op: ConstraintOp::Exists,
        value: String::new(),
    };
    let not_red = Constraint::Fact {
        predicate: "test.colour".to_string(),
        op: ConstraintOp::Ne,
        value: "red".to_string(),
    };
    assert_eq!(
        lines(&store, &[all, not_red]),
        vec![(rel, 2)],
        "a line that says red is not a line that is not red, whatever else it says"
    );
}

// ------------------------------------------------------------- full text

#[test]
fn text_terms_match_a_substring_of_a_line_or_of_the_notes_name() {
    let (_dir, store) = store();
    let rel = note(&store, "The Plasterer arrives\nand a second line");
    assert_eq!(
        lines(&store, &[text("aster")]),
        vec![(rel.clone(), 1)],
        "inside a word, which is what the shipped search already does"
    );
    assert_eq!(
        lines(&store, &[text("PLASTERER")]),
        vec![(rel.clone(), 1)],
        "and case does not matter"
    );
    assert_eq!(
        lines(
            &store,
            &[Constraint::Text {
                target: TextTarget::Body,
                query: "second line".to_string()
            }]
        ),
        vec![(rel.clone(), 2)],
        "a phrase is a substring, spaces and all"
    );
    let name = name_of(&rel).to_string();
    assert_eq!(
        notes(
            &store,
            &[Constraint::Text {
                target: TextTarget::Name,
                query: name[..6].to_string()
            }]
        ),
        vec![rel.clone()]
    );
    assert!(
        notes(&store, &[text("nothing of the sort")]).is_empty(),
        "and a term nothing holds finds nothing"
    );
    assert_eq!(body(&store, &rel).lines().count(), 2);
}

#[test]
fn a_term_shorter_than_a_trigram_is_answered_by_a_scan_rather_than_by_silence() {
    // Recorded because P2 has to know it: a trigram index holds no token
    // for a one- or two-character query, so a MATCH would find nothing
    // at all. Falling back to a scan of the indexed lines keeps the
    // language's meaning — substring, always — and costs a vault-sized
    // read only for the shortest queries anybody types.
    let (_dir, store) = store();
    let rel = note(&store, "an ox in the field");
    assert_eq!(lines(&store, &[text("ox")]), vec![(rel.clone(), 1)]);
    assert_eq!(lines(&store, &[text("x")]), vec![(rel, 1)]);
}

#[test]
fn a_text_term_matching_the_notes_name_holds_on_every_line_of_it() {
    // What a bare term means in the shipped language: the body **or**
    // the name. A conjunction cannot express that `or`, so it lives
    // inside the one constraint — the name is a fact about the note, and
    // a fact about the note holds on every line of it.
    let (_dir, store) = store();
    let rel = note(&store, "colour=red one\ncolour=blue two");
    let (colour, _) = Marks::new("test.colour", "colour=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    store.advance_index().expect("advance");
    let name = name_of(&rel).to_string();
    assert_eq!(
        lines(
            &store,
            &[
                Constraint::Text {
                    target: TextTarget::Any,
                    query: name
                },
                Constraint::Fact {
                    predicate: "test.colour".to_string(),
                    op: ConstraintOp::Exists,
                    value: String::new()
                }
            ]
        ),
        vec![(rel.clone(), 1), (rel, 2)]
    );
}

#[test]
fn a_query_bounds_its_answer_and_orders_it_the_same_way_every_time() {
    let (_dir, store) = store();
    let mut expected = Vec::new();
    for n in 0..5 {
        expected.push(note(&store, &format!("a repeated word here, note {n}")));
    }
    expected.sort();
    let all = store
        .query(&[text("repeated word")], QueryScope::Note, 100)
        .expect("query");
    assert_eq!(
        all.iter().map(|a| a.rel_path.clone()).collect::<Vec<_>>(),
        expected
    );
    let bounded = store
        .query(&[text("repeated word")], QueryScope::Note, 2)
        .expect("query");
    assert_eq!(bounded.len(), 2);
    assert_eq!(bounded[0].rel_path, expected[0]);
    assert!(store
        .query(&[], QueryScope::Note, 10)
        .expect("query")
        .is_empty());
}

// ------------------------------------------------------- the front matter

/// A note with keys of somebody's own added to the header the kernel
/// wrote. A `.md` file whose front matter carries no `gilune-id` is
/// quarantined (ADR 0007), so this is how arbitrary front matter really
/// reaches a vault: on a note the kernel already claims, edited outside.
fn keyed(store: &Store, rel_path: &str, keys: &str) {
    let path = store.vault().join(rel_path);
    let text = std::fs::read_to_string(&path).expect("read the note");
    let head = text
        .split_inclusive("\n---\n")
        .next()
        .expect("front matter")
        .to_string();
    let body = text[head.len()..].to_string();
    let reopened = format!("{}{keys}---\n{body}", head.trim_end_matches("---\n"));
    std::fs::write(&path, reopened).expect("write the note");
    store.reconcile().expect("reconcile");
}

/// The indexed text of a note, as the full-text half holds it.
fn text_of(store: &Store, rel_path: &str) -> Vec<String> {
    store
        .with_index(|db| {
            let mut stmt = db.prepare(
                "SELECT text FROM lines WHERE rel_path = ?1 AND field = 'body' ORDER BY line",
            )?;
            let rows = stmt.query_map([rel_path], |row| row.get::<_, String>(0))?;
            Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
        })
        .expect("lines")
}

fn front_matter(store: &Store, rel_path: &str) -> Vec<(String, String)> {
    said(store, rel_path, crate::index::FRONT_MATTER_NAMESPACE)
        .into_iter()
        .map(|fact| (fact.predicate, fact.value))
        .collect()
}

#[test]
fn a_notes_front_matter_is_read_into_facts_the_kernel_interprets_none_of() {
    let (_dir, store) = store();
    let rel = note(&store, "the wall needs plastering");
    keyed(&store, &rel, "mood: calm\nTags: home\n");
    let said = front_matter(&store, &rel);
    assert!(
        said.contains(&("frontmatter.mood".to_string(), "calm".to_string()))
            && said.contains(&("frontmatter.tags".to_string(), "home".to_string())),
        "the key is lower-cased so [Tags] and [tags] are one question, \
         and the value is exactly what was written: {said:?}"
    );
    assert!(
        said.iter()
            .any(|(predicate, _)| predicate == "frontmatter.gilune-id"),
        "and the kernel's own keys are stored like anybody else's: {said:?}"
    );
    // Note-level, so it holds of the whole note and of every line in it.
    assert_eq!(
        notes(&store, &[eq("frontmatter.mood", "calm")]),
        vec![rel.clone()]
    );
    assert!(
        !said.is_empty()
            && store
                .facts_of(&rel)
                .expect("facts")
                .iter()
                .all(|fact| fact.line.is_none()),
        "front matter describes the note, not a line of it"
    );
}

#[test]
fn a_note_with_no_front_matter_says_nothing_about_itself() {
    let (_dir, store) = store();
    std::fs::write(
        store.vault().join("Bare.md"),
        "just prose, no header at all\n",
    )
    .expect("write");
    store.reconcile().expect("reconcile");
    assert!(front_matter(&store, "Bare.md").is_empty());
    assert!(
        !text_of(&store, "Bare.md").is_empty(),
        "and its words are still findable"
    );
}

#[test]
fn a_list_is_one_pair_per_item_and_a_nested_mapping_is_none() {
    // The two shapes YAML offers beyond `key: value`, read at the parser
    // because a vault cannot hold one today: ADR 0007 quarantines a note
    // whose front matter is not `key: value` throughout, so a list in a
    // real header is a quarantined file rather than a fact. The rule is
    // written anyway, and pinned here, because the reading is not
    // obvious — a list is one fact per item, since that is what somebody
    // tagging a note is asking to be found by, and a nested mapping is
    // nothing at all, since a fact anchors to a note and not to a path
    // into a tree.
    assert_eq!(
        front_matter_of(
            "---\ntags:\n  - home\n  - chores\nauthor:\n  name: nobody\ntitle: a note\n---\nbody\n"
        ),
        vec![
            ("tags".to_string(), "home".to_string()),
            ("tags".to_string(), "chores".to_string()),
            ("title".to_string(), "a note".to_string()),
        ]
    );
    // And the degenerate shapes: no header, an unterminated one, a line
    // with no key, and a key with nothing under it.
    assert!(front_matter_of("no header here\n").is_empty());
    assert!(front_matter_of("---\nmood: calm\nnever closed\n").is_empty());
    assert_eq!(
        front_matter_of("---\n: value\nmood: calm\nempty:\n---\nbody\n"),
        vec![
            ("mood".to_string(), "calm".to_string()),
            ("empty".to_string(), String::new())
        ]
    );
}

#[test]
fn front_matter_facts_follow_a_rename_and_go_with_the_note() {
    let (_dir, store) = store();
    let rel = note(&store, "movable");
    keyed(&store, &rel, "mood: calm\n");
    std::fs::rename(store.vault().join(&rel), store.vault().join("Moved.md")).expect("rename");
    store.reconcile().expect("reconcile");
    assert_eq!(
        notes(&store, &[eq("frontmatter.mood", "calm")]),
        vec!["Moved.md".to_string()],
        "a fact about a note is about it wherever it is"
    );

    std::fs::remove_file(store.vault().join("Moved.md")).expect("delete");
    store.reconcile().expect("reconcile");
    assert!(notes(&store, &[eq("frontmatter.mood", "calm")]).is_empty());
}

#[test]
fn an_edit_to_the_header_replaces_what_the_note_said_about_itself() {
    // The second-order case: facts are replaced whole per note, so a key
    // whose value changed must not go on answering for the old one.
    let (_dir, store) = store();
    let rel = note(&store, "the kitchen");
    keyed(&store, &rel, "mood: calm\n");
    let path = store.vault().join(&rel);
    let text = std::fs::read_to_string(&path).expect("read");
    std::fs::write(&path, text.replace("mood: calm", "mood: fraught")).expect("write");
    store.reconcile().expect("reconcile");
    assert!(front_matter(&store, &rel)
        .contains(&("frontmatter.mood".to_string(), "fraught".to_string())));
    assert!(notes(&store, &[eq("frontmatter.mood", "calm")]).is_empty());
}

#[test]
fn the_front_matter_namespace_is_not_a_plugins_to_register() {
    let (_dir, store) = store();
    let (marks, _) = Marks::new("test.colour", "colour=");
    assert!(store
        .register_indexer(crate::index::FRONT_MATTER_NAMESPACE, 1, &[], marks)
        .is_err());
}

// ------------------------------------------------- incremental == rebuilt

/// A deterministic generator, so a failing seed is a failing seed for
/// ever. Nothing here needs cryptographic quality and a dependency for
/// eight lines would be a dependency.
struct Rolls(u64);

impl Rolls {
    fn next(&mut self, bound: usize) -> usize {
        self.0 = self
            .0
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        ((self.0 >> 33) as usize) % bound.max(1)
    }
}

fn projected(store: &Store) -> Vec<String> {
    let mut stmt = store
        .conn()
        .prepare("SELECT rel_path FROM projections ORDER BY rel_path")
        .expect("prepare");
    let rows = stmt
        .query_map([], |row| row.get::<_, String>(0))
        .expect("query");
    rows.filter_map(std::result::Result::ok)
        .filter(|rel| store.vault().join(rel).is_file())
        .collect()
}

/// Files the vault is holding as import candidates and still has.
fn imported(store: &Store) -> Vec<String> {
    let mut stmt = store
        .conn()
        .prepare("SELECT rel_path FROM imports ORDER BY rel_path")
        .expect("prepare");
    let rows = stmt
        .query_map([], |row| row.get::<_, String>(0))
        .expect("query");
    rows.filter_map(std::result::Result::ok)
        .filter(|rel| store.vault().join(rel).is_file())
        .collect()
}

fn randomised_vault(seed: u64, steps: usize) -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().expect("tempdir");
    let store = Store::open(dir.path()).expect("open");
    let (colour, _) = Marks::new("test.colour", "colour=");
    let (weight, _) = Marks::new("test.weight", "weight=");
    store
        .register_indexer(
            "test.colours",
            1,
            &kinds("test.colour", ValueKind::Text),
            colour,
        )
        .expect("register");
    store
        .register_indexer(
            "test.weights",
            1,
            &kinds("test.weight", ValueKind::Number),
            weight,
        )
        .expect("register");
    let mut rolls = Rolls(seed);
    let palette = ["red", "blue", "green", "black", "heavy"];

    for step in 0..steps {
        let live = projected(&store);
        let brought_in = imported(&store);
        let action = if live.is_empty() { 0 } else { rolls.next(6) };
        match action {
            5 if brought_in.is_empty() => {}
            5 => {
                // An edit to a file the kernel does not claim. The
                // property test deliberately avoided this until imports
                // reached the feed: an announcement nobody made is a
                // difference between keeping up and rebuilding, which is
                // exactly what this test is for.
                let rel = brought_in[rolls.next(brought_in.len())].clone();
                std::fs::write(
                    store.vault().join(&rel),
                    format!(
                        "colour={} weight={} rewritten elsewhere at {step}",
                        palette[rolls.next(palette.len())],
                        rolls.next(50)
                    ),
                )
                .expect("their edit");
                store.reconcile().expect("reconcile");
            }
            0 => {
                store
                    .capture_bytes(
                        "txt",
                        format!(
                            "colour={} weight={} note {step}\na second line for {step}",
                            palette[rolls.next(palette.len())],
                            rolls.next(50)
                        )
                        .as_bytes(),
                    )
                    .expect("capture");
            }
            1 => {
                let rel = live[rolls.next(live.len())].clone();
                rewrite(
                    &store,
                    &rel,
                    &format!(
                        "colour={} weight={} edited at {step}\nand more words {step}",
                        palette[rolls.next(palette.len())],
                        rolls.next(50)
                    ),
                );
            }
            2 => {
                let rel = live[rolls.next(live.len())].clone();
                let moved = format!("Moved/renamed-{step}.md");
                std::fs::create_dir_all(store.vault().join("Moved")).expect("folder");
                std::fs::rename(store.vault().join(&rel), store.vault().join(&moved))
                    .expect("move");
                // Reconciliation is what follows a file by its id. A
                // projection pass first would find the recorded path
                // empty, write the note back where it was, and leave the
                // moved copy to be quarantined as a duplicate — which is
                // the kernel behaving correctly and the harness asking
                // the wrong thing.
                store.reconcile().expect("reconcile");
            }
            3 => {
                let rel = live[rolls.next(live.len())].clone();
                std::fs::remove_file(store.vault().join(&rel)).expect("delete");
                store.reconcile().expect("reconcile");
            }
            _ => {
                // A file the kernel did not write: an import candidate,
                // offered once and never touched.
                std::fs::write(
                    store.vault().join(format!("brought-in-{step}.md")),
                    format!("colour=blue weight=7 a file from somewhere else, {step}"),
                )
                .expect("write");
                store.reconcile().expect("reconcile");
            }
        }
        store.project().expect("project");
        store.reconcile().expect("reconcile");
    }
    (dir, store)
}

#[test]
fn an_index_kept_up_to_date_equals_one_rebuilt_from_the_beginning() {
    // ADR 0020 § 8.4. The property the whole incremental design stands
    // on: whatever sequence of creations, edits, moves, deletions and
    // imports a vault has been through, catching up change by change
    // lands exactly where deriving the lot from cursor 0 lands.
    for seed in [1_u64, 7, 2026, 90_210] {
        let (_dir, store) = randomised_vault(seed, 200);
        let incremental = snapshot(&store);
        store.rebuild_index(None).expect("rebuild");
        let rebuilt = snapshot(&store);
        assert_eq!(
            incremental, rebuilt,
            "seed {seed}: an index caught up differs from one rebuilt"
        );
        assert!(
            !incremental.is_empty(),
            "seed {seed}: the vault indexed nothing at all"
        );
    }
}

#[test]
fn text_queries_answer_exactly_what_reading_every_file_would_have() {
    // The other half of § 8.4: the index and the scan it replaces give
    // the same answer, under the semantics this round chose — substring,
    // case-insensitive, body lines or the note's name.
    let (_dir, store) = randomised_vault(31, 120);
    for needle in [
        "colour",
        "second line",
        "edited at",
        "blue",
        "somewhere else",
        "note 1",
    ] {
        let mut scanned: Vec<String> = Vec::new();
        let mut stack = vec![store.vault().to_path_buf()];
        while let Some(dir) = stack.pop() {
            for entry in std::fs::read_dir(&dir).expect("read dir").flatten() {
                let path = entry.path();
                let name = entry.file_name().to_string_lossy().to_string();
                if path.is_dir() {
                    if !name.starts_with('.') {
                        stack.push(path);
                    }
                    continue;
                }
                if !name.ends_with(".md") || name.starts_with(".tmp-") {
                    continue;
                }
                let Ok(text) = std::fs::read_to_string(&path) else {
                    continue;
                };
                let rel = path
                    .strip_prefix(store.vault())
                    .unwrap_or(&path)
                    .to_string_lossy()
                    .replace('\\', "/");
                let wanted = needle.to_lowercase();
                let matched = name_of(&rel).to_lowercase().contains(&wanted)
                    || body_of(&text)
                        .split('\n')
                        .any(|line| line.to_lowercase().contains(&wanted));
                if matched {
                    scanned.push(rel);
                }
            }
        }
        scanned.sort();
        assert_eq!(
            notes(&store, &[text(needle)]),
            scanned,
            "the index and a scan disagree about {needle:?}"
        );
    }
}

// ------------------------------------------------------------ measurement

#[test]
fn a_thousand_notes_cost_one_indexer_call_and_one_note_of_work_for_one_edit() {
    // ADR 0020 § 8.5 at the size that matters, and the measurement § 8.8
    // asks for. Printed rather than asserted: a wall-clock number on a
    // machine running a test suite is evidence, not a threshold.
    let (_dir, store) = store();
    let (colour, calls) = Marks::new("test.colour", "colour=");
    store
        .register_indexer("test.colours", 1, &[], colour)
        .expect("register");
    let mut first = String::new();
    for n in 0..1000 {
        let mut note = format!("colour=red note number {n}\n");
        for line in 0..19 {
            note.push_str(&format!("line {line} of note {n} with some words in it\n"));
        }
        let receipt = store
            .capture_bytes("txt", note.as_bytes())
            .expect("capture");
        if n == 0 {
            first = receipt.rel_path.replace(".txt", ".md");
        }
    }
    let built = std::time::Instant::now();
    store.project().expect("project");
    let build_ms = built.elapsed().as_millis();
    assert_eq!(calls.load(Ordering::SeqCst), 1000);

    let status = store.index_status().expect("status");
    println!(
        "index: 1000 notes x 20 lines built in {build_ms} ms, \
         {} indexed lines, {} bytes on disk",
        status.lines, status.db_bytes
    );

    calls.store(0, Ordering::SeqCst);
    crate::probe::reset_file_reads();
    let edited = std::time::Instant::now();
    rewrite(
        &store,
        &first,
        "colour=black the only note that changed\nand one more line",
    );
    let edit_ms = edited.elapsed().as_millis();
    assert_eq!(
        calls.load(Ordering::SeqCst),
        1,
        "one edit, one indexer call"
    );
    assert_eq!(
        store.index_status().expect("status").lines,
        status.lines - 19,
        "twenty lines plus the trailing blank replaced by two, and nothing else touched"
    );

    let asked = std::time::Instant::now();
    let hit = lines(&store, &[eq("test.colour", "black")]);
    let query_ms = asked.elapsed().as_micros();
    assert_eq!(hit, vec![(first, 1)]);
    println!("index: one edit reconciled and indexed in {edit_ms} ms, one query in {query_ms} us");
    println!(
        "index: the edit read {} file(s)",
        crate::probe::file_reads()
    );
}
