//! The sample processors from Spike S2, recompiled as a sandboxed component.
//! `fake-transcribe` and `fake-task` must produce byte-identical payloads to
//! the bundled implementations in spike-s2's processors.rs — that is the
//! contract-parity claim S3 exists to test. The extra handlers are
//! adversarial: they crash, spin, hog memory, or return garbage so the host
//! can prove containment.

wit_bindgen::generate!({
    path: "../wit",
    world: "processor",
});

use exports::gilune::processor::handler::Guest;
use gilune::processor::host_objects;
use gilune::processor::types::ProposedOutput;

struct Component;

impl Guest for Component {
    fn handle(
        name: String,
        subject_object: String,
        subject_revision: String,
    ) -> Result<Option<ProposedOutput>, String> {
        match name.as_str() {
            "fake-transcribe" => transcribe(&subject_object),
            "fake-task" => propose_task(&subject_revision),
            // Adversarial handlers for sandbox containment tests:
            "crash" => panic!("deliberate guest crash"),
            "spin" => {
                let mut x: u64 = 0;
                loop {
                    x = x.wrapping_add(1);
                    core::hint::black_box(x);
                }
            }
            "hog" => {
                let mut hoard: Vec<Vec<u8>> = Vec::new();
                loop {
                    hoard.push(vec![0u8; 1 << 20]);
                    core::hint::black_box(hoard.len());
                }
            }
            "huge-output" => Ok(Some(ProposedOutput {
                type_id: "org.memory.spike/huge".into(),
                class: "artifact".into(),
                event_type: "org.memory.spike/huge-ready".into(),
                payload_json: "x".repeat(32 * 1024 * 1024),
            })),
            "bad-json" => Ok(Some(ProposedOutput {
                type_id: "org.memory.transcription/transcript".into(),
                class: "artifact".into(),
                event_type: "org.memory.transcription/transcript-ready".into(),
                payload_json: "{not json at all".into(),
            })),
            "undeclared-read" => {
                // Ask for an object the grant does not cover; the denial must
                // come back as an ordinary error the plugin can surface.
                host_objects::read_source_bytes("object-i-was-never-granted")
                    .map(|_| None)
                    .map_err(|e| format!("host denied as expected: {e}"))
            }
            other => Err(format!("unknown handler {other}")),
        }
    }
}

fn transcribe(source_object: &str) -> Result<Option<ProposedOutput>, String> {
    let (bytes, media_type) = host_objects::read_source_bytes(source_object)?;
    let text = if media_type.starts_with("text/") {
        String::from_utf8_lossy(&bytes).to_string()
    } else {
        let mut h = <sha2::Sha256 as sha2::Digest>::new();
        sha2::Digest::update(&mut h, &bytes);
        let sha = hex::encode(sha2::Digest::finalize(h));
        format!("(untranscribable {} bytes, sha {})", bytes.len(), &sha[..12])
    };
    Ok(Some(ProposedOutput {
        type_id: "org.memory.transcription/transcript".into(),
        class: "artifact".into(),
        event_type: "org.memory.transcription/transcript-ready".into(),
        payload_json: serde_json::json!({ "text": text }).to_string(),
    }))
}

fn propose_task(transcript_revision: &str) -> Result<Option<ProposedOutput>, String> {
    let payload_json = host_objects::read_payload(transcript_revision)?;
    let payload: serde_json::Value =
        serde_json::from_str(&payload_json).map_err(|e| format!("bad input payload: {e}"))?;
    let text = payload["text"].as_str().unwrap_or_default();
    let todo_line = text
        .lines()
        .find(|l| l.to_ascii_lowercase().contains("todo"))
        .map(|l| l.trim().to_string());
    Ok(todo_line.map(|title| ProposedOutput {
        type_id: "org.memory.tasks/task".into(),
        class: "proposal".into(),
        event_type: "org.memory.tasks/task-proposed".into(),
        payload_json: serde_json::json!({ "title": title, "status": "proposed" }).to_string(),
    }))
}

export!(Component);
