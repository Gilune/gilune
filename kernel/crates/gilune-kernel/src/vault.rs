//! The Markdown vault projection and its bidirectional reconciliation
//! (ADR 0007). The kernel store is the sole transactional authority; the
//! Markdown layer is a continuously maintained, rebuildable projection.
//! Reconciliation ingests external changes as history — an edited file
//! becomes a revision, a deleted file becomes a proposal, a foreign file
//! becomes an import candidate, a malformed file quarantines untouched —
//! and is never destructive to kernel history. Files are matched by
//! front-matter ID, never by name; renames are metadata changes.
//!
//! Rendering is deterministic: the same committed state always produces
//! the same bytes, so delete-and-rebuild converges and sync replicas
//! project identically. Front-matter keys are `memory-*` — the vault
//! format is a public contract and stays project-neutral.

use crate::capture::record_operation;
use crate::crashpoints::crashpoint;
use crate::store::Store;
use crate::{fs, ids, KernelError, Result};
use std::collections::BTreeMap;

pub const REVISION_ADDED_EVENT: &str = "org.memory.revision-added";

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct ProjectSummary {
    pub written: i64,
    pub unchanged: i64,
    pub held_back: i64,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct ReconcileSummary {
    pub edits: i64,
    pub renames: i64,
    pub tombstone_proposals: i64,
    pub imports: i64,
    pub quarantined: i64,
}

struct Head {
    object_id: String,
    type_id: String,
    type_schema: i64,
    created_ms: i64,
    revision_id: String,
    payload: String,
    conflicted: bool,
}

impl Store {
    /// Project every live object into the vault. Idempotent and safe to
    /// run at any time: a file that differs from what the kernel last
    /// wrote is held back for `reconcile` rather than clobbered, and
    /// quarantined paths and tombstone-proposed objects are never
    /// touched.
    pub fn project(&self) -> Result<ProjectSummary> {
        let mut summary = ProjectSummary::default();
        for head in self.live_heads()? {
            let rel_path = self.projection_path(&head)?;
            if self.is_quarantined(&rel_path)? || self.has_open_tombstone(&head.object_id)? {
                summary.held_back += 1;
                continue;
            }
            let canonical = self.render(&head)?;
            let canonical_sha = ids::sha256_hex(canonical.as_bytes());
            let target = self.vault().join(&rel_path);
            let recorded: Option<String> = self
                .conn()
                .query_row(
                    "SELECT content_sha256 FROM projections WHERE object_id = ?1",
                    [&head.object_id],
                    |row| row.get(0),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            let on_disk = std::fs::read(&target).ok();
            let disk_sha = on_disk.as_deref().map(ids::sha256_hex);

            match (&disk_sha, &recorded) {
                (Some(d), _) if *d == canonical_sha => {
                    self.record_projection(&head, &rel_path, &canonical_sha)?;
                    summary.unchanged += 1;
                }
                (Some(d), Some(r)) if d != r => {
                    // Externally changed since we last wrote: reconcile owns it.
                    summary.held_back += 1;
                }
                _ => {
                    write_atomic(&target, canonical.as_bytes())?;
                    crashpoint("project.after_write");
                    self.record_projection(&head, &rel_path, &canonical_sha)?;
                    summary.written += 1;
                }
            }
        }
        Ok(summary)
    }

    /// Ingest external changes to the vault. Never destructive: kernel
    /// history only grows.
    pub fn reconcile(&self) -> Result<ReconcileSummary> {
        let mut summary = ReconcileSummary::default();
        let mut seen_paths: Vec<String> = Vec::new();

        for rel_path in self.markdown_files()? {
            seen_paths.push(rel_path.clone());
            let text = std::fs::read_to_string(self.vault().join(&rel_path)).map_err(|e| {
                KernelError::Io { path: self.vault().join(&rel_path), source: e }
            })?;
            let tracked_object: Option<(String, String)> = self
                .conn()
                .query_row(
                    "SELECT object_id, content_sha256 FROM projections WHERE rel_path = ?1",
                    [&rel_path],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .map(Some)
                .or_else(no_rows_none)?;

            match parse_front_matter(&text) {
                FrontMatter::Absent | FrontMatter::Malformed if tracked_object.is_some() => {
                    // A file the kernel wrote lost or broke its identity:
                    // never guess, never overwrite — a person decides.
                    summary.quarantined +=
                        self.quarantine(&rel_path, "projection front matter missing or malformed")?;
                }
                FrontMatter::Malformed => {
                    summary.quarantined += self.quarantine(&rel_path, "unparseable front matter")?;
                }
                FrontMatter::Absent => {
                    summary.imports += self.record_import(&rel_path)?;
                }
                FrontMatter::Parsed { keys, body } => {
                    let Some(object_id) = keys.get("memory-id").cloned() else {
                        summary.quarantined +=
                            self.quarantine(&rel_path, "front matter has no memory-id")?;
                        continue;
                    };
                    let known: Option<(String, String)> = self
                        .conn()
                        .query_row(
                            "SELECT rel_path, content_sha256 FROM projections WHERE object_id = ?1",
                            [&object_id],
                            |row| Ok((row.get(0)?, row.get(1)?)),
                        )
                        .map(Some)
                        .or_else(no_rows_none)?;
                    let Some((recorded_path, recorded_sha)) = known else {
                        summary.imports += self.record_import(&rel_path)?;
                        continue;
                    };
                    if recorded_path != rel_path {
                        if self.vault().join(&recorded_path).exists() {
                            summary.quarantined +=
                                self.quarantine(&rel_path, "duplicate of a tracked projection")?;
                            continue;
                        }
                        self.conn().execute(
                            "UPDATE projections SET rel_path = ?2 WHERE object_id = ?1",
                            [&object_id, &rel_path],
                        )?;
                        summary.renames += 1;
                    }
                    if ids::sha256_hex(text.as_bytes()) != recorded_sha {
                        self.ingest_external_edit(&object_id, &body, text.as_bytes())?;
                        summary.edits += 1;
                    }
                }
            }
        }

        // Tracked projections whose file is gone become tombstone
        // proposals; the file is not resurrected while a proposal is open.
        let missing: Vec<String> = {
            let mut stmt = self
                .conn()
                .prepare("SELECT object_id, rel_path FROM projections")?;
            let rows = stmt.query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })?;
            let mut missing = Vec::new();
            for row in rows {
                let (object_id, rel_path) = row?;
                if !seen_paths.contains(&rel_path) && !self.vault().join(&rel_path).exists() {
                    missing.push(object_id);
                }
            }
            missing
        };
        for object_id in missing {
            if !self.has_open_tombstone(&object_id)? {
                self.conn().execute(
                    "INSERT INTO proposals (proposal_id, object_id, kind, state, created_ms)
                     VALUES (?1, ?2, 'tombstone', 'open', ?3)",
                    rusqlite::params![ids::new_id(), object_id, ids::now_ms()],
                )?;
                summary.tombstone_proposals += 1;
            }
        }
        Ok(summary)
    }

    /// Resolve a tombstone proposal. Accepting records the deletion as
    /// durable state and an operation; rejecting lets projection restore
    /// the file.
    pub fn resolve_tombstone(&self, proposal_id: &str, accept: bool) -> Result<()> {
        let object_id: String = self
            .conn()
            .query_row(
                "SELECT object_id FROM proposals
                 WHERE proposal_id = ?1 AND kind = 'tombstone' AND state = 'open'",
                [proposal_id],
                |row| row.get(0),
            )
            .map_err(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => KernelError::NoSuchJob {
                    job_id: format!("open tombstone proposal {proposal_id}"),
                },
                e => KernelError::Storage(e),
            })?;
        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let done: Result<()> = (|| {
            conn.execute(
                "UPDATE proposals SET state = ?2 WHERE proposal_id = ?1",
                rusqlite::params![proposal_id, if accept { "accepted" } else { "rejected" }],
            )?;
            if accept {
                conn.execute(
                    "UPDATE objects SET deleted = 1 WHERE object_id = ?1",
                    [&object_id],
                )?;
                conn.execute("DELETE FROM projections WHERE object_id = ?1", [&object_id])?;
                record_operation(conn, "tombstone", &object_id, "{}", ids::now_ms())?;
            }
            Ok(())
        })();
        match done {
            Ok(()) => conn.execute_batch("COMMIT;")?,
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                return Err(e);
            }
        }
        Ok(())
    }

    pub fn open_proposals(&self) -> Result<Vec<(String, String)>> {
        let mut stmt = self.conn().prepare(
            "SELECT proposal_id, object_id FROM proposals WHERE state = 'open' ORDER BY created_ms",
        )?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    // ---------------------------------------------------------- internals

    fn live_heads(&self) -> Result<Vec<Head>> {
        let mut stmt = self.conn().prepare(
            "SELECT o.object_id, o.type_id, o.type_schema, o.created_ms,
                    r.revision_id, r.payload,
                    (SELECT COUNT(*) FROM heads h2 WHERE h2.object_id = o.object_id) AS head_count
             FROM objects o
             JOIN heads h ON h.object_id = o.object_id
             JOIN revisions r ON r.revision_id = h.revision_id
             WHERE o.deleted = 0
             ORDER BY o.object_id, r.created_ms DESC, r.revision_id DESC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Head {
                object_id: row.get(0)?,
                type_id: row.get(1)?,
                type_schema: row.get(2)?,
                created_ms: row.get(3)?,
                revision_id: row.get(4)?,
                payload: row.get(5)?,
                conflicted: row.get::<_, i64>(6)? > 1,
            })
        })?;
        let mut heads: Vec<Head> = Vec::new();
        for row in rows {
            let head = row?;
            // Newest head renders; the conflicted flag surfaces the rest.
            if heads.last().map(|h| h.object_id.as_str()) != Some(head.object_id.as_str()) {
                heads.push(head);
            }
        }
        Ok(heads)
    }

    fn projection_path(&self, head: &Head) -> Result<String> {
        let recorded: Option<String> = self
            .conn()
            .query_row(
                "SELECT rel_path FROM projections WHERE object_id = ?1",
                [&head.object_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(no_rows_none)?;
        if let Some(path) = recorded {
            return Ok(path);
        }
        let payload: serde_json::Value = serde_json::from_str(&head.payload)?;
        if let Some(blob_rel) = payload.get("rel_path").and_then(|v| v.as_str()) {
            if let Some(stem) = blob_rel.rsplit_once('.').map(|(stem, _)| stem) {
                return Ok(format!("{stem}.md"));
            }
        }
        Ok(format!(
            "Objects/{}-{}.md",
            head.type_id.rsplit('.').next().unwrap_or("object"),
            ids::short(&head.object_id)
        ))
    }

    /// Deterministic canonical form: same committed state, same bytes.
    fn render(&self, head: &Head) -> Result<String> {
        let payload: serde_json::Value = serde_json::from_str(&head.payload)?;
        let mut front = String::from("---\n");
        front.push_str(&format!("memory-id: {}\n", head.object_id));
        front.push_str(&format!("memory-type: {}\n", head.type_id));
        front.push_str(&format!("memory-schema: {}\n", head.type_schema));
        front.push_str(&format!("memory-revision: {}\n", head.revision_id));
        front.push_str(&format!("memory-created-ms: {}\n", head.created_ms));
        if let Some(blob_rel) = payload.get("rel_path").and_then(|v| v.as_str()) {
            front.push_str(&format!("memory-blob: {blob_rel}\n"));
        }
        if head.conflicted {
            front.push_str("memory-conflicted: true\n");
        }
        front.push_str("---\n");

        let body = if let Some(text) = payload.get("text").and_then(|v| v.as_str()) {
            text.to_string()
        } else if let Some(blob_rel) = payload.get("rel_path").and_then(|v| v.as_str()) {
            if payload.get("media").and_then(|v| v.as_str()) == Some("txt") {
                std::fs::read_to_string(self.vault().join(blob_rel)).map_err(|e| {
                    KernelError::Io { path: self.vault().join(blob_rel), source: e }
                })?
            } else {
                let name = blob_rel.rsplit('/').next().unwrap_or(blob_rel);
                format!("![[{name}]]\n")
            }
        } else {
            // Unknown payload shape: preserve it legibly (rule 9).
            format!("```json\n{}\n```\n", head.payload)
        };
        Ok(front + &body)
    }

    fn ingest_external_edit(&self, object_id: &str, body: &str, disk: &[u8]) -> Result<()> {
        let revision_id = ids::new_id();
        let created_ms = ids::now_ms();
        let payload = serde_json::json!({ "text": body }).to_string();
        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let done: Result<()> = (|| {
            conn.execute(
                "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
                 VALUES (?1, ?2, ?3, 'external-edit', ?4)",
                rusqlite::params![revision_id, object_id, payload, created_ms],
            )?;
            conn.execute("DELETE FROM heads WHERE object_id = ?1", [object_id])?;
            conn.execute(
                "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                [object_id, revision_id.as_str()],
            )?;
            record_operation(
                conn,
                "create-revision",
                object_id,
                &serde_json::json!({
                    "revision_id": revision_id,
                    "payload": payload,
                    "origin": "external-edit",
                    "created_ms": created_ms,
                })
                .to_string(),
                created_ms,
            )?;
            conn.execute(
                "INSERT INTO events (event_id, event_type, subject_id, payload, created_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![ids::new_id(), REVISION_ADDED_EVENT, object_id, payload, created_ms],
            )?;
            // Record the disk state we ingested so projection may
            // canonicalise over it.
            conn.execute(
                "UPDATE projections SET revision_id = ?2, content_sha256 = ?3 WHERE object_id = ?1",
                rusqlite::params![object_id, revision_id, ids::sha256_hex(disk)],
            )?;
            Ok(())
        })();
        match done {
            Ok(()) => conn.execute_batch("COMMIT;")?,
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                return Err(e);
            }
        }
        Ok(())
    }

    fn record_projection(&self, head: &Head, rel_path: &str, content_sha: &str) -> Result<()> {
        self.conn().execute(
            "INSERT INTO projections (object_id, rel_path, revision_id, content_sha256)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(object_id) DO UPDATE
             SET rel_path = ?2, revision_id = ?3, content_sha256 = ?4",
            rusqlite::params![head.object_id, rel_path, head.revision_id, content_sha],
        )?;
        Ok(())
    }

    fn is_quarantined(&self, rel_path: &str) -> Result<bool> {
        let hit: Option<i64> = self
            .conn()
            .query_row("SELECT 1 FROM quarantine WHERE rel_path = ?1", [rel_path], |row| {
                row.get(0)
            })
            .map(Some)
            .or_else(no_rows_none)?;
        Ok(hit.is_some())
    }

    fn has_open_tombstone(&self, object_id: &str) -> Result<bool> {
        let hit: Option<i64> = self
            .conn()
            .query_row(
                "SELECT 1 FROM proposals WHERE object_id = ?1 AND state = 'open'",
                [object_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(no_rows_none)?;
        Ok(hit.is_some())
    }

    fn quarantine(&self, rel_path: &str, reason: &str) -> Result<i64> {
        let inserted = self.conn().execute(
            "INSERT OR IGNORE INTO quarantine (rel_path, reason, created_ms)
             VALUES (?1, ?2, ?3)",
            rusqlite::params![rel_path, reason, ids::now_ms()],
        )?;
        Ok(inserted as i64)
    }

    fn record_import(&self, rel_path: &str) -> Result<i64> {
        let inserted = self.conn().execute(
            "INSERT OR IGNORE INTO imports (rel_path, created_ms) VALUES (?1, ?2)",
            rusqlite::params![rel_path, ids::now_ms()],
        )?;
        Ok(inserted as i64)
    }

    /// Every `.md` file in the vault outside `.gilune/`, as portable
    /// `/`-separated rel-paths in deterministic order.
    fn markdown_files(&self) -> Result<Vec<String>> {
        let mut found = Vec::new();
        let mut stack = vec![self.vault().to_path_buf()];
        while let Some(dir) = stack.pop() {
            for entry in std::fs::read_dir(&dir)
                .map_err(|e| KernelError::Io { path: dir.clone(), source: e })?
            {
                let entry = entry.map_err(|e| KernelError::Io { path: dir.clone(), source: e })?;
                let path = entry.path();
                let name = entry.file_name().to_string_lossy().to_string();
                if path.is_dir() {
                    if !name.starts_with('.') {
                        stack.push(path);
                    }
                } else if name.ends_with(".md") && !name.starts_with(".tmp-") {
                    let rel = path
                        .strip_prefix(self.vault())
                        .unwrap_or(&path)
                        .to_path_buf();
                    found.push(fs::portable_rel(&rel));
                }
            }
        }
        found.sort();
        Ok(found)
    }
}

enum FrontMatter {
    Absent,
    Malformed,
    Parsed { keys: BTreeMap<String, String>, body: String },
}

fn parse_front_matter(text: &str) -> FrontMatter {
    let Some(rest) = text.strip_prefix("---\n") else {
        return FrontMatter::Absent;
    };
    let Some(end) = rest.find("\n---\n") else {
        return FrontMatter::Malformed;
    };
    let mut keys = BTreeMap::new();
    for line in rest[..end].lines() {
        let Some((key, value)) = line.split_once(':') else {
            return FrontMatter::Malformed;
        };
        keys.insert(key.trim().to_string(), value.trim().to_string());
    }
    let body = rest[end + "\n---\n".len()..].to_string();
    FrontMatter::Parsed { keys, body }
}

fn write_atomic(target: &std::path::Path, content: &[u8]) -> Result<()> {
    let parent = target.parent().ok_or_else(|| KernelError::VaultUnusable {
        reason: format!("{} has no parent directory", target.display()),
    })?;
    std::fs::create_dir_all(parent)
        .map_err(|e| KernelError::Io { path: parent.to_path_buf(), source: e })?;
    let file_name = target
        .file_name()
        .ok_or_else(|| KernelError::VaultUnusable {
            reason: format!("{} has no file name", target.display()),
        })?
        .to_string_lossy()
        .to_string();
    let tmp = parent.join(format!(".tmp-{file_name}"));
    std::fs::write(&tmp, content).map_err(|e| KernelError::Io { path: tmp.clone(), source: e })?;
    fs::fsync_file(&tmp)?;
    std::fs::rename(&tmp, target)
        .map_err(|e| KernelError::Io { path: target.to_path_buf(), source: e })?;
    fs::fsync_dir(parent)?;
    Ok(())
}

fn no_rows_none<T>(e: rusqlite::Error) -> std::result::Result<Option<T>, rusqlite::Error> {
    match e {
        rusqlite::Error::QueryReturnedNoRows => Ok(None),
        e => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn captured(text: &str) -> (tempfile::TempDir, Store, String) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let receipt = store.capture_bytes("txt", text.as_bytes()).expect("capture");
        store.project().expect("project");
        let projection_rel = receipt.rel_path.replace(".txt", ".md");
        (dir, store, projection_rel)
    }

    fn read_rel(store: &Store, rel: &str) -> String {
        std::fs::read_to_string(store.vault().join(rel)).expect("read projection")
    }

    #[test]
    fn a_committed_capture_projects_with_identity_and_body() {
        let (_dir, store, rel) = captured("Remember the milk");
        let text = read_rel(&store, &rel);
        assert!(text.starts_with("---\n"));
        assert!(text.contains("memory-type: org.memory.source\n"));
        assert!(text.contains("memory-blob: "));
        assert!(text.ends_with("Remember the milk"));
        assert_eq!(store.status().expect("status").projections, 1);
    }

    #[test]
    fn deleting_the_markdown_layer_rebuilds_byte_identical_content() {
        let (_dir, store, rel) = captured("rebuild me");
        let before = read_rel(&store, &rel);
        std::fs::remove_file(store.vault().join(&rel)).expect("delete projection");
        // A deleted tracked file is a tombstone proposal, not an instant
        // rebuild; rejecting the proposal restores the file.
        let summary = store.reconcile().expect("reconcile");
        assert_eq!(summary.tombstone_proposals, 1);
        let (proposal_id, _) = store.open_proposals().expect("proposals")[0].clone();
        store.resolve_tombstone(&proposal_id, false).expect("reject");
        store.project().expect("project");
        assert_eq!(read_rel(&store, &rel), before);
    }

    #[test]
    fn an_accepted_tombstone_deletes_durably_and_does_not_resurrect() {
        let (_dir, store, rel) = captured("delete me");
        std::fs::remove_file(store.vault().join(&rel)).expect("delete projection");
        store.reconcile().expect("reconcile");
        let (proposal_id, _) = store.open_proposals().expect("proposals")[0].clone();
        store.resolve_tombstone(&proposal_id, true).expect("accept");
        store.project().expect("project");
        assert!(!store.vault().join(&rel).exists(), "must not resurrect");
        assert_eq!(store.status().expect("status").operations, 2); // capture + tombstone
    }

    #[test]
    fn an_external_edit_becomes_a_revision_and_projection_canonicalises() {
        let (_dir, store, rel) = captured("original text");
        let path = store.vault().join(&rel);
        let edited = read_rel(&store, &rel).replace("original text", "edited text");
        std::fs::write(&path, &edited).expect("external edit");

        let summary = store.reconcile().expect("reconcile");
        assert_eq!(summary.edits, 1);
        store.project().expect("canonicalise");

        let canonical = read_rel(&store, &rel);
        assert!(canonical.ends_with("edited text"));
        // History grew: capture revision + external-edit revision.
        let revisions: i64 = store
            .conn()
            .query_row("SELECT COUNT(*) FROM revisions", [], |row| row.get(0))
            .expect("count");
        assert_eq!(revisions, 2);
        // Re-running both is a fixed point.
        assert_eq!(store.reconcile().expect("reconcile again").edits, 0);
        assert_eq!(store.project().expect("project again").written, 0);
    }

    #[test]
    fn the_original_blob_is_never_rewritten_by_an_edit() {
        let (_dir, store, rel) = captured("immutable capture");
        let blob_rel = rel.replace(".md", ".txt");
        let edited = read_rel(&store, &rel).replace("immutable capture", "changed");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit projection");
        store.reconcile().expect("reconcile");
        store.project().expect("project");
        let blob = std::fs::read_to_string(store.vault().join(&blob_rel)).expect("blob");
        assert_eq!(blob, "immutable capture", "source bytes are immutable (rule 2)");
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn malformed_front_matter_quarantines_untouched_and_projection_holds_back() {
        let (_dir, store, rel) = captured("innocent text");
        let path = store.vault().join(&rel);
        let mangled = "---\nno colon here\n---\nbody".to_string();
        std::fs::write(&path, &mangled).expect("mangle");
        let summary = store.reconcile().expect("reconcile");
        assert_eq!(summary.quarantined, 1);
        store.project().expect("project");
        assert_eq!(read_rel(&store, &rel), mangled, "quarantined file untouched");
    }

    #[test]
    fn a_foreign_markdown_file_is_an_import_candidate_not_an_error() {
        let (_dir, store, _rel) = captured("resident");
        std::fs::write(store.vault().join("Notes.md"), "just my notes").expect("foreign file");
        let summary = store.reconcile().expect("reconcile");
        assert_eq!(summary.imports, 1);
        assert_eq!(summary.quarantined, 0);
        // Still there, still untouched, not double-counted next time.
        assert_eq!(store.reconcile().expect("again").imports, 0);
        assert_eq!(
            std::fs::read_to_string(store.vault().join("Notes.md")).expect("read"),
            "just my notes"
        );
    }

    #[test]
    fn a_renamed_projection_is_tracked_by_id_not_path() {
        let (_dir, store, rel) = captured("name me differently");
        let new_rel = "Renamed/better-name.md";
        let target = store.vault().join(new_rel);
        std::fs::create_dir_all(target.parent().expect("parent")).expect("mkdir");
        std::fs::rename(store.vault().join(&rel), &target).expect("rename");

        let summary = store.reconcile().expect("reconcile");
        assert_eq!(summary.renames, 1);
        assert_eq!(summary.tombstone_proposals, 0, "a rename is not a deletion");
        store.project().expect("project");
        assert!(!store.vault().join(&rel).exists(), "no duplicate at the old path");
        assert!(target.exists());
    }

    #[test]
    fn external_edits_record_operations_for_sync() {
        let (_dir, store, rel) = captured("sync me");
        let edited = read_rel(&store, &rel).replace("sync me", "sync me please");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit");
        store.reconcile().expect("reconcile");
        let kinds: Vec<String> = {
            let mut stmt = store
                .conn()
                .prepare("SELECT kind FROM operations ORDER BY op_seq")
                .expect("stmt");
            stmt.query_map([], |row| row.get(0))
                .expect("query")
                .collect::<std::result::Result<_, _>>()
                .expect("rows")
        };
        assert_eq!(kinds, vec!["create-source".to_string(), "create-revision".to_string()]);
    }
}
