//! Publish a bounded page of independently prepared producer results together.
//! No callbacks run in the transaction. Location reuse requires fresh output
//! from every owner sharing the old evidence; otherwise normal invalidation wins.
use super::*;
use rusqlite::{params, Connection, OptionalExtension};


impl Store {
    pub(super) fn publish_ready_page(
        &self,
        registered: &[Registration],
        epoch: &str,
        tip: i64,
        notes: &mut Notes,
        pass: &mut IndexPass,
    ) -> Result<bool> {
        let mut sources = vec![
            (TEXT_NAMESPACE, Source::Text),
            (FRONT_MATTER_NAMESPACE, Source::FrontMatter),
        ];
        sources.extend(
            registered
                .iter()
                .map(|r| (r.namespace.as_str(), Source::Plugin(r))),
        );
        let states = self.with_index(|db| {
            sources
                .iter()
                .map(|(namespace, _)| {
                    cursor_of(db, namespace)?;
                    Ok(db.query_row(
                        "SELECT cursor_seq,version FROM namespaces WHERE namespace=?1",
                        [namespace],
                        |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)),
                    )?)
                })
                .collect::<Result<Vec<_>>>()
        })?;
        let seq = states[0].0;
        if states.iter().any(|s| s.0 != seq)
            || sources.iter().zip(&states).any(|((_, source), (_, version))|
                matches!(source, Source::Plugin(r) if r.version != *version))
        {
            return Ok(false);
        }
        if seq == tip {
            return Ok(true);
        }
        let mut page = match self.changes_since(
            &Cursor {
                epoch: epoch.into(),
                seq,
            },
            BATCH,
        ) {
            Ok(page) => page,
            Err(KernelError::CursorUnknown { .. }) => return Ok(false),
            Err(e) => return Err(e),
        };
        page.changes.retain(|c| c.seq <= tip);
        if page
            .changes
            .iter()
            .map(|c| &c.rel_path)
            .collect::<BTreeSet<_>>()
            .len()
            > 1
        {
            return Ok(false);
        }
        // Rebuilds, moves, removals and lagging cursors keep the general path.
        // This bounds retained plugin output to one feed page and avoids running
        // any callback twice just to discover that publication is ineligible.
        if !page.complete
            || page.changes.is_empty()
            || page.changes.iter().any(|c| {
                !matches!(
                    c.kind,
                    ChangeKind::Created | ChangeKind::Modified | ChangeKind::Imported
                )
            })
        {
            return Ok(false);
        }
        let mut plans = Vec::new();
        for ((namespace, source), (cursor, version)) in sources.into_iter().zip(states) {
            let mut plan = self.plan(namespace, source, &page.changes, notes)?;
            plan.expected_cursor = cursor;
            plan.expected_version = version;
            plans.push((namespace, plan));
        }
        let committed = self.with_index(|db| publish(db, &plans))?;
        if let Some(delta) = committed {
            pass.indexed += delta.indexed;
            pass.removed += delta.removed;
            pass.moved += delta.moved;
            pass.failures.extend(delta.failures);
            Ok(true)
        } else {
            notes.0.clear();
            Ok(false)
        }
    }
}

fn publish(db: &Connection, plans: &[(&str, Plan)]) -> Result<Option<IndexPass>> {
    let _profile = crate::update_profile::span("publish_ready_page", "");
    let tx = rusqlite::Transaction::new_unchecked(db, rusqlite::TransactionBehavior::Immediate)?;
    for (namespace, plan) in plans {
        let current = tx
            .query_row(
                "SELECT cursor_seq,version FROM namespaces WHERE namespace=?1",
                [namespace],
                |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)),
            )
            .optional()?;
        if current != Some((plan.expected_cursor, plan.expected_version)) {
            return Ok(None);
        }
    }
    let reused = advance_locations(&tx, plans)?;
    let mut delta = IndexPass::default();
    for (namespace, plan) in plans {
        apply_plan(&tx, namespace, plan, &mut delta, &reused)?;
        if let Some(message) = &plan.failure {
            delta.failures.push(format!("{namespace}: {message}"));
        }
    }
    let _profile = crate::update_profile::span("durable_commit", "publication");
    tx.commit()?;
    Ok(Some(delta))
}

fn advance_locations(db: &Connection, plans: &[(&str, Plan)]) -> Result<BTreeSet<String>> {
    let mut reused = BTreeSet::new();
    let _profile = crate::update_profile::span("advance_shared_locations", "");
    let Some((_, text)) = plans.iter().find(|(ns, _)| *ns == TEXT_NAMESPACE) else {
        return Ok(reused);
    };
    for (_, step) in &text.steps {
        let Step::Put {
            rel_path: path,
            rows: Rows::Lines(lines, Some((version, layout))),
        } = step
        else {
            continue;
        };
        let old: Option<String> = db
            .query_row(
                "SELECT source_version FROM source_maps WHERE rel_path=?1",
                [path],
                |r| r.get(0),
            )
            .optional()?;
        let Some(old) = old.filter(|old| old != version) else {
            continue;
        };
        let ready: BTreeSet<&str> = plans
            .iter()
            .filter_map(|(namespace, plan)| {
                plan.steps.iter().find_map(|(_, step)| match step {
                    Step::Put {
                        rel_path,
                        rows: Rows::Facts(facts, Some(source)),
                    } if rel_path == path
                        && source == version
                        && facts
                            .iter()
                            .all(|f| f.source_version.as_ref() == Some(version)) =>
                    {
                        Some(*namespace)
                    }
                    _ => None,
                })
            })
            .collect();
        let owners: Vec<String> = db.prepare_cached("SELECT DISTINCT p.namespace FROM fact_locations l CROSS JOIN fact_values f INDEXED BY fact_values_location ON f.location_id=l.id CROSS JOIN index_predicates p ON p.id=f.predicate_id WHERE l.rel_path=?1 AND l.source_version=?2")?
            .query_map(params![path,old], |r| r.get(0))?.collect::<rusqlite::Result<_>>()?;
        if owners.iter().any(|ns| !ready.contains(ns.as_str())) {
            continue;
        }
        // A previously seen revision may still have locations held by another
        // producer. Do not merge identities or overwrite that producer's evidence.
        let collision: bool = db.query_row(
            "SELECT EXISTS(SELECT 1 FROM fact_frames WHERE rel_path=?1 AND source_version=?2)",
            params![path, version],
            |r| r.get(0),
        )?;
        if collision {
            continue;
        }
        if coordinates::advance(db, path, &old, version, layout, lines)? {
            reused.insert(path.clone());
        }
    }
    Ok(reused)
}

mod coordinates;
#[cfg(test)]
mod tests;
