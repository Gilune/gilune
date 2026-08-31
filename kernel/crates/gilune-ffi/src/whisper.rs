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
        Ok(text.trim().to_string())
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
        }])
    }
}
