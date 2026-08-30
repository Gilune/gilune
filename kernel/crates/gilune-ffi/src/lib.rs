//! The application boundary. Everything the shells may call crosses
//! here, and nothing else does: kernel implementation types never leak
//! (ADR 0001). Grows with the milestones; starts with identity so the
//! boundary exists — and is versioned — from day one.

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
}

impl From<gilune_kernel::KernelError> for GiluneError {
    fn from(e: gilune_kernel::KernelError) -> Self {
        use gilune_kernel::KernelError as K;
        match e {
            K::VaultUnusable { .. } => GiluneError::VaultUnusable { detail: e.to_string() },
            K::SchemaTooNew { .. } => GiluneError::Incompatible { detail: e.to_string() },
            K::Storage(_) | K::Io { .. } => GiluneError::Storage { detail: e.to_string() },
        }
    }
}

/// The boundary owns the threading policy: shells may call from any
/// thread, so the store sits behind one lock here and the kernel crate
/// stays lock-free by design.
#[derive(uniffi::Object)]
pub struct Gilune {
    store: Mutex<gilune_kernel::store::Store>,
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
    /// Open (or create) the vault at `dir`, running migrations.
    #[uniffi::constructor]
    pub fn open(dir: String) -> Result<Arc<Self>, GiluneError> {
        let store = gilune_kernel::store::Store::open(dir)?;
        Ok(Arc::new(Self { store: Mutex::new(store) }))
    }

    pub fn schema_version(&self) -> Result<i64, GiluneError> {
        Ok(self.store().schema_version()?)
    }

    pub fn vault_path(&self) -> String {
        self.store().vault().to_string_lossy().to_string()
    }
}

#[uniffi::export]
pub fn kernel_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
