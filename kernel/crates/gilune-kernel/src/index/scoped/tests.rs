use super::*;
use std::sync::atomic::{AtomicBool, Ordering};

mod latency;

#[test]
fn an_empty_result_from_a_newer_source_is_not_evidence_for_a_negative_on_an_old_source() {
    let (dir, store, _) = fixture("- red square\n");
    std::fs::write(dir.path().join("a.md"), "- red\n").unwrap();
    // Re-registering this producer re-derives it from source; the text namespace
    // has no feed change yet. Its empty result still needs version evidence.
    store
        .register_indexer("fixture.shapes", 2, &[], Arc::new(Shapes))
        .unwrap();
    store.advance_index().unwrap();
    let q = request(vec![
        eq("fixture.colour", "red"),
        Constraint::FactAt {
            predicate: "fixture.shape".into(),
            op: ConstraintOp::NotExists,
            value: String::new(),
            locality: FactLocality::Exact,
        },
    ]);
    let before = store.query_locations(&q).unwrap();
    assert!(!before.index_complete);
    assert!(before.matches.is_empty());
    store.reconcile().unwrap();
    let after = store.query_locations(&q).unwrap();
    assert!(after.index_complete);
    assert_eq!(after.matches.len(), 1);
}

#[test]
fn more_than_ten_thousand_items_page_completely_without_source_reads() {
    let (_, store, _) = fixture(&"- red square\n".repeat(10_005));
    let mut q = request(vec![eq("fixture.colour", "red"), eq("fixture.shape", "square")]);
    q.kind = LocationKind::MarkdownItem;
    q.page_size = 500;
    crate::probe::reset_file_reads();
    let started = std::time::Instant::now();
    let mut keys = BTreeSet::new();
    loop {
        let p = store.query_locations(&q).unwrap();
        assert!(p.index_complete);
        for m in p.matches {
            let Location::MarkdownItem { key } = m.location else {
                panic!("item expected")
            };
            assert!(keys.insert(key), "a location must not appear on two pages");
        }
        q.cursor = p.next_cursor;
        if q.cursor.is_none() {
            break;
        }
    }
    assert_eq!(keys.len(), 10_005);
    assert_eq!(crate::probe::file_reads(), 0);
    println!(
        "10,005 scoped item matches paged in {:?}; zero source reads",
        started.elapsed()
    );
}

#[test]
fn moves_deletes_and_invalid_yaml_leave_truthful_scope_evidence() {
    let (dir, store, _) = fixture("- red\n");
    let q = request(vec![eq("fixture.colour", "red")]);
    let original = store.query_locations(&q).unwrap().matches.remove(0);
    std::fs::rename(dir.path().join("a.md"), dir.path().join("b.md")).unwrap();
    store.reconcile().unwrap();
    let moved = store.query_locations(&q).unwrap();
    assert!(moved.index_complete);
    assert_eq!(moved.matches[0].rel_path, "b.md");
    assert_eq!(moved.matches[0].source_version, original.source_version);
    std::fs::write(dir.path().join("b.md"), "---\nbroken: [\n---\n- red\n").unwrap();
    // Exercise an invalid source named by the feed. Reconciliation may refuse
    // malformed imported metadata before recording any change at all.
    crate::changes::record(
        store.conn(),
        crate::changes::Change {
            kind: ChangeKind::Modified,
            rel_path: "b.md",
            previous_rel_path: None,
            object_id: None,
            revision_id: None,
            cause: crate::changes::ChangeCause::ExternalEdit,
        },
    )
    .unwrap();
    store.advance_index().unwrap();
    let invalid = store.query_locations(&q).unwrap();
    assert!(!invalid.index_complete);
    assert!(invalid.matches.is_empty());
    // Malformed YAML does not prevent ordinary full-text indexing.
    assert_eq!(
        store
            .query(
                &[Constraint::Text {
                    target: TextTarget::Body,
                    query: "red".into()
                }],
                QueryScope::Line,
                100
            )
            .unwrap()
            .len(),
        1
    );
    std::fs::remove_file(dir.path().join("b.md")).unwrap();
    store.reconcile().unwrap();
    let deleted = store.query_locations(&q).unwrap();
    assert!(deleted.index_complete);
    assert!(deleted.matches.is_empty());
}

// Two unrelated fact producers join through the public location contract.
// Neither knows the other's syntax, predicates, or implementation.
struct Colours {
    fail: Arc<AtomicBool>,
}
impl NoteIndexer for Colours {
    fn index(&self, _: &str, _: &str) -> std::result::Result<Vec<Fact>, String> {
        unreachable!()
    }
    fn uses_scoped_input(&self) -> bool {
        true
    }
    fn index_scoped(
        &self,
        _: &str,
        s: &TextSnapshot,
    ) -> std::result::Result<Vec<ScopedFact>, String> {
        if self.fail.load(Ordering::SeqCst) {
            return Err("fixture failure".into());
        }
        let mut facts = vec![ScopedFact {
            location: Location::DocumentRoot,
            predicate: "fixture.colour".into(),
            value: "green".into(),
        }];
        for (offset, _) in s.text().match_indices("red") {
            facts.push(ScopedFact {
                location: s.location_at(offset as u64).unwrap(),
                predicate: "fixture.colour".into(),
                value: "red".into(),
            });
        }
        Ok(facts)
    }
}
struct Shapes;
impl NoteIndexer for Shapes {
    fn index(&self, _: &str, _: &str) -> std::result::Result<Vec<Fact>, String> {
        unreachable!()
    }
    fn uses_scoped_input(&self) -> bool {
        true
    }
    fn index_scoped(
        &self,
        _: &str,
        s: &TextSnapshot,
    ) -> std::result::Result<Vec<ScopedFact>, String> {
        Ok(s.text()
            .match_indices("square")
            .map(|(offset, _)| ScopedFact {
                location: s.location_at(offset as u64).unwrap(),
                predicate: "fixture.shape".into(),
                value: "square".into(),
            })
            .collect())
    }
}
fn fixture(text: &str) -> (tempfile::TempDir, Store, Arc<AtomicBool>) {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let fail = Arc::new(AtomicBool::new(false));
    store
        .register_indexer(
            "fixture.colours",
            1,
            &[],
            Arc::new(Colours { fail: fail.clone() }),
        )
        .unwrap();
    store
        .register_indexer("fixture.shapes", 1, &[], Arc::new(Shapes))
        .unwrap();
    std::fs::write(dir.path().join("a.md"), text).unwrap();
    store.reconcile().unwrap();
    (dir, store, fail)
}
fn eq(predicate: &str, value: &str) -> Constraint {
    Constraint::FactAt {
        predicate: predicate.into(),
        op: ConstraintOp::Eq,
        value: value.into(),
        locality: FactLocality::Exact,
    }
}
fn request(constraints: Vec<Constraint>) -> LocationQuery {
    LocationQuery {
        constraints,
        kind: LocationKind::Any,
        required_namespaces: vec!["fixture.colours".into(), "fixture.shapes".into()],
        page_size: 2,
        cursor: None,
    }
}

#[test]
fn joins_continuations_without_inheriting_nested_or_root_facts_and_reads_no_files() {
    let (dir, store, _) = fixture("- square\n  red\n  - square\n- red\n\nred prose\n");
    crate::probe::reset_file_reads();
    std::fs::write(dir.path().join("a.md"), "unreconciled replacement").unwrap();
    let page = store
        .query_locations(&request(vec![
            eq("fixture.colour", "red"),
            eq("fixture.shape", "square"),
        ]))
        .unwrap();
    assert!(page.index_complete);
    assert_eq!(page.matches.len(), 1);
    assert_eq!(page.matches[0].location, Location::MarkdownItem { key: 0 });
    assert!(page.matches[0].own_ranges.iter().all(|r| r.end <= 17));
    assert_eq!(crate::probe::file_reads(), 0);
    assert!(store
        .query_locations(&request(vec![
            eq("fixture.colour", "green"),
            eq("fixture.shape", "square")
        ]))
        .unwrap()
        .matches
        .is_empty());
    let inherited = Constraint::FactAt {
        predicate: "fixture.colour".into(),
        op: ConstraintOp::Eq,
        value: "green".into(),
        locality: FactLocality::DocumentRoot,
    };
    assert_eq!(
        store
            .query_locations(&request(vec![inherited, eq("fixture.shape", "square")]))
            .unwrap()
            .matches
            .len(),
        2
    );
    // Old line APIs must not reinterpret item facts as document-root facts.
    assert!(!store
        .facts_of("a.md")
        .unwrap()
        .iter()
        .any(|f| f.predicate == "fixture.shape"));
    assert!(store
        .query(&[eq("fixture.shape", "square")], QueryScope::Line, 100)
        .unwrap()
        .is_empty());
}

#[test]
fn membership_negation_paging_rebuild_and_cursor_invalidation() {
    let (_, store, _) = fixture("- red\n- red square\n- red\n\nred prose\n");
    let mut q = request(vec![
        Constraint::FactIn {
            predicate: "fixture.colour".into(),
            values: vec!["red".into(), "red".into()],
            locality: FactLocality::Exact,
        },
        Constraint::FactAt {
            predicate: "fixture.shape".into(),
            op: ConstraintOp::NotExists,
            value: String::new(),
            locality: FactLocality::Exact,
        },
    ]);
    let first = store.query_locations(&q).unwrap();
    assert_eq!(first.matches.len(), 2);
    q.cursor = first.next_cursor.clone();
    assert!(q.cursor.is_some());
    let last = store.query_locations(&q).unwrap();
    assert_eq!(last.matches.len(), 1);
    assert_eq!(last.matches[0].location, Location::MarkdownItem { key: 19 });
    assert!(first
        .matches
        .iter()
        .any(|m| m.location == Location::BodyLine { number: 5 }));
    assert!(last.next_cursor.is_none());
    store.rebuild_index(None).unwrap();
    assert!(store.query_locations(&q).is_err());
    q.cursor = None;
    assert_eq!(store.query_locations(&q).unwrap().matches, first.matches);
}

#[test]
fn failures_never_join_old_facts_to_new_source_even_if_item_keys_are_reused() {
    let (dir, store, fail) = fixture("- red square\n");
    fail.store(true, Ordering::SeqCst);
    std::fs::write(dir.path().join("a.md"), "- blue square\n").unwrap();
    store.reconcile().unwrap();
    let q = request(vec![
        eq("fixture.colour", "red"),
        eq("fixture.shape", "square"),
    ]);
    let p = store.query_locations(&q).unwrap();
    assert!(!p.index_complete);
    assert!(p.matches.is_empty());
    fail.store(false, Ordering::SeqCst);
    store.advance_index().unwrap();
    assert!(store.query_locations(&q).unwrap().index_complete);
}

#[test]
fn rejects_unknown_locations_and_implicit_or_unsupported_query_semantics() {
    let snapshot = TextSnapshot::new("- text\n".into()).unwrap();
    for location in [
        Location::BodyLine { number: 0 },
        Location::MarkdownItem { key: 999 },
    ] {
        assert!(typed_scoped_row(
            &BTreeMap::new(),
            &snapshot,
            ScopedFact {
                location,
                predicate: "fixture.x".into(),
                value: "x".into()
            }
        )
        .is_err());
    }
    let (_, store, _) = fixture("- red\n");
    assert!(store
        .query_locations(&request(vec![Constraint::Fact {
            predicate: "fixture.colour".into(),
            op: ConstraintOp::Eq,
            value: "red".into()
        }]))
        .is_err());
    assert!(store
        .query_locations(&request(vec![Constraint::Text {
            target: TextTarget::Any,
            query: "red".into()
        }]))
        .is_err());
}
