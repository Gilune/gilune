//! The processing host (constitution rules 2, 5, 7; ADR 0004): claims
//! durable jobs and runs registered processors against them. A processor
//! sees a `ProcessorCtx` — the narrow seam shaped like a scoped
//! `objects.read` grant: the job's subject and its blob bytes, never a
//! store handle. Outputs commit transactionally, idempotently (one
//! processing run per processor + idempotency key, however often the job
//! is delivered), as separate interpretation objects with provenance to
//! their inputs. The Rust trait mirrors the public WIT contract in
//! `contracts/processor/`; bundled and sandboxed implementations must
//! stay behaviourally interchangeable (ADR 0003).

use crate::capture::record_operation;
use crate::crashpoints::crashpoint;
use crate::jobs::Job;
use crate::store::Store;
use crate::{ids, KernelError, Result};

pub const OBJECT_CREATED_EVENT_PREFIX: &str = "org.memory.";

/// What a processor may read while handling a job.
pub struct ProcessorCtx<'a> {
    store: &'a Store,
    subject_id: String,
}

impl ProcessorCtx<'_> {
    /// The subject's current head payload (canonical JSON document).
    pub fn subject_payload(&self) -> Result<String> {
        let payload = self.store.conn().query_row(
            "SELECT r.payload FROM heads h
             JOIN revisions r ON r.revision_id = h.revision_id
             WHERE h.object_id = ?1
             ORDER BY r.created_ms DESC LIMIT 1",
            [&self.subject_id],
            |row| row.get(0),
        )?;
        Ok(payload)
    }

    /// The subject's original blob bytes, verified against the recorded
    /// digest — a processor never sees bytes the kernel cannot vouch for.
    pub fn subject_blob(&self) -> Result<Vec<u8>> {
        let (rel_path, sha256): (String, String) = self.store.conn().query_row(
            "SELECT rel_path, sha256 FROM blobs WHERE object_id = ?1 AND state = 'ready'",
            [&self.subject_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        let path = self.store.vault().join(&rel_path);
        let bytes =
            std::fs::read(&path).map_err(|e| KernelError::Io { path, source: e })?;
        if ids::sha256_hex(&bytes) != sha256 {
            return Err(KernelError::VaultUnusable {
                reason: format!("blob for {} fails its digest", self.subject_id),
            });
        }
        Ok(bytes)
    }
}

/// An interpretation a processor proposes. The kernel owns committing it.
#[derive(Debug, Clone)]
pub struct ProposedOutput {
    /// e.g. "org.memory.transcript"; the last segment names the vault
    /// folder and the `<type>-ready` event.
    pub type_id: String,
    pub type_schema: i64,
    /// Canonical JSON document.
    pub payload: String,
}

pub trait Processor: Send + Sync {
    /// Stable identity; doubles as the subscription id.
    fn id(&self) -> &str;
    /// Deterministic handlers make recovery failures distinguishable from
    /// provider variation; report honestly either way.
    fn process(&self, ctx: &ProcessorCtx<'_>, job: &Job) -> std::result::Result<Vec<ProposedOutput>, String>;
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct ProcessingSummary {
    pub completed: i64,
    pub skipped_duplicate: i64,
    pub failed: i64,
    pub outputs: i64,
}

impl Store {
    /// Run pending jobs against the registered processors until none are
    /// claimable. At-least-once: a crash mid-run only delays the job.
    pub fn run_pending_jobs(
        &self,
        processors: &[&dyn Processor],
        now_ms: i64,
        lease_ms: i64,
    ) -> Result<ProcessingSummary> {
        let mut summary = ProcessingSummary::default();
        while let Some(job) = self.claim_next_job(now_ms, lease_ms)? {
            let Some(processor) = processors.iter().find(|p| p.id() == job.subscription_id)
            else {
                // No implementation registered here (another device may own
                // it); release the claim by letting the lease expire.
                continue;
            };
            let idempotency_key = format!("{}:{}", processor.id(), job.event_id);
            if self.run_exists(processor.id(), &idempotency_key)? {
                self.complete_job(&job.job_id)?;
                summary.skipped_duplicate += 1;
                continue;
            }
            let ctx = ProcessorCtx { store: self, subject_id: job.subject_id.clone() };
            match processor.process(&ctx, &job) {
                Ok(outputs) => {
                    summary.outputs +=
                        self.commit_outputs(processor.id(), &idempotency_key, &job, &outputs)?;
                    self.complete_job(&job.job_id)?;
                    summary.completed += 1;
                }
                Err(message) => {
                    self.fail_job(&job.job_id, now_ms, &message)?;
                    summary.failed += 1;
                }
            }
        }
        Ok(summary)
    }

    fn run_exists(&self, processor_id: &str, idempotency_key: &str) -> Result<bool> {
        let hit: Option<i64> = self
            .conn()
            .query_row(
                "SELECT 1 FROM processing_runs WHERE processor_id = ?1 AND idempotency_key = ?2",
                [processor_id, idempotency_key],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        Ok(hit.is_some())
    }

    /// One transaction: run record, every output object with provenance
    /// and operation, and the `<type>-ready` event. Output IDs derive
    /// from the idempotency inputs, so a duplicate commit is impossible
    /// by construction, not by luck.
    fn commit_outputs(
        &self,
        processor_id: &str,
        idempotency_key: &str,
        job: &Job,
        outputs: &[ProposedOutput],
    ) -> Result<i64> {
        let created_ms = ids::now_ms();
        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let done: Result<i64> = (|| {
            conn.execute(
                "INSERT INTO processing_runs
                 (run_id, processor_id, idempotency_key, event_id, created_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![ids::new_id(), processor_id, idempotency_key, job.event_id, created_ms],
            )?;
            let mut committed = 0i64;
            for (index, output) in outputs.iter().enumerate() {
                let object_id = derived_id(&format!("{idempotency_key}:{index}"));
                let revision_id = derived_id(&format!("{idempotency_key}:{index}:rev"));
                conn.execute(
                    "INSERT INTO objects (object_id, type_id, type_schema, created_ms)
                     VALUES (?1, ?2, ?3, ?4)",
                    rusqlite::params![object_id, output.type_id, output.type_schema, created_ms],
                )?;
                conn.execute(
                    "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
                     VALUES (?1, ?2, ?3, ?4, ?5)",
                    rusqlite::params![
                        revision_id,
                        object_id,
                        output.payload,
                        format!("processor:{processor_id}"),
                        created_ms
                    ],
                )?;
                conn.execute(
                    "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                    [&object_id, &revision_id],
                )?;
                conn.execute(
                    "INSERT INTO provenance (object_id, target_id, relation, processor_id)
                     VALUES (?1, ?2, 'derived-from', ?3)",
                    rusqlite::params![object_id, job.subject_id, processor_id],
                )?;
                record_operation(
                    conn,
                    "create-object",
                    &object_id,
                    &serde_json::json!({
                        "type_id": output.type_id,
                        "type_schema": output.type_schema,
                        "payload": output.payload,
                        "origin": format!("processor:{processor_id}"),
                        "derived_from": job.subject_id,
                        "created_ms": created_ms,
                    })
                    .to_string(),
                    created_ms,
                )?;
                let short_type =
                    output.type_id.rsplit('.').next().unwrap_or("object").to_string();
                conn.execute(
                    "INSERT INTO events (event_id, event_type, subject_id, payload, created_ms)
                     VALUES (?1, ?2, ?3, ?4, ?5)",
                    rusqlite::params![
                        ids::new_id(),
                        format!("{OBJECT_CREATED_EVENT_PREFIX}{short_type}-ready"),
                        object_id,
                        output.payload,
                        created_ms
                    ],
                )?;
                committed += 1;
            }
            crashpoint("output.commit.inside");
            Ok(committed)
        })();
        match done {
            Ok(count) => {
                conn.execute_batch("COMMIT;")?;
                Ok(count)
            }
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                Err(e)
            }
        }
    }
}

/// Deterministic ID from documented idempotency inputs, UUID-shaped so
/// every ID in the system reads alike.
fn derived_id(input: &str) -> String {
    let digest = ids::sha256_hex(input.as_bytes());
    format!(
        "{}-{}-{}-{}-{}",
        &digest[0..8],
        &digest[8..12],
        &digest[12..16],
        &digest[16..20],
        &digest[20..32]
    )
}

/// The deterministic stand-in transcriber: proves the pipeline without a
/// model in the loop. Kept in the kernel for the CLI and the crash
/// harness; the real provider ships with the application.
pub struct FakeTranscriber;

impl Processor for FakeTranscriber {
    fn id(&self) -> &str {
        "transcribe"
    }

    fn process(
        &self,
        ctx: &ProcessorCtx<'_>,
        _job: &Job,
    ) -> std::result::Result<Vec<ProposedOutput>, String> {
        let payload: serde_json::Value =
            serde_json::from_str(&ctx.subject_payload().map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        if payload.get("media").and_then(|v| v.as_str()) != Some("wav") {
            return Ok(Vec::new()); // only audio transcribes
        }
        let bytes = ctx.subject_blob().map_err(|e| e.to_string())?;
        let text = format!(
            "[fake transcript of {} bytes, sha {}]",
            bytes.len(),
            &ids::sha256_hex(&bytes)[..12]
        );
        Ok(vec![ProposedOutput {
            type_id: "org.memory.transcript".to_string(),
            type_schema: 1,
            payload: serde_json::json!({ "text": text }).to_string(),
        }])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wav_captured() -> (tempfile::TempDir, Store) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.subscribe("transcribe", crate::capture::SOURCE_READY_EVENT).expect("subscribe");
        store.capture_bytes("wav", &[1u8, 2, 3, 4, 5]).expect("capture");
        (dir, store)
    }

    #[test]
    fn a_wav_capture_becomes_a_transcript_with_provenance() {
        let (_dir, store) = wav_captured();
        let summary = store
            .run_pending_jobs(&[&FakeTranscriber], 1_000, 30_000)
            .expect("run");
        assert_eq!(summary.completed, 1);
        assert_eq!(summary.outputs, 1);

        let (type_id, origin): (String, String) = store
            .conn()
            .query_row(
                "SELECT o.type_id, r.origin FROM objects o
                 JOIN revisions r ON r.object_id = o.object_id
                 WHERE o.type_id = 'org.memory.transcript'",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("transcript");
        assert_eq!(type_id, "org.memory.transcript");
        assert_eq!(origin, "processor:transcribe");
        let provenance: i64 = store
            .conn()
            .query_row("SELECT COUNT(*) FROM provenance WHERE relation = 'derived-from'", [], |r| r.get(0))
            .expect("provenance");
        assert_eq!(provenance, 1);
        // The transcript projects into the vault with its text as body.
        store.project().expect("project");
        let files: Vec<_> = std::fs::read_dir(store.vault().join("Transcripts"))
            .expect("Transcripts dir")
            .collect();
        assert_eq!(files.len(), 1);
    }

    #[test]
    fn duplicate_delivery_produces_exactly_one_transcript() {
        let (_dir, store) = wav_captured();
        store.run_pending_jobs(&[&FakeTranscriber], 1_000, 1_000).expect("first run");
        // Force the completed job back to pending, as a redelivery would.
        store
            .conn()
            .execute("UPDATE jobs SET state = 'pending', not_before_ms = 0", [])
            .expect("redeliver");
        let second = store
            .run_pending_jobs(&[&FakeTranscriber], 60_000, 30_000)
            .expect("second run");
        assert_eq!(second.skipped_duplicate, 1);
        assert_eq!(second.outputs, 0);
        let transcripts: i64 = store
            .conn()
            .query_row(
                "SELECT COUNT(*) FROM objects WHERE type_id = 'org.memory.transcript'",
                [],
                |r| r.get(0),
            )
            .expect("count");
        assert_eq!(transcripts, 1);
    }

    #[test]
    fn a_failing_processor_retries_and_never_blocks_capture() {
        struct Boom;
        impl Processor for Boom {
            fn id(&self) -> &str {
                "transcribe"
            }
            fn process(
                &self,
                _ctx: &ProcessorCtx<'_>,
                _job: &Job,
            ) -> std::result::Result<Vec<ProposedOutput>, String> {
                Err("boom".to_string())
            }
        }
        let (_dir, store) = wav_captured();
        let summary = store.run_pending_jobs(&[&Boom], 1_000, 30_000).expect("run");
        assert_eq!(summary.failed, 1);
        store.capture_bytes("txt", b"still capturing").expect("capture unblocked");
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn a_text_capture_produces_no_transcript() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.subscribe("transcribe", crate::capture::SOURCE_READY_EVENT).expect("subscribe");
        store.capture_bytes("txt", b"just text").expect("capture");
        let summary = store
            .run_pending_jobs(&[&FakeTranscriber], 1_000, 30_000)
            .expect("run");
        assert_eq!(summary.completed, 1);
        assert_eq!(summary.outputs, 0);
    }
}
