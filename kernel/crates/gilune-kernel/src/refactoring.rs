//! Reviewed, durable document changes. Domain providers propose text; this
//! service binds approval, history, filesystem recovery and retry identity.
use crate::{
    changes::{self, Change, ChangeCause, ChangeKind},
    documents::{self, DocumentState, TextEdit},
    fs, ids,
    store::Store,
    KernelError, Result,
};
use rusqlite::params;
use std::{
    fs::OpenOptions,
    io::Write,
    path::{Path, PathBuf},
};

#[derive(Debug, Clone)]
pub struct RefactoringPlan {
    pub id: String,
    pub provider: String,
    pub provider_version: String,
    pub configuration: String,
    pub label: String,
    pub state: String,
    pub review_token: Option<String>,
    pub total: u64,
    pub applied: u64,
}
#[derive(Debug, Clone)]
pub struct RefactoringEntry {
    pub id: String,
    pub plan_id: String,
    pub rel_path: String,
    pub expected_version: String,
    pub before_text: String,
    pub after_text: String,
    pub published_text: Option<String>,
    pub object_id: Option<String>,
    pub expected_revision: Option<String>,
    pub payload: Option<String>,
    pub state: String,
    pub detail: String,
}

fn invalid(detail: impl Into<String>) -> KernelError {
    KernelError::DocumentInvalid {
        detail: detail.into(),
    }
}
fn io(path: &Path, source: std::io::Error) -> KernelError {
    KernelError::Io {
        path: path.into(),
        source,
    }
}
fn read(path: &Path) -> Result<String> {
    std::fs::read_to_string(path).map_err(|e| io(path, e))
}
fn write_new(path: &Path, text: &str) -> Result<()> {
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)
        .map_err(|e| io(path, e))?;
    file.write_all(text.as_bytes()).map_err(|e| io(path, e))?;
    file.sync_all().map_err(|e| io(path, e))?;
    Ok(())
}

impl Store {
    pub fn create_refactoring_plan(
        &self,
        provider: &str,
        version: &str,
        configuration: &str,
        label: &str,
    ) -> Result<RefactoringPlan> {
        if provider.is_empty()
            || version.is_empty()
            || label.is_empty()
            || configuration.len() > 1024 * 1024
        {
            return Err(invalid("invalid provider, version, label or configuration"));
        }
        let id = ids::new_id();
        self.conn().execute(
            "INSERT INTO refactoring_plans VALUES (?1,?2,?3,?4,?5,'discovering',NULL,?6)",
            params![id, provider, version, configuration, label, ids::now_ms()],
        )?;
        self.refactoring_plan(&id)
    }

    pub fn refactoring_plan(&self, id: &str) -> Result<RefactoringPlan> {
        Ok(self.conn().query_row("SELECT plan_id,provider,provider_version,configuration,label,state,review_token,
            (SELECT count(*) FROM refactoring_entries e WHERE e.plan_id=p.plan_id),
            (SELECT count(*) FROM refactoring_entries e WHERE e.plan_id=p.plan_id AND e.state='applied')
            FROM refactoring_plans p WHERE plan_id=?1", [id], |r| Ok(RefactoringPlan {
                id:r.get(0)?,provider:r.get(1)?,provider_version:r.get(2)?,configuration:r.get(3)?,label:r.get(4)?,
                state:r.get(5)?,review_token:r.get(6)?,total:r.get(7)?,applied:r.get(8)? }))?)
    }

    pub fn refactoring_plans(&self) -> Result<Vec<RefactoringPlan>> {
        let mut stmt = self.conn().prepare(
            "SELECT plan_id FROM refactoring_plans ORDER BY created_ms DESC,plan_id DESC",
        )?;
        let ids = stmt
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        ids.iter().map(|id| self.refactoring_plan(id)).collect()
    }

    pub fn refactoring_entries(
        &self,
        plan: &str,
        offset: u64,
        limit: u32,
    ) -> Result<Vec<RefactoringEntry>> {
        if limit == 0 || limit > 100 {
            return Err(invalid("entry page size must be 1..100"));
        }
        let mut stmt = self.conn().prepare(
            "SELECT entry_id,plan_id,rel_path,expected_version,before_text,after_text,
            published_text,object_id,expected_revision,payload,state,detail FROM refactoring_entries
            WHERE plan_id=?1 ORDER BY entry_id LIMIT ?2 OFFSET ?3",
        )?;
        let entries = stmt
            .query_map(params![plan, limit, offset], entry_row)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(entries)
    }

    pub fn add_refactoring_entry(
        &self,
        plan: &str,
        path: &str,
        expected: &str,
        edits: &[TextEdit],
    ) -> Result<RefactoringEntry> {
        self.write_txn(|| {
            if self.refactoring_plan(plan)?.state != "discovering" { return Err(invalid("reviewed plans are immutable")); }
            let prepared = self.prepare_document_change(path, expected, edits)?;
            if prepared.before.content.text() == prepared.after.text() { return Err(invalid("change has no edits")); }
            let id = ids::new_id();
            let origin = format!("refactoring:{plan}");
            let (payload, published) = if let Some(object) = &prepared.before.source.object_id {
                let (payload, text) = self.refactoring_projection(object, &id, prepared.after.text(), &origin)?;
                (Some(payload), text)
            } else { (None, prepared.after.text().into()) };
            self.conn().execute("INSERT INTO refactoring_entries
                (entry_id,plan_id,rel_path,expected_version,before_text,after_text,published_text,object_id,expected_revision,payload,state)
                VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,'pending')",
                params![id,plan,prepared.before.source.rel_path,expected,prepared.before.content.text(),prepared.after.text(),
                    published,prepared.before.source.object_id,prepared.before.source.revision_id,payload])?;
            self.refactoring_entry(&id)
        })
    }

    pub fn add_refactoring_issue(&self, plan: &str, path: &str, detail: &str) -> Result<()> {
        self.write_txn(|| {
            if self.refactoring_plan(plan)?.state != "discovering" { return Err(invalid("reviewed plans are immutable")); }
            self.conn().execute("INSERT INTO refactoring_entries (entry_id,plan_id,rel_path,expected_version,before_text,after_text,state,detail)
                VALUES (?1,?2,?3,'','','','issue',?4)", params![ids::new_id(),plan,path,detail])?;
            Ok(())
        })
    }

    pub fn seal_refactoring_plan(&self, plan: &str) -> Result<RefactoringPlan> {
        self.write_txn(|| {
            let existing = self.refactoring_plan(plan)?;
            if existing.state != "discovering" { return Ok(existing); }
            let mut stmt = self.conn().prepare("SELECT entry_id,expected_version,after_text FROM refactoring_entries WHERE plan_id=?1 ORDER BY entry_id")?;
            let mut evidence = format!("{plan}:{}:{}:{}",existing.provider,existing.provider_version,existing.configuration);
            for row in stmt.query_map([plan], |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?)))? {
                let (id, version, after) = row?;
                evidence.push_str(&format!(":{id}:{version}:{}",ids::sha256_hex(after.as_bytes())));
            }
            self.conn().execute("UPDATE refactoring_plans SET state='preview',review_token=?2 WHERE plan_id=?1",
                params![plan,ids::sha256_hex(evidence.as_bytes())])?;
            self.refactoring_plan(plan)
        })
    }

    /// Undo is another reviewable plan. Only a uniquely anchored inverse is
    /// proposed; edits inside that evidence become explicit conflicts.
    pub fn prepare_refactoring_undo(&self, original: &str) -> Result<RefactoringPlan> {
        let previous = self.refactoring_plan(original)?;
        let plan = self.create_refactoring_plan(
            "memory:refactoring.undo",
            "1",
            original,
            &format!("Undo {}", previous.label),
        )?;
        let mut offset = 0;
        loop {
            let entries = self.refactoring_entries(original, offset, 100)?;
            if entries.is_empty() {
                break;
            }
            offset += entries.len() as u64;
            for entry in entries.into_iter().filter(|e| e.state == "applied") {
                let result = (|| {
                    let current = self.document_snapshot(&entry.rel_path)?;
                    let published = entry
                        .published_text
                        .as_deref()
                        .ok_or_else(|| invalid("missing undo evidence"))?;
                    let restored = if entry.object_id.is_some() {
                        crate::vault::with_current_projection_metadata(
                            &entry.before_text,
                            published,
                        )?
                    } else {
                        entry.before_text.clone()
                    };
                    let edits = inverse_edits(published, &restored, current.content.text())?;
                    self.add_refactoring_entry(
                        &plan.id,
                        &entry.rel_path,
                        &current.source.version,
                        &edits,
                    )
                })();
                if let Err(error) = result {
                    self.add_refactoring_issue(&plan.id, &entry.rel_path, &error.to_string())?;
                }
            }
        }
        self.seal_refactoring_plan(&plan.id)
    }

    /// Cancellation only stops future writes. It never hides completed entries.
    pub fn set_refactoring_cancelled(&self, plan: &str, cancelled: bool) -> Result<()> {
        self.conn().execute("UPDATE refactoring_plans SET state=CASE WHEN ?2 THEN 'cancelled'
            WHEN NOT EXISTS (SELECT 1 FROM refactoring_entries WHERE plan_id=?1 AND state<>'applied') THEN 'completed'
            WHEN EXISTS (SELECT 1 FROM refactoring_entries WHERE plan_id=?1 AND state<>'pending') THEN 'partial'
            ELSE 'preview' END WHERE plan_id=?1 AND review_token IS NOT NULL", params![plan,cancelled])?;
        Ok(())
    }

    pub fn apply_refactoring_entry(
        &self,
        plan: &str,
        token: &str,
        entry: &str,
    ) -> Result<RefactoringEntry> {
        let _lease = self.refactoring_lease()?;
        let review = self.refactoring_plan(plan)?;
        if review.review_token.as_deref() != Some(token)
            || review.state == "cancelled"
            || review.state == "discovering"
        {
            return Err(invalid("apply requires this plan's current reviewed token"));
        }
        let candidate = self.refactoring_entry(entry)?;
        if candidate.plan_id != plan {
            return Err(invalid("entry belongs to another plan"));
        }
        if candidate.state == "applied" {
            self.maintain_index();
            return Ok(candidate);
        }
        if candidate.state != "pending" {
            return Err(invalid("entry needs recovery or a new preview"));
        }
        let recovery = self.refactoring_recovery_dir()?;
        let captured = recovery.join(format!("{entry}.original"));
        let prepared = recovery.join(format!("{entry}.prepared"));
        let (_, target) = documents::document_path(self.vault(), &candidate.rel_path)?;
        let published = candidate
            .published_text
            .as_deref()
            .ok_or_else(|| invalid("missing prepared publication"))?;
        // The immutable journal has both before and proposed bytes before any
        // filesystem mutation. The displaced inode is retained as well.
        let permissions = std::fs::metadata(&target)
            .map_err(|e| io(&target, e))?
            .permissions();
        if permissions.readonly() {
            return Err(invalid("document is read-only"));
        }
        if !prepared.exists() {
            write_new(&prepared, published)?;
            std::fs::set_permissions(&prepared, permissions).map_err(|e| io(&prepared, e))?;
            fs::fsync_dir(&recovery)?;
        }
        if read(&prepared)? != published {
            return Err(invalid("prepared publication differs from review"));
        }
        self.conn().execute(
            "UPDATE refactoring_entries SET state='applying' WHERE entry_id=?1",
            [entry],
        )?;
        crate::crashpoints::crashpoint("refactor-journal");
        let result = self.write_txn(|| {
            let fresh = self.document_snapshot(&candidate.rel_path)?;
            if fresh.source.version != candidate.expected_version {
                return Err(KernelError::DocumentChanged);
            }
            if !matches!(
                fresh.source.state,
                DocumentState::Managed | DocumentState::Unmanaged
            ) {
                return Err(invalid("document requires resolution"));
            }
            if captured.exists() {
                return Err(invalid("recovery bytes already exist"));
            }
            #[cfg(test)]
            tests::edit_at("before-displacement", &target, &captured);
            std::fs::rename(&target, &captured).map_err(|e| io(&target, e))?;
            fs::fsync_dir(target.parent().ok_or_else(|| invalid("missing parent"))?)?;
            fs::fsync_dir(&recovery)?;
            crate::crashpoints::crashpoint("refactor-displaced");
            #[cfg(test)]
            tests::edit_at("after-displacement", &target, &captured);
            if read(&captured)? != candidate.before_text {
                return Err(KernelError::DocumentChanged);
            }
            if !fs::publish_new_file(&prepared, &target)? {
                return Err(KernelError::DocumentChanged);
            }
            fs::fsync_file(&target)?;
            fs::fsync_dir(target.parent().ok_or_else(|| invalid("missing parent"))?)?;
            crate::crashpoints::crashpoint("refactor-published");
            #[cfg(test)]
            tests::edit_at("after-publication", &target, &captured);
            if read(&captured)? != candidate.before_text || read(&target)? != published {
                return Err(KernelError::DocumentChanged);
            }
            if let (Some(object), Some(payload)) = (&candidate.object_id, &candidate.payload) {
                self.record_refactoring_revision(
                    object,
                    entry,
                    payload,
                    &format!("refactoring:{plan}"),
                    &candidate.rel_path,
                    published,
                )?;
            } else {
                changes::record(
                    self.conn(),
                    Change {
                        kind: ChangeKind::Modified,
                        rel_path: &candidate.rel_path,
                        previous_rel_path: None,
                        object_id: None,
                        revision_id: None,
                        cause: ChangeCause::LocalEdit,
                    },
                )?;
            }
            self.conn().execute(
                "UPDATE refactoring_entries SET state='applied',detail='' WHERE entry_id=?1",
                [entry],
            )?;
            crate::crashpoints::crashpoint("refactor-revision");
            Ok(())
        });
        if let Err(error) = result {
            self.recover_refactoring_or_record_failure(&candidate)?;
            self.conn().execute(
                "UPDATE refactoring_entries SET detail=?2 || ' ' || detail WHERE entry_id=?1",
                params![entry, error.to_string()],
            )?;
        } else {
            crate::crashpoints::crashpoint("refactor-committed");
            self.maintain_index();
        }
        self.conn().execute("UPDATE refactoring_plans SET state=CASE WHEN EXISTS
            (SELECT 1 FROM refactoring_entries WHERE plan_id=?1 AND state<>'applied') THEN 'partial' ELSE 'completed' END
            WHERE plan_id=?1 AND state<>'cancelled'",[plan])?;
        self.refactoring_entry(entry)
    }

    /// Recovery never calls a plugin or replays a planned edit. A publication
    /// without its revision commit is rolled back only while its bytes still
    /// match. Concurrent replacement files are preserved and reported.
    pub fn recover_refactorings(&self) -> Result<()> {
        let _lease = match self.refactoring_lease() {
            Ok(lease) => lease,
            Err(KernelError::DocumentUnavailable { .. }) => return Ok(()),
            Err(error) => return Err(error),
        };
        let mut stmt = self
            .conn()
            .prepare("SELECT entry_id FROM refactoring_entries WHERE state='applying'")?;
        let entries = stmt
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        drop(stmt);
        for id in entries {
            let entry = self.refactoring_entry(&id)?;
            self.recover_refactoring_or_record_failure(&entry)?;
            self.set_refactoring_cancelled(&entry.plan_id, false)?;
        }
        Ok(())
    }

    fn recover_refactoring_or_record_failure(&self, entry: &RefactoringEntry) -> Result<()> {
        if let Err(error) = self.recover_refactoring_entry(entry) {
            self.conn().execute(
                "UPDATE refactoring_entries SET state='conflict',detail=?2 WHERE entry_id=?1",
                params![
                    entry.id,
                    format!("Recovery needs resolution; retained copies remain available. {error}")
                ],
            )?;
        }
        Ok(())
    }

    fn recover_refactoring_entry(&self, entry: &RefactoringEntry) -> Result<()> {
        let recovery = self.refactoring_recovery_dir()?;
        let captured = recovery.join(format!("{}.original", entry.id));
        // The path may be missing precisely because publication was interrupted.
        let target = checked_recovery_target(self.vault(), &entry.rel_path)?;
        let mut detail = "Interrupted before publication; review again.".to_string();
        if captured.exists() {
            if target.exists() && read(&target).ok().as_deref() == entry.published_text.as_deref() {
                let displaced =
                    recovery.join(format!("{}.uncommitted-{}", entry.id, ids::new_id()));
                std::fs::rename(&target, &displaced).map_err(|e| io(&target, e))?;
                fs::fsync_dir(&recovery)?;
                // A writer can change the target between comparison and rename.
                // Put that newer inode back if the name is still vacant.
                if read(&displaced).ok().as_deref() != entry.published_text.as_deref() {
                    fs::publish_new_file(&displaced, &target)?;
                }
            }
            if !target.exists() {
                let restore = recovery.join(format!("{}.restore-{}", entry.id, ids::new_id()));
                write_new(&restore, &read(&captured)?)?;
                if fs::publish_new_file(&restore, &target)? {
                    fs::fsync_file(&target)?;
                    fs::fsync_dir(target.parent().ok_or_else(|| invalid("missing parent"))?)?;
                    detail="Interrupted change restored. Prepare a new preview; recovery bytes are retained.".into();
                }
            } else {
                detail="The document changed during publication. Current and displaced bytes are preserved; resolve before replanning.".into();
            }
        }
        self.conn().execute(
            "UPDATE refactoring_entries SET state='conflict',detail=?2 WHERE entry_id=?1",
            params![entry.id, detail],
        )?;
        Ok(())
    }

    /// Retained bytes are inspectable even after the originating plugin is gone.
    pub fn refactoring_recovery_copies(&self, entry: &str) -> Result<Vec<(String, String)>> {
        self.refactoring_entry(entry)?;
        let directory = self.refactoring_recovery_dir()?;
        let mut copies = Vec::new();
        for item in std::fs::read_dir(&directory).map_err(|e| io(&directory, e))? {
            let item = item.map_err(|e| io(&directory, e))?;
            let name = item.file_name().to_string_lossy().into_owned();
            if name.starts_with(&format!("{entry}."))
                && !name.ends_with(".prepared")
                && item.file_type().map_err(|e| io(&item.path(), e))?.is_file()
            {
                copies.push((name, read(&item.path())?));
            }
        }
        copies.sort_by(|a, b| a.0.cmp(&b.0));
        Ok(copies)
    }

    pub fn prepare_refactoring_recovery(&self, entry: &str, name: &str) -> Result<RefactoringPlan> {
        let original = self.refactoring_entry(entry)?;
        let retained = self
            .refactoring_recovery_copies(entry)?
            .into_iter()
            .find(|(key, _)| key == name)
            .ok_or_else(|| invalid("recovery copy not found"))?
            .1;
        let current = self.document_snapshot(&original.rel_path)?;
        let restored = if current.source.object_id.is_some() {
            crate::vault::with_current_projection_metadata(&retained, current.content.text())?
        } else {
            retained
        };
        let edits = inverse_edits(current.content.text(), &restored, current.content.text())?;
        let plan = self.create_refactoring_plan(
            "memory:refactoring.undo",
            "1",
            entry,
            &format!("Recover {}", original.rel_path),
        )?;
        if let Err(error) = self.add_refactoring_entry(
            &plan.id,
            &original.rel_path,
            &current.source.version,
            &edits,
        ) {
            self.add_refactoring_issue(&plan.id, &original.rel_path, &error.to_string())?;
        }
        self.seal_refactoring_plan(&plan.id)
    }

    fn refactoring_entry(&self, id: &str) -> Result<RefactoringEntry> {
        Ok(self.conn().query_row("SELECT entry_id,plan_id,rel_path,expected_version,before_text,after_text,
            published_text,object_id,expected_revision,payload,state,detail FROM refactoring_entries WHERE entry_id=?1",[id],entry_row)?)
    }
    fn refactoring_recovery_dir(&self) -> Result<PathBuf> {
        let dir = self.local_dir().join("refactoring-recovery");
        std::fs::create_dir_all(&dir).map_err(|e| io(&dir, e))?;
        fs::fsync_dir(&self.local_dir())?;
        Ok(dir)
    }
    fn refactoring_lease(&self) -> Result<std::fs::File> {
        let path = self.local_dir().join("refactoring.lock");
        let file = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(&path)
            .map_err(|e| io(&path, e))?;
        file.try_lock().map_err(|e| match e {
            std::fs::TryLockError::WouldBlock => KernelError::DocumentUnavailable {
                detail: "another refactoring is publishing a document".into(),
            },
            std::fs::TryLockError::Error(error) => io(&path, error),
        })?;
        Ok(file)
    }
}

fn entry_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<RefactoringEntry> {
    Ok(RefactoringEntry {
        id: r.get(0)?,
        plan_id: r.get(1)?,
        rel_path: r.get(2)?,
        expected_version: r.get(3)?,
        before_text: r.get(4)?,
        after_text: r.get(5)?,
        published_text: r.get(6)?,
        object_id: r.get(7)?,
        expected_revision: r.get(8)?,
        payload: r.get(9)?,
        state: r.get(10)?,
        detail: r.get(11)?,
    })
}

fn inverse_edit(published: &str, restored: &str, current: &str) -> Result<TextEdit> {
    let mut start = published
        .bytes()
        .zip(restored.bytes())
        .take_while(|(a, b)| a == b)
        .count();
    while !published.is_char_boundary(start) || !restored.is_char_boundary(start) {
        start -= 1;
    }
    let mut suffix = published[start..]
        .bytes()
        .rev()
        .zip(restored[start..].bytes().rev())
        .take_while(|(a, b)| a == b)
        .count();
    while !published.is_char_boundary(published.len() - suffix)
        || !restored.is_char_boundary(restored.len() - suffix)
    {
        suffix -= 1;
    }
    let end = published.len() - suffix;
    let mut anchor_start = start.saturating_sub(32);
    while !published.is_char_boundary(anchor_start) {
        anchor_start += 1;
    }
    let mut anchor_end = (end + 32).min(published.len());
    while !published.is_char_boundary(anchor_end) {
        anchor_end -= 1;
    }
    let anchor = &published[anchor_start..anchor_end];
    let matches: Vec<_> = current.match_indices(anchor).take(2).collect();
    if matches.len() != 1 {
        return Err(KernelError::DocumentChanged);
    }
    let at = matches[0].0 + start - anchor_start;
    Ok(TextEdit {
        range: documents::ByteSpan {
            start: at as u64,
            end: (at + end - start) as u64,
        },
        replacement: restored[start..restored.len() - suffix].into(),
    })
}

fn inverse_edits(published: &str, restored: &str, current: &str) -> Result<Vec<TextEdit>> {
    let before =
        documents::TextSnapshot::new(published.into()).map_err(|e| invalid(e.to_string()))?;
    let after =
        documents::TextSnapshot::new(restored.into()).map_err(|e| invalid(e.to_string()))?;
    let present =
        documents::TextSnapshot::new(current.into()).map_err(|e| invalid(e.to_string()))?;
    fn ordered(snapshot: &documents::TextSnapshot) -> Vec<documents::ByteSpan> {
        let mut ranges = snapshot.protected_ranges().to_vec();
        ranges.sort_by_key(|r| r.start);
        let mut merged: Vec<documents::ByteSpan> = Vec::new();
        for range in ranges {
            if let Some(last) = merged.last_mut().filter(|last| last.end >= range.start) {
                last.end = last.end.max(range.end);
            } else {
                merged.push(range);
            }
        }
        merged
    }
    let left = ordered(&before);
    let right = ordered(&after);
    let current_ranges = ordered(&present);
    if left.len() != right.len() || left.len() != current_ranges.len() {
        return Err(invalid("undo metadata structure changed"));
    }
    let mut from = 0;
    let mut to = 0;
    let mut current_from = 0;
    let mut edits = Vec::new();
    fn within(old: &str, new: &str, current: &str, start: usize, end: usize) -> Result<TextEdit> {
        let mut edit = inverse_edit(old, new, &current[start..end])?;
        edit.range.start += start as u64;
        edit.range.end += start as u64;
        Ok(edit)
    }
    for ((a, b), c) in left.iter().zip(&right).zip(&current_ranges) {
        if a.slice(published).map_err(|e| invalid(e.to_string()))?
            != b.slice(restored).map_err(|e| invalid(e.to_string()))?
        {
            return Err(invalid("undo cannot replace protected metadata"));
        }
        let old = &published[from..a.start as usize];
        let new = &restored[to..b.start as usize];
        if old != new {
            edits.push(within(old, new, current, current_from, c.start as usize)?);
        }
        from = a.end as usize;
        to = b.end as usize;
        current_from = c.end as usize;
    }
    if published[from..] != restored[to..] {
        edits.push(within(
            &published[from..],
            &restored[to..],
            current,
            current_from,
            current.len(),
        )?);
    }
    Ok(edits)
}

fn checked_recovery_target(vault: &Path, relative: &str) -> Result<PathBuf> {
    let root = std::fs::canonicalize(vault).map_err(|e| io(vault, e))?;
    let target = root.join(relative);
    let parent = target
        .parent()
        .ok_or_else(|| invalid("missing recovery parent"))?;
    let canonical = std::fs::canonicalize(parent).map_err(|e| io(parent, e))?;
    if !canonical.starts_with(&root) {
        return Err(invalid("recovery parent left the vault"));
    }
    let mut walk = root;
    for component in Path::new(relative).components() {
        if !matches!(component, std::path::Component::Normal(_)) {
            return Err(invalid("invalid recovery path"));
        }
        walk.push(component);
        match std::fs::symlink_metadata(&walk) {
            Ok(metadata) if metadata.file_type().is_symlink() => {
                return Err(invalid("recovery does not follow symbolic links"))
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound && walk == target => {}
            Err(error) => return Err(io(&walk, error)),
        }
    }
    Ok(target)
}

#[cfg(test)]
mod tests;
