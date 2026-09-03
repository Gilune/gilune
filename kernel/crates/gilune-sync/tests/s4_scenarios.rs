//! The S4 scenario suite, re-run against the **production** kernel over a
//! real loopback socket (ADR 0019 gate A, points 1 and 2).
//!
//! Spike S4 proved these semantics over a shared folder, where every
//! message was a whole file that could not be truncated, reordered or
//! oversized by its sender. This file is the same ten scenarios plus the
//! three the ADR adds, against the kernel that ships, over a transport
//! that can do all three.

mod replica;

use gilune_kernel::alerts;
use gilune_kernel::limits::Limits;
use gilune_kernel::operations::{self, Envelope};
use replica::{Fault, Replica};

/// A hand-built operation from a device that does not exist, for the
/// scenarios about data this kernel is not supposed to understand.
fn foreign(device: &str, seq: i64, kind: &str, target: &str, payload: serde_json::Value) -> Vec<u8> {
    operations::encode(&Envelope {
        version: operations::ENVELOPE_VERSION,
        op_id: format!("{device}-{seq}"),
        device_id: device.to_string(),
        op_seq: seq,
        kind: kind.to_string(),
        target_id: target.to_string(),
        created_ms: 1_700_000_000_000 + seq,
        payload,
    })
}

// ---------------------------------------------------------------- scenario 1

#[test]
fn a_capture_flows_to_a_second_replica_without_being_reprocessed_there() {
    let a = Replica::new().with_processor();
    let b = Replica::new().with_processor();

    let receipt = a.store.capture_bytes("wav", &[3u8; 2048]).expect("capture");
    assert_eq!(a.work(), 1, "the device that captured it does the work");
    a.store.project().expect("project");

    let report = b.pull_from(&a);
    assert_eq!(report.applied, 2, "the capture and the transcription revision");
    assert_eq!(report.blobs_completed, 1, "and the bytes that go with them");
    assert!(report.halted.is_empty());

    assert_eq!(a.digest(), b.digest(), "two replicas, one state");
    assert_eq!(
        b.store.status().expect("status").jobs_pending,
        0,
        "work belongs to the device that captured it"
    );
    assert_eq!(b.work(), 0, "and the second replica finds nothing to do");
    assert_eq!(
        b.store.backfill_jobs().expect("backfill"),
        0,
        "not even when a person deliberately asks for a catch-up"
    );

    b.store.project().expect("project");
    let rel = a.projection_of(&receipt.source_id).expect("projected on a");
    assert_eq!(rel, b.projection_of(&receipt.source_id).expect("projected on b"));
    assert_eq!(
        std::fs::read(a.path(&rel)).expect("a"),
        std::fs::read(b.path(&rel)).expect("b"),
        "the same committed state projects the same bytes"
    );
    assert!(b.store.verify().expect("verify").is_empty());
}

// ---------------------------------------------------------------- scenario 2

#[test]
fn pulling_before_the_origin_has_processed_still_respects_the_origin_device() {
    let a = Replica::new().with_processor();
    let b = Replica::new().with_processor();

    a.store.capture_bytes("wav", &[8u8; 1024]).expect("capture");
    let first = b.pull_from(&a);
    assert_eq!(first.applied, 1, "the raw capture, and nothing else exists yet");
    assert_eq!(b.work(), 0, "the arrival of a capture is not a claim on it");

    assert_eq!(a.work(), 1);
    let second = b.pull_from(&a);
    assert_eq!(second.applied, 1, "exactly the operation that was missing");
    assert_eq!(a.digest(), b.digest());
    assert_eq!(b.store.processing_run_count("transcribe").expect("runs"), 0);
}

// ---------------------------------------------------------------- scenario 3

#[test]
fn concurrent_edits_on_two_replicas_preserve_both_heads() {
    let a = Replica::new();
    let b = Replica::new();
    let receipt = a.store.capture_bytes("txt", b"the original words").expect("capture");
    a.store.project().expect("project");
    b.pull_from(&a);
    b.store.project().expect("project");

    let rel = a.projection_of(&receipt.source_id).expect("projected");
    for (side, words) in [(&a, "what a wrote offline"), (&b, "what b wrote offline")] {
        let text = std::fs::read_to_string(side.path(&rel)).expect("read");
        std::fs::write(side.path(&rel), text.replace("the original words", words)).expect("edit");
        side.store.reconcile().expect("reconcile");
    }

    a.pull_from(&b);
    b.pull_from(&a);

    for side in [&a, &b] {
        assert_eq!(
            side.store.object_heads(&receipt.source_id).expect("heads").len(),
            2,
            "both devices wrote something true and both are kept"
        );
    }
    assert_eq!(a.digest(), b.digest(), "conflicted is a state replicas agree on");

    a.store.project().expect("project");
    let projected = std::fs::read_to_string(a.path(&rel)).expect("read");
    assert!(
        projected.contains("gilune-conflicted: true"),
        "the conflict is surfaced, never hidden behind a winner"
    );

    // Editing a conflicted note is how a person resolves it.
    std::fs::write(a.path(&rel), projected.replace("what b wrote offline", "settled")).expect("w");
    a.store.reconcile().expect("reconcile");
    assert_eq!(a.store.object_heads(&receipt.source_id).expect("heads").len(), 1);
    b.pull_from(&a);
    assert_eq!(b.store.object_heads(&receipt.source_id).expect("heads").len(), 1);
    assert_eq!(a.digest(), b.digest());
}

// ---------------------------------------------------------------- scenario 4

#[test]
fn a_repeated_pull_is_a_no_op_and_a_late_joiner_converges_from_scratch() {
    let a = Replica::new().with_processor();
    let b = Replica::new();
    a.store.capture_bytes("txt", b"first").expect("capture");
    a.store.capture_bytes("wav", &[1u8; 512]).expect("capture");
    assert_eq!(a.work(), 1);

    let first = b.pull_from(&a);
    assert!(first.applied > 0);
    let again = b.pull_from(&a);
    assert_eq!(again.applied, 0, "everything already applied stays applied once");
    assert_eq!(again.duplicates, 0, "and is not even asked for again");
    assert_eq!(a.digest(), b.digest());

    let late = Replica::new();
    late.pull_from(&a);
    assert_eq!(late.digest(), a.digest(), "a device that arrives later catches up");
    assert!(late.store.verify().expect("verify").is_empty());
}

// ---------------------------------------------------------------- scenario 5

#[test]
fn a_sequence_gap_halts_that_device_until_the_missing_operation_arrives() {
    let a = Replica::new();
    let b = Replica::new();
    for word in ["one", "two", "three"] {
        a.store.capture_bytes("txt", word.as_bytes()).expect("capture");
    }

    let halted = b.pull_through(&a, Fault::WithholdSeq(2));
    assert_eq!(halted.applied, 1, "everything before the hole, and nothing after it");
    assert_eq!(halted.halted.len(), 1);
    assert!(halted.halted[0].reason.contains("expected seq 2"));
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_GAP], "the halt is visible");
    assert_ne!(a.digest(), b.digest());

    // The hole closes; application resumes exactly where it stopped.
    let repaired = b.pull_from(&a);
    assert_eq!(repaired.applied, 2, "the two that were held back, and no more");
    assert!(repaired.halted.is_empty());
    assert_eq!(a.digest(), b.digest());
    assert!(b.alert_kinds().is_empty(), "a condition that clears stops being reported");
}

// ---------------------------------------------------------------- scenario 6

#[test]
fn a_corrupt_operation_halts_the_stream_with_nothing_half_applied() {
    let a = Replica::new();
    let b = Replica::new();
    a.store.capture_bytes("txt", b"perfectly good words").expect("capture");
    a.store.capture_bytes("txt", b"and more of them").expect("capture");

    let halted = b.pull_through(&a, Fault::CorruptFirst);
    assert_eq!(halted.applied, 0, "nothing past the fault, and the fault was first");
    assert_eq!(halted.halted.len(), 1);
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_CORRUPT_OPERATION]);
    assert_eq!(
        b.store.status().expect("status").sources,
        0,
        "a refused operation leaves no trace of itself"
    );
    assert!(b.store.verify().expect("verify").is_empty());

    let repaired = b.pull_from(&a);
    assert_eq!(repaired.applied, 2);
    assert_eq!(a.digest(), b.digest());
    assert!(b.alert_kinds().is_empty());
}

// ---------------------------------------------------------------- scenario 7

#[test]
fn an_interrupted_blob_transfer_completes_byte_identically_when_it_resumes() {
    let content: Vec<u8> = (0..200_000u32).map(|i| (i % 253) as u8).collect();
    let a = Replica::new();
    // A chunk size that makes the transfer many round trips, and a byte
    // budget that runs out partway through: an interruption, expressed as
    // the bound that causes it rather than a socket someone unplugs.
    let b = Replica::with_limits(Limits {
        chunk_bytes: 8 * 1024,
        max_bytes_per_session: 40 * 1024,
        ..Limits::DEFAULT
    });

    let receipt = a.store.capture_bytes("wav", &content).expect("capture");
    let partial = b.pull_from(&a);
    assert_eq!(partial.applied, 1, "the envelope applies before its content arrives");
    assert_eq!(partial.blobs_pending, 1);
    assert!(partial.incomplete(), "and the report says to come back");
    assert!(
        !b.path(&receipt.rel_path).exists(),
        "nothing reaches the vault until every byte is verified"
    );
    // The vault is usable meanwhile: one interrupted transfer must not
    // stop a device projecting everything else it knows.
    b.store.project().expect("project");
    assert!(b.store.verify().expect("verify").is_empty(), "in transit is not damage");

    let mut rounds = 0;
    while b.pull_from(&a).incomplete() && rounds < 40 {
        rounds += 1;
    }
    assert_eq!(
        std::fs::read(b.path(&receipt.rel_path)).expect("assembled"),
        content,
        "what arrives is byte-identical to what was captured"
    );
    assert_eq!(a.digest(), b.digest());
    assert!(b.store.verify().expect("verify").is_empty());
    assert!(b.alert_kinds().is_empty());
}

// ---------------------------------------------------------------- scenario 8

#[test]
fn a_tombstone_propagates_and_withdraws_the_remote_projection() {
    let a = Replica::new();
    let b = Replica::new();
    let receipt = a.store.capture_bytes("txt", b"this will be deleted").expect("capture");
    a.store.project().expect("project");
    b.pull_from(&a);
    b.store.project().expect("project");
    let rel = b.projection_of(&receipt.source_id).expect("projected");
    assert!(b.path(&rel).exists());

    std::fs::remove_file(a.path(&rel)).expect("delete on a");
    a.store.reconcile().expect("reconcile");
    let proposal = a.store.open_proposals().expect("proposals")[0].0.clone();
    a.store.resolve_tombstone(&proposal, true).expect("accept");

    let report = b.pull_from(&a);
    assert_eq!(report.applied, 1, "a deletion is one operation like any other");
    let summary = b.store.project().expect("project");
    assert_eq!(summary.removed, 1);
    assert!(!b.path(&rel).exists(), "the file goes");
    assert_eq!(a.digest(), b.digest(), "and history keeps the tombstone on both");
    assert_eq!(b.store.status().expect("status").sources, 1, "the object is not erased");
}

// ---------------------------------------------------------------- scenario 9

#[test]
fn an_object_of_an_unknown_type_round_trips_verbatim_and_projects_generically() {
    let a = Replica::new();
    let c = Replica::new();
    let document = serde_json::json!({ "colour": "amber", "corners": 4 }).to_string();
    let bytes = foreign(
        "device-elsewhere",
        1,
        operations::OBJECT_CREATED,
        "widget-1",
        serde_json::json!({
            "object": {
                "object_id": "widget-1",
                "type_id": "com.example.widget",
                "type_schema": 3,
                "created_ms": 1_700_000_000_000i64,
            },
            "revision": {
                "revision_id": "widget-rev-1",
                "payload": document,
                "origin": "plugin:com.example.widgets",
                "created_ms": 1_700_000_000_000i64,
            },
        }),
    );

    a.store.apply_operation("test", &bytes, Limits::DEFAULT).expect("apply");
    a.store.project().expect("project");
    // A blobless object of an unknown type projects into a folder named
    // for its type: the generic path the projection falls back to.
    let rel = format!("Widgets/widget-{}.md", gilune_kernel::ids::short("widget-1"));
    let projected = std::fs::read_to_string(a.path(&rel)).expect("projected generically");
    assert!(
        projected.contains("com.example.widget") && projected.contains("amber"),
        "a type nothing here understands is still preserved legibly"
    );

    // And a third replica receives it from the second, byte for byte.
    c.pull_from(&a);
    assert_eq!(
        c.store.operations_after("device-elsewhere", 0, 10).expect("ops"),
        vec![bytes],
        "a full replica forwards a third device's history unaltered"
    );
    assert_eq!(a.digest(), c.digest());
}

#[test]
fn an_operation_of_an_unknown_kind_is_preserved_and_forwarded_but_never_applied() {
    let b = Replica::new();
    let c = Replica::new();
    let bytes = foreign(
        "device-elsewhere",
        1,
        "com.example.widgets/widget-moved",
        "widget-1",
        serde_json::json!({ "to": [3, 4] }),
    );

    let applied = b.store.apply_operation("test", &bytes, Limits::DEFAULT).expect("apply");
    assert!(applied.uninterpreted, "understood as data, not as an instruction");
    assert_eq!(b.store.status().expect("status").sources, 0);
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_UNKNOWN_OP_KIND]);
    assert_eq!(
        b.store.sync_frontier("device-elsewhere").expect("frontier"),
        1,
        "an operation nothing here understands must not halt the stream"
    );

    c.pull_from(&b);
    assert_eq!(
        c.store.operations_after("device-elsewhere", 0, 10).expect("ops"),
        vec![bytes]
    );
}

#[test]
fn one_operation_id_claiming_two_places_in_history_halts_by_name() {
    let b = Replica::new();
    let first = foreign(
        "device-elsewhere",
        1,
        operations::TOMBSTONE,
        "object-1",
        serde_json::json!({}),
    );
    b.store.apply_operation("test", &first, Limits::DEFAULT).expect("apply");

    // The same operation id, at the sequence that comes next. Not a
    // duplicate — a forgery, and one the primary key would otherwise
    // report as a storage fault nobody could act on.
    let forged = operations::encode(&Envelope {
        version: operations::ENVELOPE_VERSION,
        op_id: "device-elsewhere-1".to_string(),
        device_id: "device-elsewhere".to_string(),
        op_seq: 2,
        kind: operations::TOMBSTONE.to_string(),
        target_id: "object-2".to_string(),
        created_ms: 1_700_000_000_002,
        payload: serde_json::json!({}),
    });
    let refused = b.store.apply_operation("test", &forged, Limits::DEFAULT);
    assert!(matches!(
        refused,
        Err(gilune_kernel::KernelError::SyncHalted { .. })
    ));
    assert_eq!(b.alert_kinds(), vec![alerts::SYNC_CORRUPT_OPERATION]);
    assert_eq!(
        b.store.sync_frontier("device-elsewhere").expect("frontier"),
        1,
        "nothing past the fault, and the frontier is where it was"
    );
}

// --------------------------------------------------------------- scenario 10

#[test]
fn three_replicas_converge_with_mixed_writers() {
    let a = Replica::new().with_processor();
    let b = Replica::new();
    let c = Replica::new();

    a.store.capture_bytes("txt", b"a's thought").expect("capture");
    a.work();
    b.store.capture_bytes("txt", b"b's thought").expect("capture");
    c.store.capture_bytes("wav", &[5u8; 4096]).expect("capture");

    // Everyone meets everyone once, then once more so each carries the
    // third device's history it only learned about on the first round.
    for _ in 0..2 {
        a.sync_with(&b);
        b.sync_with(&c);
        c.sync_with(&a);
    }

    assert_eq!(a.digest(), b.digest());
    assert_eq!(b.digest(), c.digest());
    for side in [&a, &b, &c] {
        assert!(side.store.verify().expect("verify").is_empty());
        assert!(side.alert_kinds().is_empty());
        assert_eq!(side.store.status().expect("status").sources, 3);
        side.store.project().expect("project");
    }
    assert_eq!(
        a.store.processing_run_count("transcribe").expect("a"),
        1,
        "only the device that captured it transcribed it"
    );
    for side in [&b, &c] {
        assert_eq!(side.store.processing_run_count("transcribe").expect("runs"), 0);
    }
}

// -------------------------------------------------------- gate A, point 2

#[test]
fn reordered_and_duplicated_deliveries_converge() {
    let a = Replica::new();
    let b = Replica::new();
    let c = Replica::new();
    for word in ["one", "two", "three", "four"] {
        a.store.capture_bytes("txt", word.as_bytes()).expect("capture");
    }

    // Reversed: the kernel refuses anything out of order, so the first
    // frame halts — and the very next session, in order, converges.
    let reversed = b.pull_through(&a, Fault::Reverse);
    assert!(reversed.applied < 4, "an out-of-order stream does not simply apply");
    while b.pull_from(&a).incomplete() {}
    assert_eq!(a.digest(), b.digest(), "and the retry converges");

    // Duplicated: every operation arrives twice, and lands once.
    let doubled = c.pull_through(&a, Fault::Duplicate);
    assert_eq!(doubled.applied, 4);
    assert_eq!(doubled.duplicates, 4, "the second copy of each is recognised");
    assert_eq!(a.digest(), c.digest());
    assert_eq!(c.store.status().expect("status").sources, 4);
}

#[test]
fn an_object_deleted_and_then_restored_converges() {
    let a = Replica::new();
    let b = Replica::new();
    let receipt = a.store.capture_bytes("txt", b"gone and back again").expect("capture");
    a.store.project().expect("project");
    b.pull_from(&a);

    let rel = a.projection_of(&receipt.source_id).expect("projected");
    std::fs::remove_file(a.path(&rel)).expect("delete");
    a.store.reconcile().expect("reconcile");
    let proposal = a.store.open_proposals().expect("proposals")[0].0.clone();
    a.store.resolve_tombstone(&proposal, true).expect("accept");
    b.pull_from(&a);
    assert_eq!(a.digest(), b.digest());

    // Restored: the same words, captured again, are a new object — the
    // tombstone stands and the restoration is history, not an undo.
    a.store.capture_bytes("txt", b"gone and back again").expect("recapture");
    b.pull_from(&a);
    assert_eq!(a.digest(), b.digest());
    assert_eq!(b.store.status().expect("status").sources, 2);
    b.store.project().expect("project");
    assert!(b.store.verify().expect("verify").is_empty());
}

#[test]
fn objects_created_while_both_devices_were_offline_converge_when_they_meet() {
    let a = Replica::new();
    let b = Replica::new();
    // Neither has ever heard of the other.
    a.store.capture_bytes("txt", b"written on the train").expect("capture");
    a.store.capture_bytes("txt", b"and again").expect("capture");
    b.store.capture_bytes("txt", b"written in the kitchen").expect("capture");

    a.sync_with(&b);
    b.sync_with(&a);

    assert_eq!(a.digest(), b.digest());
    assert_eq!(a.store.status().expect("status").sources, 3);
    for side in [&a, &b] {
        side.store.project().expect("project");
        assert!(side.store.verify().expect("verify").is_empty());
    }
}
