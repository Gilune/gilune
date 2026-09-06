//! The system index at the boundary (ADR 0020 § 2.3–2.4): a foreign
//! indexer, a conjunctive query, and the shapes a shell will build.
//!
//! The kernel's own tests prove the storage rules. What only this level
//! can show is that they survive the crossing: an indexer implemented
//! **outside** the kernel — the `KeyWrap` shape, and in the shells a
//! Kotlin object — is asked about a note and its facts come back as
//! anchors a shell can navigate to, and that an implementation which
//! throws is a recorded refusal rather than a crash.

use gilune_ffi::{
    Anchor, Constraint, ConstraintOp, Fact, Gilune, GiluneError, NoteIndexer, PredicateKind,
    QueryScope, TextTarget, ValueKind,
};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

/// A foreign indexer, in the only foreign language available here.
/// `colour=red` on a line becomes `test.colour = red` anchored to it.
struct Colours {
    calls: Arc<AtomicUsize>,
    refuse: bool,
}

impl NoteIndexer for Colours {
    fn index(&self, rel_path: String, text: String) -> Result<Vec<Fact>, GiluneError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if self.refuse {
            return Err(GiluneError::Invalid {
                detail: format!("this implementation will not read {rel_path}"),
            });
        }
        Ok(text
            .split('\n')
            .enumerate()
            .filter_map(|(index, line)| {
                let at = line.find("colour=")?;
                let value = line[at + "colour=".len()..]
                    .split_whitespace()
                    .next()?
                    .to_string();
                Some(Fact {
                    line: Some(index as u32 + 1),
                    predicate: "test.colour".to_string(),
                    value,
                })
            })
            .collect())
    }
}

fn open(dir: &tempfile::TempDir) -> Arc<Gilune> {
    Gilune::open(dir.path().to_string_lossy().to_string()).expect("open")
}

fn colour(value: &str) -> Constraint {
    Constraint::Fact {
        predicate: "test.colour".to_string(),
        op: ConstraintOp::Eq,
        value: value.to_string(),
    }
}

fn anchors(gilune: &Gilune, constraints: Vec<Constraint>, scope: QueryScope) -> Vec<Anchor> {
    gilune.query(constraints, scope, 50).expect("query")
}

#[test]
fn a_foreign_indexer_is_asked_about_a_note_and_its_facts_come_back_as_anchors() {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = open(&dir);
    let calls = Arc::new(AtomicUsize::new(0));
    gilune
        .register_indexer(
            "test.colours".to_string(),
            1,
            vec![PredicateKind {
                predicate: "test.colour".to_string(),
                kind: ValueKind::Text,
            }],
            Arc::new(Colours {
                calls: calls.clone(),
                refuse: false,
            }),
        )
        .expect("register");

    gilune
        .capture_text("colour=red the first line\ncolour=blue the second".to_string())
        .expect("capture");
    gilune.project().expect("project");
    assert_eq!(
        calls.load(Ordering::SeqCst),
        1,
        "the pass that wrote the note read it"
    );

    let hits = anchors(&gilune, vec![colour("blue")], QueryScope::Line);
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].line, Some(2), "a line a shell can navigate to");
    assert!(hits[0].rel_path.ends_with(".md"));

    // The join a shell will build: a fact and a text term on one line.
    let both = anchors(
        &gilune,
        vec![
            colour("red"),
            Constraint::Text {
                target: TextTarget::Body,
                query: "first line".to_string(),
            },
        ],
        QueryScope::Line,
    );
    assert_eq!(
        both,
        vec![Anchor {
            rel_path: hits[0].rel_path.clone(),
            line: Some(1)
        }]
    );
    assert_eq!(
        anchors(&gilune, vec![colour("red")], QueryScope::Note)[0].line,
        None,
        "a note anchor names no line"
    );

    let facts = gilune.facts_of(hits[0].rel_path.clone()).expect("facts");
    let contributed: Vec<_> = facts
        .iter()
        .filter(|f| f.namespace == "test.colours")
        .collect();
    assert_eq!(contributed.len(), 2);
    assert!(
        facts.iter().any(|f| f.namespace == "frontmatter"),
        "the note's own header is read by the kernel beside them"
    );

    let status = gilune.index_status().expect("status");
    assert_eq!(status.schema_version, gilune_kernel::index::SCHEMA_VERSION);
    assert!(status
        .namespaces
        .iter()
        .any(|n| n.namespace == "test.colours" && n.registered && n.facts == 2));
    assert!(status.namespaces.iter().any(|n| n.namespace == "text"));
}

#[test]
fn an_indexer_registered_after_the_vault_was_open_catches_up_when_it_is_told_to() {
    // The case `project` does not cover: a shell that registers a
    // plugin's indexer on a settings change has a feed behind it and no
    // pass to ride on.
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = open(&dir);
    gilune
        .capture_text("colour=green written before anybody was reading".to_string())
        .expect("capture");
    gilune.project().expect("project");

    let calls = Arc::new(AtomicUsize::new(0));
    gilune
        .register_indexer(
            "test.colours".to_string(),
            1,
            vec![],
            Arc::new(Colours {
                calls: calls.clone(),
                refuse: false,
            }),
        )
        .expect("register");
    assert!(anchors(&gilune, vec![colour("green")], QueryScope::Note).is_empty());

    let pass = gilune.advance_index().expect("advance");
    assert_eq!(pass.indexed, 1);
    assert!(pass.failures.is_empty());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    assert_eq!(
        anchors(&gilune, vec![colour("green")], QueryScope::Note).len(),
        1
    );
}

#[test]
fn a_foreign_indexer_that_throws_is_a_recorded_refusal_and_not_a_crash() {
    // A Kotlin object can throw, and the boundary has to survive it: the
    // pass completes, the namespace stops where it was, and the message
    // is on the record for a person to read.
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = open(&dir);
    gilune
        .register_indexer(
            "test.colours".to_string(),
            1,
            vec![],
            Arc::new(Colours {
                calls: Arc::new(AtomicUsize::new(0)),
                refuse: true,
            }),
        )
        .expect("register");
    gilune
        .capture_text("colour=red nobody will read this".to_string())
        .expect("capture");

    let summary = gilune
        .project()
        .expect("the projection is not stopped by the index");
    assert_eq!(
        summary.written, 1,
        "the note was written whatever the index did"
    );
    let pass = gilune.advance_index().expect("the pass completes");
    assert_eq!(pass.failures.len(), 1, "{:?}", pass.failures);
    assert!(pass.failures[0].contains("will not read"));

    let status = gilune.index_status().expect("status");
    let stuck = status
        .namespaces
        .iter()
        .find(|n| n.namespace == "test.colours")
        .expect("the namespace");
    assert_eq!(
        stuck.cursor_seq, 0,
        "it stopped short of the note it refused"
    );
    assert!(stuck.last_error.is_some());
    assert!(
        status
            .namespaces
            .iter()
            .any(|n| n.namespace == "text" && n.cursor_seq > 0),
        "and the kernel's own text index carried on"
    );
}

#[test]
fn the_kernels_text_namespace_is_not_a_shells_to_register() {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = open(&dir);
    assert!(matches!(
        gilune.register_indexer(
            "text".to_string(),
            1,
            vec![],
            Arc::new(Colours {
                calls: Arc::new(AtomicUsize::new(0)),
                refuse: false
            })
        ),
        Err(GiluneError::VaultUnusable { .. })
    ));
}

#[test]
fn removing_a_plugin_takes_the_facts_it_contributed_and_leaves_the_notes() {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = open(&dir);
    gilune
        .register_plugin(gilune_ffi::PluginDeclaration {
            plugin_id: "test.colours".to_string(),
            name: "Colours".to_string(),
            class: "index".to_string(),
            profile: "bundled".to_string(),
            required: false,
            processor_id: None,
            subscribes: None,
            emits: vec![],
            capabilities: vec![],
            contributions: vec![],
        })
        .expect("register the plugin");
    gilune
        .register_indexer(
            "test.colours".to_string(),
            1,
            vec![],
            Arc::new(Colours {
                calls: Arc::new(AtomicUsize::new(0)),
                refuse: false,
            }),
        )
        .expect("register the indexer");
    gilune
        .capture_text("colour=red words that stay".to_string())
        .expect("capture");
    gilune.project().expect("project");
    let rel = anchors(&gilune, vec![colour("red")], QueryScope::Note)[0]
        .rel_path
        .clone();

    gilune
        .remove_plugin("test.colours".to_string())
        .expect("remove");
    assert!(anchors(&gilune, vec![colour("red")], QueryScope::Note).is_empty());
    assert_eq!(
        anchors(
            &gilune,
            vec![Constraint::Text {
                target: TextTarget::Any,
                query: "words that stay".to_string()
            }],
            QueryScope::Note
        )
        .len(),
        1,
        "removing a reader never removes what it read"
    );
    assert!(std::fs::read_to_string(dir.path().join(&rel))
        .expect("the note is still there")
        .contains("words that stay"));
}
