//! What a hostile or careless peer costs (ADR 0019 § 8, gate A point 4).
//!
//! Every bound is tested the same way, because every bound has the same
//! answer: raise a visible alert, stop that peer's stream, apply nothing
//! past the offending point, and resume from the frontier when the
//! condition clears. A bound that halted silently, or that halted
//! permanently, would fail these tests as surely as one that did not
//! halt at all.

mod replica;

use gilune_kernel::alerts;
use gilune_kernel::limits::Limits;
use gilune_kernel::operations::{self, Envelope};
use gilune_kernel::store::Store;
use replica::{Fault, Replica};

/// Nothing of `device`'s stream has been applied here.
fn nothing_applied(store: &Store, device: &str) {
    assert_eq!(store.sync_frontier(device).expect("frontier"), 0);
    assert_eq!(store.status().expect("status").sources, 0);
    assert_eq!(store.status().expect("status").blobs_awaiting, 0);
    assert!(store.verify().expect("verify").is_empty());
}

#[test]
fn an_oversized_envelope_halts_the_stream_and_nothing_past_it_applies() {
    let a = Replica::new();
    let mut b = Replica::with_limits(Limits { max_envelope_bytes: 2048, ..Limits::DEFAULT });
    a.store.capture_bytes("txt", b"the first of three").expect("capture");
    a.store.capture_bytes("txt", b"the second").expect("capture");
    a.store.capture_bytes("txt", b"the third").expect("capture");
    let device = a.store.device_id().expect("device");

    let halted = b.pull_through(&a, Fault::InflateFirst(4096));
    assert_eq!(halted.applied, 0, "the oversized envelope was the first one");
    assert_eq!(halted.halted.len(), 1);
    assert!(halted.halted[0].reason.contains("over the 2048-byte limit"));
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_LIMIT_EXCEEDED], "and it is visible");
    nothing_applied(&b.store, &device);

    // The condition clears — here by raising the bound, in life by a peer
    // that stops sending documents the size of blobs.
    b.relimit(Limits::DEFAULT);
    let resumed = b.pull_from(&a);
    assert_eq!(resumed.applied, 3, "from the frontier, which never moved");
    assert!(resumed.halted.is_empty());
    assert_eq!(a.digest(), b.digest());
    assert!(b.alert_kinds().is_empty());
}

#[test]
fn a_blob_larger_than_this_device_accepts_is_refused_before_a_byte_is_written() {
    let a = Replica::new();
    let mut b = Replica::with_limits(Limits { max_blob_bytes: 1024, ..Limits::DEFAULT });
    let receipt = a.store.capture_bytes("wav", &[6u8; 8192]).expect("capture");
    let device = a.store.device_id().expect("device");

    let halted = b.pull_from(&a);
    assert_eq!(halted.applied, 0);
    assert_eq!(halted.halted.len(), 1);
    assert!(halted.halted[0].reason.contains("declared blob length 8192"));
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_LIMIT_EXCEEDED]);
    nothing_applied(&b.store, &device);
    assert!(
        !b.path(&receipt.rel_path).exists(),
        "a length is refused, never staged and then measured"
    );

    b.relimit(Limits::DEFAULT);
    while b.pull_from(&a).incomplete() {}
    assert_eq!(std::fs::read(b.path(&receipt.rel_path)).expect("arrived").len(), 8192);
    assert_eq!(a.digest(), b.digest());
    assert!(b.alert_kinds().is_empty());
}

#[test]
fn a_declared_length_that_would_not_fit_on_the_disk_is_refused_before_a_byte_is_written() {
    let a = Replica::new();
    // The margin stands in for a full disk: what is under test is that a
    // declared length is compared against real free space at all, before
    // staging opens — not that a test can fill a volume.
    let mut b = Replica::with_limits(Limits {
        free_space_margin_bytes: u64::MAX / 2,
        ..Limits::DEFAULT
    });
    a.store.capture_bytes("wav", &[2u8; 4096]).expect("capture");
    let device = a.store.device_id().expect("device");

    let halted = b.pull_from(&a);
    assert_eq!(halted.halted.len(), 1);
    assert!(halted.halted[0].reason.contains("free space"));
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_LIMIT_EXCEEDED]);
    nothing_applied(&b.store, &device);

    b.relimit(Limits::DEFAULT);
    while b.pull_from(&a).incomplete() {}
    assert_eq!(a.digest(), b.digest());
    assert!(b.alert_kinds().is_empty());
}

#[test]
fn a_session_operation_cap_ends_the_session_cleanly_and_the_next_one_resumes() {
    let a = Replica::new();
    let b = Replica::with_limits(Limits { max_ops_per_session: 2, ..Limits::DEFAULT });
    for word in ["one", "two", "three", "four", "five"] {
        a.store.capture_bytes("txt", word.as_bytes()).expect("capture");
    }

    let first = b.pull_from(&a);
    assert_eq!(first.applied, 2, "exactly the cap, not one more");
    assert!(first.ended_on_cap, "ended, not truncated");
    assert!(first.halted.is_empty(), "a cap is this device's choice, not the peer's fault");
    assert!(b.alert_kinds().is_empty(), "and so it raises no alert about the peer");

    let mut sessions = 1;
    while b.pull_from(&a).incomplete() && sessions < 10 {
        sessions += 1;
    }
    assert_eq!(a.digest(), b.digest(), "resumption costs round trips and nothing else");
    assert_eq!(b.store.status().expect("status").sources, 5);
}

#[test]
fn a_session_byte_cap_ends_the_session_cleanly_and_the_next_one_resumes() {
    let a = Replica::new();
    let b = Replica::with_limits(Limits {
        max_bytes_per_session: 1024,
        chunk_bytes: 4096,
        ..Limits::DEFAULT
    });
    for i in 0..6 {
        a.store.capture_bytes("txt", format!("thought number {i}").as_bytes()).expect("capture");
    }

    let first = b.pull_from(&a);
    assert!(first.ended_on_cap);
    assert!(first.applied >= 1 && first.applied < 6, "some progress, and not all of it");
    assert!(first.halted.is_empty());

    let mut sessions = 1;
    while b.pull_from(&a).incomplete() && sessions < 30 {
        sessions += 1;
    }
    assert_eq!(a.digest(), b.digest());
}

/// An `object-created` from a device that does not exist, carrying
/// whatever blob declaration a test wants to see refused.
fn created_with_blob(blob: serde_json::Value) -> Vec<u8> {
    operations::encode(&Envelope {
        version: operations::ENVELOPE_VERSION,
        op_id: "elsewhere-1".to_string(),
        device_id: "device-elsewhere".to_string(),
        op_seq: 1,
        kind: operations::OBJECT_CREATED.to_string(),
        target_id: "thing-1".to_string(),
        created_ms: 1_700_000_000_000,
        payload: serde_json::json!({
            "object": {
                "object_id": "thing-1",
                "type_id": "com.example.thing",
                "type_schema": 1,
                "created_ms": 1_700_000_000_000i64,
            },
            "revision": {
                "revision_id": "thing-rev-1",
                "payload": "{}",
                "origin": "capture",
                "created_ms": 1_700_000_000_000i64,
            },
            "blob": blob,
        }),
    })
}

#[test]
fn every_refusal_inside_the_apply_transaction_names_its_own_alert_kind() {
    // A refusal that rolls back its transaction has to be re-raised
    // outside it, and that outer step used to relabel every one of them
    // `sync-limit-exceeded` about the sending device — so a malformed
    // payload and a blob path escaping the vault were both reported as
    // somebody's disc filling up, and the subject the raise site chose
    // was thrown away. Each site now says what it means.
    let sound = serde_json::json!({
        "blob_id": "blob-1",
        "rel_path": "Captures/thing.wav",
        "sha256": "00",
        "bytes": 8,
    });
    let cases: Vec<(&str, Vec<u8>, &str)> = vec![
        (
            "a blob declared without a digest",
            created_with_blob(serde_json::json!({
                "blob_id": "blob-1",
                "rel_path": "Captures/thing.wav",
                "bytes": 8,
            })),
            alerts::SYNC_CORRUPT_OPERATION,
        ),
        (
            "a blob path escaping the vault",
            created_with_blob(serde_json::json!({
                "blob_id": "blob-1",
                "rel_path": "../../elsewhere.wav",
                "sha256": "00",
                "bytes": 8,
            })),
            alerts::SYNC_CORRUPT_OPERATION,
        ),
        (
            "a blob over the size bound",
            created_with_blob(serde_json::json!({
                "blob_id": "blob-1",
                "rel_path": "Captures/thing.wav",
                "sha256": "00",
                "bytes": 4096,
            })),
            alerts::SYNC_LIMIT_EXCEEDED,
        ),
        (
            "an object-created naming no revision",
            operations::encode(&Envelope {
                version: operations::ENVELOPE_VERSION,
                op_id: "elsewhere-1".to_string(),
                device_id: "device-elsewhere".to_string(),
                op_seq: 1,
                kind: operations::OBJECT_CREATED.to_string(),
                target_id: "thing-1".to_string(),
                created_ms: 1_700_000_000_000,
                payload: serde_json::json!({ "object": { "object_id": "thing-1" } }),
            }),
            alerts::SYNC_CORRUPT_OPERATION,
        ),
    ];

    for (name, bytes, expected) in cases {
        let b = Replica::with_limits(Limits { max_blob_bytes: 1024, ..Limits::DEFAULT });
        let refused = b.store.apply_operation("test", &bytes, b.limits());
        assert!(refused.is_err(), "{name} must be refused");
        assert_eq!(b.alert_kinds(), vec![expected.to_string()], "{name}");
        assert_eq!(
            b.store.sync_frontier("device-elsewhere").expect("frontier"),
            0,
            "{name}: nothing past the fault, and the frontier is where it was"
        );
        assert_eq!(b.store.status().expect("status").sources, 0, "{name}");
    }

    // And the free-space refusal, which needs its own bound to provoke.
    let b = Replica::with_limits(Limits {
        free_space_margin_bytes: u64::MAX / 2,
        ..Limits::DEFAULT
    });
    assert!(b.store.apply_operation("test", &created_with_blob(sound), b.limits()).is_err());
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_LIMIT_EXCEEDED]);
}

#[test]
fn a_chunk_larger_than_the_chunk_bound_is_refused_by_the_peer() {
    let a = Replica::new();
    let b = Replica::with_limits(Limits { chunk_bytes: 256, ..Limits::DEFAULT });
    let receipt = a.store.capture_bytes("wav", &[4u8; 4096]).expect("capture");
    let envelopes = a
        .store
        .operations_after(&a.store.device_id().expect("device"), 0, 10)
        .expect("ops");

    // Push the operation so the receiver knows the blob, then offer it
    // content in one gulp rather than in chunks.
    let mut session = a.dial(&b);
    let need = session.send_envelope(&envelopes[0]).expect("push").expect("it wants content");
    assert_eq!(need.bytes, 4096);
    assert!(
        session.send_chunk(&need.blob_id, 0, &[4u8; 4096]).is_err(),
        "a peer's chunk bound is its own, and it says no"
    );
    let _ = session.close();

    let awaiting = b.store.blobs_awaiting_bytes().expect("awaiting");
    assert_eq!(awaiting.len(), 1);
    assert_eq!(
        awaiting[0].received, 0,
        "refused before it was written, not written and then judged"
    );
    assert!(!b.path(&receipt.rel_path).exists());
    assert!(b.store.verify().expect("verify").is_empty());
    assert_eq!(
        b.alert_kinds(),
        vec![alerts::SYNC_LIMIT_EXCEEDED],
        "a bound that stopped a transfer is as visible as one that stopped a stream"
    );
}

#[test]
fn a_misplaced_chunk_halts_that_transfer_and_nothing_else() {
    // A bound on content owes exactly what a bound on an operation owes:
    // a visible alert, one stream stopped, everything else untouched, and
    // a clean resumption. This one used to end the whole session with an
    // error, which cost a peer every other stream over one bad chunk.
    let a = Replica::new();
    let b = Replica::new();
    let receipt = a.store.capture_bytes("wav", &[5u8; 4096]).expect("capture");

    let halted = b.pull_through(&a, Fault::MisplaceChunk);
    assert_eq!(halted.applied, 1, "the operation arrived; it is the content that stopped");
    assert_eq!(halted.halted.len(), 1, "one transfer, not the session");
    assert_eq!(
        halted.halted[0].device_id,
        b.store.blobs_awaiting_bytes().expect("awaiting")[0].blob_id,
        "a stream of content is named by its blob"
    );
    assert!(halted.halted[0].reason.contains("0 bytes are staged"), "{:?}", halted.halted);
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_CORRUPT_OPERATION], "and it is not silent");
    assert!(!b.path(&receipt.rel_path).exists(), "no hole was written");
    assert!(b.store.verify().expect("verify").is_empty());

    // An honest transport, and the transfer picks up where it stopped.
    while b.pull_from(&a).incomplete() {}
    assert_eq!(std::fs::read(b.path(&receipt.rel_path)).expect("arrived").len(), 4096);
    assert_eq!(a.digest(), b.digest());
    assert!(b.alert_kinds().is_empty(), "a condition that clears stops being reported");
}

#[test]
fn content_that_fails_its_digest_never_reaches_the_vault() {
    let a = Replica::new();
    let b = Replica::new();
    let receipt = a.store.capture_bytes("wav", &[1u8; 512]).expect("capture");
    let envelopes = a
        .store
        .operations_after(&a.store.device_id().expect("device"), 0, 10)
        .expect("ops");

    let mut session = a.dial(&b);
    let need = session.send_envelope(&envelopes[0]).expect("push").expect("wants content");
    // The right length, the wrong bytes: only the digest catches this.
    session.send_chunk(&need.blob_id, 0, &[9u8; 512]).expect("accepted by length");
    let _ = session.close();

    assert!(!b.path(&receipt.rel_path).exists());
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_BLOB_DIGEST_MISMATCH]);
    assert!(b.store.verify().expect("verify").is_empty());

    // And the transfer can be made again, correctly.
    while b.pull_from(&a).incomplete() {}
    assert_eq!(std::fs::read(b.path(&receipt.rel_path)).expect("arrived"), vec![1u8; 512]);
    assert!(b.alert_kinds().is_empty());
}
