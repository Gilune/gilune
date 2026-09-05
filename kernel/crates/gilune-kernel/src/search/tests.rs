use super::*;

fn vault() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().expect("temp");
    let store = Store::open(dir.path()).expect("open");
    (dir, store)
}
fn write(store: &Store, path: &str, body: &str) {
    std::fs::write(store.vault().join(path), body).expect("write");
    store.reconcile_path(path).expect("reconcile");
}
fn request(words: &[&str]) -> SearchRequest {
    SearchRequest {
        clauses: words
            .iter()
            .map(|word| SearchClause {
                constraints: vec![Constraint::Text {
                    target: TextTarget::Any,
                    query: word.to_string(),
                }],
                negated: false,
                case_sensitive: false,
                residual: None,
                never: false,
            })
            .collect(),
        line_mode: false,
        extension_candidates: false,
        group_by_note: true,
        body_needles: words.iter().map(|s| s.to_string()).collect(),
        name_needles: words.iter().map(|s| s.to_string()).collect(),
        sort_predicate: String::new(),
        required_namespaces: Vec::new(),
        extension_key: String::new(),
        page_size: 50,
        max_lines: 5,
        cursor: None,
    }
}

#[test]
fn document_terms_can_match_different_lines_but_line_terms_cannot() {
    let (_dir, store) = vault();
    write(&store, "example.md", "alpha first\n\nbeta second\n");
    let mut query = request(&["alpha", "beta"]);
    let page = store.search(&query, None).expect("search");
    assert!(page.index_complete);
    assert_eq!(page.total_hits, 1);
    assert_eq!(
        page.hits[0]
            .lines
            .iter()
            .map(|l| l.number)
            .collect::<Vec<_>>(),
        vec![1, 3]
    );
    query.line_mode = true;
    assert_eq!(
        store.search(&query, None).expect("line search").total_hits,
        0
    );
}

#[test]
fn unicode_matches_have_utf16_highlight_offsets_and_search_never_reads_vault_files() {
    let (_dir, store) = vault();
    write(&store, "unicode.md", "😀 Éclair\nCAFÉ\n");
    crate::probe::reset_file_reads();
    // Changing the un-reconciled file proves the result comes from the index.
    std::fs::write(store.vault().join("unicode.md"), "unindexed replacement")
        .expect("external write");
    let page = store
        .search(&request(&["éc"]), None)
        .expect("short unicode");
    assert_eq!(page.total_hits, 1);
    assert_eq!(
        page.hits[0].lines[0].ranges,
        vec![SearchRange { start: 3, end: 5 }]
    );
    assert_eq!(
        store
            .search(&request(&["café"]), None)
            .expect("long unicode")
            .total_hits,
        1
    );
    assert_eq!(crate::probe::file_reads(), 0);
}

#[test]
fn pagination_ranks_before_limiting_and_rejects_a_changed_snapshot() {
    let (_dir, store) = vault();
    write(&store, "a.md", "needle");
    write(&store, "b.md", "needle needle");
    write(&store, "needle.md", "other");
    let mut query = request(&["needle"]);
    query.page_size = 1;
    let first = store.search(&query, None).expect("first");
    assert_eq!(first.hits[0].rel_path, "needle.md");
    assert_eq!(first.total_hits, 3);
    query.cursor = first.next_cursor;
    let second = store.search(&query, None).expect("second");
    assert_eq!(second.hits[0].rel_path, "b.md");
    query.cursor = second.next_cursor;
    assert_eq!(
        store.search(&query, None).expect("third").hits[0].rel_path,
        "a.md"
    );
    write(&store, "a.md", "needle changed");
    assert!(matches!(
        store.search(&query, None),
        Err(KernelError::SearchCursorExpired)
    ));
}

#[test]
fn negative_only_query_finds_a_match_after_ten_thousand_rejected_candidates() {
    let (_dir, store) = vault();
    for n in 0..10_001 {
        std::fs::write(
            store.vault().join(format!("{n:05}.md")),
            if n == 10_000 { "keep" } else { "excluded" },
        )
        .expect("write");
    }
    store.reconcile().expect("index corpus");
    let mut query = request(&["excluded"]);
    query.clauses[0].negated = true;
    query.body_needles.clear();
    query.name_needles.clear();
    let page = store.search(&query, None).expect("search");
    assert_eq!(page.total_hits, 1);
    assert_eq!(page.hits[0].rel_path, "10000.md");
}

#[test]
fn plugin_predicates_receive_an_indexed_snapshot_and_fail_visibly() {
    struct Check;
    impl SearchExtension for Check {
        fn matches(&self, key: u32, doc: &SearchDocument) -> std::result::Result<bool, String> {
            if key == 1 {
                return Err("plugin unavailable".into());
            }
            Ok(doc.body.starts_with("start\n\n")
                && doc
                    .properties
                    .iter()
                    .any(|p| p.key == "tag" && p.value == "kept"))
        }
    }
    let (_dir, store) = vault();
    write(
        &store,
        "a.md",
        "---\ngilune-id: fixture\ntag: kept\n---\nstart\n\nend\n",
    );
    let mut query = request(&[]);
    query.clauses.push(SearchClause {
        constraints: vec![],
        negated: false,
        case_sensitive: false,
        residual: Some(0),
        never: false,
    });
    assert_eq!(
        store
            .search(&query, Some(&Check))
            .expect("plugin")
            .total_hits,
        1
    );
    query.clauses[0].residual = Some(1);
    assert!(matches!(
        store.search(&query, Some(&Check)),
        Err(KernelError::SearchInvalid { .. })
    ));
}

#[test]
fn missing_required_namespace_is_incomplete_rather_than_an_authoritative_empty_result() {
    let (_dir, store) = vault();
    write(&store, "a.md", "prose");
    let mut query = request(&[]);
    query.required_namespaces.push("test.missing".into());
    let page = store.search(&query, None).expect("status");
    assert!(!page.index_complete);
    assert!(page.issues.iter().any(|s| s.contains("test.missing")));
}

#[test]
fn representative_first_pages_are_bounded_and_do_not_read_vault_files() {
    let (_dir, store) = vault();
    for n in 0..1000 {
        let mut body = format!("note number {n}\n");
        for line in 1..20 {
            body.push_str(&format!("kitchen line {line} in this note\n"));
        }
        std::fs::write(store.vault().join(format!("{n:04}.md")), body).expect("write");
    }
    store.reconcile().expect("index corpus");
    crate::probe::reset_file_reads();
    for (label, query, count) in [
        ("broad", request(&["kitchen"]), 1000),
        ("selective", request(&["note number 500"]), 1),
        ("all", request(&[]), 1000),
    ] {
        let start = std::time::Instant::now();
        let page = store.search(&query, None).expect("search");
        println!(
            "search first page: {label}, {} us, {} hits of {}",
            start.elapsed().as_micros(),
            page.hits.len(),
            page.total_hits
        );
        assert_eq!(page.total_hits, count);
        assert!(page.hits.len() <= 50);
    }
    assert_eq!(crate::probe::file_reads(), 0);
}
