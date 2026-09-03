//! Blob content arriving from another device. The operation describing a
//! blob applies long before its bytes finish crossing (ADR 0005), so this
//! module owns the gap between the two: bytes stream into the same
//! staging area a local capture uses, are hashed only as they are read
//! back, and promote into the vault by the same atomic rename.
//!
//! Nothing here ever allocates from a length a peer declared (ADR 0019
//! § 8). A transfer that stops resumes from what is already staged,
//! because the staged length *is* the resume point — no separate cursor
//! to disagree with the file.

use crate::alerts;
use crate::limits::Limits;
use crate::operations::BlobNeed;
use crate::store::Store;
use crate::{fs, KernelError, Result};
use std::io::{Read, Seek, Write};
use std::path::PathBuf;

/// Staged remote content is named so recovery can tell it from an
/// abandoned capture: a paused transfer is not an orphan, and sweeping it
/// to lost+found would make a long transfer impossible to finish.
const INCOMING_PREFIX: &str = "sync-";

pub(crate) fn incoming_path(store: &Store, blob_id: &str) -> PathBuf {
    store.staging_dir().join(format!("{INCOMING_PREFIX}{blob_id}"))
}

/// Whether a staging file belongs to a transfer in progress rather than
/// to a capture that never committed. Recovery asks before sweeping.
pub(crate) fn is_incoming(name: &str) -> bool {
    name.starts_with(INCOMING_PREFIX)
}

/// Bytes already staged for a blob — the resume point, and zero for a
/// transfer that has not started. Never fails: an unreadable stage is
/// simply a transfer that starts again.
pub(crate) fn staged_bytes(store: &Store, blob_id: &str) -> u64 {
    std::fs::metadata(incoming_path(store, blob_id))
        .map(|m| m.len())
        .unwrap_or(0)
}

impl Store {
    /// Blobs whose operation has applied but whose content has not
    /// arrived, with how much of each is already staged. What an exchange
    /// loop asks for at the start of a session, and what makes an
    /// interrupted transfer cost only the bytes it had not yet sent.
    pub fn blobs_awaiting_bytes(&self) -> Result<Vec<BlobNeed>> {
        let mut stmt = self.conn().prepare(
            "SELECT blob_id, sha256, bytes FROM blobs
             WHERE state = 'committed' AND origin = 'sync' ORDER BY blob_id",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
            ))
        })?;
        let mut needs = Vec::new();
        for row in rows {
            let (blob_id, sha256, bytes) = row?;
            let received = staged_bytes(self, &blob_id);
            needs.push(BlobNeed {
                blob_id,
                sha256,
                bytes: bytes.max(0) as u64,
                received,
            });
        }
        Ok(needs)
    }

    /// Append one chunk of a blob's content at `offset`, which must be
    /// exactly what is already staged — a sender that has lost its place
    /// is told the truth rather than allowed to write a hole. Returns the
    /// new staged length.
    ///
    /// Refuses, without writing, a chunk that would take the content past
    /// the length its operation declared. That is the second half of not
    /// trusting a declared length: the declaration bounds the transfer,
    /// and the transfer is bounded again by what actually arrives.
    pub fn write_blob_chunk(
        &self,
        blob_id: &str,
        offset: u64,
        chunk: &[u8],
        limits: Limits,
    ) -> Result<u64> {
        let declared = self.declared_blob_length(blob_id)?;
        let path = incoming_path(self, blob_id);
        let staged = staged_bytes(self, blob_id);
        if offset != staged {
            return Err(KernelError::SyncHalted {
                device_id: blob_id.to_string(),
                reason: format!("chunk offered at {offset}, but {staged} bytes are staged"),
            });
        }
        if chunk.len() > limits.chunk_bytes {
            return Err(KernelError::SyncHalted {
                device_id: blob_id.to_string(),
                reason: format!(
                    "chunk of {} bytes is over the {}-byte chunk limit",
                    chunk.len(),
                    limits.chunk_bytes
                ),
            });
        }
        let would_be = staged.saturating_add(chunk.len() as u64);
        if would_be > declared {
            return Err(KernelError::SyncHalted {
                device_id: blob_id.to_string(),
                reason: format!(
                    "content would reach {would_be} bytes, past the declared {declared}"
                ),
            });
        }
        let mut file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)
            .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        file.write_all(chunk)
            .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        file.sync_all()
            .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        Ok(would_be)
    }

    /// Complete a transfer: verify the staged bytes against the digest the
    /// operation declared, then promote them into the vault by atomic
    /// rename. `Ok(false)` means the content is not all here yet — the
    /// ordinary state of an interrupted transfer, not a failure.
    ///
    /// A digest mismatch raises a visible alert and discards the staged
    /// bytes, because bytes that failed their digest are the one thing
    /// preservation must not keep: they are not the user's content.
    pub fn finish_blob(&self, blob_id: &str) -> Result<bool> {
        let (rel_path, sha256, declared) = self.blob_row(blob_id)?;
        let path = incoming_path(self, blob_id);
        if staged_bytes(self, blob_id) != declared {
            return Ok(false);
        }
        let actual = digest_of(&path)?;
        if actual != sha256 {
            alerts::raise(
                self.conn(),
                alerts::SYNC_BLOB_DIGEST_MISMATCH,
                blob_id,
                &format!("staged bytes hash to {actual}, not the declared {sha256}"),
            )?;
            std::fs::remove_file(&path)
                .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
            return Ok(false);
        }
        let target = self.vault().join(&rel_path);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| KernelError::Io { path: parent.to_path_buf(), source: e })?;
            match std::fs::rename(&path, &target) {
                Ok(()) => {}
                // Another process completed the same transfer between our
                // check and here: multi-instance is supported use.
                Err(e) if target.exists() => {
                    let _ = e;
                    let _ = std::fs::remove_file(&path);
                }
                Err(e) => return Err(KernelError::Io { path: target.clone(), source: e }),
            }
            fs::fsync_dir(parent)?;
        }
        crate::crashpoints::crashpoint("blob.promote.after");
        self.conn().execute(
            "UPDATE blobs SET state = 'ready' WHERE blob_id = ?1",
            [blob_id],
        )?;
        alerts::clear(self.conn(), alerts::SYNC_BLOB_DIGEST_MISMATCH, blob_id)?;
        Ok(true)
    }

    /// Read a slice of a blob this device already holds, for sending. The
    /// caller bounds `len`; the file is never read whole.
    pub fn blob_chunk(&self, blob_id: &str, offset: u64, len: usize) -> Result<Vec<u8>> {
        let (rel_path, _, _) = self.blob_row(blob_id)?;
        let path = self.vault().join(&rel_path);
        let mut file = std::fs::File::open(&path)
            .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        file.seek(std::io::SeekFrom::Start(offset))
            .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        let mut buffer = vec![0u8; len];
        let mut filled = 0;
        while filled < len {
            let read = file
                .read(&mut buffer[filled..])
                .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
            if read == 0 {
                break;
            }
            filled += read;
        }
        buffer.truncate(filled);
        Ok(buffer)
    }

    fn declared_blob_length(&self, blob_id: &str) -> Result<u64> {
        Ok(self.blob_row(blob_id)?.2)
    }

    pub(crate) fn blob_row(&self, blob_id: &str) -> Result<(String, String, u64)> {
        self.conn()
            .query_row(
                "SELECT rel_path, sha256, bytes FROM blobs WHERE blob_id = ?1",
                [blob_id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, i64>(2)?.max(0) as u64,
                    ))
                },
            )
            .map_err(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => KernelError::Corrupt {
                    detail: format!("no blob {blob_id}"),
                },
                e => KernelError::Storage(e),
            })
    }
}

/// Whether the vault file for a transferred blob really is the content
/// its operation declared. Asked by recovery about a promotion that may
/// have been interrupted between the rename and the row update — the one
/// window in which bytes reach the vault without this being checked.
pub(crate) fn promoted_bytes_are_sound(store: &Store, blob_id: &str) -> Result<bool> {
    let (rel_path, sha256, declared) = store.blob_row(blob_id)?;
    let path = store.vault().join(&rel_path);
    let Ok(metadata) = std::fs::metadata(&path) else { return Ok(false) };
    if metadata.len() != declared {
        return Ok(false);
    }
    Ok(digest_of(&path)? == sha256)
}

/// Hash a staged file by reading it back a chunk at a time. Deliberately
/// not a running hasher held across chunk writes: a transfer that resumes
/// after a restart has no such hasher, and one code path that always
/// works beats two that agree only while the process lives.
fn digest_of(path: &std::path::Path) -> Result<String> {
    use sha2::Digest;
    let mut file = std::fs::File::open(path)
        .map_err(|e| KernelError::Io { path: path.to_path_buf(), source: e })?;
    let mut hasher = <sha2::Sha256 as sha2::Digest>::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .map_err(|e| KernelError::Io { path: path.to_path_buf(), source: e })?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex::encode(hasher.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ids;

    /// A store holding one blob row awaiting `content` from elsewhere.
    fn awaiting(content: &[u8]) -> (tempfile::TempDir, Store, String) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let blob_id = ids::new_id();
        store
            .conn()
            .execute(
                "INSERT INTO objects (object_id, type_id, type_schema, created_ms)
                 VALUES ('owner', 'org.memory.source', 1, 1)",
                [],
            )
            .expect("object");
        store
            .conn()
            .execute(
                "INSERT INTO blobs (blob_id, object_id, rel_path, sha256, bytes, state, origin)
                 VALUES (?1, 'owner', 'Captures/remote.wav', ?2, ?3, 'committed', 'sync')",
                rusqlite::params![blob_id, ids::sha256_hex(content), content.len() as i64],
            )
            .expect("blob row");
        (dir, store, blob_id)
    }

    #[test]
    fn an_interrupted_transfer_resumes_from_what_is_already_staged() {
        let content: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        let (dir, store, blob_id) = awaiting(&content);
        let limits = Limits::DEFAULT;

        store.write_blob_chunk(&blob_id, 0, &content[..2000], limits).expect("first");
        assert!(!store.finish_blob(&blob_id).expect("incomplete"), "not all here yet");
        assert_eq!(
            store.blobs_awaiting_bytes().expect("awaiting")[0].received,
            2000,
            "the staged length is the resume point"
        );

        store.write_blob_chunk(&blob_id, 2000, &content[2000..], limits).expect("rest");
        assert!(store.finish_blob(&blob_id).expect("complete"));
        assert_eq!(
            std::fs::read(dir.path().join("Captures/remote.wav")).expect("promoted"),
            content
        );
        assert!(store.blobs_awaiting_bytes().expect("awaiting").is_empty());
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn a_chunk_offered_at_the_wrong_offset_is_refused_rather_than_leaving_a_hole() {
        let (_dir, store, blob_id) = awaiting(&[7u8; 100]);
        assert!(matches!(
            store.write_blob_chunk(&blob_id, 50, &[7u8; 10], Limits::DEFAULT),
            Err(KernelError::SyncHalted { .. })
        ));
        assert_eq!(staged_bytes(&store, &blob_id), 0);
    }

    #[test]
    fn content_past_the_declared_length_is_refused_before_it_is_written() {
        let (_dir, store, blob_id) = awaiting(&[1u8; 64]);
        store.write_blob_chunk(&blob_id, 0, &[1u8; 64], Limits::DEFAULT).expect("exact fit");
        assert!(matches!(
            store.write_blob_chunk(&blob_id, 64, &[1u8; 1], Limits::DEFAULT),
            Err(KernelError::SyncHalted { .. })
        ));
        assert_eq!(staged_bytes(&store, &blob_id), 64);
    }

    #[test]
    fn bytes_that_fail_their_digest_are_discarded_visibly_rather_than_promoted() {
        let (dir, store, blob_id) = awaiting(&[3u8; 32]);
        store.write_blob_chunk(&blob_id, 0, &[9u8; 32], Limits::DEFAULT).expect("wrong bytes");
        assert!(!store.finish_blob(&blob_id).expect("refused"));
        assert!(!dir.path().join("Captures/remote.wav").exists());
        assert_eq!(
            store.integrity_alerts_of(alerts::SYNC_BLOB_DIGEST_MISMATCH).expect("alerts").len(),
            1
        );
        assert_eq!(staged_bytes(&store, &blob_id), 0, "the transfer can start again");
    }

    #[test]
    fn a_paused_transfer_is_not_swept_away_as_an_abandoned_capture() {
        let (_dir, store, blob_id) = awaiting(&[5u8; 200]);
        store.write_blob_chunk(&blob_id, 0, &[5u8; 100], Limits::DEFAULT).expect("half");
        store.force_stage_sweep().expect("sweep past every age gate");
        assert_eq!(
            staged_bytes(&store, &blob_id),
            100,
            "a transfer in progress is not an orphan"
        );
    }
}
