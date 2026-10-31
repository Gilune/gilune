//! Whisper asking a shell to name the speakers (ADR 0030): a real model
//! and a real speech WAV, with a stand-in labeller in place of the shell's
//! speaker model. Skips without `GILUNE_TEST_MODEL` and `GILUNE_TEST_WAV`,
//! as the smoke test does.

#![cfg(feature = "whisper")]

use gilune_ffi::{Gilune, GiluneError, SpeakerLabeller, TimedWord};
use std::sync::{Arc, Mutex};

/// What the transcriber handed over, and what to answer with.
struct Recorder {
    answer: Result<Option<String>, String>,
    asked: Mutex<Vec<(Vec<u8>, Vec<TimedWord>)>>,
}

impl Recorder {
    fn answering(answer: Result<Option<String>, String>) -> Arc<Self> {
        Arc::new(Self { answer, asked: Mutex::new(Vec::new()) })
    }

    fn times_asked(&self) -> usize {
        self.asked.lock().unwrap().len()
    }
}

impl SpeakerLabeller for Recorder {
    fn label(&self, wav: Vec<u8>, words: Vec<TimedWord>) -> Result<Option<String>, GiluneError> {
        self.asked.lock().unwrap().push((wav, words));
        self.answer.clone().map_err(|detail| GiluneError::Invalid { detail })
    }
}

fn inputs() -> Option<(String, Vec<u8>)> {
    let (Ok(model), Ok(wav)) = (std::env::var("GILUNE_TEST_MODEL"), std::env::var("GILUNE_TEST_WAV")) else {
        eprintln!("skipped: set GILUNE_TEST_MODEL and GILUNE_TEST_WAV");
        return None;
    };
    Some((model, std::fs::read(wav).expect("wav bytes")))
}

/// Capture `wav` in a fresh vault, with `labeller` set (or none), and
/// return the words the note ended up with.
fn transcribe(model: &str, wav: &[u8], labeller: Option<Arc<Recorder>>, attached: bool) -> String {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    gilune.enable_whisper(model.to_string()).expect("model accepted");
    gilune.set_speaker_labeller(labeller.map(|l| l as Arc<dyn SpeakerLabeller>));
    let writer = if attached {
        gilune.clone().begin_attached_capture("wav".into()).expect("begin")
    } else {
        gilune.clone().begin_capture("wav".into()).expect("begin")
    };
    writer.write_chunk(wav.to_vec()).expect("chunk");
    let receipt = writer.finish().expect("finish");
    let summary = gilune.run_processing().expect("processing");
    assert_eq!(summary.completed, 1, "the job completes whatever the labeller says");
    gilune.object_text(receipt.source_id).expect("query").expect("words")
}

#[test]
fn the_labeller_is_handed_the_recording_and_timed_words_and_its_answer_is_the_note() {
    let Some((model, wav)) = inputs() else { return };
    let plain = transcribe(&model, &wav, None, false);

    let labeller = Recorder::answering(Ok(Some("**Speaker 1:** one\n\n**Speaker 2:** two".into())));
    let text = transcribe(&model, &wav, Some(labeller.clone()), false);
    assert_eq!(text, "**Speaker 1:** one\n\n**Speaker 2:** two");

    let asked = labeller.asked.lock().unwrap();
    assert_eq!(asked.len(), 1);
    let (given, words) = &asked[0];
    assert_eq!(given, &wav, "the recording exactly as captured");
    assert!(words.len() > 2, "{words:?}");
    for word in words.iter() {
        println!("WORD {:>6}-{:>6} {}", word.start_ms, word.end_ms, word.text);
    }
    let joined = words.iter().map(|w| w.text.as_str()).collect::<Vec<_>>().join(" ");
    assert_eq!(joined, plain, "the words are the transcript, cut up and nothing more");
    for pair in words.windows(2) {
        assert!(pair[0].start_ms <= pair[1].start_ms, "in order: {pair:?}");
    }
    for word in words.iter() {
        assert!(word.start_ms <= word.end_ms, "{word:?}");
    }
    let seconds = (wav.len() - 44) as u32 / 32;
    assert!(words.last().unwrap().end_ms <= seconds + 1_000, "times are inside the recording");
}

#[test]
fn no_answer_or_a_failing_labeller_leaves_the_plain_transcript() {
    let Some((model, wav)) = inputs() else { return };
    let plain = transcribe(&model, &wav, None, false);

    let silent = Recorder::answering(Ok(None));
    assert_eq!(transcribe(&model, &wav, Some(silent.clone()), false), plain);
    assert_eq!(silent.times_asked(), 1);

    let failing = Recorder::answering(Err("no speaker model".into()));
    assert_eq!(transcribe(&model, &wav, Some(failing.clone()), false), plain, "an error never costs words");
    assert_eq!(failing.times_asked(), 1);

    let blank = Recorder::answering(Ok(Some("  ".into())));
    assert_eq!(transcribe(&model, &wav, Some(blank), false), plain, "an empty answer is no answer");
}

#[test]
fn dictation_into_a_note_is_never_offered_for_labels() {
    let Some((model, wav)) = inputs() else { return };
    let labeller = Recorder::answering(Ok(Some("**Speaker 1:** never".into())));
    let text = transcribe(&model, &wav, Some(labeller.clone()), true);
    assert_eq!(labeller.times_asked(), 0, "an attached capture is one person at their cursor");
    assert!(!text.contains("Speaker"), "{text}");
}

#[test]
fn a_labeller_cleared_after_whisper_was_enabled_is_not_asked() {
    let Some((model, wav)) = inputs() else { return };
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    let labeller = Recorder::answering(Ok(Some("**Speaker 1:** never".into())));
    // Set before whisper is enabled, cleared after: the slot is shared,
    // so both orders reach the transcriber.
    gilune.set_speaker_labeller(Some(labeller.clone()));
    gilune.enable_whisper(model).expect("model accepted");
    gilune.set_speaker_labeller(None);
    let writer = gilune.clone().begin_capture("wav".into()).expect("begin");
    writer.write_chunk(wav).expect("chunk");
    writer.finish().expect("finish");
    gilune.run_processing().expect("processing");
    assert_eq!(labeller.times_asked(), 0);
}
