//! The termination matrix for the apply path (ADR 0002's gate applied to
//! ADR 0019's new commit points). Capture already survives an abort at
//! every persistence boundary; applying an operation is the second way
//! state now enters this kernel, so it owes the same evidence.
//!
//! The invariant under test is the one the whole halt policy rests on:
//! **nothing is ever half-applied**. A process killed inside apply's
//! transaction leaves no trace and no advanced frontier; killed after
//! it, the operation is there exactly once and re-delivery is a
//! duplicate rather than a second copy.

use gilune_kernel::limits::Limits;
use gilune_kernel::store::Store;
use std::path::Path;
use std::process::{Command, Output};

fn cli(vault: &Path, crashpoint: Option<&str>, args: &[&str]) -> Output {
    let mut cmd = Command::new(env!("CARGO_BIN_EXE_gilune-cli"));
    cmd.arg("--vault").arg(vault).args(args);
    match crashpoint {
        Some(point) => cmd.env("GILUNE_CRASHPOINT", point),
        None => cmd.env_remove("GILUNE_CRASHPOINT"),
    };
    cmd.output().expect("spawn gilune-cli")
}

fn ok_json(vault: &Path, args: &[&str]) -> serde_json::Value {
    let out = cli(vault, None, args);
    assert!(
        out.status.success(),
        "command {args:?} failed:\n{}",
        String::from_utf8_lossy(&out.stderr)
    );
    serde_json::from_slice(&out.stdout).expect("json stdout")
}

/// A vault with two operations to hand over, and their envelope bytes
/// written to files a subprocess can be pointed at.
fn origin_with_two_operations(
    into: &Path,
) -> (String, Vec<std::path::PathBuf>, tempfile::TempDir) {
    let source = tempfile::tempdir().expect("tempdir");
    let store = Store::open(source.path()).expect("open");
    store.capture_bytes("txt", b"the first thing that happened").expect("capture");
    store.capture_bytes("txt", b"and then the second").expect("capture");
    let device = store.device_id().expect("device");
    let envelopes = store.operations_after(&device, 0, 10).expect("operations");
    assert_eq!(envelopes.len(), 2);
    let files: Vec<std::path::PathBuf> = envelopes
        .iter()
        .enumerate()
        .map(|(i, bytes)| {
            let path = into.join(format!("op-{i}.envelope"));
            std::fs::write(&path, bytes).expect("write envelope");
            path
        })
        .collect();
    (device, files, source)
}

#[test]
fn an_abort_inside_the_apply_transaction_leaves_nothing_behind() {
    let dir = tempfile::tempdir().expect("tempdir");
    let scratch = tempfile::tempdir().expect("tempdir");
    let (device, files, _source) = origin_with_two_operations(scratch.path());

    let crashed = cli(
        dir.path(),
        Some("apply.commit.inside"),
        &["apply", "--file", files[0].to_str().expect("path")],
    );
    assert!(!crashed.status.success(), "the process must have died");

    let status = ok_json(dir.path(), &["status"]);
    assert_eq!(status["sources"], 0, "no object");
    assert_eq!(status["operations"], 0, "no operation row");
    assert_eq!(status["events"], 0, "no event");
    assert_eq!(status["blobs_awaiting"], 0, "no blob expectation");
    ok_json(dir.path(), &["verify"]);

    let store = Store::open(dir.path()).expect("reopen");
    assert_eq!(
        store.sync_frontier(&device).expect("frontier"),
        0,
        "the frontier is where it was, so the peer simply sends it again"
    );

    // And sending it again works, exactly once.
    let applied = ok_json(dir.path(), &["apply", "--file", files[0].to_str().expect("path")]);
    assert_eq!(applied["duplicate"], false);
    assert_eq!(ok_json(dir.path(), &["status"])["operations"], 1);
}

#[test]
fn an_abort_after_the_apply_commit_makes_re_delivery_a_duplicate() {
    let dir = tempfile::tempdir().expect("tempdir");
    let scratch = tempfile::tempdir().expect("tempdir");
    let (device, files, _source) = origin_with_two_operations(scratch.path());

    let crashed = cli(
        dir.path(),
        Some("apply.commit.after"),
        &["apply", "--file", files[0].to_str().expect("path")],
    );
    assert!(!crashed.status.success(), "the process must have died after committing");

    let status = ok_json(dir.path(), &["status"]);
    assert_eq!(status["sources"], 1, "a committed application survives the abort");
    assert_eq!(status["operations"], 1);
    let store = Store::open(dir.path()).expect("reopen");
    assert_eq!(store.sync_frontier(&device).expect("frontier"), 1);

    let again = ok_json(dir.path(), &["apply", "--file", files[0].to_str().expect("path")]);
    assert_eq!(again["duplicate"], true, "at-least-once delivery lands once");
    assert_eq!(ok_json(dir.path(), &["status"])["operations"], 1);

    // And the stream carries on from there rather than being stuck.
    let next = ok_json(dir.path(), &["apply", "--file", files[1].to_str().expect("path")]);
    assert_eq!(next["duplicate"], false);
    assert_eq!(ok_json(dir.path(), &["status"])["sources"], 2);
    ok_json(dir.path(), &["verify"]);
}

#[test]
fn recovery_completes_a_promotion_that_died_before_the_row_was_updated() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source = tempfile::tempdir().expect("tempdir");
    let content: Vec<u8> = (0..2048u32).map(|i| (i % 197) as u8).collect();
    let origin = Store::open(source.path()).expect("open");
    let receipt = origin.capture_bytes("wav", &content).expect("capture");
    let device = origin.device_id().expect("device");
    let envelope = origin.operations_after(&device, 0, 10).expect("ops")[0].clone();

    let store = Store::open(dir.path()).expect("open");
    store.apply_operation("test", &envelope, Limits::DEFAULT).expect("apply");
    let need = store.blobs_awaiting_bytes().expect("awaiting")[0].clone();
    store.write_blob_chunk(&need.blob_id, 0, &content, Limits::DEFAULT).expect("chunks");

    // Stand in for a death between the rename and the row update: the
    // bytes are in the vault, the row still says they are on their way.
    let target = dir.path().join(&receipt.rel_path);
    std::fs::create_dir_all(target.parent().expect("parent")).expect("dirs");
    std::fs::rename(
        dir.path().join(".gilune/local/staging").join(format!("sync-{}", need.blob_id)),
        &target,
    )
    .expect("promote by hand");
    drop(store);

    let store = Store::open(dir.path()).expect("reopen runs recovery");
    assert!(
        store.blobs_awaiting_bytes().expect("awaiting").is_empty(),
        "recovery finishes what the promotion started"
    );
    assert_eq!(store.status().expect("status").blobs_ready, 1);
    assert!(store.verify().expect("verify").is_empty());
}

#[test]
fn recovery_refuses_to_record_promoted_bytes_that_are_not_the_declared_content() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source = tempfile::tempdir().expect("tempdir");
    let origin = Store::open(source.path()).expect("open");
    let receipt = origin.capture_bytes("wav", &[1u8; 512]).expect("capture");
    let device = origin.device_id().expect("device");
    let envelope = origin.operations_after(&device, 0, 10).expect("ops")[0].clone();

    let store = Store::open(dir.path()).expect("open");
    store.apply_operation("test", &envelope, Limits::DEFAULT).expect("apply");
    drop(store);

    // Something that is not the content, where the content should go.
    let target = dir.path().join(&receipt.rel_path);
    std::fs::create_dir_all(target.parent().expect("parent")).expect("dirs");
    std::fs::write(&target, [9u8; 512]).expect("impostor");

    let store = Store::open(dir.path()).expect("reopen runs recovery");
    assert_eq!(
        store.blobs_awaiting_bytes().expect("awaiting").len(),
        1,
        "the right length is not the right bytes, and only the digest knows"
    );
    assert_eq!(store.status().expect("status").blobs_ready, 0);
}
