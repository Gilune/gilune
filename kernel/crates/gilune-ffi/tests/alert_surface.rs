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
        ],
        "if a kind changes here, both shells' alert wording has to change with it"
    );
}
