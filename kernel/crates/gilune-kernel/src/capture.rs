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

/// A capture in staging: bytes stream in chunk by chunk (no whole-copy
/// requirement on callers), then `finish` runs the commit sequence.
/// Dropping a stage without finishing leaves an orphan for recovery to
/// sweep to lost+found — never silent deletion.
pub struct CaptureStage<'a> {
    store: &'a Store,
    file: std::fs::File,
    stage_path: PathBuf,
    hasher: sha2::Sha256,
    bytes: u64,
    extension: String,
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
    pub fn begin_capture(&self, extension: &str) -> Result<CaptureStage<'_>> {
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
            store: self,
            file,
            stage_path,
            hasher: <sha2::Sha256 as sha2::Digest>::new(),
            bytes: 0,
            extension: extension.to_string(),
        })
    }

    /// Convenience for callers whose payload is already in memory.
    pub fn capture_bytes(&self, extension: &str, bytes: &[u8]) -> Result<CaptureReceipt> {
        let mut stage = self.begin_capture(extension)?;
        stage.write_chunk(bytes)?;
        stage.finish()
    }
}

impl CaptureStage<'_> {
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

    /// Commit sequence: durable stage → one transaction → promote.
    pub fn finish(self) -> Result<CaptureReceipt> {
        use sha2::Digest;
        let CaptureStage { store, file, stage_path, hasher, bytes, extension } = self;

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
        let payload = serde_json::json!({
            "media": extension,
            "blob_id": blob_id,
            "rel_path": rel_path,
            "sha256": sha256,
            "bytes": bytes,
        })
        .to_string();

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
            record_operation(conn, "create-source", &source_id, &payload, created_ms)?;
            let event_id = ids::new_id();
            conn.execute(
                "INSERT INTO events (event_id, event_type, subject_id, payload, created_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
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

/// Operations are the sync substrate (ADR 0005): immutable, per-device
/// monotonic, self-describing. Written inside the caller's transaction so
/// a committed change and its operation are inseparable.
pub(crate) fn record_operation(
    conn: &rusqlite::Connection,
    kind: &str,
    target_id: &str,
    payload: &str,
    created_ms: i64,
) -> Result<()> {
    let device_id: String =
        conn.query_row("SELECT value FROM meta WHERE key = 'device_id'", [], |row| row.get(0))?;
    let seq: i64 = conn
        .query_row("SELECT value FROM meta WHERE key = 'op_seq'", [], |row| row.get(0))
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(0),
            e => Err(e),
        })?
        + 1;
    conn.execute(
        "INSERT OR REPLACE INTO meta (key, value) VALUES ('op_seq', ?1)",
        [seq],
    )?;
    conn.execute(
        "INSERT INTO operations (op_id, device_id, op_seq, kind, target_id, payload, created_ms)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        rusqlite::params![ids::new_id(), device_id, seq, kind, target_id, payload, created_ms],
    )?;
    Ok(())
}

fn promote(store: &Store, blob_id: &str, stage_path: &std::path::Path, rel_path: &str) -> Result<()> {
    let target = store.vault().join(rel_path);
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).map_err(|e| KernelError::Io {
            path: parent.to_path_buf(),
            source: e,
        })?;
        std::fs::rename(stage_path, &target).map_err(|e| KernelError::Io {
            path: target.clone(),
            source: e,
        })?;
        fs::fsync_dir(parent)?;
    }
    crashpoint("promote.after");
    store.conn().execute(
        "UPDATE blobs SET state = 'ready' WHERE blob_id = ?1",
        [blob_id],
    )?;
    Ok(())
}

/// Startup recovery, run by `Store::open`. Committed-but-unpromoted blobs
/// finish promoting (or are marked ready if the rename already happened);
/// staged bytes with no committed row move to lost+found — preserved,
/// never silently deleted (failure favours preservation).
pub(crate) fn recover(store: &Store) -> Result<()> {
    let committed: Vec<(String, String)> = {
        let conn = store.conn();
        let mut stmt =
            conn.prepare("SELECT blob_id, rel_path FROM blobs WHERE state = 'committed'")?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        rows.collect::<std::result::Result<_, _>>()?
    };
    for (blob_id, rel_path) in committed {
        let target = store.vault().join(&rel_path);
        if target.exists() {
            store
                .conn()
                .execute("UPDATE blobs SET state = 'ready' WHERE blob_id = ?1", [&blob_id])?;
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
        if let Ok(data) = std::fs::read(&path) {
            if ids::sha256_hex(&data) == expected {
                return Ok(Some(path));
            }
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
        let receipt = stage.finish().expect("finish");
        assert_eq!(receipt.bytes, 7);
        assert_eq!(receipt.sha256, ids::sha256_hex(b"abcdefg"));
    }

    #[test]
    fn an_abandoned_stage_goes_to_lost_and_found_not_oblivion() {
        let dir = tempfile::tempdir().expect("tempdir");
        {
            let store = Store::open(dir.path()).expect("open");
            let mut stage = store.begin_capture("md").expect("begin");
            stage.write_chunk(b"never finished").expect("chunk");
            // stage dropped unfinished: process-death stand-in
        }
        drop(Store::open(dir.path()).expect("reopen runs recovery"));
        let lost: Vec<_> = std::fs::read_dir(dir.path().join(".gilune/local/lost+found"))
            .expect("lost+found exists")
            .collect();
        assert_eq!(lost.len(), 1);
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
    fn a_bad_extension_is_refused_before_any_bytes_move() {
        let (_dir, store) = open();
        assert!(store.begin_capture("../evil").is_err());
        assert!(store.begin_capture("").is_err());
    }
}
