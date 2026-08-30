//! The portable Gilune kernel. Owns the invariants every feature relies
//! on — durable storage, capture commit, events and jobs, sync
//! operations — and names no domain type (constitution rule 4). Domain
//! behaviour lives in plugins behind the contracts in `contracts/`.

pub mod fs;
pub mod store;

/// Errors crossing the kernel's public boundary. Every variant carries
/// enough context to act on; panics are bugs, not error handling.
#[derive(Debug, thiserror::Error)]
pub enum KernelError {
    #[error("storage: {0}")]
    Storage(#[from] rusqlite::Error),
    #[error("io on {path}: {source}")]
    Io {
        path: std::path::PathBuf,
        source: std::io::Error,
    },
    #[error("vault directory is not usable: {reason}")]
    VaultUnusable { reason: String },
    #[error("schema version {found} is newer than this kernel supports ({supported})")]
    SchemaTooNew { found: i64, supported: i64 },
}

pub type Result<T> = std::result::Result<T, KernelError>;
