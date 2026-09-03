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
//! project identically. Front-matter keys are `gilune-*` (owner decision
//! 2026-08-30): keys are user-visible product surface in every note, so
//! they carry the name deliberately. The *values* stay contract-neutral —
//! type and event identifiers remain `org.memory.*` per the permanent
//! namespace rule — so a future rename touches keys only.

use crate::crashpoints::crashpoint;
use crate::revisions::{add_revision, Announce};
use crate::store::Store;
use crate::{fs, ids, KernelError, Result};
use std::collections::BTreeMap;

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct ProjectSummary {
    pub written: i64,
    pub unchanged: i64,
    pub held_back: i64,
    /// Files withdrawn because their object was deleted elsewhere and the
    /// tombstone reached this device.
    pub removed: i64,
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

/// This projection as a plugin declaration (ADR 0011). It lives beside
/// the implementation so every host declares the same thing, and it is
/// `required`: the vault is the product surface, not an optional view.
pub fn plugin_descriptor() -> crate::plugins::PluginDescriptor {
    crate::plugins::PluginDescriptor {
        plugin_id: "org.memory.vault".to_string(),
        name: "Markdown vault".to_string(),
        class: crate::plugins::PluginClass::Projection,
        profile: crate::plugins::PluginProfile::Bundled,
        required: true,
        processor_id: None,
        subscribes: None,
        emits: Vec::new(),
        capabilities: Vec::new(),
        contributions: Vec::new(),
    }
}

impl Store {
    /// Project every live object into the vault, attached captures aside
    /// (ADR 0013). Idempotent and safe to run at any time: a file that
    /// differs from what the kernel last wrote is held back for
    /// `reconcile` rather than clobbered, and quarantined paths and
    /// tombstone-proposed objects are never touched.
    pub fn project(&self) -> Result<ProjectSummary> {
        let mut summary = ProjectSummary::default();
        // Which objects are already reported as unreadable. Read once, so
        // the ordinary pass — where nothing is wrong — clears nothing and
        // writes nothing.
        let unreadable: std::collections::BTreeSet<String> = self
            .integrity_alerts_of(crate::alerts::VAULT_CONTENT_UNREADABLE)?
            .into_iter()
            .map(|a| a.subject)
            .collect();
        for head in self.live_heads()? {
            if is_attached(&head.payload) {
                // An attached capture belongs to a note that already
                // exists (ADR 0013), so it has no note of its own and
                // nothing to hold back: it is absent from the projection
                // rather than pending in it, and no counter reports it.
                continue;
            }
            let rel_path = self.projection_path(&head)?;
            if self.is_quarantined(&rel_path)? || self.has_open_tombstone(&head.object_id)? {
                summary.held_back += 1;
                continue;
            }
            let Some(canonical) = self.render(&head)? else {
                // The object's own preserved bytes could not be read here
                // and are not in transit: rendering a body we cannot
                // vouch for would put a placeholder where the words were,
                // and reconcile would then ingest it as an edit. Hold the
                // object back and leave whatever is on disk alone.
                //
                // And **say so**. Holding back silently was the whole of
                // the fault: `held_back` is one number on a summary
                // nothing displays, so a note whose recording had gone
                // missing simply stopped being maintained, with nothing
                // anywhere naming the note or the file.
                summary.held_back += 1;
                crate::alerts::raise(
                    self.conn(),
                    crate::alerts::VAULT_CONTENT_UNREADABLE,
                    &head.object_id,
                    &format!(
                        "this note's own preserved bytes could not be read, so the note at \
                         {rel_path} is being left exactly as it is rather than rewritten \
                         around a gap"
                    ),
                )?;
                continue;
            };
            if unreadable.contains(&head.object_id) {
                // It renders again: whatever was standing about it stops
                // being reported without anyone dismissing it.
                crate::alerts::clear(
                    self.conn(),
                    crate::alerts::VAULT_CONTENT_UNREADABLE,
                    &head.object_id,
                )?;
            }
            let canonical_sha = ids::sha256_hex(canonical.as_bytes());
            let target = self.vault().join(&rel_path);
            let row: Option<(String, String, String)> = self
                .conn()
                .query_row(
                    "SELECT content_sha256, rel_path, revision_id FROM projections
                     WHERE object_id = ?1",
                    [&head.object_id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            let recorded = row.as_ref().map(|(sha, _, _)| sha.clone());
            // The disk read stays: it is what detects an external edit,
            // and skipping it would be skipping reconciliation's input.
            let on_disk = std::fs::read(&target).ok();
            let disk_sha = on_disk.as_deref().map(ids::sha256_hex);

            match (&disk_sha, &recorded) {
                (Some(d), _) if *d == canonical_sha => {
                    // Nothing about the projection has changed, so there
                    // is nothing to record. This used to write the same
                    // row back on every pass — one INSERT OR REPLACE per
                    // note per project, which for the steady state of a
                    // vault is the whole vault rewritten to say that
                    // nothing happened.
                    let unchanged = row.as_ref().is_some_and(|(sha, path, revision)| {
                        sha == &canonical_sha
                            && path == &rel_path
                            && revision == &head.revision_id
                    });
                    if !unchanged {
                        self.record_projection(&head, &rel_path, &canonical_sha)?;
                    }
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
        summary.removed = self.withdraw_tombstoned()?;
        Ok(summary)
    }

    /// Withdraw the projections of objects deleted elsewhere. A tombstone
    /// that arrives by sync finds the file still on disk — unlike a local
    /// deletion, which is a missing file in the first place — so the
    /// projection has to answer it.
    ///
    /// Only bytes the kernel itself last wrote are removed. A file edited
    /// since then is left exactly where it is: a deletion on one device
    /// must not silently destroy words written on another, and reconcile
    /// preserves it as an import candidate (constitution rule 1).
    ///
    /// Two questions, and both have to be asked.
    ///
    /// **Did the kernel write these bytes?** `disk_sha == recorded_sha`.
    /// Not sufficient on its own: `ingest_external_edit` records the
    /// *edited disk bytes* against the projection so the next projection
    /// may canonicalise over them, so one tick after an edit the hash
    /// matches again.
    ///
    /// **Did the deleter see everything that is here?** Every local head
    /// of the object must be among the heads the tombstone names. That is
    /// the question that cannot be spoofed by a hash, and it is the same
    /// question a revision answers with `supersedes`: a head the deleter
    /// did not name was concurrent with the deletion, which is somebody
    /// keeping the note while somebody else deleted it.
    ///
    /// What it replaced was a second hash — "are these bytes what this
    /// kernel would render right now?" — which was cheap to state and
    /// wrong in both directions. It kept a file whose object had merely
    /// been revised before the delete arrived (a revise-then-delete in one
    /// pull left an orphan for ever), and it would have deleted an edit
    /// that happened to canonicalise to the same bytes.
    ///
    /// A tombstone written before the key existed names nothing, and for
    /// those the old proxy is kept in its narrowest form: withdraw unless
    /// the projection's own revision came from an external edit.
    fn withdraw_tombstoned(&self) -> Result<i64> {
        let doomed: Vec<(String, String, String, String)> = {
            let mut stmt = self.conn().prepare(
                "SELECT p.object_id, p.rel_path, p.content_sha256, p.revision_id
                 FROM projections p JOIN objects o ON o.object_id = p.object_id
                 WHERE o.deleted = 1",
            )?;
            let rows = stmt.query_map([], |row| {
                Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?))
            })?;
            rows.collect::<std::result::Result<_, _>>()?
        };
        let mut removed = 0;
        for (object_id, rel_path, recorded_sha, projected_revision) in doomed {
            let target = self.vault().join(&rel_path);
            match std::fs::read(&target) {
                Ok(bytes) => {
                    let disk_sha = ids::sha256_hex(&bytes);
                    let ours = disk_sha == recorded_sha
                        && self.deleter_saw_everything(&object_id, &projected_revision)?;
                    if !ours {
                        // Somebody's words, not ours. The file stays and
                        // so does the projection row, so reconcile finds
                        // it and says why it is being kept.
                        continue;
                    }
                    std::fs::remove_file(&target)
                        .map_err(|e| KernelError::Io { path: target.clone(), source: e })?;
                    if let Some(parent) = target.parent() {
                        fs::fsync_dir(parent)?;
                    }
                    removed += 1;
                }
                // Already gone: there is nothing to withdraw, and the
                // projection row goes with it.
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                // Unreadable for any other reason — a lock, a permission,
                // a disconnected drive — says nothing about what the file
                // contains. Keep the row: forgetting the projection would
                // make the note an import candidate the next time the
                // vault could be read, out of a transient failure.
                Err(_) => continue,
            }
            self.conn()
                .execute("DELETE FROM projections WHERE object_id = ?1", [&object_id])?;
        }
        Ok(removed)
    }

    /// Ingest external changes to the vault. Never destructive: kernel
    /// history only grows.
    pub fn reconcile(&self) -> Result<ReconcileSummary> {
        let mut summary = ReconcileSummary::default();
        let mut seen_paths: Vec<String> = Vec::new();
        // Objects whose `vault-deleted-note-edited` notice is still true
        // at the end of this pass: a file is still in the vault, still
        // carrying that object's id, and still unclaimed by the kernel.
        // Anything standing that is *not* in here has been resolved — the
        // person deleted the file, or brought it back as something new —
        // and a notice about a decision already taken is a notice nobody
        // can act on.
        let mut kept: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();

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
                    let Some(object_id) = keys.get("gilune-id").cloned() else {
                        summary.quarantined +=
                            self.quarantine(&rel_path, "front matter has no gilune-id")?;
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
                        // A file carrying an id the kernel no longer
                        // claims. When that object was deleted elsewhere,
                        // this is the note being kept because it was
                        // edited here, and its notice is still true.
                        if self.is_deleted(&object_id)? {
                            kept.insert(object_id.clone());
                        }
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
                        if self.is_deleted(&object_id)? {
                            // The object was deleted on another device and
                            // this file was edited here. The edit is the
                            // person keeping it, so the words stay — as a
                            // file the kernel no longer claims, which is
                            // to say an import candidate. Ingesting the
                            // edit instead would write a revision of a
                            // deleted object and, worse, hand projection
                            // the very hash that authorises deleting the
                            // file (constitution rule 1: never destroy
                            // what the kernel did not write).
                            kept.insert(object_id.clone());
                            summary.imports += self.keep_edit_of_deleted(&object_id, &rel_path)?;
                        } else {
                            self.ingest_external_edit(&object_id, &body, text.as_bytes())?;
                            summary.edits += 1;
                        }
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

        // Withdraw every kept-edit notice the vault no longer bears out.
        // The alert had no exit at all: it was raised when the edit was
        // first noticed and stood for ever, so a person who read it and
        // then deleted the file — or brought the words back as a new note
        // — was left with a standing notice about a decision they had
        // already made. Resolution is *observed* here rather than
        // recorded in a column, so it cannot go stale: the question is
        // asked of the vault on every pass.
        for alert in self
            .integrity_alerts_of(crate::alerts::VAULT_DELETED_NOTE_EDITED)?
        {
            if !kept.contains(&alert.subject) {
                crate::alerts::clear(
                    self.conn(),
                    crate::alerts::VAULT_DELETED_NOTE_EDITED,
                    &alert.subject,
                )?;
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
                // The heads the deleter could see, named in the operation
                // exactly as a revision names the heads it supersedes.
                // Without them a tombstone says only "this object is
                // gone", and the receiving projection had no way to tell
                // "the deleter saw everything that is here" from "somebody
                // else wrote something the deleter never had in front of
                // them" — so it fell back to re-rendering the object and
                // comparing hashes, which an ingested edit could match.
                let superseded = crate::revisions::heads(conn, &object_id)?;
                let mut carried = serde_json::json!({ "supersedes": superseded });
                crate::operations::name_origins(conn, &object_id, &superseded, &mut carried)?;
                conn.execute(
                    "UPDATE objects SET deleted = 1 WHERE object_id = ?1",
                    [&object_id],
                )?;
                conn.execute("DELETE FROM projections WHERE object_id = ?1", [&object_id])?;
                crate::operations::record(
                    conn,
                    crate::operations::TOMBSTONE,
                    &object_id,
                    carried,
                    ids::now_ms(),
                )?;
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
        let mut stmt = self.conn().prepare(&head_sql())?;
        let rows = stmt.query_map([], read_head)?;
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

    /// Whether every head this device holds for the object is named by a
    /// tombstone about it — "the deleter saw everything that is here".
    ///
    /// The union across every tombstone the object has: with several
    /// deletions from several devices, a head any one of them named was
    /// seen by a deleter, and a head none of them named was concurrent
    /// with all of them.
    ///
    /// A tombstone written before the key existed names nothing at all,
    /// and cannot be told from one that legitimately named no heads. For
    /// those the older, weaker proxy stands: the projection's own revision
    /// is not an external edit. It is not exact — it cannot see an edit
    /// made on another device — but it is the question the key now
    /// answers properly, and a vault holding pre-key tombstones has by
    /// definition never synced under a kernel that wrote them.
    fn deleter_saw_everything(
        &self,
        object_id: &str,
        projected_revision: &str,
    ) -> Result<bool> {
        let mut named: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();
        let mut any_key = false;
        {
            let mut stmt = self.conn().prepare(
                "SELECT payload FROM operations WHERE kind = ?1 AND target_id = ?2",
            )?;
            let rows = stmt.query_map(
                rusqlite::params![crate::operations::TOMBSTONE, object_id],
                |row| row.get::<_, String>(0),
            )?;
            for row in rows {
                let payload: serde_json::Value =
                    serde_json::from_str(&row?).unwrap_or(serde_json::Value::Null);
                let Some(heads) = payload.get("supersedes").and_then(|v| v.as_array()) else {
                    continue;
                };
                any_key = true;
                named.extend(heads.iter().filter_map(|v| v.as_str()).map(str::to_string));
            }
        }
        if !any_key {
            let origin: Option<String> = self
                .conn()
                .query_row(
                    "SELECT origin FROM revisions WHERE revision_id = ?1",
                    [projected_revision],
                    |row| row.get(0),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            return Ok(origin.as_deref() != Some("external-edit"));
        }
        Ok(crate::revisions::heads(self.conn(), object_id)?
            .iter()
            .all(|head| named.contains(head)))
    }

    fn is_deleted(&self, object_id: &str) -> Result<bool> {
        let deleted: Option<i64> = self
            .conn()
            .query_row(
                "SELECT deleted FROM objects WHERE object_id = ?1",
                [object_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(no_rows_none)?;
        Ok(deleted == Some(1))
    }

    /// Keep an edited file whose object was deleted elsewhere: stop
    /// claiming it as a projection, record it as an import candidate, and
    /// raise the notice that says what happened. Nothing on disk is
    /// touched — the whole point is that these are the person's words.
    fn keep_edit_of_deleted(&self, object_id: &str, rel_path: &str) -> Result<i64> {
        self.conn()
            .execute("DELETE FROM projections WHERE object_id = ?1", [object_id])?;
        crate::alerts::raise(
            self.conn(),
            crate::alerts::VAULT_DELETED_NOTE_EDITED,
            object_id,
            &format!(
                "this note was deleted on another device, but it had been edited here; \
                 the edited file is kept at {rel_path} and is offered as an import"
            ),
        )?;
        self.record_import(rel_path)
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
        // Blobless objects project into a folder named for their type:
        // org.memory.transcript -> Transcripts/transcript-<short>.md.
        let last = head.type_id.rsplit('.').next().unwrap_or("object");
        let mut folder: String = last.chars().take(1).flat_map(char::to_uppercase).collect();
        folder.push_str(&last[1.min(last.len())..]);
        folder.push('s');
        Ok(format!("{folder}/{last}-{}.md", ids::short(&head.object_id)))
    }

    /// Deterministic canonical form: same committed state, same bytes.
    ///
    /// `None` means "not this pass": the object's own preserved bytes are
    /// the body, and they could not be read, and they are not content in
    /// transit. There is no honest rendering of that, and a dishonest one
    /// is worse than none — the placeholder would become the note's body,
    /// and the next reconcile would ingest it as an edit.
    fn render(&self, head: &Head) -> Result<Option<String>> {
        let payload: serde_json::Value = serde_json::from_str(&head.payload)?;
        let mut front = String::from("---\n");
        front.push_str(&format!("gilune-id: {}\n", head.object_id));
        front.push_str(&format!("gilune-type: {}\n", head.type_id));
        front.push_str(&format!("gilune-schema: {}\n", head.type_schema));
        front.push_str(&format!("gilune-revision: {}\n", head.revision_id));
        front.push_str(&format!("gilune-created-ms: {}\n", head.created_ms));
        if let Some(blob_rel) = payload.get("rel_path").and_then(|v| v.as_str()) {
            front.push_str(&format!("gilune-blob: {blob_rel}\n"));
        }
        if head.conflicted {
            front.push_str("gilune-conflicted: true\n");
        }
        front.push_str("---\n");

        let body = if let Some(text) = payload.get("text").and_then(|v| v.as_str()) {
            text.to_string()
        } else if let Some(blob_rel) = payload.get("rel_path").and_then(|v| v.as_str()) {
            let name = blob_rel.rsplit('/').next().unwrap_or(blob_rel);
            if payload.get("media").and_then(|v| v.as_str()) == Some("txt") {
                match std::fs::read_to_string(self.vault().join(blob_rel)) {
                    Ok(text) => text,
                    // Bytes that have not arrived yet are the ordinary
                    // state of a note whose operation reached this device
                    // before its content did (ADR 0005). Project the link
                    // now and canonicalise when the content lands:
                    // refusing to project would make one missing transfer
                    // hide a vault. That reasoning covers exactly this
                    // case and no other — a local read failure is not a
                    // transfer in progress, and answering it with the
                    // same placeholder would put `![[name]]` where the
                    // person's words are and let reconcile ingest it.
                    Err(_) if self.awaiting_content(&head.object_id, &payload)? => {
                        format!("![[{name}]]\n")
                    }
                    Err(_) => return Ok(None),
                }
            } else {
                format!("![[{name}]]\n")
            }
        } else {
            // Unknown payload shape: preserve it legibly (rule 9).
            format!("```json\n{}\n```\n", head.payload)
        };
        Ok(Some(front + &body))
    }

    /// Whether this object's bytes are content still crossing from
    /// another device, rather than content this device is missing.
    fn awaiting_content(&self, object_id: &str, payload: &serde_json::Value) -> Result<bool> {
        let row: Option<(String, String)> = match payload.get("blob_id").and_then(|v| v.as_str()) {
            Some(blob_id) => self
                .conn()
                .query_row(
                    "SELECT state, origin FROM blobs WHERE blob_id = ?1",
                    [blob_id],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .map(Some)
                .or_else(no_rows_none)?,
            None => self
                .conn()
                .query_row(
                    "SELECT state, origin FROM blobs WHERE object_id = ?1",
                    [object_id],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .map(Some)
                .or_else(no_rows_none)?,
        };
        Ok(matches!(row, Some((state, origin)) if state != "ready" && origin == "sync"))
    }

    fn ingest_external_edit(&self, object_id: &str, body: &str, disk: &[u8]) -> Result<()> {
        let revision_id = ids::new_id();
        let created_ms = ids::now_ms();
        // An edit changes the words, not the document's other facts: the
        // new text merges into the current head so `rel_path` and friends
        // survive. Replacing the document here would strip a voice note's
        // link to its own audio on the first edit (found 2026-08-31).
        let mut document = crate::revisions::head_payload(self.conn(), object_id)?
            .and_then(|payload| serde_json::from_str::<serde_json::Value>(&payload).ok())
            .unwrap_or_else(|| serde_json::json!({}));
        document["text"] = serde_json::Value::String(body.to_string());
        let payload = document.to_string();
        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let done: Result<()> = (|| {
            add_revision(
                conn,
                object_id,
                &revision_id,
                &payload,
                "external-edit",
                created_ms,
                Announce::Event,
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
        // OR REPLACE covers both unique constraints (object_id, rel_path):
        // two processes recording the same projection concurrently must
        // both succeed, whichever constraint SQLite trips first.
        self.conn().execute(
            "INSERT OR REPLACE INTO projections
             (object_id, rel_path, revision_id, content_sha256)
             VALUES (?1, ?2, ?3, ?4)",
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

/// The head selection the projection reads. One statement, ordered by the
/// one rule every reader takes (`newest_head_first`), so replicas cannot
/// disagree about which head renders.
fn head_sql() -> String {
    format!(
        "SELECT o.object_id, o.type_id, o.type_schema, o.created_ms,
                r.revision_id, r.payload,
                (SELECT COUNT(*) FROM heads h2 WHERE h2.object_id = o.object_id) AS head_count
         FROM objects o
         JOIN heads h ON h.object_id = o.object_id
         JOIN revisions r ON r.revision_id = h.revision_id
         WHERE o.deleted = 0
         ORDER BY o.object_id, {}",
        crate::revisions::newest_head_first("r").trim_start_matches("ORDER BY ")
    )
}

fn read_head(row: &rusqlite::Row<'_>) -> rusqlite::Result<Head> {
    Ok(Head {
        object_id: row.get(0)?,
        type_id: row.get(1)?,
        type_schema: row.get(2)?,
        created_ms: row.get(3)?,
        revision_id: row.get(4)?,
        payload: row.get(5)?,
        conflicted: row.get::<_, i64>(6)? > 1,
    })
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
    // Unique per writer: concurrent processes projecting the same object
    // must never share a temp file (multi-instance is supported use).
    let tmp = parent.join(format!(".tmp-{}-{file_name}", ids::short(&ids::new_id())));
    std::fs::write(&tmp, content).map_err(|e| KernelError::Io { path: tmp.clone(), source: e })?;
    fs::fsync_file(&tmp)?;
    // Windows can transiently refuse a replace while another process has
    // the target mid-replace or open; a bounded retry rides it out
    // (multi-instance is supported use).
    let mut renamed = std::fs::rename(&tmp, target);
    for _ in 0..10 {
        if renamed.is_ok() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
        renamed = std::fs::rename(&tmp, target);
    }
    renamed.map_err(|e| KernelError::Io { path: target.to_path_buf(), source: e })?;
    fs::fsync_dir(parent)?;
    Ok(())
}

fn is_attached(payload: &str) -> bool {
    serde_json::from_str(payload)
        .map(|document| crate::capture::is_attached(&document))
        .unwrap_or(false)
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

    /// One operation from another device, applied the way one really
    /// arrives — through `apply_operation`, not by poking the database.
    fn from_elsewhere(
        store: &Store,
        seq: i64,
        kind: &str,
        target: &str,
        payload: serde_json::Value,
    ) {
        let bytes = crate::operations::encode(&crate::operations::Envelope {
            version: crate::operations::ENVELOPE_VERSION,
            op_id: format!("elsewhere-{seq}"),
            device_id: "device-elsewhere".to_string(),
            op_seq: seq,
            kind: kind.to_string(),
            target_id: target.to_string(),
            created_ms: 1_700_000_000_000 + seq,
            payload,
        });
        store
            .apply_operation("test", &bytes, crate::limits::Limits::DEFAULT)
            .expect("the operation applies");
    }

    /// A tombstone written before tombstones named the heads they saw —
    /// which is every tombstone in every vault that already exists.
    fn tombstone_from_elsewhere(store: &Store, object_id: &str) {
        from_elsewhere(
            store,
            1,
            crate::operations::TOMBSTONE,
            object_id,
            serde_json::json!({}),
        );
    }

    /// A tombstone that names what the deleter could see.
    fn tombstone_naming(store: &Store, seq: i64, object_id: &str, heads: &[&str]) {
        from_elsewhere(
            store,
            seq,
            crate::operations::TOMBSTONE,
            object_id,
            serde_json::json!({ "supersedes": heads }),
        );
    }

    fn head_of(store: &Store, object_id: &str) -> String {
        crate::revisions::heads(store.conn(), object_id)
            .expect("heads")
            .first()
            .expect("a head")
            .clone()
    }

    fn alert_kinds(store: &Store) -> Vec<String> {
        store
            .integrity_alerts()
            .expect("alerts")
            .into_iter()
            .map(|a| a.kind)
            .collect()
    }

    #[test]
    fn a_revision_and_then_a_deletion_in_one_pull_withdraws_the_file() {
        // The regression the second hash caused. A device revised the note
        // and then deleted it, and both operations arrived before this
        // device projected anything. The file on disk is still the bytes
        // the kernel wrote — nobody here touched it — but re-rendering the
        // object now produces the *revised* text, so "are these the bytes
        // I would write today" answered no and the file was kept for ever
        // as an orphan of a note that had been deleted.
        let (_dir, store, rel) = captured("first words");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let seen = head_of(&store, &object_id);

        from_elsewhere(
            &store,
            1,
            crate::operations::OBJECT_REVISED,
            &object_id,
            serde_json::json!({
                "object_id": object_id,
                "revision": {
                    "revision_id": "rev-elsewhere",
                    "payload": serde_json::json!({ "text": "second words" }).to_string(),
                    "origin": "external-edit",
                    "created_ms": 1_700_000_000_500i64,
                },
                "supersedes": [seen],
            }),
        );
        tombstone_naming(&store, 2, &object_id, &["rev-elsewhere"]);

        assert_eq!(store.project().expect("project").removed, 1);
        assert!(!store.vault().join(&rel).exists(), "the deletion is carried out");
        assert!(alert_kinds(&store).is_empty(), "and it is not reported as a decision");
    }

    #[test]
    fn an_edit_the_deleter_had_already_seen_is_withdrawn() {
        // The other half of the same rule, and the one that says this is a
        // question about *what the deleter saw* rather than about who
        // wrote the bytes. The edit was made here, synced, and only then
        // deleted — so the deleter had it in front of them, named it, and
        // the deletion is a decision about the edited note.
        let (_dir, store, rel) = captured("written here");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let edited = read_rel(&store, &rel).replace("written here", "and edited here");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit");
        assert_eq!(store.reconcile().expect("reconcile").edits, 1);
        let mine = head_of(&store, &object_id);

        tombstone_naming(&store, 1, &object_id, &[mine.as_str()]);
        assert_eq!(store.project().expect("project").removed, 1);
        assert!(!store.vault().join(&rel).exists());
    }

    #[test]
    fn an_edit_the_deleter_never_saw_keeps_the_file_even_when_the_hash_matches() {
        // The second-order case, and the reason the head question exists:
        // `ingest_external_edit` records the *edited disk bytes* against
        // the projection, so one tick after an edit "did the kernel write
        // these bytes" answers yes. Only the head the tombstone does not
        // name says that somebody wrote something the deleter never had.
        let (_dir, store, rel) = captured("written here");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let seen = head_of(&store, &object_id);
        let edited = read_rel(&store, &rel).replace("written here", "and edited here");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit");
        assert_eq!(store.reconcile().expect("reconcile").edits, 1);

        // The deleter names only the head it had — the capture — not the
        // edit that happened concurrently here.
        tombstone_naming(&store, 1, &object_id, &[seen.as_str()]);
        assert_eq!(store.project().expect("project").removed, 0);
        assert_eq!(
            read_rel(&store, &rel),
            edited,
            "the words a person wrote survive a deletion made elsewhere"
        );
    }

    #[test]
    fn a_tombstone_from_elsewhere_never_deletes_a_note_edited_here() {
        // The deletion and the edit were concurrent, and the edit is a
        // person keeping the note. The file stays, and it says why.
        let (_dir, store, rel) = captured("worth keeping");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        tombstone_from_elsewhere(&store, &object_id);

        let edited = read_rel(&store, &rel).replace("worth keeping", "still worth keeping");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit");

        let reconciled = store.reconcile().expect("reconcile");
        assert_eq!(reconciled.edits, 0, "an edit is not ingested against a deleted object");
        assert_eq!(reconciled.imports, 1, "it is preserved as something to bring back");
        let summary = store.project().expect("project");
        assert_eq!(summary.removed, 0, "nothing the kernel did not write is removed");

        assert_eq!(
            read_rel(&store, &rel),
            edited,
            "the words a person wrote survive a deletion made elsewhere"
        );
        assert_eq!(alert_kinds(&store), vec![crate::alerts::VAULT_DELETED_NOTE_EDITED]);
        // Stable: running both again neither deletes the file nor
        // re-counts it.
        assert_eq!(store.reconcile().expect("again").imports, 0);
        assert_eq!(store.project().expect("again").removed, 0);
        assert!(store.vault().join(&rel).exists());
    }

    #[test]
    fn the_kept_note_notice_is_withdrawn_once_the_person_deletes_the_file() {
        // The notice had no exit: it was raised when the edit was first
        // noticed and stood for ever, so somebody who read it and then
        // chose — deleted the file, having decided the deletion was right
        // — was left with a standing notice about a decision they had
        // already made.
        let (_dir, store, rel) = captured("worth keeping");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        tombstone_from_elsewhere(&store, &object_id);
        let edited = read_rel(&store, &rel).replace("worth keeping", "still worth keeping");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit");
        store.reconcile().expect("reconcile");
        assert_eq!(alert_kinds(&store), vec![crate::alerts::VAULT_DELETED_NOTE_EDITED]);

        std::fs::remove_file(store.vault().join(&rel)).expect("the person decides");
        store.reconcile().expect("reconcile");
        assert!(
            alert_kinds(&store).is_empty(),
            "a notice about a decision already taken is one nobody can act on"
        );
    }

    #[test]
    fn the_kept_note_notice_is_withdrawn_once_the_words_come_back_as_something_new() {
        // The other resolution, and the second-order case: the person
        // keeps the words by making them a note of their own. The old id
        // is gone from the vault, so the notice about the old object is
        // finished — and this is *observed* on every pass rather than
        // recorded once, so it cannot go stale.
        let (_dir, store, rel) = captured("worth keeping");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        tombstone_from_elsewhere(&store, &object_id);
        let edited = read_rel(&store, &rel).replace("worth keeping", "still worth keeping");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit");
        store.reconcile().expect("reconcile");
        assert_eq!(alert_kinds(&store), vec![crate::alerts::VAULT_DELETED_NOTE_EDITED]);

        // Brought back as a plain note: no front matter, no old identity.
        std::fs::write(store.vault().join(&rel), "still worth keeping\n").expect("adopt");
        store.reconcile().expect("reconcile");
        assert!(alert_kinds(&store).is_empty());
        assert!(
            store.vault().join(&rel).exists(),
            "and nothing on disk was touched to achieve it"
        );
    }

    #[test]
    fn the_kept_note_notice_stands_while_the_file_is_still_there_unclaimed() {
        // The case that must NOT clear: the person has not decided yet.
        // A sweep that cleared everything it did not recognise would make
        // the notice vanish on the very next reconcile.
        let (_dir, store, rel) = captured("worth keeping");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        tombstone_from_elsewhere(&store, &object_id);
        let edited = read_rel(&store, &rel).replace("worth keeping", "still worth keeping");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit");
        store.reconcile().expect("reconcile");
        for _ in 0..3 {
            store.reconcile().expect("reconcile again");
            assert_eq!(
                alert_kinds(&store),
                vec![crate::alerts::VAULT_DELETED_NOTE_EDITED],
                "the decision is still waiting to be made"
            );
        }
    }

    #[test]
    fn an_edit_ingested_before_the_tombstone_arrived_still_keeps_the_file() {
        // The other ordering, and the one that used to lose the words:
        // ingesting the edit records the *edited* bytes against the
        // projection, so a tombstone landing afterwards found a hash that
        // matched and deleted the file one tick later.
        let (_dir, store, rel) = captured("written here first");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");

        let edited = read_rel(&store, &rel).replace("written here first", "and edited here");
        std::fs::write(store.vault().join(&rel), &edited).expect("edit");
        assert_eq!(store.reconcile().expect("reconcile").edits, 1);

        tombstone_from_elsewhere(&store, &object_id);
        assert_eq!(store.project().expect("project").removed, 0);
        assert_eq!(read_rel(&store, &rel), edited, "the edit is not deleted under the person");
    }

    #[test]
    fn a_tombstone_from_elsewhere_still_withdraws_a_file_the_kernel_wrote() {
        // The rule is "never delete what the kernel did not write", not
        // "never delete": an untouched projection goes, as it must.
        let (_dir, store, rel) = captured("nobody touched this");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        tombstone_from_elsewhere(&store, &object_id);

        assert_eq!(store.project().expect("project").removed, 1);
        assert!(!store.vault().join(&rel).exists());
        assert!(alert_kinds(&store).is_empty(), "an ordinary deletion is not a notice");
    }

    #[test]
    fn a_projection_whose_own_bytes_cannot_be_read_is_held_back_not_overwritten() {
        // A text capture's body *is* its preserved bytes. If they cannot
        // be read and are not in transit, there is no honest rendering —
        // and the dishonest one, an embed link, would become the note's
        // body and be ingested as an edit on the next reconcile.
        let (_dir, store, rel) = captured("the only copy of these words");
        let blob_rel: String = store
            .conn()
            .query_row("SELECT rel_path FROM blobs", [], |row| row.get(0))
            .expect("the blob");
        let before = read_rel(&store, &rel);
        std::fs::remove_file(store.vault().join(&blob_rel)).expect("lose the bytes");

        let summary = store.project().expect("project");
        assert_eq!(summary.held_back, 1);
        assert_eq!(summary.written, 0);
        assert_eq!(read_rel(&store, &rel), before, "the note is left exactly as it was");
        assert!(!read_rel(&store, &rel).contains("![["), "and never becomes a link");

        // And it is **said**. Holding back silently was the whole of the
        // fault: `held_back` is a number on a summary nothing displays, so
        // a note whose recording had gone missing simply stopped being
        // maintained with nothing anywhere naming it.
        let alerts = store.integrity_alerts().expect("alerts");
        assert_eq!(alerts.len(), 1);
        assert_eq!(alerts[0].kind, crate::alerts::VAULT_CONTENT_UNREADABLE);
        assert!(!alerts[0].damage, "the note is intact; something beside it is not");
        assert!(alerts[0].detail.contains(&rel), "a person has to be told which note");

        // The second-order half: it has to stop being reported when the
        // bytes come back, without anyone dismissing it.
        std::fs::write(store.vault().join(&blob_rel), b"the only copy of these words")
            .expect("restore the bytes");
        assert_eq!(store.project().expect("project").held_back, 0);
        assert!(
            store.integrity_alerts().expect("alerts").is_empty(),
            "a condition that clears stops being reported"
        );
    }

    #[test]
    fn content_still_crossing_from_another_device_projects_as_a_link() {
        // The case the placeholder exists for, kept honest: bytes that
        // have not arrived yet are the ordinary state of a note whose
        // operation got here first, and one missing transfer must not
        // hide a vault.
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let bytes = crate::operations::encode(&crate::operations::Envelope {
            version: crate::operations::ENVELOPE_VERSION,
            op_id: "elsewhere-1".to_string(),
            device_id: "device-elsewhere".to_string(),
            op_seq: 1,
            kind: crate::operations::OBJECT_CREATED.to_string(),
            target_id: "note-1".to_string(),
            created_ms: 1_700_000_000_000,
            payload: serde_json::json!({
                "object": {
                    "object_id": "note-1",
                    "type_id": crate::capture::SOURCE_TYPE,
                    "type_schema": 1,
                    "created_ms": 1_700_000_000_000i64,
                },
                "revision": {
                    "revision_id": "note-rev-1",
                    "payload": serde_json::json!({
                        "media": "txt",
                        "blob_id": "blob-1",
                        "rel_path": "Captures/capture-elsewhere.txt",
                        "sha256": ids::sha256_hex(b"not here yet"),
                        "bytes": 12,
                    })
                    .to_string(),
                    "origin": "capture",
                    "created_ms": 1_700_000_000_000i64,
                },
                "blob": {
                    "blob_id": "blob-1",
                    "rel_path": "Captures/capture-elsewhere.txt",
                    "sha256": ids::sha256_hex(b"not here yet"),
                    "bytes": 12,
                },
            }),
        });
        store
            .apply_operation("test", &bytes, crate::limits::Limits::DEFAULT)
            .expect("apply");

        assert_eq!(store.project().expect("project").written, 1);
        let rel: String = store
            .conn()
            .query_row("SELECT rel_path FROM projections", [], |row| row.get(0))
            .expect("projection");
        assert!(read_rel(&store, &rel).contains("![[capture-elsewhere.txt]]"));
    }

    #[test]
    fn an_unreadable_projection_keeps_its_row_rather_than_being_forgotten() {
        // Unreadable is not absent. A lock, a permission, a disconnected
        // drive says nothing about what the file holds — and dropping the
        // projection row would turn a transient failure into a note the
        // kernel no longer claims.
        let (_dir, store, rel) = captured("behind a lock");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let path = store.vault().join(&rel);
        std::fs::remove_file(&path).expect("remove");
        // A directory where the file was: unreadable on every platform,
        // and not `NotFound` on any of them.
        std::fs::create_dir(&path).expect("obstruct");
        tombstone_from_elsewhere(&store, &object_id);

        assert_eq!(store.project().expect("project").removed, 0);
        let rows: i64 = store
            .conn()
            .query_row(
                "SELECT COUNT(*) FROM projections WHERE object_id = ?1",
                [&object_id],
                |row| row.get(0),
            )
            .expect("count");
        assert_eq!(rows, 1, "the projection is still this kernel's to answer for");

        // Genuinely absent is the other answer, and it is a clean one.
        std::fs::remove_dir(&path).expect("clear");
        store.project().expect("project");
        let rows: i64 = store
            .conn()
            .query_row(
                "SELECT COUNT(*) FROM projections WHERE object_id = ?1",
                [&object_id],
                |row| row.get(0),
            )
            .expect("count");
        assert_eq!(rows, 0, "a file that is simply gone needs no withdrawing");
    }

    #[test]
    fn a_settled_projection_is_not_rewritten_but_never_goes_stale_either() {
        // Projection used to write the whole vault back on every pass to
        // say that nothing had happened: one `INSERT OR REPLACE` per note
        // per project. Skipping that is only safe if the skip asks about
        // *everything* the row holds — the path and the revision as well
        // as the hash — and this is the second-order case where it does
        // not: the file is byte-identical, so the hash matches, and the
        // path has changed underneath it.
        let (_dir, store, rel) = captured("settled");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let row = |store: &Store| -> (String, String) {
            store
                .conn()
                .query_row(
                    "SELECT rel_path, revision_id FROM projections WHERE object_id = ?1",
                    [&object_id],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .expect("row")
        };
        let before = row(&store);
        assert_eq!(store.project().expect("again").written, 0, "a settled vault writes nothing");
        assert_eq!(row(&store), before, "and the row it did not write is still right");

        let moved = "Renamed/settled.md";
        std::fs::create_dir_all(store.vault().join("Renamed")).expect("mkdir");
        std::fs::rename(store.vault().join(&rel), store.vault().join(moved)).expect("rename");
        assert_eq!(store.reconcile().expect("reconcile").renames, 1);

        store.project().expect("project");
        assert_eq!(row(&store).0, moved, "the row followed the file rather than being skipped");
        assert!(!store.vault().join(&rel).exists(), "and nothing was rebuilt at the old path");
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn a_committed_capture_projects_with_identity_and_body() {
        let (_dir, store, rel) = captured("Remember the milk");
        let text = read_rel(&store, &rel);
        assert!(text.starts_with("---\n"));
        assert!(text.contains("gilune-type: org.memory.source\n"));
        assert!(text.contains("gilune-blob: "));
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
    fn an_edit_changes_the_words_and_keeps_the_documents_other_facts() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = crate::store::Store::open(dir.path()).expect("open");
        let receipt = store.capture_bytes("wav", &[7u8; 64]).expect("capture");
        store.project().expect("project");
        let rel = {
            let head: String = store
                .conn()
                .query_row(
                    "SELECT rel_path FROM projections WHERE object_id = ?1",
                    [&receipt.source_id],
                    |row| row.get(0),
                )
                .expect("projection");
            head
        };
        let path = store.vault().join(&rel);
        let edited = std::fs::read_to_string(&path).expect("read") + "typed under the recording\n";
        std::fs::write(&path, &edited).expect("edit");

        assert_eq!(store.reconcile().expect("reconcile").edits, 1);
        let payload: serde_json::Value = serde_json::from_str(
            &crate::revisions::head_payload(store.conn(), &receipt.source_id)
                .expect("head")
                .expect("present"),
        )
        .expect("json");
        assert!(
            payload["text"].as_str().unwrap_or("").contains("typed under the recording"),
            "the edit's words are the document's text"
        );
        assert_eq!(
            payload["rel_path"].as_str(),
            Some(receipt.rel_path.as_str()),
            "editing a voice note must not strip its link to its own audio"
        );
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
    fn an_attached_capture_is_preserved_but_never_becomes_a_note() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let mut stage = store.begin_attached_capture("wav").expect("begin");
        stage.write_chunk(&[5u8; 32]).expect("chunk");
        let receipt = stage.finish(&store).expect("finish");
        store.capture_bytes("txt", b"an ordinary thought").expect("capture");

        let summary = store.project().expect("project");
        assert_eq!(summary.written, 1, "only the ordinary capture projects");
        assert_eq!(summary.held_back, 0, "an attachment is absent, not pending");
        assert_eq!(store.status().expect("status").projections, 1);
        assert!(
            !dir.path().join(receipt.rel_path.replace(".wav", ".md")).exists(),
            "an attachment has no note of its own"
        );
        // Preserved all the same: the bytes are in the vault and verify.
        assert_eq!(
            std::fs::read(dir.path().join(&receipt.rel_path)).expect("blob"),
            vec![5u8; 32]
        );
        assert!(store.verify().expect("verify").is_empty());
        assert_eq!(store.reconcile().expect("reconcile").tombstone_proposals, 0);
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
        assert_eq!(
            kinds,
            vec![
                crate::operations::OBJECT_CREATED.to_string(),
                crate::operations::OBJECT_REVISED.to_string()
            ]
        );
    }
}
