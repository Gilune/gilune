//! The local Whisper transcription provider (bundled profile, desktop).
//! Local-only by decision: audio never leaves the machine. Accepts the
//! app's canonical WAV (16 kHz mono s16, the format both recorders emit);
//! anything else fails the job visibly rather than guessing.

use gilune_kernel::jobs::Job;
use gilune_kernel::plugins::{Contribution, PluginClass, PluginDescriptor, PluginProfile};
use gilune_kernel::processor::{OutputTarget, Processor, ProcessorCtx, ProposedOutput};
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

pub const PLUGIN_ID: &str = "org.memory.transcription";

/// What this provider declares about itself. The model download is a
/// declared `network.request`, so a person sees and consents to it
/// instead of a first launch quietly fetching 75 MiB (ADR 0011).
pub fn descriptor() -> PluginDescriptor {
    PluginDescriptor {
        plugin_id: PLUGIN_ID.to_string(),
        name: "Local transcription".to_string(),
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

pub struct WhisperTranscriber {
    ctx: WhisperContext,
    /// BCP-47-ish whisper language code, or "auto". An explicit language
    /// skips whisper's detection pass, which costs a full encoder run —
    /// worth ~20% on short clips. Comes from the plugin's settings.
    language: String,
}

impl WhisperTranscriber {
    pub fn load(model_path: &str, language: &str) -> Result<Self, String> {
        let ctx = WhisperContext::new_with_params(model_path, WhisperContextParameters::default())
            .map_err(|e| format!("loading whisper model {model_path}: {e}"))?;
        Ok(Self { ctx, language: language.to_string() })
    }

    fn transcribe(&self, samples: &[f32]) -> Result<String, String> {
        let mut state = self.ctx.create_state().map_err(|e| e.to_string())?;
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

impl Processor for WhisperTranscriber {
    fn id(&self) -> &str {
        "transcribe"
    }

    fn process(&self, ctx: &ProcessorCtx<'_>, _job: &Job) -> Result<Vec<ProposedOutput>, String> {
        let mut document: serde_json::Value =
            serde_json::from_str(&ctx.subject_payload().map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        // Read together with the document, before the model runs: a long
        // recording can take seconds, and whatever landed on the note in
        // the meantime — a person typing, a second engine finishing — is
        // an answer this one must not silently replace.
        let read_head = ctx.subject_head().map_err(|e| e.to_string())?;
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
