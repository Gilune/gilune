//! Capture: stage bytes, commit the minimum envelope atomically, promote the
//! blob into the vault, reconcile on restart.
//!
//! The sequence and its crash points follow 03-data-model.md "Blobs". The
//! original bytes live once, visibly, inside the vault (`Captures/…`), so the
//! vault is the blob store for originals; only the database and staging area
//! are device-local.

use crate::crashpoints::crashpoint;
use crate::{fsync_dir, ids, jobs, store, Kernel};
use anyhow::{bail, Context, Result};
use std::io::Write;
use std::path::PathBuf;

pub const SOURCE_TYPE: &str = "memory.capture/source";
pub const SOURCE_READY: &str = "memory.capture/source-ready";

pub struct CaptureOutcome {
    pub object_id: String,
    pub revision_id: String,
    pub blob_rel_path: String,
}

fn extension_for(media_type: &str) -> &'static str {
    match media_type {
        "audio/wav" => "wav",
        "audio/ogg" => "ogg",
        "text/plain" => "txt",
        _ => "bin",
    }
}

/// Stage + commit + promote one capture. Prints nothing; the caller reports
/// "saved" only after this returns from the commit step.
pub fn capture(k: &mut Kernel, bytes: &[u8], media_type: &str) -> Result<CaptureOutcome> {
    // 1. Stage bytes under a private ID and make them durable.
    let stage_id = ids::new_id();
    let staging = Kernel::staging_dir(&k.vault);
    let stage_path = staging.join(&stage_id);
    {
        let mut f = std::fs::File::create(&stage_path)?;
        f.write_all(bytes)?;
        crashpoint("stage.after_write");
        f.sync_all()?;
    }
    fsync_dir(&staging)?;
    crashpoint("stage.after_fsync");

    let sha = ids::sha256_hex(bytes);
    let object_id = ids::new_id();
    let revision_id = ids::new_id();
    let blob_id = ids::new_id();
    let now = Kernel::now_ms();
    // UUIDv7 begins with a timestamp, so the LEADING chars collide for
    // objects created close together; the trailing chars are random.
    let rel_path = format!(
        "Captures/capture-{}.{}",
        &object_id[object_id.len() - 12..],
        extension_for(media_type)
    );

    // 2. Commit envelope, blob metadata, reference, operation, event, and
    //    subscription jobs in ONE transaction (04-processing-model.md).
    let device = k.device_id.clone();
    let tx = k.conn.transaction()?;
    {
        let payload = serde_json::json!({
            "media_type": media_type,
            "byte_len": bytes.len(),
            "sha256": sha,
        });
        let op_payload = serde_json::json!({
            "object": { "object_id": &object_id, "type_id": SOURCE_TYPE,
                        "schema_version": 1, "class": "source",
                        "display_created_at": now },
            "revision": { "revision_id": &revision_id, "based_on": [],
                          "payload": &payload },
            "blob": { "blob_id": &blob_id, "media_type": media_type,
                      "byte_len": bytes.len(), "sha256": &sha,
                      "rel_path": &rel_path },
            "event_type": SOURCE_READY,
        });
        let op_id =
            store::record_operation(&tx, &device, "object-created", &object_id, &op_payload, now)?;
        tx.execute(
            "INSERT INTO objects (object_id, type_id, schema_version, class, origin_device, created_operation, display_created_at)
             VALUES (?1, ?2, 1, 'source', ?3, ?4, ?5)",
            rusqlite::params![object_id, SOURCE_TYPE, device, op_id, now],
        )?;
        tx.execute(
            "INSERT INTO revisions (revision_id, object_id, based_on_json, payload_json, created_operation, created_at)
             VALUES (?1, ?2, '[]', ?3, ?4, ?5)",
            rusqlite::params![revision_id, object_id, payload.to_string(), op_id, now],
        )?;
        tx.execute(
            "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
            rusqlite::params![object_id, revision_id],
        )?;
        tx.execute(
            "INSERT INTO blobs (blob_id, state, media_type, byte_len, sha256, rel_path, stage_id, created_at)
             VALUES (?1, 'committed', ?2, ?3, ?4, ?5, ?6, ?7)",
            rusqlite::params![blob_id, media_type, bytes.len(), sha, rel_path, stage_id, now],
        )?;
        tx.execute(
            "INSERT INTO blob_refs (blob_id, object_id) VALUES (?1, ?2)",
            rusqlite::params![blob_id, object_id],
        )?;
        let event_id = ids::new_id();
        let correlation = event_id.clone();
        jobs::emit_event_in_tx(
            &tx,
            &event_id,
            SOURCE_READY,
            "kernel",
            &object_id,
            &revision_id,
            None,
            &correlation,
            &device,
            &device,
            now,
        )?;
        crashpoint("commit.inside");
    }
    tx.commit()?;
    crashpoint("commit.after");

    // 3. Promote: atomic rename into the vault, then record 'ready'.
    promote_blob(k, &blob_id)?;

    Ok(CaptureOutcome {
        object_id,
        revision_id,
        blob_rel_path: rel_path,
    })
}

fn promote_blob(k: &mut Kernel, blob_id: &str) -> Result<()> {
    let (rel_path, stage_id): (String, String) = k.conn.query_row(
        "SELECT rel_path, stage_id FROM blobs WHERE blob_id = ?1",
        [blob_id],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    let target = k.vault.join(&rel_path);
    let stage_path = Kernel::staging_dir(&k.vault).join(&stage_id);
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
        std::fs::rename(&stage_path, &target)
            .with_context(|| format!("promoting blob {blob_id} to {rel_path}"))?;
        fsync_dir(parent)?;
    }
    crashpoint("promote.after");
    k.conn.execute(
        "UPDATE blobs SET state = 'ready' WHERE blob_id = ?1",
        [blob_id],
    )?;
    Ok(())
}

/// Startup reconciliation of staged and committed blob state
/// (03-data-model.md step 5). Never deletes metadata; never hides a
/// committed source; orphaned stages move to lost+found.
pub fn recover_blobs(k: &mut Kernel) -> Result<()> {
    // Committed-but-unpromoted blobs: finish the promotion if the bytes
    // exist in either place; otherwise raise a visible integrity alert.
    // stage_id 'remote' marks blobs whose bytes arrive via sync; their
    // completion belongs to the pull path, not local staging recovery.
    let pending: Vec<(String, String, String)> = {
        let mut stmt = k.conn.prepare(
            "SELECT blob_id, rel_path, stage_id FROM blobs
             WHERE state = 'committed' AND stage_id != 'remote'",
        )?;
        let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
        rows.collect::<std::result::Result<_, _>>()?
    };
    for (blob_id, rel_path, stage_id) in pending {
        let target = k.vault.join(&rel_path);
        let staged = Kernel::staging_dir(&k.vault).join(&stage_id);
        if target.exists() {
            k.conn.execute(
                "UPDATE blobs SET state = 'ready' WHERE blob_id = ?1",
                [&blob_id],
            )?;
        } else if staged.exists() {
            promote_blob(k, &blob_id)?;
        } else {
            store::raise_alert(
                &k.conn,
                "missing-blob-bytes",
                &blob_id,
                &format!("blob committed but neither staged nor promoted ({rel_path})"),
            )?;
        }
    }

    // Staged files with no committed blob row are abandoned pre-commit
    // stages: move aside rather than delete, so nothing is hidden.
    let staging = Kernel::staging_dir(&k.vault);
    let known: Vec<String> = {
        let mut stmt = k.conn.prepare("SELECT stage_id FROM blobs")?;
        let rows = stmt.query_map([], |r| r.get(0))?;
        rows.collect::<std::result::Result<_, _>>()?
    };
    for entry in std::fs::read_dir(&staging)? {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().to_string();
        if !known.contains(&name) {
            let dest = Kernel::lost_found_dir(&k.vault).join(&name);
            std::fs::rename(entry.path(), dest)?;
        }
    }
    Ok(())
}

/// Read the ready bytes of a blob referenced by an object.
pub fn read_source_bytes(k: &Kernel, object_id: &str) -> Result<(Vec<u8>, String)> {
    let (rel_path, state, media_type): (String, String, String) = k.conn.query_row(
        "SELECT b.rel_path, b.state, b.media_type
         FROM blobs b JOIN blob_refs r ON r.blob_id = b.blob_id
         WHERE r.object_id = ?1",
        [object_id],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    )?;
    if state != "ready" {
        bail!("blob for {object_id} not ready");
    }
    let bytes = std::fs::read(k.vault.join(&rel_path))?;
    Ok((bytes, media_type))
}

pub fn blob_paths(k: &Kernel) -> Result<Vec<(String, PathBuf, String)>> {
    let mut stmt = k
        .conn
        .prepare("SELECT blob_id, rel_path, sha256 FROM blobs WHERE state = 'ready'")?;
    let rows = stmt.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
        ))
    })?;
    let mut out = Vec::new();
    for row in rows {
        let (id, rel, sha) = row?;
        out.push((id, PathBuf::from(rel), sha));
    }
    Ok(out)
}
