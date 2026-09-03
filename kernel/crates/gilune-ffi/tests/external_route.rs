//! The FFI processor route across the real boundary (ADR 0018 § 2): a
//! fake app-side engine driving all four calls exactly as a shell will —
//! `register_external_processor`, `claim_job`, `submit_output`,
//! `fail_job` — with the subject's blob crossing chunk by chunk.
//!
//! The kernel half is tested in `gilune_kernel::external`; what is proved
//! here is that the *boundary* carries it without weakening anything: the
//! caller proposes and the kernel commits, the bytes are digest-verified
//! before they cross, and nothing here can name a subject it did not
//! claim.

use gilune_ffi::{Gilune, OutputTarget, PluginContribution, PluginDeclaration, ProposedOutput};
use std::sync::Arc;

const PLUGIN: &str = "org.memory.moonshine";
const PROCESSOR: &str = "moonshine-transcribe";

fn declaration() -> PluginDeclaration {
    PluginDeclaration {
        plugin_id: PLUGIN.to_string(),
        name: "Realtime transcription (Moonshine)".to_string(),
        class: "provider".to_string(),
        profile: "bundled".to_string(),
        required: false,
        processor_id: Some(PROCESSOR.to_string()),
        subscribes: Some("org.memory.source-ready".to_string()),
        emits: vec!["org.memory.revision-added".to_string()],
        capabilities: vec!["blobs.read".to_string(), "objects.write".to_string()],
        contributions: Vec::<PluginContribution>::new(),
    }
}

/// A handle with the external provider declared and registered, and one
/// wav waiting — larger than a chunk, so the stream is exercised for real.
fn engine(dir: &tempfile::TempDir) -> (Arc<Gilune>, String, Vec<u8>) {
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    gilune.register_plugin(declaration()).expect("declare");
    gilune
        .register_external_processor(PLUGIN.to_string(), PROCESSOR.to_string())
        .expect("register the implementation this process carries");
    let spoken: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
    let writer = gilune.clone().begin_capture("wav".to_string()).expect("begin");
    for chunk in spoken.chunks(64 * 1024) {
        writer.write_chunk(chunk.to_vec()).expect("write");
    }
    let receipt = writer.finish().expect("finish");
    (gilune, receipt.source_id, spoken)
}

/// The state of *this* processor's job for a subject. Deliberately not a
/// global job count: on a `whisper` build the bundled transcription
/// provider is registered too, so one wav queues one job **per enabled
/// provider** — ADR 0018 § 3's named hole, visible here as soon as a
/// second provider exists. Scoping the question is the honest fix; the
/// hole itself is the shell's to close by keeping one provider enabled.
fn job_state(dir: &tempfile::TempDir, source_id: &str) -> Option<String> {
    gilune_kernel::store::Store::open(dir.path())
        .expect("open")
        .job_state_for(source_id, PROCESSOR)
        .expect("state")
}

fn revise(payload: &str, text: &str) -> Vec<ProposedOutput> {
    let mut document: serde_json::Value = serde_json::from_str(payload).expect("json");
    document["text"] = serde_json::Value::String(text.to_string());
    vec![ProposedOutput {
        type_id: "org.memory.source".to_string(),
        type_schema: 1,
        payload: document.to_string(),
        target: OutputTarget::ReviseSubject,
        expected_head: None,
    }]
}

#[test]
fn all_four_calls_carry_a_transcript_into_the_note() {
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, source_id, spoken) = engine(&dir);

    let claim = gilune
        .clone()
        .claim_job(PROCESSOR.to_string())
        .expect("claim")
        .expect("the capture queued a job");
    let job = claim.job();
    assert_eq!(job.processor_id, PROCESSOR);
    assert_eq!(job.subject_id, source_id);
    assert_eq!(job.attempts, 1);

    // The blob crosses in pieces and is never returned whole.
    let facts = job.blob.clone().expect("a wav has bytes");
    assert_eq!(facts.bytes, spoken.len() as u64);
    let mut received = Vec::new();
    let mut chunks = 0;
    while let Some(chunk) = claim.next_chunk().expect("chunk") {
        chunks += 1;
        received.extend_from_slice(&chunk);
    }
    assert!(chunks > 1, "200 KB must not arrive in one copy: {chunks} chunk(s)");
    assert_eq!(received, spoken, "what was captured is what the processor reads");
    assert_eq!(
        gilune_kernel::external::digest_of(&received),
        facts.sha256,
        "and the caller can prove it received all of it"
    );
    assert_eq!(claim.next_chunk().expect("drained"), None, "draining twice is safe");

    let receipt = gilune
        .submit_output(claim, revise(&job.subject_payload, "remember the milk"))
        .expect("submit");
    assert!(!receipt.skipped_duplicate);
    assert_eq!(receipt.outputs, 1);

    assert_eq!(
        gilune.object_text(source_id.clone()).expect("text").as_deref(),
        Some("remember the milk")
    );
    // The note is the capture's own, with the words as its body (ADR 0013).
    gilune.project().expect("project");
    assert_eq!(job_state(&dir, &source_id).as_deref(), Some("done"));
    assert!(
        gilune.clone().claim_job(PROCESSOR.to_string()).expect("claim").is_none(),
        "the job is finished"
    );
}

#[test]
fn a_retried_submit_after_a_lost_reply_lands_exactly_once() {
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, source_id, _) = engine(&dir);
    let claim = gilune.clone().claim_job(PROCESSOR.to_string()).expect("claim").expect("job");
    let payload = claim.job().subject_payload;

    let first = gilune
        .submit_output(claim.clone(), revise(&payload, "first"))
        .expect("submit");
    assert_eq!(first.outputs, 1);

    // The host never saw the reply and asked again — derived IDs, not
    // luck, are what make this harmless.
    let second = gilune
        .submit_output(claim, revise(&payload, "second"))
        .expect("retry");
    assert!(second.skipped_duplicate);
    assert_eq!(second.outputs, 0);
    assert_eq!(
        gilune.object_text(source_id).expect("text").as_deref(),
        Some("first"),
        "the retry changed nothing"
    );
}

/// The same output, but naming the head it was written over.
fn revise_over(payload: &str, text: &str, head: Option<String>) -> Vec<ProposedOutput> {
    let mut outputs = revise(payload, text);
    outputs[0].expected_head = head;
    outputs
}

/// Somebody types into the note while the engine is running — by the real
/// path, not a poke at the database: the projection is edited on disk and
/// reconciled, which is exactly what a person with the vault open does.
const TYPED: &str = "typed while the engine was thinking";

fn somebody_types(gilune: &Arc<Gilune>, dir: &tempfile::TempDir, source_id: &str) {
    gilune.project().expect("project");
    let rel = gilune
        .recent_sources(20)
        .expect("sources")
        .into_iter()
        .find(|s| s.object_id == source_id)
        .and_then(|s| s.projection_rel_path)
        .expect("the capture has a note");
    let path = dir.path().join(&rel);
    let text = std::fs::read_to_string(&path).expect("read");
    std::fs::write(&path, format!("{text}{TYPED}\n")).expect("edit");
    gilune.reconcile().expect("reconcile");
    assert!(
        gilune.object_text(source_id.to_string()).expect("text").unwrap_or_default().contains(TYPED),
        "the edit really did become the note's newest revision"
    );
}

#[test]
fn an_output_that_names_the_head_it_read_is_refused_once_the_note_has_moved_on() {
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, source_id, _) = engine(&dir);
    let claim = gilune.clone().claim_job(PROCESSOR.to_string()).expect("claim").expect("job");
    let payload = claim.job().subject_payload;
    let head = claim.subject_head().expect("a claimed subject has a head");

    somebody_types(&gilune, &dir, &source_id);

    let refused =
        gilune.submit_output(claim, revise_over(&payload, "what the engine heard", Some(head)));
    assert!(
        refused.is_err(),
        "a submission that named a head must not overwrite an answer that landed after it"
    );
    assert!(
        gilune.object_text(source_id).expect("text").unwrap_or_default().contains(TYPED),
        "the words a person wrote are still the words on the note"
    );
}

#[test]
fn an_output_that_names_no_head_still_lands_whatever_the_note_has_become() {
    // The second-order case, and the one that decides whether this is a
    // change of behaviour or an addition: compare-and-set is *asked for*.
    // Everything that shipped names no head and must keep working exactly
    // as it did — last writer wins, ADR 0013's original contract.
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, source_id, _) = engine(&dir);
    let claim = gilune.clone().claim_job(PROCESSOR.to_string()).expect("claim").expect("job");
    let payload = claim.job().subject_payload;

    somebody_types(&gilune, &dir, &source_id);

    let receipt = gilune
        .submit_output(claim, revise_over(&payload, "what the engine heard", None))
        .expect("a submission that asked for nothing is refused nothing");
    assert_eq!(receipt.outputs, 1);
    assert_eq!(
        gilune.object_text(source_id).expect("text").as_deref(),
        Some("what the engine heard")
    );
}

#[test]
fn a_head_that_has_not_moved_is_no_obstacle() {
    // The other second-order case: the check must not refuse the ordinary
    // path. Nothing happened while the engine ran, so naming the head is
    // free and the output lands exactly as it always did.
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, source_id, _) = engine(&dir);
    let claim = gilune.clone().claim_job(PROCESSOR.to_string()).expect("claim").expect("job");
    let payload = claim.job().subject_payload;
    let head = claim.subject_head().expect("head");

    let receipt = gilune
        .submit_output(claim, revise_over(&payload, "remember the milk", Some(head)))
        .expect("submit");
    assert_eq!(receipt.outputs, 1);
    assert_eq!(
        gilune.object_text(source_id).expect("text").as_deref(),
        Some("remember the milk")
    );
}

#[test]
fn a_failed_job_returns_to_the_queue() {
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, source_id, _) = engine(&dir);
    let claim = gilune.clone().claim_job(PROCESSOR.to_string()).expect("claim").expect("job");
    gilune.fail_job(claim, "the engine has no model yet".to_string()).expect("fail");
    assert_eq!(gilune.object_text(source_id.clone()).expect("text"), None);
    assert_eq!(
        job_state(&dir, &source_id).as_deref(),
        Some("pending"),
        "the work is kept, not destroyed"
    );

    // Backoff is real, so the immediate retry finds nothing; the kernel's
    // own tests walk the clock. What matters here is that it was not lost.
    let store = gilune_kernel::store::Store::open(dir.path()).expect("open");
    let recovered = store
        .claim_external_job(PROCESSOR, gilune_kernel::ids::now_ms() + 60_000, 30_000)
        .expect("claim")
        .expect("the job came back");
    assert_eq!(recovered.job.attempts, 2);
}

#[test]
fn claiming_without_registering_is_refused() {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    gilune.register_plugin(declaration()).expect("declare");
    // Declared, but this process never said it carries the implementation.
    assert!(
        gilune.clone().claim_job(PROCESSOR.to_string()).is_err(),
        "a declaration is not an implementation"
    );
}

#[test]
fn a_host_may_only_implement_what_the_plugin_declared() {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    gilune.register_plugin(declaration()).expect("declare");
    assert!(
        gilune
            .register_external_processor(PLUGIN.to_string(), "transcribe".to_string())
            .is_err(),
        "a host may implement what a plugin declared, never more"
    );
    assert!(
        gilune
            .register_external_processor("org.memory.nobody".to_string(), PROCESSOR.to_string())
            .is_err(),
        "and never for a plugin that does not exist"
    );
}

#[test]
fn registration_is_refused_while_the_plugin_is_disabled() {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    gilune.register_plugin(declaration()).expect("declare");
    gilune.set_plugin_enabled(PLUGIN.to_string(), false).expect("disable");
    assert!(
        gilune
            .register_external_processor(PLUGIN.to_string(), PROCESSOR.to_string())
            .is_err(),
        "the registry decides what may run, not the caller"
    );
}

#[test]
fn disabling_the_plugin_withdraws_the_registration_from_this_handle() {
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, _source_id, _) = engine(&dir);
    gilune.set_plugin_enabled(PLUGIN.to_string(), false).expect("disable");
    assert!(
        gilune.clone().claim_job(PROCESSOR.to_string()).is_err(),
        "a handle that already said yes cannot keep claiming for a disabled plugin"
    );
    // Parked, not destroyed.
    gilune.set_plugin_enabled(PLUGIN.to_string(), true).expect("enable");
    gilune
        .register_external_processor(PLUGIN.to_string(), PROCESSOR.to_string())
        .expect("re-register");
    assert!(
        gilune.clone().claim_job(PROCESSOR.to_string()).expect("claim").is_some(),
        "the parked job resumes where it stopped"
    );
}

#[test]
fn the_local_run_loop_leaves_the_external_processors_jobs_alone() {
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, _source_id, _) = engine(&dir);
    let summary = gilune.run_processing().expect("run");
    assert_eq!(summary.completed, 0);
    assert_eq!(summary.failed, 0);
    let claim = gilune
        .clone()
        .claim_job(PROCESSOR.to_string())
        .expect("claim")
        .expect("the job is untouched");
    assert_eq!(claim.job().attempts, 1, "no lease and no attempt was burnt on it");
}

#[test]
fn a_corrupt_blob_is_refused_rather_than_handed_over() {
    let dir = tempfile::tempdir().expect("tempdir");
    let (gilune, _source_id, _) = engine(&dir);
    let claim = gilune.clone().claim_job(PROCESSOR.to_string()).expect("claim").expect("job");
    let rel = claim.job().blob.expect("facts").rel_path;
    let path = dir.path().join(&rel);
    let mut bytes = std::fs::read(&path).expect("read");
    bytes[7] ^= 0xFF;
    std::fs::write(&path, &bytes).expect("corrupt it");

    assert!(
        claim.next_chunk().is_err(),
        "the kernel vouches for the bytes or hands out none"
    );
}
