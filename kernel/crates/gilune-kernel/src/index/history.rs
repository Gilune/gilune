//! Line history (ADR 0025): for each line of a note's body, when the line
//! reached its current text — the "since" a `git blame` gives, read from the
//! note's own revisions and never written into the note.
//!
//! The revisions are replayed oldest first and each is line-diffed against
//! the one before: a line both share keeps its date, a line the newer one
//! introduced takes that revision's time. Two dates are deliberately
//! unknown rather than guessed: lines that arrived with the adoption of a
//! file Gilune did not write (`external-import` — the line may be years
//! older than the day Gilune first saw it), and lines on disk that no
//! revision has recorded yet. An edit made in another app is dated when the
//! kernel recorded it, which is when Gilune next looked, not when it was
//! typed.
//!
//! Concurrent heads are replayed in time order like everything else: the
//! history is an interpretation for queries, not the record, so a line both
//! branches wrote is dated by whichever wrote it first.
//!
//! The replay is cached per object in the index database — derived, so a
//! rebuild loses nothing — and extended by the revisions newer than the
//! cached one. Any count that does not add up (a synced revision older than
//! the cache) replays from the beginning.

use crate::store::Store;
use crate::Result;
use rusqlite::OptionalExtension;

/// When each body line reached its current text, in milliseconds since the
/// epoch, aligned with the body's lines; `None` when unknown.
pub type LineHistory = Vec<Option<i64>>;

/// One line and its date, as the replay carries them.
type Dated = (String, Option<i64>);

impl Store {
    /// The history of a note's current body, best-effort: an error or a
    /// note the kernel holds no object for answers every line unknown, so
    /// indexing never waits on it.
    pub(crate) fn line_history(&self, rel_path: &str, body: &str) -> LineHistory {
        let lines = split(body);
        let recorded = self
            .object_at(rel_path)
            .ok()
            .flatten()
            .and_then(|object_id| self.recorded_lines(&object_id).ok());
        match recorded {
            Some(recorded) => carry(&recorded, &lines, None).into_iter().map(|(_, since)| since).collect(),
            None => vec![None; lines.len()],
        }
    }

    fn object_at(&self, rel_path: &str) -> Result<Option<String>> {
        Ok(self
            .conn()
            .query_row(
                "SELECT object_id FROM projections WHERE rel_path = ?1",
                [rel_path],
                |row| row.get(0),
            )
            .optional()?)
    }

    /// The newest revision's lines with their dates, from the cache when it
    /// can be extended and from the beginning when it cannot.
    fn recorded_lines(&self, object_id: &str) -> Result<Vec<Dated>> {
        let total: i64 = self.conn().query_row(
            "SELECT COUNT(*) FROM revisions WHERE object_id = ?1",
            [object_id],
            |row| row.get(0),
        )?;
        let cached: Option<(i64, i64, String, String)> = self.with_index(|db| {
            Ok(db
                .query_row(
                    "SELECT revisions, last_created, last_revision, lines FROM line_history WHERE object_id = ?1",
                    [object_id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
                )
                .optional()?)
        })?;
        if let Some((count, created, revision, lines)) = cached {
            if count == total {
                if let Ok(lines) = serde_json::from_str(&lines) {
                    return Ok(lines);
                }
            } else if count < total {
                let newer = self.revisions_after(object_id, Some((created, &revision)))?;
                if count + newer.len() as i64 == total {
                    if let Ok(start) = serde_json::from_str::<Vec<Dated>>(&lines) {
                        let lines = replay(start, &newer);
                        self.save_history(object_id, total, &newer, &lines)?;
                        return Ok(lines);
                    }
                }
            }
        }
        let all = self.revisions_after(object_id, None)?;
        let lines = replay(Vec::new(), &all);
        self.save_history(object_id, total, &all, &lines)?;
        Ok(lines)
    }

    fn revisions_after(&self, object_id: &str, after: Option<(i64, &str)>) -> Result<Vec<Revision>> {
        let (created, revision) = after.unwrap_or((i64::MIN, ""));
        let mut statement = self.conn().prepare(
            "SELECT revision_id, created_ms, origin, payload FROM revisions
             WHERE object_id = ?1 AND (created_ms > ?2 OR (created_ms = ?2 AND revision_id > ?3))
             ORDER BY created_ms, revision_id",
        )?;
        let rows = statement.query_map(rusqlite::params![object_id, created, revision], |row| {
            let payload: String = row.get(3)?;
            Ok(Revision {
                id: row.get(0)?,
                created_ms: row.get(1)?,
                origin: row.get(2)?,
                text: serde_json::from_str::<serde_json::Value>(&payload)
                    .ok()
                    .and_then(|document| document.get("text")?.as_str().map(str::to_string)),
            })
        })?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    fn save_history(&self, object_id: &str, total: i64, replayed: &[Revision], lines: &[Dated]) -> Result<()> {
        let Some(last) = replayed.last() else { return Ok(()) };
        let encoded = serde_json::to_string(lines)?;
        self.with_index(|db| {
            db.execute(
                "INSERT INTO line_history (object_id, revisions, last_created, last_revision, lines)
                 VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT(object_id) DO UPDATE SET revisions = excluded.revisions,
                     last_created = excluded.last_created, last_revision = excluded.last_revision,
                     lines = excluded.lines",
                rusqlite::params![object_id, total, last.created_ms, last.id, encoded],
            )?;
            Ok(())
        })
    }
}

pub(crate) struct Revision {
    pub(crate) id: String,
    pub(crate) created_ms: i64,
    pub(crate) origin: String,
    pub(crate) text: Option<String>,
}

/// Replay revisions over the lines already dated. A revision without text
/// (an attachment's, say) changes nothing; an adoption's lines are undated.
pub(crate) fn replay(mut lines: Vec<Dated>, revisions: &[Revision]) -> Vec<Dated> {
    for revision in revisions {
        let Some(text) = &revision.text else { continue };
        let stamp = (revision.origin != crate::vault::adopt::IMPORT_ORIGIN).then_some(revision.created_ms);
        lines = carry(&lines, &split(text), stamp);
    }
    lines
}

/// The newer lines, each keeping the date of the older line it matches and
/// taking `stamp` when it matches none.
pub(crate) fn carry(old: &[Dated], new: &[&str], stamp: Option<i64>) -> Vec<Dated> {
    let mut dated: Vec<Dated> = new.iter().map(|line| (line.to_string(), stamp)).collect();
    for (from, to) in matches(old, new) {
        dated[to].1 = old[from].1;
    }
    dated
}

/// Matched pairs `(old index, new index)`, increasing in both: the longest
/// common subsequence of the lines after the shared prefix and suffix are
/// taken off, and a greedy in-order match when the middle is too large for
/// the table.
fn matches(old: &[Dated], new: &[&str]) -> Vec<(usize, usize)> {
    let mut pairs = Vec::new();
    let mut prefix = 0;
    while prefix < old.len() && prefix < new.len() && old[prefix].0 == new[prefix] {
        pairs.push((prefix, prefix));
        prefix += 1;
    }
    let mut suffix = 0;
    while suffix < old.len() - prefix
        && suffix < new.len() - prefix
        && old[old.len() - 1 - suffix].0 == new[new.len() - 1 - suffix]
    {
        suffix += 1;
    }
    let a = &old[prefix..old.len() - suffix];
    let b = &new[prefix..new.len() - suffix];
    let middle = if a.len().saturating_mul(b.len()) <= MAX_TABLE {
        lcs(a, b)
    } else {
        greedy(a, b)
    };
    pairs.extend(middle.into_iter().map(|(i, j)| (prefix + i, prefix + j)));
    pairs.extend((0..suffix).map(|k| (old.len() - suffix + k, new.len() - suffix + k)));
    pairs
}

/// Cells in the LCS table before the greedy match takes over.
const MAX_TABLE: usize = 4_000_000;

fn lcs(a: &[Dated], b: &[&str]) -> Vec<(usize, usize)> {
    let (n, m) = (a.len(), b.len());
    let mut table = vec![0u32; (n + 1) * (m + 1)];
    let at = |i: usize, j: usize| i * (m + 1) + j;
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            table[at(i, j)] = if a[i].0 == b[j] {
                table[at(i + 1, j + 1)] + 1
            } else {
                table[at(i + 1, j)].max(table[at(i, j + 1)])
            };
        }
    }
    let (mut i, mut j, mut pairs) = (0, 0, Vec::new());
    while i < n && j < m {
        if a[i].0 == b[j] {
            pairs.push((i, j));
            i += 1;
            j += 1;
        } else if table[at(i + 1, j)] >= table[at(i, j + 1)] {
            i += 1;
        } else {
            j += 1;
        }
    }
    pairs
}

fn greedy(a: &[Dated], b: &[&str]) -> Vec<(usize, usize)> {
    let mut at: std::collections::HashMap<&str, std::collections::VecDeque<usize>> = Default::default();
    for (i, (line, _)) in a.iter().enumerate() {
        at.entry(line.as_str()).or_default().push_back(i);
    }
    let mut next = 0;
    let mut pairs = Vec::new();
    for (j, line) in b.iter().enumerate() {
        let Some(queue) = at.get_mut(line) else { continue };
        while queue.front().is_some_and(|&i| i < next) {
            queue.pop_front();
        }
        if let Some(i) = queue.pop_front() {
            pairs.push((i, j));
            next = i + 1;
        }
    }
    pairs
}

/// A body's lines as the index numbers them: split on `\n`, `\r` dropped,
/// the empty line after a final newline kept — the split [`super::body_of`]
/// makes and the document layout counts.
fn split(text: &str) -> Vec<&str> {
    text.split('\n').map(|line| line.strip_suffix('\r').unwrap_or(line)).collect()
}

#[cfg(test)]
mod tests;
