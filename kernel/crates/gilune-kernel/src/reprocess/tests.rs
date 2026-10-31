use super::*;
use crate::jobs::Job;
use crate::processor::{OutputTarget, Processor, ProcessorCtx, ProposedOutput};
use std::sync::atomic::{AtomicUsize, Ordering};

/// A processor whose every answer is new — "answer 1", "answer 2" — so a
/// second run is visible as a second answer, which the deterministic
/// `FakeTranscriber` cannot show. It reads the bytes, as a transcriber
/// must, so a recording the kernel cannot find is a failed job here.
#[derive(Default)]
struct Counting(AtomicUsize);

impl Processor for Counting {
    fn id(&self) -> &str {
        "transcribe"
    }

    fn process(&self, ctx: &ProcessorCtx<'_>, _job: &Job) -> std::result::Result<Vec<ProposedOutput>, String> {
        ctx.subject_blob().map_err(|e| e.to_string())?;
        let mut document: serde_json::Value =
            serde_json::from_str(&ctx.subject_payload().map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        let n = self.0.fetch_add(1, Ordering::SeqCst) + 1;
        document["text"] = serde_json::Value::String(format!("answer {n}"));
        Ok(vec![ProposedOutput {
            type_id: SOURCE_TYPE.to_string(),
            type_schema: 1,
            payload: document.to_string(),
            target: OutputTarget::ReviseSubject,
            expected_head: None,
        }])
    }
}

fn recorded() -> (tempfile::TempDir, Store, String, String) {
    let dir = tempfile::tempdir().expect("tempdir");
    let store = Store::open(dir.path()).expect("open");
    store
        .register_plugin(&crate::processor::fake_transcriber_descriptor())
        .expect("a transcriber");
    let receipt = store.capture_bytes("wav", b"RIFF a recording's bytes").expect("capture");
    store.project().expect("project");
    (dir, store, receipt.source_id, receipt.rel_path)
}

fn run(store: &Store, processor: &Counting) -> crate::processor::ProcessingSummary {
    store.run_pending_jobs(&[processor], 1_000, 30_000).expect("run")
}

fn text(store: &Store, object_id: &str) -> String {
    store.object_text(object_id).expect("text").unwrap_or_default()
}

fn note(store: &Store, object_id: &str) -> String {
    let rel: String = store
        .conn()
        .query_row("SELECT rel_path FROM projections WHERE object_id = ?1", [object_id], |r| r.get(0))
        .expect("projected");
    std::fs::read_to_string(store.vault().join(rel)).expect("read")
}

fn count(store: &Store, sql: &str) -> i64 {
    store.conn().query_row(sql, [], |r| r.get(0)).expect("count")
}

#[test]
fn asking_again_runs_the_processor_again_and_its_answer_becomes_the_text() {
    let (_dir, store, source, _) = recorded();
    let engine = Counting::default();
    assert_eq!(run(&store, &engine).outputs, 1);
    assert_eq!(text(&store, &source), "answer 1");
    // Without a request the work is done: at-least-once is not "again".
    assert_eq!(run(&store, &engine).outputs, 0);

    assert_eq!(store.process_again(&source).expect("again"), 1);
    assert_eq!(run(&store, &engine).outputs, 1);
    assert_eq!(text(&store, &source), "answer 2");
    assert_eq!(count(&store, "SELECT COUNT(*) FROM processing_runs"), 2);
    // A request is one answer, however often the pass runs after it.
    assert_eq!(run(&store, &engine).outputs, 0);
}

#[test]
fn the_request_says_it_is_one_so_a_processor_that_skips_answered_subjects_can_tell() {
    let (_dir, store, source, _) = recorded();
    run(&store, &Counting::default());
    store.process_again(&source).expect("again");
    let job = store.claim_next_job(1_000, 30_000).expect("claim").expect("a job");
    let payload: serde_json::Value = serde_json::from_str(&job.payload).expect("json");
    assert_eq!(payload[AGAIN_KEY], serde_json::Value::Bool(true));
    assert_eq!(job.subject_id, source);
}

#[test]
fn nothing_switched_on_queues_nothing_and_says_so_with_zero() {
    let (_dir, store, source, _) = recorded();
    run(&store, &Counting::default());
    store.set_plugin_enabled("org.memory.fake-transcription", false).expect("off");
    assert_eq!(store.process_again(&source).expect("again"), 0);
}

#[test]
fn only_a_live_source_with_its_bytes_can_be_asked() {
    let (_dir, store, source, rel) = recorded();
    assert!(store.process_again("no-such-object").is_err());
    std::fs::remove_file(store.vault().join(&rel)).expect("remove");
    store.conn().execute("UPDATE blobs SET state = 'committed'", []).expect("not held");
    assert!(store.process_again(&source).is_err(), "no preserved bytes, nothing to answer from");
}

#[test]
fn the_text_is_edited_once_it_is_not_what_a_processor_last_wrote() {
    let (_dir, store, _, rel) = recorded();
    let found = |store: &Store| store.source_for_file(&rel).expect("lookup").expect("a source");
    // No words yet: nothing to lose.
    assert!(!found(&store).text_edited);
    run(&store, &Counting::default());
    store.project().expect("project");
    assert!(!found(&store).text_edited, "the processor's own words are not an edit");

    let path = store.vault().join(found(&store).projection_rel_path.expect("a note"));
    let written = std::fs::read_to_string(&path).expect("read");
    std::fs::write(&path, written.replace("answer 1", "answer 1 and what I added")).expect("edit");
    store.reconcile().expect("reconcile");
    assert!(found(&store).text_edited);

    // Put the words back exactly — trailing whitespace is not an edit.
    let written = std::fs::read_to_string(&path).expect("read");
    std::fs::write(&path, written.replace("answer 1 and what I added", "answer 1\n")).expect("undo");
    store.reconcile().expect("reconcile");
    assert!(!found(&store).text_edited);
}

#[test]
fn a_file_that_is_no_source_is_not_found() {
    let (_dir, store, _, _) = recorded();
    std::fs::write(store.vault().join("song.wav"), b"other bytes").expect("write");
    assert_eq!(store.source_for_file("song.wav").expect("lookup"), None);
    assert_eq!(store.source_for_file("missing.wav").expect("lookup"), None);
}

// ------------------------------------------------- a recording that moved

fn rename_note_and_recording(store: &Store, source: &str, rel: &str) -> (String, String) {
    let projected: String = store
        .conn()
        .query_row("SELECT rel_path FROM projections WHERE object_id = ?1", [source], |r| r.get(0))
        .expect("projected");
    let vault = store.vault().to_path_buf();
    std::fs::rename(vault.join(&projected), vault.join("Captures/Standup.md")).expect("note");
    std::fs::rename(vault.join(rel), vault.join("Captures/Standup.wav")).expect("recording");
    ("Captures/Standup.md".into(), "Captures/Standup.wav".into())
}

#[test]
fn a_recording_renamed_with_its_note_is_followed_not_adopted_twice() {
    let (_dir, store, source, rel) = recorded();
    let (_, moved) = rename_note_and_recording(&store, &source, &rel);
    store.reconcile().expect("reconcile");
    store.project().expect("project");

    assert_eq!(
        count(&store, "SELECT COUNT(*) FROM objects WHERE type_id = 'org.memory.attachment'"),
        0,
        "the moved bytes are the recording, not a second file to carry",
    );
    let here: String = store
        .conn()
        .query_row("SELECT rel_path FROM blobs WHERE object_id = ?1", [&source], |r| r.get(0))
        .expect("blob");
    assert_eq!(here, moved);
    let note = note(&store, &source);
    assert!(note.contains("gilune-blob: Captures/Standup.wav"), "{note}");
    assert!(note.contains("![[Standup.wav]]"), "the link follows: {note}");

    // And the reason it matters: the processor finds the bytes.
    store.process_again(&source).expect("again");
    let summary = run(&store, &Counting::default());
    assert_eq!((summary.outputs, summary.failed), (2, 0), "the capture's own job and the request");
}

#[test]
fn a_copy_of_a_recording_is_a_copy() {
    let (_dir, store, source, rel) = recorded();
    let vault = store.vault().to_path_buf();
    std::fs::copy(vault.join(&rel), vault.join("Captures/Copy.wav")).expect("copy");
    store.reconcile().expect("reconcile");
    assert_eq!(
        count(&store, "SELECT COUNT(*) FROM objects WHERE type_id = 'org.memory.attachment'"),
        1,
        "the recording is still where the kernel recorded it",
    );
    let here: String = store
        .conn()
        .query_row("SELECT rel_path FROM blobs WHERE object_id = ?1", [&source], |r| r.get(0))
        .expect("blob");
    assert_eq!(here, rel);
}

#[test]
fn a_recording_moved_before_the_rule_existed_is_found_and_followed_when_asked_for() {
    // The state an earlier version left behind: the moved file adopted as
    // an attachment, the source still recorded at a path that is gone.
    // Built the only way it can be now — a copy adopted, then the
    // original removed.
    let (_dir, store, source, rel) = recorded();
    let vault = store.vault().to_path_buf();
    std::fs::copy(vault.join(&rel), vault.join("Captures/Standup.wav")).expect("copy");
    store.reconcile().expect("adopted as a copy");
    std::fs::remove_file(vault.join(&rel)).expect("original gone");

    let found = store.source_for_file("Captures/Standup.wav").expect("lookup").expect("found");
    assert_eq!(found.object_id, source);
    store.process_again(&source).expect("again");
    let summary = run(&store, &Counting::default());
    assert_eq!(summary.failed, 0, "the processor reads the followed path");
    assert!(summary.outputs >= 1);
}
