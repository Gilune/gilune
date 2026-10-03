//! The capture termination matrix (ADR 0002 acceptance gate, carried
//! into product code): abort the real process at every persistence
//! boundary, restart, and assert the recovery invariants. "Saved" is
//! never reported for an unrecoverable capture; a committed capture
//! always survives; a pre-commit abort leaves nothing half-visible and
//! nothing silently deleted.

use std::path::Path;
use std::process::{Command, Output};

fn cli(vault: &Path, crashpoint: Option<&str>, args: &[&str]) -> Output {
    let mut cmd = Command::new(support::cli());
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

fn assert_recovered(vault: &Path, expected_sources: i64) {
    let status = ok_json(vault, &["status"]);
    assert_eq!(status["sources"], expected_sources, "sources after recovery");
    assert_eq!(status["blobs_committed"], 0, "no blob left unpromoted");
    assert_eq!(status["blobs_ready"], expected_sources);
    assert_eq!(status["events"], expected_sources);
    assert_eq!(status["operations"], expected_sources);
    ok_json(vault, &["verify"]);
}

const PRE_COMMIT_POINTS: &[&str] = &["stage.after_write", "stage.after_fsync", "commit.inside"];
const POST_COMMIT_POINTS: &[&str] = &["commit.after", "promote.after"];

#[test]
fn saved_is_never_reported_for_a_capture_that_did_not_commit() {
    for point in PRE_COMMIT_POINTS.iter().chain(POST_COMMIT_POINTS) {
        let dir = tempfile::tempdir().expect("tempdir");
        let out = cli(dir.path(), Some(point), &["capture", "--text", "TODO: survive"]);
        assert!(!out.status.success(), "{point}: the process must have died");
        assert!(
            !String::from_utf8_lossy(&out.stdout).contains("\"saved\""),
            "{point}: a receipt must never precede durability"
        );
    }
}

#[test]
fn a_pre_commit_abort_leaves_no_half_visible_state() {
    for point in PRE_COMMIT_POINTS {
        let dir = tempfile::tempdir().expect("tempdir");
        cli(dir.path(), Some(point), &["capture", "--text", "TODO: vanish cleanly"]);
        assert_recovered(dir.path(), 0);
        let captures = dir.path().join("Captures");
        assert!(
            !captures.exists() || captures.read_dir().expect("read").next().is_none(),
            "{point}: nothing may appear in the vault"
        );
    }
}

#[test]
fn a_committed_capture_survives_every_post_commit_abort() {
    for point in POST_COMMIT_POINTS {
        let dir = tempfile::tempdir().expect("tempdir");
        cli(dir.path(), Some(point), &["capture", "--text", "TODO: survive the crash"]);
        assert_recovered(dir.path(), 1);
        let projected = dir
            .path()
            .join("Captures")
            .read_dir()
            .expect("Captures exists")
            .count();
        assert_eq!(projected, 1, "{point}: the original bytes live in the vault");
    }
}

#[test]
fn an_aborted_stage_is_preserved_in_lost_and_found() {
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), Some("stage.after_fsync"), &["capture", "--text", "orphan"]);
    ok_json(dir.path(), &["status"]); // recovery runs, but the stage is
                                      // young enough to be a live capture
    let store = gilune_kernel::store::Store::open(dir.path()).expect("open");
    store.force_stage_sweep().expect("sweep past the age gate");
    let lost = dir.path().join(".gilune/local/lost+found");
    assert_eq!(lost.read_dir().expect("lost+found").count(), 1);
}

#[test]
fn happy_path_reports_saved_and_verifies_end_to_end() {
    let dir = tempfile::tempdir().expect("tempdir");
    let receipt = ok_json(dir.path(), &["capture", "--text", "Remember the milk"]);
    assert_eq!(receipt["saved"], true);
    let stored = std::fs::read(dir.path().join(receipt["rel_path"].as_str().expect("rel_path")))
        .expect("blob in vault");
    assert_eq!(stored, b"Remember the milk");
    assert_recovered(dir.path(), 1);
}

#[test]
fn a_crash_mid_output_commit_yields_exactly_one_revision_on_retry() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = {
        let store = gilune_kernel::store::Store::open(dir.path()).expect("open");
        store
            .subscribe("transcribe", gilune_kernel::capture::SOURCE_READY_EVENT)
            .expect("subscribe");
        store.capture_bytes("wav", &[9u8; 128]).expect("wav capture").source_id
    };
    let crashed = cli(dir.path(), Some("output.commit.inside"), &["work"]);
    assert!(!crashed.status.success(), "work must die mid-commit");

    // The crashed run still holds the job's lease; retry past its expiry.
    let store = gilune_kernel::store::Store::open(dir.path()).expect("reopen");
    let past_lease = gilune_kernel::ids::now_ms() + 60_000;
    let retried = store
        .run_pending_jobs(&[&gilune_kernel::processor::FakeTranscriber], past_lease, 30_000)
        .expect("retry");
    assert_eq!(retried.outputs, 1, "the rolled-back revision commits exactly once");
    assert_eq!(
        store.revision_count(&source_id).expect("revisions"),
        2,
        "capture plus transcription"
    );

    let rerun = store
        .run_pending_jobs(
            &[&gilune_kernel::processor::FakeTranscriber],
            past_lease + 60_000,
            30_000,
        )
        .expect("idempotent rerun");
    assert_eq!(rerun.outputs, 0, "no duplicates after crash + retry");
    assert_eq!(
        store.revision_count(&source_id).expect("revisions"),
        2,
        "and none from the rerun"
    );
    assert!(store.verify().expect("verify").is_empty());
}

#[test]
fn a_crash_after_the_outputs_committed_completes_the_job_even_if_the_retry_would_fail() {
    // The window `output.after_commit` names: the outputs are durable and
    // the job is still leased. Recovery is the lease expiring and the run
    // record making the retry a skip — but only if the retry asks *before*
    // it runs the processor. Asking afterwards meant a provider that had
    // since started failing (a model that will not load, a device
    // offline) turned finished work into a failure, five of which
    // quarantine a job whose answer was already in the vault.
    struct Boom;
    impl gilune_kernel::processor::Processor for Boom {
        fn id(&self) -> &str {
            "transcribe"
        }
        fn process(
            &self,
            _ctx: &gilune_kernel::processor::ProcessorCtx<'_>,
            _job: &gilune_kernel::jobs::Job,
        ) -> Result<Vec<gilune_kernel::processor::ProposedOutput>, String> {
            Err("the model is not loaded any more".to_string())
        }
    }

    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = {
        let store = gilune_kernel::store::Store::open(dir.path()).expect("open");
        store
            .subscribe("transcribe", gilune_kernel::capture::SOURCE_READY_EVENT)
            .expect("subscribe");
        store.capture_bytes("wav", &[4u8; 96]).expect("wav capture").source_id
    };
    let crashed = cli(dir.path(), Some("output.after_commit"), &["work"]);
    assert!(!crashed.status.success(), "work must die after committing its outputs");

    let store = gilune_kernel::store::Store::open(dir.path()).expect("reopen");
    assert_eq!(
        store.revision_count(&source_id).expect("revisions"),
        2,
        "the outputs are durable: capture plus transcription"
    );
    let past_lease = gilune_kernel::ids::now_ms() + 60_000;
    let retried = store.run_pending_jobs(&[&Boom], past_lease, 30_000).expect("retry");
    assert_eq!(retried.skipped_duplicate, 1, "the run record answers before the model does");
    assert_eq!(retried.failed, 0, "work already done cannot fail");
    assert_eq!(retried.outputs, 0);
    assert_eq!(
        store.job_state_for(&source_id, "transcribe").expect("state").as_deref(),
        Some("done"),
        "and the job is finished rather than left to be retried into quarantine"
    );
    assert_eq!(store.revision_count(&source_id).expect("revisions"), 2, "and never twice");
    assert!(store.verify().expect("verify").is_empty());
}

#[test]
fn repeated_captures_accumulate_and_stay_verifiable() {
    let dir = tempfile::tempdir().expect("tempdir");
    for i in 0..5 {
        ok_json(dir.path(), &["capture", "--text", &format!("thought {i}")]);
    }
    assert_recovered(dir.path(), 5);
}

mod support;
