//! Transaction-scoped fact writes shared by every producer. No plugin semantics.
//! Locations and predicates are interned once per batch; facts store integer IDs.
use super::*;
use rusqlite::{params, Connection, OptionalExtension};

type Anchor = (i64, i64);

fn anchor(row: &FactRow) -> Option<Anchor> {
    match (row.line, row.item_key) {
        (None, Some(key)) => Some((2, key)),
        (Some(line), None) => Some((1, line)),
        (None, None) => Some((0, 0)),
        (Some(_), Some(_)) => None,
    }
}

struct Bindings<'a> {
    predicates: BTreeMap<&'a str, i64>,
    version: Option<String>,
    targets: BTreeMap<Anchor, i64>,
}

impl<'a> Bindings<'a> {
    fn resolve(db: &Connection, namespace: &str, path: &str, facts: &'a [FactRow]) -> Result<Self> {
        // These IDs are valid only inside the caller's write transaction. Never
        // cache them across a commit, source replacement or dictionary removal.
        let mut predicates = BTreeMap::new();
        let names: BTreeSet<&str> = facts.iter().map(|f| f.predicate.as_str()).collect();
        let mut insert = db.prepare_cached("INSERT INTO index_predicates(namespace,predicate) VALUES(?1,?2) ON CONFLICT(namespace,predicate) DO NOTHING")?;
        let mut lookup = db.prepare_cached(
            "SELECT id FROM index_predicates WHERE namespace=?1 AND predicate=?2",
        )?;
        for predicate in names {
            insert.execute(params![namespace, predicate])?;
            predicates.insert(
                predicate,
                lookup.query_row(params![namespace, predicate], |r| r.get(0))?,
            );
        }

        let source: Option<(i64, String)> = if facts.iter().any(|f| f.source_version.is_some()) {
            db.query_row(
                "SELECT id,source_version FROM source_maps WHERE rel_path=?1",
                [path],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?
        } else {
            None
        };
        let mut targets = BTreeMap::new();
        let version = if let Some((source_id, version)) = source {
            let wanted: BTreeSet<Anchor> = facts
                .iter()
                .filter(|f| f.source_version.as_deref() == Some(version.as_str()))
                .filter_map(anchor)
                .collect();
            // A page property must not load thousands of unrelated item/line
            // targets. Large batches scan each requested kind once; memory is
            // bounded by the distinct anchors actually referenced by the batch.
            if wanted.len() <= 64 {
                for key in wanted {
                    if let Some(id) = positions::target(db, source_id, key.0, key.1)? {
                        targets.insert(key, id);
                    }
                }
            } else {
                let kinds: BTreeSet<i64> = wanted.iter().map(|k| k.0).collect();
                let mut lookup = db.prepare_cached(
                    "SELECT kind,local_key,id FROM index_targets WHERE source_id=?1 AND kind=?2",
                )?;
                for kind in kinds {
                    for row in lookup.query_map(params![source_id, kind], |r| {
                        Ok(((r.get(0)?, r.get(1)?), r.get(2)?))
                    })? {
                        let (key, id) = row?;
                        if wanted.contains(&key) {
                            targets.insert(key, id);
                        }
                    }
                }
            }
            Some(version)
        } else {
            None
        };
        Ok(Self {
            predicates,
            version,
            targets,
        })
    }

    fn target(&self, fact: &FactRow) -> Option<i64> {
        if self.version.is_none() || self.version.as_deref() != fact.source_version.as_deref() {
            return None;
        }
        anchor(fact).and_then(|a| self.targets.get(&a).copied())
    }
}

#[cfg(test)]
pub(super) fn put_facts(
    db: &Connection,
    namespace: &str,
    path: &str,
    facts: &[FactRow],
    source_version: Option<&str>,
) -> Result<()> {
    // The existing plan commit owns rollback, producer/cursor checks and receipts.
    // Refuse accidental standalone use: another writer could otherwise invalidate
    // the resolved source or predicate IDs before the facts are inserted.
    if db.is_autocommit() {
        return Err(KernelError::SearchInvalid {
            detail: "fact batch requires a write transaction".into(),
        });
    }
    let bindings = Bindings::resolve(db, namespace, path, facts)?;
    if let Some(version) = source_version {
        db.execute(
            "INSERT INTO fact_sources(namespace,rel_path,source_version) VALUES(?1,?2,?3)",
            params![namespace, path, version],
        )?;
    }
    let locations = intern_locations(db, path, facts, &bindings)?;
    let mut insert = db.prepare_cached("INSERT INTO fact_values(location_id,predicate_id,target_id,value,num,typed) VALUES(?1,?2,?3,?4,?5,?6)")?;
    for fact in facts {
        insert.execute(params![
            locations[&(&fact.source_version, fact.line, fact.item_key)],
            bindings.predicates[fact.predicate.as_str()],
            bindings.target(fact),
            fact.value,
            fact.num,
            fact.typed as i64
        ])?;
    }
    Ok(())
}

type LocationKey<'a> = (&'a Option<String>, Option<i64>, Option<i64>);

fn intern_locations<'a>(
    db: &Connection,
    path: &str,
    facts: &'a [FactRow],
    bindings: &Bindings<'_>,
) -> Result<BTreeMap<LocationKey<'a>, i64>> {
    let mut locations = BTreeMap::new();
    let mut frames = BTreeMap::new();
    let mut frame_insert = db.prepare_cached(
        "INSERT OR IGNORE INTO fact_frames(rel_path,source_version) VALUES(?1,?2)",
    )?;
    let mut frame_lookup = db.prepare_cached("SELECT id FROM fact_frames WHERE rel_path=?1 AND COALESCE(source_version,'')=COALESCE(?2,'')")?;
    for version in facts.iter().map(|f| &f.source_version) {
        if let std::collections::btree_map::Entry::Vacant(entry) = frames.entry(version) {
            let args = params![path, version];
            // Avoid an attempted write for an already interned frame.
            let id = match frame_lookup
                .query_row(args, |r| r.get::<_, i64>(0))
                .optional()?
            {
                Some(id) => id,
                None => {
                    frame_insert.execute(args)?;
                    frame_lookup.query_row(args, |r| r.get(0))?
                }
            };
            entry.insert(id);
        }
    }
    let mut intern = db.prepare_cached(
        "INSERT INTO fact_location_slots(frame_id,target_id,line,item_key) VALUES(?1,?2,?3,?4) RETURNING id",
    )?;
    let mut lookup = db.prepare_cached("SELECT id FROM fact_location_slots WHERE frame_id=?1 AND COALESCE(target_id,-1)=COALESCE(?2,-1) AND COALESCE(line,-1)=COALESCE(?3,-1) AND COALESCE(item_key,-1)=COALESCE(?4,-1)")?;
    for fact in facts {
        let key = (&fact.source_version, fact.line, fact.item_key);
        if let std::collections::btree_map::Entry::Vacant(entry) = locations.entry(key) {
            let target = bindings.target(fact);
            let line = fact.line.map(|n| if target.is_some() { 0 } else { n });
            let item = fact.item_key.map(|n| if target.is_some() { 0 } else { n });
            let args = params![frames[&fact.source_version], target, line, item];
            let id = match lookup.query_row(args, |r| r.get::<_, i64>(0)).optional()? {
                Some(id) => id,
                None => intern.query_row(args, |r| r.get(0))?,
            };
            entry.insert(id);
        }
    }
    Ok(locations)
}

#[derive(Debug, Default, PartialEq, Eq)]
pub(super) struct Changes {
    pub unchanged: usize,
    pub binding_only: usize,
    pub values_changed: usize,
    pub inserted: usize,
    pub deleted: usize,
}

struct Stored {
    id: i64,
    location: i64,
    predicate: i64,
    target: Option<i64>,
    value: String,
    num: Option<f64>,
    typed: bool,
}

/// Compare a producer's complete ordered output inside its own transaction.
/// Slots preserve output order and multiplicity; they are not stable domain IDs.
/// A changed source still requires new location/target bindings for retained values.
pub(super) fn replace_facts(
    db: &Connection,
    namespace: &str,
    path: &str,
    facts: &[FactRow],
    source_version: Option<&str>,
) -> Result<Changes> {
    replace_facts_mode(db, namespace, path, facts, source_version, false)
}

pub(super) fn replace_facts_mode(
    db: &Connection,
    namespace: &str,
    path: &str,
    facts: &[FactRow],
    source_version: Option<&str>,
    by_location: bool,
) -> Result<Changes> {
    let _profile = crate::update_profile::span("diff_facts_receipt_indexes", namespace);
    if db.is_autocommit() {
        return Err(KernelError::SearchInvalid {
            detail: "fact replacement requires a write transaction".into(),
        });
    }
    let bindings = Bindings::resolve(db, namespace, path, facts)?;
    let locations = intern_locations(db, path, facts, &bindings)?;
    let old: Vec<Stored> = db.prepare_cached("SELECT f.id,f.location_id,f.predicate_id,f.target_id,f.value,f.num,f.typed FROM fact_locations l CROSS JOIN fact_values f INDEXED BY fact_values_location ON f.location_id=l.id CROSS JOIN index_predicates p ON p.id=f.predicate_id WHERE l.rel_path=?1 AND p.namespace=?2 ORDER BY f.id")?
        .query_map(params![path,namespace], |r| Ok(Stored { id:r.get(0)?, location:r.get(1)?, predicate:r.get(2)?, target:r.get(3)?, value:r.get(4)?, num:r.get(5)?, typed:r.get(6)? }))?
        .collect::<rusqlite::Result<_>>()?;
    let mut available: BTreeMap<i64, std::collections::VecDeque<usize>> = BTreeMap::new();
    if by_location {
        for (i, row) in old.iter().enumerate() {
            available.entry(row.location).or_default().push_back(i);
        }
    }
    let mut used = vec![false; old.len()];
    let mut changes = Changes::default();
    let mut relocate =
        db.prepare_cached("UPDATE fact_values SET location_id=?1,target_id=?2 WHERE id=?3")?;
    let mut update = db.prepare_cached("UPDATE fact_values SET location_id=?1,predicate_id=?2,target_id=?3,value=?4,num=?5,typed=?6 WHERE id=?7")?;
    let mut insert = db.prepare_cached("INSERT INTO fact_values(location_id,predicate_id,target_id,value,num,typed) VALUES(?1,?2,?3,?4,?5,?6)")?;
    for (i, fact) in facts.iter().enumerate() {
        let location = locations[&(&fact.source_version, fact.line, fact.item_key)];
        let predicate = bindings.predicates[fact.predicate.as_str()];
        let target = bindings.target(fact);
        let slot = if by_location {
            available
                .get_mut(&location)
                .and_then(std::collections::VecDeque::pop_front)
        } else {
            (i < old.len()).then_some(i)
        };
        if let Some(slot) = slot {
            used[slot] = true;
            let old = &old[slot];
            if old.predicate != predicate
                || old.value != fact.value
                || old.num != fact.num
                || old.typed != fact.typed
            {
                update.execute(params![
                    location, predicate, target, fact.value, fact.num, fact.typed, old.id
                ])?;
                changes.values_changed += 1;
            } else if old.location != location || old.target != target {
                relocate.execute(params![location, target, old.id])?;
                changes.binding_only += 1;
            } else {
                changes.unchanged += 1;
            }
        } else {
            insert.execute(params![
                location, predicate, target, fact.value, fact.num, fact.typed
            ])?;
            changes.inserted += 1;
        }
    }
    let mut delete = db.prepare_cached("DELETE FROM fact_values WHERE id=?1")?;
    for (i, old) in old.iter().enumerate() {
        if !used[i] {
            delete.execute([old.id])?;
            changes.deleted += 1;
        }
    }
    if let Some(version) = source_version {
        db.execute("INSERT INTO fact_sources(namespace,rel_path,source_version) VALUES(?1,?2,?3) ON CONFLICT(namespace,rel_path) DO UPDATE SET source_version=excluded.source_version WHERE fact_sources.source_version IS NOT excluded.source_version", params![namespace,path,version])?;
    } else {
        db.execute(
            "DELETE FROM fact_sources WHERE namespace=?1 AND rel_path=?2",
            params![namespace, path],
        )?;
    }
    Ok(changes)
}

#[cfg(test)]
mod differential_tests;
#[cfg(test)]
mod tests;
