use super::*;
use crate::index::{scoped::ScopedFact, Fact, FactLocality, NoteIndexer};
use gilune_document::{Location, TextSnapshot};
use std::sync::Arc;

struct Fixture;
impl NoteIndexer for Fixture {
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
            predicate: "fixture.colour".into(),
            value: "green".into(),
        }];
        for (word, predicate) in [
            ("record", "fixture.kind"),
            ("red", "fixture.colour"),
            ("blue", "fixture.colour"),
        ] {
            for (offset, _) in s.text().match_indices(word) {
                facts.push(ScopedFact {
                    location: s.location_at(offset as u64).unwrap(),
                    predicate: predicate.into(),
                    value: word.into(),
                });
            }
        }
        Ok(facts)
    }
}
fn setup(text: &str) -> (tempfile::TempDir, Store) {
    let (dir, store) = vault();
    store
        .register_indexer("fixture", 1, &[], Arc::new(Fixture))
        .unwrap();
    write(&store, "a.md", text);
    (dir, store)
}
fn fact(value: &str) -> Constraint {
    Constraint::FactAt {
        predicate: "fixture.colour".into(),
        op: index::ConstraintOp::Eq,
        value: value.into(),
        locality: FactLocality::Exact,
    }
}
fn query() -> SearchRequest {
    let mut q = request(&[]);
    q.line_mode = true;
    q.group_by_note = false;
    q.required_namespaces = vec!["fixture".into()];
    q.clauses.push(SearchClause {
        constraints: vec![Constraint::FactAt {
            predicate: "fixture.kind".into(),
            op: index::ConstraintOp::Exists,
            value: String::new(),
            locality: FactLocality::Exact,
        }],
        negated: false,
        case_sensitive: false,
        residual: None,
        never: false,
    });
    q
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
fn body(query: &str) -> Constraint {
    Constraint::Text {
        target: TextTarget::Body,
        query: query.into(),
    }
}

#[test]
fn own_text_spans_and_unicode_survive_crlf_metadata_and_nested_items() {
    let source="\u{feff}---\r\ntitle: Example\r\n---\r\n- record 😀 first\r\n  red second\r\n  - record blue child\r\n- record other\r\n";
    let (_dir, store) = setup(source);
    let mut q = query();
    q.clauses.push(clause(
        vec![body("first"), body("second"), fact("red")],
        false,
    ));
    q.body_needles = vec!["😀".into(), "second".into()];
    crate::probe::reset_file_reads();
    let p = store.search_items(&q, None).unwrap();
    assert!(p.index_complete, "{:?}", p.issues);
    assert_eq!(p.total_hits, 1);
    let i = &p.hits[0].items[0];
    assert_eq!(i.first_line, 1);
    assert_eq!(i.text, "- record 😀 first\n  red second");
    assert_eq!(
        i.source_version,
        TextSnapshot::new(source.into()).unwrap().version()
    );
    assert_eq!(i.ranges.len(), 2);
    assert_eq!(i.ranges[0].end - i.ranges[0].start, 2);
    assert_eq!(crate::probe::file_reads(), 0);
    assert!(p.hits[0].lines.is_empty());
    assert!(!i.facts.iter().any(|f| f.value == "green"));
    q.clauses.push(clause(vec![body("child")], false));
    assert_eq!(store.search_items(&q, None).unwrap().total_hits, 0);
}

#[test]
fn whole_clause_negatives_membership_and_explicit_root_queries() {
    let (_dir, store) =
        setup("- record red apple\n- record red pear\n- record blue apple\n- record plain\n");
    let mut q = query();
    q.clauses
        .push(clause(vec![fact("red"), body("apple")], true));
    assert_eq!(store.search_items(&q, None).unwrap().total_hits, 3);
    q.clauses.pop();
    q.clauses.push(clause(
        vec![Constraint::FactIn {
            predicate: "fixture.colour".into(),
            values: vec!["red".into(), "blue".into()],
            locality: FactLocality::Exact,
        }],
        true,
    ));
    assert_eq!(store.search_items(&q, None).unwrap().total_hits, 1);
    q.clauses.pop();
    q.clauses.push(clause(vec![fact("green")], false));
    assert_eq!(store.search_items(&q, None).unwrap().total_hits, 0);
    q.clauses.pop();
    q.clauses.push(clause(
        vec![Constraint::FactAt {
            predicate: "fixture.colour".into(),
            op: index::ConstraintOp::Eq,
            value: "green".into(),
            locality: FactLocality::DocumentRoot,
        }],
        false,
    ));
    assert_eq!(store.search_items(&q, None).unwrap().total_hits, 4);
    q.clauses = vec![clause(vec![fact("red")], true)];
    assert_eq!(
        store.search_items(&q, None).unwrap().total_hits,
        2,
        "negative-only has the shared item universe"
    );
}

#[test]
fn grouping_limits_sorting_paging_and_residuals_are_kernel_owned() {
    let (_dir, store) = setup("- record red\n- record blue\n- record red\n");
    let mut q = query();
    q.page_size = 1;
    q.max_lines = 1;
    q.sort_predicate = "fixture.colour".into();
    let first = store.search_items(&q, None).unwrap();
    assert_eq!(first.total_hits, 3);
    assert!(first.hits[0].items[0].text.contains("blue"));
    q.cursor = first.next_cursor;
    let second = store.search_items(&q, None).unwrap();
    assert_ne!(first.hits[0].items[0].key, second.hits[0].items[0].key);
    assert!(matches!(
        store.search(&q, None),
        Err(KernelError::SearchCursorExpired)
    ));
    q.cursor = None;
    q.group_by_note = true;
    let grouped = store.search_items(&q, None).unwrap();
    assert_eq!(grouped.total_hits, 1);
    assert_eq!(grouped.hits[0].items.len(), 1);
    assert_eq!(grouped.hits[0].more_matches, 2);
    struct Predicate;
    impl SearchExtension for Predicate {
        fn matches(&self, _: u32, d: &SearchDocument) -> std::result::Result<bool, String> {
            Ok(d.body.contains("blue"))
        }
    }
    q.group_by_note = false;
    q.clauses.push(SearchClause {
        constraints: vec![],
        negated: false,
        case_sensitive: false,
        residual: Some(0),
        never: false,
    });
    let p = store.search_items(&q, Some(&Predicate)).unwrap();
    assert_eq!(p.total_hits, 1);
    assert!(p.hits[0].items[0].text.contains("blue"));
    store.rebuild_index(None).unwrap();
    q.cursor = second.next_cursor;
    assert!(matches!(
        store.search_items(&q, Some(&Predicate)),
        Err(KernelError::SearchCursorExpired)
    ));
}

#[test]
fn document_search_can_find_contained_item_facts_and_sources_are_not_reread() {
    let (dir, store) = setup("- record red\n  continued\n");
    let mut q = request(&[]);
    q.required_namespaces = vec!["fixture".into()];
    q.clauses.push(clause(vec![fact("red")], false));
    std::fs::write(dir.path().join("a.md"), "outside edit").unwrap();
    crate::probe::reset_file_reads();
    assert_eq!(store.search(&q, None).unwrap().total_hits, 1);
    assert_eq!(crate::probe::file_reads(), 0);
}
