//! The portable Gilune kernel. Owns the invariants every feature relies
//! on — durable storage, capture commit, events and jobs, sync
//! operations — and names no domain type (constitution rule 4). Domain
//! behaviour lives in plugins behind the contracts in `contracts/`.

pub mod capture;
pub mod crashpoints;
pub mod fs;
pub mod ids;
pub mod jobs;
pub mod query;
pub mod store;
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
}

pub type Result<T> = std::result::Result<T, KernelError>;
