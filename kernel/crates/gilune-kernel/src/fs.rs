//! Filesystem durability primitives. Platform differences live here and
//! nowhere else (conventions § Rust). The Windows semantics were
//! established by the S2 Windows evidence (2026-08-30): directories only
//! open with backup semantics, and FlushFileBuffers requires a writable
//! handle.

use crate::{KernelError, Result};
use std::path::Path;

fn io_err(path: &Path, source: std::io::Error) -> KernelError {
    KernelError::Io { path: path.to_path_buf(), source }
}

/// fsync a directory so a rename into it is durable.
#[cfg(unix)]
pub fn fsync_dir(dir: &Path) -> Result<()> {
    let f = std::fs::File::open(dir).map_err(|e| io_err(dir, e))?;
    f.sync_all().map_err(|e| io_err(dir, e))?;
    Ok(())
}

/// fsync a directory so a rename into it is durable.
#[cfg(windows)]
pub fn fsync_dir(dir: &Path) -> Result<()> {
    use std::os::windows::fs::OpenOptionsExt;
    const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
    let f = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS)
        .open(dir)
        .map_err(|e| io_err(dir, e))?;
    f.sync_all().map_err(|e| io_err(dir, e))?;
    Ok(())
}

/// fsync a closed file by path. Windows refuses to flush read-only
/// handles, so the reopen is always writable.
pub fn fsync_file(path: &Path) -> Result<()> {
    let f = std::fs::OpenOptions::new()
        .write(true)
        .open(path)
        .map_err(|e| io_err(path, e))?;
    f.sync_all().map_err(|e| io_err(path, e))?;
    Ok(())
}

/// Relative paths stored in the database, manifests, and operation files
/// are a portable contract: `/`-separated on every platform
/// (03-data-model § Export contract). Joining them back onto a root with
/// `Path::join` is correct on Windows, which accepts `/`.
pub fn portable_rel(rel: &Path) -> String {
    rel.components()
        .map(|c| c.as_os_str().to_string_lossy())
        .collect::<Vec<_>>()
        .join("/")
}
