//! Spike S2: durable capture, events, recovery, and vault projection.
//!
//! Disposable evidence prototype for ADR 0002 (SQLite + external blobs),
//! ADR 0004 (transactional events, at-least-once jobs) and ADR 0007
//! (Markdown vault projection). Not production code: the point is to let the
//! fault harness kill the process at every persistence boundary and prove the
//! invariants in `architecture/spikes/README.md` (S2).

pub mod capture;
pub mod crashpoints;
pub mod export;
pub mod ids;
pub mod jobs;
pub mod processors;
pub mod store;
pub mod sync;
pub mod vault;

use anyhow::Result;
use rusqlite::Connection;
use std::path::{Path, PathBuf};

pub struct Kernel {
    pub conn: Connection,
    pub vault: PathBuf,
    pub device_id: String,
}

impl Kernel {
    /// Vault-relative locations. The database and staging area are
    /// device-local (never replicated); blobs and Markdown are the vault.
    pub fn local_dir(vault: &Path) -> PathBuf {
        vault.join(".gilune").join("local")
    }
    pub fn db_path(vault: &Path) -> PathBuf {
        Self::local_dir(vault).join("kernel.db")
    }
    pub fn staging_dir(vault: &Path) -> PathBuf {
        Self::local_dir(vault).join("staging")
    }
    pub fn lost_found_dir(vault: &Path) -> PathBuf {
        Self::local_dir(vault).join("lost+found")
    }

    /// Open (creating if needed) and run the startup recovery checks.
    /// Every CLI command goes through here, so recovery is exercised on
    /// every restart exactly as the processing model requires.
    pub fn open(vault: &Path) -> Result<Kernel> {
        std::fs::create_dir_all(Self::staging_dir(vault))?;
        std::fs::create_dir_all(Self::lost_found_dir(vault))?;
        let conn = store::open_db(&Self::db_path(vault))?;
        let mut k = Kernel {
            conn,
            vault: vault.to_path_buf(),
            device_id: String::new(),
        };
        store::migrate(&mut k)?;
        k.device_id = store::device_id(&k.conn)?;
        k.recover()?;
        Ok(k)
    }

    /// Startup recovery, mirroring 04-processing-model.md "Recovery checks".
    pub fn recover(&mut self) -> Result<()> {
        capture::recover_blobs(self)?;
        jobs::expire_stale_leases(self)?;
        // The job backfill reads v2 columns; on a database deliberately held
        // at v1 (GILUNE_SCHEMA_TARGET test hook) it must wait for migration.
        if store::schema_version(&self.conn) >= 2 {
            jobs::ensure_jobs_for_committed_events(self)?;
        }
        Ok(())
    }

    pub fn now_ms() -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock before epoch")
            .as_millis() as i64
    }
}

/// fsync a directory so a rename into it is durable.
#[cfg(unix)]
pub fn fsync_dir(dir: &Path) -> Result<()> {
    let f = std::fs::File::open(dir)?;
    f.sync_all()?;
    Ok(())
}

/// fsync a directory so a rename into it is durable.
///
/// Windows: directories only open with FILE_FLAG_BACKUP_SEMANTICS, and
/// FlushFileBuffers demands a writable handle; a plain read-only open is
/// ERROR_ACCESS_DENIED for both reasons.
#[cfg(windows)]
pub fn fsync_dir(dir: &Path) -> Result<()> {
    use std::os::windows::fs::OpenOptionsExt;
    const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
    let f = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS)
        .open(dir)?;
    f.sync_all()?;
    Ok(())
}

/// fsync a closed file by path. Windows FlushFileBuffers requires write
/// access, so a read-only reopen (fine for fsync on POSIX) is refused there.
pub fn fsync_file(path: &Path) -> Result<()> {
    let f = std::fs::OpenOptions::new().write(true).open(path)?;
    f.sync_all()?;
    Ok(())
}

/// Relative paths stored in the database, export manifests, and sync files
/// are a portable contract: `/`-separated on every platform, so a vault can
/// move between operating systems. Joining them back onto a root with
/// `Path::join` is fine — Windows accepts `/`.
pub fn portable_rel(rel: &Path) -> String {
    rel.components()
        .map(|c| c.as_os_str().to_string_lossy())
        .collect::<Vec<_>>()
        .join("/")
}
