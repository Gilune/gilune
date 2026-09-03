//! The portable Gilune kernel. Owns the invariants every feature relies
//! on — durable storage, capture commit, events and jobs, sync
//! operations — and names no domain type (constitution rule 4). Domain
//! behaviour lives in plugins behind the contracts in `contracts/`.

pub mod alerts;
pub mod blobs;
pub mod capture;
pub mod crashpoints;
pub mod external;
pub mod fs;
pub mod ids;
pub mod jobs;
pub mod limits;
pub mod operations;
pub mod plugins;
pub mod processor;
pub mod query;
pub mod revisions;
pub mod store;
mod transcript_merge;
pub mod vault;

/// Errors crossing the kernel's public boundary. Every variant carries
/// enough context to act on; panics are bugs, not error handling.
#[derive(Debug, thiserror::Error)]
pub enum KernelError {
    #[error("storage: {0}")]
    Storage(#[from] rusqlite::Error),
    #[error("encoding: {0}")]
    Encoding(#[from] serde_json::Error),
    #[error("io on {path}: {source}")]
    Io {
        path: std::path::PathBuf,
        source: std::io::Error,
    },
    #[error("vault directory is not usable: {reason}")]
    VaultUnusable { reason: String },
    #[error("schema version {found} is newer than this kernel supports ({supported})")]
    SchemaTooNew { found: i64, supported: i64 },
    #[error("no such job: {job_id}")]
    NoSuchJob { job_id: String },
    #[error("no such plugin: {plugin_id}")]
    NoSuchPlugin { plugin_id: String },
    #[error("{plugin_id} is required and cannot be removed")]
    PluginRequired { plugin_id: String },
    #[error("{plugin_id} does not declare the capability {capability}")]
    UndeclaredCapability { plugin_id: String, capability: String },
    #[error("no enabled plugin claims work for the processor {processor_id}")]
    ProcessorNotClaimable { processor_id: String },
    /// A revise-subject output named the head it was written over, and
    /// the subject has moved on since. Compare-and-set: whoever submits
    /// second is refused rather than quietly replacing an answer that
    /// arrived while it was working. Naming a head is optional — an
    /// output that names none keeps the old last-writer behaviour — so
    /// this is only ever raised by a caller that asked for it.
    #[error("{subject_id} was at {expected} when the work began and is now at {found}")]
    SubjectMoved {
        subject_id: String,
        expected: String,
        found: String,
    },
    #[error("local state is corrupt: {detail}")]
    Corrupt { detail: String },
    /// An operation arriving from elsewhere was refused. The stream from
    /// that device is halted: nothing past the fault has been applied,
    /// the frontier is where it was, and a standing integrity alert says
    /// so. Resuming needs no repair here — only the condition to clear.
    ///
    /// `kind` is the alert kind that was raised and `subject` is what it
    /// was raised about — a device id for a broken stream, a blob id for
    /// content, an object or revision id for a referent that has not
    /// arrived. Both travel with the error because the refusal and the
    /// notice a person is shown must never be able to disagree: before
    /// they did, and every in-transaction halt was relabelled
    /// `sync-limit-exceeded` on its way out.
    #[error("sync halted for {subject}: {reason}")]
    SyncHalted {
        kind: String,
        subject: String,
        reason: String,
    },
}

pub type Result<T> = std::result::Result<T, KernelError>;
