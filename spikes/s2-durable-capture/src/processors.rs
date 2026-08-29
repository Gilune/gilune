//! SAMPLE DOMAIN — stands in for plugins, and must stay quarantined here.
//!
//! Tasks and transcription are domain features: in the real system they are
//! plugins behind the capability contract (S3 validates that boundary). S2
//! only needs deterministic processors to drive the event→job→output chain,
//! so this module simulates two "bundled plugins":
//!
//! - handlers receive a deliberately narrow [`ProcessorCtx`] shaped like the
//!   `objects.read` capability (read a referenced source's bytes, read an
//!   input revision's payload) — never a database connection;
//! - subscriptions are declared here, as a manifest would;
//! - vault rendering for these types is contributed here as [`TypeRenderer`]
//!   entries; the projection itself knows no domain types and falls back to
//!   a generic preservable form for types nothing has registered.
//!
//! Nothing outside this module may name a domain type. If S3 replaces this
//! with real WIT-contract plugins, the kernel modules should not change.

use crate::{capture, jobs, Kernel};
use anyhow::Result;

pub const TRANSCRIPT_TYPE: &str = "org.memory.transcription/transcript";
pub const TRANSCRIPT_READY: &str = "org.memory.transcription/transcript-ready";
pub const TASK_TYPE: &str = "org.memory.tasks/task";
pub const TASK_PROPOSED: &str = "org.memory.tasks/task-proposed";

pub const HANDLER_TRANSCRIBE: &str = "fake-transcribe";
pub const HANDLER_TASK: &str = "fake-task";

/// The manifest-equivalent: which events these sample "plugins" subscribe to.
pub fn seed_subscriptions(k: &Kernel) -> Result<()> {
    jobs::register_subscription(k, "sub-transcribe", capture::SOURCE_READY, HANDLER_TRANSCRIBE)?;
    jobs::register_subscription(k, "sub-task", TRANSCRIPT_READY, HANDLER_TASK)?;
    Ok(())
}

/// What a processor may see: the shape of a scoped `objects.read` grant.
/// No queries, no writes, no other objects — reads of its declared inputs.
pub struct ProcessorCtx<'a> {
    kernel: &'a Kernel,
}

impl<'a> ProcessorCtx<'a> {
    pub(crate) fn new(kernel: &'a Kernel) -> Self {
        ProcessorCtx { kernel }
    }
    pub fn read_source_bytes(&self, object_id: &str) -> Result<(Vec<u8>, String)> {
        capture::read_source_bytes(self.kernel, object_id)
    }
    pub fn read_payload(&self, revision_id: &str) -> Result<serde_json::Value> {
        let payload: String = self.kernel.conn.query_row(
            "SELECT payload_json FROM revisions WHERE revision_id = ?1",
            [revision_id],
            |r| r.get(0),
        )?;
        Ok(serde_json::from_str(&payload)?)
    }
}

/// A processor's proposed output: the domain half of a job result. The job
/// system supplies identity, provenance, causation, and commit.
pub struct ProposedOutput {
    pub type_id: &'static str,
    pub class: &'static str,
    pub event_type: &'static str,
    pub payload: serde_json::Value,
}

pub fn dispatch(
    ctx: &ProcessorCtx,
    handler: &str,
    subject_object: &str,
    subject_revision: &str,
) -> Result<Option<ProposedOutput>> {
    match handler {
        HANDLER_TRANSCRIBE => transcribe(ctx, subject_object),
        HANDLER_TASK => propose_task(ctx, subject_revision),
        other => anyhow::bail!("unknown handler {other}"),
    }
}

/// Deterministic fake transcription: text passes through, audio is labelled.
fn transcribe(ctx: &ProcessorCtx, source_object: &str) -> Result<Option<ProposedOutput>> {
    let (bytes, media_type) = ctx.read_source_bytes(source_object)?;
    let text = if media_type.starts_with("text/") {
        String::from_utf8_lossy(&bytes).to_string()
    } else {
        format!(
            "(untranscribable {} bytes, sha {})",
            bytes.len(),
            &crate::ids::sha256_hex(&bytes)[..12]
        )
    };
    Ok(Some(ProposedOutput {
        type_id: TRANSCRIPT_TYPE,
        class: "artifact",
        event_type: TRANSCRIPT_READY,
        payload: serde_json::json!({ "text": text }),
    }))
}

/// Deterministic fake task proposer: a transcript line containing "todo"
/// becomes a task proposal; anything else, no output.
fn propose_task(ctx: &ProcessorCtx, transcript_revision: &str) -> Result<Option<ProposedOutput>> {
    let payload = ctx.read_payload(transcript_revision)?;
    let text = payload["text"].as_str().unwrap_or_default();
    let todo_line = text
        .lines()
        .find(|l| l.to_ascii_lowercase().contains("todo"))
        .map(|l| l.trim().to_string());
    Ok(todo_line.map(|title| ProposedOutput {
        type_id: TASK_TYPE,
        class: "proposal",
        event_type: TASK_PROPOSED,
        payload: serde_json::json!({ "title": title, "status": "proposed" }),
    }))
}

// ---------------------------------------------------------------------------
// Vault rendering contributions: how a type OWNER says its objects look as
// Markdown, and how an external edit maps back into its payload. The vault
// projection consults these and otherwise uses its generic preservable form.

pub struct TypeRenderer {
    pub type_id: &'static str,
    pub path_prefix: &'static str,
    pub render: fn(&serde_json::Value) -> String,
    /// How an externally edited body maps back into the payload. A type
    /// without this cannot be edited through the vault: edits to it are
    /// quarantined because no owner can interpret them.
    pub apply_edit: Option<fn(&mut serde_json::Value, &str)>,
}

fn render_transcript(p: &serde_json::Value) -> String {
    p["text"].as_str().unwrap_or_default().to_string()
}
fn edit_transcript(p: &mut serde_json::Value, body: &str) {
    p["text"] = serde_json::json!(body.trim_end());
}
fn render_task(p: &serde_json::Value) -> String {
    format!("- [ ] {}", p["title"].as_str().unwrap_or_default())
}
fn edit_task(p: &mut serde_json::Value, body: &str) {
    let title = body.trim().trim_start_matches("- [ ]").trim().to_string();
    p["title"] = serde_json::json!(title);
}

pub static SAMPLE_RENDERERS: &[TypeRenderer] = &[
    TypeRenderer {
        type_id: TRANSCRIPT_TYPE,
        path_prefix: "Transcripts/transcript",
        render: render_transcript,
        apply_edit: Some(edit_transcript),
    },
    TypeRenderer {
        type_id: TASK_TYPE,
        path_prefix: "Tasks/task",
        render: render_task,
        apply_edit: Some(edit_task),
    },
];

pub fn renderer_for(type_id: &str) -> Option<&'static TypeRenderer> {
    SAMPLE_RENDERERS.iter().find(|r| r.type_id == type_id)
}
