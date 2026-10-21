//! Live dictation through the real engine (ADR 0027 S2): a speech WAV fed
//! to a live session a second at a time, as a microphone would, settles
//! words before the speaker stops and leaves nothing behind once stopped.
//! Skips without GILUNE_TEST_MODEL and GILUNE_TEST_WAV, like the other
//! whisper rows.

#![cfg(feature = "whisper")]

use gilune_ffi::Gilune;

#[test]
fn speech_settles_while_it_is_being_spoken() {
    let (Ok(model), Ok(wav_path)) = (std::env::var("GILUNE_TEST_MODEL"), std::env::var("GILUNE_TEST_WAV"))
    else {
        eprintln!("skipped: set GILUNE_TEST_MODEL and GILUNE_TEST_WAV");
        return;
    };
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    gilune.enable_whisper(model).expect("model accepted");
    let live = gilune.begin_live_transcription().expect("a live session");

    let wav = std::fs::read(&wav_path).expect("wav bytes");
    let pcm = &wav[44..];
    let second = 32_000;
    let mut settled = Vec::new();
    let mut settled_before_stop = 0;
    let mut first_words_after = None;
    for (i, chunk) in pcm.chunks(second).enumerate() {
        live.accept(chunk.to_vec());
        for line in live.update().expect("update") {
            if first_words_after.is_none() && !line.text.trim().is_empty() {
                first_words_after = Some(i + 1);
            }
            if line.complete {
                settled.push(line.text);
                settled_before_stop += 1;
            }
        }
    }
    let rest = live.finish().expect("finish");
    assert!(rest.iter().all(|l| l.complete), "nothing provisional is left after a stop");
    settled.extend(rest.into_iter().map(|l| l.text));
    let text = settled.join(" ");
    println!("FIRST WORDS after {first_words_after:?} s of audio; {settled_before_stop} settled before stop\n{text}");

    assert!(settled_before_stop > 0, "words settled while the speaker was still speaking");
    assert!(first_words_after.is_some_and(|s| s <= 3), "words show within a few seconds");
    let lower = text.to_lowercase();
    assert!(lower.contains("plasterer") && lower.contains("friday"), "the words are the speech: {text}");
    // Nothing doubled at a settle boundary: no word appears twice running.
    let words: Vec<&str> = lower.split_whitespace().collect();
    assert!(words.windows(2).all(|w| w[0] != w[1]), "no repeated words: {text}");
}

#[test]
fn a_live_session_needs_a_ready_transcriber() {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    assert!(gilune.begin_live_transcription().is_err(), "no model on this handle, no session");
}
