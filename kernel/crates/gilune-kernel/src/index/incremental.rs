//! Optional local-input derivation. Shared Markdown context determines which
//! inputs changed; a bounded disposable cache reuses only successful outputs.
//! Plugins opting in may depend on their own ranges and document metadata,
//! never unrelated targets, absolute coordinates or the source-version token.
//! Document-root input is metadata only; item input excludes nested children.
use super::{scoped::ScopedFact, Fact, NoteIndexer};
use gilune_document::{ByteSpan, Location, TextSnapshot};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, VecDeque};
use std::sync::{Arc, Mutex};

type Key = (u8, u64);
type Fingerprint = [u8; 32];

fn key(location: &Location) -> Key {
    match location {
        Location::DocumentRoot => (0, 0),
        Location::BodyLine { number } => (1, *number as u64),
        Location::MarkdownItem { key } => (2, *key),
    }
}
fn location(key: Key) -> Location {
    match key.0 {
        0 => Location::DocumentRoot,
        1 => Location::BodyLine {
            number: key.1 as u32,
        },
        _ => Location::MarkdownItem { key: key.1 },
    }
}

struct Cached {
    path: String,
    inputs: BTreeMap<Key, Fingerprint>,
    facts: BTreeMap<Key, Vec<ScopedFact>>,
}

/// Wrap an explicitly local producer. Eviction/restart always falls back to
/// complete derivation. One document is retained, with a 32 MiB payload limit.
pub fn local(indexer: Arc<dyn NoteIndexer>) -> Arc<dyn NoteIndexer> {
    Arc::new(Local {
        inner: indexer,
        cached: Mutex::new(None),
    })
}

struct Local {
    inner: Arc<dyn NoteIndexer>,
    cached: Mutex<Option<Cached>>,
}
impl NoteIndexer for Local {
    fn index(&self, _: &str, _: &str) -> Result<Vec<Fact>, String> {
        Err("scoped source input required".into())
    }
    fn uses_scoped_input(&self) -> bool {
        true
    }
    fn index_scoped(&self, path: &str, source: &TextSnapshot) -> Result<Vec<ScopedFact>, String> {
        let inputs = inputs(source);
        // Do not hold a lock across foreign code. Concurrent/reentrant calls may
        // evict the optimisation; they cannot borrow another source's output.
        let previous = self
            .cached
            .lock()
            .map_err(|_| "local index cache poisoned")?
            .take();
        let mut reusable: BTreeMap<Fingerprint, VecDeque<Vec<ScopedFact>>> = BTreeMap::new();
        if let Some(old) = previous.filter(|c| c.path == path) {
            let mut facts = old.facts;
            for (at, fingerprint) in old.inputs {
                reusable
                    .entry(fingerprint)
                    .or_default()
                    .push_back(facts.remove(&at).unwrap_or_default());
            }
        }
        let mut output = BTreeMap::new();
        let mut changed = Vec::new();
        for (at, fingerprint) in &inputs {
            if let Some(mut facts) = reusable.get_mut(fingerprint).and_then(VecDeque::pop_front) {
                for fact in &mut facts {
                    fact.location = location(*at);
                }
                output.insert(*at, facts);
            } else {
                changed.push(location(*at));
            }
        }
        if !changed.is_empty() {
            let selected: std::collections::BTreeSet<_> = changed.iter().map(key).collect();
            let facts = self.inner.index_scoped_locations(path, source, &changed)?;
            for fact in facts {
                let at = key(&fact.location);
                if !selected.contains(&at) {
                    return Err(
                        "local indexer returned a fact outside its selected locations".into(),
                    );
                }
                output.entry(at).or_insert_with(Vec::new).push(fact);
            }
        }
        let facts: Vec<_> = output.values().flatten().cloned().collect();
        let bytes = source.text().len()
            + inputs.len() * 80
            + facts
                .iter()
                .map(|f| f.predicate.len() + f.value.len() + 128)
                .sum::<usize>();
        if bytes <= 32 * 1024 * 1024 {
            *self
                .cached
                .lock()
                .map_err(|_| "local index cache poisoned")? = Some(Cached {
                path: path.into(),
                inputs,
                facts: output,
            });
        }
        Ok(facts)
    }
}

fn inputs(source: &TextSnapshot) -> BTreeMap<Key, Fingerprint> {
    let _profile = crate::update_profile::span("local_input_diff", "");
    let layout = source.layout();
    let mut seed = Sha256::new();
    seed.update(layout.version.to_le_bytes());
    seed.update(&source.text().as_bytes()[..layout.body_start as usize]);
    let mut result = BTreeMap::new();
    let mut root = seed.clone();
    root.update([0]);
    result.insert((0, 0), root.finalize().into());
    for line in &layout.lines {
        result.insert(
            (1, line.number as u64),
            fingerprint(source, &seed, 1, &[line.range]),
        );
    }
    for item in &layout.items {
        result.insert(
            (2, item.key),
            fingerprint(source, &seed, 2, &item.own_ranges),
        );
    }
    result
}

fn fingerprint(source: &TextSnapshot, seed: &Sha256, kind: u8, ranges: &[ByteSpan]) -> Fingerprint {
    let mut hash = seed.clone();
    hash.update([kind]);
    // Include parser eligibility, not just raw text: a distant fence or link
    // definition can change the meaning of otherwise identical source bytes.
    let eligible = &source.layout().text_ranges;
    for range in ranges {
        hash.update((range.end - range.start).to_le_bytes());
        hash.update(&source.text().as_bytes()[range.start as usize..range.end as usize]);
        let start = eligible.partition_point(|r| r.end <= range.start);
        for span in eligible[start..].iter().take_while(|r| r.start < range.end) {
            hash.update((span.start.max(range.start) - range.start).to_le_bytes());
            hash.update((span.end.min(range.end) - range.start).to_le_bytes());
            if kind == 1 {
                // Ordinary line text moving into/out of an item's own range
                // changes which location receives references on that line.
                hash.update([u8::from(matches!(
                    source.location_at(span.start.max(range.start)),
                    Ok(Location::MarkdownItem { .. })
                ))]);
            }
        }
        hash.update(u64::MAX.to_le_bytes());
    }
    hash.finalize().into()
}

#[cfg(test)]
mod tests;
