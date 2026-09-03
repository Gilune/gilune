//! The operation envelope as a public contract with portable test
//! vectors (ADR 0005's gate; ADR 0019 gate A point 3).
//!
//! The vectors are plain files under `kernel/vectors/operations-v1/`, not
//! fixtures compiled into a test binary, because their whole purpose is
//! to be readable by an implementation that is not this one — the Kotlin
//! side of a future wire contract, a third-party tool, or this codec
//! rewritten. `manifest.json` names each file and what a decoder must do
//! with it, so the set can be checked mechanically rather than by
//! reading Rust.
//!
//! Two properties are pinned here:
//!
//! - **Every valid vector round-trips byte-for-byte.** Decoding then
//!   re-encoding must reproduce the file exactly. That is what makes the
//!   encoding canonical, and it is what lets a replica forward a third
//!   device's operations without having encoded them.
//! - **Every invalid vector is refused.** Not "handled" — refused, with
//!   the field or condition named, so a malformed operation halts a
//!   stream instead of being half-believed.

use gilune_kernel::limits::Limits;
use gilune_kernel::operations::{self, Envelope, ENVELOPE_VERSION};
use std::path::PathBuf;

fn vectors() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../vectors/operations-v1")
}

fn manifest() -> serde_json::Value {
    let path = vectors().join("manifest.json");
    let text = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("reading {}: {e}", path.display()));
    serde_json::from_str(&text).expect("manifest is json")
}

#[test]
fn every_valid_vector_decodes_and_re_encodes_to_exactly_the_same_bytes() {
    let manifest = manifest();
    let cases = manifest["valid"].as_array().expect("valid vectors");
    assert!(cases.len() >= 5, "the set must cover every kind the codec writes");
    for case in cases {
        let name = case["file"].as_str().expect("file");
        let path = vectors().join("valid").join(name);
        let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("reading {name}: {e}"));
        let envelope = operations::decode(&bytes, &Limits::DEFAULT)
            .unwrap_or_else(|e| panic!("{name} must decode: {e}"));
        assert_eq!(
            envelope.kind,
            case["kind"].as_str().expect("kind"),
            "{name} is the kind the manifest says it is"
        );
        assert_eq!(
            operations::encode(&envelope),
            bytes,
            "{name} must re-encode byte-for-byte, or the encoding is not canonical"
        );
    }
}

#[test]
fn every_malformed_and_out_of_bounds_vector_is_refused() {
    let manifest = manifest();
    let cases = manifest["invalid"].as_array().expect("invalid vectors");
    assert!(cases.len() >= 15, "an adversarial parser needs an adversarial set");
    for case in cases {
        let name = case["file"].as_str().expect("file");
        let why = case["reason"].as_str().expect("reason");
        let path = vectors().join("invalid").join(name);
        let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("reading {name}: {e}"));
        // The small cap is part of the set: one vector is refused for
        // being too large, and its limit has to be reachable.
        let limits = Limits { max_envelope_bytes: 4096, ..Limits::DEFAULT };
        assert!(
            operations::decode(&bytes, &limits).is_err(),
            "{name} must be refused ({why})"
        );
    }
}

#[test]
fn the_vector_set_covers_every_kind_this_codec_writes() {
    let manifest = manifest();
    let kinds: Vec<&str> = manifest["valid"]
        .as_array()
        .expect("valid")
        .iter()
        .filter_map(|c| c["kind"].as_str())
        .collect();
    for required in [
        operations::OBJECT_CREATED,
        operations::OBJECT_REVISED,
        operations::TOMBSTONE,
    ] {
        assert!(kinds.contains(&required), "no vector for {required}");
    }
    assert!(
        kinds.iter().any(|k| !k.starts_with("object") && *k != operations::TOMBSTONE),
        "the set must include a kind this codec does not interpret, \
         because forward compatibility is part of the contract"
    );
}

#[test]
fn the_origin_keys_are_additive_and_a_decoder_must_read_both_shapes() {
    // The contract the whole `sync-awaiting-referent` exit rests on: a
    // naming operation *may* say whose the thing it names is, and one
    // written before the key existed says nothing and is still valid.
    // A decoder that required the keys would refuse every operation any
    // shipped device has ever written.
    let read = |name: &str| -> serde_json::Value {
        let bytes = std::fs::read(vectors().join("valid").join(name))
            .unwrap_or_else(|e| panic!("reading {name}: {e}"));
        let envelope = operations::decode(&bytes, &Limits::DEFAULT)
            .unwrap_or_else(|e| panic!("{name} must decode: {e}"));
        envelope.payload
    };
    for name in ["object-revised-concurrent.json", "tombstone.json"] {
        let payload = read(name);
        assert!(
            payload.get(operations::OBJECT_ORIGIN_DEVICE).is_none(),
            "{name} is the pre-key shape and must stay that way"
        );
    }
    let named = read("object-revised-named-origins.json");
    assert!(named.get(operations::OBJECT_ORIGIN_DEVICE).is_some());
    assert_eq!(
        named[operations::SUPERSEDES_ORIGIN]
            .as_object()
            .expect("a map from head to origin")
            .len(),
        2,
        "every superseded head whose origin is known is named"
    );
    let tombstone = read("tombstone-named-origins.json");
    assert!(
        tombstone["supersedes"].as_array().is_some_and(|h| !h.is_empty()),
        "a tombstone names the heads the deleter saw"
    );
    assert!(tombstone.get(operations::OBJECT_ORIGIN_DEVICE).is_some());
}

/// Regenerate the vector set and its manifest. Ignored by default: the
/// committed files are the contract, and a change that alters them is a
/// change to a public format which must be a deliberate act, not a side
/// effect of running the suite. Run with
/// `cargo test -p gilune-kernel --test operation_vectors -- --ignored`.
#[test]
#[ignore]
fn regenerate_the_vector_set() {
    let root = vectors();
    std::fs::create_dir_all(root.join("valid")).expect("valid directory");
    std::fs::create_dir_all(root.join("invalid")).expect("invalid directory");

    let mut valid = Vec::new();
    for (name, envelope) in canonical_examples() {
        std::fs::write(root.join("valid").join(name), operations::encode(&envelope))
            .expect("write vector");
        valid.push(serde_json::json!({ "file": name, "kind": envelope.kind }));
    }

    let mut invalid = Vec::new();
    for (name, reason, bytes) in malformed_examples() {
        std::fs::write(root.join("invalid").join(name), &bytes).expect("write vector");
        invalid.push(serde_json::json!({ "file": name, "reason": reason }));
    }

    let manifest = serde_json::json!({
        "format": "gilune-operation-envelope",
        "version": ENVELOPE_VERSION,
        "note": "Every file under valid/ must decode and re-encode to identical \
                 bytes. Every file under invalid/ must be refused, with the named \
                 condition. Decoders are checked against a 4096-byte envelope cap.",
        "valid": valid,
        "invalid": invalid,
    });
    std::fs::write(
        root.join("manifest.json"),
        serde_json::to_string_pretty(&manifest).expect("manifest"),
    )
    .expect("write manifest");
}

/// One valid envelope, damaged one way at a time. Deriving them from a
/// good one is what makes the set adversarial rather than arbitrary:
/// each file differs from a decodable operation in exactly one respect,
/// so a decoder that accepts one has a specific hole.
fn malformed_examples() -> Vec<(&'static str, &'static str, Vec<u8>)> {
    let good = operations::encode(&canonical_examples()[3].1);
    let base: serde_json::Value = serde_json::from_slice(&good).expect("json");
    let mutate = |field: &str, value: serde_json::Value| -> Vec<u8> {
        let mut broken = base.clone();
        broken[field] = value;
        broken.to_string().into_bytes()
    };
    let without = |field: &str| -> Vec<u8> {
        let mut broken = base.clone();
        broken.as_object_mut().expect("object").remove(field);
        broken.to_string().into_bytes()
    };

    let mut oversize = base.clone();
    oversize["payload"] = serde_json::json!({ "padding": "x".repeat(8192) });

    vec![
        ("not-json.json", "not JSON at all", b"{definitely not json".to_vec()),
        ("empty.json", "no bytes", Vec::new()),
        ("top-level-array.json", "top level is an array", b"[]".to_vec()),
        ("top-level-string.json", "top level is a string", b"\"an operation\"".to_vec()),
        ("not-utf8.json", "not valid UTF-8", vec![0xff, 0xfe, 0x7b, 0x7d]),
        (
            "trailing-garbage.json",
            "valid JSON followed by more bytes",
            [good.clone(), b" and then some".to_vec()].concat(),
        ),
        ("version-missing.json", "no codec version", without("version")),
        ("version-future.json", "a codec version this kernel cannot read", mutate("version", serde_json::json!(2))),
        ("version-zero.json", "codec version zero", mutate("version", serde_json::json!(0))),
        ("version-string.json", "codec version is a string", mutate("version", serde_json::json!("1"))),
        ("op-seq-missing.json", "no sequence number", without("op_seq")),
        ("op-seq-zero.json", "sequence numbers start at one", mutate("op_seq", serde_json::json!(0))),
        ("op-seq-negative.json", "negative sequence number", mutate("op_seq", serde_json::json!(-1))),
        (
            "op-seq-beyond-i64.json",
            "sequence number past the representable range, which must be refused not truncated",
            br#"{"created_ms":1700000000003,"device_id":"01a06435-51a7-7653-b4f4-b5c2e919f2a7","kind":"tombstone","op_id":"01a06436-9a69-74b0-9ff9-85cedc4221c4","op_seq":9223372036854775808,"payload":{},"target_id":"01a06436-9a71-7000-8000-000000000002","version":1}"#.to_vec(),
        ),
        ("op-seq-float.json", "sequence number is not an integer", mutate("op_seq", serde_json::json!(1.5))),
        ("op-id-missing.json", "no operation id", without("op_id")),
        ("op-id-too-long.json", "operation id past the length bound", mutate("op_id", serde_json::json!("x".repeat(129)))),
        ("device-id-missing.json", "no device id", without("device_id")),
        ("device-id-empty.json", "empty device id", mutate("device_id", serde_json::json!(""))),
        ("device-id-number.json", "device id is not a string", mutate("device_id", serde_json::json!(7))),
        (
            "device-id-control-characters.json",
            "device id carries control characters, which reach file names and log lines",
            mutate("device_id", serde_json::json!("a\u{0}b")),
        ),
        ("kind-missing.json", "no kind", without("kind")),
        ("kind-empty.json", "empty kind", mutate("kind", serde_json::json!(""))),
        ("kind-too-long.json", "kind past the length bound", mutate("kind", serde_json::json!("k".repeat(65)))),
        ("target-id-missing.json", "no target", without("target_id")),
        ("target-id-empty.json", "empty target", mutate("target_id", serde_json::json!(""))),
        ("created-ms-missing.json", "no timestamp", without("created_ms")),
        ("created-ms-negative.json", "a timestamp before the epoch", mutate("created_ms", serde_json::json!(-1))),
        ("payload-missing.json", "no payload", without("payload")),
        ("payload-array.json", "payload is an array", mutate("payload", serde_json::json!([]))),
        ("payload-string.json", "payload is a string", mutate("payload", serde_json::json!("not an object"))),
        ("oversize.json", "larger than the envelope cap", oversize.to_string().into_bytes()),
    ]
}

/// The operations this codec writes, one of each, with values chosen to
/// exercise the shapes rather than to look plausible.
fn canonical_examples() -> Vec<(&'static str, Envelope)> {
    let device = "01a06435-51a7-7653-b4f4-b5c2e919f2a7";
    let created = 1_700_000_000_000i64;
    let document = serde_json::json!({
        "media": "wav",
        "blob_id": "01a06435-51c7-75f1-80a5-405988e9c26d",
        "rel_path": "Captures/capture-405988e9c26d.wav",
        "sha256": "69bbaf13cfdd9c38e4c515c4c83bfdcf74096bff0f428dbe24d3c7a7afff161e",
        "bytes": 2048,
    })
    .to_string();

    vec![
        (
            "object-created-capture.json",
            Envelope {
                version: ENVELOPE_VERSION,
                op_id: "01a06436-9a69-74b0-9ff9-85cedc4221c1".to_string(),
                device_id: device.to_string(),
                op_seq: 1,
                kind: operations::OBJECT_CREATED.to_string(),
                target_id: "01a06436-9a6f-79b1-94c9-a3513c0c693a".to_string(),
                created_ms: created,
                payload: serde_json::json!({
                    "object": {
                        "object_id": "01a06436-9a6f-79b1-94c9-a3513c0c693a",
                        "type_id": "org.memory.source",
                        "type_schema": 1,
                        "created_ms": created,
                    },
                    "revision": {
                        "revision_id": "01a06436-9a70-7000-8000-000000000001",
                        "payload": document,
                        "origin": "capture",
                        "created_ms": created,
                    },
                    "blob": {
                        "blob_id": "01a06435-51c7-75f1-80a5-405988e9c26d",
                        "rel_path": "Captures/capture-405988e9c26d.wav",
                        "sha256":
                            "69bbaf13cfdd9c38e4c515c4c83bfdcf74096bff0f428dbe24d3c7a7afff161e",
                        "bytes": 2048,
                    },
                    "event_type": "org.memory.source-ready",
                }),
            },
        ),
        (
            "object-created-derived.json",
            Envelope {
                version: ENVELOPE_VERSION,
                op_id: "01a06436-9a69-74b0-9ff9-85cedc4221c2".to_string(),
                device_id: device.to_string(),
                op_seq: 2,
                kind: operations::OBJECT_CREATED.to_string(),
                target_id: "01a06436-9a71-7000-8000-000000000002".to_string(),
                created_ms: created + 1,
                payload: serde_json::json!({
                    "object": {
                        "object_id": "01a06436-9a71-7000-8000-000000000002",
                        "type_id": "com.example.widget",
                        "type_schema": 3,
                        "created_ms": created + 1,
                    },
                    "revision": {
                        "revision_id": "01a06436-9a72-7000-8000-000000000003",
                        "payload": "{\"corners\":4,\"colour\":\"amber\"}",
                        "origin": "processor:example",
                        "created_ms": created + 1,
                    },
                    "derived_from": "01a06436-9a6f-79b1-94c9-a3513c0c693a",
                    "processor_id": "example",
                    "event_type": "org.memory.widget-ready",
                }),
            },
        ),
        (
            "object-revised-concurrent.json",
            Envelope {
                version: ENVELOPE_VERSION,
                op_id: "01a06436-9a69-74b0-9ff9-85cedc4221c3".to_string(),
                device_id: device.to_string(),
                op_seq: 3,
                kind: operations::OBJECT_REVISED.to_string(),
                target_id: "01a06436-9a6f-79b1-94c9-a3513c0c693a".to_string(),
                created_ms: created + 2,
                payload: serde_json::json!({
                    "object_id": "01a06436-9a6f-79b1-94c9-a3513c0c693a",
                    "revision": {
                        "revision_id": "01a06436-9a73-7000-8000-000000000004",
                        "payload": "{\"text\":\"what one device wrote\"}",
                        "origin": "external-edit",
                        "created_ms": created + 2,
                    },
                    // Two superseded heads: this operation is what
                    // resolving a conflict looks like on the wire.
                    "supersedes": [
                        "01a06436-9a70-7000-8000-000000000001",
                        "01a06436-9a74-7000-8000-000000000005",
                    ],
                    "event_type": "org.memory.revision-added",
                }),
            },
        ),
        (
            "tombstone.json",
            Envelope {
                version: ENVELOPE_VERSION,
                op_id: "01a06436-9a69-74b0-9ff9-85cedc4221c4".to_string(),
                device_id: device.to_string(),
                op_seq: 4,
                kind: operations::TOMBSTONE.to_string(),
                target_id: "01a06436-9a71-7000-8000-000000000002".to_string(),
                created_ms: created + 3,
                payload: serde_json::json!({}),
            },
        ),
        (
            "unknown-kind.json",
            Envelope {
                version: ENVELOPE_VERSION,
                op_id: "01a06436-9a69-74b0-9ff9-85cedc4221c5".to_string(),
                device_id: device.to_string(),
                op_seq: 5,
                kind: "com.example.widget-moved".to_string(),
                target_id: "01a06436-9a71-7000-8000-000000000002".to_string(),
                created_ms: created + 4,
                payload: serde_json::json!({ "to": [3, 4], "by": "someone else's plugin" }),
            },
        ),
        // The origin keys, added deliberately. A naming operation says
        // whose the thing it names is, so a receiver that has never seen
        // that thing can still tell "it has not arrived yet" from "it
        // belongs to a device I have revoked and can never arrive".
        // Without them a revoked device silences a healthy one for ever.
        //
        // Both are **additive and optional**: the two vectors above name
        // no origins and must still decode, because that is what every
        // operation written before this key existed looks like.
        (
            "object-revised-named-origins.json",
            Envelope {
                version: ENVELOPE_VERSION,
                op_id: "01a06436-9a69-74b0-9ff9-85cedc4221c6".to_string(),
                device_id: device.to_string(),
                op_seq: 6,
                kind: operations::OBJECT_REVISED.to_string(),
                target_id: "01a06436-9a6f-79b1-94c9-a3513c0c693a".to_string(),
                created_ms: created + 5,
                payload: serde_json::json!({
                    "object_id": "01a06436-9a6f-79b1-94c9-a3513c0c693a",
                    "revision": {
                        "revision_id": "01a06436-9a75-7000-8000-000000000006",
                        "payload": "{\"text\":\"written over two heads\"}",
                        "origin": "external-edit",
                        "created_ms": created + 5,
                    },
                    "supersedes": [
                        "01a06436-9a70-7000-8000-000000000001",
                        "01a06436-9a74-7000-8000-000000000005",
                    ],
                    // The object is this device's own; one of the heads it
                    // supersedes came from somewhere else entirely.
                    (operations::OBJECT_ORIGIN_DEVICE): device,
                    (operations::SUPERSEDES_ORIGIN): {
                        "01a06436-9a70-7000-8000-000000000001": device,
                        "01a06436-9a74-7000-8000-000000000005":
                            "01a06435-51a7-7653-b4f4-000000000009",
                    },
                    "event_type": "org.memory.revision-added",
                }),
            },
        ),
        (
            "tombstone-named-origins.json",
            Envelope {
                version: ENVELOPE_VERSION,
                op_id: "01a06436-9a69-74b0-9ff9-85cedc4221c7".to_string(),
                device_id: device.to_string(),
                op_seq: 7,
                kind: operations::TOMBSTONE.to_string(),
                target_id: "01a06436-9a6f-79b1-94c9-a3513c0c693a".to_string(),
                created_ms: created + 6,
                payload: serde_json::json!({
                    // A tombstone names the heads the deleter could see,
                    // exactly as a revision names the heads it supersedes.
                    // A head it does not name was written concurrently
                    // with the deletion, and the projection keeps that
                    // file rather than destroying somebody's words.
                    "supersedes": ["01a06436-9a75-7000-8000-000000000006"],
                    (operations::OBJECT_ORIGIN_DEVICE): device,
                    (operations::SUPERSEDES_ORIGIN): {
                        "01a06436-9a75-7000-8000-000000000006": device,
                    },
                }),
            },
        ),
    ]
}

#[test]
fn the_committed_vectors_are_what_this_codec_produces_today() {
    for (name, envelope) in canonical_examples() {
        let path = vectors().join("valid").join(name);
        let committed =
            std::fs::read(&path).unwrap_or_else(|e| panic!("reading {name}: {e}"));
        assert_eq!(
            operations::encode(&envelope),
            committed,
            "{name} drifted: the codec changed, or the vector did. Either is a \
             change to a public format and must be deliberate."
        );
    }
}
