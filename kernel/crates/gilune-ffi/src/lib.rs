//! The application boundary. Everything the shells may call crosses
//! here, and nothing else does: kernel implementation types never leak
//! (ADR 0001). The boundary owns the threading policy — shells call from
//! any thread; the store sits behind one lock and the kernel crate stays
//! lock-free by design. Bindings are generated per target from that
//! target's own binary (07-compatibility).

use std::sync::{Arc, Mutex};

uniffi::setup_scaffolding!();

/// Errors as the shells see them. A deliberately flat, stable surface
/// over `gilune_kernel::KernelError`; variants are added, not reshaped.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum GiluneError {
    #[error("storage: {detail}")]
    Storage { detail: String },
    #[error("vault unusable: {detail}")]
    VaultUnusable { detail: String },
    #[error("incompatible: {detail}")]
    Incompatible { detail: String },
    #[error("invalid: {detail}")]
    Invalid { detail: String },
}

impl From<gilune_kernel::KernelError> for GiluneError {
    fn from(e: gilune_kernel::KernelError) -> Self {
        use gilune_kernel::KernelError as K;
        match e {
            K::VaultUnusable { .. } => GiluneError::VaultUnusable { detail: e.to_string() },
            K::SchemaTooNew { .. } => GiluneError::Incompatible { detail: e.to_string() },
            K::NoSuchJob { .. } => GiluneError::Invalid { detail: e.to_string() },
            K::Storage(_) | K::Io { .. } | K::Encoding(_) => {
                GiluneError::Storage { detail: e.to_string() }
            }
        }
    }
}

type FfiResult<T> = Result<T, GiluneError>;

#[derive(Debug, Clone, uniffi::Record)]
pub struct CaptureReceipt {
    pub source_id: String,
    pub blob_id: String,
    pub rel_path: String,
    pub sha256: String,
    pub bytes: u64,
}

impl From<gilune_kernel::capture::CaptureReceipt> for CaptureReceipt {
    fn from(r: gilune_kernel::capture::CaptureReceipt) -> Self {
        CaptureReceipt {
            source_id: r.source_id,
            blob_id: r.blob_id,
            rel_path: r.rel_path,
            sha256: r.sha256,
            bytes: r.bytes,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GiluneStatus {
    pub sources: i64,
    pub blobs_ready: i64,
    pub blobs_committed: i64,
    pub events: i64,
    pub jobs_pending: i64,
    pub jobs_quarantined: i64,
    pub operations: i64,
    pub projections: i64,
    pub proposals_open: i64,
    pub quarantined_files: i64,
    pub import_candidates: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProjectSummary {
    pub written: i64,
    pub unchanged: i64,
    pub held_back: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReconcileSummary {
    pub edits: i64,
    pub renames: i64,
    pub tombstone_proposals: i64,
    pub imports: i64,
    pub quarantined: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceSummary {
    pub object_id: String,
    pub created_ms: i64,
    pub blob_rel_path: Option<String>,
    pub projection_rel_path: Option<String>,
    pub preview: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TombstoneProposal {
    pub proposal_id: String,
    pub object_id: String,
}

#[cfg(feature = "whisper")]
mod whisper;

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProcessingSummary {
    pub completed: i64,
    pub skipped_duplicate: i64,
    pub failed: i64,
    pub outputs: i64,
}

#[derive(uniffi::Object)]
pub struct Gilune {
    store: Mutex<gilune_kernel::store::Store>,
    processors: Mutex<Vec<Box<dyn gilune_kernel::processor::Processor>>>,
}

impl Gilune {
    fn store(&self) -> std::sync::MutexGuard<'_, gilune_kernel::store::Store> {
        // A poisoned lock means a panicked kernel call; the data behind it
        // is transactional, so continuing is safe and loses nothing.
        self.store.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
impl Gilune {
    /// Open (or create) the vault at `dir`, running migrations and
    /// startup recovery.
    #[uniffi::constructor]
    pub fn open(dir: String) -> FfiResult<Arc<Self>> {
        let store = gilune_kernel::store::Store::open(dir)?;
        Ok(Arc::new(Self {
            store: Mutex::new(store),
            processors: Mutex::new(Vec::new()),
        }))
    }

    /// Enable local speech-to-text with a Whisper model file. Desktop
    /// builds only (`whisper` feature); Android v0 captures and lets a
    /// paired coordinator transcribe (threat-model decision 1).
    pub fn enable_whisper(&self, model_path: String) -> FfiResult<()> {
        #[cfg(feature = "whisper")]
        {
            let transcriber = whisper::WhisperTranscriber::load(&model_path)
                .map_err(|detail| GiluneError::Invalid { detail })?;
            self.store()
                .subscribe("transcribe", gilune_kernel::capture::SOURCE_READY_EVENT)?;
            self.processors
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(Box::new(transcriber));
            Ok(())
        }
        #[cfg(not(feature = "whisper"))]
        {
            let _ = model_path;
            Err(GiluneError::Invalid {
                detail: "this build has no local transcription".into(),
            })
        }
    }

    /// Run pending processing jobs with whatever processors this build
    /// registered. A no-op when none are (Android v0).
    pub fn run_processing(&self) -> FfiResult<ProcessingSummary> {
        let processors = self.processors.lock().unwrap_or_else(|e| e.into_inner());
        let refs: Vec<&dyn gilune_kernel::processor::Processor> =
            processors.iter().map(|p| p.as_ref()).collect();
        let s = self
            .store()
            .run_pending_jobs(&refs, gilune_kernel::ids::now_ms(), 60_000)?;
        Ok(ProcessingSummary {
            completed: s.completed,
            skipped_duplicate: s.skipped_duplicate,
            failed: s.failed,
            outputs: s.outputs,
        })
    }

    pub fn schema_version(&self) -> FfiResult<i64> {
        Ok(self.store().schema_version()?)
    }

    pub fn device_id(&self) -> FfiResult<String> {
        Ok(self.store().device_id()?)
    }

    pub fn vault_path(&self) -> String {
        self.store().vault().to_string_lossy().to_string()
    }

    /// Capture a text thought. Durably committed before this returns.
    pub fn capture_text(&self, text: String) -> FfiResult<CaptureReceipt> {
        Ok(self.store().capture_bytes("txt", text.as_bytes())?.into())
    }

    /// Begin a streamed capture (audio and other media): chunks cross the
    /// boundary one at a time; nothing is held whole in memory.
    pub fn begin_capture(self: Arc<Self>, extension: String) -> FfiResult<Arc<CaptureWriter>> {
        let stage = self.store().begin_capture(&extension)?;
        Ok(Arc::new(CaptureWriter {
            gilune: self.clone(),
            stage: Mutex::new(Some(stage)),
        }))
    }

    pub fn project(&self) -> FfiResult<ProjectSummary> {
        let s = self.store().project()?;
        Ok(ProjectSummary { written: s.written, unchanged: s.unchanged, held_back: s.held_back })
    }

    pub fn reconcile(&self) -> FfiResult<ReconcileSummary> {
        let s = self.store().reconcile()?;
        Ok(ReconcileSummary {
            edits: s.edits,
            renames: s.renames,
            tombstone_proposals: s.tombstone_proposals,
            imports: s.imports,
            quarantined: s.quarantined,
        })
    }

    pub fn status(&self) -> FfiResult<GiluneStatus> {
        let s = self.store().status()?;
        Ok(GiluneStatus {
            sources: s.sources,
            blobs_ready: s.blobs_ready,
            blobs_committed: s.blobs_committed,
            events: s.events,
            jobs_pending: s.jobs_pending,
            jobs_quarantined: s.jobs_quarantined,
            operations: s.operations,
            projections: s.projections,
            proposals_open: s.proposals_open,
            quarantined_files: s.quarantined_files,
            import_candidates: s.import_candidates,
        })
    }

    pub fn recent_sources(&self, limit: u32) -> FfiResult<Vec<SourceSummary>> {
        Ok(self
            .store()
            .recent_sources(limit)?
            .into_iter()
            .map(|s| SourceSummary {
                object_id: s.object_id,
                created_ms: s.created_ms,
                blob_rel_path: s.blob_rel_path,
                projection_rel_path: s.projection_rel_path,
                preview: s.preview,
            })
            .collect())
    }

    pub fn open_proposals(&self) -> FfiResult<Vec<TombstoneProposal>> {
        Ok(self
            .store()
            .open_proposals()?
            .into_iter()
            .map(|(proposal_id, object_id)| TombstoneProposal { proposal_id, object_id })
            .collect())
    }

    pub fn resolve_tombstone(&self, proposal_id: String, accept: bool) -> FfiResult<()> {
        Ok(self.store().resolve_tombstone(&proposal_id, accept)?)
    }
}

/// A streamed capture in progress. Finish commits durably; abandoning the
/// writer leaves an orphan that recovery preserves in lost+found.
#[derive(uniffi::Object)]
pub struct CaptureWriter {
    gilune: Arc<Gilune>,
    stage: Mutex<Option<gilune_kernel::capture::CaptureStage>>,
}

#[uniffi::export]
impl CaptureWriter {
    pub fn write_chunk(&self, chunk: Vec<u8>) -> FfiResult<()> {
        let mut guard = self.stage.lock().unwrap_or_else(|e| e.into_inner());
        let stage = guard
            .as_mut()
            .ok_or(GiluneError::Invalid { detail: "capture already finished".into() })?;
        stage.write_chunk(&chunk)?;
        Ok(())
    }

    pub fn finish(&self) -> FfiResult<CaptureReceipt> {
        let stage = self
            .stage
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .ok_or(GiluneError::Invalid { detail: "capture already finished".into() })?;
        Ok(stage.finish(&self.gilune.store())?.into())
    }
}

#[uniffi::export]
pub fn kernel_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
