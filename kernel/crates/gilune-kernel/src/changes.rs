//! The vault change feed (ADR 0020 § 2.1): what changed in the
//! projection, densely numbered, resumable from a cursor.
//!
//! It describes **files**, not objects. Object-level change is what the
//! events table has always carried and is unchanged; four mutations of
//! the projection — a rename by id, a tombstone withdrawal, an import, a
//! quarantine — were never announced anywhere, and none of them carried
//! a number a reader could resume from. A consumer that has to rescan the
//! vault to find out what happened inherits the rescan's cost curve for
//! ever, which is the whole reason this exists.
//!
//! Two rules make it usable and both live here:
//!
//! - **Dense by construction.** `seq` comes from a counter in `meta`
//!   advanced inside the transaction that commits the change, the way
//!   the operation sequence works. An `AUTOINCREMENT` column burns a
//!   number on every rolled-back transaction, and a reader seeing a gap
//!   cannot tell that from a row it missed. SQLite's single writer makes
//!   the counter safe across processes.
//! - **An epoch, not a retention window.** The table is never trimmed.
//!   `epoch` is stamped when the table is created, so a cursor from a
//!   different database — another vault's, swapped in — is refused
//!   rather than answered with somebody else's history.

use crate::store::Store;
use crate::{ids, KernelError, Result};

/// The dense counter. Advanced inside the caller's transaction.
pub(crate) const SEQ_KEY: &str = "vault_change_seq";
/// The random id stamped when the feed's table was created.
pub(crate) const EPOCH_KEY: &str = "vault_change_epoch";

/// Rows one page may carry, whatever a caller asks for. A page is owned
/// values crossing a boundary (02-kernel-contract), so it is bounded
/// here rather than by a caller's arithmetic; a consumer catching up
/// pages through `next` and pays one more round trip, never a vault of
/// rows in memory at once.
pub const MAX_PAGE: u32 = 10_000;

/// What happened to a file. Exactly the six of ADR 0020 § 2.1.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChangeKind {
    /// The vault did not have this file and now does.
    Created,
    /// The file's bytes changed.
    Modified,
    /// The same file is at a different path; `previous_rel_path` says
    /// where it was. Files are matched by front-matter id, never by name
    /// (ADR 0007), so a move is a metadata change and not a delete.
    Moved,
    /// The file is no longer in the vault.
    Removed,
    /// A file the kernel did not write is offered as something to bring
    /// in. Nothing on disk was touched.
    Imported,
    /// A file the kernel will not act on until a person decides. Nothing
    /// on disk was touched.
    Quarantined,
}

/// Why it happened, as far as the kernel can say. **Best-effort by
/// contract** (ADR 0020 § 2.1): exact where the kernel knows, and
/// `LocalEdit` where a change reached the projection without its
/// provenance being plumbed there. A consumer must not branch on it for
/// correctness — it is for diagnostics and for a person reading a feed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChangeCause {
    Capture,
    Processor,
    LocalEdit,
    ExternalEdit,
    Sync,
    Reconcile,
}

impl ChangeKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ChangeKind::Created => "created",
            ChangeKind::Modified => "modified",
            ChangeKind::Moved => "moved",
            ChangeKind::Removed => "removed",
            ChangeKind::Imported => "imported",
            ChangeKind::Quarantined => "quarantined",
        }
    }

    fn parse(text: &str) -> Option<ChangeKind> {
        Some(match text {
            "created" => ChangeKind::Created,
            "modified" => ChangeKind::Modified,
            "moved" => ChangeKind::Moved,
            "removed" => ChangeKind::Removed,
            "imported" => ChangeKind::Imported,
            "quarantined" => ChangeKind::Quarantined,
            _ => return None,
        })
    }
}

impl ChangeCause {
    pub fn as_str(self) -> &'static str {
        match self {
            ChangeCause::Capture => "capture",
            ChangeCause::Processor => "processor",
            ChangeCause::LocalEdit => "local-edit",
            ChangeCause::ExternalEdit => "external-edit",
            ChangeCause::Sync => "sync",
            ChangeCause::Reconcile => "reconcile",
        }
    }

    fn parse(text: &str) -> Option<ChangeCause> {
        Some(match text {
            "capture" => ChangeCause::Capture,
            "processor" => ChangeCause::Processor,
            "local-edit" => ChangeCause::LocalEdit,
            "external-edit" => ChangeCause::ExternalEdit,
            "sync" => ChangeCause::Sync,
            "reconcile" => ChangeCause::Reconcile,
            _ => return None,
        })
    }
}

/// Where a reader is in the feed. `seq` 0 with the right epoch means
/// "everything", which is the sanctioned full rebuild.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Cursor {
    pub epoch: String,
    pub seq: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VaultChange {
    pub seq: i64,
    pub kind: ChangeKind,
    pub rel_path: String,
    /// Where the file was, on a `Moved`.
    pub previous_rel_path: Option<String>,
    /// The object the file projects, where there is one. Imports and
    /// quarantines are files the kernel does not claim, so they carry
    /// none.
    pub object_id: Option<String>,
    pub revision_id: Option<String>,
    pub cause: ChangeCause,
    pub created_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChangePage {
    pub changes: Vec<VaultChange>,
    /// Where to resume. Equals the cursor that was handed in when the
    /// page is empty, so resuming is always the same call.
    pub next: Cursor,
    /// Nothing else was waiting when this page was read.
    pub complete: bool,
}

/// One change, as a mutation site describes itself. Borrowed rather than
/// owned: every caller already holds these strings.
pub(crate) struct Change<'a> {
    pub kind: ChangeKind,
    pub rel_path: &'a str,
    pub previous_rel_path: Option<&'a str>,
    pub object_id: Option<&'a str>,
    pub revision_id: Option<&'a str>,
    pub cause: ChangeCause,
}

/// Write one change row, taking its sequence number from the `meta`
/// counter in the same statement.
///
/// **Runs on the caller's connection, inside the caller's transaction.**
/// That is the contract of ADR 0020 § 2.2 and it is not decoration: a
/// change row that could commit without the mutation it describes would
/// make the feed a second source of truth, and a mutation that could
/// commit without its row would make the feed lossy. A rolled-back
/// transaction un-advances the counter with everything else, which is
/// why the counter is not an `AUTOINCREMENT` column.
pub(crate) fn record(conn: &rusqlite::Connection, change: Change<'_>) -> Result<i64> {
    let seq: i64 = conn.query_row(
        "INSERT INTO meta (key, value) VALUES (?1, 1)
         ON CONFLICT(key) DO UPDATE SET value = value + 1
         RETURNING value",
        [SEQ_KEY],
        |row| row.get(0),
    )?;
    conn.execute(
        "INSERT INTO vault_changes
         (seq, kind, rel_path, previous_rel_path, object_id, revision_id, cause, created_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        rusqlite::params![
            seq,
            change.kind.as_str(),
            change.rel_path,
            change.previous_rel_path,
            change.object_id,
            change.revision_id,
            change.cause.as_str(),
            ids::now_ms(),
        ],
    )?;
    Ok(seq)
}

impl Store {
    /// This feed's epoch. A consumer stores it beside its cursor; a
    /// cursor carrying any other epoch is refused.
    pub fn change_epoch(&self) -> Result<String> {
        self.conn()
            .query_row(
                "SELECT value FROM meta WHERE key = ?1",
                [EPOCH_KEY],
                |row| row.get(0),
            )
            .map_err(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => KernelError::Corrupt {
                    detail: "this vault has no change-feed epoch".to_string(),
                },
                e => KernelError::Storage(e),
            })
    }

    /// A cursor at the very beginning of this vault's feed: the only
    /// sanctioned full scan (ADR 0020 § 2.1).
    pub fn change_origin(&self) -> Result<Cursor> {
        Ok(Cursor { epoch: self.change_epoch()?, seq: 0 })
    }

    /// Changes after `cursor`, oldest first, at most `limit` of them.
    ///
    /// `CursorUnknown` for a cursor this vault cannot answer from: a
    /// foreign epoch, a negative sequence, or a sequence the table does
    /// not hold — including one past the counter, which is a reader
    /// ahead of the vault it is reading. The consumer's answer to all
    /// three is the same: rebuild from `change_origin`.
    pub fn changes_since(&self, cursor: &Cursor, limit: u32) -> Result<ChangePage> {
        let epoch = self.change_epoch()?;
        if cursor.epoch != epoch {
            return Err(KernelError::CursorUnknown {
                epoch: cursor.epoch.clone(),
                seq: cursor.seq,
                reason: "the cursor was taken against a different vault database".to_string(),
            });
        }
        if cursor.seq < 0 {
            return Err(KernelError::CursorUnknown {
                epoch,
                seq: cursor.seq,
                reason: "a sequence number is never negative".to_string(),
            });
        }
        if cursor.seq > 0 {
            let held: i64 = self.conn().query_row(
                "SELECT EXISTS(SELECT 1 FROM vault_changes WHERE seq = ?1)",
                [cursor.seq],
                |row| row.get(0),
            )?;
            if held == 0 {
                return Err(KernelError::CursorUnknown {
                    epoch,
                    seq: cursor.seq,
                    reason: "this vault holds no change at that sequence".to_string(),
                });
            }
        }
        // One more than asked for, so `complete` is observed rather than
        // inferred from the counter — a page that is exactly `limit`
        // long is the case where guessing goes wrong.
        let want = limit.min(MAX_PAGE) as i64;
        let mut stmt = self.conn().prepare(
            "SELECT seq, kind, rel_path, previous_rel_path, object_id, revision_id,
                    cause, created_ms
             FROM vault_changes WHERE seq > ?1 ORDER BY seq LIMIT ?2",
        )?;
        let rows = stmt.query_map(rusqlite::params![cursor.seq, want + 1], |row| {
            let kind: String = row.get(1)?;
            let cause: String = row.get(6)?;
            Ok(VaultChange {
                seq: row.get(0)?,
                // A row this build cannot name is local state it wrote
                // itself, so it cannot happen without the database being
                // edited from outside; reporting it as the nearest true
                // thing beats refusing the page.
                kind: ChangeKind::parse(&kind).unwrap_or(ChangeKind::Modified),
                rel_path: row.get(2)?,
                previous_rel_path: row.get(3)?,
                object_id: row.get(4)?,
                revision_id: row.get(5)?,
                cause: ChangeCause::parse(&cause).unwrap_or(ChangeCause::LocalEdit),
                created_ms: row.get(7)?,
            })
        })?;
        let mut changes: Vec<VaultChange> = Vec::new();
        let mut more = false;
        for row in rows {
            let change = row?;
            if changes.len() as i64 >= want {
                more = true;
                break;
            }
            changes.push(change);
        }
        let next = Cursor {
            epoch,
            seq: changes.last().map(|c| c.seq).unwrap_or(cursor.seq),
        };
        Ok(ChangePage { changes, next, complete: !more })
    }

    /// Whether the last thing the feed said about this path was that it
    /// left the vault.
    ///
    /// The guard on `Removed`: a file can disappear once and be noticed
    /// twice — reconcile finds the projection's file gone, and a
    /// tombstone arriving afterwards finds it gone again — and two rows
    /// for one disappearance would have a consumer forget the same path
    /// twice. Anything that puts a file back writes a row of its own, so
    /// a second genuine removal is never suppressed.
    pub(crate) fn already_removed(&self, rel_path: &str) -> Result<bool> {
        let latest: Option<String> = self
            .conn()
            .query_row(
                "SELECT kind FROM vault_changes WHERE rel_path = ?1 ORDER BY seq DESC LIMIT 1",
                [rel_path],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(KernelError::from(e)),
            })?;
        Ok(latest.as_deref() == Some(ChangeKind::Removed.as_str()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store() -> (tempfile::TempDir, Store) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        (dir, store)
    }

    fn write(store: &Store, kind: ChangeKind, rel_path: &str) -> i64 {
        store
            .write_txn(|| {
                record(
                    store.conn(),
                    Change {
                        kind,
                        rel_path,
                        previous_rel_path: None,
                        object_id: None,
                        revision_id: None,
                        cause: ChangeCause::Reconcile,
                    },
                )
            })
            .expect("record")
    }

    #[test]
    fn the_sequence_is_dense_and_a_rolled_back_transaction_leaves_no_gap() {
        // The reason the counter is not an AUTOINCREMENT column: that
        // burns a number on every rollback, and a reader seeing a gap
        // cannot tell a discarded number from a row it missed.
        let (_dir, store) = store();
        assert_eq!(write(&store, ChangeKind::Created, "a.md"), 1);

        let attempted: Result<()> = store.write_txn(|| {
            record(
                store.conn(),
                Change {
                    kind: ChangeKind::Modified,
                    rel_path: "b.md",
                    previous_rel_path: None,
                    object_id: None,
                    revision_id: None,
                    cause: ChangeCause::Reconcile,
                },
            )?;
            Err(KernelError::Corrupt { detail: "the work this change described failed".into() })
        });
        assert!(attempted.is_err());

        assert_eq!(write(&store, ChangeKind::Created, "c.md"), 2, "no number was burned");
        let page = store
            .changes_since(&store.change_origin().expect("origin"), 100)
            .expect("page");
        assert_eq!(
            page.changes.iter().map(|c| c.seq).collect::<Vec<_>>(),
            vec![1, 2]
        );
        assert_eq!(
            page.changes.iter().map(|c| c.rel_path.as_str()).collect::<Vec<_>>(),
            vec!["a.md", "c.md"],
            "the change whose transaction rolled back is not in the feed at all"
        );
    }

    #[test]
    fn pages_are_bounded_and_complete_is_observed_not_guessed() {
        let (_dir, store) = store();
        for n in 0..5 {
            write(&store, ChangeKind::Created, &format!("{n}.md"));
        }
        let mut cursor = store.change_origin().expect("origin");
        let first = store.changes_since(&cursor, 2).expect("page");
        assert_eq!(first.changes.len(), 2);
        assert!(!first.complete);
        cursor = first.next;

        let second = store.changes_since(&cursor, 2).expect("page");
        assert_eq!(second.changes.len(), 2);
        assert!(!second.complete);
        cursor = second.next;

        // The second-order case: a page that is exactly the last of the
        // rows. Inferring completeness from "fewer than asked for" would
        // call this incomplete and cost a round trip for ever.
        let third = store.changes_since(&cursor, 1).expect("page");
        assert_eq!(third.changes.len(), 1);
        assert!(third.complete, "the last row was the last row");
        assert_eq!(third.next.seq, 5);

        let after = store.changes_since(&third.next, 10).expect("page");
        assert!(after.changes.is_empty());
        assert!(after.complete);
        assert_eq!(after.next, third.next, "resuming from the end is the same call");
    }

    #[test]
    fn a_cursor_this_vault_cannot_answer_from_is_refused_rather_than_guessed_at() {
        let (_dir, store) = store();
        write(&store, ChangeKind::Created, "a.md");
        let epoch = store.change_epoch().expect("epoch");

        assert!(matches!(
            store.changes_since(&Cursor { epoch: "somebody-elses".into(), seq: 1 }, 10),
            Err(KernelError::CursorUnknown { .. })
        ));
        assert!(
            matches!(
                store.changes_since(&Cursor { epoch: epoch.clone(), seq: 9 }, 10),
                Err(KernelError::CursorUnknown { .. })
            ),
            "a reader ahead of the vault is a reader that has to rebuild"
        );
        assert!(matches!(
            store.changes_since(&Cursor { epoch: epoch.clone(), seq: -1 }, 10),
            Err(KernelError::CursorUnknown { .. })
        ));
        // And the one that must be answered: the beginning.
        assert_eq!(
            store
                .changes_since(&Cursor { epoch, seq: 0 }, 10)
                .expect("the sanctioned full rebuild")
                .changes
                .len(),
            1
        );
    }

    #[test]
    fn every_epoch_is_its_own_so_a_swapped_database_is_never_answered_from() {
        let (_a, one) = store();
        let (_b, two) = store();
        assert_ne!(one.change_epoch().expect("epoch"), two.change_epoch().expect("epoch"));
    }

    #[test]
    fn a_limit_of_zero_asks_for_nothing_and_still_says_whether_there_is_more() {
        let (_dir, store) = store();
        let origin = store.change_origin().expect("origin");
        let empty = store.changes_since(&origin, 0).expect("page");
        assert!(empty.changes.is_empty());
        assert!(empty.complete, "nothing was waiting");

        write(&store, ChangeKind::Created, "a.md");
        let waiting = store.changes_since(&origin, 0).expect("page");
        assert!(waiting.changes.is_empty());
        assert!(!waiting.complete, "something is waiting and the caller asked for none of it");
    }
}
