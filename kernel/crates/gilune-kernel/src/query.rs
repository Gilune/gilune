//! Read-side queries for shells and tools: bounded, envelope-level, no
//! raw SQL across the boundary (02-kernel-contract). Grows per screen
//! need, not speculatively.

use crate::capture::ATTACHED_KEY;
use crate::store::Store;
use crate::Result;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceSummary {
    pub object_id: String,
    pub created_ms: i64,
    pub blob_rel_path: Option<String>,
    pub projection_rel_path: Option<String>,
    pub preview: String,
    /// Whether **a processor has finished a run over this source**: a
    /// `processing_runs` row exists for an event this object was the
    /// subject of.
    ///
    /// It exists because a shell cannot otherwise tell *no words yet*
    /// from *no words, ever*. An empty preview on a recording means the
    /// body is empty; whether anything is still coming is a different
    /// question, and the honest answer is a fact rather than a timeout.
    ///
    /// **The run, not the revision.** A processor that finds nothing to
    /// say completes with no outputs and writes no revision at all — a
    /// silent recording under one transcriber does exactly that — so a
    /// revision-origin test would report finished work as pending
    /// forever. The run record is written for every completed delivery,
    /// outputs or none, and it survives whatever is written over the
    /// document afterwards.
    ///
    /// Deliberately not "transcribed": the kernel does not know what a
    /// transcriber is (02-kernel-contract). It reports that an
    /// interpretation was attempted and finished; what that means for a
    /// row is the shell's reading.
    pub interpreted: bool,
}

const PREVIEW_CHARS: usize = 160;

impl Store {
    /// Newest-first sources for a capture feed. Attached captures are
    /// absent: they belong to a note the feed already shows (ADR 0013),
    /// and the exclusion is in SQL so the limit still bounds what a
    /// person sees.
    pub fn recent_sources(&self, limit: u32) -> Result<Vec<SourceSummary>> {
        // One row per object, and always the same row: the head every
        // reader sees (revisions::newest_head_first). An object with
        // concurrent heads appears once, not once per head.
        let mut stmt = self.conn().prepare(&format!(
            "SELECT o.object_id, o.created_ms, r.payload,
                    (SELECT p.rel_path FROM projections p WHERE p.object_id = o.object_id),
                    EXISTS (SELECT 1 FROM processing_runs pr
                            JOIN events e ON e.event_id = pr.event_id
                            WHERE e.subject_id = o.object_id)
             FROM objects o
             JOIN revisions r ON r.revision_id = (
                 SELECT h.revision_id FROM heads h
                 JOIN revisions hr ON hr.revision_id = h.revision_id
                 WHERE h.object_id = o.object_id {head_order} LIMIT 1
             )
             WHERE o.deleted = 0 AND o.type_id = 'org.memory.source'
               AND COALESCE(json_extract(r.payload, '$.{ATTACHED_KEY}'), 0) = 0
             ORDER BY o.created_ms DESC
             LIMIT ?1",
            head_order = crate::revisions::newest_head_first("hr")
        ))?;
        let rows = stmt.query_map([limit], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<String>>(3)?,
                row.get::<_, bool>(4)?,
            ))
        })?;
        let mut out = Vec::new();
        for row in rows {
            let (object_id, created_ms, payload, projection_rel_path, interpreted) = row?;
            let payload: serde_json::Value = serde_json::from_str(&payload)?;
            let blob_rel_path = payload
                .get("rel_path")
                .and_then(|v| v.as_str())
                .map(str::to_string);
            let preview = if let Some(text) = payload.get("text").and_then(|v| v.as_str()) {
                truncate(text)
            } else if let (Some(rel), Some("txt")) = (
                blob_rel_path.as_deref(),
                payload.get("media").and_then(|v| v.as_str()),
            ) {
                std::fs::read_to_string(self.vault().join(rel))
                    .map(|t| truncate(&t))
                    .unwrap_or_default()
            } else {
                payload
                    .get("media")
                    .and_then(|v| v.as_str())
                    .map(|m| format!("[{m} capture]"))
                    .unwrap_or_default()
            };
            out.push(SourceSummary {
                object_id,
                created_ms,
                blob_rel_path,
                projection_rel_path,
                preview,
                interpreted,
            });
        }
        Ok(out)
    }

    /// The `text` of an object's current document — what a shell inserts
    /// at the cursor once dictation has been transcribed. `None` for an
    /// object with no text yet, and for one that does not exist: a
    /// caller that is waiting cannot tell the difference and need not.
    pub fn object_text(&self, object_id: &str) -> Result<Option<String>> {
        let Some(payload) = crate::revisions::head_payload(self.conn(), object_id)? else {
            return Ok(None);
        };
        let document: serde_json::Value = serde_json::from_str(&payload)?;
        Ok(document
            .get("text")
            .and_then(|v| v.as_str())
            .map(str::to_string))
    }

    /// How many revisions an object has. Diagnostic: history growing by
    /// exactly one is what idempotency looks like from outside.
    pub fn revision_count(&self, object_id: &str) -> Result<i64> {
        Ok(self.conn().query_row(
            "SELECT COUNT(*) FROM revisions WHERE object_id = ?1",
            [object_id],
            |row| row.get(0),
        )?)
    }

    /// Every head an object currently has, newest first. One is the
    /// ordinary case; more than one means two devices wrote concurrently
    /// and both were kept (ADR 0005).
    pub fn object_heads(&self, object_id: &str) -> Result<Vec<String>> {
        crate::revisions::heads(self.conn(), object_id)
    }

    /// A digest of everything two replicas must agree on: which objects
    /// exist, of what type, whether they are tombstoned, and which
    /// revisions are their heads. Equal digests are what convergence
    /// means, and unequal ones say so without a person diffing two
    /// databases.
    ///
    /// Deliberately excludes device-local facts — frontiers, alerts, job
    /// state, projections — because replicas are not supposed to agree on
    /// those, and a digest that included them could never be equal.
    pub fn convergence_digest(&self) -> Result<String> {
        let mut stmt = self.conn().prepare(
            "SELECT object_id, type_id, type_schema, deleted FROM objects ORDER BY object_id",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, i64>(3)?,
            ))
        })?;
        let mut material = String::new();
        for row in rows {
            let (object_id, type_id, type_schema, deleted) = row?;
            let mut heads = self.object_heads(&object_id)?;
            heads.sort();
            material.push_str(&format!(
                "{object_id}|{type_id}|{type_schema}|{deleted}|{}\n",
                heads.join(",")
            ));
        }
        Ok(crate::ids::sha256_hex(material.as_bytes()))
    }
}

fn truncate(text: &str) -> String {
    let line = text.lines().next().unwrap_or("");
    line.chars().take(PREVIEW_CHARS).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recent_sources_are_newest_first_with_previews() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.capture_bytes("txt", b"first thought\nsecond line").expect("capture");
        store.capture_bytes("wav", &[0u8; 4]).expect("capture");
        store.project().expect("project");

        let sources = store.recent_sources(10).expect("query");
        assert_eq!(sources.len(), 2);
        assert_eq!(sources[0].preview, "[wav capture]");
        assert_eq!(sources[1].preview, "first thought");
        assert!(sources[1].projection_rel_path.is_some());
        assert_eq!(store.recent_sources(1).expect("limit").len(), 1);
    }

    #[test]
    fn attached_captures_are_absent_from_the_capture_feed() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.capture_bytes("txt", b"a thought of my own").expect("capture");
        let mut stage = store.begin_attached_capture("wav").expect("begin");
        stage.write_chunk(&[1u8; 16]).expect("chunk");
        let attached = stage.finish(&store).expect("finish");

        let sources = store.recent_sources(10).expect("query");
        assert_eq!(sources.len(), 1, "dictation is not a capture of its own");
        assert_eq!(sources[0].preview, "a thought of my own");
        assert!(store.object_text(&attached.source_id).expect("text").is_none());
    }

    /// The ordinary case: a processor ran, wrote words, and the source
    /// says so. A capture nothing has looked at yet says the opposite —
    /// which is what keeps "waiting" and "finished" apart.
    #[test]
    fn a_finished_processing_run_marks_a_source_interpreted() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.subscribe("transcribe", crate::capture::SOURCE_READY_EVENT).expect("subscribe");
        store.capture_bytes("wav", &[7u8; 32]).expect("capture");
        assert!(
            !store.recent_sources(1).expect("query")[0].interpreted,
            "a capture whose job has not run is not interpreted"
        );

        store
            .run_pending_jobs(&[&crate::processor::FakeTranscriber], 1_000, 30_000)
            .expect("run");
        assert!(store.recent_sources(1).expect("query")[0].interpreted);
    }

    /// **The second-order case, and the one this field exists for.** A
    /// processor that finds nothing to say completes with *no outputs*:
    /// no revision is written, the head is the capture's own, and the
    /// document is exactly as empty as it was before the engine ran.
    /// Reading the head revision's origin would call that pending
    /// forever; the run record calls it finished, which it is.
    ///
    /// `FakeTranscriber` takes that branch for a non-audio capture, which
    /// is the same branch a transcriber takes for a recording of silence.
    #[test]
    fn a_run_that_wrote_nothing_still_marks_a_source_interpreted() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.subscribe("transcribe", crate::capture::SOURCE_READY_EVENT).expect("subscribe");
        store.capture_bytes("txt", b"nothing for a transcriber here").expect("capture");
        let summary = store
            .run_pending_jobs(&[&crate::processor::FakeTranscriber], 1_000, 30_000)
            .expect("run");
        assert_eq!(summary.outputs, 0, "the processor had nothing to say");

        let source = store.recent_sources(1).expect("query").remove(0);
        assert_eq!(
            store.revision_count(&source.object_id).expect("revisions"),
            1,
            "no revision was written, so a revision-origin test would see only the capture's own"
        );
        assert!(source.interpreted);
    }

    #[test]
    fn object_text_answers_with_the_current_document() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.subscribe("transcribe", crate::capture::SOURCE_READY_EVENT).expect("subscribe");
        let mut stage = store.begin_attached_capture("wav").expect("begin");
        stage.write_chunk(&[2u8; 48]).expect("chunk");
        let attached = stage.finish(&store).expect("finish");
        store
            .run_pending_jobs(&[&crate::processor::FakeTranscriber], 1_000, 30_000)
            .expect("run");

        let text = store.object_text(&attached.source_id).expect("query").expect("transcribed");
        assert!(text.starts_with("[fake transcript"));
        assert_eq!(store.object_text("no-such-object").expect("query"), None);
    }

    #[test]
    fn an_external_edit_updates_the_preview() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.capture_bytes("txt", b"before edit").expect("capture");
        store.project().expect("project");
        let rel = store.recent_sources(1).expect("query")[0]
            .projection_rel_path
            .clone()
            .expect("projected");
        let edited = std::fs::read_to_string(store.vault().join(&rel))
            .expect("read")
            .replace("before edit", "after edit");
        std::fs::write(store.vault().join(&rel), edited).expect("edit");
        store.reconcile().expect("reconcile");
        assert_eq!(store.recent_sources(1).expect("query")[0].preview, "after edit");
    }
}
