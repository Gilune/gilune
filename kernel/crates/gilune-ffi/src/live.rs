//! Live dictation (ADR 0027 Â§ 1, stage S2): words while someone is still
//! speaking, from an engine that only knows how to transcribe a buffer.
//!
//! Whisper is not a streaming model. This makes it behave like one the way
//! `whisper_streaming` does: the shell keeps feeding audio, every update
//! re-decodes the audio still in the window, and a word **settles** once two
//! consecutive decodes agree on it (LocalAgreement-2). Settled words never
//! change again â that is the moment the shell may put them in the buffer.
//! Everything after them is provisional and is shown grey, outside the file.
//! Audio behind a segment whose words have all settled is dropped, so each
//! decode stays short however long the dictation runs.
//!
//! The policy here is pure over a [`Decoder`], so it is tested with scripted
//! decodes and no model. The FFI object is the same in every build: without
//! the `whisper` feature there is simply no engine to start one with.

// Without an engine nothing constructs a session, so the policy is unused
// in that build — by design, not by accident.
#![cfg_attr(not(feature = "whisper"), allow(dead_code))]

use std::sync::{Arc, Mutex};

use crate::{FfiResult, GiluneError};

/// Samples per second of the PCM every Gilune recording carries.
pub(crate) const SAMPLE_RATE: usize = 16_000;

/// The longest window kept before everything but its last segment is
/// settled by force. Whisper's own window is 30 s; a third of that margin
/// keeps decodes well inside it.
const MAX_WINDOW_SECONDS: f32 = 20.0;

/// Below this peak amplitude there is nothing to decode. Whisper narrates
/// silence and, worse, invents short phrases for room tone; not asking it
/// is cheaper than cleaning up after it.
const SILENCE_PEAK: f32 = 0.01;

/// One decoded span of the window; its end is relative to the window's start.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Segment {
    pub end_s: f32,
    pub text: String,
}

/// Anything that can turn a buffer of 16 kHz mono samples into segments.
pub(crate) trait Decoder: Send {
    fn decode(&self, samples: &[f32]) -> Result<Vec<Segment>, String>;
}

/// One line of a live transcript, in the shape the shells' caption fold
/// already reads (`CaptionEvent`): a line that arrives `complete` never
/// changes again, and the line in flight may be rewritten wholesale.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LiveLine {
    pub id: u64,
    pub text: String,
    pub complete: bool,
}

/// The LocalAgreement policy over one recording.
pub(crate) struct Session<D: Decoder> {
    decoder: D,
    /// The audio still in the window.
    audio: Vec<f32>,
    /// Samples accepted since the last decode; nothing new, nothing to do.
    fresh: usize,
    /// Normalised words of the window already settled, oldest first.
    settled: Vec<String>,
    /// The provisional words the last decode proposed, as written.
    proposed: Vec<String>,
    /// The id of the line in flight.
    line: u64,
}

fn words(segments: &[Segment]) -> Vec<(String, usize)> {
    segments
        .iter()
        .enumerate()
        .flat_map(|(i, s)| s.text.split_whitespace().map(move |w| (w.to_string(), i)))
        .collect()
}

/// What two decodes are compared on: case and punctuation are the decoder
/// changing its mind about presentation, not about what was said.
fn normal(word: &str) -> String {
    word.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect()
}

impl<D: Decoder> Session<D> {
    pub(crate) fn new(decoder: D) -> Self {
        Self {
            decoder,
            audio: Vec::new(),
            fresh: 0,
            settled: Vec::new(),
            proposed: Vec::new(),
            line: 0,
        }
    }

    pub(crate) fn accept(&mut self, samples: &[f32]) {
        self.audio.extend_from_slice(samples);
        self.fresh += samples.len();
    }

    /// Where this decode's words stop repeating what has already settled.
    ///
    /// A re-decode may split or join a word it already gave ("ring main" /
    /// "ringmain"), so counting settled words is not enough. The settled
    /// words are compared as one run of letters: the tail starts after the
    /// decoded words that spell exactly that run, or, when the decoder has
    /// changed its mind about a settled word, after the first ones that
    /// cover as many letters.
    fn tail_start(&self, decoded: &[(String, usize)]) -> usize {
        let settled: String = self.settled.concat();
        if settled.is_empty() {
            return 0;
        }
        let mut spelled = String::new();
        let mut covering = None;
        for (i, (word, _)) in decoded.iter().enumerate() {
            spelled.push_str(&normal(word));
            if spelled == settled {
                return i + 1;
            }
            if covering.is_none() && spelled.len() >= settled.len() {
                covering = Some(i + 1);
            }
        }
        covering.unwrap_or(decoded.len())
    }

    fn silent(&self) -> bool {
        self.audio.iter().all(|s| s.abs() < SILENCE_PEAK)
    }

    /// Decode the window and say what changed. Returns at most two lines:
    /// newly settled words as a complete line, then the provisional rest.
    pub(crate) fn update(&mut self) -> Result<Vec<LiveLine>, String> {
        if self.fresh == 0 {
            return Ok(Vec::new());
        }
        self.fresh = 0;
        if self.silent() {
            return Ok(self.lines(Vec::new(), Vec::new()));
        }
        let segments = self.decoder.decode(&self.audio)?;
        let decoded = words(&segments);
        let start = self.tail_start(&decoded);
        let tail = &decoded[start..];
        let agree = self
            .proposed
            .iter()
            .zip(tail.iter())
            .take_while(|(a, (b, _))| normal(a) == normal(b))
            .count();
        let mut newly: Vec<String> = tail[..agree].iter().map(|(w, _)| w.clone()).collect();
        let mut provisional: Vec<String> = tail[agree..].iter().map(|(w, _)| w.clone()).collect();
        self.settled.extend(newly.iter().map(|w| normal(w)));
        let mut settled_at = start + agree;

        // Drop audio behind every segment whose words have all settled,
        // keeping the last segment: it is the one still being spoken.
        let mut cut = None;
        let mut count = 0;
        for (i, segment) in segments.iter().enumerate().take(segments.len().saturating_sub(1)) {
            let in_segment = decoded.iter().filter(|(_, s)| *s == i).count();
            if count + in_segment > settled_at {
                break;
            }
            count += in_segment;
            cut = Some((segment.end_s, count));
        }
        // A window that has grown too long settles everything but its last
        // segment, agreed or not, rather than slowing every decode further.
        let too_long = self.audio.len() as f32 / SAMPLE_RATE as f32 > MAX_WINDOW_SECONDS;
        if too_long && segments.len() > 1 && cut.is_none_or(|(_, n)| n < decoded.len()) {
            let last = segments.len() - 1;
            let keep_from = decoded.iter().position(|(_, s)| *s == last).unwrap_or(decoded.len());
            if keep_from > settled_at {
                let forced: Vec<String> =
                    decoded[settled_at..keep_from].iter().map(|(w, _)| w.clone()).collect();
                self.settled.extend(forced.iter().map(|w| normal(w)));
                newly.extend(forced);
                provisional = decoded[keep_from..].iter().map(|(w, _)| w.clone()).collect();
                settled_at = keep_from;
            }
            cut = Some((segments[last - 1].end_s, keep_from));
        }
        if let Some((end_s, dropped)) = cut {
            let samples = ((end_s.max(0.0) * SAMPLE_RATE as f32) as usize).min(self.audio.len());
            self.audio.drain(..samples);
            // The settled words still in the window are the ones after the
            // dropped segments, which is how this decode counted them.
            let keep = settled_at.saturating_sub(dropped).min(self.settled.len());
            let from = self.settled.len() - keep;
            self.settled.drain(..from);
        }
        Ok(self.lines(newly, provisional))
    }

    fn lines(&mut self, newly: Vec<String>, provisional: Vec<String>) -> Vec<LiveLine> {
        let mut out = Vec::new();
        if !newly.is_empty() {
            out.push(LiveLine { id: self.line, text: newly.join(" "), complete: true });
            self.line += 1;
        }
        out.push(LiveLine { id: self.line, text: provisional.join(" "), complete: false });
        self.proposed = provisional;
        out
    }

    /// The microphone stopped: one last decode, and everything not yet
    /// settled settles now â a stopped speaker has finished the sentence.
    pub(crate) fn finish(&mut self) -> Result<Vec<LiveLine>, String> {
        let rest: Vec<String> = if self.audio.is_empty() || self.silent() {
            self.proposed.clone()
        } else {
            let segments = self.decoder.decode(&self.audio)?;
            let decoded = words(&segments);
            let start = self.tail_start(&decoded);
            let tail: Vec<String> = decoded[start..].iter().map(|(w, _)| w.clone()).collect();
            // A last decode that hears less than the one before it does not
            // take back words already on screen.
            if tail.is_empty() { self.proposed.clone() } else { tail }
        };
        self.audio.clear();
        self.fresh = 0;
        self.proposed.clear();
        let mut out = Vec::new();
        if !rest.is_empty() {
            out.push(LiveLine { id: self.line, text: rest.join(" "), complete: true });
            self.line += 1;
        }
        Ok(out)
    }
}

/// What the FFI object holds: a session over whichever engine started it.
pub(crate) trait LiveEngine: Send {
    fn accept(&mut self, samples: &[f32]);
    fn update(&mut self) -> Result<Vec<LiveLine>, String>;
    fn finish(&mut self) -> Result<Vec<LiveLine>, String>;
}

impl<D: Decoder> LiveEngine for Session<D> {
    fn accept(&mut self, samples: &[f32]) {
        Session::accept(self, samples)
    }
    fn update(&mut self) -> Result<Vec<LiveLine>, String> {
        Session::update(self)
    }
    fn finish(&mut self) -> Result<Vec<LiveLine>, String> {
        Session::finish(self)
    }
}

/// A live dictation session (ADR 0027). The shell feeds it the same PCM
/// the recording is made of, asks for an update about once a second from a
/// thread of its own, and calls `finish` when the microphone stops. It is
/// display and input only: the recording commits through the ordinary
/// capture path whatever happens here.
#[derive(uniffi::Object)]
pub struct LiveTranscription {
    engine: Mutex<Box<dyn LiveEngine>>,
}

impl LiveTranscription {
    pub(crate) fn new(engine: Box<dyn LiveEngine>) -> Arc<Self> {
        Arc::new(Self { engine: Mutex::new(engine) })
    }

    fn engine(&self) -> std::sync::MutexGuard<'_, Box<dyn LiveEngine>> {
        self.engine.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
impl LiveTranscription {
    /// More of the recording: 16 kHz mono signed 16-bit little-endian PCM,
    /// any length. Cheap; nothing is decoded here.
    pub fn accept(&self, pcm: Vec<u8>) {
        let samples: Vec<f32> = pcm
            .as_chunks::<2>()
            .0
            .iter()
            .map(|b| i16::from_le_bytes(*b) as f32 / 32768.0)
            .collect();
        self.engine().accept(&samples);
    }

    /// Decode what has arrived. Takes as long as the engine takes, so a
    /// shell calls it off its UI thread. Returns the lines that changed:
    /// a `complete` one is settled for good, the other is the provisional
    /// rest and replaces whatever was provisional before.
    pub fn update(&self) -> FfiResult<Vec<LiveLine>> {
        self.engine().update().map_err(|detail| GiluneError::Invalid { detail })
    }

    /// The microphone stopped: the remaining words, all complete.
    pub fn finish(&self) -> FfiResult<Vec<LiveLine>> {
        self.engine().finish().map_err(|detail| GiluneError::Invalid { detail })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::sync::Mutex as StdMutex;

    /// Decodes are scripted: each call returns the next answer, whatever
    /// the audio, and records how much audio it was asked about.
    struct Scripted {
        answers: StdMutex<VecDeque<Vec<Segment>>>,
        asked: Arc<StdMutex<Vec<usize>>>,
    }

    impl Decoder for Scripted {
        fn decode(&self, samples: &[f32]) -> Result<Vec<Segment>, String> {
            self.asked.lock().unwrap().push(samples.len());
            Ok(self.answers.lock().unwrap().pop_front().unwrap_or_default())
        }
    }

    fn seg(_start_s: f32, end_s: f32, text: &str) -> Segment {
        Segment { end_s, text: text.to_string() }
    }

    fn session(answers: Vec<Vec<Segment>>) -> (Session<Scripted>, Arc<StdMutex<Vec<usize>>>) {
        let asked = Arc::new(StdMutex::new(Vec::new()));
        let decoder = Scripted { answers: StdMutex::new(answers.into()), asked: Arc::clone(&asked) };
        (Session::new(decoder), asked)
    }

    fn speech(seconds: f32) -> Vec<f32> {
        vec![0.2; (seconds * SAMPLE_RATE as f32) as usize]
    }

    fn settled(lines: &[LiveLine]) -> Vec<String> {
        lines.iter().filter(|l| l.complete).map(|l| l.text.clone()).collect()
    }

    fn provisional(lines: &[LiveLine]) -> String {
        lines.iter().find(|l| !l.complete).map(|l| l.text.clone()).unwrap_or_default()
    }

    #[test]
    fn nothing_settles_until_two_decodes_agree() {
        let (mut s, _) = session(vec![
            vec![seg(0.0, 1.0, "Met the")],
            vec![seg(0.0, 2.0, "Met the savela on")],
            vec![seg(0.0, 3.0, "Met the surveyor on site")],
        ]);
        s.accept(&speech(1.0));
        let first = s.update().unwrap();
        assert!(settled(&first).is_empty(), "one decode proves nothing");
        assert_eq!(provisional(&first), "Met the");

        s.accept(&speech(1.0));
        let second = s.update().unwrap();
        assert_eq!(settled(&second), ["Met the"]);
        assert_eq!(provisional(&second), "savela on");

        s.accept(&speech(1.0));
        let third = s.update().unwrap();
        assert!(settled(&third).is_empty(), "a revised word is not agreement");
        assert_eq!(provisional(&third), "surveyor on site", "provisional words are revised in place");
    }

    #[test]
    fn settled_words_are_never_offered_twice() {
        let (mut s, _) = session(vec![
            vec![seg(0.0, 1.0, "one two")],
            vec![seg(0.0, 2.0, "one two three")],
            vec![seg(0.0, 3.0, "one two three four")],
            vec![seg(0.0, 4.0, "one two three four five")],
        ]);
        let mut all = Vec::new();
        for _ in 0..4 {
            s.accept(&speech(1.0));
            all.extend(settled(&s.update().unwrap()));
        }
        all.extend(settled(&s.finish().unwrap()));
        assert_eq!(all.join(" "), "one two three four five");
    }

    #[test]
    fn presentation_changes_do_not_block_agreement() {
        let (mut s, _) = session(vec![
            vec![seg(0.0, 1.0, "the quote came")],
            vec![seg(0.0, 2.0, "The quote, came in")],
        ]);
        s.accept(&speech(1.0));
        s.update().unwrap();
        s.accept(&speech(1.0));
        let lines = s.update().unwrap();
        // The later decode's spelling is the one written down.
        assert_eq!(settled(&lines), ["The quote, came"]);
    }

    #[test]
    fn a_re_decode_that_joins_a_settled_word_neither_repeats_nor_drops_one() {
        // The step past the obvious: "ring main" settled as two words, then
        // the decoder writes it as one. Counting settled words would start
        // the tail one word late and lose "in".
        let (mut s, _) = session(vec![
            vec![seg(0.0, 1.0, "the ring main")],
            vec![seg(0.0, 2.0, "the ring main in")],
            vec![seg(0.0, 3.0, "the ringmain in the")],
            vec![seg(0.0, 4.0, "the ringmain in the kitchen")],
        ]);
        let mut all = Vec::new();
        for _ in 0..4 {
            s.accept(&speech(1.0));
            all.extend(settled(&s.update().unwrap()));
        }
        all.extend(settled(&s.finish().unwrap()));
        // The earlier spelling of the settled words stands; nothing repeats
        // and nothing is lost.
        assert_eq!(all.join(" "), "the ring main in the kitchen");
    }

    #[test]
    fn audio_behind_a_settled_segment_is_dropped() {
        let (mut s, asked) = session(vec![
            vec![seg(0.0, 2.0, "First sentence."), seg(2.0, 3.0, "Second")],
            vec![seg(0.0, 2.0, "First sentence."), seg(2.0, 4.0, "Second one")],
            vec![seg(0.0, 2.0, "one more")],
        ]);
        s.accept(&speech(3.0));
        s.update().unwrap();
        s.accept(&speech(1.0));
        let lines = s.update().unwrap();
        assert_eq!(settled(&lines), ["First sentence. Second"]);
        s.accept(&speech(1.0));
        s.update().unwrap();
        let asked = asked.lock().unwrap();
        assert_eq!(asked[1], 4 * SAMPLE_RATE);
        assert_eq!(asked[2], 3 * SAMPLE_RATE, "the settled first segment's two seconds are gone");
    }

    #[test]
    fn a_window_that_grows_too_long_settles_all_but_its_last_segment() {
        let long = |extra: &str| {
            vec![
                seg(0.0, 10.0, "a long run on"),
                seg(10.0, 20.0, "that never repeats itself"),
                seg(20.0, 21.0, extra),
            ]
        };
        let (mut s, asked) = session(vec![long("still"), long("still talking"), vec![]]);
        s.accept(&speech(21.0));
        let lines = s.update().unwrap();
        assert_eq!(settled(&lines), ["a long run on that never repeats itself"]);
        assert_eq!(provisional(&lines), "still");
        s.accept(&speech(1.0));
        s.update().unwrap();
        assert_eq!(asked.lock().unwrap()[1], 2 * SAMPLE_RATE, "only the last segment's audio is kept");
    }

    #[test]
    fn silence_is_never_decoded() {
        let (mut s, asked) = session(vec![vec![seg(0.0, 1.0, "Thank you.")]]);
        s.accept(&vec![0.001; SAMPLE_RATE]);
        let lines = s.update().unwrap();
        assert!(settled(&lines).is_empty());
        assert_eq!(provisional(&lines), "");
        assert!(s.finish().unwrap().is_empty());
        assert!(asked.lock().unwrap().is_empty(), "whisper is not asked to imagine words in room tone");
    }

    #[test]
    fn finishing_settles_whatever_was_still_provisional() {
        let (mut s, _) = session(vec![
            vec![seg(0.0, 1.0, "remind me")],
            vec![seg(0.0, 2.0, "remind me to send")],
            vec![seg(0.0, 2.5, "remind me to send the drawings")],
        ]);
        s.accept(&speech(1.0));
        s.update().unwrap();
        s.accept(&speech(1.0));
        let mid = s.update().unwrap();
        assert_eq!(settled(&mid), ["remind me"]);
        s.accept(&speech(0.5));
        let end = s.finish().unwrap();
        assert_eq!(settled(&end), ["to send the drawings"]);
        assert!(end.iter().all(|l| l.complete), "nothing is left provisional after a stop");
    }

    #[test]
    fn line_ids_follow_the_caption_fold() {
        // The settled words reuse the id of the line they were provisional
        // on, and the rest moves to the next id: the fold then freezes the
        // first and rewrites the second, exactly as with Moonshine.
        let (mut s, _) = session(vec![
            vec![seg(0.0, 1.0, "a b")],
            vec![seg(0.0, 2.0, "a b c")],
        ]);
        s.accept(&speech(1.0));
        let first = s.update().unwrap();
        assert_eq!(first, [LiveLine { id: 0, text: "a b".into(), complete: false }]);
        s.accept(&speech(1.0));
        let second = s.update().unwrap();
        assert_eq!(
            second,
            [
                LiveLine { id: 0, text: "a b".into(), complete: true },
                LiveLine { id: 1, text: "c".into(), complete: false },
            ]
        );
    }

    #[test]
    fn an_update_with_no_new_audio_does_nothing() {
        let (mut s, asked) = session(vec![vec![seg(0.0, 1.0, "hello")]]);
        s.accept(&speech(1.0));
        s.update().unwrap();
        assert!(s.update().unwrap().is_empty());
        assert_eq!(asked.lock().unwrap().len(), 1);
    }
}
