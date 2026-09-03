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
/// An operation names something that has not arrived yet — the object a
/// revision or a tombstone is about, or a head it says it supersedes.
/// Per-device sequences carry no causal parents (ADR 0005's inherited
/// gap), so a full replica can legitimately deliver one device's stream
/// before another's; that stream halts at the naming operation and
/// resumes, unchanged, once the referent arrives. The subject is the
/// missing id, never the waiting device: what has to appear is what the
/// notice names.
pub const SYNC_AWAITING_REFERENT: &str = "sync-awaiting-referent";
/// A note deleted on another device had been edited here. The edit is
/// the person keeping it, so the file stays in the vault as an import
/// candidate and the deletion is not carried out against it
/// (constitution rule 1). Not a fault — a decision waiting to be seen.
pub const VAULT_DELETED_NOTE_EDITED: &str = "vault-deleted-note-edited";
/// An operation originated on a device this one has revoked. Refused
/// whoever delivered it (ADR 0019 § 9) — in a full-replica mesh a third
/// paired device holds a revoked device's history and would otherwise
/// relay it, so "excluded" has to be a property of apply, not of who
/// happens to be on the far end of the socket.
pub const SYNC_REVOKED_DEVICE: &str = "sync-revoked-device";
/// An operation named something whose origin device this vault refuses,
/// so the thing it names can never arrive: a revision or a tombstone of
/// an object belonging to a revoked device is moot. The operation is
/// preserved and forwarded, the frontier moves on, and this says why the
/// stream did **not** stop. Informational, never damage — it is the exit
/// from `sync-awaiting-referent`, not another way in.
pub const SYNC_REFERENT_REFUSED: &str = "sync-referent-refused";
/// A peer said, inside the authenticated channel, that it used to be some
/// other device — and that device is one this vault refuses.
///
/// **A claim is not a credential.** Pairing welcomes the device that
/// actually paired: re-pairing *this* peer is a decision the user just
/// took. What it used to be called is a sentence the peer typed into the
/// greeting, and honouring it automatically would let any device this
/// vault agrees to pair with lift a revocation on any *other* device, just
/// by naming it. So the claim is recorded and shown, and re-admitting the
/// earlier identity is a second, explicit action (`Store::readmit_device`).
///
/// Not damage: nothing is broken and nothing was lost. The subject is the
/// claimed device id — what a person would be re-admitting — and the
/// detail names the peer that claimed it.
pub const SYNC_PREVIOUS_IDENTITY_CLAIMED: &str = "sync-previous-identity-claimed";
/// A note's own preserved bytes could not be read here, and they are not
/// content in transit, so the projection was held back rather than
/// written with a placeholder where the words are (`vault.rs`'s render).
/// Not damage: the kernel's record of the note is intact and the file on
/// disk is untouched — something is wrong with the *bytes beside* it, and
/// a person is the one who can say what. Cleared as soon as the object
/// renders again.
pub const VAULT_CONTENT_UNREADABLE: &str = "vault-content-unreadable";

/// Whether an alert kind means something is **damaged**, as opposed to
/// something being pending, refused by policy, or waiting on a person.
///
/// The kernel owns this classification because it owns the kinds. Every
/// shell used to restate the set in its own words, which is two places
/// for one fact and a new kind silently reading as damage in both.
///
/// An unknown kind is **not** damage: a kernel newer than this build may
/// raise a kind whose meaning is not knowable here, and guessing "broken"
/// about it would be the same over-claim the shells were making.
pub fn is_damage(kind: &str) -> bool {
    matches!(
        kind,
        SYNC_GAP
            | SYNC_CORRUPT_OPERATION
            | SYNC_UNSUPPORTED_VERSION
            | SYNC_LIMIT_EXCEEDED
            | SYNC_REVOKED_DEVICE
            | SYNC_BLOB_DIGEST_MISMATCH
    )
}

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
    /// Whether this condition means damage. Derived from `kind` by
    /// `is_damage`, and carried on the row so that no reader has to keep
    /// its own copy of the kind set.
    pub damage: bool,
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
            let kind: String = row.get(1)?;
            Ok(Alert {
                alert_id: row.get(0)?,
                damage: is_damage(&kind),
                kind,
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
    fn the_kernel_says_which_conditions_are_damage_so_no_shell_has_to() {
        let (_dir, store) = store();
        raise(store.conn(), SYNC_GAP, "device-a", "halted").expect("raise");
        raise(store.conn(), SYNC_UNKNOWN_OP_KIND, "com.example.kind", "unread").expect("raise");
        let by_kind: std::collections::BTreeMap<String, bool> = store
            .integrity_alerts()
            .expect("list")
            .into_iter()
            .map(|a| (a.kind, a.damage))
            .collect();
        assert!(by_kind[SYNC_GAP], "a halted stream is damage");
        assert!(
            !by_kind[SYNC_UNKNOWN_OP_KIND],
            "data this build cannot read is preserved, not broken"
        );
    }

    #[test]
    fn every_kind_this_module_declares_is_classified_deliberately() {
        // The second-order case: a kind added without a decision about
        // what it means. `is_damage` answers `false` for anything it does
        // not know, which is the safe default *and* the one that would
        // hide a genuine fault — so the whole declared set is pinned here
        // and adding a kind means adding a line to this table.
        let damage = [
            SYNC_GAP,
            SYNC_CORRUPT_OPERATION,
            SYNC_UNSUPPORTED_VERSION,
            SYNC_LIMIT_EXCEEDED,
            SYNC_REVOKED_DEVICE,
            SYNC_BLOB_DIGEST_MISMATCH,
        ];
        let not_damage = [
            SYNC_UNKNOWN_OP_KIND,
            SYNC_AWAITING_REFERENT,
            SYNC_REFERENT_REFUSED,
            SYNC_PREVIOUS_IDENTITY_CLAIMED,
            VAULT_DELETED_NOTE_EDITED,
            VAULT_CONTENT_UNREADABLE,
        ];
        for kind in damage {
            assert!(is_damage(kind), "{kind} must read as damage");
        }
        for kind in not_damage {
            assert!(!is_damage(kind), "{kind} must not read as damage");
        }
        assert!(
            !is_damage("something.a.newer.kernel.raised"),
            "an unknown kind is not asserted to be damage"
        );
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
