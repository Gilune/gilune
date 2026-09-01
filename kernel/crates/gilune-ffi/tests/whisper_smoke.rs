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

/// A recording nobody spoke into. Whisper narrates the silence
/// (`[BLANK_AUDIO]`); under ADR 0013 that narration would become the
/// note's body, so it is stripped and the note stays empty — the
/// recording is still preserved and still linked (owner, 2026-09-01).
/// Needs only a model: the silence is generated here.
#[test]
fn a_silent_recording_leaves_the_note_empty() {
    let Ok(model) = std::env::var("GILUNE_TEST_MODEL") else {
        eprintln!("skipped: set GILUNE_TEST_MODEL to a ggml model path");
        return;
    };
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    gilune.enable_whisper(model).expect("model loads");

    let writer = gilune.clone().begin_capture("wav".into()).expect("begin");
    writer.write_chunk(silent_wav(2)).expect("chunk");
    let receipt = writer.finish().expect("finish");

    let summary = gilune.run_processing().expect("processing");
    assert_eq!(summary.completed, 1, "silence is a completed job, never a failed one");
    assert_eq!(summary.outputs, 1, "the empty transcript still commits a revision");

    let text = gilune.object_text(receipt.source_id.clone()).expect("query");
    assert_eq!(text.as_deref(), Some(""), "no words were spoken, so there are no words");

    gilune.project().expect("project");
    let note = std::fs::read_to_string(dir.path().join(receipt.rel_path.replace(".wav", ".md")))
        .expect("the capture note");
    println!("SILENT CAPTURE NOTE:\n{note}");
    assert!(note.ends_with("---\n"), "front matter and nothing else: {note}");
    assert!(note.contains("gilune-blob: "), "the recording itself is still preserved and linked");
}

/// `seconds` of digital silence as the app's canonical WAV: 16 kHz mono
/// s16, 44-byte header — the same shape both recorders emit.
fn silent_wav(seconds: u32) -> Vec<u8> {
    let samples = 16_000 * seconds;
    let data_len = samples * 2;
    let mut wav = Vec::with_capacity(44 + data_len as usize);
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&(36 + data_len).to_le_bytes());
    wav.extend_from_slice(b"WAVEfmt ");
    wav.extend_from_slice(&16u32.to_le_bytes()); // PCM header size
    wav.extend_from_slice(&1u16.to_le_bytes()); // PCM
    wav.extend_from_slice(&1u16.to_le_bytes()); // mono
    wav.extend_from_slice(&16_000u32.to_le_bytes());
    wav.extend_from_slice(&32_000u32.to_le_bytes()); // byte rate
    wav.extend_from_slice(&2u16.to_le_bytes()); // block align
    wav.extend_from_slice(&16u16.to_le_bytes()); // bits
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&data_len.to_le_bytes());
    wav.resize(44 + data_len as usize, 0);
    wav
}
