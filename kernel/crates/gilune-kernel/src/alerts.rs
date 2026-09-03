//! Integrity alerts: the surface that makes halting visible (ADR 0019
//! § 8). Silent halting is the failure mode that would make sync look
//! like it works, so every refusal the apply path makes writes a row a
//! person can be shown, and clears it when the condition clears.
//!
//! Alerts are device-local diagnostics, never operations: they describe
//! what this device refused, which is not a fact other devices share.

use crate::store::Store;
use crate::{ids, Result};

/// A sequence number arrived out of order; that peer's stream is halted
/// until the missing operation appears.
pub const SYNC_GAP: &str = "sync-gap";
/// An envelope could not be decoded. Nothing past it applies.
pub const SYNC_CORRUPT_OPERATION: &str = "sync-corrupt-operation";
/// An envelope declared a codec version this kernel cannot read.
pub const SYNC_UNSUPPORTED_VERSION: &str = "sync-unsupported-version";
/// An operation kind no plugin here understands. Preserved verbatim and
/// forwarded, never interpreted (constitution rule 9).
pub const SYNC_UNKNOWN_OP_KIND: &str = "sync-unknown-op-kind";
/// A bound in `limits::Limits` was exceeded by the peer.
pub const SYNC_LIMIT_EXCEEDED: &str = "sync-limit-exceeded";
/// Assembled blob bytes did not match the digest the operation declared.
pub const SYNC_BLOB_DIGEST_MISMATCH: &str = "sync-blob-digest-mismatch";
/// An operation originated on a device this one has revoked. Refused
/// whoever delivered it (ADR 0019 § 9) — in a full-replica mesh a third
/// paired device holds a revoked device's history and would otherwise
/// relay it, so "excluded" has to be a property of apply, not of who
/// happens to be on the far end of the socket.
pub const SYNC_REVOKED_DEVICE: &str = "sync-revoked-device";

/// One raised condition. `subject` is whatever the alert is *about* — a
/// device id for a halted stream, an operation id for an unreadable
/// envelope, a blob id for bytes that failed their digest — so clearing
/// is addressable without a second key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Alert {
    pub alert_id: String,
    pub kind: String,
    pub subject: String,
    pub detail: String,
    pub raised_ms: i64,
}

/// Raise an alert, or refresh the detail of one already standing for this
/// (kind, subject). Deduplicating on the pair is deliberate: a peer that
/// retries every thirty seconds against an unrepaired gap must not grow
/// an unbounded list a person then has to read.
///
/// Runs on whatever connection it is given, including a transaction — but
/// the apply path deliberately raises outside its transaction, because an
/// alert about a rolled-back operation must survive the rollback.
pub(crate) fn raise(
    conn: &rusqlite::Connection,
    kind: &str,
    subject: &str,
    detail: &str,
) -> Result<()> {
    conn.execute(
        "INSERT INTO integrity_alerts (alert_id, kind, subject, detail, raised_ms)
         VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(kind, subject) DO UPDATE SET detail = ?4, raised_ms = ?5",
        rusqlite::params![ids::new_id(), kind, subject, detail, ids::now_ms()],
    )?;
    Ok(())
}

/// Withdraw any alert of this kind about this subject. Called when the
/// condition clears — a gap repaired, an envelope decoded on retry — so
/// a resolved fault stops being reported without anyone dismissing it.
pub(crate) fn clear(conn: &rusqlite::Connection, kind: &str, subject: &str) -> Result<()> {
    conn.execute(
        "DELETE FROM integrity_alerts WHERE kind = ?1 AND subject = ?2",
        [kind, subject],
    )?;
    Ok(())
}

impl Store {
    /// Every standing integrity alert, oldest first. The read API the
    /// spike lacked: it raised alerts nothing could display.
    pub fn integrity_alerts(&self) -> Result<Vec<Alert>> {
        let mut stmt = self.conn().prepare(
            "SELECT alert_id, kind, subject, detail, raised_ms
             FROM integrity_alerts ORDER BY raised_ms, alert_id",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Alert {
                alert_id: row.get(0)?,
                kind: row.get(1)?,
                subject: row.get(2)?,
                detail: row.get(3)?,
                raised_ms: row.get(4)?,
            })
        })?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Standing alerts of one kind. What a settings screen asks when it
    /// wants to say "this device is not receiving from that one".
    pub fn integrity_alerts_of(&self, kind: &str) -> Result<Vec<Alert>> {
        Ok(self
            .integrity_alerts()?
            .into_iter()
            .filter(|a| a.kind == kind)
            .collect())
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

    #[test]
    fn a_repeated_condition_refreshes_one_alert_rather_than_growing_a_list() {
        let (_dir, store) = store();
        raise(store.conn(), SYNC_GAP, "device-a", "expected 2, found 5").expect("raise");
        raise(store.conn(), SYNC_GAP, "device-a", "expected 2, found 9").expect("raise again");
        let alerts = store.integrity_alerts().expect("list");
        assert_eq!(alerts.len(), 1, "one standing condition is one row");
        assert_eq!(alerts[0].detail, "expected 2, found 9", "the newest detail wins");
    }

    #[test]
    fn alerts_about_different_subjects_stand_side_by_side() {
        let (_dir, store) = store();
        raise(store.conn(), SYNC_GAP, "device-a", "a").expect("raise");
        raise(store.conn(), SYNC_GAP, "device-b", "b").expect("raise");
        raise(store.conn(), SYNC_CORRUPT_OPERATION, "device-a", "c").expect("raise");
        assert_eq!(store.integrity_alerts().expect("list").len(), 3);
        assert_eq!(store.integrity_alerts_of(SYNC_GAP).expect("of kind").len(), 2);
    }

    #[test]
    fn clearing_withdraws_the_alert_without_anyone_dismissing_it() {
        let (_dir, store) = store();
        raise(store.conn(), SYNC_GAP, "device-a", "halted").expect("raise");
        clear(store.conn(), SYNC_GAP, "device-a").expect("clear");
        assert!(store.integrity_alerts().expect("list").is_empty());
        clear(store.conn(), SYNC_GAP, "device-a").expect("clearing nothing is not an error");
    }
}
