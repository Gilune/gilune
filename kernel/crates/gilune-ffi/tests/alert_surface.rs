//! The alert surface, at the boundary (ADR 0019 § 8).
//!
//! The kernel has raised integrity alerts since M5a and **nothing could
//! read them**: `GiluneStatus` carried neither the standing count nor the
//! blobs still in transit, and there was no way to ask what a halt was
//! about. A halted stream therefore looked exactly like an idle one,
//! which is the failure mode § 8 names — "silent halting is the failure
//! mode that would make sync look like it works".
//!
//! These tests are about the *boundary*, not the kernel: the halt policy
//! itself is proven in `gilune-sync`'s bounds and gate suites. What is
//! proven here is that a shell can find out.

use gilune_ffi::Gilune;
use gilune_kernel::store::Store;

fn open() -> (tempfile::TempDir, std::sync::Arc<Gilune>) {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    (dir, gilune)
}

/// Deliver something a peer might send, through a **second handle on the
/// same vault** — the shape a listener thread already uses, and sanctioned
/// use since 2026-08-31. The FFI has no `apply` of its own by design:
/// applying is something a session does, not something a shell asks for.
/// So the fault is caused the way a peer causes it and read the way a
/// shell reads it, which is exactly the seam under test.
fn deliver(dir: &tempfile::TempDir, bytes: &[u8]) {
    let store = Store::open(dir.path()).expect("a second handle");
    let refused = store.apply_operation("push", bytes, gilune_kernel::limits::Limits::DEFAULT);
    assert!(refused.is_err(), "this is supposed to be refused");
}

#[test]
fn a_healthy_vault_reports_no_alerts_and_nothing_in_transit() {
    let (_dir, gilune) = open();
    let status = gilune.status().expect("status");
    assert_eq!(status.alerts, 0);
    assert_eq!(status.blobs_awaiting, 0);
    assert!(gilune.integrity_alerts().expect("alerts").is_empty());
}

#[test]
fn an_operation_this_kernel_cannot_read_becomes_an_alert_a_shell_can_show() {
    let (dir, gilune) = open();

    // What a hostile or simply newer peer can put on the wire. The
    // envelope is refused; the point of this test is that the refusal is
    // *legible* afterwards rather than only counted.
    deliver(&dir, b"not an envelope at all");

    let alerts = gilune.integrity_alerts().expect("alerts");
    assert_eq!(alerts.len(), 1, "one standing condition is one row");
    let alert = &alerts[0];
    assert_eq!(alert.kind, "sync-corrupt-operation");
    assert!(!alert.detail.is_empty(), "a shell has to be able to say why");
    assert!(alert.raised_ms > 0);
    assert!(!alert.alert_id.is_empty());

    // And the same fact is on the cheap read, so a status line can carry
    // it without asking for the list.
    assert_eq!(gilune.status().expect("status").alerts, 1);
}

#[test]
fn a_repeated_refusal_is_one_standing_condition_rather_than_a_growing_list() {
    let (dir, gilune) = open();
    for _ in 0..5 {
        deliver(&dir, b"still not an envelope");
    }
    // A peer that retries every thirty seconds against an unrepaired
    // fault must not grow a list a person then has to read.
    assert_eq!(gilune.integrity_alerts().expect("alerts").len(), 1);
    assert_eq!(gilune.status().expect("status").alerts, 1);
}

#[test]
fn every_alert_kind_the_kernel_can_raise_crosses_the_boundary_as_a_named_string() {
    // Not a behaviour test — a **contract** test. Both shells map these
    // strings onto sentences a person reads, and a kind renamed in the
    // kernel without both shells following would silently degrade to
    // whatever their `else` branch says. This is the list they map.
    let kinds = [
        gilune_kernel::alerts::SYNC_GAP,
        gilune_kernel::alerts::SYNC_CORRUPT_OPERATION,
        gilune_kernel::alerts::SYNC_UNSUPPORTED_VERSION,
        gilune_kernel::alerts::SYNC_UNKNOWN_OP_KIND,
        gilune_kernel::alerts::SYNC_LIMIT_EXCEEDED,
        gilune_kernel::alerts::SYNC_BLOB_DIGEST_MISMATCH,
        gilune_kernel::alerts::SYNC_REVOKED_DEVICE,
        gilune_kernel::alerts::SYNC_AWAITING_REFERENT,
        gilune_kernel::alerts::SYNC_REFERENT_REFUSED,
        gilune_kernel::alerts::VAULT_DELETED_NOTE_EDITED,
        gilune_kernel::alerts::VAULT_CONTENT_UNREADABLE,
    ];
    assert_eq!(
        kinds,
        [
            "sync-gap",
            "sync-corrupt-operation",
            "sync-unsupported-version",
            "sync-unknown-op-kind",
            "sync-limit-exceeded",
            "sync-blob-digest-mismatch",
            "sync-revoked-device",
            "sync-awaiting-referent",
            "sync-referent-refused",
            "vault-deleted-note-edited",
            "vault-content-unreadable",
        ],
        "if a kind changes here, both shells' alert wording has to change with it"
    );
}

#[test]
fn the_kernel_says_which_alerts_are_damage_so_no_shell_has_to() {
    let (dir, gilune) = open();
    deliver(&dir, b"not an envelope at all");
    let alerts = gilune.integrity_alerts().expect("alerts");
    assert_eq!(alerts[0].kind, "sync-corrupt-operation");
    assert!(alerts[0].damage, "an envelope that will not decode is damage");
}

#[test]
fn a_condition_that_is_not_damage_crosses_the_boundary_saying_so() {
    // The second-order case, and the one each shell used to get wrong on
    // its own: a note kept because it was edited where it was deleted is a
    // **decision waiting to be seen**, not a fault. Both shells kept their
    // own copy of the damage set to work this out, which is two places for
    // one fact; the flag is the kernel answering it once.
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    let receipt = gilune.capture_text("worth keeping".to_string()).expect("capture");
    gilune.project().expect("project");
    let rel = receipt.rel_path.replace(".txt", ".md");

    // Deleted on another device — and edited here, concurrently.
    let store = Store::open(dir.path()).expect("a second handle");
    let tombstone = gilune_kernel::operations::encode(&gilune_kernel::operations::Envelope {
        version: gilune_kernel::operations::ENVELOPE_VERSION,
        op_id: "elsewhere-1".to_string(),
        device_id: "device-elsewhere".to_string(),
        op_seq: 1,
        kind: gilune_kernel::operations::TOMBSTONE.to_string(),
        target_id: receipt.source_id.clone(),
        created_ms: 1_700_000_000_000,
        payload: serde_json::json!({}),
    });
    store
        .apply_operation("push", &tombstone, gilune_kernel::limits::Limits::DEFAULT)
        .expect("the tombstone applies");
    let path = dir.path().join(&rel);
    let edited =
        std::fs::read_to_string(&path).expect("read").replace("worth keeping", "still worth it");
    std::fs::write(&path, &edited).expect("edit");
    gilune.reconcile().expect("reconcile");

    let alerts = gilune.integrity_alerts().expect("alerts");
    assert_eq!(alerts.len(), 1);
    assert_eq!(alerts[0].kind, "vault-deleted-note-edited");
    assert!(
        !alerts[0].damage,
        "a note kept on purpose is not a broken one, and a shell must not have to \
         work that out from the kind name"
    );
    // And the cheap count does not distinguish: it is a count of standing
    // conditions, damage or not, which is why the flag is on the row.
    assert_eq!(gilune.status().expect("status").alerts, 1);
}
