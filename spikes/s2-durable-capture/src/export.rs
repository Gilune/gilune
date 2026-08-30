//! Export: a copy of the vault taken quiescent, plus an integrity manifest
//! (ADR 0007). Device-local state (`.gilune/local/`) is excluded; everything
//! user-owned travels.

use crate::{ids, Kernel};
use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Serialize, Deserialize)]
pub struct Manifest {
    pub format: String,
    pub schema_version: i64,
    pub generated_at: i64,
    pub files: Vec<ManifestFile>,
    pub integrity_alerts: i64,
}

#[derive(Serialize, Deserialize)]
pub struct ManifestFile {
    pub path: String,
    pub sha256: String,
    pub bytes: u64,
}

fn walk_exportable(vault: &Path) -> Result<Vec<String>> {
    let mut out = Vec::new();
    let mut stack = vec![vault.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir)? {
            let entry = entry?;
            let path = entry.path();
            let rel = crate::portable_rel(path.strip_prefix(vault).unwrap());
            if path.is_dir() {
                if rel == ".gilune/local" {
                    continue;
                }
                stack.push(path);
            } else {
                out.push(rel);
            }
        }
    }
    out.sort();
    Ok(out)
}

pub fn export(k: &Kernel, dest: &Path) -> Result<Manifest> {
    let leased: i64 = k.conn.query_row(
        "SELECT COUNT(*) FROM jobs WHERE status = 'leased'",
        [],
        |r| r.get(0),
    )?;
    if leased > 0 {
        bail!("store not quiescent: {leased} leased jobs");
    }
    let alerts: i64 = k
        .conn
        .query_row("SELECT COUNT(*) FROM integrity_alerts", [], |r| r.get(0))?;

    std::fs::create_dir_all(dest)?;
    let mut files = Vec::new();
    for rel in walk_exportable(&k.vault)? {
        let src = k.vault.join(&rel);
        let dst = dest.join(&rel);
        if let Some(parent) = dst.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::copy(&src, &dst)?;
        let bytes = std::fs::read(&dst)?;
        files.push(ManifestFile {
            path: rel,
            sha256: ids::sha256_hex(&bytes),
            bytes: bytes.len() as u64,
        });
    }
    let manifest = Manifest {
        format: "gilune-vault-export".into(),
        schema_version: 1,
        generated_at: Kernel::now_ms(),
        files,
        integrity_alerts: alerts,
    };
    std::fs::write(
        dest.join("manifest.json"),
        serde_json::to_vec_pretty(&manifest)?,
    )?;
    Ok(manifest)
}

/// Re-hash an exported tree against its manifest. Returns mismatches.
pub fn verify_export(dest: &Path) -> Result<Vec<String>> {
    let manifest: Manifest =
        serde_json::from_slice(&std::fs::read(dest.join("manifest.json"))?)?;
    let mut problems = Vec::new();
    for f in &manifest.files {
        let path = dest.join(&f.path);
        match std::fs::read(&path) {
            Ok(bytes) => {
                if ids::sha256_hex(&bytes) != f.sha256 {
                    problems.push(format!("hash mismatch: {}", f.path));
                }
            }
            Err(_) => problems.push(format!("missing: {}", f.path)),
        }
    }
    Ok(problems)
}
