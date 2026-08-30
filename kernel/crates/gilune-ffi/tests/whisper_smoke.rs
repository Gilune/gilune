//! End-to-end transcription smoke test: a real WAV through capture →
//! job → Whisper → transcript object → vault projection. Needs a model
//! and a speech WAV supplied by the environment, so it skips (rather
//! than fails) when either is absent — CI without a model stays green,
//! and a full run on a developer machine is the honest check.

#![cfg(feature = "whisper")]

use gilune_ffi::Gilune;

#[test]
fn speech_wav_becomes_a_transcript() {
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
    writer.finish().expect("finish");

    let summary = gilune.run_processing().expect("processing");
    assert_eq!(summary.completed, 1, "the transcription job completed");
    assert_eq!(summary.outputs, 1, "one transcript committed");

    gilune.project().expect("project");
    let transcripts = std::fs::read_dir(dir.path().join("Transcripts"))
        .expect("Transcripts dir")
        .count();
    assert_eq!(transcripts, 1);
    let sources = gilune.recent_sources(10).expect("query");
    let transcript_file = std::fs::read_to_string(
        dir.path().join(
            std::fs::read_dir(dir.path().join("Transcripts"))
                .unwrap()
                .next()
                .unwrap()
                .unwrap()
                .path(),
        ),
    )
    .expect("transcript text");
    println!("TRANSCRIPT FILE:\n{transcript_file}");
    println!("SOURCES: {}", sources.len());
}
