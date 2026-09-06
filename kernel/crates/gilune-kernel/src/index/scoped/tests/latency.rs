//! Manual measurements, never wall-clock assertions in CI.
use super::*;
use crate::search::{SearchClause, SearchRequest};
use std::time::{Duration, Instant};

struct Legacy;
impl NoteIndexer for Legacy {
    fn index(&self, _: &str, text: &str) -> std::result::Result<Vec<Fact>, String> {
        Ok(text
            .lines()
            .enumerate()
            .flat_map(|(i, line)| {
                ["red", "square"]
                    .into_iter()
                    .filter(move |word| line.contains(word))
                    .map(move |word| Fact {
                        line: Some(i as u32 + 1),
                        predicate: format!("fixture.legacy.{word}"),
                        value: word.into(),
                    })
            })
            .collect())
    }
}

fn median(mut values: Vec<Duration>) -> Duration {
    values.sort();
    values[values.len() / 2]
}

#[test]
#[ignore = "manual release latency comparison; prints measurements without timing assertions"]
fn compare_existing_line_search_and_scoped_discovery() {
    for size in [100, 10_005] {
        let (_dir, store, _) = fixture(&"- red square\n".repeat(size));
        store
            .register_indexer("fixture.legacy", 1, &[], Arc::new(Legacy))
            .unwrap();
        store.advance_index().unwrap();
        let mut scoped = request(vec![
            eq("fixture.colour", "red"),
            eq("fixture.shape", "square"),
        ]);
        scoped.kind = LocationKind::MarkdownItem;
        scoped.page_size = 50;
        let constraints = vec![
            eq("fixture.legacy.red", "red"),
            eq("fixture.legacy.square", "square"),
        ];
        let mut search = SearchRequest {
            clauses: vec![SearchClause {
                constraints: constraints.clone(),
                negated: false,
                case_sensitive: false,
                residual: None,
                never: false,
            }],
            line_mode: true,
            extension_candidates: false,
            group_by_note: false,
            body_needles: Vec::new(),
            name_needles: Vec::new(),
            sort_predicate: String::new(),
            required_namespaces: vec!["fixture.legacy".into()],
            extension_key: String::new(),
            page_size: 50,
            max_lines: 1,
            cursor: None,
        };
        // Warm both paths before comparable repeated measurements. One list item
        // per line means both paths answer exactly the same set of locations.
        store.query_locations(&scoped).unwrap();
        store.search(&search, None).unwrap();
        let mut item_search = search.clone();
        item_search.required_namespaces = scoped.required_namespaces.clone();
        item_search.clauses[0].constraints = scoped.constraints.clone();
        store.search_items(&item_search, None).unwrap();
        let mut item_first = Vec::new();
        let mut item_all = Vec::new();
        let mut scoped_first = Vec::new();
        let mut legacy_first = Vec::new();
        let mut anchors_first = Vec::new();
        let mut scoped_all = Vec::new();
        let mut legacy_all = Vec::new();
        crate::probe::reset_file_reads();
        for _ in 0..3 {
            scoped.cursor = None;
            scoped.page_size = 50;
            let start = Instant::now();
            let p = store.query_locations(&scoped).unwrap();
            scoped_first.push(start.elapsed());
            assert_eq!(p.matches.len(), size.min(50));
            search.cursor = None;
            search.page_size = 50;
            let start = Instant::now();
            let p = store.search(&search, None).unwrap();
            legacy_first.push(start.elapsed());
            assert_eq!(p.total_hits, size as u64);
            item_search.cursor = None;
            item_search.page_size = 50;
            let start = Instant::now();
            let p = store.search_items(&item_search, None).unwrap();
            item_first.push(start.elapsed());
            assert_eq!(p.total_hits, size as u64);
            let start = Instant::now();
            store.query(&constraints, QueryScope::Line, 50).unwrap();
            anchors_first.push(start.elapsed());
            scoped.page_size = 500;
            let start = Instant::now();
            let mut count = 0;
            loop {
                let p = store.query_locations(&scoped).unwrap();
                count += p.matches.len();
                scoped.cursor = p.next_cursor;
                if scoped.cursor.is_none() {
                    break;
                }
            }
            scoped_all.push(start.elapsed());
            assert_eq!(count, size);
            search.page_size = 500;
            let start = Instant::now();
            let mut count = 0;
            loop {
                let p = store.search(&search, None).unwrap();
                count += p.hits.len();
                search.cursor = p.next_cursor;
                if search.cursor.is_none() {
                    break;
                }
            }
            legacy_all.push(start.elapsed());
            assert_eq!(count, size);
            item_search.page_size = 500;
            let start = Instant::now();
            let mut count = 0;
            loop {
                let p = store.search_items(&item_search, None).unwrap();
                count += p.hits.len();
                item_search.cursor = p.next_cursor;
                if item_search.cursor.is_none() {
                    break;
                }
            }
            item_all.push(start.elapsed());
            assert_eq!(count, size);
        }
        println!("LATENCY {size} one-line items; median of 3 warm runs; first=50, all=500/page");
        println!(
            "scoped first {:?}; existing search first {:?}; legacy anchor-only first {:?}",
            median(scoped_first),
            median(legacy_first),
            median(anchors_first)
        );
        println!(
            "scoped all {:?}; existing search all {:?}; source reads {}",
            median(scoped_all),
            median(legacy_all),
            crate::probe::file_reads()
        );
        println!(
            "complete item search first {:?}; all {:?}",
            median(item_first),
            median(item_all)
        );
        assert_eq!(crate::probe::file_reads(), 0);
    }
}
