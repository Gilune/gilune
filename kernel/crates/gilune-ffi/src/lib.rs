//! The application boundary. Everything the shells may call crosses
//! here, and nothing else does: kernel implementation types never leak
//! (ADR 0001). The boundary owns the threading policy — shells call from
//! any thread; the store sits behind one lock and the kernel crate stays
//! lock-free by design. Bindings are generated per target from that
//! target's own binary (07-compatibility).

// Methods on `dyn Processor` need no trait import; only the whisper
// build's concrete transcriber does.
#[cfg(feature = "whisper")]
use gilune_kernel::processor::Processor;
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
            K::NoSuchJob { .. }
            | K::NoSuchPlugin { .. }
            | K::PluginRequired { .. }
            | K::UndeclaredCapability { .. } => GiluneError::Invalid { detail: e.to_string() },
            K::Storage(_) | K::Io { .. } | K::Encoding(_) | K::Corrupt { .. } => {
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

/// A plugin as the settings surface sees it (ADR 0011): what it is, what
/// it declares, what it has actually been granted, and what it adds to
/// the app. `ungranted` is what a person has still to agree to.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PluginInfo {
    pub plugin_id: String,
    pub name: String,
    pub class: String,
    pub profile: String,
    pub required: bool,
    pub state: String,
    pub processor_id: Option<String>,
    pub subscribes: Option<String>,
    pub emits: Vec<String>,
    pub capabilities: Vec<String>,
    pub granted: Vec<String>,
    pub ungranted: Vec<String>,
    pub contributions: Vec<PluginContribution>,
    pub settings: String,
}

/// One control a plugin adds to a host surface. Slot names are the
/// contract with the shell: `capture.actions`, `note.actions`,
/// `navigation`, `command.palette`, `settings.form`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PluginContribution {
    pub slot: String,
    pub id: String,
    pub label: String,
    /// Contribution-private payload, canonical JSON by convention and
    /// opaque to the kernel (ADR 0015 § 5). Defaulted so that a host
    /// declaring the three original strings keeps compiling.
    #[uniffi(default = "")]
    pub config: String,
}

/// What a host declares when it registers an implementation it carries.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PluginDeclaration {
    pub plugin_id: String,
    pub name: String,
    /// `domain`, `provider`, `adapter`, `projection` or `index`.
    pub class: String,
    /// `bundled` or `sandboxed`.
    pub profile: String,
    pub required: bool,
    pub processor_id: Option<String>,
    pub subscribes: Option<String>,
    pub emits: Vec<String>,
    pub capabilities: Vec<String>,
    pub contributions: Vec<PluginContribution>,
}

impl From<gilune_kernel::plugins::InstalledPlugin> for PluginInfo {
    fn from(p: gilune_kernel::plugins::InstalledPlugin) -> Self {
        PluginInfo {
            plugin_id: p.descriptor.plugin_id.clone(),
            name: p.descriptor.name.clone(),
            class: p.descriptor.class.as_str().to_string(),
            profile: p.descriptor.profile.as_str().to_string(),
            required: p.descriptor.required,
            state: p.state.as_str().to_string(),
            processor_id: p.descriptor.processor_id.clone(),
            subscribes: p.descriptor.subscribes.clone(),
            emits: p.descriptor.emits.clone(),
            capabilities: p.descriptor.capabilities.clone(),
            ungranted: p.ungranted(),
            granted: p.granted.clone(),
            contributions: p
                .descriptor
                .contributions
                .iter()
                .map(|c| PluginContribution {
                    slot: c.slot.clone(),
                    id: c.id.clone(),
                    label: c.label.clone(),
                    config: c.config.clone(),
                })
                .collect(),
            settings: p.settings,
        }
    }
}

impl TryFrom<PluginDeclaration> for gilune_kernel::plugins::PluginDescriptor {
    type Error = GiluneError;

    fn try_from(d: PluginDeclaration) -> Result<Self, GiluneError> {
        use gilune_kernel::plugins::{PluginClass, PluginProfile};
        let class = match d.class.as_str() {
            "domain" => PluginClass::Domain,
            "provider" => PluginClass::Provider,
            "adapter" => PluginClass::Adapter,
            "projection" => PluginClass::Projection,
            "index" => PluginClass::Index,
            other => {
                return Err(GiluneError::Invalid {
                    detail: format!("{other} is not a plugin class"),
                })
            }
        };
        let profile = match d.profile.as_str() {
            "bundled" => PluginProfile::Bundled,
            "sandboxed" => PluginProfile::Sandboxed,
            other => {
                return Err(GiluneError::Invalid {
                    detail: format!("{other} is not an execution profile"),
                })
            }
        };
        Ok(gilune_kernel::plugins::PluginDescriptor {
            plugin_id: d.plugin_id,
            name: d.name,
            class,
            profile,
            required: d.required,
            processor_id: d.processor_id,
            subscribes: d.subscribes,
            emits: d.emits,
            capabilities: d.capabilities,
            contributions: d
                .contributions
                .into_iter()
                .map(|c| gilune_kernel::plugins::Contribution {
                    slot: c.slot,
                    id: c.id,
                    label: c.label,
                    config: c.config,
                })
                .collect(),
        })
    }
}

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

    /// Stop running a processor in this process. Job claiming is already
    /// gated on the plugin's state, so this only releases what the
    /// implementation holds — a loaded model, for instance.
    fn unload_processor(&self, plugin_id: &str) -> FfiResult<()> {
        let Some(plugin) = self.store().plugin(plugin_id)? else { return Ok(()) };
        let Some(processor_id) = plugin.descriptor.processor_id else { return Ok(()) };
        self.processors
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .retain(|p| p.id() != processor_id);
        Ok(())
    }
}

/// The plugins this binary carries. Registered on every open: declaration
/// only, idempotent, and never resurrecting one the user removed. Which
/// implementations exist here is a build fact — a plugin's availability
/// varies by platform (ADR 0006) — so absence from this list is honest.
fn register_builtins(store: &gilune_kernel::store::Store) -> gilune_kernel::Result<()> {
    store.register_plugin(&gilune_kernel::vault::plugin_descriptor())?;
    #[cfg(feature = "whisper")]
    {
        // Host policy (the kernel grants nothing on its own): a bundled
        // plugin's LOCAL capabilities are consented by installing the
        // application, so they are granted once, on first install.
        // `network.request` is deliberately not here — leaving the
        // device always takes an explicit yes (ADR 0011). Nothing is
        // ever re-granted after a revoke or a removal.
        let fresh = store.plugin(whisper::PLUGIN_ID)?.is_none();
        let state = store.register_plugin(&whisper::descriptor())?;
        if fresh && state == gilune_kernel::plugins::PluginState::Enabled {
            store.grant_capability(whisper::PLUGIN_ID, "blobs.read")?;
            store.grant_capability(whisper::PLUGIN_ID, "objects.write")?;
        }
    }
    Ok(())
}

#[uniffi::export]
impl Gilune {
    /// Open (or create) the vault at `dir`, running migrations and
    /// startup recovery.
    #[uniffi::constructor]
    pub fn open(dir: String) -> FfiResult<Arc<Self>> {
        let store = gilune_kernel::store::Store::open(dir)?;
        register_builtins(&store)?;
        Ok(Arc::new(Self {
            store: Mutex::new(store),
            processors: Mutex::new(Vec::new()),
        }))
    }

    /// Load the local speech-to-text model. Desktop builds only
    /// (`whisper` feature); Android v0 captures and lets a paired
    /// coordinator transcribe (threat-model decision 1). Refused while
    /// the transcription plugin is disabled or removed — the registry,
    /// not the caller, decides what may run.
    pub fn enable_whisper(&self, model_path: String) -> FfiResult<()> {
        #[cfg(feature = "whisper")]
        {
            use gilune_kernel::plugins::PluginState;
            let state = self
                .store()
                .plugin(whisper::PLUGIN_ID)?
                .map(|p| p.state)
                .unwrap_or(PluginState::Removed);
            if state != PluginState::Enabled {
                return Err(GiluneError::Invalid {
                    detail: format!("transcription is {}", state.as_str()),
                });
            }
            // The plugin's settings document owns provider choices; an
            // explicit language skips whisper's detection pass (measured
            // 2026-08-31: ~40% of a short clip's wall time).
            let language = self
                .store()
                .plugin(whisper::PLUGIN_ID)?
                .and_then(|p| serde_json::from_str::<serde_json::Value>(&p.settings).ok())
                .and_then(|s| s.get("language").and_then(|v| v.as_str()).map(str::to_string))
                .unwrap_or_else(|| "auto".to_string());
            let transcriber = whisper::WhisperTranscriber::load(&model_path, &language)
                .map_err(|detail| GiluneError::Invalid { detail })?;
            let mut processors = self.processors.lock().unwrap_or_else(|e| e.into_inner());
            processors.retain(|p| p.id() != transcriber.id());
            processors.push(Box::new(transcriber));
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

    /// Begin a streamed *attached* capture (ADR 0013): as durable as any
    /// other, but it projects no note of its own because it belongs to
    /// one the user is already writing. Dictation records through this,
    /// then inserts `object_text` at the cursor when transcription lands.
    pub fn begin_attached_capture(
        self: Arc<Self>,
        extension: String,
    ) -> FfiResult<Arc<CaptureWriter>> {
        let stage = self.store().begin_attached_capture(&extension)?;
        Ok(Arc::new(CaptureWriter {
            gilune: self.clone(),
            stage: Mutex::new(Some(stage)),
        }))
    }

    /// The text of an object's current document, or `None` while there is
    /// none — a dictation not yet transcribed answers `None`, and so does
    /// an object that does not exist.
    pub fn object_text(&self, object_id: String) -> FfiResult<Option<String>> {
        Ok(self.store().object_text(&object_id)?)
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

    /// Declare an implementation this host carries — a platform adapter,
    /// for instance, whose code lives in the shell rather than here.
    /// Idempotent; returns the plugin's state, which is `removed` if the
    /// user removed it, so a caller knows not to initialise it.
    pub fn register_plugin(&self, declaration: PluginDeclaration) -> FfiResult<String> {
        let descriptor = gilune_kernel::plugins::PluginDescriptor::try_from(declaration)?;
        Ok(self.store().register_plugin(&descriptor)?.as_str().to_string())
    }

    pub fn plugins(&self) -> FfiResult<Vec<PluginInfo>> {
        Ok(self.store().installed_plugins()?.into_iter().map(Into::into).collect())
    }

    /// Turn a plugin off or on. Waiting work is parked, not destroyed.
    pub fn set_plugin_enabled(&self, plugin_id: String, enabled: bool) -> FfiResult<()> {
        self.store().set_plugin_enabled(&plugin_id, enabled)?;
        if !enabled {
            self.unload_processor(&plugin_id)?;
        }
        Ok(())
    }

    /// Remove a plugin: grants and settings go, waiting work is parked,
    /// and everything it created stays in the vault. Device-local assets
    /// it downloaded are the caller's to delete.
    pub fn remove_plugin(&self, plugin_id: String) -> FfiResult<()> {
        self.unload_processor(&plugin_id)?;
        Ok(self.store().remove_plugin(&plugin_id)?)
    }

    /// Re-install a removed plugin. Grants start empty again.
    pub fn reinstall_plugin(&self, plugin_id: String) -> FfiResult<()> {
        Ok(self.store().reinstall_plugin(&plugin_id)?)
    }

    /// Record consent to a capability the plugin declares. Granting one it
    /// does not declare is refused.
    pub fn grant_capability(&self, plugin_id: String, capability: String) -> FfiResult<()> {
        Ok(self.store().grant_capability(&plugin_id, &capability)?)
    }

    pub fn revoke_capability(&self, plugin_id: String, capability: String) -> FfiResult<()> {
        Ok(self.store().revoke_capability(&plugin_id, &capability)?)
    }

    /// Whether a plugin may do something now. False for a disabled or
    /// removed plugin whatever it was granted, so hosts ask only this.
    pub fn is_granted(&self, plugin_id: String, capability: String) -> FfiResult<bool> {
        Ok(self.store().is_granted(&plugin_id, &capability)?)
    }

    pub fn set_plugin_settings(&self, plugin_id: String, settings: String) -> FfiResult<()> {
        Ok(self.store().set_plugin_settings(&plugin_id, &settings)?)
    }

    /// Queue work for captures no enabled plugin ever saw — ones made
    /// before a plugin was installed, or while it was switched off.
    /// Deliberate, and safe to repeat. Returns how many were queued.
    pub fn backfill_jobs(&self) -> FfiResult<i64> {
        Ok(self.store().backfill_jobs()?)
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
