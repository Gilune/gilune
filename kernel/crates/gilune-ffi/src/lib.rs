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
            // A halted sync stream is refused input, like any other
            // invalid argument: M5a adds no boundary variant, and the
            // shells learn about halts from the integrity alerts (M5c),
            // not from an error code.
            K::NoSuchJob { .. }
            | K::NoSuchPlugin { .. }
            | K::PluginRequired { .. }
            | K::UndeclaredCapability { .. }
            | K::ProcessorNotClaimable { .. }
            | K::SyncHalted { .. } => GiluneError::Invalid { detail: e.to_string() },
            K::Storage(_) | K::Io { .. } | K::Encoding(_) | K::Corrupt { .. } => {
                GiluneError::Storage { detail: e.to_string() }
            }
        }
    }
}

impl From<gilune_sync::SyncError> for GiluneError {
    fn from(e: gilune_sync::SyncError) -> Self {
        use gilune_sync::SyncError as S;
        match e {
            S::Kernel(k) => GiluneError::from(k),
            // A peer that misbehaved, a code that did not match, a device
            // that is not paired: all refused input, which is what
            // `Invalid` already means at this boundary. The boundary
            // gains variants, never reshaped ones (ADR 0019 § 6).
            S::Protocol { .. } | S::OversizeFrame { .. } | S::Refused { .. } => {
                GiluneError::Invalid { detail: e.to_string() }
            }
            // This device could not do its part — a socket that would not
            // open, an identity file that would not read.
            S::Io(_) | S::Identity { .. } => GiluneError::Storage { detail: e.to_string() },
        }
    }
}

type FfiResult<T> = Result<T, GiluneError>;

/// The platform's key-protection adapter (ADR 0019 § 4), implemented in
/// Kotlin on both shells: DPAPI user scope on Windows, an AndroidKeyStore
/// AES-GCM key on Android. Rust never calls a platform API (ADR 0001);
/// it asks for one of these and records honestly when it is given none.
#[uniffi::export(with_foreign)]
pub trait KeyWrap: Send + Sync {
    /// What to record as having protected the key, so a settings screen
    /// can name it rather than promise it.
    fn name(&self) -> String;
    fn wrap(&self, plaintext: Vec<u8>) -> FfiResult<Vec<u8>>;
    fn unwrap(&self, ciphertext: Vec<u8>) -> FfiResult<Vec<u8>>;
}

/// Bridges the foreign trait onto the sync crate's own, which knows
/// nothing about uniffi.
struct WrapAdapter(Arc<dyn KeyWrap>);

impl gilune_sync::KeyWrap for WrapAdapter {
    fn name(&self) -> String {
        self.0.name()
    }
    fn wrap(&self, plaintext: &[u8]) -> Result<Vec<u8>, String> {
        self.0.wrap(plaintext.to_vec()).map_err(|e| e.to_string())
    }
    fn unwrap(&self, ciphertext: &[u8]) -> Result<Vec<u8>, String> {
        self.0.unwrap(ciphertext.to_vec()).map_err(|e| e.to_string())
    }
}

/// This device's own sync facts, and everything a settings screen shows.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncStatus {
    /// Whether the listener is bound. Turning this on is what raises a
    /// firewall prompt, and nothing else does.
    pub enabled: bool,
    pub device_id: String,
    pub device_name: String,
    /// Short and human-comparable — what "is that really my laptop" means
    /// when there is no certificate authority anywhere.
    pub fingerprint: String,
    pub identity_pub: String,
    /// The adapter that actually protected the identity key, or `none`.
    pub key_protection: String,
    /// `continued`, `adopted` or `re-enrolled`.
    pub enrolment: String,
    /// A sentence for the shell's message line when opening this vault on
    /// this machine meant something the user should know about.
    pub enrolment_notice: Option<String>,
    pub listening_on: Option<String>,
    pub pairing: Option<PairingOffer>,
    pub peers: Vec<PeerInfo>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PeerInfo {
    /// The pinned static key: this peer's identity, and its handle for
    /// `revoke_peer`.
    pub static_pub: String,
    pub fingerprint: String,
    pub device_id: String,
    pub name: String,
    pub address: String,
    /// `paired` or `revoked`.
    pub state: String,
    pub paired_ms: i64,
    pub last_seen_ms: i64,
}

/// A pairing window, while it stands.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PairingOffer {
    pub code: String,
    pub address: String,
    pub fingerprint: String,
    pub expires_ms: i64,
    pub attempts_left: u32,
}

/// A QR as bits, never as a string (ADR 0019 § 7): the shell paints
/// squares on a canvas and the pairing secret never crosses as something
/// a shell could log.
#[derive(Debug, Clone, uniffi::Record)]
pub struct QrMatrix {
    pub width: u32,
    /// Row-major, `width * width` long. `true` is a dark module.
    pub modules: Vec<bool>,
}

/// What one round of syncing moved.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncReport {
    pub applied: i64,
    pub duplicates: i64,
    pub sent: i64,
    pub blobs_completed: i64,
    pub blobs_pending: i64,
    pub bytes: i64,
    /// Streams that stopped, and why. A halt is visible by contract: a
    /// silent one is the failure mode that makes sync look like it works.
    pub halted: Vec<String>,
    /// Something still needs another session. Not a failure — the
    /// frontier makes resuming free.
    pub incomplete: bool,
}

impl From<gilune_sync::service::PeerSummary> for PeerInfo {
    fn from(p: gilune_sync::service::PeerSummary) -> Self {
        PeerInfo {
            static_pub: p.static_pub,
            fingerprint: p.fingerprint,
            device_id: p.device_id,
            name: p.name,
            address: p.address,
            state: p.state,
            paired_ms: p.paired_ms,
            last_seen_ms: p.last_seen_ms,
        }
    }
}

impl From<gilune_sync::service::PairingOffer> for PairingOffer {
    fn from(o: gilune_sync::service::PairingOffer) -> Self {
        PairingOffer {
            code: o.code,
            address: o.address,
            fingerprint: o.fingerprint,
            expires_ms: o.expires_ms,
            attempts_left: o.attempts_left,
        }
    }
}

impl From<gilune_sync::service::SyncFacts> for SyncStatus {
    fn from(f: gilune_sync::service::SyncFacts) -> Self {
        SyncStatus {
            enabled: f.enabled,
            device_id: f.device_id,
            device_name: f.device_name,
            fingerprint: f.fingerprint,
            identity_pub: f.identity_pub,
            key_protection: f.key_protection,
            enrolment: f.enrolment,
            enrolment_notice: f.enrolment_notice,
            listening_on: f.listening_on,
            pairing: f.pairing.map(Into::into),
            peers: f.peers.into_iter().map(Into::into).collect(),
        }
    }
}

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

/// Where a proposed output lands (ADR 0013). An enum, not a string: this
/// is contract, and a typo in it would be a silent new object where a
/// revision was meant.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OutputTarget {
    /// A new interpretation object, with provenance back to the subject.
    NewObject,
    /// A new revision of the subject's own document, replacing its head.
    ReviseSubject,
}

/// An interpretation an external processor proposes. The kernel owns
/// committing it: nothing here may supply a revision ID, an origin, an
/// operation or a provenance row (ADR 0018 § 2).
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProposedOutput {
    /// Ignored for `ReviseSubject`, which keeps the subject's own type.
    pub type_id: String,
    /// Ignored for `ReviseSubject`, as `type_id` is.
    pub type_schema: i64,
    /// Canonical JSON document.
    pub payload: String,
    pub target: OutputTarget,
}

impl From<ProposedOutput> for gilune_kernel::processor::ProposedOutput {
    fn from(o: ProposedOutput) -> Self {
        gilune_kernel::processor::ProposedOutput {
            type_id: o.type_id,
            type_schema: o.type_schema,
            payload: o.payload,
            target: match o.target {
                OutputTarget::NewObject => gilune_kernel::processor::OutputTarget::NewObject,
                OutputTarget::ReviseSubject => {
                    gilune_kernel::processor::OutputTarget::ReviseSubject
                }
            },
        }
    }
}

/// Where a claimed subject's preserved bytes are and what they must hash
/// to — enough for a caller to know it received all of them.
#[derive(Debug, Clone, uniffi::Record)]
pub struct BlobFacts {
    pub rel_path: String,
    pub sha256: String,
    pub bytes: u64,
}

/// A claimed job as an external processor sees it: what `ProcessorCtx`
/// carries, flattened for the crossing.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ExternalJob {
    pub job_id: String,
    pub processor_id: String,
    pub event_id: String,
    pub event_type: String,
    pub subject_id: String,
    /// The event's own payload, as the subscription delivered it.
    pub event_payload: String,
    pub attempts: i64,
    /// The subject's head document at the moment of the claim.
    pub subject_payload: String,
    /// `None` when the subject has no preserved bytes.
    pub blob: Option<BlobFacts>,
}

/// What one submission did. A skipped duplicate is not a failure — it is
/// at-least-once working as promised.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SubmitReceipt {
    pub skipped_duplicate: bool,
    pub outputs: i64,
}

/// How long a claim holds a job before it is claimable again. The same
/// lease `run_processing` takes, because an external claimant is not a
/// different kind of claimant.
const LEASE_MS: i64 = 60_000;

#[derive(uniffi::Object)]
pub struct Gilune {
    store: Mutex<gilune_kernel::store::Store>,
    processors: Mutex<Vec<Box<dyn gilune_kernel::processor::Processor>>>,
    /// Processor ids this host process carries an external implementation
    /// for (ADR 0018 § 2). Per-handle and per-session by design: which
    /// process can run something is a fact about that process, never
    /// about the vault, so it is never written down.
    external: Mutex<Vec<String>>,
    /// The sync service, once a shell has said where this device keeps
    /// its own state. Absent until then: identity lives outside the
    /// vault, and only the shell knows where that is on its platform
    /// (ADR 0019 § 4).
    sync: Mutex<Option<gilune_sync::SyncService>>,
}

impl Gilune {
    fn store(&self) -> std::sync::MutexGuard<'_, gilune_kernel::store::Store> {
        // A poisoned lock means a panicked kernel call; the data behind it
        // is transactional, so continuing is safe and loses nothing.
        self.store.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Stop running a processor in this process. Job claiming is already
    /// gated on the plugin's state, so this only releases what the
    /// implementation holds — a loaded model, for instance — and the
    /// external registration, so a disabled provider cannot keep claiming
    /// through a handle that already said yes.
    fn unload_processor(&self, plugin_id: &str) -> FfiResult<()> {
        let Some(plugin) = self.store().plugin(plugin_id)? else { return Ok(()) };
        let Some(processor_id) = plugin.descriptor.processor_id else { return Ok(()) };
        self.processors
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .retain(|p| p.id() != processor_id);
        self.external
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .retain(|id| *id != processor_id);
        Ok(())
    }

    /// Run something against the sync service, or say plainly that this
    /// host never told the kernel where its device-local state lives.
    fn with_sync<T>(
        &self,
        act: impl FnOnce(&gilune_sync::SyncService) -> gilune_sync::Result<T>,
    ) -> FfiResult<T> {
        let guard = self.sync.lock().unwrap_or_else(|e| e.into_inner());
        let service = guard.as_ref().ok_or(GiluneError::Invalid {
            detail: "this host has not configured sync".to_string(),
        })?;
        Ok(act(service)?)
    }

    fn is_external(&self, processor_id: &str) -> bool {
        self.external
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .iter()
            .any(|id| id == processor_id)
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
            external: Mutex::new(Vec::new()),
            sync: Mutex::new(None),
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
    /// registered. A no-op when none are (Android v0). Jobs belonging to
    /// an external processor registered here are left alone: their host
    /// claims them itself, and claiming them here would only burn a lease.
    pub fn run_processing(&self) -> FfiResult<ProcessingSummary> {
        let processors = self.processors.lock().unwrap_or_else(|e| e.into_inner());
        let refs: Vec<&dyn gilune_kernel::processor::Processor> =
            processors.iter().map(|p| p.as_ref()).collect();
        let external = self.external.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let s = self.store().run_pending_jobs_excluding(
            &refs,
            &external,
            gilune_kernel::ids::now_ms(),
            LEASE_MS,
        )?;
        Ok(ProcessingSummary {
            completed: s.completed,
            skipped_duplicate: s.skipped_duplicate,
            failed: s.failed,
            outputs: s.outputs,
        })
    }

    /// Declare that **this host process** carries an implementation of
    /// `processor_id` for `plugin_id` (ADR 0018 § 2). It creates no
    /// subscription — `register_plugin` already does that — and queues
    /// nothing: it only says that jobs for this id have somewhere to go
    /// here, so `run_processing` leaves them for `claim_job` instead of
    /// expiring their lease forever.
    ///
    /// Per-handle and per-session, exactly as `enable_whisper` is. Refused
    /// while the plugin is disabled or removed, and refused for an id the
    /// plugin's own declaration does not name — a host may implement what
    /// a plugin declared, never more.
    pub fn register_external_processor(
        &self,
        plugin_id: String,
        processor_id: String,
    ) -> FfiResult<()> {
        use gilune_kernel::plugins::PluginState;
        let plugin = self.store().plugin(&plugin_id)?.ok_or(GiluneError::Invalid {
            detail: format!("{plugin_id} is removed"),
        })?;
        if plugin.state != PluginState::Enabled {
            return Err(GiluneError::Invalid {
                detail: format!("{plugin_id} is {}", plugin.state.as_str()),
            });
        }
        if plugin.descriptor.processor_id.as_deref() != Some(processor_id.as_str()) {
            return Err(GiluneError::Invalid {
                detail: format!("{plugin_id} does not declare the processor {processor_id}"),
            });
        }
        let mut external = self.external.lock().unwrap_or_else(|e| e.into_inner());
        if !external.contains(&processor_id) {
            external.push(processor_id);
        }
        Ok(())
    }

    /// Claim one job for an external processor registered here, under the
    /// shipped lease. `None` when nothing is waiting. The subject's head
    /// document comes with the claim; its blob is streamed from the claim
    /// chunk by chunk, digest-verified before a byte crosses.
    ///
    /// A claimant that dies holds the job only until its lease expires;
    /// after that it is claimable again, which is at-least-once doing the
    /// work a reclaim column would otherwise have to do.
    pub fn claim_job(self: Arc<Self>, processor_id: String) -> FfiResult<Option<Arc<ClaimedJob>>> {
        if !self.is_external(&processor_id) {
            return Err(GiluneError::Invalid {
                detail: format!("no external implementation of {processor_id} is registered here"),
            });
        }
        let claimed = self.store().claim_external_job(
            &processor_id,
            gilune_kernel::ids::now_ms(),
            LEASE_MS,
        )?;
        Ok(claimed.map(|claim| {
            Arc::new(ClaimedJob {
                gilune: self.clone(),
                claim,
                stream: Mutex::new(None),
                drained: Mutex::new(false),
            })
        }))
    }

    /// Commit an external processor's outputs through **the same
    /// transaction a bundled Rust processor's outputs take**: the
    /// `processing_runs` row keyed `{processor_id}:{event_id}`, revision
    /// IDs derived from those same inputs, `processor:{id}` origin, the
    /// `create-revision` operation and the `revision-added` event
    /// (ADR 0013's amendment). Retrying a submit after a crash therefore
    /// lands exactly once by construction, not by luck.
    ///
    /// Refused if the plugin stopped being enabled while the work was in
    /// flight. The claim is the kernel's own — a caller cannot name a
    /// subject it did not claim, so this is not a general write door.
    pub fn submit_output(
        &self,
        claim: Arc<ClaimedJob>,
        outputs: Vec<ProposedOutput>,
    ) -> FfiResult<SubmitReceipt> {
        let outputs: Vec<gilune_kernel::processor::ProposedOutput> =
            outputs.into_iter().map(Into::into).collect();
        let delivered = self.store().submit_external_outputs(
            &claim.claim.job.subscription_id,
            &claim.claim.job,
            &outputs,
        )?;
        Ok(SubmitReceipt {
            skipped_duplicate: delivered.skipped_duplicate,
            outputs: delivered.outputs,
        })
    }

    /// Report that an external processor could not do the work. The very
    /// same path a Rust processor's `Err` takes: retry with backoff, then
    /// quarantine after five attempts. The job returns to the queue; the
    /// capture is never at risk either way.
    pub fn fail_job(&self, claim: Arc<ClaimedJob>, message: String) -> FfiResult<()> {
        Ok(self.store().fail_external_job(
            &claim.claim.job.subscription_id,
            &claim.claim.job,
            gilune_kernel::ids::now_ms(),
            &message,
        )?)
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

    // ------------------------------------------------------------ sync

    /// Enrol this machine against this vault (ADR 0019 § 4) and stand the
    /// sync service up. Called once, straight after `open`.
    ///
    /// `identity_dir` is device-local state **outside the vault** and the
    /// shell nominates it — `%LOCALAPPDATA%\Gilune` on Windows,
    /// `filesDir` on Android — so the kernel stays path-agnostic.
    /// `key_wrap` is the platform's protection for the key at rest; pass
    /// `None` and the key is stored unwrapped with the reason recorded
    /// in the file rather than assumed away.
    ///
    /// **Nothing binds a socket here.** The returned status carries
    /// `enrolment_notice`, which is non-null exactly when this vault
    /// turned out to have come from another device — the case the UI must
    /// say out loud rather than leaving a user to wonder why their laptop
    /// appears twice.
    pub fn configure_sync(
        &self,
        identity_dir: String,
        device_name: String,
        key_wrap: Option<Arc<dyn KeyWrap>>,
    ) -> FfiResult<SyncStatus> {
        let wrap = key_wrap.map(|w| {
            Arc::new(WrapAdapter(w)) as Arc<dyn gilune_sync::KeyWrap>
        });
        let service = gilune_sync::SyncService::configure(
            &self.store(),
            identity_dir,
            &device_name,
            wrap,
            gilune_kernel::limits::Limits::DEFAULT,
        )?;
        let facts = service.facts();
        *self.sync.lock().unwrap_or_else(|e| e.into_inner()) = Some(service);
        Ok(facts.into())
    }

    pub fn sync_status(&self) -> FfiResult<SyncStatus> {
        Ok(self.with_sync(|service| Ok(service.facts()))?.into())
    }

    /// Turn sync on or off. **This is the moment the listener binds**
    /// (ADR 0019 § 2): a user who never turns sync on never meets the
    /// firewall prompt, and capture is never behind it. Switching off
    /// closes the socket and abandons any pairing window with it.
    pub fn set_sync_enabled(&self, enabled: bool) -> FfiResult<SyncStatus> {
        self.with_sync(|service| {
            service.set_enabled(enabled)?;
            Ok(service.facts())
        })
        .map(Into::into)
    }

    /// Open a pairing window: a fresh six-digit code and a fresh QR
    /// secret, single-use, expiring, and abandoned after a few wrong
    /// guesses. Asking again replaces the window, which is how a person
    /// gets a new code after theirs was used up.
    pub fn start_pairing(&self) -> FfiResult<PairingOffer> {
        self.with_sync(|service| service.start_pairing()).map(Into::into)
    }

    /// The QR for the open window, as a module matrix.
    pub fn pairing_qr(&self) -> FfiResult<QrMatrix> {
        let matrix = self.with_sync(|service| service.pairing_qr())?;
        Ok(QrMatrix { width: matrix.width, modules: matrix.modules })
    }

    /// Join a device that is showing a code. `address` is `host:port`,
    /// from that device's settings screen.
    pub fn pair_with_code(&self, address: String, code: String) -> FfiResult<PeerInfo> {
        let store = self.store();
        self.with_sync(|service| service.pair_with_code(&store, &address, &code))
            .map(Into::into)
    }

    /// Join a device from a scanned QR. The address comes from the
    /// payload, and the fingerprint in it is checked against the identity
    /// the handshake actually transmits — so a code photographed off
    /// somebody else's screen cannot redirect this device silently.
    pub fn pair_from_qr(&self, payload: String) -> FfiResult<PeerInfo> {
        let store = self.store();
        self.with_sync(|service| service.pair_from_qr(&store, &payload))
            .map(Into::into)
    }

    pub fn peers(&self) -> FfiResult<Vec<PeerInfo>> {
        Ok(self
            .with_sync(|service| Ok(service.peers()))?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Revoke a peer, by the static key `PeerInfo` carries. Local and
    /// immediate: the pair secret goes, a live session ends at the next
    /// operation boundary, and that device's operations are refused on
    /// apply whoever relays them afterwards.
    ///
    /// It does not retract history. Revocation protects what comes next,
    /// and the UI must say so rather than implying an undo.
    pub fn revoke_peer(&self, static_pub: String) -> FfiResult<bool> {
        let store = self.store();
        self.with_sync(|service| service.revoke_peer(&store, &static_pub))
    }

    /// Sync with every paired peer this device has an address for. M5b
    /// has no discovery, so a peer whose address is unknown is skipped
    /// rather than hunted for.
    pub fn sync_now(&self) -> FfiResult<SyncReport> {
        let store = self.store();
        let report = self.with_sync(|service| service.sync_now(&store))?;
        Ok(SyncReport {
            applied: report.applied as i64,
            duplicates: report.duplicates as i64,
            sent: report.sent as i64,
            blobs_completed: report.blobs_completed as i64,
            blobs_pending: report.blobs_pending as i64,
            bytes: report.bytes as i64,
            incomplete: report.incomplete(),
            halted: report
                .halted
                .iter()
                .map(|h| format!("{}: {}", h.device_id, h.reason))
                .collect(),
        })
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

/// A job an external processor holds a lease on. The claim is the
/// kernel's to hand out and cannot be built by a caller, which is what
/// stops `submit_output` from becoming a route to revise any object at
/// all: you may write an output only for the subject you were given.
#[derive(uniffi::Object)]
pub struct ClaimedJob {
    gilune: Arc<Gilune>,
    claim: gilune_kernel::external::ExternalClaim,
    /// Opened on the first chunk, not on the claim: the digest pass costs
    /// a read of the whole file, and a caller that never wants the bytes
    /// should never pay for it.
    stream: Mutex<Option<gilune_kernel::external::BlobStream>>,
    drained: Mutex<bool>,
}

#[uniffi::export]
impl ClaimedJob {
    /// Everything the claim carries except the bytes.
    pub fn job(&self) -> ExternalJob {
        let job = &self.claim.job;
        ExternalJob {
            job_id: job.job_id.clone(),
            processor_id: job.subscription_id.clone(),
            event_id: job.event_id.clone(),
            event_type: job.event_type.clone(),
            subject_id: job.subject_id.clone(),
            event_payload: job.payload.clone(),
            attempts: job.attempts,
            subject_payload: self.claim.subject_payload.clone(),
            blob: self.claim.blob.as_ref().map(|b| BlobFacts {
                rel_path: b.rel_path.clone(),
                sha256: b.sha256.clone(),
                bytes: b.bytes,
            }),
        }
    }

    /// The next chunk of the subject's preserved bytes, or `None` once
    /// the whole blob has crossed. The kernel verifies the digest over the
    /// entire file before the first chunk is handed out, and checks a
    /// running hash again at the end — so a truncated or altered read is
    /// an error here, not a surprise in the transcript. A caller may also
    /// hash what it received and compare it against `job().blob.sha256`.
    pub fn next_chunk(&self) -> FfiResult<Option<Vec<u8>>> {
        let mut drained = self.drained.lock().unwrap_or_else(|e| e.into_inner());
        // A subject with no preserved bytes reads as no bytes, not as an
        // error: `job().blob` is how a caller asks whether there are any.
        if *drained || self.claim.blob.is_none() {
            return Ok(None);
        }
        let mut guard = self.stream.lock().unwrap_or_else(|e| e.into_inner());
        if guard.is_none() {
            *guard = Some(self.gilune.store().open_claim_blob(&self.claim)?);
        }
        let stream = guard.as_mut().expect("just opened");
        match stream.next_chunk()? {
            Some(chunk) => Ok(Some(chunk)),
            None => {
                *drained = true;
                Ok(None)
            }
        }
    }
}

#[uniffi::export]
pub fn kernel_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
