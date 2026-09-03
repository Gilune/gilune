//! The capture commit path (constitution rule 1): original bytes are
//! staged and fsynced; then envelope, blob metadata, operation, event,
//! and subscription jobs commit in one transaction; then the blob
//! promotes into the vault by atomic rename. "Saved" is reported only
//! after the commit; every intermediate state is recoverable on the next
//! open. Capture requires no network, account, model, plugin, or
//! structure.

use crate::crashpoints::crashpoint;
use crate::store::Store;
use crate::{fs, ids, KernelError, Result};
use std::io::Write;
use std::path::PathBuf;

pub const SOURCE_TYPE: &str = "org.memory.source";
pub const SOURCE_READY_EVENT: &str = "org.memory.source-ready";

/// Document key marking a capture as *attached* (ADR 0013): preserved and
/// processed like any other, but belonging to a note the user is already
/// writing, so it projects no note of its own.
pub const ATTACHED_KEY: &str = "attached";

/// Whether a source document says it is attached.
pub fn is_attached(document: &serde_json::Value) -> bool {
    document
        .get(ATTACHED_KEY)
        .and_then(|v| v.as_bool())
        .unwrap_or(false)
}

/// A capture in staging: bytes stream in chunk by chunk (no whole-copy
/// requirement on callers), then `finish` runs the commit sequence.
/// Detached from the store so it can be owned across an FFI boundary;
/// dropping a stage without finishing leaves an orphan for recovery to
/// sweep to lost+found — never silent deletion.
pub struct CaptureStage {
    file: std::fs::File,
    stage_path: PathBuf,
    hasher: sha2::Sha256,
    bytes: u64,
    extension: String,
    attached: bool,
}

/// What a successful capture durably is.
#[derive(Debug, Clone)]
pub struct CaptureReceipt {
    pub source_id: String,
    pub blob_id: String,
    pub rel_path: String,
    pub sha256: String,
    pub bytes: u64,
}

impl Store {
    /// Begin staging a capture. `extension` names the media container
    /// ("md", "wav", "m4a", …) and must be a bare extension.
    pub fn begin_capture(&self, extension: &str) -> Result<CaptureStage> {
        self.begin(extension, false)
    }

    /// Begin staging an *attached* capture (ADR 0013). Durability is
    /// unchanged in every respect — the same staging, fsync, transaction,
    /// promotion, operation, event and jobs — and only the projection
    /// differs: an attached capture belongs to a note the user is already
    /// writing, so it never becomes a note of its own. This is the
    /// dictation substrate: a crash between the words being transcribed
    /// and the shell inserting them loses nothing.
    pub fn begin_attached_capture(&self, extension: &str) -> Result<CaptureStage> {
        self.begin(extension, true)
    }

    fn begin(&self, extension: &str, attached: bool) -> Result<CaptureStage> {
        if extension.is_empty() || !extension.chars().all(|c| c.is_ascii_alphanumeric()) {
            return Err(KernelError::VaultUnusable {
                reason: format!("'{extension}' is not a bare file extension"),
            });
        }
        let stage_path = self
            .staging_dir()
            .join(format!("{}.{extension}", ids::new_id()));
        let file = std::fs::File::create(&stage_path).map_err(|e| KernelError::Io {
            path: stage_path.clone(),
            source: e,
        })?;
        Ok(CaptureStage {
            file,
            stage_path,
            hasher: <sha2::Sha256 as sha2::Digest>::new(),
            bytes: 0,
            extension: extension.to_string(),
            attached,
        })
    }

    /// Convenience for callers whose payload is already in memory.
    pub fn capture_bytes(&self, extension: &str, bytes: &[u8]) -> Result<CaptureReceipt> {
        let mut stage = self.begin_capture(extension)?;
        stage.write_chunk(bytes)?;
        stage.finish(self)
    }

    /// Sweep orphaned stages regardless of age. For tools and tests; the
    /// age gate exists so ordinary recovery cannot steal a live capture
    /// from a concurrent process.
    pub fn force_stage_sweep(&self) -> Result<()> {
        recover_with_sweep_age(self, 0)
    }
}

impl CaptureStage {
    pub fn write_chunk(&mut self, chunk: &[u8]) -> Result<()> {
        use sha2::Digest;
        self.file.write_all(chunk).map_err(|e| KernelError::Io {
            path: self.stage_path.clone(),
            source: e,
        })?;
        self.hasher.update(chunk);
        self.bytes += chunk.len() as u64;
        Ok(())
    }

    /// Commit sequence: durable stage → one transaction → promote. The
    /// stage must finish against the store that began it.
    pub fn finish(self, store: &Store) -> Result<CaptureReceipt> {
        use sha2::Digest;
        let CaptureStage { file, stage_path, hasher, bytes, extension, attached } = self;

        crashpoint("stage.after_write");
        file.sync_all().map_err(|e| KernelError::Io {
            path: stage_path.clone(),
            source: e,
        })?;
        drop(file);
        fs::fsync_dir(&store.staging_dir())?;
        crashpoint("stage.after_fsync");

        let sha256 = hex::encode(hasher.finalize());
        let source_id = ids::new_id();
        let revision_id = ids::new_id();
        let blob_id = ids::new_id();
        let created_ms = ids::now_ms();
        let rel_path = format!("Captures/capture-{}.{extension}", ids::short(&source_id));
        let mut document = serde_json::json!({
            "media": extension,
            "blob_id": blob_id,
            "rel_path": rel_path,
            "sha256": sha256,
            "bytes": bytes,
        });
        if attached {
            document[ATTACHED_KEY] = serde_json::Value::Bool(true);
        }
        let payload = document.to_string();

        let conn = store.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let committed: Result<()> = (|| {
            conn.execute(
                "INSERT INTO objects (object_id, type_id, type_schema, created_ms)
                 VALUES (?1, ?2, 1, ?3)",
                rusqlite::params![source_id, SOURCE_TYPE, created_ms],
            )?;
            conn.execute(
                "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
                 VALUES (?1, ?2, ?3, 'capture', ?4)",
                rusqlite::params![revision_id, source_id, payload, created_ms],
            )?;
            conn.execute(
                "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                rusqlite::params![source_id, revision_id],
            )?;
            conn.execute(
                "INSERT INTO blobs (blob_id, object_id, rel_path, sha256, bytes, state)
                 VALUES (?1, ?2, ?3, ?4, ?5, 'committed')",
                rusqlite::params![blob_id, source_id, rel_path, sha256, bytes as i64],
            )?;
            crate::operations::record(
                conn,
                crate::operations::OBJECT_CREATED,
                &source_id,
                serde_json::json!({
                    "object": {
                        "object_id": source_id,
                        "type_id": SOURCE_TYPE,
                        "type_schema": 1,
                        "created_ms": created_ms,
                    },
                    "revision": {
                        "revision_id": revision_id,
                        "payload": payload,
                        "origin": "capture",
                        "created_ms": created_ms,
                    },
                    "blob": {
                        "blob_id": blob_id,
                        "rel_path": rel_path,
                        "sha256": sha256,
                        "bytes": bytes,
                    },
                    "event_type": SOURCE_READY_EVENT,
                }),
                created_ms,
            )?;
            let event_id = ids::new_id();
            conn.execute(
                "INSERT INTO events
                 (event_id, event_type, subject_id, payload, created_ms, origin_device)
                 VALUES (?1, ?2, ?3, ?4, ?5,
                         (SELECT value FROM meta WHERE key = 'device_id'))",
                rusqlite::params![event_id, SOURCE_READY_EVENT, source_id, payload, created_ms],
            )?;
            conn.execute(
                "INSERT INTO jobs (job_id, subscription_id, event_id, state)
                 SELECT ?1 || subscription_id, subscription_id, ?2, 'pending'
                 FROM subscriptions WHERE event_type = ?3 AND enabled = 1",
                rusqlite::params![event_id, event_id, SOURCE_READY_EVENT],
            )?;
            crashpoint("commit.inside");
            Ok(())
        })();
        match committed {
            Ok(()) => conn.execute_batch("COMMIT;")?,
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                return Err(e);
            }
        }
        crashpoint("commit.after");

        promote(store, &blob_id, &stage_path, &rel_path)?;

        Ok(CaptureReceipt { source_id, blob_id, rel_path, sha256, bytes })
    }
}

/// Move staged bytes into the vault and mark the blob ready. **The one
/// promotion**: a capture's own bytes and a blob that arrived over a
/// transport take exactly this path, because they are the same act and
/// two copies of it had already drifted — `blobs::finish_blob` cleaned up
/// after a lost race and this did not.
pub(crate) fn promote(
    store: &Store,
    blob_id: &str,
    stage_path: &std::path::Path,
    rel_path: &str,
) -> Result<()> {
    let target = store.vault().join(rel_path);
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).map_err(|e| KernelError::Io {
            path: parent.to_path_buf(),
            source: e,
        })?;
        // Idempotent under concurrency: another process's recovery may
        // have completed this promotion between our commit and here.
        match std::fs::rename(stage_path, &target) {
            Ok(()) => {}
            Err(e) if target.exists() => {
                // Promotion already happened elsewhere, and the stage is
                // now a duplicate of bytes that are safely in the vault.
                // It has to go: leaving it violates recovery's own
                // invariant that "promoted stages were renamed away;
                // whatever remains is orphaned", so the sweep would file
                // the user's recording in lost+found as if nobody had
                // finished it — a second copy of a capture that
                // succeeded, reported as one that failed.
                let _ = e;
                let _ = std::fs::remove_file(stage_path);
            }
            Err(e) => return Err(KernelError::Io { path: target.clone(), source: e }),
        }
        fs::fsync_dir(parent)?;
    }
    crashpoint("promote.after");
    store.conn().execute(
        "UPDATE blobs SET state = 'ready' WHERE blob_id = ?1",
        [blob_id],
    )?;
    Ok(())
}

/// How old an orphaned stage must be before recovery sweeps it. Another
/// process may be mid-capture: its stage is orphaned only in appearance,
/// and stealing it would fail a live capture (multi-instance is
/// supported use). Any real commit completes orders of magnitude faster
/// than this.
pub const STAGE_SWEEP_AGE_MS: u128 = 15 * 60 * 1000;

/// Startup recovery, run by `Store::open` with the default sweep age.
/// Committed-but-unpromoted blobs finish promoting (or are marked ready
/// if the rename already happened); staged bytes with no committed row
/// move to lost+found once old enough that no live process can own them
/// — preserved, never silently deleted (failure favours preservation).
pub(crate) fn recover(store: &Store) -> Result<()> {
    recover_with_sweep_age(store, STAGE_SWEEP_AGE_MS)
}

pub(crate) fn recover_with_sweep_age(store: &Store, sweep_age_ms: u128) -> Result<()> {
    let committed: Vec<(String, String, String)> = {
        let conn = store.conn();
        let mut stmt = conn
            .prepare("SELECT blob_id, rel_path, origin FROM blobs WHERE state = 'committed'")?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))?;
        rows.collect::<std::result::Result<_, _>>()?
    };
    for (blob_id, rel_path, origin) in committed {
        let target = store.vault().join(&rel_path);
        if target.exists() {
            // A promotion that landed before the process died. For bytes
            // that arrived over a transport the digest is checked again
            // here, because this is the only path that did not check it
            // as it wrote — the promoting one did.
            if origin == "sync" && !crate::blobs::promoted_bytes_are_sound(store, &blob_id)? {
                continue;
            }
            store
                .conn()
                .execute("UPDATE blobs SET state = 'ready' WHERE blob_id = ?1", [&blob_id])?;
            continue;
        }
        // Bytes still arriving from another device have no stage file
        // here to find: their transfer, not recovery, completes them.
        if origin != "local" {
            continue;
        }
        // The stage file is findable by digest match: any staged file whose
        // bytes match this row's digest completes it.
        if let Some(stage) = find_stage_by_digest(store, &blob_id)? {
            promote(store, &blob_id, &stage, &rel_path)?;
        }
        // Neither staged nor promoted: the row stays 'committed' and
        // verify reports it — visible, not silently repaired.
    }

    // Promoted stages were renamed away; whatever remains is orphaned.
    let lost = store.lost_found_dir();
    for entry in std::fs::read_dir(store.staging_dir()).map_err(|e| KernelError::Io {
        path: store.staging_dir(),
        source: e,
    })? {
        let entry = entry.map_err(|e| KernelError::Io { path: store.staging_dir(), source: e })?;
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        // A blob transfer in progress lives in staging too, and it is not
        // an orphan however long it has been paused: its bytes belong to
        // an operation that has already applied.
        if crate::blobs::is_incoming(&entry.file_name().to_string_lossy()) {
            continue;
        }
        let age_ms = entry
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.elapsed().ok())
            .map(|d| d.as_millis())
            .unwrap_or(u128::MAX);
        if age_ms < sweep_age_ms {
            continue; // possibly a live capture in another process
        }
        std::fs::create_dir_all(&lost).map_err(|e| KernelError::Io {
            path: lost.clone(),
            source: e,
        })?;
        let dest = lost.join(entry.file_name());
        std::fs::rename(&path, &dest).map_err(|e| KernelError::Io { path: dest, source: e })?;
    }
    Ok(())
}

fn find_stage_by_digest(store: &Store, blob_id: &str) -> Result<Option<PathBuf>> {
    let expected: String = store.conn().query_row(
        "SELECT sha256 FROM blobs WHERE blob_id = ?1",
        [blob_id],
        |row| row.get(0),
    )?;
    for entry in std::fs::read_dir(store.staging_dir()).map_err(|e| KernelError::Io {
        path: store.staging_dir(),
        source: e,
    })? {
        let entry = entry.map_err(|e| KernelError::Io { path: store.staging_dir(), source: e })?;
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        if crate::blobs::is_incoming(&entry.file_name().to_string_lossy()) {
            continue;
        }
        // Streamed: recovery runs on every open, and a vault with a
        // half-gigabyte recording abandoned in staging must not need it
        // in memory to find out whose it is.
        if crate::blobs::digest_of(&path).ok().as_deref() == Some(expected.as_str()) {
            return Ok(Some(path));
        }
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn open() -> (tempfile::TempDir, Store) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        (dir, store)
    }

    #[test]
    fn capture_commits_envelope_blob_operation_and_event_atomically() {
        let (dir, store) = open();
        let receipt = store.capture_bytes("md", b"remember the milk").expect("capture");
        let status = store.status().expect("status");
        assert_eq!(status.sources, 1);
        assert_eq!(status.blobs_ready, 1);
        assert_eq!(status.events, 1);
        assert_eq!(status.operations, 1);
        assert!(store.verify().expect("verify").is_empty());

        let on_disk = std::fs::read(dir.path().join(&receipt.rel_path)).expect("blob file");
        assert_eq!(on_disk, b"remember the milk");
        assert_eq!(ids::sha256_hex(&on_disk), receipt.sha256);
    }

    #[test]
    fn chunked_capture_equals_whole_capture() {
        let (_dir, store) = open();
        let mut stage = store.begin_capture("wav").expect("begin");
        stage.write_chunk(b"abc").expect("chunk");
        stage.write_chunk(b"defg").expect("chunk");
        let receipt = stage.finish(&store).expect("finish");
        assert_eq!(receipt.bytes, 7);
        assert_eq!(receipt.sha256, ids::sha256_hex(b"abcdefg"));
    }

    #[test]
    fn an_abandoned_stage_survives_until_it_cannot_be_a_live_capture() {
        let dir = tempfile::tempdir().expect("tempdir");
        {
            let store = Store::open(dir.path()).expect("open");
            let mut stage = store.begin_capture("md").expect("begin");
            stage.write_chunk(b"never finished").expect("chunk");
            // stage dropped unfinished: process-death stand-in
        }
        let store = Store::open(dir.path()).expect("reopen runs recovery");
        // A fresh stage is NOT swept: it could belong to a concurrent
        // process mid-capture (multi-instance is supported use).
        assert!(!dir.path().join(".gilune/local/lost+found").exists());
        // Past the age gate it sweeps to lost+found, never to oblivion.
        store.force_stage_sweep().expect("sweep");
        let lost: Vec<_> = std::fs::read_dir(dir.path().join(".gilune/local/lost+found"))
            .expect("lost+found exists")
            .collect();
        assert_eq!(lost.len(), 1);
    }

    #[test]
    fn an_attached_capture_commits_exactly_as_any_other_does() {
        let (dir, store) = open();
        let mut stage = store.begin_attached_capture("wav").expect("begin");
        stage.write_chunk(b"dictated mid-note").expect("chunk");
        let receipt = stage.finish(&store).expect("finish");

        let status = store.status().expect("status");
        assert_eq!(status.sources, 1);
        assert_eq!(status.blobs_ready, 1, "the bytes are promoted, not held");
        assert_eq!(status.events, 1, "it is announced like any capture, so it is processed");
        assert_eq!(status.operations, 1, "and it reaches other devices");
        assert!(store.verify().expect("verify").is_empty());
        assert_eq!(
            std::fs::read(dir.path().join(&receipt.rel_path)).expect("blob"),
            b"dictated mid-note"
        );

        let payload: String = store
            .conn()
            .query_row("SELECT payload FROM revisions", [], |row| row.get(0))
            .expect("revision");
        let document: serde_json::Value = serde_json::from_str(&payload).expect("json");
        assert!(is_attached(&document));
    }

    #[test]
    fn an_ordinary_capture_says_nothing_about_attachment() {
        let (_dir, store) = open();
        store.capture_bytes("md", b"plain").expect("capture");
        let payload: String = store
            .conn()
            .query_row("SELECT payload FROM revisions", [], |row| row.get(0))
            .expect("revision");
        let document: serde_json::Value = serde_json::from_str(&payload).expect("json");
        assert!(document.get(ATTACHED_KEY).is_none(), "the ordinary document is unchanged");
    }

    #[test]
    fn operations_are_per_device_monotonic() {
        let (_dir, store) = open();
        store.capture_bytes("md", b"one").expect("capture");
        store.capture_bytes("md", b"two").expect("capture");
        let seqs: Vec<i64> = {
            let mut stmt = store
                .conn()
                .prepare("SELECT op_seq FROM operations ORDER BY op_seq")
                .expect("stmt");
            stmt.query_map([], |row| row.get(0))
                .expect("query")
                .collect::<std::result::Result<_, _>>()
                .expect("rows")
        };
        assert_eq!(seqs, vec![1, 2]);
    }

    #[test]
    fn a_promotion_that_lost_the_race_leaves_no_second_copy_of_the_recording() {
        // Multi-instance is supported use (owner decision 2026-08-31), so
        // another process's recovery can promote these bytes between this
        // one's commit and its own rename. The bytes are safe either way
        // — but the stage left behind is a duplicate, and recovery's rule
        // is "promoted stages were renamed away; whatever remains is
        // orphaned", so the sweep filed a recording that had succeeded in
        // lost+found as one that had failed.
        let (dir, store) = open();
        let receipt = store.capture_bytes("wav", &[9u8; 128]).expect("capture");
        let stage = store.staging_dir().join("racing.wav");
        std::fs::write(&stage, [9u8; 128]).expect("a stage this process still holds");

        promote(&store, &receipt.blob_id, &stage, &receipt.rel_path).expect("promote");
        assert!(!stage.exists(), "the duplicate stage goes with the race it lost");
        assert_eq!(store.status().expect("status").blobs_ready, 1, "and the blob is ready");

        // One step past: the sweep is where the duplicate would have
        // surfaced, so it is the sweep that has to find nothing.
        store.force_stage_sweep().expect("sweep past every age gate");
        assert!(
            !dir.path().join(".gilune/local/lost+found").exists(),
            "a capture that succeeded must not also be reported as one that failed"
        );
        assert_eq!(
            std::fs::read(dir.path().join(&receipt.rel_path)).expect("blob"),
            vec![9u8; 128]
        );
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn a_bad_extension_is_refused_before_any_bytes_move() {
        let (_dir, store) = open();
        assert!(store.begin_capture("../evil").is_err());
        assert!(store.begin_capture("").is_err());
    }
}
