//! Operation sync over a shared folder (Spike S4, reusing the S2 model as
//! the spike plan requires).
//!
//! The relay is a plain directory any dumb store could replicate:
//!
//! ```text
//! relay/
//!   ops/<device_id>/<seq, zero-padded>.json    # immutable, write-once
//!   blobs/<blob_id>/manifest.json + <i>.chunk  # content chunks
//! ```
//!
//! Semantics owned here (kernel side, per ADR 0005): per-device monotonic
//! sequences with gap detection, idempotent application, concurrent-head
//! preservation, unknown-type preservation, tombstone propagation, and the
//! origin-device processing policy on applied events. The "transport" is
//! nothing but files appearing in a folder — which is also the first
//! evidence for ADR 0008's shared-folder transport and the
//! operations-as-immutable-files layout question.

use crate::crashpoints::crashpoint;
use crate::{fsync_dir, ids, jobs, store, Kernel};
use anyhow::{Context, Result};
use std::path::{Path, PathBuf};

pub const CHUNK_SIZE: usize = 64 * 1024;

#[derive(Debug, Default)]
pub struct PushReport {
    pub ops_written: usize,
    pub blobs_written: usize,
}

#[derive(Debug, Default)]
pub struct PullReport {
    pub ops_applied: usize,
    pub pending_blobs: usize,
    pub blobs_completed: usize,
    pub gaps: usize,
    pub corrupt: usize,
}

fn ops_dir(relay: &Path, device: &str) -> PathBuf {
    relay.join("ops").join(device)
}
fn blob_dir(relay: &Path, blob_id: &str) -> PathBuf {
    relay.join("blobs").join(blob_id)
}

fn write_immutable(path: &Path, bytes: &[u8]) -> Result<bool> {
    if path.exists() {
        return Ok(false); // write-once: never overwrite a published file
    }
    let parent = path.parent().unwrap();
    std::fs::create_dir_all(parent)?;
    let tmp = parent.join(format!(".tmp-{}", path.file_name().unwrap().to_string_lossy()));
    std::fs::write(&tmp, bytes)?;
    std::fs::File::open(&tmp)?.sync_all()?;
    std::fs::rename(&tmp, path)?;
    fsync_dir(parent)?;
    Ok(true)
}

/// Publish local operations (and their blob chunks) newer than the last push.
pub fn push(k: &mut Kernel, relay: &Path) -> Result<PushReport> {
    let mut report = PushReport::default();
    let device = k.device_id.clone();
    let pushed: i64 = k
        .conn
        .query_row(
            "SELECT value FROM meta WHERE key = 'pushed_seq'",
            [],
            |r| r.get::<_, String>(0),
        )
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);

    let ops: Vec<(String, i64, String, String, String, i64)> = {
        let mut stmt = k.conn.prepare(
            "SELECT operation_id, device_seq, kind, target_id, payload_json, committed_at
             FROM operations WHERE device_id = ?1 AND device_seq > ?2
             ORDER BY device_seq",
        )?;
        let rows = stmt.query_map(rusqlite::params![device, pushed], |r| {
            Ok((
                r.get(0)?,
                r.get(1)?,
                r.get(2)?,
                r.get(3)?,
                r.get(4)?,
                r.get(5)?,
            ))
        })?;
        rows.collect::<std::result::Result<_, _>>()?
    };

    let mut max_seq = pushed;
    for (operation_id, seq, kind, target, payload_json, committed_at) in ops {
        let payload: serde_json::Value = serde_json::from_str(&payload_json)?;
        // Blob bytes travel as chunks beside the operations.
        if let Some(blob) = payload.get("blob") {
            let blob_id = blob["blob_id"].as_str().unwrap_or_default().to_string();
            let rel_path = blob["rel_path"].as_str().unwrap_or_default();
            let bytes = std::fs::read(k.vault.join(rel_path))
                .with_context(|| format!("reading blob bytes {rel_path}"))?;
            if push_blob(relay, &blob_id, &bytes)? {
                report.blobs_written += 1;
            }
        }
        let envelope = serde_json::json!({
            "operation_id": operation_id,
            "device_id": &device,
            "device_seq": seq,
            "kind": kind,
            "target_id": target,
            "committed_at": committed_at,
            "payload": payload,
        });
        let file = ops_dir(relay, &device).join(format!("{seq:012}.json"));
        if write_immutable(&file, serde_json::to_string_pretty(&envelope)?.as_bytes())? {
            report.ops_written += 1;
        }
        crashpoint("sync.during_push");
        max_seq = seq;
    }
    k.conn.execute(
        "INSERT INTO meta (key, value) VALUES ('pushed_seq', ?1)
         ON CONFLICT(key) DO UPDATE SET value = ?1",
        [max_seq.to_string()],
    )?;
    Ok(report)
}

fn push_blob(relay: &Path, blob_id: &str, bytes: &[u8]) -> Result<bool> {
    let dir = blob_dir(relay, blob_id);
    let chunks = bytes.chunks(CHUNK_SIZE).count().max(1);
    let manifest = serde_json::json!({
        "byte_len": bytes.len(),
        "sha256": ids::sha256_hex(bytes),
        "chunk_size": CHUNK_SIZE,
        "chunks": chunks,
    });
    let mut wrote = false;
    for (i, chunk) in bytes.chunks(CHUNK_SIZE).enumerate() {
        wrote |= write_immutable(&dir.join(format!("{i}.chunk")), chunk)?;
    }
    if bytes.is_empty() {
        wrote |= write_immutable(&dir.join("0.chunk"), &[])?;
    }
    wrote |= write_immutable(
        &dir.join("manifest.json"),
        serde_json::to_string_pretty(&manifest)?.as_bytes(),
    )?;
    Ok(wrote)
}

/// Apply other devices' operations after our acknowledged frontier, in
/// per-device sequence order, idempotently. A missing sequence number is a
/// gap: application for that device stops, visibly, until it appears.
pub fn pull(k: &mut Kernel, relay: &Path) -> Result<PullReport> {
    let mut report = PullReport::default();
    let local = k.device_id.clone();
    let ops_root = relay.join("ops");
    if !ops_root.exists() {
        return Ok(report);
    }
    let mut devices: Vec<String> = std::fs::read_dir(&ops_root)?
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .map(|e| e.file_name().to_string_lossy().to_string())
        .filter(|d| *d != local)
        .collect();
    devices.sort();

    for device in devices {
        let frontier: i64 = k
            .conn
            .query_row(
                "SELECT applied_seq FROM sync_frontiers WHERE device_id = ?1",
                [&device],
                |r| r.get(0),
            )
            .unwrap_or(0);
        let dir = ops_dir(relay, &device);
        let mut seqs: Vec<i64> = std::fs::read_dir(&dir)?
            .filter_map(|e| e.ok())
            .filter_map(|e| {
                e.file_name()
                    .to_string_lossy()
                    .strip_suffix(".json")?
                    .parse()
                    .ok()
            })
            .filter(|s| *s > frontier)
            .collect();
        seqs.sort();

        let mut expected = frontier + 1;
        for seq in seqs {
            if seq != expected {
                store::raise_alert(
                    &k.conn,
                    "sync-gap",
                    &device,
                    &format!("expected seq {expected}, next available {seq}"),
                )?;
                report.gaps += 1;
                break;
            }
            let file = dir.join(format!("{seq:012}.json"));
            let text = std::fs::read_to_string(&file)?;
            let envelope: serde_json::Value = match serde_json::from_str(&text) {
                Ok(v) => v,
                Err(e) => {
                    store::raise_alert(
                        &k.conn,
                        "sync-corrupt-operation",
                        &device,
                        &format!("seq {seq}: {e}"),
                    )?;
                    report.corrupt += 1;
                    break; // a corrupt op is a gap: nothing past it applies
                }
            };
            apply_operation(k, &envelope)?;
            k.conn.execute(
                "INSERT INTO sync_frontiers (device_id, applied_seq) VALUES (?1, ?2)
                 ON CONFLICT(device_id) DO UPDATE SET applied_seq = ?2",
                rusqlite::params![device, seq],
            )?;
            report.ops_applied += 1;
            crashpoint("sync.during_pull");
            expected = seq + 1;
        }
    }

    // Assemble any blobs whose operations arrived before all their chunks.
    let (done, pending) = assemble_pending_blobs(k, relay)?;
    report.blobs_completed = done;
    report.pending_blobs = pending;
    Ok(report)
}

/// Idempotent application of one remote operation. Everything happens in one
/// transaction; reapplying is a no-op because the operation ID is the guard.
fn apply_operation(k: &mut Kernel, envelope: &serde_json::Value) -> Result<()> {
    let op_id = envelope["operation_id"].as_str().unwrap_or_default().to_string();
    let device = envelope["device_id"].as_str().unwrap_or_default().to_string();
    let seq = envelope["device_seq"].as_i64().unwrap_or_default();
    let kind = envelope["kind"].as_str().unwrap_or_default().to_string();
    let target = envelope["target_id"].as_str().unwrap_or_default().to_string();
    let committed_at = envelope["committed_at"].as_i64().unwrap_or_default();
    let payload = &envelope["payload"];
    let local = k.device_id.clone();
    let now = Kernel::now_ms();

    let tx = k.conn.transaction()?;
    {
        let seen: i64 = tx.query_row(
            "SELECT COUNT(*) FROM operations WHERE operation_id = ?1",
            [&op_id],
            |r| r.get(0),
        )?;
        if seen > 0 {
            tx.commit()?;
            return Ok(());
        }
        tx.execute(
            "INSERT INTO operations (operation_id, device_id, device_seq, kind, target_id, payload_json, committed_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            rusqlite::params![op_id, device, seq, kind, target, payload.to_string(), committed_at],
        )?;

        match kind.as_str() {
            "object-created" => {
                let obj = &payload["object"];
                let rev = &payload["revision"];
                let object_id = obj["object_id"].as_str().unwrap_or_default();
                let revision_id = rev["revision_id"].as_str().unwrap_or_default();
                tx.execute(
                    "INSERT OR IGNORE INTO objects (object_id, type_id, schema_version, class, origin_device, created_operation, display_created_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                    rusqlite::params![
                        object_id,
                        obj["type_id"].as_str().unwrap_or_default(),
                        obj["schema_version"].as_i64().unwrap_or(1),
                        obj["class"].as_str().unwrap_or("artifact"),
                        device,
                        op_id,
                        obj["display_created_at"].as_i64().unwrap_or(committed_at)
                    ],
                )?;
                tx.execute(
                    "INSERT OR IGNORE INTO revisions (revision_id, object_id, based_on_json, payload_json, created_operation, created_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    rusqlite::params![
                        revision_id,
                        object_id,
                        rev["based_on"].to_string(),
                        rev["payload"].to_string(),
                        op_id,
                        committed_at
                    ],
                )?;
                tx.execute(
                    "INSERT OR IGNORE INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                    rusqlite::params![object_id, revision_id],
                )?;
                if let Some(blob) = payload.get("blob").filter(|b| !b.is_null()) {
                    let blob_id = blob["blob_id"].as_str().unwrap_or_default();
                    // stage_id 'remote' marks bytes that arrive via sync;
                    // recovery leaves them to pull-side assembly.
                    tx.execute(
                        "INSERT OR IGNORE INTO blobs (blob_id, state, media_type, byte_len, sha256, rel_path, stage_id, created_at)
                         VALUES (?1, 'committed', ?2, ?3, ?4, ?5, 'remote', ?6)",
                        rusqlite::params![
                            blob_id,
                            blob["media_type"].as_str().unwrap_or_default(),
                            blob["byte_len"].as_i64().unwrap_or_default(),
                            blob["sha256"].as_str().unwrap_or_default(),
                            blob["rel_path"].as_str().unwrap_or_default(),
                            committed_at
                        ],
                    )?;
                    tx.execute(
                        "INSERT OR IGNORE INTO blob_refs (blob_id, object_id) VALUES (?1, ?2)",
                        rusqlite::params![blob_id, object_id],
                    )?;
                }
                if let Some(event_type) = payload["event_type"].as_str() {
                    let event_id = ids::new_id();
                    jobs::emit_event_in_tx(
                        &tx, &event_id, event_type, "sync", object_id, revision_id, None,
                        &event_id, &device, &local, now,
                    )?;
                }
            }
            "object-revised" => {
                let rev = &payload["revision"];
                let object_id = payload["object_id"].as_str().unwrap_or_default();
                let revision_id = rev["revision_id"].as_str().unwrap_or_default();
                let already: i64 = tx.query_row(
                    "SELECT COUNT(*) FROM revisions WHERE revision_id = ?1",
                    [revision_id],
                    |r| r.get(0),
                )?;
                if already == 0 {
                    tx.execute(
                        "INSERT INTO revisions (revision_id, object_id, based_on_json, payload_json, created_operation, created_at)
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                        rusqlite::params![
                            revision_id,
                            object_id,
                            rev["based_on"].to_string(),
                            rev["payload"].to_string(),
                            op_id,
                            committed_at
                        ],
                    )?;
                    // The new revision supersedes exactly the heads it names;
                    // any other concurrent head is preserved (multi-head).
                    if let Some(supersedes) = payload["supersedes"].as_array() {
                        for s in supersedes {
                            if let Some(rev_id) = s.as_str() {
                                tx.execute(
                                    "DELETE FROM heads WHERE object_id = ?1 AND revision_id = ?2",
                                    rusqlite::params![object_id, rev_id],
                                )?;
                            }
                        }
                    }
                    tx.execute(
                        "INSERT OR IGNORE INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                        rusqlite::params![object_id, revision_id],
                    )?;
                    if let Some(event_type) = payload["event_type"].as_str() {
                        let event_id = ids::new_id();
                        jobs::emit_event_in_tx(
                            &tx, &event_id, event_type, "sync", object_id, revision_id, None,
                            &event_id, &device, &local, now,
                        )?;
                    }
                }
            }
            "tombstone" => {
                tx.execute(
                    "UPDATE objects SET tombstone_json = ?2 WHERE object_id = ?1",
                    rusqlite::params![target, payload["tombstone"].to_string()],
                )?;
            }
            other => {
                // Unknown operation kinds are preserved (the row above) but
                // not interpreted — forward compatibility per constitution 9.
                store::raise_alert(
                    &tx,
                    "sync-unknown-op-kind",
                    &op_id,
                    &format!("kind {other} preserved but not applied"),
                )?;
            }
        }
    }
    tx.commit()?;
    Ok(())
}

/// Complete remote blobs whose chunks have all arrived: assemble in staging,
/// verify the digest, then atomic-rename into the vault.
fn assemble_pending_blobs(k: &mut Kernel, relay: &Path) -> Result<(usize, usize)> {
    let pending: Vec<(String, String, String, i64)> = {
        let mut stmt = k.conn.prepare(
            "SELECT blob_id, rel_path, sha256, byte_len FROM blobs
             WHERE state = 'committed' AND stage_id = 'remote'",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
        })?;
        rows.collect::<std::result::Result<_, _>>()?
    };
    let mut done = 0;
    let mut still_pending = 0;
    for (blob_id, rel_path, sha, byte_len) in pending {
        let dir = blob_dir(relay, &blob_id);
        let manifest_path = dir.join("manifest.json");
        if !manifest_path.exists() {
            still_pending += 1;
            continue;
        }
        let manifest: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&manifest_path)?)?;
        let chunks = manifest["chunks"].as_u64().unwrap_or(0) as usize;
        let mut bytes = Vec::with_capacity(byte_len as usize);
        let mut complete = true;
        for i in 0..chunks {
            match std::fs::read(dir.join(format!("{i}.chunk"))) {
                Ok(mut c) => bytes.append(&mut c),
                Err(_) => {
                    complete = false;
                    break;
                }
            }
        }
        if !complete {
            still_pending += 1;
            continue;
        }
        if ids::sha256_hex(&bytes) != sha {
            store::raise_alert(
                &k.conn,
                "sync-blob-digest-mismatch",
                &blob_id,
                "assembled bytes do not match the operation's digest",
            )?;
            still_pending += 1;
            continue;
        }
        let staging = Kernel::staging_dir(&k.vault).join(format!("remote-{blob_id}"));
        std::fs::write(&staging, &bytes)?;
        std::fs::File::open(&staging)?.sync_all()?;
        let target = k.vault.join(&rel_path);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
            std::fs::rename(&staging, &target)?;
            fsync_dir(parent)?;
        }
        k.conn.execute(
            "UPDATE blobs SET state = 'ready' WHERE blob_id = ?1",
            [&blob_id],
        )?;
        done += 1;
    }
    Ok((done, still_pending))
}
