use super::*;
use crate::index::{scoped::ScopedFact, Fact, NoteIndexer, PredicateKind, ValueKind};
use gilune_document::{Location, TextSnapshot};
use std::sync::Arc;

struct Labels;
impl NoteIndexer for Labels {
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
        let mut facts = vec![ScopedFact {
            location: Location::DocumentRoot,
            predicate: "example.colour".into(),
            value: "root".into(),
        }];
        for (i, item) in s.layout().items.iter().enumerate() {
            let mut add = |predicate: &str, value: &str| {
                facts.push(ScopedFact {
                    location: Location::MarkdownItem { key: item.key },
                    predicate: predicate.into(),
                    value: value.into(),
                })
            };
            add("example.kind", "entry");
            if i % 4 != 3 {
                add("example.colour", ["red", "blue", "Éclair"][i % 4]);
            }
            // Sorting must use the minimum across all values, not only the value
            // that witnessed a filter, and duplicate facts must not duplicate hits.
            if i % 3 == 0 {
                add("example.colour", "aaa");
                add("example.colour", "aaa");
            }
            add("example.number", ["10", "-2", "3.5", "invalid"][i % 4]);
            if i % 3 == 0 {
                add("example.number", "100");
            }
        }
        Ok(facts)
    }
}
fn setup(text: &str) -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().unwrap();
    let s = Store::open(dir.path()).unwrap();
    s.register_indexer(
        "example",
        1,
        &[PredicateKind {
            predicate: "example.number".into(),
            kind: ValueKind::Number,
        }],
        Arc::new(Labels),
    )
    .unwrap();
    for path in ["z.md", "Éclair.md", "a.md"] {
        std::fs::write(dir.path().join(path), text).unwrap();
    }
    s.reconcile().unwrap();
    s.advance_index().unwrap();
    (dir, s)
}
fn query() -> SearchRequest {
    SearchRequest {
        clauses: vec![],
        line_mode: true,
        extension_candidates: false,
        group_by_note: false,
        body_needles: vec![],
        name_needles: vec![],
        sort_predicate: String::new(),
        required_namespaces: vec!["example".into()],
        extension_key: String::new(),
        page_size: 2,
        max_lines: 2,
        cursor: None,
    }
}
fn clause(constraints: Vec<Constraint>, negated: bool) -> SearchClause {
    SearchClause {
        constraints,
        negated,
        case_sensitive: false,
        residual: None,
        never: false,
    }
}
fn at(predicate: &str, op: ConstraintOp, value: &str, locality: FactLocality) -> Constraint {
    Constraint::FactAt {
        predicate: predicate.into(),
        op,
        value: value.into(),
        locality,
    }
}
fn equivalent(s: &Store, q: &SearchRequest) {
    s.with_index(|db| {
        db.execute_batch("BEGIN")?;
        let mut after = None;
        for _ in 0..100 {
            let actual = items::search(
                db,
                q,
                None,
                "test".into(),
                "test".into(),
                after.clone(),
                vec![],
            )?;
            let expected = items::legacy_search(
                db,
                q,
                None,
                "test".into(),
                "test".into(),
                after.clone(),
                vec![],
            )?;
            assert_eq!(actual, expected, "query {q:?}, after {after:?}");
            if actual.next_cursor.is_none() {
                db.execute_batch("ROLLBACK")?;
                return Ok(());
            }
            after = decode_cursor(actual.next_cursor.as_deref(), "test", "test")?;
        }
        panic!("continuation did not terminate")
    })
    .unwrap();
}

#[test]
fn all_fact_operators_scopes_clause_negation_sorting_and_grouping_match_legacy() {
    let (_,s)=setup("\u{feff}---\r\ntitle: Sample\r\n---\r\n- first 😀\r\n  continued\r\n  - child\r\n- third\r\n- fourth\r\n- fifth\r\n");
    let mut cases = vec![query()];
    for locality in [
        FactLocality::Exact,
        FactLocality::DocumentRoot,
        FactLocality::Inherited,
    ] {
        for (op, value) in [
            (ConstraintOp::Eq, "red"),
            (ConstraintOp::Ne, "red"),
            (ConstraintOp::Exists, ""),
            (ConstraintOp::NotExists, ""),
            (ConstraintOp::Prefix, "é"),
            (ConstraintOp::Contains, "Lai"),
            (ConstraintOp::Ge, "blue"),
        ] {
            for negated in [false, true] {
                let mut q = query();
                q.clauses.push(clause(
                    vec![at("example.colour", op, value, locality)],
                    negated,
                ));
                cases.push(q);
            }
        }
        for values in [vec![], vec!["red".into(), "blue".into(), "red".into()]] {
            let mut q = query();
            q.clauses.push(clause(
                vec![Constraint::FactIn {
                    predicate: "example.colour".into(),
                    values,
                    locality,
                }],
                false,
            ));
            cases.push(q);
        }
    }
    for op in [
        ConstraintOp::Lt,
        ConstraintOp::Le,
        ConstraintOp::Gt,
        ConstraintOp::Ge,
    ] {
        for value in ["3.5", "NaN", "invalid"] {
            let mut q = query();
            q.clauses.push(clause(
                vec![at("example.number", op, value, FactLocality::Exact)],
                false,
            ));
            cases.push(q);
        }
    }
    let mut q = query();
    q.clauses.push(clause(
        vec![
            at(
                "example.number",
                ConstraintOp::Lt,
                "50",
                FactLocality::Exact,
            ),
            at(
                "example.number",
                ConstraintOp::Gt,
                "50",
                FactLocality::Exact,
            ),
        ],
        true,
    ));
    cases.push(q.clone());
    q.clauses[0].negated = false;
    assert_eq!(
        s.search_items(&q, None).unwrap().total_hits,
        3,
        "different numeric facts may witness each bound"
    );
    cases.push(q);
    for negated in [false, true] {
        let mut q = query();
        let mut c = clause(vec![], negated);
        c.never = true;
        q.clauses.push(c);
        cases.push(q);
    }
    for op in [ConstraintOp::Exists, ConstraintOp::NotExists] {
        let mut q = query();
        q.clauses.push(clause(
            vec![at("missing", op, "", FactLocality::Exact)],
            false,
        ));
        cases.push(q);
    }
    for mut q in cases {
        for sort in ["", "example.colour"] {
            q.sort_predicate = sort.into();
            for grouped in [false, true] {
                q.group_by_note = grouped;
                equivalent(&s, &q);
            }
        }
    }
    let mut q = query();
    q.max_lines = 0;
    equivalent(&s, &q);
    q.group_by_note = true;
    equivalent(&s, &q);
}

#[test]
fn projection_reads_only_returned_items_and_preserves_snapshot_cursor_guards() {
    let (dir, s) = setup(&"- entry 😀\n".repeat(1000));
    let q = query();
    target_projection::WORK.with(|w| w.set((0, 0)));
    crate::probe::reset_file_reads();
    let first = s.search_items(&q, None).unwrap();
    assert_eq!(first.total_hits, 3000);
    assert_eq!(first.hits.len(), 2);
    target_projection::WORK.with(|w| assert_eq!(w.get(), (2, 2)));
    assert_eq!(crate::probe::file_reads(), 0);
    std::fs::write(dir.path().join("a.md"), "- edited\n").unwrap();
    // The indexed snapshot remains authoritative until explicit maintenance.
    assert_eq!(first, s.search_items(&q, None).unwrap());
    s.reconcile().unwrap();
    let mut next = q;
    next.cursor = first.next_cursor;
    assert!(matches!(
        s.search_items(&next, None),
        Err(KernelError::SearchCursorExpired)
    ));
}

#[test]
fn incomplete_source_receipts_and_detached_stale_facts_match_legacy() {
    let (_, s) = setup("- entry\n- another\n");
    let mut q = query();
    q.clauses.push(clause(
        vec![at(
            "example.colour",
            ConstraintOp::Ne,
            "red",
            FactLocality::Inherited,
        )],
        false,
    ));
    s.with_index(|db| {
        db.execute(
            "DELETE FROM fact_sources WHERE namespace='example' AND rel_path='a.md'",
            [],
        )?;
        db.execute(
            "UPDATE source_maps SET source_version='replacement' WHERE rel_path='z.md'",
            [],
        )?;
        Ok(())
    })
    .unwrap();
    equivalent(&s, &q);
    let p = s.search_items(&q, None).unwrap();
    assert!(!p.index_complete);
    assert!(p.hits.iter().all(|h| h.rel_path == "Éclair.md"));
}

#[test]
fn coincident_predicate_names_from_distinct_owners_keep_facts_and_stable_sorting() {
    let (_, s) = setup("- first\n- second\n- third\n- fourth\n");
    s.register_indexer("other", 1, &[], Arc::new(Labels))
        .unwrap();
    s.advance_index().unwrap();
    let mut q = query();
    q.sort_predicate = "example.colour".into();
    equivalent(&s, &q);
    q.clauses.push(clause(
        vec![Constraint::Fact {
            predicate: "example.colour".into(),
            op: ConstraintOp::Eq,
            value: "root".into(),
        }],
        false,
    ));
    equivalent(&s, &q);
}

#[test]
fn text_relevance_mixed_negation_and_grouped_pages_match_the_previous_executor() {
    let (_, s) = setup("---\r\ntitle: properties\r\n---\r\n- First 😀\r\n  continued\r\n  - child é\r\n- third\r\n- fourth first\r\n");
    for target in [TextTarget::Any, TextTarget::Body, TextTarget::Name, TextTarget::Path] {
        for text in ["first", "First", "\n  continued", "é", "missing", ""] {
            for negated in [false, true] {
                for grouped in [false, true] {
                    let mut q = query();
                    q.group_by_note = grouped;
                    q.body_needles = vec!["first".into(), "é".into()];
                    q.name_needles = vec!["é".into()];
                    q.clauses.push(clause(vec![at("example.colour", ConstraintOp::Eq, "red", FactLocality::Exact), Constraint::Text { target, query: text.into() }], negated));
                    equivalent(&s, &q);
                    q.clauses[0].case_sensitive = true;
                    q.sort_predicate = "example.colour".into();
                    equivalent(&s, &q);
                }
            }
        }
    }
}

#[test]
fn independent_pages_and_counts_are_generation_bound_and_cancelled_queries_do_not_publish() {
    let (dir, s) = setup(&"- entry\n".repeat(100));
    let q = query();
    let reader = Arc::new(s.prepare_search(q.clone(), true).unwrap());
    let page = reader.page(None, None).unwrap();
    assert_eq!(page.hits.len(), 2);
    assert_eq!(page.total_hits, None);
    assert!(page.index_complete);
    // A page and a count can run concurrently while the writer connection holds
    // an uncommitted transaction. They read independent committed WAL snapshots.
    s.with_index(|db| {
        db.execute_batch("BEGIN IMMEDIATE")?;
        std::thread::scope(|scope| {
            let counted = scope.spawn(|| reader.count(&page.generation, None).unwrap());
            let next = reader.page(page.next_cursor.clone(), None).unwrap();
            assert_eq!(next.generation, page.generation);
            assert_eq!(counted.join().unwrap().total_hits, 300);
        });
        db.execute_batch("ROLLBACK")?;
        Ok(())
    }).unwrap();
    std::fs::write(dir.path().join("a.md"), "- changed").unwrap();
    s.reconcile().unwrap();
    s.advance_index().unwrap();
    assert!(matches!(reader.count(&page.generation, None), Err(KernelError::SearchCursorExpired)));
    assert!(matches!(reader.page(page.next_cursor, None), Err(KernelError::SearchCursorExpired)));
    reader.cancel();
    assert!(reader.page(None, None).is_err());
    assert!(reader.count(&page.generation, None).is_err());
}

#[test]
fn cancelling_a_running_callback_count_keeps_an_independent_page_available() {
    use std::sync::{Barrier, atomic::{AtomicBool, Ordering}};
    struct Gate { entered: Barrier, release: Barrier, first: AtomicBool }
    impl SearchExtension for Gate {
        fn matches(&self, _: u32, _: &SearchDocument) -> std::result::Result<bool,String> {
            if !self.first.swap(true, Ordering::SeqCst) { self.entered.wait(); self.release.wait(); }
            Ok(true)
        }
    }
    let (_, s) = setup("- entry");
    let reader = s.prepare_search(query(), true).unwrap();
    let generation = reader.page(None, None).unwrap().generation;
    let mut request = query();
    let mut c = clause(vec![], false); c.residual = Some(0); request.clauses.push(c);
    let slow = s.prepare_search(request, true).unwrap();
    let gate = Gate { entered: Barrier::new(2), release: Barrier::new(2), first: AtomicBool::new(false) };
    std::thread::scope(|scope| {
        let count = scope.spawn(|| slow.count(&generation, Some(&gate)));
        gate.entered.wait();
        assert_eq!(reader.page(None,None).unwrap().hits.len(), 2);
        slow.cancel();
        gate.release.wait();
        assert!(count.join().unwrap().is_err());
    });
}

#[test]
fn text_windows_cross_long_own_ranges_and_revisit_nested_children() {
    let text=format!("- Parent 😀\r\n{}  - Child é\r\n{}- Last needle\r\n", "  continued needle\r\n".repeat(600), "    child continued\r\n".repeat(600));
    let (_,s)=setup(&text);
    let mut q=query(); q.body_needles=vec!["needle".into()];
    q.clauses.push(clause(vec![Constraint::Text {target:TextTarget::Body,query:"needle".into()}],false));
    equivalent(&s,&q);
    q.group_by_note=true; equivalent(&s,&q);
}
