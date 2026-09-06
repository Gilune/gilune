//! An unrelated consumer of the public fact contract. The kernel knows
//! nothing about the vocabulary or the structure of each stored value.
use super::*;
use crate::index::{Fact, FactLocality, NoteIndexer, QueryScope};
use std::sync::Arc;

struct Fixture;
impl NoteIndexer for Fixture {
    fn index(&self, _: &str, text: &str) -> std::result::Result<Vec<Fact>, String> {
        let mut facts = Vec::new();
        for (n, line) in text.lines().enumerate() {
            for (prefix, predicate, root) in [
                ("root=", "fixture.value", true),
                ("value=", "fixture.value", false),
                ("kind=", "fixture.kind", false),
            ] {
                for value in line.split('|').filter_map(|s| s.strip_prefix(prefix)) {
                    facts.push(Fact {
                        line: if root { None } else { Some(n as u32 + 1) },
                        predicate: predicate.into(),
                        value: value.into(),
                    });
                }
            }
        }
        Ok(facts)
    }
}

fn setup(body: &str) -> (tempfile::TempDir, Store) {
    let (dir, store) = vault();
    store
        .register_indexer("fixture", 1, &[], Arc::new(Fixture))
        .expect("register");
    write(&store, "fixture.md", body);
    (dir, store)
}

fn membership(values: &[&str], locality: FactLocality) -> Constraint {
    Constraint::FactIn {
        predicate: "fixture.value".into(),
        values: values.iter().map(|v| v.to_string()).collect(),
        locality,
    }
}

fn filtered(constraint: Constraint) -> SearchRequest {
    let mut query = request(&[]);
    query.line_mode = true;
    query.group_by_note = false;
    query.required_namespaces.push("fixture".into());
    query.clauses = vec![SearchClause {
        constraints: vec![constraint],
        negated: false,
        case_sensitive: false,
        residual: None,
        never: false,
    }];
    query
}

fn lines(store: &Store, query: &SearchRequest) -> Vec<u32> {
    store
        .search(query, None)
        .expect("search")
        .hits
        .iter()
        .flat_map(|h| h.lines.iter().map(|l| l.number))
        .collect()
}

#[test]
fn membership_is_exact_existential_and_negation_complements_the_whole_set() {
    let (_dir, store) = setup("value=red|value=blue\nvalue=blue\nvalue=redder\nmissing\nvalue=RED");
    let mut query = filtered(membership(&["red", "blue", "red"], FactLocality::Exact));
    assert_eq!(lines(&store, &query), [1, 2]);
    query.clauses[0].negated = true;
    assert_eq!(lines(&store, &query), [3, 4, 5]);
    query.clauses[0].constraints = vec![membership(&[], FactLocality::Exact)];
    assert_eq!(lines(&store, &query), [1, 2, 3, 4, 5]);
    query.clauses[0].negated = false;
    assert!(lines(&store, &query).is_empty());
}

#[test]
fn values_are_opaque_even_when_they_contain_quotes_unicode_or_query_syntax() {
    let values = [
        "[\"category\",\"赤\"]",
        "') OR 1=1 --",
        "red\0tail",
        "red",
        "😀",
        "",
    ];
    let body = values
        .iter()
        .map(|value| format!("value={value}"))
        .collect::<Vec<_>>()
        .join("\n");
    let (_dir, store) = setup(&body);
    for (index, value) in values.iter().enumerate() {
        let actual = lines(&store, &filtered(membership(&[value], FactLocality::Exact)));
        let equal = lines(
            &store,
            &filtered(Constraint::FactAt {
                predicate: "fixture.value".into(),
                op: index::ConstraintOp::Eq,
                value: (*value).into(),
                locality: FactLocality::Exact,
            }),
        );
        assert_eq!(actual, [index as u32 + 1], "opaque member {value:?}");
        assert_eq!(actual, equal, "singleton membership equals scalar equality");
    }
}

#[test]
fn exact_facts_do_not_inherit_roots_and_root_queries_ignore_contained_values() {
    let (_dir, store) = setup("root=red\nkind=record|value=blue\nkind=record|value=red");
    let mut query = filtered(membership(&["red"], FactLocality::Exact));
    assert_eq!(lines(&store, &query), [3]);
    query.clauses[0].constraints = vec![membership(&["red"], FactLocality::DocumentRoot)];
    assert_eq!(lines(&store, &query), [1, 2, 3]);
    query.clauses[0].constraints = vec![membership(&["blue"], FactLocality::DocumentRoot)];
    assert!(lines(&store, &query).is_empty());
    query.clauses[0].constraints = vec![membership(&["red"], FactLocality::Inherited)];
    assert_eq!(lines(&store, &query), [1, 2, 3]);
    query.clauses[0].constraints = vec![Constraint::FactAt {
        predicate: "fixture.value".into(),
        op: index::ConstraintOp::Ne,
        value: "red".into(),
        locality: FactLocality::Exact,
    }];
    assert_eq!(lines(&store, &query), [1, 2]);
}

#[test]
fn legacy_anchor_queries_obey_explicit_scope_for_positive_and_negative_facts() {
    let (_dir, store) = setup("root=red\nkind=record|value=blue\nkind=record|value=red");
    let kind = Constraint::Fact {
        predicate: "fixture.kind".into(),
        op: index::ConstraintOp::Exists,
        value: String::new(),
    };
    let exact = membership(&["red"], FactLocality::Exact);
    let anchors = store
        .query(&[kind.clone(), exact], QueryScope::Line, 100)
        .expect("anchors");
    assert_eq!(
        anchors.iter().map(|a| a.line).collect::<Vec<_>>(),
        [Some(3)]
    );
    let negative = Constraint::FactAt {
        predicate: "fixture.value".into(),
        op: index::ConstraintOp::Ne,
        value: "red".into(),
        locality: FactLocality::Exact,
    };
    let anchors = store
        .query(&[kind, negative], QueryScope::Line, 100)
        .expect("negative");
    assert_eq!(
        anchors.iter().map(|a| a.line).collect::<Vec<_>>(),
        [Some(2)]
    );
    assert_eq!(
        store
            .query(
                &[membership(&["red"], FactLocality::DocumentRoot)],
                QueryScope::Note,
                100
            )
            .expect("root")
            .len(),
        1
    );
    assert!(store
        .query(
            &[membership(&[], FactLocality::Exact)],
            QueryScope::Note,
            100
        )
        .expect("empty")
        .is_empty());
}

#[test]
fn membership_composes_with_independent_type_facts_at_the_same_location() {
    let (_dir, store) = setup("kind=record|value=red\nkind=other|value=blue\nkind=record|value=green\nkind=record|value=blue");
    let mut query = filtered(membership(&["red", "blue"], FactLocality::Exact));
    query.clauses[0].constraints.push(Constraint::FactAt {
        predicate: "fixture.kind".into(),
        op: index::ConstraintOp::Eq,
        value: "record".into(),
        locality: FactLocality::Exact,
    });
    assert_eq!(lines(&store, &query), [1, 4]);
    query.line_mode = false;
    assert_eq!(store.search(&query, None).expect("document").total_hits, 1);
}

#[test]
fn equivalent_sets_can_continue_a_cursor_but_changed_sets_cannot() {
    let (_dir, store) = setup("value=red\nvalue=blue\nvalue=green");
    let mut query = filtered(membership(&["red", "blue"], FactLocality::Exact));
    query.page_size = 1;
    let first = store.search(&query, None).expect("first");
    assert_eq!(first.total_hits, 2);
    query.cursor = first.next_cursor;
    assert!(query.cursor.is_some());
    query.clauses[0].constraints = vec![membership(&["blue", "red", "blue"], FactLocality::Exact)];
    assert_eq!(lines(&store, &query), [2]);
    query.clauses[0].constraints = vec![membership(&["red", "green"], FactLocality::Exact)];
    assert!(matches!(
        store.search(&query, None),
        Err(KernelError::SearchCursorExpired)
    ));
}

#[test]
fn membership_bounds_are_checked_before_deduplication_on_both_apis() {
    let (_dir, store) = setup("value=red");
    for values in [
        vec!["red".into(); index::MAX_FACT_SET_VALUES + 1],
        vec!["x".repeat(index::MAX_FACT_SET_BYTES + 1)],
    ] {
        let constraint = Constraint::FactIn {
            predicate: "fixture.value".into(),
            values,
            locality: FactLocality::Exact,
        };
        assert!(matches!(
            store.query(std::slice::from_ref(&constraint), QueryScope::Line, 100),
            Err(KernelError::SearchInvalid { .. })
        ));
        assert!(matches!(
            store.search(&filtered(constraint), None),
            Err(KernelError::SearchInvalid { .. })
        ));
    }
}

#[test]
fn full_membership_results_page_past_ten_thousand_without_reading_sources() {
    let body = (0..10_051)
        .map(|n| {
            if n % 2 == 0 {
                "value=red"
            } else {
                "value=blue"
            }
        })
        .collect::<Vec<_>>()
        .join("\n");
    let (_dir, store) = setup(&body);
    let mut query = filtered(membership(&["red", "blue"], FactLocality::Exact));
    query.page_size = 500;
    std::fs::remove_file(store.vault().join("fixture.md"))
        .expect("remove source without reconciliation");
    crate::probe::reset_file_reads();
    let mut seen = std::collections::BTreeSet::new();
    loop {
        let page = store.search(&query, None).expect("page");
        assert_eq!(page.total_hits, 10_051);
        for hit in page.hits {
            assert!(seen.insert(hit.lines[0].number));
        }
        query.cursor = page.next_cursor;
        if query.cursor.is_none() {
            break;
        }
    }
    assert_eq!(seen.len(), 10_051);
    assert_eq!(crate::probe::file_reads(), 0);
}

#[test]
fn document_root_facts_are_returned_without_lines_or_filesystem_reads() {
    let (_dir, store) = setup("root=Alice\nvalue=child\n");
    let mut query = request(&[]);
    query.max_lines = 0;
    crate::probe::reset_file_reads();
    std::fs::write(store.vault().join("fixture.md"), "unindexed external change").unwrap();
    for sort in ["", "fixture.value"] {
        query.sort_predicate = sort.into();
        let page = store.search(&query, None).unwrap();
        assert!(page.hits[0].lines.is_empty());
        assert!(page.hits[0].facts.iter().any(|f| f.key == "fixture.value" && f.value == "Alice"));
        assert!(!page.hits[0].facts.iter().any(|f| f.value == "child"));
    }
    assert_eq!(crate::probe::file_reads(), 0);
}

#[test]
fn source_aware_indexing_keeps_legacy_body_input_and_reuses_the_same_read() {
    struct WholeSource;
    impl NoteIndexer for WholeSource {
        fn index(&self, _: &str, _: &str) -> std::result::Result<Vec<Fact>, String> {
            panic!("source-aware override must be used")
        }
        fn index_source(&self, _: &str, source: &str, body: &str) -> std::result::Result<Vec<Fact>, String> {
            assert!(source.starts_with("---\r\nname: Alice\r\n"));
            assert_eq!(body, "value=child\n");
            Ok(vec![Fact { line: None, predicate: "source.title".into(), value: "Alice".into() }])
        }
    }
    let (_dir, store) = vault();
    store.register_indexer("fixture", 1, &[], Arc::new(Fixture)).unwrap();
    store.register_indexer("source", 1, &[], Arc::new(WholeSource)).unwrap();
    write(&store, "source.md", "---\r\nname: Alice\r\n---\r\nvalue=child\r\n");
    let source = store.search(&request(&[]), None).unwrap();
    assert!(source.hits[0].facts.iter().any(|fact| fact.key == "source.title" && fact.value == "Alice"));
    assert_eq!(lines(&store, &filtered(membership(&["child"], FactLocality::Exact))), vec![1]);
    crate::probe::reset_file_reads();
    store.rebuild_index(None).unwrap();
    assert_eq!(crate::probe::file_reads(), 1, "all namespace callbacks share one source read");
}
