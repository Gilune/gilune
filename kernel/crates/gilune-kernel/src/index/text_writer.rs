//! Differential maintenance of indexed text. Plugins still receive full sources.
//! Row IDs are private FTS addresses, never document or domain object identities.
use super::*;
use rusqlite::{params, Connection, OptionalExtension};

#[derive(Debug, Default, PartialEq, Eq)]
pub(super) struct Changes {
    retained: usize,
    moved: usize,
    inserted: usize,
    deleted: usize,
}

struct Stored {
    id: i64,
    line: Option<i64>,
    target: Option<i64>,
    field: String,
    text: String,
}

pub(super) fn replace(
    db: &Connection,
    path: &str,
    rows: &[LineRow],
    source: Option<&(String, String)>,
) -> Result<Changes> {
    if db.is_autocommit() {
        return Err(KernelError::SearchInvalid {
            detail: "text replacement requires a write transaction".into(),
        });
    }
    // Source targets retain the existing invalidation protocol.
    // In particular, moving text does not validate a lagging plugin's facts.
    {
        let _profile = crate::update_profile::span("replace_source_map_targets", TEXT_NAMESPACE);
        let previous: Option<(String, String)> = db
            .query_row(
                "SELECT source_version,layout FROM source_maps WHERE rel_path=?1",
                [path],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        if previous.as_ref() != source {
            if let Some((version, layout)) = source {
                db.prepare_cached(
                    "INSERT INTO source_maps(rel_path,source_version,layout) VALUES(?1,?2,?3) ON CONFLICT(rel_path) DO UPDATE SET source_version=excluded.source_version,layout=excluded.layout",
                )?.execute(params![path, version, layout])?;
            } else {
                db.execute("DELETE FROM source_maps WHERE rel_path=?1", [path])?;
            }
        }
    }
    db.execute("DELETE FROM prepared_geometry WHERE source_id IN (SELECT id FROM source_maps WHERE rel_path=?1)",[path])?;
    let targets: BTreeMap<i64,i64> = db.prepare_cached("SELECT t.local_key,t.id FROM index_targets t JOIN source_maps s ON s.id=t.source_id WHERE s.rel_path=?1 AND t.kind=1")?
        .query_map([path],|r|Ok((r.get(0)?,r.get(1)?)))?.collect::<rusqlite::Result<_>>()?;
    let _profile = crate::update_profile::span("diff_text_rows_fts", TEXT_NAMESPACE);
    let stored: Vec<Stored> = db
        .prepare_cached("SELECT l.id,l.line,l.field,l.text,x.target_id FROM lines l JOIN text_rows x ON x.id=l.id WHERE l.rel_path=?1 ORDER BY l.id")?
        .query_map([path], |r| {
            Ok(Stored {
                id: r.get(0)?,
                line: r.get(1)?,
                target: r.get(4)?,
                field: r.get(2)?,
                text: r.get(3)?,
            })
        })?
        .collect::<rusqlite::Result<_>>()?;
    // Prefer exact coordinates before pairing moved equal text. Multisets retain
    // duplicate lines independently, without quadratic scans or guessed identity.
    let mut exact = BTreeMap::new();
    for (i, row) in stored.iter().enumerate() {
        exact
            .entry((row.field.as_str(), row.line, row.text.as_str()))
            .or_insert_with(Vec::new)
            .push(i);
    }
    let mut matches = vec![None; rows.len()];
    let mut used = vec![false; stored.len()];
    for (i, row) in rows.iter().enumerate() {
        if let Some(old) = exact
            .get_mut(&(row.field, row.line, row.text.as_str()))
            .and_then(Vec::pop)
        {
            matches[i] = Some(old);
            used[old] = true;
        }
    }
    drop(exact);
    let mut movable = BTreeMap::new();
    for (i, row) in stored.iter().enumerate() {
        if !used[i] {
            movable
                .entry((row.field.as_str(), row.text.as_str()))
                .or_insert_with(Vec::new)
                .push(i);
        }
    }
    for (i, row) in rows.iter().enumerate() {
        if matches[i].is_none() {
            if let Some(old) = movable
                .get_mut(&(row.field, row.text.as_str()))
                .and_then(Vec::pop)
            {
                matches[i] = Some(old);
                used[old] = true;
            }
        }
    }
    let mut changes = Changes::default();
    let mut delete = db.prepare_cached("DELETE FROM text_rows WHERE id=?1")?;
    for (i, row) in stored.iter().enumerate() {
        if !used[i] {
            delete.execute([row.id])?;
            changes.deleted += 1;
        }
    }
    // Only coordinates change here. FTS indexes folded text against the same ID,
    // so its existing insert/delete triggers intentionally need not run.
    let mut moved = db.prepare_cached("UPDATE text_rows SET line=?1,target_id=?2 WHERE id=?3")?;
    let mut insert = db.prepare_cached(
        "INSERT INTO text_rows(rel_path,line,field,text,folded,target_id) VALUES(?1,?2,?3,?4,?5,?6)",
    )?;
    for (row, old) in rows.iter().zip(matches) {
        let target = if row.field == "body" {
            row.line.and_then(|n| targets.get(&n).copied())
        } else {
            None
        };
        if let Some(old) = old {
            if stored[old].line != row.line || stored[old].target != target {
                moved.execute(params![row.line, target, stored[old].id])?;
                if stored[old].line != row.line {
                    changes.moved += 1;
                } else {
                    changes.retained += 1;
                }
            } else {
                changes.retained += 1;
            }
        } else {
            insert.execute(params![
                path,
                row.line,
                row.field,
                row.text,
                crate::search_text::fold(&row.text),
                target
            ])?;
            changes.inserted += 1;
        }
    }
    Ok(changes)
}

#[cfg(test)]
mod tests;
