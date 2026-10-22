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
    ///
    /// Every refusal takes the same path as a refused operation: a
    /// visible alert about the blob, and a halt that stops this transfer
    /// and nothing else. Before, the three refusals here returned a halt
    /// nobody raised an alert for and the exchange loop turned into a
    /// session-killing error.
    pub fn write_blob_chunk(
        &self,
        blob_id: &str,
        offset: u64,
        chunk: &[u8],
        limits: Limits,
    ) -> Result<u64> {
        let declared = self.declared_blob_length(blob_id)?;
        self.write_blob_chunk_bounded(blob_id, declared, offset, chunk, limits)
    }

    /// As `write_blob_chunk`, for a caller that already holds the length
    /// the operation declared — the fetch loop has it in the `BlobNeed`
    /// it is working through, so looking it up again for every 64 KiB was
    /// a query per chunk to learn something the caller had in its hand.
    ///
    /// The staged length is still read from the file on every chunk, and
    /// deliberately: multi-instance is supported use, so another process
    /// may be appending to the same transfer, and the offset check is the
    /// one thing standing between that and a hole in the middle of
    /// somebody's recording. It costs a stat, which is not a query.
    pub fn write_blob_chunk_bounded(
        &self,
        blob_id: &str,
        declared: u64,
        offset: u64,
        chunk: &[u8],
        limits: Limits,
    ) -> Result<u64> {
        let path = incoming_path(self, blob_id);
        let staged = staged_bytes(self, blob_id);
        if offset != staged {
            return Err(self.halt(
                alerts::SYNC_CORRUPT_OPERATION,
                blob_id,
                &format!("chunk offered at {offset}, but {staged} bytes are staged"),
            ));
        }
        if chunk.len() > limits.chunk_bytes {
            return Err(self.halt(
                alerts::SYNC_LIMIT_EXCEEDED,
                blob_id,
                &format!(
                    "chunk of {} bytes is over the {}-byte chunk limit",
                    chunk.len(),
                    limits.chunk_bytes
                ),
            ));
        }
        let would_be = staged.saturating_add(chunk.len() as u64);
        if would_be > declared {
            return Err(self.halt(
                alerts::SYNC_LIMIT_EXCEEDED,
                blob_id,
                &format!("content would reach {would_be} bytes, past the declared {declared}"),
            ));
        }
        let mut file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)
            .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        file.write_all(chunk)
            .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
        // Deliberately not fsynced per chunk. `finish_blob` syncs the
        // whole staged file before promoting it, and the digest it checks
        // there is the backstop for a tail lost to a crash: a short file
        // resumes from what survived, a torn one fails its digest and is
        // fetched again. An fsync every 64 KiB bought nothing but time.
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
        // A blob of nought bytes is content, not an absence: an empty
        // recording is a real capture and its note must reach the other
        // device. Nothing ever opens the staging file for it, because
        // there is no chunk to write, so it is created here — and then
        // the empty-input digest, the promotion and the row update are
        // the ordinary path. Left to itself this was a wedge: the digest
        // pass failed to open a file that was never made, and the error
        // took the whole session down on every attempt, for ever.
        if declared == 0 && !path.exists() {
            std::fs::File::create(&path)
                .map_err(|e| KernelError::Io { path: path.clone(), source: e })?;
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
        // The one fsync of the transfer, here rather than per chunk: the
        // bytes must be durable before the rename that publishes them.
        fs::fsync_file(&path)?;
        // An attachment may find its path taken (ADR 0007, 2026-10-22):
        // by the same bytes, by the bytes its previous revision declared,
        // or by something else entirely. Each is a different answer.
        let rel_path = self.settle_attachment_target(blob_id, &rel_path, &sha256)?;
        // The same promotion a capture takes, and deliberately not a
        // second copy of it: two of them had already drifted, and the
        // half that cleaned up after a lost race was the half that did
        // not need to (`capture::promote`).
        crate::capture::promote(self, blob_id, &path, &rel_path)?;
        // Content that arrived whole withdraws everything that was
        // standing about this transfer.
        for kind in [
            alerts::SYNC_BLOB_DIGEST_MISMATCH,
            alerts::SYNC_LIMIT_EXCEEDED,
            alerts::SYNC_CORRUPT_OPERATION,
        ] {
            alerts::clear(self.conn(), kind, blob_id)?;
        }
        Ok(true)
    }

    /// Where an arriving attachment's bytes go when its path is occupied.
    ///
    /// The same bytes: nowhere new, `promote` finds them there. The bytes
    /// the object's previous revision declared: an edit in place, so the
    /// old file is removed and the new one takes its path. Anything else
    /// is somebody's file, kept, and the arrival is written beside it as
    /// `<stem> (from <origin>).<ext>` — the blob row follows, so every
    /// later question about where the bytes are is answered truthfully —
    /// and a not-damage notice says so. A capture's blob never gets here:
    /// its path is unique by construction.
    fn settle_attachment_target(&self, blob_id: &str, rel_path: &str, sha256: &str) -> Result<String> {
        let target = self.vault().join(rel_path);
        if !target.exists() {
            return Ok(rel_path.to_string());
        }
        let (object_id, type_id): (String, String) = self.conn().query_row(
            "SELECT b.object_id, o.type_id FROM blobs b
             JOIN objects o ON o.object_id = b.object_id WHERE b.blob_id = ?1",
            [blob_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        if type_id != crate::vault::ATTACHMENT_TYPE {
            return Ok(rel_path.to_string());
        }
        let held = digest_of(&target)?;
        if held == sha256 {
            return Ok(rel_path.to_string());
        }
        let previous: i64 = self.conn().query_row(
            "SELECT EXISTS(SELECT 1 FROM blobs
                            WHERE object_id = ?1 AND sha256 = ?2 AND blob_id <> ?3)",
            rusqlite::params![object_id, held, blob_id],
            |row| row.get(0),
        )?;
        if previous == 1 {
            std::fs::remove_file(&target)
                .map_err(|e| KernelError::Io { path: target.clone(), source: e })?;
            return Ok(rel_path.to_string());
        }
        let origin = crate::operations::origins_of(self.conn(), &object_id)?
            .object
            .filter(|o| !o.is_empty())
            .map(|o| crate::ids::short(&o))
            .unwrap_or_else(|| "elsewhere".to_string());
        let (stem, ext) = rel_path.rsplit_once('.').unwrap_or((rel_path, "bin"));
        let beside = format!("{stem} (from {origin}).{ext}");
        self.conn().execute(
            "UPDATE blobs SET rel_path = ?2 WHERE blob_id = ?1",
            [blob_id, beside.as_str()],
        )?;
        alerts::raise(
            self.conn(),
            alerts::VAULT_ATTACHMENT_PATH_OCCUPIED,
            &object_id,
            &format!(
                "{rel_path} already held different bytes, so the copy from another device                  was written beside it as {beside}; both are kept"
            ),
        )?;
        Ok(beside)
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

/// How much of a file is resident while it is being hashed. A blob may be
/// half a gigabyte (`Limits::max_blob_bytes`), and nothing in the kernel
/// is ever allowed to hold one.
pub(crate) const DIGEST_CHUNK_BYTES: usize = 64 * 1024;

/// Hash a file by reading it back a chunk at a time. Deliberately not a
/// running hasher held across chunk writes: a transfer that resumes after
/// a restart has no such hasher, and one code path that always works
/// beats two that agree only while the process lives.
///
/// **The only way the kernel hashes a blob.** `Store::verify`,
/// recovery's stage matching and the external route's streaming claim all
/// used to call `std::fs::read` and hash the result, so the three places
/// that exist to protect large recordings each held one whole.
pub(crate) fn digest_of(path: &std::path::Path) -> Result<String> {
    let mut file = std::fs::File::open(path)
        .map_err(|e| KernelError::Io { path: path.to_path_buf(), source: e })?;
    digest_reader(&mut file)
        .map_err(|e| KernelError::Io { path: path.to_path_buf(), source: e })
        .map(|(digest, _)| digest)
}

/// Hash whatever a reader yields, `DIGEST_CHUNK_BYTES` at a time, and say
/// how many bytes there were — so a caller checking a length need not
/// stat the file a second time or read it again to find out.
pub(crate) fn digest_reader(reader: &mut impl Read) -> std::io::Result<(String, u64)> {
    use sha2::Digest;
    let mut hasher = <sha2::Sha256 as sha2::Digest>::new();
    let mut buffer = vec![0u8; DIGEST_CHUNK_BYTES];
    let mut total = 0u64;
    loop {
        let read = reader.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
        total += read as u64;
    }
    Ok((hex::encode(hasher.finalize()), total))
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
    fn a_blob_of_nought_bytes_completes_without_a_chunk_ever_being_written() {
        // A plain empty recording: `capture_bytes` records bytes: 0, and
        // the operation carrying it declares 0. There is no chunk to send,
        // so nothing opens the staging file — and the transfer must still
        // finish rather than failing on a file that was never made.
        let (dir, store, blob_id) = awaiting(&[]);
        assert!(store.finish_blob(&blob_id).expect("an empty transfer completes"));
        assert_eq!(
            std::fs::read(dir.path().join("Captures/remote.wav")).expect("promoted"),
            Vec::<u8>::new()
        );
        assert!(store.blobs_awaiting_bytes().expect("awaiting").is_empty());
        assert_eq!(store.status().expect("status").blobs_awaiting, 0);
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn a_refused_chunk_is_visible_as_an_alert_and_withdrawn_when_the_content_lands() {
        let (_dir, store, blob_id) = awaiting(&[8u8; 64]);
        assert!(matches!(
            store.write_blob_chunk(&blob_id, 0, &[8u8; 64], Limits { chunk_bytes: 8, ..Limits::DEFAULT }),
            Err(KernelError::SyncHalted { .. })
        ));
        assert_eq!(
            store.integrity_alerts_of(alerts::SYNC_LIMIT_EXCEEDED).expect("alerts").len(),
            1,
            "a bound that stopped a transfer is not allowed to be silent"
        );
        store.write_blob_chunk(&blob_id, 0, &[8u8; 64], Limits::DEFAULT).expect("within bounds");
        assert!(store.finish_blob(&blob_id).expect("complete"));
        assert!(store.integrity_alerts().expect("alerts").is_empty());
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

    /// A reader that records the largest buffer it was ever asked to
    /// fill. Nothing else can prove "never held whole" from the outside:
    /// a hasher that read the file into a `Vec` first would produce the
    /// same digest and pass every other assertion.
    struct Counting<'a> {
        bytes: &'a [u8],
        at: usize,
        largest_ask: usize,
    }

    impl Read for Counting<'_> {
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            self.largest_ask = self.largest_ask.max(buffer.len());
            let taken = buffer.len().min(self.bytes.len() - self.at);
            buffer[..taken].copy_from_slice(&self.bytes[self.at..self.at + taken]);
            self.at += taken;
            Ok(taken)
        }
    }

    #[test]
    fn hashing_a_blob_never_holds_more_than_a_chunk_of_it() {
        // A blob may be half a gigabyte (`Limits::max_blob_bytes`), and
        // three separate places used to hash one by reading the whole
        // file into memory first: `Store::verify`, recovery's stage
        // matching, and the external route's streaming claim — the three
        // places whose whole job is to protect large recordings.
        let content: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
        let mut reader = Counting { bytes: &content, at: 0, largest_ask: 0 };
        let (digest, len) = digest_reader(&mut reader).expect("hash");
        assert_eq!(digest, ids::sha256_hex(&content));
        assert_eq!(len, content.len() as u64, "the length comes back from the same pass");
        assert!(
            reader.largest_ask <= DIGEST_CHUNK_BYTES,
            "asked for {} bytes at once, which is more than a chunk",
            reader.largest_ask
        );
        assert!(reader.at > DIGEST_CHUNK_BYTES, "and it really did read past the first chunk");
    }

    #[test]
    fn verify_reads_past_the_first_chunk_of_a_large_blob() {
        // The second-order case for streaming: a hasher that stopped
        // early would also never hold the file whole, and would also
        // agree with itself. Damage placed deliberately beyond the first
        // chunk is what tells the two apart.
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let content: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
        let receipt = store.capture_bytes("wav", &content).expect("capture");
        assert!(store.verify().expect("verify").is_empty());

        let path = dir.path().join(&receipt.rel_path);
        let mut damaged = std::fs::read(&path).expect("read");
        let far = DIGEST_CHUNK_BYTES * 3 + 17;
        damaged[far] ^= 0xFF;
        std::fs::write(&path, &damaged).expect("damage a byte a long way in");

        let problems = store.verify().expect("verify");
        assert_eq!(problems.len(), 1, "{problems:?}");
        assert!(problems[0].contains("content hash mismatch"), "{}", problems[0]);
    }

    #[test]
    fn a_promotion_that_lost_the_race_leaves_no_staged_duplicate() {
        // The transfer side of the shared promotion: another process
        // finished the same transfer first. The bytes are in the vault
        // either way, and the stage must not be left behind.
        let (dir, store, blob_id) = awaiting(&[4u8; 96]);
        store.write_blob_chunk(&blob_id, 0, &[4u8; 96], Limits::DEFAULT).expect("all of it");
        let target = dir.path().join("Captures/remote.wav");
        std::fs::create_dir_all(target.parent().expect("parent")).expect("mkdir");
        std::fs::write(&target, [4u8; 96]).expect("another process got there first");

        assert!(store.finish_blob(&blob_id).expect("complete"));
        assert!(!incoming_path(&store, &blob_id).exists(), "no staged duplicate is left");
        assert_eq!(store.status().expect("status").blobs_awaiting, 0);
        assert!(store.verify().expect("verify").is_empty());
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
