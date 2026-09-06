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
                let mut lookup = db.prepare_cached(
                    "SELECT id FROM index_targets WHERE source_id=?1 AND kind=?2 AND local_key=?3",
                )?;
                for key in wanted {
                    if let Some(id) = lookup
                        .query_row(params![source_id, key.0, key.1], |r| r.get(0))
                        .optional()?
                    {
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
    let mut locations = BTreeMap::new();
    let mut intern = db.prepare_cached("INSERT OR IGNORE INTO fact_locations(rel_path,source_version,line,item_key) VALUES(?1,?2,?3,?4)")?;
    let mut lookup = db.prepare_cached("SELECT id FROM fact_locations WHERE rel_path=?1 AND COALESCE(source_version,'')=COALESCE(?2,'') AND COALESCE(line,-1)=COALESCE(?3,-1) AND COALESCE(item_key,-1)=COALESCE(?4,-1)")?;
    for fact in facts {
        let key = (&fact.source_version, fact.line, fact.item_key);
        if let std::collections::btree_map::Entry::Vacant(entry) = locations.entry(key) {
            let args = params![path, fact.source_version, fact.line, fact.item_key];
            intern.execute(args)?;
            entry.insert(lookup.query_row(args, |r| r.get::<_, i64>(0))?);
        }
    }
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

#[cfg(test)]
mod tests;
