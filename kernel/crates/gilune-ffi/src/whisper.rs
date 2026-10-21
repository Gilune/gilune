//! The local Whisper transcription provider (bundled profile, desktop).
//! Local-only by decision: audio never leaves the machine. Accepts the
//! app's canonical WAV (16 kHz mono s16, the format both recorders emit);
//! anything else fails the job visibly rather than guessing.
//!
//! The model is resident only while there is work for it (owner decision
//! 2026-09-04): the ggml weights cost 59 MB of a 335 MB desktop process
//! and used to be read at vault open and held for the life of the app,
//! whether or not anybody ever spoke.

use gilune_kernel::jobs::Job;
use gilune_kernel::plugins::{Contribution, PluginClass, PluginDescriptor, PluginProfile};
use gilune_kernel::processor::{OutputTarget, Processor, ProcessorCtx, ProposedOutput};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

pub const PLUGIN_ID: &str = "org.memory.transcription";

/// What this provider declares about itself. The model download is a
/// declared `network.request`, so a person sees and consents to it
/// instead of a first launch quietly fetching 75 MiB (ADR 0011).
pub fn descriptor() -> PluginDescriptor {
    PluginDescriptor {
        plugin_id: PLUGIN_ID.to_string(),
        name: "Transcription (Whisper)".to_string(),
        class: PluginClass::Provider,
        profile: PluginProfile::Bundled,
        required: false,
        processor_id: Some("transcribe".to_string()),
        subscribes: Some(gilune_kernel::capture::SOURCE_READY_EVENT.to_string()),
        emits: vec![gilune_kernel::revisions::REVISION_ADDED_EVENT.to_string()],
        capabilities: vec![
            "blobs.read".to_string(),
            "objects.write".to_string(),
            "network.request".to_string(),
        ],
        contributions: vec![Contribution {
            slot: "settings.form".to_string(),
            id: "transcription-model".to_string(),
            label: "Model and language".to_string(),
            config: String::new(),
        }],
    }
}

/// How long the weights may sit unused before they are dropped. Ten
/// minutes is long enough that a person dictating a series of notes never
/// pays a reload, and short enough that an app left open all day is not
/// holding a model nobody asked for.
const IDLE_UNLOAD_MS: i64 = 10 * 60 * 1000;

/// How often the sweeper looks. Granularity, not the threshold: a minute
/// of overshoot on ten costs nothing and one wakeup a minute is free.
const SWEEP_INTERVAL: Duration = Duration::from_secs(60);

/// The model file and whatever is currently loaded from it.
///
/// Loading is deferred to the first job (`enable_whisper` only checks
/// that the file is there and readable), and a sweeper drops the weights
/// again once no job has wanted them for `idle_ms`. Both directions are
/// invisible to callers: `transcribe` loads if it must and can be called
/// any number of times either side of an unload.
pub struct WhisperEngine {
    model_path: String,
    idle_ms: i64,
    slot: Mutex<Slot>,
    /// How many times the weights have been read off disk. The
    /// observable that says a concurrent first call loaded once, not
    /// twice.
    loads: AtomicUsize,
}

#[derive(Default)]
struct Slot {
    ctx: Option<Arc<WhisperContext>>,
    /// Transcriptions holding this context right now. The sweeper reads
    /// it because "unused for ten minutes" is not the same question as
    /// "not in use": a recording longer than the threshold would
    /// otherwise have the model pulled out from under it.
    in_flight: usize,
    last_used_ms: i64,
}

/// A context borrowed for one transcription. Its existence is what makes
/// the slot busy; dropping it starts the idle clock again.
struct Lease<'a> {
    engine: &'a WhisperEngine,
    ctx: Arc<WhisperContext>,
}

impl Drop for Lease<'_> {
    fn drop(&mut self) {
        let mut slot = self.engine.slot.lock().unwrap_or_else(|e| e.into_inner());
        slot.in_flight = slot.in_flight.saturating_sub(1);
        slot.last_used_ms = gilune_kernel::ids::now_ms();
    }
}

impl WhisperEngine {
    /// Check the model file without reading it. The failure a caller sees
    /// for a path that is not there, or not a file, or not readable, is
    /// the one it saw when this call loaded the weights.
    fn new(model_path: &str, idle_ms: i64) -> Result<Self, String> {
        let readable = std::fs::File::open(model_path)
            .and_then(|file| file.metadata())
            .map_err(|e| format!("loading whisper model {model_path}: {e}"))?;
        if !readable.is_file() {
            return Err(format!("loading whisper model {model_path}: not a file"));
        }
        Ok(Self {
            model_path: model_path.to_string(),
            idle_ms,
            slot: Mutex::new(Slot::default()),
            loads: AtomicUsize::new(0),
        })
    }

    /// Borrow the context, loading it if this is the first job since the
    /// last unload. The slot lock is held across the load — which is what
    /// makes two jobs arriving together share one context — and released
    /// before any transcription runs.
    fn lease(&self) -> Result<Lease<'_>, String> {
        let mut slot = self.slot.lock().unwrap_or_else(|e| e.into_inner());
        let ctx = match slot.ctx.as_ref() {
            Some(ctx) => Arc::clone(ctx),
            None => {
                let loaded = WhisperContext::new_with_params(
                    &self.model_path,
                    WhisperContextParameters::default(),
                )
                .map_err(|e| format!("loading whisper model {}: {e}", self.model_path))?;
                self.loads.fetch_add(1, Ordering::Relaxed);
                let loaded = Arc::new(loaded);
                slot.ctx = Some(Arc::clone(&loaded));
                loaded
            }
        };
        slot.in_flight += 1;
        slot.last_used_ms = gilune_kernel::ids::now_ms();
        Ok(Lease { engine: self, ctx })
    }

    /// Drop the weights if nothing holds them and nothing has wanted them
    /// for `idle_ms`. Idempotent, and safe to call on an empty slot.
    fn sweep(&self, now_ms: i64) {
        let mut slot = self.slot.lock().unwrap_or_else(|e| e.into_inner());
        if slot.in_flight == 0
            && slot.ctx.is_some()
            && now_ms.saturating_sub(slot.last_used_ms) >= self.idle_ms
        {
            slot.ctx = None;
        }
    }

    /// Whether the weights are in memory right now.
    pub(crate) fn is_resident(&self) -> bool {
        self.slot.lock().unwrap_or_else(|e| e.into_inner()).ctx.is_some()
    }

    #[cfg(test)]
    fn loads(&self) -> usize {
        self.loads.load(Ordering::Relaxed)
    }
}

/// Sweep `engine` every `interval` for as long as anything still holds
/// it. The thread keeps only a weak reference, so dropping the
/// transcriber ends the thread — one per engine, however many handles a
/// process opens, and none once the last is gone. Detached: a sleeping
/// thread cannot delay process exit, and there is nothing to flush.
fn sweep_while_alive(engine: &Arc<WhisperEngine>, interval: Duration) {
    let watched = Arc::downgrade(engine);
    std::thread::spawn(move || loop {
        std::thread::sleep(interval);
        let Some(engine) = watched.upgrade() else { return };
        engine.sweep(gilune_kernel::ids::now_ms());
    });
}

pub struct WhisperTranscriber {
    engine: Arc<WhisperEngine>,
    /// BCP-47-ish whisper language code, or "auto". An explicit language
    /// skips whisper's detection pass, which costs a full encoder run —
    /// worth ~20% on short clips. Comes from the plugin's settings.
    language: String,
}

impl WhisperTranscriber {
    /// Accept a model without loading it: the first job pays that cost
    /// (74 ms measured for base-q5_1 off a warm page cache, against a
    /// whole job of ~880 ms). A caller still learns straight away that
    /// the path is wrong.
    pub fn new(model_path: &str, language: &str) -> Result<Self, String> {
        Self::with_policy(model_path, language, IDLE_UNLOAD_MS, SWEEP_INTERVAL)
    }

    fn with_policy(
        model_path: &str,
        language: &str,
        idle_ms: i64,
        sweep_interval: Duration,
    ) -> Result<Self, String> {
        let engine = Arc::new(WhisperEngine::new(model_path, idle_ms)?);
        sweep_while_alive(&engine, sweep_interval);
        Ok(Self { engine, language: language.to_string() })
    }

    /// The engine behind this transcriber, so a host can observe whether
    /// the model is resident without owning it.
    pub(crate) fn engine(&self) -> &Arc<WhisperEngine> {
        &self.engine
    }

    fn transcribe(&self, samples: &[f32]) -> Result<String, String> {
        let lease = self.engine.lease()?;
        let mut state = lease.ctx.create_state().map_err(|e| e.to_string())?;
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        params.set_language(Some(&self.language));
        // whisper.cpp defaults to 4 threads however many cores exist;
        // it stops scaling usefully around 8.
        let threads = std::thread::available_parallelism()
            .map(|n| n.get().min(8))
            .unwrap_or(4);
        params.set_n_threads(threads as std::os::raw::c_int);
        state.full(params, samples).map_err(|e| e.to_string())?;
        let mut text = String::new();
        for i in 0..state.full_n_segments() {
            if let Some(segment) = state.get_segment(i) {
                text.push_str(segment.to_str_lossy().map_err(|e| e.to_string())?.trim());
                text.push(' ');
            }
        }
        Ok(clean_transcript(&text))
    }
}

/// Whisper narrates silence: a recording with no speech transcribes as
/// `[BLANK_AUDIO]`, and other non-speech is marked the same way
/// (`[MUSIC]`, `(SILENCE)`). Under ADR 0013 a transcript becomes the
/// note's own body, so left alone the model's stage direction would be
/// what a person finds written in their note. Nobody said those words;
/// a silent recording keeps an empty transcript (owner, 2026-09-01).
///
/// Deliberately conservative — this deletes a person's words if it is
/// wrong. A bracketed or parenthesised run is dropped only when it
/// stands alone (string edge or whitespace on both sides) and wraps
/// nothing but uppercase letters, underscores and spaces. Anything a
/// person might actually have said in brackets survives untouched:
/// `[sic]`, `(see below)`, `[Chapter 3]` all carry lowercase or digits
/// and are therefore prose, not markers.
fn clean_transcript(raw: &str) -> String {
    let mut kept = String::with_capacity(raw.len());
    let mut rest = raw;
    // The start of the string is a token boundary, as whitespace is.
    let mut at_boundary = true;
    while let Some(ch) = rest.chars().next() {
        if at_boundary {
            if let Some(len) = marker_len(rest) {
                // Leave a separator behind so the words either side of a
                // dropped marker cannot fuse into one.
                kept.push(' ');
                rest = &rest[len..];
                continue;
            }
        }
        kept.push(ch);
        rest = &rest[ch.len_utf8()..];
        at_boundary = ch.is_whitespace();
    }
    kept.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// The byte length of the standalone non-speech marker `text` starts
/// with, if it starts with one at all.
fn marker_len(text: &str) -> Option<usize> {
    let close = match text.as_bytes().first()? {
        b'[' => ']',
        b'(' => ')',
        _ => return None,
    };
    let end = text.find(close)?;
    let inner = &text[1..end];
    if inner.is_empty()
        || !inner.chars().all(|c| c.is_ascii_uppercase() || c == '_' || c == ' ')
    {
        return None;
    }
    let after = end + close.len_utf8();
    match text[after..].chars().next() {
        // A marker owns its whole token: trailing punctuation or a letter
        // means this is something else, and something else is speech.
        None => Some(after),
        Some(next) if next.is_whitespace() => Some(after),
        Some(_) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::clean_transcript;

    #[test]
    fn silence_transcribes_to_nothing_at_all() {
        assert_eq!(clean_transcript("[BLANK_AUDIO]"), "");
        assert_eq!(clean_transcript("  [BLANK_AUDIO]  "), "");
        assert_eq!(clean_transcript("[BLANK_AUDIO] [BLANK_AUDIO]"), "");
    }

    #[test]
    fn a_marker_beside_speech_costs_only_the_marker() {
        assert_eq!(clean_transcript("[BLANK_AUDIO] Remember the milk."), "Remember the milk.");
        assert_eq!(clean_transcript("Remember the milk. [BLANK_AUDIO]"), "Remember the milk.");
        assert_eq!(
            clean_transcript("Remember the milk. [MUSIC] And the eggs."),
            "Remember the milk. And the eggs.",
            "words either side of a dropped marker must not fuse"
        );
        assert_eq!(clean_transcript("(SILENCE) Hello"), "Hello");
        assert_eq!(clean_transcript("[MUSIC PLAYING] Hello"), "Hello", "markers may be phrases");
    }

    #[test]
    fn brackets_a_person_might_have_spoken_survive() {
        // Lowercase, mixed case and digits are prose. `[sic]` is a word
        // someone dictated; deleting it would be the worse failure.
        let sic = "He wrote sunk [sic] in the letter";
        assert_eq!(clean_transcript(sic), sic);
        assert_eq!(clean_transcript("(see below) matters"), "(see below) matters");
        assert_eq!(clean_transcript("[Chapter 3] is the one"), "[Chapter 3] is the one");
        assert_eq!(clean_transcript("[BLANK_AUDIO]s are a thing"), "[BLANK_AUDIO]s are a thing",
            "not standalone: the marker is part of a longer word");
        assert_eq!(clean_transcript("the array x[I] holds it"), "the array x[I] holds it",
            "not standalone: no whitespace before the bracket");
        assert_eq!(clean_transcript("[]"), "[]", "an empty bracket is not a marker");
    }

    #[test]
    fn whitespace_is_collapsed_and_nothing_in_is_nothing_out() {
        assert_eq!(clean_transcript(""), "");
        assert_eq!(clean_transcript("   "), "");
        assert_eq!(clean_transcript("  two   words  "), "two words");
    }
}

/// The residency policy, against the real engine. Needs a model, so each
/// row skips (rather than fails) when `GILUNE_TEST_MODEL` is unset —
/// the house rule the smoke tests already follow.
///
/// Every row drives `sweep` by hand with an hour-long sweep interval, so
/// the background thread can never be the thing that made a test pass.
#[cfg(test)]
mod residency {
    use super::{WhisperTranscriber, IDLE_UNLOAD_MS};
    use std::time::Duration;

    const NEVER_SWEEPS: Duration = Duration::from_secs(3600);

    fn model() -> Option<String> {
        match std::env::var("GILUNE_TEST_MODEL") {
            Ok(path) => Some(path),
            Err(_) => {
                eprintln!("skipped: set GILUNE_TEST_MODEL to a ggml model path");
                None
            }
        }
    }

    /// A second of digital silence. Transcribes to nothing, which is all
    /// these rows need: the question is what is in memory, not what was
    /// said.
    fn silence() -> Vec<f32> {
        vec![0.0; 16_000]
    }

    #[test]
    fn accepting_a_model_does_not_read_it_off_disk() {
        let Some(path) = model() else { return };
        let transcriber =
            WhisperTranscriber::with_policy(&path, "en", IDLE_UNLOAD_MS, NEVER_SWEEPS)
                .expect("a present model is accepted");
        assert!(!transcriber.engine().is_resident(), "nothing is loaded until a job asks");
        assert_eq!(transcriber.engine().loads(), 0);
    }

    #[test]
    fn a_missing_model_is_refused_without_waiting_for_a_job() {
        let dir = tempfile::tempdir().expect("tempdir");
        let absent = dir.path().join("no-such-model.bin");
        let Err(err) = WhisperTranscriber::new(&absent.to_string_lossy(), "en") else {
            panic!("a path with no model behind it cannot be accepted");
        };
        assert!(err.starts_with("loading whisper model "), "{err}");
    }

    #[test]
    fn the_first_job_loads_the_model_and_later_jobs_reuse_it() {
        let Some(path) = model() else { return };
        let transcriber =
            WhisperTranscriber::with_policy(&path, "en", IDLE_UNLOAD_MS, NEVER_SWEEPS)
                .expect("model accepted");
        let started = std::time::Instant::now();
        transcriber.transcribe(&silence()).expect("first transcription");
        let cold = started.elapsed();
        assert!(transcriber.engine().is_resident());
        assert_eq!(transcriber.engine().loads(), 1);

        let started = std::time::Instant::now();
        transcriber.transcribe(&silence()).expect("second transcription");
        let warm = started.elapsed();
        assert_eq!(transcriber.engine().loads(), 1, "a warm model is not read again");
        println!(
            "MODEL LOAD: cold job {} ms, warm job {} ms, so the load costs about {} ms",
            cold.as_millis(),
            warm.as_millis(),
            cold.saturating_sub(warm).as_millis()
        );
    }

    #[test]
    fn the_model_leaves_memory_once_no_job_has_wanted_it() {
        let Some(path) = model() else { return };
        let transcriber = WhisperTranscriber::with_policy(&path, "en", 0, NEVER_SWEEPS)
            .expect("model accepted");
        transcriber.transcribe(&silence()).expect("transcription");
        assert!(transcriber.engine().is_resident());

        transcriber.engine().sweep(gilune_kernel::ids::now_ms());
        assert!(!transcriber.engine().is_resident(), "an idle model is dropped");
        transcriber.engine().sweep(gilune_kernel::ids::now_ms());
        assert!(!transcriber.engine().is_resident(), "sweeping an empty slot is a no-op");
    }

    #[test]
    fn a_model_still_inside_its_idle_window_is_left_alone() {
        let Some(path) = model() else { return };
        let transcriber =
            WhisperTranscriber::with_policy(&path, "en", IDLE_UNLOAD_MS, NEVER_SWEEPS)
                .expect("model accepted");
        transcriber.transcribe(&silence()).expect("transcription");
        transcriber.engine().sweep(gilune_kernel::ids::now_ms());
        assert!(transcriber.engine().is_resident(), "ten minutes have not passed");
    }

    #[test]
    fn a_job_after_an_unload_loads_the_model_again_and_still_transcribes() {
        let Some(path) = model() else { return };
        let transcriber = WhisperTranscriber::with_policy(&path, "en", 0, NEVER_SWEEPS)
            .expect("model accepted");
        let first = transcriber.transcribe(&silence()).expect("first transcription");
        transcriber.engine().sweep(gilune_kernel::ids::now_ms());
        assert!(!transcriber.engine().is_resident());

        let second = transcriber.transcribe(&silence()).expect("transcription after an unload");
        assert_eq!(second, first, "the same audio gives the same words either side of an unload");
        assert!(transcriber.engine().is_resident());
        assert_eq!(transcriber.engine().loads(), 2, "the second job read the model again");
    }

    #[test]
    fn two_jobs_racing_the_first_load_share_one_context() {
        let Some(path) = model() else { return };
        let transcriber =
            WhisperTranscriber::with_policy(&path, "en", IDLE_UNLOAD_MS, NEVER_SWEEPS)
                .expect("model accepted");
        std::thread::scope(|scope| {
            for _ in 0..2 {
                scope.spawn(|| {
                    transcriber.transcribe(&silence()).expect("transcription");
                });
            }
        });
        assert_eq!(
            transcriber.engine().loads(),
            1,
            "59 MB of weights are read once however many jobs arrive together"
        );
    }

    #[test]
    fn the_sweep_never_takes_the_model_from_a_running_transcription() {
        let Some(path) = model() else { return };
        // Idle threshold zero: by the "unused for long enough" question
        // alone this model is always due to be dropped, so only the
        // in-flight count can keep it.
        let transcriber = WhisperTranscriber::with_policy(&path, "en", 0, NEVER_SWEEPS)
            .expect("model accepted");
        let lease = transcriber.engine().lease().expect("lease");
        transcriber.engine().sweep(gilune_kernel::ids::now_ms());
        assert!(
            transcriber.engine().is_resident(),
            "a transcription in flight holds the model however idle the clock says it is"
        );
        drop(lease);
        transcriber.engine().sweep(gilune_kernel::ids::now_ms());
        assert!(!transcriber.engine().is_resident(), "the last job out drops it");
    }
}

/// Parse the app's canonical WAV: 44-byte PCM header, 16 kHz mono s16.
fn canonical_wav_samples(bytes: &[u8]) -> Result<Vec<f32>, String> {
    if bytes.len() < 44 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err("not a canonical Gilune WAV".to_string());
    }
    let channels = u16::from_le_bytes([bytes[22], bytes[23]]);
    let sample_rate = u32::from_le_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]);
    let bits = u16::from_le_bytes([bytes[34], bytes[35]]);
    if channels != 1 || sample_rate != 16_000 || bits != 16 {
        return Err(format!(
            "unsupported WAV shape: {channels}ch {sample_rate}Hz {bits}bit (expected 1ch 16000Hz 16bit)"
        ));
    }
    let (samples, _trailing_byte) = bytes[44..].as_chunks::<2>();
    Ok(samples
        .iter()
        .map(|sample| i16::from_le_bytes(*sample) as f32 / 32768.0)
        .collect())
}

/// Whisper as the engine under a live dictation session (ADR 0027 S2).
///
/// It decodes the session's window with the same parameters a committed
/// transcription uses, through the same on-demand model: a live session
/// keeps the model resident while it runs, and the idle sweep drops it
/// afterwards exactly as it does after a job.
pub(crate) struct WhisperDecoder {
    engine: Arc<WhisperEngine>,
    /// The language each decode is told. "auto" until the first decode
    /// that hears speech has detected one; that answer then holds for the
    /// rest of the dictation, because detection is a whole extra pass
    /// (~40% of a short decode) and a person does not change language
    /// mid-sentence. A fixed language from the settings is used as given.
    language: Mutex<String>,
}

impl WhisperDecoder {
    pub(crate) fn new(engine: Arc<WhisperEngine>, language: &str) -> Self {
        Self { engine, language: Mutex::new(language.to_string()) }
    }
}

impl crate::live::Decoder for WhisperDecoder {
    fn decode(&self, samples: &[f32]) -> Result<Vec<crate::live::Segment>, String> {
        // whisper refuses less than a second of audio; a short window is
        // padded with silence rather than skipped.
        let minimum = crate::live::SAMPLE_RATE + crate::live::SAMPLE_RATE / 10;
        let mut padded;
        let samples = if samples.len() < minimum {
            padded = samples.to_vec();
            padded.resize(minimum, 0.0);
            &padded[..]
        } else {
            samples
        };
        let lease = self.engine.lease()?;
        let mut state = lease.ctx.create_state().map_err(|e| e.to_string())?;
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        let language = self.language.lock().unwrap_or_else(|e| e.into_inner()).clone();
        params.set_language(Some(&language));
        // Each decode stands alone: carrying the previous decode's text in
        // as a prompt is how whisper repeats itself.
        params.set_no_context(true);
        let threads = std::thread::available_parallelism()
            .map(|n| n.get().min(8))
            .unwrap_or(4);
        params.set_n_threads(threads as std::os::raw::c_int);
        state.full(params, samples).map_err(|e| e.to_string())?;
        // Not from the first second, though: detection on that little
        // speech is a guess (p ~0.55 measured); three seconds settle it.
        if language == "auto" && samples.len() >= 3 * crate::live::SAMPLE_RATE && state.full_n_segments() > 0 {
            if let Some(heard) = whisper_rs::get_lang_str(state.full_lang_id_from_state()) {
                *self.language.lock().unwrap_or_else(|e| e.into_inner()) = heard.to_string();
            }
        }
        let mut segments = Vec::new();
        for i in 0..state.full_n_segments() {
            if let Some(segment) = state.get_segment(i) {
                let text = clean_transcript(&segment.to_str_lossy().map_err(|e| e.to_string())?);
                if text.is_empty() {
                    continue;
                }
                segments.push(crate::live::Segment {
                    end_s: segment.end_timestamp() as f32 / 100.0,
                    text,
                });
            }
        }
        Ok(segments)
    }
}

impl Processor for WhisperTranscriber {
    fn id(&self) -> &str {
        "transcribe"
    }

    fn process(&self, ctx: &ProcessorCtx<'_>, _job: &Job) -> Result<Vec<ProposedOutput>, String> {
        // The head and the document, from one row and one read. Taken
        // before the model runs: a long recording can take seconds, and
        // whatever landed on the note in the meantime — a person typing, a
        // second engine finishing — is an answer this one must not
        // silently replace.
        //
        // **One read rather than two.** They used to be separate calls,
        // and a revision landing between them left this holding the old
        // document under the new head — at which point the compare-and-set
        // below would pass and overwrite the very answer it exists to
        // protect.
        let (read_head, payload) =
            ctx.subject_head_and_payload().map_err(|e| e.to_string())?;
        let mut document: serde_json::Value =
            serde_json::from_str(&payload).map_err(|e| e.to_string())?;
        if document.get("media").and_then(|v| v.as_str()) != Some("wav") {
            return Ok(Vec::new());
        }
        let bytes = ctx.subject_blob().map_err(|e| e.to_string())?;
        let samples = canonical_wav_samples(&bytes)?;
        if samples.len() < 16_000 / 10 {
            return Err("recording shorter than 100 ms".to_string());
        }
        document["text"] = serde_json::Value::String(self.transcribe(&samples)?);
        Ok(vec![ProposedOutput {
            // The revised document is still the subject's own, so it keeps
            // the subject's type; both fields are ignored either way.
            type_id: gilune_kernel::capture::SOURCE_TYPE.to_string(),
            type_schema: 1,
            payload: document.to_string(),
            target: OutputTarget::ReviseSubject,
            expected_head: read_head,
        }])
    }
}
