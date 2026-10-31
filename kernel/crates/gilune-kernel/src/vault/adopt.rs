//! Every file in the vault becomes something sync can carry (ADR 0007,
//! amendment 2026-10-22).
//!
//! Three rules live here, and `reconcile` applies them:
//!
//!  - **Markdown is adopted.** A `.md` file the kernel does not claim —
//!    no front matter, or front matter with no reserved key — becomes a
//!    note object, one `object-created` operation, and a projection at
//!    its own path carrying the `gilune-*` header. The body is kept
//!    byte-for-byte; only the header is added. A file carrying a
//!    `gilune-id` nobody holds is adopted under that id, because the
//!    crash window between the header write and the commit, and a file
//!    copied from another device's vault, both land here and both mean
//!    "this is that note".
//!  - **Everything else is an attachment.** Images, PDFs and the rest
//!    become blob-backed objects recorded in place: nothing is copied
//!    and nothing is written into the file. The receiving device takes
//!    the bytes as it takes a recording, and because the document says
//!    `attached`, no note is projected for it.
//!  - **Local-only is never adopted.** `gilune-sync: local` in a note's
//!    front matter, or a `.gilune-local` marker in a folder, keeps every
//!    file it covers as a candidate: indexed here, never an object, never
//!    an operation. A note that already syncs and enters local scope is
//!    recorded in `local_objects` with the last revision peers were sent;
//!    its revisions carry on with no operations, and leaving local scope
//!    sends one revision that supersedes that last-sent head.
//!
//! The crash points are real process aborts in the CLI's crash matrix:
//! an abort between the rows and the header write leaves a tracked file
//! whose bytes equal its record — which `reconcile_file` treats as a
//! header still to come, never as a broken projection.

use super::header::{self, Header};
use super::{stat_of, now_ns, write_atomic, Head, Stat};
use crate::changes::{Change, ChangeCause, ChangeKind};
use crate::crashpoints::crashpoint;
use crate::limits::Limits;
use crate::store::Store;
use crate::{alerts, changes, ids, KernelError, Result};
use std::path::Path;

/// A note the vault received from outside: a file somebody or some other
/// tool wrote, adopted as an object.
pub const NOTE_TYPE: &str = "org.memory.note";
/// A file that is not a note, carried as a blob.
pub const ATTACHMENT_TYPE: &str = "org.memory.attachment";
/// The origin of a revision made by adopting a file.
pub(crate) const IMPORT_ORIGIN: &str = "external-import";
pub(crate) const NOTE_CREATED_EVENT: &str = "org.memory.note-created";
pub(crate) const ATTACHMENT_CREATED_EVENT: &str = "org.memory.attachment-created";
/// The file whose presence keeps a folder, and everything under it, on
/// this device.
pub const LOCAL_MARKER: &str = ".gilune-local";
/// The value of `gilune-sync` that keeps a note on this device.
pub const LOCAL_VALUE: &str = "local";
/// The origin of the one revision sent when a note leaves local scope.
pub(crate) const REJOIN_ORIGIN: &str = "local-rejoin";

/// A file the walk found, and whether it sits under a local marker.
pub(super) struct Found {
    pub rel_path: String,
    pub local: bool,
}

/// Everything in the vault that is anybody's: Markdown and the rest,
/// each in deterministic path order, each knowing whether a `.gilune-
/// local` marker covers it. One walk, because the two lists come from
/// the same directories and a vault is read once per pass.
pub(super) struct VaultFiles {
    pub markdown: Vec<Found>,
    pub other: Vec<Found>,
}

/// Walk the vault. Dot-folders (`.gilune/`, `.obsidian/`) are never
/// entered, and a dot-file that is not Markdown (the local marker itself,
/// `.DS_Store`) and `.tmp-*` are nobody's: neither adopted nor announced.
/// A dot-prefixed `.md` IS a note — the Markdown walk this replaced took
/// it, ADR 0020 § 10 counts it, and the Android suite pins it.
pub(super) fn vault_files(root: &Path) -> Result<VaultFiles> {
    let mut markdown = Vec::new();
    let mut other = Vec::new();
    let mut stack = vec![(root.to_path_buf(), root.join(LOCAL_MARKER).is_file())];
    while let Some((dir, local)) = stack.pop() {
        for entry in std::fs::read_dir(&dir)
            .map_err(|e| KernelError::Io { path: dir.clone(), source: e })?
        {
            let entry = entry.map_err(|e| KernelError::Io { path: dir.clone(), source: e })?;
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            if path.is_dir() {
                if !name.starts_with('.') {
                    let here = local || path.join(LOCAL_MARKER).is_file();
                    stack.push((path, here));
                }
                continue;
            }
            let dot_file = name.starts_with('.') && !name.ends_with(".md");
            if dot_file || name.starts_with(".tmp-") || !path.is_file() {
                continue;
            }
            let rel = path.strip_prefix(root).unwrap_or(&path).to_path_buf();
            let found = Found { rel_path: crate::fs::portable_rel(&rel), local };
            if name.ends_with(".md") {
                markdown.push(found);
            } else {
                other.push(found);
            }
        }
    }
    markdown.sort_by(|a, b| a.rel_path.cmp(&b.rel_path));
    other.sort_by(|a, b| a.rel_path.cmp(&b.rel_path));
    Ok(VaultFiles { markdown, other })
}

/// Whether a path sits under a `.gilune-local` marker, asked for one
/// file rather than by the walk: the vault root and every folder on the
/// way down are checked.
pub(super) fn folder_is_local(root: &Path, rel_path: &str) -> bool {
    let mut dir = root.to_path_buf();
    if dir.join(LOCAL_MARKER).is_file() {
        return true;
    }
    let mut parts: Vec<&str> = rel_path.split('/').collect();
    parts.pop();
    for part in parts {
        dir.push(part);
        if dir.join(LOCAL_MARKER).is_file() {
            return true;
        }
    }
    false
}

/// Whether a note's own front matter keeps it on this device.
pub(super) fn header_says_local(text: &str) -> bool {
    gilune_document::TextSnapshot::new(text.to_string())
        .ok()
        .and_then(|s| s.metadata_string(gilune_document::USER_RESERVED_KEY).map(|v| v == LOCAL_VALUE))
        .unwrap_or(false)
}

/// The media name a file's extension gives, lower-cased; `bin` for none.
fn media_of(rel_path: &str) -> String {
    let name = rel_path.rsplit('/').next().unwrap_or(rel_path);
    match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() && !ext.is_empty() => ext.to_ascii_lowercase(),
        _ => "bin".to_string(),
    }
}

/// What this device knows about the blob at a path: the newest row,
/// with its object's type and standing.
struct BlobAt {
    blob_id: String,
    object_id: String,
    type_id: String,
    deleted: bool,
    sha256: String,
    bytes: i64,
    mtime_ns: i64,
    recorded_ns: i64,
}

impl Store {
    // ------------------------------------------------------------ markdown

    /// Adopt a Markdown file as a note object. `header` is the parsed
    /// front matter when the file carries reserved keys (an id nobody
    /// holds); `wanted_id` is that id. Returns the object id.
    pub(super) fn adopt_markdown(
        &self,
        rel_path: &str,
        text: &str,
        header: Option<&Header>,
        wanted_id: Option<&str>,
    ) -> Result<String> {
        let mut document = match header {
            Some(h) => serde_json::json!({ "text": h.snapshot.body(), header::PAYLOAD_KEY: h.payload() }),
            None => super::new_page_payload(text)?,
        };
        // Where the note lives, for the device that receives it: a page
        // carries its path in its own envelope, a capture names its blob,
        // and an adopted note had nowhere to say — so it projected into
        // the type's default folder over there under a generated name.
        document["path"] = serde_json::Value::String(rel_path.to_string());
        let payload = document.to_string();
        let object_id = wanted_id.map(str::to_owned).unwrap_or_else(ids::new_id);
        // A file that names its own type — one exported from another
        // vault — keeps it; everything else is a note.
        let (type_id, type_schema) = match header {
            Some(h) => (
                h.snapshot.metadata_string("gilune-type").unwrap_or(NOTE_TYPE).to_string(),
                h.snapshot
                    .metadata_string("gilune-schema")
                    .and_then(|s| s.parse::<i64>().ok())
                    .unwrap_or(1),
            ),
            None => (NOTE_TYPE.to_string(), 1),
        };
        let revision_id = ids::new_id();
        let created_ms = ids::now_ms();
        let disk_sha = ids::sha256_hex(text.as_bytes());
        let stat = stat_of(&self.vault().join(rel_path));
        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let done: Result<()> = (|| {
            conn.execute(
                "INSERT INTO objects (object_id, type_id, type_schema, created_ms)
                 VALUES (?1, ?2, ?3, ?4)",
                rusqlite::params![object_id, type_id, type_schema, created_ms],
            )?;
            conn.execute(
                "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![revision_id, object_id, payload, IMPORT_ORIGIN, created_ms],
            )?;
            conn.execute(
                "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                [&object_id, &revision_id],
            )?;
            crate::operations::record(
                conn,
                crate::operations::OBJECT_CREATED,
                &object_id,
                serde_json::json!({
                    "object": {
                        "object_id": object_id,
                        "type_id": type_id,
                        "type_schema": type_schema,
                        "created_ms": created_ms,
                    },
                    "revision": {
                        "revision_id": revision_id,
                        "payload": payload,
                        "origin": IMPORT_ORIGIN,
                        "created_ms": created_ms,
                    },
                    "event_type": NOTE_CREATED_EVENT,
                }),
                created_ms,
            )?;
            conn.execute(
                "INSERT INTO events
                 (event_id, event_type, subject_id, payload, created_ms, origin_device)
                 VALUES (?1, ?2, ?3, ?4, ?5,
                         (SELECT value FROM meta WHERE key = 'device_id'))",
                rusqlite::params![ids::new_id(), NOTE_CREATED_EVENT, object_id, payload, created_ms],
            )?;
            conn.execute("DELETE FROM imports WHERE rel_path = ?1", [rel_path])?;
            // The projection records the bytes that are on disk NOW, so a
            // crash before the header lands leaves a tracked file whose
            // bytes equal its record — a header still to come, not a
            // broken projection — and `project` canonicalises it.
            conn.execute(
                "INSERT OR REPLACE INTO projections
                 (object_id, rel_path, revision_id, content_sha256, size, mtime_ns, recorded_ns)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                rusqlite::params![
                    object_id,
                    rel_path,
                    revision_id,
                    disk_sha,
                    stat.map(|s| s.size).unwrap_or(-1),
                    stat.map(|s| s.mtime_ns).unwrap_or(0),
                    if stat.is_some() { now_ns() } else { 0 },
                ],
            )?;
            changes::record(
                conn,
                Change {
                    kind: ChangeKind::Created,
                    rel_path,
                    previous_rel_path: None,
                    object_id: Some(&object_id),
                    revision_id: Some(&revision_id),
                    cause: ChangeCause::Reconcile,
                },
            )?;
            crashpoint("adopt.commit.inside");
            Ok(())
        })();
        match done {
            Ok(()) => conn.execute_batch("COMMIT;")?,
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                return Err(e);
            }
        }
        crashpoint("adopt.after_commit");
        let head = Head {
            object_id: object_id.clone(),
            type_id,
            type_schema,
            created_ms,
            revision_id,
            payload,
            origin: IMPORT_ORIGIN.to_string(),
            conflicted: false,
        };
        if let Some(canonical) = self.render(&head)? {
            write_atomic(&self.vault().join(rel_path), canonical.as_bytes())?;
            crashpoint("adopt.after_write");
            self.record_projection(&head, rel_path, &ids::sha256_hex(canonical.as_bytes()), None)?;
        }
        Ok(object_id)
    }

    // --------------------------------------------------------- attachments

    /// Every file that is not a note: adopt the new ones, revise the ones
    /// whose bytes changed, leave the rest alone without reading them.
    /// Returns (adopted, revised).
    pub(super) fn reconcile_attachments(&self, found: &[Found]) -> Result<(i64, i64)> {
        let mut adopted = 0;
        let mut revised = 0;
        for file in found {
            if file.local {
                continue;
            }
            let rows = self.blobs_at(&file.rel_path)?;
            // A recording's bytes are a capture's — a source, immutable
            // by constitution — and never an attachment to be revised.
            if rows.iter().any(|b| b.type_id != ATTACHMENT_TYPE) {
                continue;
            }
            match rows.iter().find(|b| !b.deleted) {
                None => {
                    if self.follow_moved_recording(&file.rel_path)?.is_some() {
                        continue;
                    }
                    if self.adopt_attachment(&file.rel_path)? {
                        adopted += 1;
                    }
                }
                Some(current) => {
                    if self.attachment_revised(current, &file.rel_path)? {
                        revised += 1;
                    }
                }
            }
        }
        Ok((adopted, revised))
    }

    /// A recording somebody moved, followed to where it went: the source
    /// whose preserved bytes are exactly this file's, and whose recorded
    /// path no longer holds them, now records this path. Returns the
    /// source's id, or `None` when the file is not a moved recording.
    ///
    /// **Why**: renaming a voice note renames its recording too, because
    /// the rail pairs the two by stem. Before this, the kernel kept
    /// reading the old path — so a processor asked for the recording
    /// found nothing — and adopted the moved file as a second, unrelated
    /// attachment, carrying the same bytes to every device twice.
    ///
    /// Matched by digest, as a note is matched by its id: a name says
    /// nothing about which recording a file is. A **copy** is not a move —
    /// while the recorded path still holds the bytes, the file is left to
    /// be adopted like any other. The new path is this device's alone;
    /// the source's document is not revised, so nothing is sent.
    pub(crate) fn follow_moved_recording(&self, rel_path: &str) -> Result<Option<String>> {
        let path = self.vault().join(rel_path);
        let Some(stat) = stat_of(&path) else { return Ok(None) };
        let sha256 = crate::blobs::digest_of(&path)?;
        let mut stmt = self.conn().prepare(
            "SELECT b.blob_id, b.object_id, b.rel_path
             FROM blobs b JOIN objects o ON o.object_id = b.object_id
             WHERE b.sha256 = ?1 AND b.state = 'ready' AND o.deleted = 0
               AND o.type_id = ?2
             ORDER BY b.rowid DESC",
        )?;
        let candidates = stmt
            .query_map(rusqlite::params![sha256, crate::capture::SOURCE_TYPE], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?, row.get::<_, String>(2)?))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        for (blob_id, object_id, recorded) in candidates {
            if recorded == rel_path {
                return Ok(Some(object_id));
            }
            if self.vault().join(&recorded).exists() {
                continue;
            }
            self.write_txn(|| {
                let conn = self.conn();
                conn.execute(
                    "UPDATE blobs SET rel_path = ?2, mtime_ns = ?3, recorded_ns = ?4
                     WHERE blob_id = ?1",
                    rusqlite::params![blob_id, rel_path, stat.mtime_ns, now_ns()],
                )?;
                changes::record(
                    conn,
                    Change {
                        kind: ChangeKind::Moved,
                        rel_path,
                        previous_rel_path: Some(&recorded),
                        object_id: Some(&object_id),
                        revision_id: None,
                        cause: ChangeCause::Reconcile,
                    },
                )?;
                Ok(())
            })?;
            return Ok(Some(object_id));
        }
        Ok(None)
    }

    /// Blob rows at a path, newest first, with their object's standing.
    fn blobs_at(&self, rel_path: &str) -> Result<Vec<BlobAt>> {
        let mut stmt = self.conn().prepare(
            "SELECT b.blob_id, b.object_id, o.type_id, o.deleted, b.sha256, b.bytes,
                    b.mtime_ns, b.recorded_ns
             FROM blobs b JOIN objects o ON o.object_id = b.object_id
             WHERE b.rel_path = ?1
             ORDER BY b.rowid DESC",
        )?;
        let rows = stmt.query_map([rel_path], |row| {
            Ok(BlobAt {
                blob_id: row.get(0)?,
                object_id: row.get(1)?,
                type_id: row.get(2)?,
                deleted: row.get::<_, i64>(3)? == 1,
                sha256: row.get(4)?,
                bytes: row.get(5)?,
                mtime_ns: row.get(6)?,
                recorded_ns: row.get(7)?,
            })
        })?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Adopt one file as an attachment object, in place. `Ok(false)` when
    /// it could not be: gone between the walk and now, or over the blob
    /// limit, which is said once and stands until the file changes.
    fn adopt_attachment(&self, rel_path: &str) -> Result<bool> {
        let path = self.vault().join(rel_path);
        let Some(stat) = stat_of(&path) else { return Ok(false) };
        let limits = Limits::DEFAULT;
        if stat.size as u64 > limits.max_blob_bytes {
            alerts::raise(
                self.conn(),
                alerts::VAULT_ATTACHMENT_TOO_LARGE,
                rel_path,
                &format!(
                    "{rel_path} is {} bytes, over the {}-byte limit a device will take, so it \
                     stays here and is not carried to your other devices",
                    stat.size, limits.max_blob_bytes
                ),
            )?;
            return Ok(false);
        }
        alerts::clear(self.conn(), alerts::VAULT_ATTACHMENT_TOO_LARGE, rel_path)?;
        let sha256 = crate::blobs::digest_of(&path)?;
        let media = media_of(rel_path);
        let object_id = ids::new_id();
        let revision_id = ids::new_id();
        let blob_id = ids::new_id();
        let created_ms = ids::now_ms();
        let document = serde_json::json!({
            "media": media,
            "blob_id": blob_id,
            "rel_path": rel_path,
            "sha256": sha256,
            "bytes": stat.size,
            crate::capture::ATTACHED_KEY: true,
        });
        let payload = document.to_string();
        self.write_txn(|| {
            let conn = self.conn();
            conn.execute(
                "INSERT INTO objects (object_id, type_id, type_schema, created_ms)
                 VALUES (?1, ?2, 1, ?3)",
                rusqlite::params![object_id, ATTACHMENT_TYPE, created_ms],
            )?;
            conn.execute(
                "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![revision_id, object_id, payload, IMPORT_ORIGIN, created_ms],
            )?;
            conn.execute(
                "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                [&object_id, &revision_id],
            )?;
            conn.execute(
                "INSERT INTO blobs
                 (blob_id, object_id, rel_path, sha256, bytes, state, origin, mtime_ns, recorded_ns)
                 VALUES (?1, ?2, ?3, ?4, ?5, 'ready', 'local', ?6, ?7)",
                rusqlite::params![blob_id, object_id, rel_path, sha256, stat.size, stat.mtime_ns, now_ns()],
            )?;
            crate::operations::record(
                conn,
                crate::operations::OBJECT_CREATED,
                &object_id,
                serde_json::json!({
                    "object": {
                        "object_id": object_id,
                        "type_id": ATTACHMENT_TYPE,
                        "type_schema": 1,
                        "created_ms": created_ms,
                    },
                    "revision": {
                        "revision_id": revision_id,
                        "payload": payload,
                        "origin": IMPORT_ORIGIN,
                        "created_ms": created_ms,
                    },
                    "blob": {
                        "blob_id": blob_id,
                        "rel_path": rel_path,
                        "sha256": sha256,
                        "bytes": stat.size,
                    },
                    "event_type": ATTACHMENT_CREATED_EVENT,
                }),
                created_ms,
            )?;
            conn.execute(
                "INSERT INTO events
                 (event_id, event_type, subject_id, payload, created_ms, origin_device)
                 VALUES (?1, ?2, ?3, ?4, ?5,
                         (SELECT value FROM meta WHERE key = 'device_id'))",
                rusqlite::params![ids::new_id(), ATTACHMENT_CREATED_EVENT, object_id, payload, created_ms],
            )?;
            changes::record(
                conn,
                Change {
                    kind: ChangeKind::Created,
                    rel_path,
                    previous_rel_path: None,
                    object_id: Some(&object_id),
                    revision_id: Some(&revision_id),
                    cause: ChangeCause::Reconcile,
                },
            )?;
            Ok(())
        })?;
        Ok(true)
    }

    /// An attachment this device already holds: the stat rule of ADR 0020
    /// § 2.5 decides whether to read it at all, and a changed digest is a
    /// new blob and a new revision. Returns whether a revision was made.
    fn attachment_revised(&self, current: &BlobAt, rel_path: &str) -> Result<bool> {
        let path = self.vault().join(rel_path);
        let Some(stat) = stat_of(&path) else { return Ok(false) };
        let unchanged_by_stat = stat == Stat { size: current.bytes, mtime_ns: current.mtime_ns }
            && current.recorded_ns > 0
            && stat.mtime_ns < current.recorded_ns;
        if unchanged_by_stat {
            return Ok(false);
        }
        let sha256 = crate::blobs::digest_of(&path)?;
        if sha256 == current.sha256 {
            // Same bytes, racy or merely rewritten: re-arm so the next
            // pass answers from the stat.
            self.conn().execute(
                "UPDATE blobs SET mtime_ns = ?2, recorded_ns = ?3 WHERE blob_id = ?1",
                rusqlite::params![current.blob_id, stat.mtime_ns, now_ns()],
            )?;
            return Ok(false);
        }
        if stat.size as u64 > Limits::DEFAULT.max_blob_bytes {
            alerts::raise(
                self.conn(),
                alerts::VAULT_ATTACHMENT_TOO_LARGE,
                rel_path,
                &format!(
                    "{rel_path} grew to {} bytes, over the {}-byte limit a device will take, so \
                     the version your other devices hold is the last one under the limit",
                    stat.size,
                    Limits::DEFAULT.max_blob_bytes
                ),
            )?;
            return Ok(false);
        }
        alerts::clear(self.conn(), alerts::VAULT_ATTACHMENT_TOO_LARGE, rel_path)?;
        let blob_id = ids::new_id();
        let revision_id = ids::new_id();
        let created_ms = ids::now_ms();
        let mut document = crate::revisions::head_payload(self.conn(), &current.object_id)?
            .and_then(|payload| serde_json::from_str::<serde_json::Value>(&payload).ok())
            .unwrap_or_else(|| serde_json::json!({ crate::capture::ATTACHED_KEY: true }));
        document["media"] = serde_json::Value::String(media_of(rel_path));
        document["blob_id"] = serde_json::Value::String(blob_id.clone());
        document["rel_path"] = serde_json::Value::String(rel_path.to_string());
        document["sha256"] = serde_json::Value::String(sha256.clone());
        document["bytes"] = serde_json::Value::from(stat.size);
        document[crate::capture::ATTACHED_KEY] = serde_json::Value::Bool(true);
        let payload = document.to_string();
        let object_id = current.object_id.clone();
        self.write_txn(|| {
            let conn = self.conn();
            crate::revisions::add_revision_naming(
                conn,
                &object_id,
                &revision_id,
                &payload,
                super::EXTERNAL_EDIT_ORIGIN,
                created_ms,
                crate::revisions::Announce::Silent,
                None,
                Some(serde_json::json!({
                    "blob": {
                        "blob_id": blob_id,
                        "rel_path": rel_path,
                        "sha256": sha256,
                        "bytes": stat.size,
                    }
                })),
            )?;
            conn.execute(
                "INSERT INTO blobs
                 (blob_id, object_id, rel_path, sha256, bytes, state, origin, mtime_ns, recorded_ns)
                 VALUES (?1, ?2, ?3, ?4, ?5, 'ready', 'local', ?6, ?7)",
                rusqlite::params![blob_id, object_id, rel_path, sha256, stat.size, stat.mtime_ns, now_ns()],
            )?;
            changes::record(
                conn,
                Change {
                    kind: ChangeKind::Modified,
                    rel_path,
                    previous_rel_path: None,
                    object_id: Some(&object_id),
                    revision_id: Some(&revision_id),
                    cause: ChangeCause::ExternalEdit,
                },
            )?;
            Ok(())
        })?;
        Ok(true)
    }

    /// Where each live attachment's bytes are, by its newest blob row:
    /// (object_id, rel_path, sha256).
    fn attachment_locations(&self, deleted: bool) -> Result<Vec<(String, String, String)>> {
        let mut stmt = self.conn().prepare(
            "SELECT b.object_id, b.rel_path, b.sha256
             FROM blobs b JOIN objects o ON o.object_id = b.object_id
             WHERE o.type_id = ?1 AND o.deleted = ?2 AND b.recorded_ns >= 0
               AND b.rowid = (SELECT MAX(rowid) FROM blobs b2 WHERE b2.object_id = b.object_id)",
        )?;
        let rows = stmt.query_map(
            rusqlite::params![ATTACHMENT_TYPE, if deleted { 1 } else { 0 }],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Attachments whose files have gone become tombstone proposals, as a
    /// note's does. Returns how many were proposed.
    pub(super) fn propose_missing_attachments(&self) -> Result<i64> {
        let mut proposed = 0;
        for (object_id, rel_path, _) in self.attachment_locations(false)? {
            if !self.vault().join(&rel_path).exists() {
                proposed += self.propose_tombstone(&object_id, &rel_path)?;
            }
        }
        Ok(proposed)
    }

    /// Withdraw the files of tombstoned attachments — only when the bytes
    /// on disk are exactly the blob's. Bytes that differ are somebody's
    /// and stay; the row is marked so they are not re-hashed every pass.
    pub(super) fn withdraw_tombstoned_attachments(&self) -> Result<i64> {
        let mut removed = 0;
        for (object_id, rel_path, sha256) in self.attachment_locations(true)? {
            let target = self.vault().join(&rel_path);
            if !target.is_file() {
                continue;
            }
            if crate::blobs::digest_of(&target)? != sha256 {
                self.conn().execute(
                    "UPDATE blobs SET recorded_ns = -1 WHERE object_id = ?1",
                    [&object_id],
                )?;
                continue;
            }
            std::fs::remove_file(&target)
                .map_err(|e| KernelError::Io { path: target.clone(), source: e })?;
            if let Some(parent) = target.parent() {
                crate::fs::fsync_dir(parent)?;
            }
            let announce = !self.already_removed(&rel_path)?;
            if announce {
                let cause = self.deletion_cause(&object_id)?;
                self.write_txn(|| {
                    changes::record(
                        self.conn(),
                        Change {
                            kind: ChangeKind::Removed,
                            rel_path: &rel_path,
                            previous_rel_path: None,
                            object_id: Some(&object_id),
                            revision_id: None,
                            cause,
                        },
                    )
                })?;
            }
            removed += 1;
        }
        Ok(removed)
    }

    // ---------------------------------------------------------- local scope

    /// Whether a note is kept on this device.
    pub(crate) fn is_local_object(&self, object_id: &str) -> Result<bool> {
        Ok(self.conn().query_row(
            "SELECT EXISTS(SELECT 1 FROM local_objects WHERE object_id = ?1)",
            [object_id],
            |row| row.get::<_, i64>(0),
        )? == 1)
    }

    /// A synced note enters local scope: remember the last revision peers
    /// were sent, which is the head as it stands before anything local
    /// is written over it.
    pub(super) fn enter_local_scope(&self, object_id: &str) -> Result<()> {
        if self.is_local_object(object_id)? {
            return Ok(());
        }
        let heads = crate::revisions::heads(self.conn(), object_id)?;
        let Some(last_sent) = heads.first() else { return Ok(()) };
        self.conn().execute(
            "INSERT OR IGNORE INTO local_objects (object_id, last_sent_revision) VALUES (?1, ?2)",
            [object_id, last_sent.as_str()],
        )?;
        Ok(())
    }

    /// A note leaves local scope: one revision goes out superseding the
    /// head peers last saw, so they see one change and never a history
    /// they cannot reference.
    pub(super) fn leave_local_scope(&self, object_id: &str) -> Result<()> {
        let last_sent: Option<String> = self
            .conn()
            .query_row(
                "SELECT last_sent_revision FROM local_objects WHERE object_id = ?1",
                [object_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(super::no_rows_none)?;
        let Some(last_sent) = last_sent else { return Ok(()) };
        let Some(payload) = crate::revisions::head_payload(self.conn(), object_id)? else {
            return Ok(());
        };
        let revision_id = ids::new_id();
        let created_ms = ids::now_ms();
        self.write_txn(|| {
            self.conn().execute("DELETE FROM local_objects WHERE object_id = ?1", [object_id])?;
            crate::revisions::add_revision_naming(
                self.conn(),
                object_id,
                &revision_id,
                &payload,
                REJOIN_ORIGIN,
                created_ms,
                crate::revisions::Announce::Silent,
                Some(std::slice::from_ref(&last_sent)),
                None,
            )?;
            // The projection row still names the previous head; the next
            // pass re-renders with the new revision id in the header.
            self.conn().execute(
                "UPDATE projections SET revision_id = ?2 WHERE object_id = ?1",
                [object_id, revision_id.as_str()],
            )?;
            Ok(())
        })
    }

    /// Apply local scope to a tracked note as this pass found it.
    pub(super) fn settle_local_scope(&self, object_id: &str, local: bool) -> Result<()> {
        if local {
            self.enter_local_scope(object_id)
        } else if self.is_local_object(object_id)? {
            self.leave_local_scope(object_id)
        } else {
            Ok(())
        }
    }
}
