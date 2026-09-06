//! The processing host (constitution rules 2, 5, 7; ADR 0004): claims
//! durable jobs and runs registered processors against them. A processor
//! sees a `ProcessorCtx` — the narrow seam shaped like a scoped
//! `objects.read` grant: the job's subject and its blob bytes, never a
//! store handle. Outputs commit transactionally, idempotently (one
//! processing run per processor + idempotency key, however often the job
//! is delivered), either as separate interpretation objects with
//! provenance to their inputs or as a revision of the subject itself
//! (ADR 0013). The Rust trait mirrors the public WIT contract in
//! `contracts/processor/`; bundled and sandboxed implementations must
//! stay behaviourally interchangeable (ADR 0003).

use crate::crashpoints::crashpoint;
use crate::jobs::Job;
use crate::revisions::{add_revision, head_payload, Announce};
use crate::store::Store;
use crate::{ids, KernelError, Result};

pub const OBJECT_CREATED_EVENT_PREFIX: &str = "org.memory.";

/// What a revision written by a processor carries in `revisions.origin`,
/// before the processor's own id. One home: the delivery path writes it
/// and the change feed classifies by it.
pub(crate) const ORIGIN_PREFIX: &str = "processor:";

/// Where a subject's preserved bytes are and what they must hash to.
/// Enough to read the blob without reading it — which is what lets the
/// external route stream it instead of returning it whole (ADR 0018 § 2).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlobFacts {
    /// Vault-relative, '/'-separated (03-data-model).
    pub rel_path: String,
    pub sha256: String,
    pub bytes: u64,
}

/// What a processor may read while handling a job.
pub struct ProcessorCtx<'a> {
    store: &'a Store,
    subject_id: String,
}

impl<'a> ProcessorCtx<'a> {
    /// The seam is the kernel's to hand out, never the caller's to build:
    /// a `ProcessorCtx` exists only for the subject of a claimed job.
    pub(crate) fn new(store: &'a Store, subject_id: String) -> Self {
        Self { store, subject_id }
    }

    /// The subject's current head payload (canonical JSON document).
    pub fn subject_payload(&self) -> Result<String> {
        head_payload(self.store.conn(), &self.subject_id)?.ok_or_else(|| KernelError::Corrupt {
            detail: format!("{} has no current revision", self.subject_id),
        })
    }

    /// Which revision `subject_payload` just answered with — the head a
    /// processor actually read. Passing it back as an output's
    /// `expected_head` is how a slow processor declines to overwrite an
    /// answer that landed while it was working.
    ///
    /// **Prefer `subject_head_and_payload`.** Calling this and
    /// `subject_payload` separately is two reads with a gap between them,
    /// and the gap is exactly the race the compare-and-set exists to
    /// close: see that method for what the gap does.
    pub fn subject_head(&self) -> Result<Option<String>> {
        Ok(crate::revisions::heads(self.store.conn(), &self.subject_id)?.into_iter().next())
    }

    /// The head **and** the document it holds, from one row.
    ///
    /// **Two reads with a gap between them defeat the check they feed.**
    /// A processor reads the document, reads the head, and hands the head
    /// back as `expected_head` so a revision that landed meanwhile refuses
    /// its output. If a revision lands *between* those two reads, the pair
    /// describes no revision that ever existed: the old document under the
    /// new head. The compare-and-set then passes — the head is current,
    /// because it was read second — and the newer answer is silently
    /// overwritten by work done against the older one. Reading them the
    /// other way round is no better; it just moves which half is stale.
    ///
    /// One query over one head row cannot be interleaved, so the pair is
    /// always one revision's, and a revision arriving after it is exactly
    /// what the compare-and-set is there to refuse.
    pub fn subject_head_and_payload(&self) -> Result<(Option<String>, String)> {
        let row: Option<(String, String)> = self
            .store
            .conn()
            .query_row(
                &format!(
                    "SELECT r.revision_id, r.payload FROM heads h
                     JOIN revisions r ON r.revision_id = h.revision_id
                     WHERE h.object_id = ?1 {} LIMIT 1",
                    crate::revisions::newest_head_first("r")
                ),
                [&self.subject_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(KernelError::from(e)),
            })?;
        let (head, payload) = row.ok_or_else(|| KernelError::Corrupt {
            detail: format!("{} has no current revision", self.subject_id),
        })?;
        Ok((Some(head), payload))
    }

    /// Where the subject's blob is, or `None` when it has none — a
    /// derived object carries its interpretation in its document.
    pub fn subject_blob_facts(&self) -> Result<Option<BlobFacts>> {
        self.store
            .conn()
            .query_row(
                "SELECT rel_path, sha256, bytes FROM blobs
                 WHERE object_id = ?1 AND state = 'ready'",
                [&self.subject_id],
                |row| {
                    Ok(BlobFacts {
                        rel_path: row.get(0)?,
                        sha256: row.get(1)?,
                        bytes: row.get::<_, i64>(2)? as u64,
                    })
                },
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(KernelError::from(e)),
            })
    }

    /// The subject's original blob bytes, verified against the recorded
    /// digest — a processor never sees bytes the kernel cannot vouch for.
    pub fn subject_blob(&self) -> Result<Vec<u8>> {
        let facts = self.subject_blob_facts()?.ok_or_else(|| KernelError::Corrupt {
            detail: format!("{} has no blob", self.subject_id),
        })?;
        let path = self.store.vault().join(&facts.rel_path);
        let bytes = std::fs::read(&path).map_err(|e| KernelError::Io { path, source: e })?;
        if ids::sha256_hex(&bytes) != facts.sha256 {
            return Err(KernelError::VaultUnusable {
                reason: format!("blob for {} fails its digest", self.subject_id),
            });
        }
        Ok(bytes)
    }
}

/// Where a proposed output lands (ADR 0013).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutputTarget {
    /// A new interpretation object, with provenance back to the subject.
    NewObject,
    /// A new revision of the subject's own document, replacing its head.
    /// The words belong in the note they were spoken into, not beside it.
    ReviseSubject,
}

/// An interpretation a processor proposes. The kernel owns committing it.
#[derive(Debug, Clone)]
pub struct ProposedOutput {
    /// e.g. "org.memory.transcript"; the last segment names the vault
    /// folder and the `<type>-ready` event. **Ignored for
    /// `ReviseSubject`**, which writes the subject's own document and so
    /// keeps the subject's type; the revision's origin records who wrote
    /// it, and no provenance row is added because there is no new object
    /// to attach one to.
    pub type_id: String,
    /// Ignored for `ReviseSubject`, as `type_id` is.
    pub type_schema: i64,
    /// Canonical JSON document.
    pub payload: String,
    pub target: OutputTarget,
    /// The subject head this output was written over, for a
    /// `ReviseSubject` that would rather be refused than overwrite. `None`
    /// — the default and the shape everything shipped with — keeps the
    /// old behaviour: the output lands whatever the subject has become.
    ///
    /// Ignored for `NewObject`, which replaces nothing.
    pub expected_head: Option<String>,
}

pub trait Processor: Send + Sync {
    /// Stable identity; doubles as the subscription id.
    fn id(&self) -> &str;
    /// Deterministic handlers make recovery failures distinguishable from
    /// provider variation; report honestly either way.
    fn process(
        &self,
        ctx: &ProcessorCtx<'_>,
        job: &Job,
    ) -> std::result::Result<Vec<ProposedOutput>, String>;
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct ProcessingSummary {
    pub completed: i64,
    pub skipped_duplicate: i64,
    /// The subject was revised while the processor was running, and the
    /// output named the head it had read — so it was refused rather than
    /// written over the newer answer, and the job completed with nothing
    /// to show. Its own counter because it is not a duplicate: a duplicate
    /// means the work is already recorded, this means the work was dropped
    /// on purpose because something better arrived.
    pub skipped_moved: i64,
    pub failed: i64,
    pub outputs: i64,
}

/// What one delivery of a processor's result did. A duplicate delivery is
/// not a failure — it is at-least-once working as promised.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Delivery {
    /// The run was already recorded, so nothing was written a second time.
    pub skipped_duplicate: bool,
    pub outputs: i64,
}

/// One processing run per processor per event, however often the job is
/// delivered and **whichever process delivers it** — the derived IDs in
/// `commit_outputs` hang off this, so it is the whole of exactly-once.
pub fn idempotency_key(processor_id: &str, event_id: &str) -> String {
    format!("{processor_id}:{event_id}")
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
        self.run_pending_jobs_excluding(processors, &[], now_ms, lease_ms)
    }

    /// As `run_pending_jobs`, but leaving `external` processor ids alone.
    ///
    /// **This loop claims only what it can run.** A claim spends an
    /// attempt and takes a lease, so claiming a subscription with no
    /// implementation here is not a harmless no-op: it is another
    /// claimant's attempt spent for it, and five of them leave a job one
    /// real failure from quarantine. The claim is therefore filtered to
    /// the ids in `processors`, which makes the `external` list belt to
    /// that braces — an externally-implemented processor (ADR 0018 § 2)
    /// has no `Processor` here to be named by, so it is already excluded.
    /// The parameter stays because a host may carry both an id's Rust
    /// implementation and an external claimant, and then it is the only
    /// thing that says which one owns the work.
    pub fn run_pending_jobs_excluding(
        &self,
        processors: &[&dyn Processor],
        external: &[String],
        now_ms: i64,
        lease_ms: i64,
    ) -> Result<ProcessingSummary> {
        let mut summary = ProcessingSummary::default();
        let runnable: Vec<&str> = processors
            .iter()
            .map(|p| p.id())
            .filter(|id| !external.iter().any(|e| e == id))
            .collect();
        while let Some(job) = self.claim_next_job_among(&runnable, now_ms, lease_ms)? {
            let Some(processor) = processors.iter().find(|p| p.id() == job.subscription_id) else {
                // Unreachable while the claim is filtered to these ids;
                // kept because releasing a claim by letting its lease
                // expire is the safe answer if it ever is reached.
                continue;
            };
            // The work may already be done: a crash after the outputs
            // committed but before the job completed leaves the job
            // leased and its run recorded. Asking here, rather than only
            // after the processor has run, is what stops a redelivery
            // re-running a model for an answer already held — and what
            // stops a provider that has since started failing turning a
            // finished job into a quarantined one.
            if self.run_exists(processor.id(), &idempotency_key(processor.id(), &job.event_id))? {
                self.complete_job(&job.job_id)?;
                summary.skipped_duplicate += 1;
                continue;
            }
            let ctx = ProcessorCtx::new(self, job.subject_id.clone());
            match processor.process(&ctx, &job) {
                Ok(outputs) => {
                    match self.deliver_outputs(processor.id(), &job, &outputs) {
                        Ok(delivered) => {
                            summary.outputs += delivered.outputs;
                            if delivered.skipped_duplicate {
                                summary.skipped_duplicate += 1;
                            } else {
                                summary.completed += 1;
                            }
                        }
                        // **The subject moved while this ran, and that is
                        // an ordinary outcome.** The compare-and-set exists
                        // so a slow processor declines to overwrite an
                        // answer that landed while its engine was working —
                        // a person typing, a second engine finishing — and
                        // declining is the feature working, not a fault.
                        //
                        // It escaped this loop, which made it one: the `?`
                        // carried it out of `run_pending_jobs` as an error,
                        // so the *rest of the queue* never ran and every
                        // caller — the app's work pass, the recording
                        // service, the CLI — reported a failure for a job
                        // that had simply been overtaken. And the job kept
                        // its lease, so it came back and did it again.
                        //
                        // So it completes here: this job is done, there is
                        // nothing left for it to write, and the loop
                        // carries on to the next one. Counted as
                        // `skipped_moved` rather than folded into
                        // `skipped_duplicate` — a duplicate means the work
                        // was already recorded, and this is the opposite,
                        // work deliberately dropped because it was stale.
                        Err(KernelError::SubjectMoved { .. }) => {
                            self.complete_job(&job.job_id)?;
                            summary.skipped_moved += 1;
                        }
                        Err(e) => return Err(e),
                    }
                }
                Err(message) => {
                    self.fail_job(&job.job_id, now_ms, &message)?;
                    summary.failed += 1;
                }
            }
        }
        Ok(summary)
    }

    /// **The output transaction, and the only one.** Whatever produced
    /// these outputs — a bundled Rust processor, a sandboxed component, or
    /// an external implementation reaching in over the FFI — the run
    /// record, the derived revision IDs, the `processor:{id}` origin, the
    /// operation and the event are the kernel's and are identical
    /// (ADR 0013's 2026-09-02 amendment; ADR 0018 § 2). The caller
    /// proposes; this commits.
    pub fn deliver_outputs(
        &self,
        processor_id: &str,
        job: &Job,
        outputs: &[ProposedOutput],
    ) -> Result<Delivery> {
        let key = idempotency_key(processor_id, &job.event_id);
        if self.run_exists(processor_id, &key)? {
            self.complete_job(&job.job_id)?;
            return Ok(Delivery { skipped_duplicate: true, outputs: 0 });
        }
        let outputs = self.commit_outputs(processor_id, &key, job, outputs)?;
        // The window a redelivery must survive: the outputs are durable
        // but the job is still leased. Recovery is the lease expiring and
        // the run record making the retry a skip.
        crashpoint("output.after_commit");
        self.complete_job(&job.job_id)?;
        Ok(Delivery { skipped_duplicate: false, outputs })
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

    /// One transaction: the run record, then each output — a new object
    /// with provenance and its `<type>-ready` event, or a revision of the
    /// subject with its `revision-added` event — each with the operation
    /// that carries it to other devices. Output IDs derive from the
    /// idempotency inputs, so a duplicate commit is impossible by
    /// construction, not by luck.
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
                rusqlite::params![
                    ids::new_id(),
                    processor_id,
                    idempotency_key,
                    job.event_id,
                    created_ms
                ],
            )?;
            let origin = format!("{ORIGIN_PREFIX}{processor_id}");
            let mut committed = 0i64;
            for (index, output) in outputs.iter().enumerate() {
                let revision_id = derived_id(&format!("{idempotency_key}:{index}:rev"));
                if output.target == OutputTarget::ReviseSubject {
                    // Compare-and-set, inside the transaction that writes
                    // — anywhere else it would be a check that another
                    // submitter could slip past. An output that names no
                    // head asks for nothing and is not checked.
                    if let Some(expected) = &output.expected_head {
                        let found = crate::revisions::heads(conn, &job.subject_id)?
                            .into_iter()
                            .next()
                            .unwrap_or_default();
                        if &found != expected {
                            return Err(KernelError::SubjectMoved {
                                subject_id: job.subject_id.clone(),
                                expected: expected.clone(),
                                found,
                            });
                        }
                    }
                    let current = crate::revisions::head_payload(conn, &job.subject_id)?
                        .unwrap_or_else(|| "{}".into());
                    let payload =
                        crate::vault::preserve_projection_metadata(&current, &output.payload)?;
                    add_revision(
                        conn,
                        &job.subject_id,
                        &revision_id,
                        &payload,
                        &origin,
                        created_ms,
                        Announce::Event,
                    )?;
                    committed += 1;
                    continue;
                }
                let object_id = derived_id(&format!("{idempotency_key}:{index}"));
                conn.execute(
                    "INSERT INTO objects (object_id, type_id, type_schema, created_ms)
                     VALUES (?1, ?2, ?3, ?4)",
                    rusqlite::params![object_id, output.type_id, output.type_schema, created_ms],
                )?;
                conn.execute(
                    "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
                     VALUES (?1, ?2, ?3, ?4, ?5)",
                    rusqlite::params![revision_id, object_id, output.payload, origin, created_ms],
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
                let short_type = output.type_id.rsplit('.').next().unwrap_or("object").to_string();
                let event_type = format!("{OBJECT_CREATED_EVENT_PREFIX}{short_type}-ready");
                crate::operations::record(
                    conn,
                    crate::operations::OBJECT_CREATED,
                    &object_id,
                    serde_json::json!({
                        "object": {
                            "object_id": object_id,
                            "type_id": output.type_id,
                            "type_schema": output.type_schema,
                            "created_ms": created_ms,
                        },
                        "revision": {
                            "revision_id": revision_id,
                            "payload": output.payload,
                            "origin": origin,
                            "created_ms": created_ms,
                        },
                        "derived_from": job.subject_id,
                        "processor_id": processor_id,
                        "event_type": event_type,
                    }),
                    created_ms,
                )?;
                conn.execute(
                    "INSERT INTO events
                     (event_id, event_type, subject_id, payload, created_ms, origin_device)
                     VALUES (?1, ?2, ?3, ?4, ?5,
                             (SELECT value FROM meta WHERE key = 'device_id'))",
                    rusqlite::params![
                        ids::new_id(),
                        event_type,
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

/// `FakeTranscriber` as a plugin declaration, for hosts that carry it.
pub fn fake_transcriber_descriptor() -> crate::plugins::PluginDescriptor {
    crate::plugins::PluginDescriptor {
        plugin_id: "org.memory.fake-transcription".to_string(),
        name: "Stand-in transcription (development)".to_string(),
        class: crate::plugins::PluginClass::Provider,
        profile: crate::plugins::PluginProfile::Bundled,
        required: false,
        processor_id: Some("transcribe".to_string()),
        subscribes: Some(crate::capture::SOURCE_READY_EVENT.to_string()),
        emits: vec![crate::revisions::REVISION_ADDED_EVENT.to_string()],
        capabilities: vec!["blobs.read".to_string(), "objects.write".to_string()],
        contributions: Vec::new(),
    }
}

impl Processor for FakeTranscriber {
    fn id(&self) -> &str {
        "transcribe"
    }

    fn process(
        &self,
        ctx: &ProcessorCtx<'_>,
        _job: &Job,
    ) -> std::result::Result<Vec<ProposedOutput>, String> {
        let mut document: serde_json::Value =
            serde_json::from_str(&ctx.subject_payload().map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        if document.get("media").and_then(|v| v.as_str()) != Some("wav") {
            return Ok(Vec::new()); // only audio transcribes
        }
        let bytes = ctx.subject_blob().map_err(|e| e.to_string())?;
        let text = format!(
            "[fake transcript of {} bytes, sha {}]",
            bytes.len(),
            &ids::sha256_hex(&bytes)[..12]
        );
        document["text"] = serde_json::Value::String(text);
        Ok(vec![ProposedOutput {
            // The revised document is still the subject's own, so it keeps
            // the subject's type; both fields are ignored either way.
            type_id: crate::capture::SOURCE_TYPE.to_string(),
            type_schema: 1,
            payload: document.to_string(),
            target: OutputTarget::ReviseSubject,
            expected_head: None,
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

    fn subject_id(store: &Store) -> String {
        store
            .conn()
            .query_row(
                "SELECT object_id FROM objects WHERE type_id = 'org.memory.source'",
                [],
                |row| row.get(0),
            )
            .expect("the capture")
    }

    fn revisions_on(store: &Store, object_id: &str) -> i64 {
        store.revision_count(object_id).expect("count")
    }

    #[test]
    fn a_wav_capture_becomes_the_text_of_its_own_note() {
        let (_dir, store) = wav_captured();
        let summary = store.run_pending_jobs(&[&FakeTranscriber], 1_000, 30_000).expect("run");
        assert_eq!(summary.completed, 1);
        assert_eq!(summary.outputs, 1);

        let source_id = subject_id(&store);
        assert_eq!(
            revisions_on(&store, &source_id),
            2,
            "the capture revision plus the transcription revision"
        );
        let (origin, payload): (String, String) = store
            .conn()
            .query_row(
                "SELECT r.origin, r.payload FROM heads h
                 JOIN revisions r ON r.revision_id = h.revision_id
                 WHERE h.object_id = ?1",
                [&source_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("the new head");
        assert_eq!(origin, "processor:transcribe");
        let document: serde_json::Value = serde_json::from_str(&payload).expect("json");
        assert!(document["text"].as_str().expect("text").starts_with("[fake transcript"));
        assert_eq!(document["media"], "wav", "the revision keeps the capture's own facts");
        assert_eq!(
            store.conn().query_row("SELECT COUNT(*) FROM objects", [], |r| r.get::<_, i64>(0)),
            Ok(1),
            "one spoken thought is one object"
        );

        // The note is the capture's own, in Captures, with the words as
        // its body — no audio embed line survives (ADR 0013).
        store.project().expect("project");
        let rel: String = store
            .conn()
            .query_row("SELECT rel_path FROM projections WHERE object_id = ?1", [&source_id], |r| {
                r.get(0)
            })
            .expect("projection");
        assert!(rel.starts_with("Captures/"), "the note lives with its capture: {rel}");
        let note = std::fs::read_to_string(store.vault().join(&rel)).expect("note");
        assert!(note.contains("gilune-blob: "), "the audio stays linked as metadata");
        assert!(!note.contains("![["), "no embed line remains");
        let body = note.rsplit_once("---\n").expect("front matter").1;
        assert_eq!(body, document["text"].as_str().expect("text"));
        assert!(!store.vault().join("Transcripts").exists());
    }

    #[test]
    fn revising_the_subject_is_idempotent_across_redelivery() {
        let (_dir, store) = wav_captured();
        store.run_pending_jobs(&[&FakeTranscriber], 1_000, 1_000).expect("first run");
        // Force the completed job back to pending, as a redelivery would.
        store
            .conn()
            .execute("UPDATE jobs SET state = 'pending', not_before_ms = 0", [])
            .expect("redeliver");
        let second =
            store.run_pending_jobs(&[&FakeTranscriber], 60_000, 30_000).expect("second run");
        assert_eq!(second.skipped_duplicate, 1);
        assert_eq!(second.outputs, 0);
        assert_eq!(revisions_on(&store, &subject_id(&store)), 2, "one revision, not two");
    }

    #[test]
    fn the_head_and_the_document_a_processor_reads_are_always_one_revisions() {
        // The adversarial ordering, and it is silent when it goes wrong.
        // A processor reads the document, then reads the head, and hands
        // the head back as `expected_head` so a revision that landed while
        // its engine ran refuses its output. A revision landing *between*
        // those two reads gives it a pair that describes no revision that
        // ever existed — the old document under the new head — and the
        // compare-and-set then passes, because the head really is current.
        // The newer answer is overwritten by work done against the older
        // one, and nothing anywhere says so.
        let (_dir, store) = wav_captured();
        let source_id = subject_id(&store);
        let ctx = ProcessorCtx::new(&store, source_id.clone());

        // Read the payload first, as the old two-call shape did.
        let payload_first = ctx.subject_payload().expect("payload");
        // Something lands: a person typing, or a second engine finishing.
        let conn = store.conn();
        conn.execute_batch("BEGIN IMMEDIATE;").expect("begin");
        crate::revisions::add_revision(
            conn,
            &source_id,
            "landed-while-it-ran",
            &serde_json::json!({ "text": "what somebody else wrote" }).to_string(),
            "external-edit",
            2_000,
            crate::revisions::Announce::Silent,
        )
        .expect("a revision lands between the two reads");
        conn.execute_batch("COMMIT;").expect("commit");
        // And only now the head.
        let head_second = ctx.subject_head().expect("head");

        assert_eq!(
            head_second.as_deref(),
            Some("landed-while-it-ran"),
            "the second read sees the newer revision, which is the whole problem"
        );

        // The shipped accessor reads one head row, so the pair it answers
        // with belongs to one revision and cannot straddle a write.
        let (head, payload) = ctx.subject_head_and_payload().expect("both at once");
        assert_eq!(head.as_deref(), Some("landed-while-it-ran"));
        assert_ne!(payload, payload_first, "the document must be the one that head actually holds");
        assert!(payload.contains("what somebody else wrote"));

        // And the pair the interleaved reads produced is refused at commit
        // rather than silently applied: the head it names is stale by the
        // time the transaction runs.
        let job = Job {
            job_id: "job-1".to_string(),
            subscription_id: "transcribe".to_string(),
            event_id: "event-1".to_string(),
            event_type: crate::capture::SOURCE_READY_EVENT.to_string(),
            subject_id: source_id.clone(),
            payload: String::new(),
            attempts: 0,
        };
        crate::revisions::heads(store.conn(), &source_id).expect("heads");
        let stale = store.deliver_outputs(
            "transcribe",
            &job,
            &[ProposedOutput {
                type_id: crate::capture::SOURCE_TYPE.to_string(),
                type_schema: 1,
                payload: payload_first,
                target: OutputTarget::ReviseSubject,
                // The head as it was when the *document* was read — which
                // is what an honest single-transaction read would have
                // given back, and what the interleaved pair lost.
                expected_head: Some("a-head-that-has-been-superseded".to_string()),
            }],
        );
        assert!(
            matches!(stale, Err(KernelError::SubjectMoved { .. })),
            "the compare-and-set must refuse a stale head: {stale:?}"
        );
    }

    #[test]
    fn a_subject_that_moved_skips_that_job_and_the_queue_carries_on() {
        // The compare-and-set is the feature working: a slow processor
        // declines to overwrite an answer that landed while its engine was
        // running. It came back out of this loop as an `Err`, which turned
        // "one note was overtaken" into "the whole work pass failed" — the
        // rest of the queue never ran, every caller reported a failure, and
        // the job kept its lease and did it again next time.
        //
        // A processor that always names a head nobody has: every delivery
        // is a subject that moved.
        struct Stale;
        impl Processor for Stale {
            fn id(&self) -> &str {
                "transcribe"
            }
            fn process(
                &self,
                _ctx: &ProcessorCtx<'_>,
                _job: &Job,
            ) -> std::result::Result<Vec<ProposedOutput>, String> {
                Ok(vec![ProposedOutput {
                    type_id: crate::capture::SOURCE_TYPE.to_string(),
                    type_schema: 1,
                    payload: serde_json::json!({ "text": "written over a head that moved" })
                        .to_string(),
                    target: OutputTarget::ReviseSubject,
                    expected_head: Some("a-revision-nobody-has".to_string()),
                }])
            }
        }

        let (_dir, store) = wav_captured();
        // A second capture, so there is a queue rather than one job: what
        // matters is that the first job's refusal does not take the second
        // one down with it.
        store.capture_bytes("wav", &[9u8, 8, 7, 6, 5]).expect("a second capture");
        assert_eq!(
            store.conn().query_row("SELECT COUNT(*) FROM jobs", [], |r| r.get::<_, i64>(0)),
            Ok(2),
            "two captures, two jobs"
        );

        let summary = store
            .run_pending_jobs(&[&Stale], 1_000, 30_000)
            .expect("a subject that moved is an outcome, never an error out of the loop");
        assert_eq!(summary.skipped_moved, 2, "both jobs were overtaken, both are done");
        assert_eq!(summary.completed, 0);
        assert_eq!(summary.failed, 0, "declining to overwrite is not a failure");
        assert_eq!(summary.outputs, 0, "and nothing was written");
        assert_eq!(
            store.conn().query_row("SELECT COUNT(*) FROM jobs WHERE state = 'done'", [], |r| {
                r.get::<_, i64>(0)
            }),
            Ok(2),
            "a job nothing is left to write for is done, not leased until it retries"
        );

        // And a healthy processor afterwards has nothing left to run: the
        // jobs really did complete rather than being parked.
        let after = store.run_pending_jobs(&[&FakeTranscriber], 60_000, 30_000).expect("run");
        assert_eq!(after.completed, 0);
        assert_eq!(after.outputs, 0);
    }

    #[test]
    fn a_new_object_output_still_commits_with_provenance() {
        struct Interpreter;
        impl Processor for Interpreter {
            fn id(&self) -> &str {
                "transcribe"
            }
            fn process(
                &self,
                _ctx: &ProcessorCtx<'_>,
                _job: &Job,
            ) -> std::result::Result<Vec<ProposedOutput>, String> {
                Ok(vec![ProposedOutput {
                    type_id: "org.memory.summary".to_string(),
                    type_schema: 1,
                    payload: serde_json::json!({ "text": "a summary" }).to_string(),
                    target: OutputTarget::NewObject,
                    expected_head: None,
                }])
            }
        }
        let (_dir, store) = wav_captured();
        let source_id = subject_id(&store);
        assert_eq!(store.run_pending_jobs(&[&Interpreter], 1_000, 30_000).expect("run").outputs, 1);
        let provenance: i64 = store
            .conn()
            .query_row(
                "SELECT COUNT(*) FROM provenance WHERE relation = 'derived-from' AND target_id = ?1",
                [&source_id],
                |r| r.get(0),
            )
            .expect("provenance");
        assert_eq!(provenance, 1);
        assert_eq!(revisions_on(&store, &source_id), 1, "the subject is untouched");
        let kinds: Vec<String> = {
            let mut stmt =
                store.conn().prepare("SELECT kind FROM operations ORDER BY op_seq").expect("stmt");
            stmt.query_map([], |row| row.get(0))
                .expect("query")
                .collect::<std::result::Result<_, _>>()
                .expect("rows")
        };
        // A capture and a processor's derived object are the same kind of
        // operation; they differ in payload, not in name (ADR 0019 § 6).
        assert_eq!(
            kinds,
            vec![
                crate::operations::OBJECT_CREATED.to_string(),
                crate::operations::OBJECT_CREATED.to_string()
            ]
        );
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
    fn a_text_capture_is_left_exactly_as_it_was() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.subscribe("transcribe", crate::capture::SOURCE_READY_EVENT).expect("subscribe");
        store.capture_bytes("txt", b"just text").expect("capture");
        let summary = store.run_pending_jobs(&[&FakeTranscriber], 1_000, 30_000).expect("run");
        assert_eq!(summary.completed, 1);
        assert_eq!(summary.outputs, 0);
        assert_eq!(revisions_on(&store, &subject_id(&store)), 1);
    }
}
