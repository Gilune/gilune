//! The external processor route's crash matrix (ADR 0018 § 6, R2's
//! gate), in the idiom of `crash_matrix.rs`: abort a real process at each
//! named boundary of the route, restart, and assert the vault converges
//! on **exactly one output or a cleanly re-claimable job** — never two
//! transcripts, never a job stranded forever.
//!
//! The route's guarantees are the shipped ones, so the points are the
//! shipped ones too: the lease makes an abandoned claim recoverable, and
//! the `processing_runs` row makes a redelivered submit a skip. Nothing
//! here is a second mechanism, which is why nothing here needed a
//! migration.
//!
//! Time is supplied, never waited for: `external --at <ms>` steps past a
//! dead claimant's lease exactly as the kernel's own tests pass `now_ms`.

use gilune_kernel::external::{
    fake_external_descriptor, FAKE_EXTERNAL_PLUGIN, FAKE_EXTERNAL_PROCESSOR,
};
use gilune_kernel::processor::{OutputTarget, ProposedOutput};
use gilune_kernel::store::Store;
use std::path::Path;
use std::process::{Command, Output};

/// Comfortably past any lease this harness takes.
const AFTER_THE_LEASE: &str = "9999999999999";

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

/// Run the route in a fresh process, with the clock past every lease.
fn route(vault: &Path) -> serde_json::Value {
    ok_json(vault, &["external", "--at", AFTER_THE_LEASE])
}

/// A vault with the external provider declared and one wav waiting for
/// it. The blob is deliberately larger than one chunk, so every run of
/// the route streams for real.
fn seeded(vault: &Path) -> String {
    let store = Store::open(vault).expect("open");
    store.register_plugin(&fake_external_descriptor()).expect("register");
    store.capture_bytes("wav", &vec![9u8; 200_000]).expect("capture").source_id
}

/// The invariant every point in this matrix converges on, for one
/// capture: its words were written once, by a nameable engine.
fn assert_one_transcript_for(vault: &Path, source_id: &str) {
    let store = Store::open(vault).expect("reopen");
    assert_eq!(
        store.revision_count(source_id).expect("revisions"),
        2,
        "the capture revision plus exactly one transcription"
    );
    assert_eq!(
        store.head_origin(source_id).expect("origin").as_deref(),
        Some(format!("processor:{FAKE_EXTERNAL_PROCESSOR}").as_str()),
        "the vault can name the engine that wrote these words"
    );
    drop(store);
    ok_json(vault, &["verify"]);
}

/// As above, and additionally that the route ran exactly once in total —
/// the sharper claim, available in the tests that make only one capture.
fn assert_exactly_one_transcript(vault: &Path, source_id: &str) {
    assert_one_transcript_for(vault, source_id);
    let store = Store::open(vault).expect("reopen");
    assert_eq!(
        store.processing_run_count(FAKE_EXTERNAL_PROCESSOR).expect("runs"),
        1,
        "one processing run, however many deliveries there were"
    );
}

/// Point 1 — killed between claim and submit. The claimant dies holding a
/// lease and having written nothing; redelivery must produce exactly one
/// output, not zero and not two.
#[test]
fn a_death_between_claim_and_submit_yields_exactly_one_output_on_redelivery() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = seeded(dir.path());

    let crashed = cli(dir.path(), Some("external.after_claim"), &["external"]);
    assert!(!crashed.status.success(), "the claimant must have died");
    {
        let store = Store::open(dir.path()).expect("reopen");
        assert_eq!(
            store.revision_count(&source_id).expect("revisions"),
            1,
            "a claim on its own writes nothing"
        );
        assert_eq!(store.status().expect("status").jobs_pending, 1, "the work is kept");
    }

    let retried = route(dir.path());
    assert_eq!(retried["claimed"], true);
    assert_eq!(retried["outputs"], 1);
    assert_exactly_one_transcript(dir.path(), &source_id);

    // And a further pass adds nothing: there is no job left to claim.
    assert_eq!(route(dir.path())["claimed"], false);
    assert_exactly_one_transcript(dir.path(), &source_id);
}

/// Point 2 — killed *inside* the submit transaction. The same
/// `output.commit.inside` crashpoint the Rust route is aborted at, now
/// reached from across the boundary: one transaction, so it rolls back
/// whole and the retry commits exactly once.
#[test]
fn a_death_inside_the_submit_transaction_rolls_back_whole_and_retries_once() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = seeded(dir.path());

    let crashed = cli(dir.path(), Some("output.commit.inside"), &["external"]);
    assert!(!crashed.status.success(), "the submit must die mid-commit");
    {
        let store = Store::open(dir.path()).expect("reopen");
        assert_eq!(
            store.revision_count(&source_id).expect("revisions"),
            1,
            "a rolled-back transaction leaves no half-written revision"
        );
        assert_eq!(
            store.processing_run_count(FAKE_EXTERNAL_PROCESSOR).expect("runs"),
            0,
            "and no run record either — the row is inside the same transaction"
        );
    }

    assert_eq!(route(dir.path())["outputs"], 1);
    assert_exactly_one_transcript(dir.path(), &source_id);
}

/// Points 2b and 4 together, because in the shipped design they are one
/// physical scenario: killed after the outputs commit but before the job
/// is completed, the next delivery **is** a duplicate submit for a
/// completed run. The words are already durable, the job still says
/// leased, and the run record is what makes the retry a skip rather than
/// a second transcript.
#[test]
fn a_death_after_the_commit_makes_the_next_delivery_a_skipped_duplicate() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = seeded(dir.path());

    let crashed = cli(dir.path(), Some("output.after_commit"), &["external"]);
    assert!(!crashed.status.success(), "the process must die before completing the job");
    {
        let store = Store::open(dir.path()).expect("reopen");
        assert_eq!(
            store.revision_count(&source_id).expect("revisions"),
            2,
            "the outputs are durable already"
        );
        assert_eq!(
            store.job_state_for(&source_id, FAKE_EXTERNAL_PROCESSOR).expect("state").as_deref(),
            Some("leased"),
            "but the job was never completed"
        );
    }

    let redelivered = route(dir.path());
    assert_eq!(redelivered["claimed"], true, "the job really was delivered again");
    assert_eq!(
        redelivered["skipped_duplicate"], true,
        "a duplicate submit for a completed run is skipped, not rewritten"
    );
    assert_eq!(redelivered["outputs"], 0);
    assert_exactly_one_transcript(dir.path(), &source_id);
    assert_eq!(
        route(dir.path())["claimed"],
        false,
        "and the job is now finished for good"
    );
}

/// Point 3 — lease expiry with a claimant still alive. Two live
/// claimants in two processes hold the same job; exactly one output
/// results, and the late submit is skipped rather than refused, because
/// at-least-once makes a late submit legal and idempotency makes it
/// harmless.
#[test]
fn two_live_claimants_across_two_processes_produce_one_output() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = seeded(dir.path());

    // Claimant A: this process, holding a lease that expires immediately.
    let store = Store::open(dir.path()).expect("open");
    let slow = store
        .claim_external_job(FAKE_EXTERNAL_PROCESSOR, 1_000, 1)
        .expect("claim")
        .expect("job");

    // Claimant B: a second process, which finds the lease expired, claims
    // the same job and finishes first.
    let quick = route(dir.path());
    assert_eq!(quick["claimed"], true);
    assert_eq!(quick["outputs"], 1);

    // Claimant A finally finishes. It is still alive and its claim is
    // still in hand.
    let late = store
        .submit_external_outputs(
            FAKE_EXTERNAL_PROCESSOR,
            &slow.job,
            &[ProposedOutput {
                type_id: gilune_kernel::capture::SOURCE_TYPE.to_string(),
                type_schema: 1,
                payload: serde_json::json!({ "media": "wav", "text": "the slow one" })
                    .to_string(),
                target: OutputTarget::ReviseSubject,
            }],
        )
        .expect("a late submit is legal");
    assert!(late.skipped_duplicate, "one output, whoever got there second");
    assert_eq!(late.outputs, 0);
    drop(store);
    assert_exactly_one_transcript(dir.path(), &source_id);
}

/// Point 5 — a submit for a job whose plugin was disabled mid-flight.
/// The claim is held in this process while another process switches the
/// provider off; the submit is refused, nothing is written, and the job
/// is parked rather than destroyed.
#[test]
fn a_submit_is_refused_when_the_plugin_was_disabled_mid_flight() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = seeded(dir.path());

    let store = Store::open(dir.path()).expect("open");
    let claim = store
        .claim_external_job(FAKE_EXTERNAL_PROCESSOR, 1_000, 60_000)
        .expect("claim")
        .expect("job");

    // Another process turns the provider off while the work is in flight.
    ok_json(dir.path(), &["plugins", "disable", FAKE_EXTERNAL_PLUGIN]);

    let refused = store.submit_external_outputs(
        FAKE_EXTERNAL_PROCESSOR,
        &claim.job,
        &[ProposedOutput {
            type_id: gilune_kernel::capture::SOURCE_TYPE.to_string(),
            type_schema: 1,
            payload: serde_json::json!({ "media": "wav", "text": "too late" }).to_string(),
            target: OutputTarget::ReviseSubject,
        }],
    );
    assert!(
        matches!(refused, Err(gilune_kernel::KernelError::ProcessorNotClaimable { .. })),
        "a disabled plugin may not write, whatever it holds a claim on"
    );
    assert_eq!(store.revision_count(&source_id).expect("revisions"), 1);
    drop(store);

    // Parked, not destroyed: switching it back on resumes the same job.
    assert_eq!(
        route(dir.path())["claimed"],
        false,
        "nothing claims work for a disabled provider"
    );
    ok_json(dir.path(), &["plugins", "enable", FAKE_EXTERNAL_PLUGIN]);
    assert_eq!(route(dir.path())["outputs"], 1);
    assert_exactly_one_transcript(dir.path(), &source_id);
}

/// The abandoned claim, stated on its own: a host that claims and dies
/// must not strand the job forever. Recovery is the lease expiring —
/// there is no reclaim column, because at-least-once already promised it.
#[test]
fn a_claimant_that_dies_strands_the_job_only_until_its_lease_expires() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = seeded(dir.path());

    cli(dir.path(), Some("external.after_claim"), &["external"]);

    {
        let store = Store::open(dir.path()).expect("reopen");
        assert_eq!(
            store.job_state_for(&source_id, FAKE_EXTERNAL_PROCESSOR).expect("state").as_deref(),
            Some("leased")
        );
        // While the lease stands the job is invisible — that is the lease
        // working, not a leak.
        assert_eq!(
            store.claim_external_job(FAKE_EXTERNAL_PROCESSOR, 1_000, 30_000).expect("claim"),
            None
        );
        // …and once it expires it comes back on its own, with the dead
        // claimant's attempt counted against it.
        let recovered = store
            .claim_external_job(
                FAKE_EXTERNAL_PROCESSOR,
                gilune_kernel::ids::now_ms() + 90_000,
                30_000,
            )
            .expect("claim")
            .expect("the job is not stranded");
        assert_eq!(recovered.job.attempts, 2);
    }

    assert_eq!(route(dir.path())["outputs"], 1);
    assert_exactly_one_transcript(dir.path(), &source_id);
}

/// Capture is never at risk, whatever the route does. Rule 1 does not
/// bend for a processor written in another language.
#[test]
fn nothing_the_route_does_can_block_a_capture() {
    let dir = tempfile::tempdir().expect("tempdir");
    let source_id = seeded(dir.path());
    for point in ["external.after_claim", "output.commit.inside", "output.after_commit"] {
        cli(dir.path(), Some(point), &["external"]);
        let receipt = ok_json(dir.path(), &["capture", "--text", "still capturing"]);
        assert_eq!(receipt["saved"], true, "{point}: capture must never be blocked");
    }
    // Drain everything the loop queued — each capture is its own event,
    // its own job and its own run, so the wav's convergence is the claim
    // to make here, not a global count.
    while route(dir.path())["claimed"] == true {}
    assert_one_transcript_for(dir.path(), &source_id);
}
