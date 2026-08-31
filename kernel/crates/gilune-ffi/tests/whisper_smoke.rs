//! End-to-end transcription smoke test: a real WAV through capture →
//! job → Whisper → the capture note's own text (ADR 0013). Needs a model
//! and a speech WAV supplied by the environment, so it skips (rather
//! than fails) when either is absent — CI without a model stays green,
//! and a full run on a developer machine is the honest check.

#![cfg(feature = "whisper")]

use gilune_ffi::Gilune;

#[test]
fn speech_wav_becomes_the_text_of_its_own_note() {
    let Ok(model) = std::env::var("GILUNE_TEST_MODEL") else {
        eprintln!("skipped: set GILUNE_TEST_MODEL to a ggml model path");
        return;
    };
    let Ok(wav_path) = std::env::var("GILUNE_TEST_WAV") else {
        eprintln!("skipped: set GILUNE_TEST_WAV to a 16k mono s16 wav");
        return;
    };
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    gilune.enable_whisper(model).expect("model loads");

    let wav = std::fs::read(&wav_path).expect("wav bytes");
    let writer = gilune.clone().begin_capture("wav".into()).expect("begin");
    writer.write_chunk(wav).expect("chunk");
    let receipt = writer.finish().expect("finish");

    let summary = gilune.run_processing().expect("processing");
    assert_eq!(summary.completed, 1, "the transcription job completed");
    assert_eq!(summary.outputs, 1, "one revision committed");

    let text = gilune
        .object_text(receipt.source_id.clone())
        .expect("query")
        .expect("the capture now carries its words");
    assert!(!text.trim().is_empty());

    gilune.project().expect("project");
    assert!(!dir.path().join("Transcripts").exists(), "one thought is one note");
    let note = std::fs::read_to_string(dir.path().join(receipt.rel_path.replace(".wav", ".md")))
        .expect("the capture note");
    assert!(note.ends_with(&text), "the body is the words, nothing else");
    println!("CAPTURE NOTE:\n{note}");
}
