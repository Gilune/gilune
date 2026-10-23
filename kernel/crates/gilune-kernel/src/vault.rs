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

use crate::changes::{Change, ChangeCause, ChangeKind};
use crate::crashpoints::crashpoint;
use crate::revisions::{add_revision, Announce};
use crate::store::Store;
use crate::{changes, fs, ids, probe, KernelError, Result};
mod header;
pub mod adopt;
#[cfg(test)]
mod adopt_tests;
pub use adopt::{ATTACHMENT_TYPE, LOCAL_MARKER, LOCAL_VALUE, NOTE_TYPE};
#[cfg(test)]
mod property_tests;
use header::Header;
use std::path::Path;

/// Processor output owns its domain payload, not the projection envelope.
/// Preserve the current envelope inside the revision transaction.
pub(crate) fn preserve_projection_metadata(current: &str, proposed: &str) -> Result<String> {
    let current: serde_json::Value = serde_json::from_str(current)?;
    let proposed_bytes = proposed;
    let mut proposed: serde_json::Value = serde_json::from_str(proposed)?;
    let keys = [header::PAYLOAD_KEY, crate::pages::PAYLOAD_KEY];
    if keys.iter().all(|key| current.get(*key).is_none() && proposed.get(*key).is_none()) {
        return Ok(proposed_bytes.into());
    }
    for key in keys {
        if let Some(envelope) = current.get(key) {
            let Some(object) = proposed.as_object_mut() else {
                return Err(KernelError::DocumentInvalid {
                    detail: "a projected document revision must preserve its metadata envelope".into(),
                });
            };
            object.insert(key.into(), envelope.clone());
        } else if let Some(object) = proposed.as_object_mut() {
            object.remove(key);
        }
    }
    Ok(proposed.to_string())
}

/// Build the editable page payload without accepting any caller-supplied identity.
pub(crate) fn new_page_payload(source: &str) -> Result<serde_json::Value> {
    let invalid = |detail: String| KernelError::DocumentInvalid { detail };
    let snapshot = gilune_document::TextSnapshot::new(source.into()).map_err(|e| invalid(e.to_string()))?;
    if snapshot.has_reserved_metadata() { return Err(invalid("creation source contains kernel metadata".into())); }
    let with_header = if snapshot.header().is_some() {
        source.to_string()
    } else {
        let (bom, body) = source.strip_prefix('\u{feff}').map_or(("", source), |body| ("\u{feff}", body));
        let newline = if source.contains("\r\n") { "\r\n" } else { "\n" };
        format!("{bom}---{newline}---{newline}{body}")
    };
    let header = Header::parse(&with_header).ok_or_else(|| invalid("unsupported page front-matter structure".into()))?;
    Ok(serde_json::json!({"text":header.snapshot.body(), header::PAYLOAD_KEY:header.payload()}))
}

/// Exact editable source retained by a page revision, without reading its file.
pub(crate) fn preserved_page_source(payload: &serde_json::Value) -> Result<String> {
    let invalid = || KernelError::DocumentInvalid { detail: "missing or unsupported preserved page source".into() };
    let header = Header::from_payload(&payload[header::PAYLOAD_KEY]).ok_or_else(invalid)?;
    let body = payload["text"].as_str().ok_or_else(invalid)?;
    Ok(format!("{}{body}", header.raw()))
}

pub(crate) fn with_current_projection_metadata(source: &str, current: &str) -> Result<String> {
    let original = Header::parse(source).ok_or_else(|| KernelError::DocumentInvalid {
        detail: "unsupported original metadata".into(),
    })?;
    let header = original
        .render(current)
        .ok_or_else(|| KernelError::DocumentInvalid {
            detail: "metadata cannot be preserved for undo".into(),
        })?;
    Ok(format!("{header}{}", original.snapshot.body()))
}

/// Origin recorded for reconciled external document edits.
pub(crate) const EXTERNAL_EDIT_ORIGIN: &str = "external-edit";
/// A revision that changes where a note is and nothing else (ADR 0007,
/// 2026-10-23): the head document with `path` set to the file's new place.
pub(crate) const RENAME_ORIGIN: &str = "rename";

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
    /// Markdown files adopted as notes this pass (ADR 0007, 2026-10-22).
    pub adopted: i64,
    /// Files adopted as attachments this pass.
    pub attachments: i64,
}

struct Head {
    object_id: String,
    type_id: String,
    type_schema: i64,
    created_ms: i64,
    revision_id: String,
    payload: String,
    /// Where this revision came from, as `revisions.origin` records it.
    /// Read for the change feed's `cause` and for nothing else.
    origin: String,
    conflicted: bool,
}

/// What the projection last recorded about a file. Read as one row
/// because every decision the projection makes needs several of its
/// columns and asking twice is how two of them come to disagree.
struct Recorded {
    rel_path: String,
    revision_id: String,
    sha: String,
    stat: Option<Stat>,
    /// The moment the record was written, in nanoseconds since the Unix
    /// epoch. Zero on a row written before there were stat facts.
    recorded_ns: i64,
}

/// A file's size and modification time, as one stat call reports them.
/// Nanoseconds on every platform (Windows file times are 100 ns ticks,
/// Linux is finer), so the unit is one fact and comparisons are exact.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Stat {
    size: i64,
    mtime_ns: i64,
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
        // The folders a vault starts with because the vault itself uses
        // them (`vault.folders`, owner 2026-10-19): captures land in
        // Captures, and Notes is the home for everything written by hand.
        // Shells create each once per vault and never put back one the
        // user deleted; the kernel only declares them.
        contributions: [("notes", "Notes"), ("captures", "Captures")]
            .into_iter()
            .map(|(id, folder)| crate::plugins::Contribution {
                slot: "vault.folders".to_string(),
                id: format!("org.memory.vault/{id}"),
                label: folder.to_string(),
                config: format!("{{\"folder\":\"{folder}\"}}"),
            })
            .collect(),
    }
}

impl Store {
    /// Prepare the ordinary projection for a reviewed edit, without committing.
    /// The shared journal supplies the revision identity; domain payload fields
    /// and original capture blobs survive exactly as with external editing.
    pub(crate) fn refactoring_projection(
        &self,
        object_id: &str,
        revision_id: &str,
        source: &str,
        origin: &str,
    ) -> Result<(String, String)> {
        let header = Header::parse(source).ok_or_else(|| KernelError::DocumentInvalid {
            detail: "document metadata has no safe projection representation".into(),
        })?;
        let mut payload: serde_json::Value = serde_json::from_str(
            &crate::revisions::head_payload(self.conn(), object_id)?
                .ok_or_else(|| KernelError::DocumentChanged)?,
        )?;
        payload["text"] = header.snapshot.body().into();
        payload[header::PAYLOAD_KEY] = header.payload();
        let (type_id, type_schema, created_ms) = self.conn().query_row(
            "SELECT type_id, type_schema, created_ms FROM objects WHERE object_id = ?1 AND deleted = 0",
            [object_id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))?;
        let head = Head {
            object_id: object_id.into(),
            type_id,
            type_schema,
            created_ms,
            revision_id: revision_id.into(),
            payload: payload.to_string(),
            origin: origin.into(),
            conflicted: false,
        };
        let rendered = self
            .render(&head)?
            .ok_or_else(|| KernelError::DocumentInvalid {
                detail: "document metadata cannot be projected without loss".into(),
            })?;
        Ok((head.payload, rendered))
    }

    /// Called inside the journal's write transaction after publication. A retry
    /// is resolved by the journal before this method, never by adding a new ID.
    pub(crate) fn record_refactoring_revision(
        &self,
        object_id: &str,
        revision_id: &str,
        payload: &str,
        origin: &str,
        rel_path: &str,
        disk: &str,
    ) -> Result<()> {
        add_revision(
            self.conn(),
            object_id,
            revision_id,
            payload,
            origin,
            ids::now_ms(),
            Announce::Event,
        )?;
        let stat = stat_of(&self.vault().join(rel_path));
        self.conn().execute(
            "UPDATE projections SET revision_id = ?2, content_sha256 = ?3,
            size = ?4, mtime_ns = ?5, recorded_ns = ?6 WHERE object_id = ?1",
            rusqlite::params![
                object_id,
                revision_id,
                ids::sha256_hex(disk.as_bytes()),
                stat.map(|s| s.size).unwrap_or(-1),
                stat.map(|s| s.mtime_ns).unwrap_or(0),
                if stat.is_some() { now_ns() } else { 0 }
            ],
        )?;
        changes::record(
            self.conn(),
            Change {
                kind: ChangeKind::Modified,
                rel_path,
                previous_rel_path: None,
                object_id: Some(object_id),
                revision_id: Some(revision_id),
                cause: ChangeCause::LocalEdit,
            },
        )?;
        Ok(())
    }
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
        let occupied: std::collections::BTreeSet<String> = self
            .integrity_alerts_of(crate::alerts::VAULT_PROJECTION_PATH_OCCUPIED)?
            .into_iter().map(|a| a.subject).collect();
        // **Local before arrived, for a path both want.** An object this
        // device made projects before one that arrived from elsewhere and
        // has no file yet, so when two notes want one path it is always
        // the arrival that goes beside (ADR 0007, 2026-10-22) — never the
        // person's own note held back because the other id sorted first.
        // The projection row is read once here and carried into the loop.
        let ours = self.device_id()?;
        let mut ordered: Vec<(Head, Option<Recorded>)> = Vec::new();
        let mut arrived: Vec<(Head, Option<Recorded>)> = Vec::new();
        for head in self.live_heads()? {
            if is_attached(&head.payload) {
                // An attached capture belongs to a note that already
                // exists (ADR 0013), so it has no note of its own and
                // nothing to hold back: it is absent from the projection
                // rather than pending in it, and no counter reports it.
                continue;
            }
            let row = self.recorded_for_object(&head.object_id)?;
            let from_elsewhere = row.is_none()
                && crate::operations::origins_of(self.conn(), &head.object_id)?
                    .object
                    .is_some_and(|origin| origin != ours);
            if from_elsewhere {
                arrived.push((head, row));
            } else {
                ordered.push((head, row));
            }
        }
        ordered.extend(arrived);
        for (mut head, mut row) in ordered {
            let mut rel_path = match self.projection_path(&head) {
                Err(KernelError::DocumentInvalid { detail }) => {
                    summary.held_back += 1;
                    crate::alerts::raise(self.conn(), crate::alerts::VAULT_CONTENT_UNREADABLE,
                        &head.object_id, &format!("page projection held back: {detail}"))?;
                    continue;
                }
                result => result?,
            };
            // **Where the note is and where its head says it is** (ADR
            // 0007, 2026-10-23). When they differ and the head is not
            // conflicted, the record decides which way to settle it. A
            // record still on the head's own revision means the file was
            // moved here after that head was projected and nobody was
            // told — a rename made before renames travelled — so it is
            // written now as the revision it should have been, once. A
            // record on an older revision means the head is new and names
            // the new place: a rename that arrived, and the file follows
            // it. A conflicted head — a rename or edit made here at the
            // same time is a second head — leaves this device's name
            // standing until the conflict is resolved.
            if let Some(wanted) = Self::path_named_by(&head.payload) {
                if let Some(record) = row.take() {
                    if wanted == record.rel_path || head.conflicted {
                        row = Some(record);
                    } else if record.revision_id == head.revision_id {
                        self.rename_as_revision(&head.object_id, &record.rel_path)?;
                        if let Some(fresh) = self.live_head(&head.object_id)? {
                            head = fresh;
                        }
                        row = Some(record);
                    } else {
                        match self.follow_rename(&head, &record, &wanted)? {
                            Some(moved) => {
                                rel_path = moved.clone();
                                row = Some(Recorded { rel_path: moved, ..record });
                            }
                            None => row = Some(record),
                        }
                    }
                }
            }
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
            let recorded = row.as_ref().map(|r| r.sha.clone());
            // The disk state is what detects an external edit, so this
            // never guesses — but it no longer *reads* to learn what it
            // can stat (ADR 0020 § 2.5). A file whose size and mtime
            // still match the record is the bytes the record names.
            let disk_sha = match &row {
                Some(record) if self.file_matches_record(&head.object_id, &target, record)? => {
                    Some(record.sha.clone())
                }
                _ => {
                    probe::file_read();
                    std::fs::read(&target).ok().as_deref().map(ids::sha256_hex)
                }
            };

            match (&disk_sha, &recorded) {
                (Some(d), _) if *d == canonical_sha => {
                    // Nothing about the projection has changed, so there
                    // is nothing to record. This used to write the same
                    // row back on every pass — one INSERT OR REPLACE per
                    // note per project, which for the steady state of a
                    // vault is the whole vault rewritten to say that
                    // nothing happened.
                    let unchanged = row.as_ref().is_some_and(|record| {
                        record.sha == canonical_sha
                            && record.rel_path == rel_path
                            && record.revision_id == head.revision_id
                    });
                    if !unchanged {
                        // The bytes on disk are already right, so the
                        // vault's files did not change — unless this
                        // kernel had never claimed the file at all, in
                        // which case the vault has gained one.
                        let announce = row.is_none().then_some(ChangeKind::Created);
                        self.record_projection(&head, &rel_path, &canonical_sha, announce)?;
                    }
                    if occupied.contains(&head.object_id) {
                        crate::alerts::clear(self.conn(), crate::alerts::VAULT_PROJECTION_PATH_OCCUPIED, &head.object_id)?;
                    }
                    summary.unchanged += 1;
                }
                (Some(_), None) => {
                    // A path requested by a new object is not authority over
                    // an unrelated existing file, including one received by
                    // sync. A note that arrived from another device is
                    // written beside the occupant under a name that says
                    // where it came from (ADR 0007, 2026-10-22): held in
                    // history with a notice and no file was a note nobody
                    // could see. The name is the notice.
                    let beside = self.conflict_path(&head, &rel_path)?;
                    match beside {
                        Some(beside)
                            if write_new_projection(
                                &self.vault().join(&beside),
                                canonical.as_bytes(),
                            )? =>
                        {
                            crashpoint("project.after_write");
                            self.record_projection(
                                &head,
                                &beside,
                                &canonical_sha,
                                Some(ChangeKind::Created),
                            )?;
                            summary.written += 1;
                        }
                        _ => {
                            summary.held_back += 1;
                            self.report_occupied_projection(&head.object_id, &rel_path)?;
                        }
                    }
                }
                (Some(d), Some(r)) if d != r => {
                    // Externally changed since we last wrote: reconcile owns it.
                    summary.held_back += 1;
                }
                _ => {
                    // `Created` says the vault did not have this file.
                    // Both ways that happens count: no record at all, and
                    // a record whose file is missing — a tombstone
                    // proposal that was rejected puts a file back, and
                    // calling that `Modified` would describe a change to
                    // something that was not there.
                    let kind = match (row.is_none(), disk_sha.is_none()) {
                        (false, false) => ChangeKind::Modified,
                        _ => ChangeKind::Created,
                    };
                    if row.is_none() {
                        if !write_new_projection(&target, canonical.as_bytes())? {
                            summary.held_back += 1;
                            self.report_occupied_projection(&head.object_id, &rel_path)?;
                            continue;
                        }
                    } else {
                        write_atomic(&target, canonical.as_bytes())?;
                    }
                    crashpoint("project.after_write");
                    self.record_projection(&head, &rel_path, &canonical_sha, Some(kind))?;
                    if occupied.contains(&head.object_id) {
                        crate::alerts::clear(self.conn(), crate::alerts::VAULT_PROJECTION_PATH_OCCUPIED, &head.object_id)?;
                    }
                    summary.written += 1;
                }
            }
        }
        summary.removed = self.withdraw_tombstoned()?;
        summary.removed += self.withdraw_tombstoned_attachments()?;
        // The index is maintained in the pass that recorded the change
        // (ADR 0020 § 2.3), so a consumer never has to ask for it and
        // never has to rescan to catch up.
        self.maintain_index();
        Ok(summary)
    }

    fn report_occupied_projection(&self, object_id: &str, path: &str) -> Result<()> {
        crate::alerts::raise(self.conn(), crate::alerts::VAULT_PROJECTION_PATH_OCCUPIED, object_id,
            &format!("the page is saved in revision history, but {path} is occupied; the existing file has been preserved"))?;
        Ok(())
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
            let rows =
                stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)))?;
            rows.collect::<std::result::Result<_, _>>()?
        };
        let mut removed = 0;
        for (object_id, rel_path, recorded_sha, projected_revision) in doomed {
            let target = self.vault().join(&rel_path);
            probe::file_read();
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
            let cause = self.deletion_cause(&object_id)?;
            let announce = !self.already_removed(&rel_path)?;
            self.write_txn(|| {
                self.conn()
                    .execute("DELETE FROM projections WHERE object_id = ?1", [&object_id])?;
                if announce {
                    changes::record(
                        self.conn(),
                        Change {
                            kind: ChangeKind::Removed,
                            rel_path: &rel_path,
                            previous_rel_path: None,
                            object_id: Some(&object_id),
                            revision_id: Some(&projected_revision),
                            cause,
                        },
                    )?;
                }
                Ok(())
            })?;
        }
        Ok(removed)
    }

    /// Whether a deletion this device is carrying out was decided
    /// elsewhere. Exact: a tombstone recorded by another device is a
    /// tombstone that arrived, and a tombstone this device recorded is
    /// one a person took here.
    fn deletion_cause(&self, object_id: &str) -> Result<ChangeCause> {
        let elsewhere: i64 = self.conn().query_row(
            "SELECT EXISTS(SELECT 1 FROM operations
                            WHERE target_id = ?1 AND kind = ?2 AND device_id <> ?3)",
            rusqlite::params![object_id, crate::operations::TOMBSTONE, self.device_id()?],
            |row| row.get(0),
        )?;
        Ok(if elsewhere == 1 { ChangeCause::Sync } else { ChangeCause::Reconcile })
    }

    /// Ingest external changes to the vault. Never destructive: kernel
    /// history only grows.
    pub fn reconcile(&self) -> Result<ReconcileSummary> {
        let _profile_total = crate::update_profile::span("reconcile_total", "");
        let profile_before = crate::update_profile::span("reconcile_before_index", "");
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

        let files = adopt::vault_files(self.vault())?;
        for found in &files.markdown {
            seen_paths.push(found.rel_path.clone());
            self.reconcile_file(&found.rel_path, found.local, &mut summary, &mut kept, true)?;
        }
        // Everything that is not a note (ADR 0007, amendment 2026-10-22):
        // adopted in place, revised when its bytes change, proposed for
        // deletion when it goes.
        let (adopted_files, revised_files) = self.reconcile_attachments(&files.other)?;
        summary.attachments += adopted_files;
        summary.edits += revised_files;
        summary.tombstone_proposals += self.propose_missing_attachments()?;

        // Tracked projections whose file is gone become tombstone
        // proposals; the file is not resurrected while a proposal is open.
        let missing: Vec<(String, String)> = {
            let mut stmt = self.conn().prepare("SELECT object_id, rel_path FROM projections")?;
            let rows =
                stmt.query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)))?;
            let mut missing = Vec::new();
            for row in rows {
                let (object_id, rel_path) = row?;
                if !seen_paths.contains(&rel_path) && !self.vault().join(&rel_path).exists() {
                    missing.push((object_id, rel_path));
                }
            }
            missing
        };
        for (object_id, rel_path) in missing {
            summary.tombstone_proposals += self.propose_tombstone(&object_id, &rel_path)?;
        }

        // Import candidates whose files have gone. No proposal: nothing
        // here was ever kernel history, so there is nothing for a person
        // to decide — but a reader told about the file has to be told it
        // left, or it answers for ever about words that are not there.
        for rel_path in self.recorded_imports()? {
            if !self.vault().join(&rel_path).exists() {
                self.forget_missing_import(&rel_path)?;
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
        for alert in self.integrity_alerts_of(crate::alerts::VAULT_DELETED_NOTE_EDITED)? {
            if !kept.contains(&alert.subject) {
                crate::alerts::clear(
                    self.conn(),
                    crate::alerts::VAULT_DELETED_NOTE_EDITED,
                    &alert.subject,
                )?;
            }
        }
        drop(profile_before);
        self.maintain_index();
        Ok(summary)
    }

    /// Reconcile **one** file, reaching the same decisions the full pass
    /// reaches about it, in time that does not grow with the vault.
    ///
    /// It exists so that a writer's own save reaches the change feed
    /// before anything refreshes (ADR 0020 § 2.2): a caller that has
    /// just written a file calls this and the feed already carries it,
    /// with no rescan and no indexer of the caller's own. Idempotent, so
    /// calling it and then running the full pass records the change once
    /// — the full pass finds the record already matching what is there.
    ///
    /// A path that is not a `.md` file inside this vault is refused:
    /// only Markdown files are projected, and a path leading out of the
    /// vault is never a path this can be asked about.
    pub fn reconcile_path(&self, rel_path: &str) -> Result<ReconcileSummary> {
        let rel = self.projected_path(rel_path)?;
        let mut summary = ReconcileSummary::default();
        let mut kept: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();
        let mut seen: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();

        if self.vault().join(&rel).is_file() {
            let local = adopt::folder_is_local(self.vault(), &rel);
            if let Some(object_id) = self.reconcile_file(&rel, local, &mut summary, &mut kept, false)? {
                seen.insert(object_id);
            }
        } else if let Some(record) = self.recorded_at_path(&rel)? {
            // The file the caller named is gone. The full pass answers
            // that with a tombstone proposal, and so does this.
            seen.insert(record.0.clone());
            summary.tombstone_proposals += self.propose_tombstone(&record.0, &rel)?;
        } else {
            // An import candidate that has gone leaves the same way here
            // as in the full pass, so a one-file call reaches the same
            // decision about the one file it was asked about.
            self.forget_missing_import(&rel)?;
        }

        // Only notices about the object this path is *about* may be
        // withdrawn here. The full pass observes the whole vault and can
        // safely clear anything it did not see; a one-file pass has seen
        // one file, and clearing on that evidence would withdraw a
        // decision nobody had taken.
        for object_id in seen.difference(&kept) {
            crate::alerts::clear(self.conn(), crate::alerts::VAULT_DELETED_NOTE_EDITED, object_id)?;
        }
        // The whole point of the one-file pass: the note somebody just
        // saved is in the index before anything refreshes.
        self.maintain_index();
        Ok(summary)
    }

    /// One file's reconciliation. Returns the object the file is about,
    /// where it names one, so a single-file pass knows what it has seen.
    fn reconcile_file(
        &self,
        rel_path: &str,
        local: bool,
        summary: &mut ReconcileSummary,
        kept: &mut std::collections::BTreeSet<String>,
        trust_stat_cache: bool,
    ) -> Result<Option<String>> {
        let tracked = self.recorded_at_path(rel_path)?;
        // Background scans use the stat cache. An explicit one-file save
        // notification must read that file: rapid same-size edits can share
        // an mtime on coarse clocks, and external tools can preserve it.
        // A content comparison still suppresses no-op revisions and events.
        if let Some((object_id, record)) = &tracked {
            if trust_stat_cache && self.file_matches_record(object_id, &self.vault().join(rel_path), record)? {
                return Ok(Some(object_id.clone()));
            }
        }
        // The same rule for a file the kernel does not claim. It has to
        // be asked *before* the read, because an import has no projection
        // record to shortcut on and the front matter is what decides it
        // is one — so without this every import in the vault was read on
        // every pass, which is exactly the cost § 2.5 removed for the
        // files the kernel writes.
        //
        // Only a candidate kept by its *folder* settles on the stat: one
        // kept by its own key has to be read to learn the key is still
        // there, and one that is neither is read so it can be adopted
        // (ADR 0007, 2026-10-22).
        if trust_stat_cache && tracked.is_none() && local {
            if let Some(object_id) = self.import_matches_record(rel_path)? {
                // A kept note is still about its object while its file is
                // there, and the notice that says so is withdrawn by not
                // being renewed — so the record answers what the read
                // used to.
                if let Some(object_id) = object_id {
                    if self.is_deleted(&object_id)? {
                        kept.insert(object_id.clone());
                    }
                    return Ok(Some(object_id));
                }
                return Ok(None);
            }
        }
        probe::file_read();
        let text = std::fs::read_to_string(self.vault().join(rel_path))
            .map_err(|e| KernelError::Io { path: self.vault().join(rel_path), source: e })?;

        match parse_front_matter(&text) {
            FrontMatter::Absent | FrontMatter::Foreign | FrontMatter::Malformed if tracked.is_some() => {
                // Bytes equal to the record are not a broken projection:
                // they are an adopted file whose header has not landed
                // yet (`adopt_markdown` commits the rows first), and
                // `project` canonicalises it on its next pass.
                if let Some((object_id, record)) = &tracked {
                    if ids::sha256_hex(text.as_bytes()) == record.sha {
                        return Ok(Some(object_id.clone()));
                    }
                }
                // A file the kernel wrote lost or broke its identity:
                // never guess, never overwrite — a person decides.
                summary.quarantined +=
                    self.quarantine(rel_path, "projection front matter missing or malformed")?;
                Ok(tracked.map(|(object_id, _)| object_id))
            }
            FrontMatter::Malformed => {
                summary.quarantined += self.quarantine(rel_path, "unparseable front matter")?;
                Ok(None)
            }
            FrontMatter::Absent | FrontMatter::Foreign => {
                if local || adopt::header_says_local(&text) {
                    // Kept on this device by the person's own mark: a
                    // candidate for the index, never an object.
                    summary.imports += self.record_import(rel_path, &text, None)?;
                    return Ok(None);
                }
                match self.adopt_markdown(rel_path, &text, None, None) {
                    Ok(object_id) => {
                        summary.adopted += 1;
                        Ok(Some(object_id))
                    }
                    // A header the lossless envelope cannot carry — YAML
                    // anchors, merges, flow mappings — is kept as the
                    // person wrote it and offered as a candidate, exactly
                    // as before; failing the pass over one file would
                    // stop every other file being seen.
                    Err(KernelError::DocumentInvalid { .. }) => {
                        summary.imports += self.record_import(rel_path, &text, None)?;
                        Ok(None)
                    }
                    Err(e) => Err(e),
                }
            }
            FrontMatter::Parsed(header) => {
                let Some(object_id) =
                    header.snapshot.metadata_string("gilune-id").map(str::to_owned)
                else {
                    if tracked.is_none() && !header.snapshot.has_reserved_metadata() {
                        summary.imports += self.record_import(rel_path, &text, None)?;
                        return Ok(None);
                    }
                    summary.quarantined +=
                        self.quarantine(rel_path, "front matter has no gilune-id")?;
                    return Ok(None);
                };
                let Some(known) = self.recorded_for_object(&object_id)? else {
                    // A file carrying an id the kernel no longer claims.
                    // When that object was deleted elsewhere, this is the
                    // note being kept because it was edited here, and its
                    // notice is still true.
                    if self.is_deleted(&object_id)? {
                        kept.insert(object_id.clone());
                    } else if !self.object_exists(&object_id)?
                        && !(local || adopt::header_says_local(&text))
                    {
                        // An id nobody here holds: the crash window after
                        // adoption wrote the header, or a file copied from
                        // another device's vault. Either way it is that
                        // note, so it is adopted under its own id.
                        self.adopt_markdown(rel_path, &text, Some(&header), Some(&object_id))?;
                        summary.adopted += 1;
                        return Ok(Some(object_id));
                    }
                    summary.imports += self.record_import(rel_path, &text, Some(&object_id))?;
                    return Ok(Some(object_id));
                };
                // Local scope is settled before any edit below is taken
                // in, so an edit that adds the mark is a revision peers
                // never hear of (ADR 0007, 2026-10-22).
                if !self.is_deleted(&object_id)? {
                    self.settle_local_scope(&object_id, local || adopt::header_says_local(&text))?;
                }
                if known.rel_path != rel_path {
                    if self.vault().join(&known.rel_path).exists() {
                        summary.quarantined +=
                            self.quarantine(rel_path, "duplicate of a tracked projection")?;
                        return Ok(Some(object_id));
                    }
                    self.record_rename(&object_id, &known.rel_path, rel_path, &known.revision_id)?;
                    summary.renames += 1;
                }
                if ids::sha256_hex(text.as_bytes()) != known.sha {
                    // Bytes that are exactly what this device would render
                    // are not an edit: they are a header that landed after
                    // adoption's rows and before its record (the crash
                    // window), or `project`'s own canonical form. The
                    // record catches up and nobody is told anything.
                    // Asked only of a note whose words are in its own
                    // document: rendering one reads nothing, whereas a
                    // capture's body is its blob and rendering it would be
                    // a read the feed never named (ADR 0020 § 8.5).
                    let carries_text = |head: &Head| {
                        serde_json::from_str::<serde_json::Value>(&head.payload)
                            .ok()
                            .is_some_and(|v| v.get("text").is_some())
                    };
                    if let Some(head) = self.live_head(&object_id)?.filter(carries_text) {
                        if self.render(&head)?.as_deref() == Some(text.as_str()) {
                            self.record_projection(&head, rel_path, &ids::sha256_hex(text.as_bytes()), None)?;
                            return Ok(Some(object_id));
                        }
                    }
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
                        summary.imports +=
                            self.keep_edit_of_deleted(&object_id, rel_path, &text)?;
                    } else {
                        self.ingest_external_edit(&object_id, rel_path, &header, text.as_bytes())?;
                        summary.edits += 1;
                    }
                }
                Ok(Some(object_id))
            }
        }
    }

    /// A tracked projection whose file has gone becomes a proposal for a
    /// person to answer, and the feed says the file left the vault.
    fn propose_tombstone(&self, object_id: &str, rel_path: &str) -> Result<i64> {
        if self.has_open_tombstone(object_id)? {
            return Ok(0);
        }
        let announce = !self.already_removed(rel_path)?;
        self.write_txn(|| {
            self.conn().execute(
                "INSERT INTO proposals (proposal_id, object_id, kind, state, created_ms)
                 VALUES (?1, ?2, 'tombstone', 'open', ?3)",
                rusqlite::params![ids::new_id(), object_id, ids::now_ms()],
            )?;
            if announce {
                // The projection row stays — rejecting the proposal is
                // how a file comes back — but the vault does not have the
                // file, and a consumer that was never told would answer
                // for ever about words that are not there.
                changes::record(
                    self.conn(),
                    Change {
                        kind: ChangeKind::Removed,
                        rel_path,
                        previous_rel_path: None,
                        object_id: Some(object_id),
                        revision_id: None,
                        cause: ChangeCause::Reconcile,
                    },
                )?;
            }
            Ok(())
        })?;
        Ok(1)
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
        // A proposal is only ever opened for a file that is missing, so
        // the feed has normally said so already; this is the guard for
        // the ordering where it has not.
        let vacated = match self.recorded_for_object(&object_id)? {
            Some(record) if !self.already_removed(&record.rel_path)? => Some(record),
            _ => None,
        };
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
                let carried = crate::operations::tombstone_payload(conn, &object_id)?;
                conn.execute("UPDATE objects SET deleted = 1 WHERE object_id = ?1", [&object_id])?;
                conn.execute("DELETE FROM projections WHERE object_id = ?1", [&object_id])?;
                // A note kept on this device is deleted on this device:
                // peers keep what they had (ADR 0007, 2026-10-22).
                if self.is_local_object(&object_id)? {
                    conn.execute("DELETE FROM local_objects WHERE object_id = ?1", [&object_id])?;
                } else {
                    crate::operations::record(
                        conn,
                        crate::operations::TOMBSTONE,
                        &object_id,
                        carried,
                        ids::now_ms(),
                    )?;
                }
                if let Some(record) = &vacated {
                    changes::record(
                        conn,
                        Change {
                            kind: ChangeKind::Removed,
                            rel_path: &record.rel_path,
                            previous_rel_path: None,
                            object_id: Some(&object_id),
                            revision_id: Some(&record.revision_id),
                            cause: ChangeCause::Reconcile,
                        },
                    )?;
                }
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

    /// The head that renders for one object, by the one rule every reader
    /// takes, or `None` for an object with no live head.
    fn live_head(&self, object_id: &str) -> Result<Option<Head>> {
        let mut stmt = self
            .conn()
            .prepare(&format!("SELECT * FROM ({}) WHERE object_id = ?1", head_sql()))?;
        let mut rows = stmt.query_map([object_id], read_head)?;
        Ok(rows.next().transpose()?)
    }

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

    // ------------------------------------------ the projection's record

    fn recorded_for_object(&self, object_id: &str) -> Result<Option<Recorded>> {
        Ok(self
            .conn()
            .query_row(
                "SELECT rel_path, revision_id, content_sha256, size, mtime_ns, recorded_ns
                 FROM projections WHERE object_id = ?1",
                [object_id],
                read_recorded,
            )
            .map(Some)
            .or_else(no_rows_none)?)
    }

    fn recorded_at_path(&self, rel_path: &str) -> Result<Option<(String, Recorded)>> {
        Ok(self
            .conn()
            .query_row(
                "SELECT object_id, rel_path, revision_id, content_sha256,
                        size, mtime_ns, recorded_ns
                 FROM projections WHERE rel_path = ?1",
                [rel_path],
                |row| Ok((row.get::<_, String>(0)?, read_recorded_from(row, 1)?)),
            )
            .map(Some)
            .or_else(no_rows_none)?)
    }

    /// Whether the file at `target` still holds the bytes the record
    /// names, decided **without reading it** where that is sound.
    ///
    /// This is git's index rule (ADR 0020 § 2.5): size and mtime that
    /// both match are enough. Openness is kept — a stat per file per
    /// pass, so a file edited by any other tool is still noticed — but
    /// the SHA-256 of every projected file on every pass is not the
    /// price of noticing.
    ///
    /// **Git's racy-file safeguard, and it is not optional.** A file
    /// whose mtime is not older than the moment its record was written
    /// may have been rewritten inside the filesystem's timestamp
    /// resolution, between the write and the record. Those are hashed
    /// regardless, and when the hash says nothing changed the record's
    /// moment is re-armed with a later one, so the entry settles instead
    /// of being re-hashed for ever.
    ///
    /// Residual, stated because it is inherited rather than introduced:
    /// a filesystem with coarse timestamps (two seconds on FAT) can
    /// report an mtime older than a write that really happened, and a
    /// rewrite that preserves the length is then invisible until
    /// `verify` hashes everything.
    fn file_matches_record(
        &self,
        object_id: &str,
        target: &Path,
        record: &Recorded,
    ) -> Result<bool> {
        let (Some(recorded_stat), Some(now)) = (record.stat, stat_of(target)) else {
            return Ok(false);
        };
        if record.recorded_ns == 0 || now != recorded_stat {
            return Ok(false);
        }
        if now.mtime_ns < record.recorded_ns {
            return Ok(true);
        }
        probe::file_read();
        let Ok(bytes) = std::fs::read(target) else {
            return Ok(false);
        };
        if ids::sha256_hex(&bytes) != record.sha {
            return Ok(false);
        }
        self.conn().execute(
            "UPDATE projections SET recorded_ns = ?2 WHERE object_id = ?1",
            rusqlite::params![object_id, now_ns()],
        )?;
        Ok(true)
    }

    /// Every projected file hashed, and anything the stat rule would
    /// have missed both reported and re-armed.
    ///
    /// Reporting alone would be a diagnosis with no treatment: the stat
    /// rule that missed the file once would go on missing it. So a
    /// record whose file has changed underneath it has its stat facts
    /// cleared, and the next reconciliation reads the file properly.
    ///
    /// A file whose record already says it is out of date is not a
    /// problem and is not reported: that is an ordinary external edit
    /// which reconciliation has yet to take in.
    pub(crate) fn verify_projections(&self) -> Result<Vec<String>> {
        let projections: Vec<(String, Recorded)> = {
            let mut stmt = self.conn().prepare(
                "SELECT object_id, rel_path, revision_id, content_sha256,
                        size, mtime_ns, recorded_ns
                 FROM projections",
            )?;
            let rows = stmt
                .query_map([], |row| Ok((row.get::<_, String>(0)?, read_recorded_from(row, 1)?)))?;
            rows.collect::<std::result::Result<_, _>>()?
        };
        let mut problems = Vec::new();
        for (object_id, record) in projections {
            let target = self.vault().join(&record.rel_path);
            probe::file_read();
            let Ok(bytes) = std::fs::read(&target) else {
                // A projected file that is not there is what reconcile
                // turns into a tombstone proposal. Not damage, and
                // saying so here would make an ordinary deletion look
                // like one.
                continue;
            };
            if ids::sha256_hex(&bytes) == record.sha {
                continue;
            }
            let stat_agreed = matches!((record.stat, stat_of(&target)), (Some(a), Some(b)) if a == b)
                && record.recorded_ns != 0;
            if !stat_agreed {
                continue;
            }
            problems.push(format!(
                "projection {} differs from its record while its size and timestamp matched; \
                 the record has been re-armed so reconciliation reads it",
                record.rel_path
            ));
            self.conn().execute(
                "UPDATE projections SET size = -1, mtime_ns = 0, recorded_ns = 0
                 WHERE object_id = ?1",
                [&object_id],
            )?;
        }
        Ok(problems)
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
    /// those the question is asked of the projection instead, in the
    /// narrowest form that is actually true: **the file on disk reflects
    /// exactly the head being deleted, and nothing has landed since.** The
    /// object has one head, and it is the revision the projection was
    /// written from. The caller has already established that the bytes on
    /// disk are the ones the kernel wrote, so together those say the whole
    /// of what is here was seen.
    ///
    /// **What this adds, and why.** The fallback used to ask only where
    /// the projected revision came from — withdraw unless its `origin` was
    /// `external-edit` — which is a question about *who wrote it* standing
    /// in for a question about *what has arrived*. A revision synced in
    /// from another device carries that device's origin, not
    /// `external-edit`, so a note revised remotely after this device
    /// projected it, and then tombstoned by a keyless deleter, had its
    /// file deleted while holding words the deleter had never seen.
    ///
    /// The head comparison is exact about that and infers nothing about
    /// provenance: a head that is not the projected one means something
    /// landed after the file was written, whoever wrote it. The origin
    /// check stays beside it rather than being replaced by it, because it
    /// answers a case the comparison cannot — an edit ingested *here*
    /// becomes the head and the projection's revision both, so on its own
    /// the comparison would delete words somebody had just typed.
    ///
    /// Both must hold, so this is strictly narrower than either question
    /// alone: it withdraws in fewer cases and never in more. That is the
    /// direction rule 1 asks for — the cost of keeping a file is an import
    /// candidate and a notice, the cost of deleting one is somebody's
    /// words.
    fn deleter_saw_everything(&self, object_id: &str, projected_revision: &str) -> Result<bool> {
        let mut named: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();
        let mut any_key = false;
        {
            let mut stmt = self
                .conn()
                .prepare("SELECT payload FROM operations WHERE kind = ?1 AND target_id = ?2")?;
            let rows = stmt
                .query_map(rusqlite::params![crate::operations::TOMBSTONE, object_id], |row| {
                    row.get::<_, String>(0)
                })?;
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
        let heads = crate::revisions::heads(self.conn(), object_id)?;
        if !any_key {
            // Nothing has landed since the file was written: one head, and
            // it is the one the projection was rendered from.
            if heads.len() != 1 || heads[0] != projected_revision {
                return Ok(false);
            }
            // And the head is not this device's own ingested edit. Kept
            // rather than replaced: on its own the head comparison says
            // yes here, because ingesting an edit *is* a revision and it
            // *does* become the projection's, so a tombstone arriving
            // afterwards would delete words somebody had just typed
            // (`an_edit_ingested_before_the_tombstone_arrived_still_keeps_the_file`).
            // The two together are strictly narrower than either alone —
            // this can only ever keep more files, never fewer — which is
            // the direction rule 1 asks for: keeping costs an import
            // candidate and a notice, deleting costs somebody's words.
            let origin: Option<String> = self
                .conn()
                .query_row(
                    "SELECT origin FROM revisions WHERE revision_id = ?1",
                    [projected_revision],
                    |row| row.get(0),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            return Ok(origin.as_deref() != Some(EXTERNAL_EDIT_ORIGIN));
        }
        Ok(heads.iter().all(|head| named.contains(head)))
    }

    fn object_exists(&self, object_id: &str) -> Result<bool> {
        Ok(self.conn().query_row(
            "SELECT EXISTS(SELECT 1 FROM objects WHERE object_id = ?1)",
            [object_id],
            |row| row.get::<_, i64>(0),
        )? == 1)
    }

    /// Where a note from another device goes when its own path is taken:
    /// `<stem> (from <origin>).md`, with the origin device's short id
    /// because the kernel knows no device names. `None` for an object
    /// this device made itself — its collision is a local one.
    fn conflict_path(&self, head: &Head, rel_path: &str) -> Result<Option<String>> {
        let origins = crate::operations::origins_of(self.conn(), &head.object_id)?;
        let Some(origin) = origins.object else { return Ok(None) };
        if origin == self.device_id()? {
            return Ok(None);
        }
        let (stem, ext) = rel_path.rsplit_once('.').unwrap_or((rel_path, "md"));
        Ok(Some(format!("{stem} (from {}).{ext}", ids::short(&origin))))
    }

    fn is_deleted(&self, object_id: &str) -> Result<bool> {
        let deleted: Option<i64> = self
            .conn()
            .query_row("SELECT deleted FROM objects WHERE object_id = ?1", [object_id], |row| {
                row.get(0)
            })
            .map(Some)
            .or_else(no_rows_none)?;
        Ok(deleted == Some(1))
    }

    /// Keep an edited file whose object was deleted elsewhere: stop
    /// claiming it as a projection, record it as an import candidate, and
    /// raise the notice that says what happened. Nothing on disk is
    /// touched — the whole point is that these are the person's words.
    fn keep_edit_of_deleted(&self, object_id: &str, rel_path: &str, text: &str) -> Result<i64> {
        self.conn().execute("DELETE FROM projections WHERE object_id = ?1", [object_id])?;
        crate::alerts::raise(
            self.conn(),
            crate::alerts::VAULT_DELETED_NOTE_EDITED,
            object_id,
            &format!(
                "this note was deleted on another device, but it had been edited here; \
                 the edited file is kept at {rel_path} and is offered as an import"
            ),
        )?;
        self.record_import(rel_path, text, Some(object_id))
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
        let payload: serde_json::Value = serde_json::from_str(&head.payload)?;
        if let Some(path) = recorded {
            if payload.get(crate::pages::PAYLOAD_KEY).is_some() {
                crate::pages::checked_path(self.vault(), &path)?;
            }
            return Ok(path);
        }
        if let Some(path) = crate::pages::projection_path(&payload)? {
            crate::pages::checked_path(self.vault(), &path)?;
            return Ok(path);
        }
        // An adopted note names its own path (ADR 0007, 2026-10-22).
        if let Some(path) = payload.get("path").and_then(|v| v.as_str()) {
            crate::pages::checked_path(self.vault(), path)?;
            return Ok(path.to_string());
        }
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
        if let Some(saved) = payload.get(header::PAYLOAD_KEY) {
            let Some(rendered) =
                Header::from_payload(saved).and_then(|header| header.render(&front))
            else {
                // An unknown or invalid envelope must never trigger a lossy
                // fallback to the legacy renderer.
                return Ok(None);
            };
            front = rendered;
        }

        let body = if let Some(text) = payload.get("text").and_then(|v| v.as_str()) {
            text.to_string()
        } else if let Some(blob_rel) = payload.get("rel_path").and_then(|v| v.as_str()) {
            let name = blob_rel.rsplit('/').next().unwrap_or(blob_rel);
            if payload.get("media").and_then(|v| v.as_str()) == Some("txt") {
                probe::file_read();
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
                .query_row("SELECT state, origin FROM blobs WHERE blob_id = ?1", [blob_id], |row| {
                    Ok((row.get(0)?, row.get(1)?))
                })
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

    fn ingest_external_edit(
        &self,
        object_id: &str,
        rel_path: &str,
        header: &Header,
        disk: &[u8],
    ) -> Result<()> {
        let revision_id = ids::new_id();
        let created_ms = ids::now_ms();
        let stat = stat_of(&self.vault().join(rel_path));
        // An edit changes the words, not the document's other facts: the
        // new text merges into the current head so `rel_path` and friends
        // survive. Replacing the document here would strip a voice note's
        // link to its own audio on the first edit (found 2026-08-31).
        let mut document = crate::revisions::head_payload(self.conn(), object_id)?
            .and_then(|payload| serde_json::from_str::<serde_json::Value>(&payload).ok())
            .unwrap_or_else(|| serde_json::json!({}));
        document["text"] = serde_json::Value::String(header.snapshot.body().to_string());
        document[header::PAYLOAD_KEY] = header.payload();
        let payload = document.to_string();
        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let done: Result<()> = (|| {
            add_revision(
                conn,
                object_id,
                &revision_id,
                &payload,
                EXTERNAL_EDIT_ORIGIN,
                created_ms,
                Announce::Event,
            )?;
            // Record the disk state we ingested so projection may
            // canonicalise over it.
            conn.execute(
                "UPDATE projections
                    SET revision_id = ?2, content_sha256 = ?3,
                        size = ?4, mtime_ns = ?5, recorded_ns = ?6
                  WHERE object_id = ?1",
                rusqlite::params![
                    object_id,
                    revision_id,
                    ids::sha256_hex(disk),
                    stat.map(|s| s.size).unwrap_or(-1),
                    stat.map(|s| s.mtime_ns).unwrap_or(0),
                    if stat.is_some() { now_ns() } else { 0 },
                ],
            )?;
            changes::record(
                conn,
                Change {
                    kind: ChangeKind::Modified,
                    rel_path,
                    previous_rel_path: None,
                    object_id: Some(object_id),
                    revision_id: Some(&revision_id),
                    cause: ChangeCause::ExternalEdit,
                },
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

    /// Record what the kernel now claims at `rel_path`, and — when
    /// `announce` names one — the change the vault's files just made.
    /// One transaction, so the two can never disagree (ADR 0020 § 2.2).
    fn record_projection(
        &self,
        head: &Head,
        rel_path: &str,
        content_sha: &str,
        announce: Option<ChangeKind>,
    ) -> Result<()> {
        // Stat after the bytes have landed: this is what lets the next
        // pass answer "unchanged" without reading the file.
        let stat = stat_of(&self.vault().join(rel_path));
        let cause = match announce {
            Some(_) => self.projection_cause(head)?,
            None => ChangeCause::LocalEdit,
        };
        self.write_txn(|| {
            // OR REPLACE covers both unique constraints (object_id,
            // rel_path): two processes recording the same projection
            // concurrently must both succeed, whichever constraint
            // SQLite trips first.
            self.conn().execute(
                "INSERT OR REPLACE INTO projections
                 (object_id, rel_path, revision_id, content_sha256, size, mtime_ns, recorded_ns)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                rusqlite::params![
                    head.object_id,
                    rel_path,
                    head.revision_id,
                    content_sha,
                    stat.map(|s| s.size).unwrap_or(-1),
                    stat.map(|s| s.mtime_ns).unwrap_or(0),
                    if stat.is_some() { now_ns() } else { 0 },
                ],
            )?;
            if let Some(kind) = announce {
                changes::record(
                    self.conn(),
                    Change {
                        kind,
                        rel_path,
                        previous_rel_path: None,
                        object_id: Some(&head.object_id),
                        revision_id: Some(&head.revision_id),
                        cause,
                    },
                )?;
            }
            Ok(())
        })
    }

    /// Follow a file that moved here. Files are matched by front-matter
    /// id, never by name (ADR 0007), so the record is a metadata change —
    /// and the rename is then a revision (amendment 2026-10-23), so the
    /// new name reaches every peer the way an edit does. A deleted
    /// object's file moving is only the record following it.
    fn record_rename(
        &self,
        object_id: &str,
        previous_rel_path: &str,
        rel_path: &str,
        revision_id: &str,
    ) -> Result<()> {
        self.move_record(object_id, previous_rel_path, rel_path, revision_id, ChangeCause::Reconcile)?;
        if !self.is_deleted(object_id)? {
            self.rename_as_revision(object_id, rel_path)?;
        }
        Ok(())
    }

    /// The path a head's own document names, when it names one: an
    /// adopted note's, or the one a rename revision set. Pages name theirs
    /// another way and are not asked; a capture that was never renamed
    /// names none.
    fn path_named_by(payload: &str) -> Option<String> {
        let value: serde_json::Value = serde_json::from_str(payload).ok()?;
        if value.get(crate::pages::PAYLOAD_KEY).is_some() {
            return None;
        }
        value.get("path").and_then(|v| v.as_str()).map(str::to_string)
    }

    /// A rename as a revision: the head document with `path` set to where
    /// the file is, origin `rename`, and so an operation carrying the new
    /// name to every peer — or none, for a note kept on this device
    /// (`add_revision` withholds it). Silent, because a rename changes no
    /// words: no processor has anything to do with it. The record moves
    /// onto the new revision as an external edit's does — the file on
    /// disk is this revision's words, only its header lags — so the
    /// document reads as managed, and the next projection rewrites the
    /// header as it does for any revision.
    fn rename_as_revision(&self, object_id: &str, rel_path: &str) -> Result<()> {
        let Some(head) = self.live_head(object_id)? else { return Ok(()) };
        let Ok(mut document) = serde_json::from_str::<serde_json::Value>(&head.payload) else {
            return Ok(());
        };
        if document.get(crate::pages::PAYLOAD_KEY).is_some() || !document.is_object() {
            return Ok(());
        }
        document["path"] = serde_json::Value::String(rel_path.to_string());
        let payload = document.to_string();
        let revision_id = ids::new_id();
        let created_ms = ids::now_ms();
        self.write_txn(|| {
            add_revision(
                self.conn(),
                object_id,
                &revision_id,
                &payload,
                RENAME_ORIGIN,
                created_ms,
                Announce::Silent,
            )?;
            self.conn().execute(
                "UPDATE projections SET revision_id = ?2 WHERE object_id = ?1",
                rusqlite::params![object_id, revision_id],
            )?;
            Ok(())
        })
    }

    /// Move this device's file to the path an arrived head names and let
    /// the record follow. The target has to be free: an unrelated file
    /// there is preserved and the note is held where it is with the same
    /// notice a new arrival gets when its path is occupied. A path that
    /// would leave the vault is not followed at all.
    fn follow_rename(&self, head: &Head, record: &Recorded, wanted: &str) -> Result<Option<String>> {
        let Ok(wanted) = self.projected_path(wanted) else { return Ok(None) };
        let from = self.vault().join(&record.rel_path);
        let to = self.vault().join(&wanted);
        if to.exists() {
            self.report_occupied_projection(&head.object_id, &wanted)?;
            return Ok(None);
        }
        if from.exists() {
            if let Some(parent) = to.parent() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| KernelError::Io { path: parent.to_path_buf(), source: e })?;
            }
            std::fs::rename(&from, &to).map_err(|e| KernelError::Io { path: to.clone(), source: e })?;
        }
        self.move_record(&head.object_id, &record.rel_path, &wanted, &record.revision_id, ChangeCause::Sync)?;
        Ok(Some(wanted))
    }

    /// The record of where a file is, after it moved — by a hand here
    /// (`Reconcile`) or by a rename that arrived (`Sync`). The bytes did
    /// not move with the record, so the stat facts are re-taken rather
    /// than assumed.
    fn move_record(
        &self,
        object_id: &str,
        previous_rel_path: &str,
        rel_path: &str,
        revision_id: &str,
        cause: ChangeCause,
    ) -> Result<()> {
        let stat = stat_of(&self.vault().join(rel_path));
        self.write_txn(|| {
            self.conn().execute(
                "UPDATE projections
                    SET rel_path = ?2, size = ?3, mtime_ns = ?4, recorded_ns = ?5
                  WHERE object_id = ?1",
                rusqlite::params![
                    object_id,
                    rel_path,
                    stat.map(|s| s.size).unwrap_or(-1),
                    stat.map(|s| s.mtime_ns).unwrap_or(0),
                    if stat.is_some() { now_ns() } else { 0 },
                ],
            )?;
            changes::record(
                self.conn(),
                Change {
                    kind: ChangeKind::Moved,
                    rel_path,
                    previous_rel_path: Some(previous_rel_path),
                    object_id: Some(object_id),
                    revision_id: Some(revision_id),
                    cause,
                },
            )?;
            Ok(())
        })
    }

    /// Why a projection write happened, as far as the kernel can say.
    ///
    /// One question is exact and it is the one that matters: **did this
    /// head arrive from another device?** The operations table records
    /// whose each revision was, so a head carried here by somebody
    /// else's operation is a synced change and nothing else. The rest is
    /// read off `revisions.origin`, which the writer stamped, and
    /// anything unrecognised is reported as a local edit rather than
    /// guessed at (ADR 0020 § 2.1: consumers must not branch on `cause`
    /// for correctness).
    ///
    /// Asked only where a change is being announced, which is to say
    /// only where the projection actually wrote something. A settled
    /// vault never reaches it.
    fn projection_cause(&self, head: &Head) -> Result<ChangeCause> {
        let origins = crate::operations::origins_of(self.conn(), &head.object_id)?;
        if let Some(device) = origins.revisions.get(&head.revision_id) {
            if *device != self.device_id()? {
                return Ok(ChangeCause::Sync);
            }
        }
        Ok(match head.origin.as_str() {
            crate::capture::CAPTURE_ORIGIN => ChangeCause::Capture,
            EXTERNAL_EDIT_ORIGIN | RENAME_ORIGIN => ChangeCause::ExternalEdit,
            origin if origin.starts_with(crate::processor::ORIGIN_PREFIX) => ChangeCause::Processor,
            _ => ChangeCause::LocalEdit,
        })
    }

    /// A caller-supplied path, checked to be a projected file inside this
    /// vault. Anything else is refused rather than acted on: `..` and an
    /// absolute path both lead out of the vault, and only `.md` files are
    /// ever projected.
    fn projected_path(&self, rel_path: &str) -> Result<String> {
        let rel = std::path::PathBuf::from(rel_path.replace('\\', "/"));
        // Path::is_relative follows the host OS. A Windows drive prefix must
        // also be refused on Unix so a portable path has the same meaning.
        let drive_prefix = rel_path.as_bytes().first().is_some_and(u8::is_ascii_alphabetic)
            && rel_path.as_bytes().get(1) == Some(&b':');
        let usable = !drive_prefix && rel.is_relative()
            && rel.components().all(|c| matches!(c, std::path::Component::Normal(_)))
            && rel_path.ends_with(".md")
            && !rel.starts_with(".gilune");
        if !usable {
            return Err(KernelError::VaultUnusable {
                reason: format!("{rel_path} is not a projected file inside this vault"),
            });
        }
        Ok(fs::portable_rel(&rel))
    }

    fn is_quarantined(&self, rel_path: &str) -> Result<bool> {
        let hit: Option<i64> = self
            .conn()
            .query_row("SELECT 1 FROM quarantine WHERE rel_path = ?1", [rel_path], |row| row.get(0))
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
        self.write_txn(|| {
            let inserted = self.conn().execute(
                "INSERT OR IGNORE INTO quarantine (rel_path, reason, created_ms)
                 VALUES (?1, ?2, ?3)",
                rusqlite::params![rel_path, reason, ids::now_ms()],
            )?;
            // OR IGNORE means a path already quarantined changes nothing,
            // so it announces nothing: the feed carries what happened, not
            // what was true again.
            if inserted == 1 {
                changes::record(
                    self.conn(),
                    Change {
                        kind: ChangeKind::Quarantined,
                        rel_path,
                        previous_rel_path: None,
                        object_id: None,
                        revision_id: None,
                        cause: ChangeCause::Reconcile,
                    },
                )?;
            }
            Ok(inserted as i64)
        })
    }

    /// Record a file the kernel does not claim, and announce what
    /// happened to it.
    ///
    /// **Offered once, but heard from for ever.** The first sighting is
    /// `Imported` — something to bring in, nothing on disk touched. Every
    /// later pass compares the bytes against what was recorded and
    /// announces `Modified / ExternalEdit` when they differ, because a
    /// file written by another tool is a file that changes, and a feed
    /// that said nothing after the first sighting left every index
    /// answering about words that had been rewritten (constitution
    /// rule 13: third-party editing is supported use, and supporting it
    /// means noticing it).
    ///
    /// Returns 1 only for a *new* import candidate: the summary's
    /// `imports` counts what is newly on offer, and an edit to something
    /// already offered is not a second offer.
    fn record_import(&self, rel_path: &str, text: &str, object_id: Option<&str>) -> Result<i64> {
        let sha = ids::sha256_hex(text.as_bytes());
        let stat = stat_of(&self.vault().join(rel_path));
        let held: Option<String> = self
            .conn()
            .query_row(
                "SELECT content_sha256 FROM imports WHERE rel_path = ?1",
                [rel_path],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(no_rows_none)?;
        // An empty recorded hash is a row written before there were any:
        // the bytes are taken as what is there now rather than announced
        // as a change nobody made.
        let announce = match held.as_deref() {
            None => Some(ChangeKind::Imported),
            Some("") => None,
            Some(recorded) if recorded == sha => None,
            Some(_) => Some(ChangeKind::Modified),
        };
        let fresh = held.is_none();
        self.write_txn(|| {
            self.conn().execute(
                "INSERT INTO imports
                     (rel_path, created_ms, content_sha256, size, mtime_ns, recorded_ns,
                      object_id)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                 ON CONFLICT(rel_path) DO UPDATE SET
                     content_sha256 = ?3, size = ?4, mtime_ns = ?5, recorded_ns = ?6,
                     object_id = ?7",
                rusqlite::params![
                    rel_path,
                    ids::now_ms(),
                    sha,
                    stat.map(|s| s.size).unwrap_or(-1),
                    stat.map(|s| s.mtime_ns).unwrap_or(0),
                    now_ns(),
                    object_id,
                ],
            )?;
            if let Some(kind) = announce {
                // A file the kernel does not claim, so it names no
                // object: whatever id the file carries, this vault has
                // no projection of it.
                changes::record(
                    self.conn(),
                    Change {
                        kind,
                        rel_path,
                        previous_rel_path: None,
                        object_id: None,
                        revision_id: None,
                        cause: if kind == ChangeKind::Imported {
                            ChangeCause::Reconcile
                        } else {
                            // Exact: the kernel did not write this file,
                            // so whoever changed it was outside.
                            ChangeCause::ExternalEdit
                        },
                    },
                )?;
            }
            Ok(())
        })?;
        Ok(if fresh { 1 } else { 0 })
    }

    /// Whether the import at `rel_path` still holds the bytes its record
    /// names, decided **without reading it** where that is sound.
    ///
    /// `file_matches_record`'s rule, applied to the other table: size and
    /// mtime that both match are enough, and git's racy-file safeguard
    /// hashes anything whose mtime is not older than the moment its
    /// record was written, re-arming that moment when the hash agrees so
    /// the entry settles instead of being hashed for ever.
    ///
    /// A row with no recorded hash — written before this was a question —
    /// is never a match, so it is read once and then behaves like the
    /// rest.
    ///
    /// `Some(object_id)` on a match, where the inner option is the id the
    /// file carries: a note kept after being deleted elsewhere is still
    /// about an object, and the notice that says so is only true while
    /// the file is there. Reading it off the record is what lets the pass
    /// answer that question **without** reading the file, which is the
    /// whole point of the rule.
    #[allow(clippy::type_complexity)]
    fn import_matches_record(&self, rel_path: &str) -> Result<Option<Option<String>>> {
        let held: Option<(String, i64, i64, i64, Option<String>)> = self
            .conn()
            .query_row(
                "SELECT content_sha256, size, mtime_ns, recorded_ns, object_id FROM imports
                  WHERE rel_path = ?1",
                [rel_path],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?)),
            )
            .map(Some)
            .or_else(no_rows_none)?;
        let Some((sha, size, mtime_ns, recorded_ns, object_id)) = held else {
            return Ok(None);
        };
        if sha.is_empty() || size < 0 || recorded_ns == 0 {
            return Ok(None);
        }
        let target = self.vault().join(rel_path);
        let Some(now) = stat_of(&target) else {
            return Ok(None);
        };
        if now != (Stat { size, mtime_ns }) {
            return Ok(None);
        }
        if now.mtime_ns < recorded_ns {
            return Ok(Some(object_id));
        }
        probe::file_read();
        let Ok(bytes) = std::fs::read(&target) else {
            return Ok(None);
        };
        if ids::sha256_hex(&bytes) != sha {
            return Ok(None);
        }
        self.conn().execute(
            "UPDATE imports SET recorded_ns = ?2 WHERE rel_path = ?1",
            rusqlite::params![rel_path, now_ns()],
        )?;
        Ok(Some(object_id))
    }

    /// An import candidate whose file has gone: the feed says so once,
    /// and the row goes, so a file put back is offered again.
    ///
    /// The projection's disappearance rule, without the proposal: nothing
    /// here was ever kernel history, so there is no decision for a person
    /// to make — the words were theirs, and they took them away.
    fn forget_missing_import(&self, rel_path: &str) -> Result<()> {
        let held: Option<i64> = self
            .conn()
            .query_row("SELECT 1 FROM imports WHERE rel_path = ?1", [rel_path], |row| row.get(0))
            .map(Some)
            .or_else(no_rows_none)?;
        if held.is_none() {
            // Never offered, so nothing left: a path the vault has never
            // heard of must not be announced as having gone.
            return Ok(());
        }
        let announce = !self.already_removed(rel_path)?;
        self.write_txn(|| {
            self.conn().execute("DELETE FROM imports WHERE rel_path = ?1", [rel_path])?;
            if announce {
                changes::record(
                    self.conn(),
                    Change {
                        kind: ChangeKind::Removed,
                        rel_path,
                        previous_rel_path: None,
                        object_id: None,
                        revision_id: None,
                        cause: ChangeCause::Reconcile,
                    },
                )?;
            }
            Ok(())
        })
    }

    /// Every import candidate this vault is holding, in path order.
    fn recorded_imports(&self) -> Result<Vec<String>> {
        let mut stmt = self.conn().prepare("SELECT rel_path FROM imports ORDER BY rel_path")?;
        let rows = stmt.query_map([], |row| row.get::<_, String>(0))?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

}

/// The head selection the projection reads. One statement, ordered by the
/// one rule every reader takes (`newest_head_first`), so replicas cannot
/// disagree about which head renders.
fn head_sql() -> String {
    format!(
        "SELECT o.object_id, o.type_id, o.type_schema, o.created_ms,
                r.revision_id, r.payload, r.origin,
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
        origin: row.get(6)?,
        conflicted: row.get::<_, i64>(7)? > 1,
    })
}

fn read_recorded(row: &rusqlite::Row<'_>) -> rusqlite::Result<Recorded> {
    read_recorded_from(row, 0)
}

/// `rel_path, revision_id, content_sha256, size, mtime_ns, recorded_ns`,
/// starting at column `first`.
fn read_recorded_from(row: &rusqlite::Row<'_>, first: usize) -> rusqlite::Result<Recorded> {
    let size: i64 = row.get(first + 3)?;
    let mtime_ns: i64 = row.get(first + 4)?;
    Ok(Recorded {
        rel_path: row.get(first)?,
        revision_id: row.get(first + 1)?,
        sha: row.get(first + 2)?,
        // A row written before there were stat facts, or one written
        // when the file could not be stat'd: absent rather than a pair
        // of zeroes that would compare equal to something.
        stat: (size >= 0).then_some(Stat { size, mtime_ns }),
        recorded_ns: row.get(first + 5)?,
    })
}

/// A file's size and modification time, or `None` when it cannot be
/// stat'd — which is answered as "read it", never as "unchanged".
fn stat_of(path: &Path) -> Option<Stat> {
    let metadata = std::fs::metadata(path).ok()?;
    let mtime_ns =
        metadata.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_nanos() as i64;
    Some(Stat { size: metadata.len() as i64, mtime_ns })
}

/// Nanoseconds since the Unix epoch — the unit `projections.mtime_ns`
/// and `projections.recorded_ns` share, so the racy comparison between
/// them is exact on every platform.
fn now_ns() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as i64)
        .unwrap_or(0)
}

enum FrontMatter {
    Absent,
    Foreign,
    Malformed,
    Parsed(Box<Header>),
}

fn parse_front_matter(text: &str) -> FrontMatter {
    match gilune_document::TextSnapshot::new(text.to_string()) {
        Ok(snapshot) if snapshot.header().is_none() => FrontMatter::Absent,
        Ok(snapshot) if !snapshot.has_reserved_metadata() => FrontMatter::Foreign,
        Ok(_) => Header::parse(text).map(Box::new).map_or(FrontMatter::Malformed, FrontMatter::Parsed),
        Err(_) => FrontMatter::Malformed,
    }
}

/// Publish a fully written first projection atomically without replacing a file
/// that appeared after discovery. The committed revision is recovery evidence.
fn write_new_projection(target: &Path, content: &[u8]) -> Result<bool> {
    use std::io::Write;
    let parent = target.parent().ok_or_else(|| KernelError::DocumentInvalid { detail: "page has no parent".into() })?;
    std::fs::create_dir_all(parent).map_err(|source| KernelError::Io { path:parent.into(),source })?;
    let temporary = parent.join(format!(".tmp-{}", ids::new_id()));
    let mut file = std::fs::OpenOptions::new().create_new(true).write(true).open(&temporary)
        .map_err(|source| KernelError::Io {path:temporary.clone(),source})?;
    file.write_all(content).map_err(|source| KernelError::Io {path:temporary.clone(),source})?;
    file.sync_all().map_err(|source| KernelError::Io {path:temporary.clone(),source})?;
    drop(file);
    let published = fs::publish_new_file(&temporary, target)?;
    fs::fsync_dir(parent)?;
    match std::fs::remove_file(&temporary) {
        Ok(()) => {},
        Err(error) if error.kind() == std::io::ErrorKind::NotFound && published => {},
        Err(source) => return Err(KernelError::Io {path:temporary,source}),
    }
    Ok(published)
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
        from_elsewhere(store, 1, crate::operations::TOMBSTONE, object_id, serde_json::json!({}));
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
        store.integrity_alerts().expect("alerts").into_iter().map(|a| a.kind).collect()
    }

    /// Every change this vault has recorded, as (kind, path, cause).
    fn feed(store: &Store) -> Vec<(ChangeKind, String, ChangeCause)> {
        page(store, &store.change_origin().expect("origin"))
    }

    fn page(
        store: &Store,
        cursor: &crate::changes::Cursor,
    ) -> Vec<(ChangeKind, String, ChangeCause)> {
        store
            .changes_since(cursor, 1000)
            .expect("changes")
            .changes
            .into_iter()
            .map(|c| (c.kind, c.rel_path, c.cause))
            .collect()
    }

    fn cursor_now(store: &Store) -> crate::changes::Cursor {
        store
            .changes_since(&store.change_origin().expect("origin"), crate::changes::MAX_PAGE)
            .expect("changes")
            .next
    }

    // ------------------------------------------- the vault change feed
    //
    // ADR 0020 § 8.1: every mutation path produces exactly its changes,
    // one test per path.

    #[test]
    fn a_voice_capture_is_one_created_change_caused_by_the_capture() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let receipt = store.capture_bytes("wav", &[3u8; 512]).expect("capture");
        assert!(feed(&store).is_empty(), "committing is not projecting");

        store.project().expect("project");
        let rel = receipt.rel_path.replace(".wav", ".md");
        assert_eq!(
            feed(&store),
            vec![(ChangeKind::Created, rel, ChangeCause::Capture)],
            "one file appeared, and the kernel knows exactly why"
        );
    }

    #[test]
    fn a_typed_capture_is_one_created_change_and_projecting_again_says_nothing() {
        let (_dir, store, rel) = captured("typed straight in");
        assert_eq!(feed(&store), vec![(ChangeKind::Created, rel, ChangeCause::Capture)]);
        // The second-order half: a feed that repeated itself on every
        // idle pass would cost a consumer the whole vault per tick,
        // which is the cost this exists to remove.
        store.project().expect("again");
        store.reconcile().expect("again");
        assert_eq!(feed(&store).len(), 1, "a settled vault says nothing");
    }

    #[test]
    fn a_processor_output_is_one_modified_change_caused_by_the_processor() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store
            .register_plugin(&crate::processor::fake_transcriber_descriptor())
            .expect("processor plugin");
        let receipt = store.capture_bytes("wav", &[7u8; 256]).expect("capture");
        store.project().expect("project");
        let rel = receipt.rel_path.replace(".wav", ".md");
        let after_capture = cursor_now(&store);

        let summary = store
            .run_pending_jobs(&[&crate::processor::FakeTranscriber], ids::now_ms(), 30_000)
            .expect("work");
        assert_eq!(summary.outputs, 1);
        store.project().expect("project the words");

        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Modified, rel, ChangeCause::Processor)],
            "the words landing in the note is a change to that file and nothing else"
        );
    }

    #[test]
    fn a_save_reaches_the_feed_through_reconcile_path_before_anything_rescans() {
        // ADR 0020 § 8.7. The editor writes its own file and calls this;
        // the change is in the feed before any full pass runs.
        let (_dir, store, rel) = captured("what was typed");
        let after_capture = cursor_now(&store);
        let edited =
            read_rel(&store, &rel).replace("what was typed", "what was typed and then some");
        std::fs::write(store.vault().join(&rel), &edited).expect("the editor's own save");

        let summary = store.reconcile_path(&rel).expect("reconcile one file");
        assert_eq!(summary.edits, 1);
        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Modified, rel.clone(), ChangeCause::ExternalEdit)]
        );

        // The second-order half: the full pass afterwards must not
        // record the same save a second time.
        let after_save = cursor_now(&store);
        store.reconcile().expect("the full pass");
        assert!(
            page(&store, &after_save).is_empty(),
            "a file already reconciled is not reconciled again"
        );
    }

    #[test]
    fn an_external_rewrite_is_one_modified_change_and_canonicalising_is_another() {
        let (_dir, store, rel) = captured("original text");
        let after_capture = cursor_now(&store);
        let edited = read_rel(&store, &rel).replace("original text", "edited text");
        std::fs::write(store.vault().join(&rel), &edited).expect("external edit");

        store.reconcile().expect("reconcile");
        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Modified, rel.clone(), ChangeCause::ExternalEdit)]
        );

        // Projection then rewrites the file into canonical form, which
        // is a second real change to the bytes on disk — the feed says
        // what happened, not what was intended.
        let after_edit = cursor_now(&store);
        assert_eq!(store.project().expect("canonicalise").written, 1);
        assert_eq!(
            page(&store, &after_edit),
            vec![(ChangeKind::Modified, rel, ChangeCause::ExternalEdit)]
        );
    }

    #[test]
    fn a_rename_by_id_is_one_move_that_names_where_the_file_was() {
        let (_dir, store, rel) = captured("name me differently");
        let after_capture = cursor_now(&store);
        let moved = "Renamed/better-name.md";
        std::fs::create_dir_all(store.vault().join("Renamed")).expect("mkdir");
        std::fs::rename(store.vault().join(&rel), store.vault().join(moved)).expect("rename");

        assert_eq!(store.reconcile().expect("reconcile").renames, 1);
        let changes = store.changes_since(&after_capture, 100).expect("changes").changes;
        assert_eq!(changes.len(), 1, "a rename is one change, not a delete and a create");
        assert_eq!(changes[0].kind, ChangeKind::Moved);
        assert_eq!(changes[0].rel_path, moved);
        assert_eq!(
            changes[0].previous_rel_path.as_deref(),
            Some(rel.as_str()),
            "a consumer has to be able to move what it knew"
        );
    }

    #[test]
    fn a_rename_and_an_edit_in_the_same_pass_are_two_changes_in_the_order_they_happened() {
        // The second-order case for the rename: moving a file and
        // editing it are one visit to that path, and a feed that
        // reported only the last thing it noticed would lose one of
        // them.
        let (_dir, store, rel) = captured("moved and edited");
        let after_capture = cursor_now(&store);
        let moved = "Elsewhere/renamed.md";
        std::fs::create_dir_all(store.vault().join("Elsewhere")).expect("mkdir");
        let text = read_rel(&store, &rel).replace("moved and edited", "and edited too");
        std::fs::remove_file(store.vault().join(&rel)).expect("remove");
        std::fs::write(store.vault().join(moved), &text).expect("write elsewhere");

        let summary = store.reconcile().expect("reconcile");
        assert_eq!((summary.renames, summary.edits), (1, 1));
        assert_eq!(
            page(&store, &after_capture),
            vec![
                (ChangeKind::Moved, moved.to_string(), ChangeCause::Reconcile),
                (ChangeKind::Modified, moved.to_string(), ChangeCause::ExternalEdit),
            ]
        );
    }

    #[test]
    fn a_deletion_that_becomes_a_withdrawn_tombstone_says_removed_once() {
        let (_dir, store, rel) = captured("delete me");
        let after_capture = cursor_now(&store);
        std::fs::remove_file(store.vault().join(&rel)).expect("delete");

        store.reconcile().expect("reconcile");
        let (proposal_id, _) = store.open_proposals().expect("proposals")[0].clone();
        store.resolve_tombstone(&proposal_id, true).expect("accept");
        store.project().expect("project");

        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Removed, rel, ChangeCause::Reconcile)],
            "one file left the vault, and it is said once however many \
             passes notice it"
        );
    }

    #[test]
    fn a_revision_that_arrived_from_elsewhere_projects_as_a_synced_change() {
        // The one part of `cause` that is exact rather than read off a
        // string the writer stamped: the operations table records whose
        // each revision was, so a head carried here by somebody else's
        // operation is a synced change and can be nothing else.
        let (_dir, store, rel) = captured("first words");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let seen = head_of(&store, &object_id);
        let after_capture = cursor_now(&store);

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
                    "origin": "capture",
                    "created_ms": 1_700_000_000_500i64,
                },
                "supersedes": [seen],
            }),
        );
        assert_eq!(store.project().expect("project").written, 1);
        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Modified, rel, ChangeCause::Sync)],
            "the same `origin` string a local capture carries, and a \
             different answer, because the question is whose operation it was"
        );
    }

    #[test]
    fn a_file_that_left_the_vault_is_announced_once_however_many_passes_notice_it() {
        // The second-order case for the removal guard, and the ordering
        // that has two passes looking at one disappearance: reconcile
        // finds the file gone and opens a proposal, and then the same
        // deletion arrives from another device, so the projection drops
        // a claim on a file that was already not there. One file left
        // the vault; a consumer that was told twice would forget it
        // twice and could not tell that from two files.
        let (_dir, store, rel) = captured("gone from here, then gone from there");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let after_capture = cursor_now(&store);

        std::fs::remove_file(store.vault().join(&rel)).expect("delete here");
        assert_eq!(store.reconcile().expect("reconcile").tombstone_proposals, 1);
        tombstone_from_elsewhere(&store, &object_id);
        store.project().expect("project");

        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Removed, rel, ChangeCause::Reconcile)]
        );
    }

    #[test]
    fn a_tombstone_arriving_from_elsewhere_withdraws_the_file_and_says_so() {
        let (_dir, store, rel) = captured("nobody touched this");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let after_capture = cursor_now(&store);
        tombstone_from_elsewhere(&store, &object_id);

        assert_eq!(store.project().expect("project").removed, 1);
        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Removed, rel, ChangeCause::Sync)],
            "the kernel knows this deletion was decided somewhere else"
        );
    }

    #[test]
    fn a_file_the_kernel_did_not_write_is_one_imported_change() {
        let (_dir, store, _rel) = captured("resident");
        // Local-only by the person's own mark (ADR 0007, 2026-10-22): the one
        // case in which a file the kernel did not write stays a candidate.
        std::fs::write(store.vault().join(".gilune-local"), "").expect("local marker");
        let after_capture = cursor_now(&store);
        std::fs::write(store.vault().join("Notes.md"), "just my notes").expect("foreign file");

        assert_eq!(store.reconcile().expect("reconcile").imports, 1);
        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Imported, "Notes.md".to_string(), ChangeCause::Reconcile)]
        );
        // The second-order half: a candidate already recorded is not a
        // change, so the feed does not repeat it on every pass.
        store.reconcile().expect("again");
        assert_eq!(page(&store, &after_capture).len(), 1);
    }

    #[test]
    fn an_edit_to_a_file_the_kernel_did_not_write_is_one_modified_change() {
        // Constitution rule 13: a note written in another editor is
        // first-class, and a feed that announced it once and then went
        // quiet left every reader answering about words that had been
        // rewritten. It is `ExternalEdit` by construction — the kernel
        // does not claim the file, so nobody here wrote it.
        let (_dir, store, _rel) = captured("resident");
        // Local-only by the person's own mark (ADR 0007, 2026-10-22): the one
        // case in which a file the kernel did not write stays a candidate.
        std::fs::write(store.vault().join(".gilune-local"), "").expect("local marker");
        std::fs::write(store.vault().join("Notes.md"), "just my notes").expect("foreign file");
        store.reconcile().expect("first sighting");

        let after_import = cursor_now(&store);
        std::fs::write(store.vault().join("Notes.md"), "my notes, revised").expect("their edit");
        store.reconcile().expect("reconcile");
        assert_eq!(
            page(&store, &after_import),
            vec![(ChangeKind::Modified, "Notes.md".to_string(), ChangeCause::ExternalEdit)]
        );

        // One step past the obvious: a second edit is a second change,
        // and a rewrite with the same bytes is none — the feed carries
        // what happened to the words, not what happened to the file's
        // timestamp.
        let after_edit = cursor_now(&store);
        std::fs::write(store.vault().join("Notes.md"), "my notes, revised again").expect("edit");
        store.reconcile().expect("reconcile");
        std::fs::write(store.vault().join("Notes.md"), "my notes, revised again").expect("touch");
        store.reconcile().expect("reconcile");
        assert_eq!(
            page(&store, &after_edit),
            vec![(ChangeKind::Modified, "Notes.md".to_string(), ChangeCause::ExternalEdit)]
        );
    }

    #[test]
    fn an_unchanged_import_is_not_read() {
        // K2's rule reaches the files the kernel does *not* write. It has
        // to be asked before the read, because the front matter is what
        // decides a file is an import at all — so a vault of imports used
        // to be read whole on every pass however settled it was.
        let (_dir, store, _rel) = captured("resident");
        std::fs::write(store.vault().join("Theirs.md"), "somebody else's words")
            .expect("foreign file");
        store.reconcile().expect("first sighting");
        store.reconcile().expect("settle the racy record");

        probe::reset_file_reads();
        store.reconcile().expect("a settled pass");
        assert_eq!(probe::file_reads(), 0, "nothing changed, so nothing was read");

        // And the second-order half: the rule must still notice a change.
        std::fs::write(store.vault().join("Theirs.md"), "somebody else's newer words")
            .expect("their edit");
        probe::reset_file_reads();
        assert_eq!(store.reconcile().expect("reconcile").imports, 0, "not a new offer");
        assert!(probe::file_reads() > 0, "a changed file is read");
    }

    #[test]
    fn an_import_that_leaves_the_vault_is_announced_once_and_offered_again_if_it_returns() {
        let (_dir, store, _rel) = captured("resident");
        // Local-only by the person's own mark (ADR 0007, 2026-10-22): the one
        // case in which a file the kernel did not write stays a candidate.
        std::fs::write(store.vault().join(".gilune-local"), "").expect("local marker");
        std::fs::write(store.vault().join("Theirs.md"), "words from elsewhere").expect("write");
        store.reconcile().expect("first sighting");

        let after_import = cursor_now(&store);
        std::fs::remove_file(store.vault().join("Theirs.md")).expect("they deleted it");
        store.reconcile().expect("reconcile");
        store.reconcile().expect("and again");
        assert_eq!(
            page(&store, &after_import),
            vec![(ChangeKind::Removed, "Theirs.md".to_string(), ChangeCause::Reconcile)],
            "said once per disappearance, however many passes notice it"
        );

        // One step past: a file put back at the same path is a new offer,
        // not a silence, because the row that would have suppressed it
        // went with the file.
        let after_removal = cursor_now(&store);
        std::fs::write(store.vault().join("Theirs.md"), "words from elsewhere").expect("back");
        assert_eq!(store.reconcile().expect("reconcile").imports, 1);
        assert_eq!(
            page(&store, &after_removal),
            vec![(ChangeKind::Imported, "Theirs.md".to_string(), ChangeCause::Reconcile)]
        );
    }

    #[test]
    fn reconcile_path_reaches_an_import_the_same_way_the_full_pass_does() {
        // The editor's own route: a one-file pass has to reach the same
        // decisions about that one file, imports included, or a note
        // written in another tool and saved here would be invisible
        // until something unrelated ran a full pass.
        let (_dir, store, _rel) = captured("resident");
        // Local-only by the person's own mark (ADR 0007, 2026-10-22): the one
        // case in which a file the kernel did not write stays a candidate.
        std::fs::write(store.vault().join(".gilune-local"), "").expect("local marker");
        std::fs::write(store.vault().join("Theirs.md"), "their words").expect("write");
        let before = cursor_now(&store);
        assert_eq!(store.reconcile_path("Theirs.md").expect("one file").imports, 1);
        std::fs::write(store.vault().join("Theirs.md"), "their newer words").expect("edit");
        store.reconcile_path("Theirs.md").expect("one file");
        std::fs::remove_file(store.vault().join("Theirs.md")).expect("delete");
        store.reconcile_path("Theirs.md").expect("one file");
        assert_eq!(
            page(&store, &before),
            vec![
                (ChangeKind::Imported, "Theirs.md".to_string(), ChangeCause::Reconcile),
                (ChangeKind::Modified, "Theirs.md".to_string(), ChangeCause::ExternalEdit),
                (ChangeKind::Removed, "Theirs.md".to_string(), ChangeCause::Reconcile),
            ]
        );
        // A path the vault never held is not announced as having gone.
        let after = cursor_now(&store);
        store.reconcile_path("Never/existed.md").expect("one file");
        assert!(page(&store, &after).is_empty());
    }

    #[test]
    fn a_projection_that_loses_its_identity_is_one_quarantined_change() {
        // Deliberately a file the kernel *had* projected: the
        // second-order case, where a path already in the feed as
        // `Created` leaves the kernel's hands.
        let (_dir, store, rel) = captured("innocent text");
        let after_capture = cursor_now(&store);
        std::fs::write(store.vault().join(&rel), "---\nno colon here\n---\nbody").expect("mangle");

        assert_eq!(store.reconcile().expect("reconcile").quarantined, 1);
        assert_eq!(
            page(&store, &after_capture),
            vec![(ChangeKind::Quarantined, rel, ChangeCause::Reconcile)]
        );
        store.reconcile().expect("again");
        assert_eq!(page(&store, &after_capture).len(), 1, "quarantine is said once");
    }

    #[test]
    fn a_file_deleted_and_made_again_under_the_same_name_is_removed_and_then_created() {
        // The second-order case for the removal guard: it suppresses a
        // second notice of the *same* disappearance, and must never
        // suppress a real one.
        let (_dir, store, rel) = captured("here, then not, then here");
        let after_capture = cursor_now(&store);
        std::fs::remove_file(store.vault().join(&rel)).expect("delete");
        store.reconcile().expect("reconcile");
        let (proposal_id, _) = store.open_proposals().expect("proposals")[0].clone();

        // Rejecting the proposal is how a file comes back.
        store.resolve_tombstone(&proposal_id, false).expect("reject");
        assert_eq!(store.project().expect("project").written, 1);
        assert_eq!(
            page(&store, &after_capture),
            vec![
                (ChangeKind::Removed, rel.clone(), ChangeCause::Reconcile),
                (ChangeKind::Created, rel, ChangeCause::Capture),
            ],
            "a file that was not there and now is has been created, not modified"
        );
    }

    #[test]
    fn a_second_process_writing_the_same_vault_is_seen_by_the_first_reader() {
        // Multi-instance is supported use (owner decision 2026-08-31),
        // and a tick raised in one process could never reach another.
        // The feed can, because it is in the vault.
        let dir = tempfile::tempdir().expect("tempdir");
        let reader = Store::open(dir.path()).expect("open");
        let cursor = reader.change_origin().expect("origin");

        {
            let writer = Store::open(dir.path()).expect("a second handle on one vault");
            writer.capture_bytes("txt", b"written by somebody else").expect("capture");
            writer.project().expect("project");
        }

        let seen = page(&reader, &cursor);
        assert_eq!(seen.len(), 1, "{seen:?}");
        assert_eq!(seen[0].0, ChangeKind::Created);
        assert_eq!(seen[0].2, ChangeCause::Capture);
    }

    #[test]
    fn every_mutation_path_together_leaves_a_dense_gap_free_sequence() {
        // ADR 0020 § 8.2, over the paths of § 8.1 in one vault: the
        // numbering is the contract, and a consumer that saw a gap
        // could not tell it from a row it had missed.
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store
            .register_plugin(&crate::processor::fake_transcriber_descriptor())
            .expect("processor plugin");

        let voice = store.capture_bytes("wav", &[9u8; 128]).expect("capture");
        let typed = store.capture_bytes("txt", b"typed").expect("capture");
        store.project().expect("project");
        store
            .run_pending_jobs(&[&crate::processor::FakeTranscriber], ids::now_ms(), 30_000)
            .expect("work");
        store.project().expect("project");

        let typed_rel = typed.rel_path.replace(".txt", ".md");
        let edited = read_rel(&store, &typed_rel).replace("typed", "typed, then edited");
        std::fs::write(store.vault().join(&typed_rel), &edited).expect("edit");
        store.reconcile().expect("reconcile");
        store.project().expect("project");

        std::fs::write(store.vault().join("Foreign.md"), "not ours").expect("foreign");
        std::fs::write(store.vault().join("Broken.md"), "---\nbroken\n---\n").expect("broken");
        let voice_rel = voice.rel_path.replace(".wav", ".md");
        std::fs::create_dir_all(store.vault().join("Moved")).expect("mkdir");
        std::fs::rename(store.vault().join(&voice_rel), store.vault().join("Moved/voice.md"))
            .expect("rename");
        store.reconcile().expect("reconcile");

        std::fs::remove_file(store.vault().join("Moved/voice.md")).expect("delete");
        store.reconcile().expect("reconcile");

        let page =
            store.changes_since(&store.change_origin().expect("origin"), 1000).expect("changes");
        assert!(page.complete);
        assert!(page.changes.len() >= 8, "{:?}", page.changes);
        let seqs: Vec<i64> = page.changes.iter().map(|c| c.seq).collect();
        assert_eq!(
            seqs,
            (1..=seqs.len() as i64).collect::<Vec<_>>(),
            "the sequence is dense across every path there is"
        );
        assert_eq!(page.next.seq, *seqs.last().expect("rows"));
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
    fn a_keyless_tombstone_keeps_a_file_a_remote_revision_has_overtaken() {
        // The adversarial case for the pre-key fallback, and the one the
        // old proxy got wrong. Nobody here touched the file, so "did the
        // kernel write these bytes" answers yes; the projected revision
        // came in from another device, so its `origin` is that device's
        // and not `external-edit` — and the old rule read that as "safe to
        // delete". But a *second* remote revision has landed since the
        // file was written, and a tombstone that names no heads cannot
        // have seen it. Deleting the file there throws away words that
        // arrived after the deleter last looked.
        let (_dir, store, rel) = captured("first words");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        let seen = head_of(&store, &object_id);

        // R1 from elsewhere, and this device projects it: the file on disk
        // is now the kernel's own rendering of a revision it received.
        from_elsewhere(
            &store,
            1,
            crate::operations::OBJECT_REVISED,
            &object_id,
            serde_json::json!({
                "object_id": object_id,
                "revision": {
                    "revision_id": "rev-one",
                    "payload": serde_json::json!({ "text": "second words" }).to_string(),
                    "origin": "capture",
                    "created_ms": 1_700_000_000_500i64,
                },
                "supersedes": [seen],
            }),
        );
        store.project().expect("project R1");
        assert!(read_rel(&store, &rel).contains("second words"));

        // R2 lands after the projection was written. Nothing rewrites the
        // file yet — the tombstone arrives in the same pull.
        from_elsewhere(
            &store,
            2,
            crate::operations::OBJECT_REVISED,
            &object_id,
            serde_json::json!({
                "object_id": object_id,
                "revision": {
                    "revision_id": "rev-two",
                    "payload": serde_json::json!({ "text": "third words" }).to_string(),
                    "origin": "capture",
                    "created_ms": 1_700_000_001_000i64,
                },
                "supersedes": ["rev-one"],
            }),
        );
        // A deleter running an older build: it names nothing at all, so
        // there is no way to know what it had in front of it.
        from_elsewhere(&store, 3, crate::operations::TOMBSTONE, &object_id, serde_json::json!({}));

        assert_eq!(
            store.project().expect("project").removed,
            0,
            "a keyless tombstone deleted a file that a later revision had already \
             overtaken — the deleter cannot have seen what arrived after it"
        );
        assert!(store.vault().join(&rel).exists());
    }

    #[test]
    fn a_keyless_tombstone_still_withdraws_a_file_nothing_has_overtaken() {
        // The other half, and the reason the rule is a comparison rather
        // than a refusal: an ordinary delete from an older build, against a
        // note nothing has touched since it was projected, must still take
        // the file away. Otherwise every pre-key tombstone leaves an
        // orphan for ever, which is the defect the named heads were added
        // to close in the first place.
        let (_dir, store, rel) = captured("nothing has happened to this");
        let object_id: String = store
            .conn()
            .query_row("SELECT object_id FROM projections", [], |row| row.get(0))
            .expect("the projection");
        tombstone_from_elsewhere(&store, &object_id);

        assert_eq!(store.project().expect("project").removed, 1);
        assert!(!store.vault().join(&rel).exists());
        assert!(alert_kinds(&store).is_empty(), "and it is not reported as a decision");
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
        assert!(store.vault().join(&rel).exists(), "and nothing on disk was touched to achieve it");
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
        store.apply_operation("test", &bytes, crate::limits::Limits::DEFAULT).expect("apply");

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

    // ------------------------------ reconcile stops reading (§ 2.5, K2)

    /// What the projection recorded about a file: its content hash and
    /// the stat facts the short-cut compares against.
    fn record_of(store: &Store, rel: &str) -> (String, i64, i64, i64) {
        store
            .conn()
            .query_row(
                "SELECT content_sha256, size, mtime_ns, recorded_ns
                 FROM projections WHERE rel_path = ?1",
                [rel],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .expect("the projection record")
    }

    fn set_mtime(path: &std::path::Path, ns: i64) {
        let file = std::fs::OpenOptions::new().write(true).open(path).expect("open");
        file.set_modified(std::time::UNIX_EPOCH + std::time::Duration::from_nanos(ns as u64))
            .expect("set the file's timestamp");
        file.sync_all().expect("flush");
    }

    #[test]
    fn an_unchanged_vault_reads_no_files_at_all() {
        // ADR 0020 § 8.6. Reconciliation used to SHA-256 every projected
        // file on every pass to find external edits; a vault of a
        // thousand notes paid a thousand reads to be told that nothing
        // had happened, per pass, per consumer.
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        for n in 0..1000 {
            store.capture_bytes("txt", format!("note number {n}").as_bytes()).expect("capture");
        }
        store.project().expect("project");
        // One settling pass: a file written and recorded inside the same
        // clock tick is racy by git's rule and is hashed until the
        // record is re-armed with a later moment. That is the rule doing
        // its job, and it happens once.
        store.reconcile().expect("settle");

        probe::reset_file_reads();
        let summary = store.reconcile().expect("reconcile an unchanged vault");
        assert_eq!(summary, ReconcileSummary::default(), "nothing happened, and it says so");
        assert_eq!(
            probe::file_reads(),
            0,
            "an unchanged vault is answered from a stat per file and nothing else"
        );
    }

    #[test]
    fn a_file_rewritten_inside_the_timestamps_resolution_is_hashed_anyway() {
        // Git's racy-file safeguard, and the reason the record keeps the
        // moment it was written. A write that lands in the same instant
        // as the record is indistinguishable from it by size and mtime,
        // so the rule that stops at a stat has to make an exception for
        // exactly that case or it loses the edit for ever.
        let (_dir, store, rel) = captured("the same length here");
        store.reconcile().expect("settle");
        let path = store.vault().join(&rel);
        let (_, size, _, recorded_ns) = record_of(&store, &rel);

        // The file was written at the very moment the record was: put
        // both the record and the file at that instant.
        store
            .conn()
            .execute("UPDATE projections SET mtime_ns = recorded_ns WHERE rel_path = ?1", [&rel])
            .expect("stage the race");
        let edited = read_rel(&store, &rel).replace("the same length here", "a different sentence");
        assert_eq!(edited.len() as i64, size, "the rewrite must be the same length");
        std::fs::write(&path, &edited).expect("rewrite inside the same instant");
        set_mtime(&path, recorded_ns);

        assert_eq!(
            store.reconcile().expect("reconcile").edits,
            1,
            "a file whose timestamp is not older than its record is hashed regardless"
        );
    }

    #[test]
    fn verify_hashes_every_projected_file_and_re_arms_what_the_stat_rule_missed() {
        // The other half of § 2.5, and the residual stated honestly: a
        // rewrite that keeps the length and lands with an older
        // timestamp is invisible to a stat. Hashing everything is what
        // answers it, which is why it is an action a person takes rather
        // than a cost every launch pays — and reporting alone would be a
        // diagnosis with no treatment, so the record is re-armed too.
        let (_dir, store, rel) = captured("the same length here");
        store.reconcile().expect("settle");
        let path = store.vault().join(&rel);
        let (_, _, mtime_ns, _) = record_of(&store, &rel);

        let edited = read_rel(&store, &rel).replace("the same length here", "a different sentence");
        std::fs::write(&path, &edited).expect("rewrite");
        set_mtime(&path, mtime_ns);

        assert_eq!(
            store.reconcile().expect("reconcile").edits,
            0,
            "by design: size and timestamp both agree, so nothing is read"
        );

        probe::reset_file_reads();
        let problems = store.verify().expect("verify");
        assert!(probe::file_reads() >= 1, "verify hashes what reconciliation stats");
        assert_eq!(problems.len(), 1, "{problems:?}");
        assert!(problems[0].contains(&rel));

        assert_eq!(
            store.reconcile().expect("reconcile").edits,
            1,
            "and the record it re-armed is read properly on the next pass"
        );
        assert!(store.verify().expect("verify").is_empty(), "the condition clears");
    }

    #[test]
    fn an_ordinary_unreconciled_edit_is_not_reported_as_an_integrity_problem() {
        // The second-order case for verify: a file the record already
        // says is out of date is reconciliation's ordinary work, and
        // calling it a problem would make every unsaved external edit
        // look like damage.
        let (_dir, store, rel) = captured("edited but not yet taken in");
        let text = read_rel(&store, &rel);
        std::fs::write(store.vault().join(&rel), text + "\nand more\n").expect("edit");
        assert!(store.verify().expect("verify").is_empty());
    }

    #[test]
    fn a_reconcile_path_on_a_file_nothing_happened_to_records_nothing() {
        // The second-order case for the editor's call: a shell that
        // calls it after every save, including the saves that wrote the
        // same bytes back, must not fill the feed with nothing.
        let (_dir, store, rel) = captured("unchanged by the save");
        let settled = cursor_now(&store);
        let summary = store.reconcile_path(&rel).expect("reconcile one file");
        assert_eq!(summary, ReconcileSummary::default());
        assert!(page(&store, &settled).is_empty());
    }

    #[test]
    fn a_named_save_detects_changed_bytes_with_unchanged_size_and_timestamp() {
        for managed in [false, true] {
            let dir = tempfile::tempdir().expect("tempdir");
            let store = Store::open(dir.path()).expect("open");
            let rel = if managed {
                store.capture_bytes("txt", b"before").expect("capture");
                store.project().expect("project");
                store.conn().query_row("SELECT rel_path FROM projections", [], |r| r.get::<_, String>(0)).expect("path")
            } else {
                // A local-only vault keeps the file a candidate (ADR 0007,
                // 2026-10-22); anywhere else it would be adopted.
                std::fs::write(store.vault().join(".gilune-local"), "").expect("local marker");
                std::fs::write(store.vault().join("external.md"), "before\n").expect("write");
                "external.md".to_string()
            };
            store.reconcile().expect("settle");
            let path = store.vault().join(&rel);
            let before = std::fs::read_to_string(&path).expect("read");
            let stamp = stat_of(&path).expect("stat");
            let cursor = cursor_now(&store);
            std::fs::write(&path, before.replace("before", "edited")).expect("edit");
            set_mtime(&path, stamp.mtime_ns);
            assert_eq!(stat_of(&path), Some(stamp), "identical metadata cannot prove identical content");

            let summary = store.reconcile_path(&rel).expect("named save");
            assert_eq!(summary.edits, i64::from(managed), "managed={managed}");
            assert_eq!(summary.imports, 0, "an edit is not a new import offer");
            let changes = page(&store, &cursor);
            assert_eq!(changes.len(), 1, "the save reaches the index feed; managed={managed}");
            assert_eq!(changes[0].0, ChangeKind::Modified);
            assert_eq!(store.reconcile_path(&rel).expect("same bytes again"), ReconcileSummary::default());
        }
    }

    #[test]
    fn a_reconcile_path_answers_a_vanished_file_the_way_the_full_pass_does() {
        let (_dir, store, rel) = captured("about to go");
        let settled = cursor_now(&store);
        std::fs::remove_file(store.vault().join(&rel)).expect("delete");

        let summary = store.reconcile_path(&rel).expect("reconcile one file");
        assert_eq!(summary.tombstone_proposals, 1);
        assert_eq!(
            page(&store, &settled),
            vec![(ChangeKind::Removed, rel, ChangeCause::Reconcile)]
        );
        // And the full pass afterwards neither proposes twice nor says
        // the file left twice.
        assert_eq!(store.reconcile().expect("full pass").tombstone_proposals, 0);
        assert_eq!(page(&store, &settled).len(), 1);
    }

    #[test]
    fn a_reconcile_path_refuses_a_path_that_is_not_a_projected_file_in_this_vault() {
        let (_dir, store, _rel) = captured("resident");
        for outside in ["../elsewhere.md", "C:/windows/system32/note.md", "C:note.md",
            "z:\\outside.md", "/tmp/outside.md", "//server/share/note.md", ".gilune/local/x.md"] {
            assert!(
                matches!(store.reconcile_path(outside), Err(KernelError::VaultUnusable { .. })),
                "{outside} was not refused"
            );
        }
        assert!(matches!(
            store.reconcile_path("Captures/recording.wav"),
            Err(KernelError::VaultUnusable { .. })
        ));
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
        // Local-only by the person's own mark (ADR 0007, 2026-10-22): the one
        // case in which a file the kernel did not write stays a candidate.
        std::fs::write(store.vault().join(".gilune-local"), "").expect("local marker");
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
        assert_eq!(std::fs::read(dir.path().join(&receipt.rel_path)).expect("blob"), vec![5u8; 32]);
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
            let mut stmt =
                store.conn().prepare("SELECT kind FROM operations ORDER BY op_seq").expect("stmt");
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
