use super::*;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

struct Fixture {
    selected: Arc<AtomicUsize>,
    fail: Arc<AtomicBool>,
}
impl NoteIndexer for Fixture {
    fn index(&self, _: &str, _: &str) -> Result<Vec<Fact>, String> {
        unreachable!()
    }
    fn index_scoped_locations(
        &self,
        _: &str,
        source: &TextSnapshot,
        locations: &[Location],
    ) -> Result<Vec<ScopedFact>, String> {
        self.selected.store(locations.len(), Ordering::SeqCst);
        if self.fail.load(Ordering::SeqCst) {
            return Err("refused".into());
        }
        Ok(locations
            .iter()
            .map(|at| {
                let value = match at {
                    Location::DocumentRoot => {
                        source.text()[..source.layout().body_start as usize].to_string()
                    }
                    Location::BodyLine { number } => {
                        let line = &source.layout().lines[*number as usize - 1];
                        format!(
                            "{}:{:?}",
                            line.range.slice(source.text()).unwrap(),
                            source
                                .layout()
                                .text_ranges
                                .iter()
                                .filter(|r| r.start < line.range.end && r.end > line.range.start)
                                .map(|r| (
                                    r.start.max(line.range.start) - line.range.start,
                                    r.end.min(line.range.end) - line.range.start
                                ))
                                .collect::<Vec<_>>()
                        )
                    }
                    Location::MarkdownItem { key } => source
                        .layout()
                        .items
                        .iter()
                        .find(|i| i.key == *key)
                        .unwrap()
                        .own_ranges
                        .iter()
                        .map(|r| r.slice(source.text()).unwrap())
                        .collect::<Vec<_>>()
                        .join("|"),
                };
                ScopedFact {
                    location: at.clone(),
                    predicate: "example.value".into(),
                    value,
                }
            })
            .collect())
    }
}
fn fixture() -> (Arc<dyn NoteIndexer>, Arc<AtomicUsize>, Arc<AtomicBool>) {
    let selected = Arc::new(AtomicUsize::new(0));
    let fail = Arc::new(AtomicBool::new(false));
    (
        local(Arc::new(Fixture {
            selected: selected.clone(),
            fail: fail.clone(),
        })),
        selected,
        fail,
    )
}
fn snapshot(text: &str) -> TextSnapshot {
    TextSnapshot::new(text.into()).unwrap()
}

#[test]
fn one_line_edit_only_rederives_its_locations_and_insertions_relocate_unchanged_outputs() {
    let (indexer, selected, _) = fixture();
    let original = "- old\n".repeat(10000);
    indexer.index_scoped("a.md", &snapshot(&original)).unwrap();
    assert_eq!(selected.load(Ordering::SeqCst), 20002);
    let changed = original.replacen("old", "a longer replacement 😀", 1);
    let actual = indexer.index_scoped("a.md", &snapshot(&changed)).unwrap();
    assert_eq!(selected.load(Ordering::SeqCst), 2);
    let (full, _, _) = fixture();
    assert_eq!(
        actual,
        full.index_scoped("a.md", &snapshot(&changed)).unwrap()
    );
    let prefix = format!("new prose\n\n{changed}");
    let actual = indexer.index_scoped("a.md", &snapshot(&prefix)).unwrap();
    assert_eq!(selected.load(Ordering::SeqCst), 2);
    let (full, _, _) = fixture();
    assert_eq!(
        actual,
        full.index_scoped("a.md", &snapshot(&prefix)).unwrap()
    );
}

#[test]
fn fences_headers_nesting_duplicates_unicode_and_deletions_match_full_derivation() {
    let (indexer, _, _) = fixture();
    for text in [
        "---\nlabel: a\n---\n- a 😀\r\n  - child\r\n- a 😀\r\n",
        "---\nlabel: b\n---\n- a 😀\r\n  - child\r\n- a 😀\r\n",
        "```\n- a 😀\n```\n- visible\n",
        "- a\n  continuation\n",
        "prose\n  continuation\n",
        "[link][ref]\n\n[ref]: https://example.org\n",
        "[link][ref]\n",
        "",
        "- a\n- a\n",
    ] {
        let source = snapshot(text);
        let actual = indexer.index_scoped("a.md", &source).unwrap();
        let (full, _, _) = fixture();
        assert_eq!(
            actual,
            full.index_scoped("a.md", &source).unwrap(),
            "{text:?}"
        );
    }
}

#[test]
fn failed_callbacks_and_path_changes_cannot_reuse_unverified_output() {
    let (indexer, selected, fail) = fixture();
    indexer.index_scoped("a.md", &snapshot("- old\n")).unwrap();
    fail.store(true, Ordering::SeqCst);
    assert!(indexer.index_scoped("a.md", &snapshot("- new\n")).is_err());
    fail.store(false, Ordering::SeqCst);
    indexer.index_scoped("a.md", &snapshot("- new\n")).unwrap();
    assert_eq!(selected.load(Ordering::SeqCst), 4);
    indexer.index_scoped("b.md", &snapshot("- new\n")).unwrap();
    assert_eq!(selected.load(Ordering::SeqCst), 4);
}
