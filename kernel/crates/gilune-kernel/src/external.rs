//! The external processor route (ADR 0018 § 2): an implementation that
//! lives outside the kernel — in the host application, in whatever
//! language it happens to be written — claims a job, reads the subject
//! exactly as a Rust processor reads it, and proposes outputs that take
//! the same transaction (ADR 0013's 2026-09-02 amendment). The route adds
//! no semantics. It is the processor contract in a different transport,
//! and every guarantee it carries is one the shipped machinery already
//! makes: the lease in `jobs.rs`, the run record in `processing_runs`,
//! the derived IDs in `commit_outputs`, the `s.enabled = 1` gate.
//!
//! **What it deliberately is not: a write door.** The only thing
//! submittable is an output for the subject of a job whose claim you
//! hold, and a claim is the kernel's to hand out. There is no route here
//! to revise an arbitrary object, which is precisely what keeps this from
//! becoming the general write side ADR 0013 rejected.
//!
//! Registration is *not* recorded here. Which host process carries an
//! implementation is a fact about that process, not about the vault, so
//! it lives in the boundary for the life of a handle. A registration row
//! would outlive the process that meant it and become a lie.

use crate::crashpoints::crashpoint;
use crate::jobs::Job;
use crate::processor::{BlobFacts, Delivery, ProcessorCtx, ProposedOutput};
use crate::store::Store;
use crate::{ids, KernelError, Result};
use std::io::Read;

/// How much of a blob crosses at once. Matches the Android recorder's
/// sink in the other direction; nothing is ever held whole.
pub const CHUNK_BYTES: usize = 64 * 1024;

/// One claimed job as an external implementation sees it — what a
/// `ProcessorCtx` carries, flattened for a boundary crossing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExternalClaim {
    pub job: Job,
    /// The subject's head document at the moment of the claim.
    pub subject_payload: String,
    /// **Which** revision that document was — the head at the claim.
    /// A claimant that hands it back as an output's `expected_head` is
    /// refused rather than overwriting an answer that landed while its
    /// engine was running. `None` only for a subject with no head at all.
    pub subject_head: Option<String>,
    /// `None` when the subject has no preserved bytes.
    pub blob: Option<BlobFacts>,
}

impl Store {
    /// Claim one job for an externally-implemented processor, under the
    /// shipped lease. A claimant that dies holds the job until its lease
    /// expires and no longer — abandoned-claim recovery needs no new
    /// column because at-least-once already provides it.
    pub fn claim_external_job(
        &self,
        processor_id: &str,
        now_ms: i64,
        lease_ms: i64,
    ) -> Result<Option<ExternalClaim>> {
        let Some(job) = self.claim_next_job_for(processor_id, now_ms, lease_ms)? else {
            return Ok(None);
        };
        let ctx = ProcessorCtx::new(self, job.subject_id.clone());
        // One read for both, deliberately. A revision landing between a
        // payload read and a head read would hand this claimant the old
        // document under the new head — and its `expected_head` would then
        // *pass*, writing stale work over the newer answer the check exists
        // to protect. `subject_head_and_payload` reads one head row.
        let (subject_head, subject_payload) = ctx.subject_head_and_payload()?;
        let claim = ExternalClaim {
            subject_payload,
            subject_head,
            blob: ctx.subject_blob_facts()?,
            job,
        };
        // Held: the lease is taken, nothing is written. Recovery is the
        // lease expiring and the job being claimed again.
        crashpoint("external.after_claim");
        Ok(Some(claim))
    }

    /// Open the claimed subject's preserved bytes for streaming. The
    /// digest is checked over the whole file **before the first chunk
    /// crosses**, so `processor.rs`'s promise — a processor never sees
    /// bytes the kernel cannot vouch for — survives the crossing intact.
    pub fn open_claim_blob(&self, claim: &ExternalClaim) -> Result<BlobStream> {
        let facts = claim.blob.clone().ok_or_else(|| KernelError::Corrupt {
            detail: format!("{} has no blob", claim.job.subject_id),
        })?;
        BlobStream::open(self, &claim.job.subject_id, facts)
    }

    /// Commit an external implementation's proposed outputs through the
    /// one output transaction. Refused when the processor's plugin stopped
    /// being enabled while the work was in flight: a claim is honoured
    /// only for an enabled plugin, and the window between claim and submit
    /// is exactly where that can change (ADR 0018 § 2).
    pub fn submit_external_outputs(
        &self,
        processor_id: &str,
        job: &Job,
        outputs: &[ProposedOutput],
    ) -> Result<Delivery> {
        self.require_claimable_processor(processor_id)?;
        self.deliver_outputs(processor_id, job, outputs)
    }

    /// Report an external implementation's failure. Deliberately the very
    /// same `fail_job` a Rust processor's `Err` takes: backoff, then
    /// quarantine at `MAX_ATTEMPTS`. An app-side engine that throws is
    /// quarantined like a bundled one rather than retried forever.
    ///
    /// One guard, and the Rust route now has the same one: a claimant can
    /// be holding a stale claim for work another claimant already landed,
    /// and a late failure report must not resurrect a finished job, so if
    /// the run exists the job is completed instead. `run_pending_jobs`
    /// asks the same question before it runs a processor at all, which is
    /// how a redelivery after a post-commit crash completes rather than
    /// being failed and eventually quarantined. Both routes therefore
    /// answer "this event has already been processed" identically; the
    /// only difference is where the answer is needed, because an external
    /// claimant reports its failure after the fact.
    pub fn fail_external_job(
        &self,
        processor_id: &str,
        job: &Job,
        now_ms: i64,
        message: &str,
    ) -> Result<()> {
        if self.has_processing_run(processor_id, &job.event_id)? {
            self.complete_job(&job.job_id)?;
            return Ok(());
        }
        self.fail_job(&job.job_id, now_ms, message)
    }

    /// Whether this processor already recorded a run for this event —
    /// the exactly-once question, asked directly.
    pub fn has_processing_run(&self, processor_id: &str, event_id: &str) -> Result<bool> {
        let key = crate::processor::idempotency_key(processor_id, event_id);
        let hit: Option<i64> = self
            .conn()
            .query_row(
                "SELECT 1 FROM processing_runs WHERE processor_id = ?1 AND idempotency_key = ?2",
                [processor_id, key.as_str()],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        Ok(hit.is_some())
    }

    /// The gate `claim_next_job` gets for free through `s.enabled = 1`,
    /// asked again at submit time. The subscription row is the single
    /// source of truth — `plugins::apply_subscription` keeps it in step
    /// with enable, disable and removal — so this cannot disagree with
    /// what the claim query would have said.
    fn require_claimable_processor(&self, processor_id: &str) -> Result<()> {
        let enabled: Option<i64> = self
            .conn()
            .query_row(
                "SELECT enabled FROM subscriptions WHERE subscription_id = ?1",
                [processor_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        match enabled {
            Some(1) => Ok(()),
            _ => Err(KernelError::ProcessorNotClaimable {
                processor_id: processor_id.to_string(),
            }),
        }
    }
}

/// Diagnostics for tooling and the crash harness: the questions someone
/// debugging the route asks, answered without handing out a SQL handle.
impl Store {
    /// How many processing runs a processor has recorded. Exactly-once is
    /// this being 1 however many times the job was delivered.
    pub fn processing_run_count(&self, processor_id: &str) -> Result<i64> {
        Ok(self.conn().query_row(
            "SELECT COUNT(*) FROM processing_runs WHERE processor_id = ?1",
            [processor_id],
            |row| row.get(0),
        )?)
    }

    /// The origin recorded on an object's current head revision — who
    /// wrote the words a person is reading. With concurrent heads this is
    /// the head a reader sees, by the one ordering every reader uses.
    pub fn head_origin(&self, object_id: &str) -> Result<Option<String>> {
        self.conn()
            .query_row(
                &format!(
                    "SELECT r.origin FROM heads h
                     JOIN revisions r ON r.revision_id = h.revision_id
                     WHERE h.object_id = ?1 {} LIMIT 1",
                    crate::revisions::newest_head_first("r")
                ),
                [object_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(KernelError::from(e)),
            })
    }

    /// The state of the job a subject's event created for a processor.
    pub fn job_state_for(&self, subject_id: &str, processor_id: &str) -> Result<Option<String>> {
        self.conn()
            .query_row(
                "SELECT j.state FROM jobs j
                 JOIN events e ON e.event_id = j.event_id
                 WHERE e.subject_id = ?1 AND j.subscription_id = ?2",
                [subject_id, processor_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(KernelError::from(e)),
            })
    }
}

/// A claimed subject's bytes, handed over a chunk at a time and never
/// held whole (ADR 0001's boundary rules; the mirror image of streamed
/// capture). Verified twice on purpose: once over the whole file before
/// anything crosses, and again as a running hash while it does, so a file
/// that changes underneath a slow reader is caught rather than delivered.
pub struct BlobStream {
    file: std::fs::File,
    facts: BlobFacts,
    hasher: sha2::Sha256,
    read: u64,
    drained: bool,
}

impl BlobStream {
    fn open(store: &Store, subject_id: &str, facts: BlobFacts) -> Result<Self> {
        let path = store.vault().join(&facts.rel_path);
        let mut file =
            std::fs::File::open(&path).map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        // The verification pass: streamed, so a large blob is never
        // resident even to be checked. The kernel's one hashing loop —
        // there were three of these, and the day one of them grew a
        // whole-file read is the day a recording stopped fitting.
        let (digest, total) = crate::blobs::digest_reader(&mut file)
            .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        if digest != facts.sha256 || total != facts.bytes {
            return Err(KernelError::VaultUnusable {
                reason: format!("blob for {subject_id} fails its digest"),
            });
        }
        // Rewind for the handout pass rather than reopening: the same
        // handle cannot be swapped for another file underneath us.
        use std::io::Seek;
        file.rewind().map_err(|e| KernelError::Io { path, source: e })?;
        Ok(Self {
            file,
            facts,
            hasher: <sha2::Sha256 as sha2::Digest>::new(),
            read: 0,
            drained: false,
        })
    }

    /// What the caller should expect to receive, so a truncated read is
    /// detectable on the far side of the boundary too.
    pub fn facts(&self) -> &BlobFacts {
        &self.facts
    }

    /// The next chunk, or `None` once the whole verified blob has crossed.
    /// The final call is where the running hash is checked, so a reader
    /// that drains the stream has the kernel's word for what it received.
    pub fn next_chunk(&mut self) -> Result<Option<Vec<u8>>> {
        use sha2::Digest;
        if self.drained {
            return Ok(None);
        }
        let mut buffer = vec![0u8; CHUNK_BYTES];
        let n = self.file.read(&mut buffer).map_err(|e| KernelError::Io {
            path: std::path::PathBuf::from(&self.facts.rel_path),
            source: e,
        })?;
        if n == 0 {
            self.drained = true;
            let digest = hex::encode(std::mem::take(&mut self.hasher).finalize());
            if digest != self.facts.sha256 || self.read != self.facts.bytes {
                return Err(KernelError::VaultUnusable {
                    reason: format!(
                        "blob {} changed while it was being read",
                        self.facts.rel_path
                    ),
                });
            }
            return Ok(None);
        }
        buffer.truncate(n);
        self.hasher.update(&buffer);
        self.read += n as u64;
        Ok(Some(buffer))
    }
}

/// The digest of what an external implementation says it received, for a
/// caller that would rather ask than hash. Present so the streamed claim
/// is checkable from either side.
pub fn digest_of(bytes: &[u8]) -> String {
    ids::sha256_hex(bytes)
}

pub const FAKE_EXTERNAL_PLUGIN: &str = "org.memory.fake-external";
/// The stand-in's processor id. Deliberately **not** named after
/// transcription: the kernel names no domain type (constitution rule 4),
/// and a stand-in that exists to prove a transport called itself
/// `external-transcribe` — putting a domain word in kernel code, and
/// implying that this route is for speech when it is for any
/// interpretation at all.
pub const FAKE_EXTERNAL_PROCESSOR: &str = "external-interpret";

/// The stand-in *external* provider: a declaration whose implementation
/// deliberately does not exist in this binary as a `Processor`, so the
/// only way its jobs are ever done is the route in this module. Kept
/// beside `fake_transcriber_descriptor` and for the same reason — the
/// CLI and the crash harness must agree on one declaration, byte for
/// byte, across a process boundary.
pub fn fake_external_descriptor() -> crate::plugins::PluginDescriptor {
    crate::plugins::PluginDescriptor {
        plugin_id: FAKE_EXTERNAL_PLUGIN.to_string(),
        name: "Stand-in external processor (development)".to_string(),
        class: crate::plugins::PluginClass::Provider,
        profile: crate::plugins::PluginProfile::Bundled,
        required: false,
        processor_id: Some(FAKE_EXTERNAL_PROCESSOR.to_string()),
        subscribes: Some(crate::capture::SOURCE_READY_EVENT.to_string()),
        emits: vec![crate::revisions::REVISION_ADDED_EVENT.to_string()],
        capabilities: vec!["blobs.read".to_string(), "objects.write".to_string()],
        contributions: Vec::new(),
    }
}

/// Drive the whole route once, exactly as a host application drives it:
/// claim, read the subject's bytes chunk by chunk, propose one
/// revise-subject output. Deterministic, so a recovery failure is
/// distinguishable from provider variation. `None` when nothing waits.
///
/// This is the harness's app-side engine, and the CLI's — it exists so a
/// crash test can kill a real process partway through a real route.
pub fn run_external_once(
    store: &Store,
    now_ms: i64,
    lease_ms: i64,
) -> Result<Option<Delivery>> {
    let Some(claim) = store.claim_external_job(FAKE_EXTERNAL_PROCESSOR, now_ms, lease_ms)? else {
        return Ok(None);
    };
    use sha2::Digest;
    let mut document: serde_json::Value = serde_json::from_str(&claim.subject_payload)?;
    let mut bytes = 0u64;
    // Hashed as the chunks arrive. A host application is never obliged to
    // hold a whole recording to say what it received, and this stands in
    // for one — collecting the stream back into a `Vec` here would have
    // undone the streaming it exists to demonstrate.
    let mut hasher = sha2::Sha256::new();
    if claim.blob.is_some() {
        let mut stream = store.open_claim_blob(&claim)?;
        while let Some(chunk) = stream.next_chunk()? {
            bytes += chunk.len() as u64;
            hasher.update(&chunk);
        }
    }
    let digest = hex::encode(hasher.finalize());
    document["text"] = serde_json::Value::String(format!(
        "[external interpretation of {bytes} bytes, sha {}]",
        &digest[..12]
    ));
    let delivered = store.submit_external_outputs(
        FAKE_EXTERNAL_PROCESSOR,
        &claim.job,
        &[ProposedOutput {
            type_id: crate::capture::SOURCE_TYPE.to_string(),
            type_schema: 1,
            payload: document.to_string(),
            target: crate::processor::OutputTarget::ReviseSubject,
            expected_head: None,
        }],
    )?;
    Ok(Some(delivered))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capture::SOURCE_READY_EVENT;
    use crate::plugins::{PluginClass, PluginDescriptor, PluginProfile};
    use crate::processor::{OutputTarget, ProposedOutput};

    const PLUGIN: &str = "org.memory.moonshine";
    const PROCESSOR: &str = "moonshine-transcribe";

    fn descriptor() -> PluginDescriptor {
        PluginDescriptor {
            plugin_id: PLUGIN.to_string(),
            name: "Realtime transcription (external)".to_string(),
            class: PluginClass::Provider,
            profile: PluginProfile::Bundled,
            required: false,
            processor_id: Some(PROCESSOR.to_string()),
            subscribes: Some(SOURCE_READY_EVENT.to_string()),
            emits: vec![crate::revisions::REVISION_ADDED_EVENT.to_string()],
            capabilities: vec!["blobs.read".to_string(), "objects.write".to_string()],
            contributions: Vec::new(),
        }
    }

    /// A vault with the external provider installed and one wav waiting.
    fn ready() -> (tempfile::TempDir, Store, String) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.register_plugin(&descriptor()).expect("register");
        let receipt = store.capture_bytes("wav", &vec![7u8; 200_000]).expect("capture");
        (dir, store, receipt.source_id)
    }

    /// The whole route, as a host drives it: claim, stream, submit.
    fn transcribe(store: &Store, now_ms: i64, words: &str) -> Delivery {
        let claim = store
            .claim_external_job(PROCESSOR, now_ms, 30_000)
            .expect("claim")
            .expect("a job is waiting");
        let mut stream = store.open_claim_blob(&claim).expect("blob");
        let mut received = Vec::new();
        while let Some(chunk) = stream.next_chunk().expect("chunk") {
            received.extend_from_slice(&chunk);
        }
        assert_eq!(
            digest_of(&received),
            claim.blob.as_ref().expect("facts").sha256,
            "what crossed hashes to what the kernel promised"
        );
        let mut document: serde_json::Value =
            serde_json::from_str(&claim.subject_payload).expect("json");
        document["text"] = serde_json::Value::String(words.to_string());
        store
            .submit_external_outputs(
                PROCESSOR,
                &claim.job,
                &[ProposedOutput {
                    type_id: crate::capture::SOURCE_TYPE.to_string(),
                    type_schema: 1,
                    payload: document.to_string(),
                    target: OutputTarget::ReviseSubject,
                    expected_head: None,
                }],
            )
            .expect("submit")
    }

    #[test]
    fn a_claims_head_names_the_very_revision_whose_document_it_carries() {
        // The external route hands both across a boundary at once, so a
        // pair that straddled a write would be *stored* in the claimant's
        // process and handed back minutes later as `expected_head`. The
        // check would then pass against a head that was current when it was
        // read but never held the document the engine worked from.
        //
        // Both come from one head row, so the pair is one revision's — and
        // that is checkable from outside: the head the claim names must be
        // the revision whose payload the claim carries.
        let (_dir, store, source_id) = ready();
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("a job is waiting");
        let head = claim.subject_head.expect("a subject with a head names it");
        let payload_of_that_head: String = store
            .conn()
            .query_row("SELECT payload FROM revisions WHERE revision_id = ?1", [&head], |row| {
                row.get(0)
            })
            .expect("the named revision");
        assert_eq!(
            claim.subject_payload, payload_of_that_head,
            "the claim carried a document that its own head does not hold"
        );
        assert_eq!(
            crate::revisions::heads(store.conn(), &source_id).expect("heads")[0],
            head,
            "and it is the head this vault would render"
        );
    }

    #[test]
    fn the_route_lands_a_revision_indistinguishable_from_a_rust_processors() {
        let (_dir, store, source_id) = ready();
        let delivered = transcribe(&store, 1_000, "remember the milk");
        assert_eq!(delivered.outputs, 1);
        assert!(!delivered.skipped_duplicate);

        let (origin, payload): (String, String) = store
            .conn()
            .query_row(
                "SELECT r.origin, r.payload FROM heads h
                 JOIN revisions r ON r.revision_id = h.revision_id
                 WHERE h.object_id = ?1",
                [&source_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("head");
        assert_eq!(
            origin,
            format!("processor:{PROCESSOR}"),
            "the vault can name the engine that wrote these words"
        );
        let document: serde_json::Value = serde_json::from_str(&payload).expect("json");
        assert_eq!(document["text"], "remember the milk");
        assert_eq!(document["media"], "wav", "the revision keeps the capture's own facts");
        assert_eq!(store.revision_count(&source_id).expect("revisions"), 2);

        // Provenance is the kernel's, and a revise-subject output records
        // it as origin rather than a provenance row — exactly as ADR 0013
        // specifies for the Rust route.
        let runs: i64 = store
            .conn()
            .query_row(
                "SELECT COUNT(*) FROM processing_runs WHERE processor_id = ?1",
                [PROCESSOR],
                |r| r.get(0),
            )
            .expect("runs");
        assert_eq!(runs, 1);
        let kinds: Vec<String> = {
            let mut stmt =
                store.conn().prepare("SELECT kind FROM operations ORDER BY op_seq").expect("stmt");
            stmt.query_map([], |row| row.get(0))
                .expect("query")
                .collect::<std::result::Result<_, _>>()
                .expect("rows")
        };
        assert_eq!(
            kinds,
            vec![
                crate::operations::OBJECT_CREATED.to_string(),
                crate::operations::OBJECT_REVISED.to_string()
            ]
        );
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn a_blob_larger_than_a_chunk_crosses_in_pieces_and_is_never_held_whole() {
        let (_dir, store, _id) = ready();
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("job");
        let facts = claim.blob.clone().expect("a wav has bytes");
        assert_eq!(facts.bytes, 200_000);
        let mut stream = store.open_claim_blob(&claim).expect("blob");
        let mut chunks = 0;
        let mut total = 0u64;
        while let Some(chunk) = stream.next_chunk().expect("chunk") {
            assert!(chunk.len() <= CHUNK_BYTES);
            chunks += 1;
            total += chunk.len() as u64;
        }
        assert!(chunks > 1, "200 KB must not arrive in one copy: {chunks} chunk(s)");
        assert_eq!(total, facts.bytes, "a truncated read is detectable by the caller");
    }

    #[test]
    fn a_corrupt_blob_is_refused_before_a_single_byte_crosses() {
        let (_dir, store, _id) = ready();
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("job");
        let path = store.vault().join(&claim.blob.as_ref().expect("facts").rel_path);
        let mut bytes = std::fs::read(&path).expect("read");
        bytes[0] ^= 0xFF;
        std::fs::write(&path, &bytes).expect("corrupt it");

        assert!(
            matches!(store.open_claim_blob(&claim), Err(KernelError::VaultUnusable { .. })),
            "the kernel vouches for the bytes or hands out none"
        );
    }

    #[test]
    fn a_truncated_blob_is_refused_as_firmly_as_a_corrupt_one() {
        let (_dir, store, _id) = ready();
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("job");
        let path = store.vault().join(&claim.blob.as_ref().expect("facts").rel_path);
        let bytes = std::fs::read(&path).expect("read");
        std::fs::write(&path, &bytes[..bytes.len() / 2]).expect("truncate it");
        assert!(matches!(
            store.open_claim_blob(&claim),
            Err(KernelError::VaultUnusable { .. })
        ));
    }

    #[test]
    fn submitting_twice_writes_once() {
        let (_dir, store, source_id) = ready();
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("job");
        let output = |text: &str| {
            vec![ProposedOutput {
                type_id: crate::capture::SOURCE_TYPE.to_string(),
                type_schema: 1,
                payload: serde_json::json!({ "media": "wav", "text": text }).to_string(),
                target: OutputTarget::ReviseSubject,
                expected_head: None,
            }]
        };
        let first = store
            .submit_external_outputs(PROCESSOR, &claim.job, &output("first"))
            .expect("first submit");
        assert_eq!(first.outputs, 1);

        // The same claim, submitted again — a host that retried after a
        // reply it never saw.
        let second = store
            .submit_external_outputs(PROCESSOR, &claim.job, &output("second"))
            .expect("second submit");
        assert!(second.skipped_duplicate, "a completed run is skipped, not rewritten");
        assert_eq!(second.outputs, 0);
        assert_eq!(
            store.revision_count(&source_id).expect("revisions"),
            2,
            "the capture plus exactly one transcription"
        );
        assert_eq!(store.object_text(&source_id).expect("text").as_deref(), Some("first"));
    }

    #[test]
    fn two_claimants_of_one_job_produce_one_output() {
        let (_dir, store, source_id) = ready();
        // The first claimant takes the job and then stalls past its lease.
        let slow = store
            .claim_external_job(PROCESSOR, 1_000, 5_000)
            .expect("claim")
            .expect("job");
        // A second host claims it once the lease has expired — at-least-once
        // working exactly as designed.
        let quick = store
            .claim_external_job(PROCESSOR, 10_000, 30_000)
            .expect("reclaim")
            .expect("the lease expired, so the job is claimable again");
        assert_eq!(quick.job.job_id, slow.job.job_id);
        assert_eq!(quick.job.attempts, slow.job.attempts + 1);

        let output = |text: &str| {
            vec![ProposedOutput {
                type_id: crate::capture::SOURCE_TYPE.to_string(),
                type_schema: 1,
                payload: serde_json::json!({ "media": "wav", "text": text }).to_string(),
                target: OutputTarget::ReviseSubject,
                expected_head: None,
            }]
        };
        let quick_delivery = store
            .submit_external_outputs(PROCESSOR, &quick.job, &output("quick"))
            .expect("submit");
        assert_eq!(quick_delivery.outputs, 1);

        // The stalled claimant finally finishes. Its lease is long gone, but
        // it is not refused — at-least-once means a late submit is legal,
        // and idempotency, not a lease check, is what makes it harmless.
        let slow_delivery = store
            .submit_external_outputs(PROCESSOR, &slow.job, &output("slow"))
            .expect("late submit");
        assert!(slow_delivery.skipped_duplicate);
        assert_eq!(
            store.revision_count(&source_id).expect("revisions"),
            2,
            "two claimants, one output"
        );
        assert_eq!(store.object_text(&source_id).expect("text").as_deref(), Some("quick"));
    }

    #[test]
    fn a_submit_is_refused_once_the_plugin_is_disabled_mid_flight() {
        let (_dir, store, source_id) = ready();
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("job");
        store.set_plugin_enabled(PLUGIN, false).expect("disable while the work is in flight");

        let refused = store.submit_external_outputs(
            PROCESSOR,
            &claim.job,
            &[ProposedOutput {
                type_id: crate::capture::SOURCE_TYPE.to_string(),
                type_schema: 1,
                payload: serde_json::json!({ "text": "too late" }).to_string(),
                target: OutputTarget::ReviseSubject,
                expected_head: None,
            }],
        );
        assert!(matches!(refused, Err(KernelError::ProcessorNotClaimable { .. })));
        assert_eq!(
            store.revision_count(&source_id).expect("revisions"),
            1,
            "a disabled plugin writes nothing"
        );

        // Parked, not destroyed: enabling resumes the very same job.
        store.set_plugin_enabled(PLUGIN, true).expect("enable");
        let resumed = transcribe(&store, 60_000, "after the switch");
        assert_eq!(resumed.outputs, 1);
        assert_eq!(store.revision_count(&source_id).expect("revisions"), 2);
    }

    #[test]
    fn a_removed_plugins_processor_can_claim_nothing() {
        let (_dir, store, _id) = ready();
        store.remove_plugin(PLUGIN).expect("remove");
        assert_eq!(
            store.claim_external_job(PROCESSOR, 1_000, 30_000).expect("claim"),
            None,
            "a tombstone is not a subscription"
        );
    }

    #[test]
    fn failing_takes_the_same_backoff_and_quarantine_a_rust_processor_takes() {
        let (_dir, store, source_id) = ready();
        let mut now = 1_000;
        let mut claim = store
            .claim_external_job(PROCESSOR, now, 30_000)
            .expect("claim")
            .expect("job");
        for _ in 0..crate::jobs::MAX_ATTEMPTS - 1 {
            store.fail_external_job(PROCESSOR, &claim.job, now, "the engine threw").expect("fail");
            assert_eq!(store.job_state(&claim.job.job_id).expect("state"), "pending");
            now += 1_000_000;
            claim = store
                .claim_external_job(PROCESSOR, now, 30_000)
                .expect("claim")
                .expect("the job comes back");
        }
        store.fail_external_job(PROCESSOR, &claim.job, now, "the engine threw").expect("final fail");
        assert_eq!(
            store.job_state(&claim.job.job_id).expect("state"),
            "quarantined",
            "an app-side engine that throws is quarantined, not retried forever"
        );
        assert_eq!(store.revision_count(&source_id).expect("revisions"), 1);
        store.capture_bytes("txt", b"capture is never blocked").expect("capture");
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn a_failed_job_returns_to_the_queue_and_a_later_claim_succeeds() {
        let (_dir, store, source_id) = ready();
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("job");
        store.fail_external_job(PROCESSOR, &claim.job, 1_000, "no model yet").expect("fail");
        assert_eq!(store.revision_count(&source_id).expect("revisions"), 1);

        let delivered = transcribe(&store, 1_000_000, "the retry succeeds");
        assert_eq!(delivered.outputs, 1);
        assert_eq!(store.object_text(&source_id).expect("text").as_deref(), Some("the retry succeeds"));
    }

    #[test]
    fn a_late_failure_report_cannot_resurrect_work_that_already_landed() {
        let (_dir, store, source_id) = ready();
        // Two claimants again: the slow one's lease expires, the quick one
        // finishes the work, and then the slow one reports a failure.
        let slow = store
            .claim_external_job(PROCESSOR, 1_000, 1)
            .expect("claim")
            .expect("job");
        transcribe(&store, 10_000, "the quick one got there");

        store
            .fail_external_job(PROCESSOR, &slow.job, 20_000, "my engine threw")
            .expect("a late failure is accepted, not an error");

        assert_eq!(
            store.job_state_for(&source_id, PROCESSOR).expect("state").as_deref(),
            Some("done"),
            "a stale claimant's failure must not put a finished job back in the queue"
        );
        assert_eq!(
            store.object_text(&source_id).expect("text").as_deref(),
            Some("the quick one got there")
        );
        assert_eq!(store.revision_count(&source_id).expect("revisions"), 2);
    }

    #[test]
    fn the_local_run_loop_leaves_an_external_processors_jobs_alone() {
        let (_dir, store, _id) = ready();
        let external = vec![PROCESSOR.to_string()];
        // A host whose Rust processors do not include this id: without the
        // exclusion the job would be claimed here, find no implementation,
        // and burn an attempt and a lease on every pass.
        let summary = store
            .run_pending_jobs_excluding(&[], &external, 1_000, 30_000)
            .expect("run");
        assert_eq!(summary, crate::processor::ProcessingSummary::default());
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("the job is untouched and still first-attempt");
        assert_eq!(claim.job.attempts, 1);
    }

    #[test]
    fn a_run_loop_never_claims_a_subscription_it_cannot_run() {
        // The rule that replaced the exclusion list as the real defence,
        // pinned so that losing it is a test failure rather than a slow
        // mystery. A claim spends an attempt and takes a lease; a host
        // carrying no implementation for this id — the CLI's `work`, a
        // pass before the provider registered — must therefore claim
        // nothing at all, however often it runs.
        let (_dir, store, _id) = ready();
        for pass in 0..5 {
            let summary = store.run_pending_jobs(&[], 1_000 + pass, 30_000).expect("run");
            assert_eq!(summary, crate::processor::ProcessingSummary::default());
        }
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("the job was never leased away, so it is claimable now");
        assert_eq!(
            claim.job.attempts, 1,
            "five passes of a host that cannot run this work spent none of its attempts"
        );
    }

    #[test]
    fn a_new_object_output_from_the_external_route_carries_provenance() {
        let (_dir, store, source_id) = ready();
        let claim = store
            .claim_external_job(PROCESSOR, 1_000, 30_000)
            .expect("claim")
            .expect("job");
        store
            .submit_external_outputs(
                PROCESSOR,
                &claim.job,
                &[ProposedOutput {
                    type_id: "org.memory.summary".to_string(),
                    type_schema: 1,
                    payload: serde_json::json!({ "text": "a summary" }).to_string(),
                    target: OutputTarget::NewObject,
                    expected_head: None,
                }],
            )
            .expect("submit");
        let provenance: i64 = store
            .conn()
            .query_row(
                "SELECT COUNT(*) FROM provenance
                 WHERE relation = 'derived-from' AND target_id = ?1 AND processor_id = ?2",
                [&source_id, &PROCESSOR.to_string()],
                |r| r.get(0),
            )
            .expect("provenance");
        assert_eq!(provenance, 1, "the kernel writes provenance, whoever proposed the output");
        assert_eq!(store.revision_count(&source_id).expect("revisions"), 1, "subject untouched");
    }
}
