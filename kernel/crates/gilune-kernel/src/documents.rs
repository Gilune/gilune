//! Source-qualified, read-only document snapshots and proposed text changes.
//! Search never calls this module. Reading a snapshot does not reconcile,
//! adopt, project or modify a file.

use crate::{probe, revisions, store::Store, KernelError, Result};
pub use gilune_document::{
    byte_to_utf16, casefold_nfc, source_properties, utf16_to_byte, BodyLine, ByteSpan,
    DocumentError, Location, MarkdownItem, MarkdownLayout, PropertyChange, PropertyField,
    PropertySheet, SourceHeader, TextEdit, TextSnapshot,
};
use rusqlite::OptionalExtension;
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DocumentState {
    Managed,
    Unmanaged,
    NeedsReconciliation,
    UnverifiedIdentity,
    Quarantined,
    Conflicted,
    Deleted,
    PendingDeletion,
}

#[derive(Debug, Clone)]
pub struct DocumentSource {
    pub document_key: String,
    /// Binds exact bytes, path and observed durable state. Not a write lease.
    pub version: String,
    pub rel_path: String,
    pub object_id: Option<String>,
    /// Present only when these exact bytes are the verified current projection.
    pub revision_id: Option<String>,
    pub state: DocumentState,
}

#[derive(Debug, Clone)]
pub struct DocumentSnapshot {
    pub source: DocumentSource,
    pub content: TextSnapshot,
}

#[derive(Debug, Clone)]
pub struct PreparedDocumentChange {
    pub before: DocumentSnapshot,
    /// Proposed content only: it has no committed revision or source version.
    pub after: TextSnapshot,
}

impl Store {
    pub fn document_snapshot(&self, rel_path: &str) -> Result<DocumentSnapshot> {
        let (rel_path, path) = document_path(self.vault(), rel_path)?;
        // One consistent database view. This does not lock an external editor;
        // a later apply must check the source again and preserve recovery bytes.
        let owned_transaction = if self.conn().is_autocommit() {
            Some(self.conn().unchecked_transaction()?)
        } else {
            None
        };
        let transaction = self.conn();
        let recorded: Option<(String, String, String, bool)> = transaction
            .query_row(
                "SELECT p.object_id, p.revision_id, p.content_sha256, o.deleted
             FROM projections p JOIN objects o ON o.object_id = p.object_id WHERE p.rel_path = ?1",
                [&rel_path],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .optional()?;
        let quarantined: bool = transaction.query_row(
            "SELECT EXISTS(SELECT 1 FROM quarantine WHERE rel_path = ?1)",
            [&rel_path],
            |row| row.get(0),
        )?;
        probe::file_read();
        let text =
            std::fs::read_to_string(&path).map_err(|source| KernelError::Io { path, source })?;
        let content = TextSnapshot::new(text).map_err(invalid)?;
        let mut object_id = None;
        let mut revision_id = None;
        let mut heads = Vec::new();
        let mut pending = false;
        let mut state = DocumentState::Unmanaged;
        if let Some((id, recorded_revision, digest, deleted)) = &recorded {
            heads = revisions::heads(transaction, id)?;
            pending = transaction.query_row(
                "SELECT EXISTS(SELECT 1 FROM proposals WHERE object_id = ?1 AND state = 'open')",
                [id],
                |row| row.get(0),
            )?;
            if content.metadata_string("gilune-id") == Some(id.as_str()) {
                object_id = Some(id.clone());
                state = if *deleted {
                    DocumentState::Deleted
                } else if pending {
                    DocumentState::PendingDeletion
                } else if heads.len() > 1 {
                    DocumentState::Conflicted
                } else if heads.first() == Some(recorded_revision)
                    && content.metadata_string("gilune-revision")
                        == Some(recorded_revision.as_str())
                    && content.version().strip_prefix("sha256:") == Some(digest.as_str())
                {
                    revision_id = Some(recorded_revision.clone());
                    DocumentState::Managed
                } else {
                    DocumentState::NeedsReconciliation
                };
            } else {
                state = DocumentState::UnverifiedIdentity;
            }
        } else if content.has_reserved_metadata() {
            state = DocumentState::UnverifiedIdentity;
        }
        if quarantined {
            state = DocumentState::Quarantined;
            revision_id = None;
        }
        let document_key = object_id
            .as_ref()
            .map_or_else(|| format!("path:{rel_path}"), |id| format!("object:{id}"));
        let version = gilune_document::content_version(&serde_json::to_string(&(
            1,
            self.change_epoch()?,
            &document_key,
            &rel_path,
            content.version(),
            format!("{state:?}"),
            &recorded,
            &heads,
            pending,
        ))?);
        if let Some(transaction) = owned_transaction {
            transaction.commit()?;
        }
        Ok(DocumentSnapshot {
            source: DocumentSource {
                document_key,
                version,
                rel_path,
                object_id,
                revision_id,
                state,
            },
            content,
        })
    }

    /// Re-read and validate a proposal; never writes or reserves a document.
    /// This is preparation for a future journalled apply, not an apply API.
    pub fn prepare_document_change(
        &self,
        rel_path: &str,
        expected_source_version: &str,
        edits: &[TextEdit],
    ) -> Result<PreparedDocumentChange> {
        let before = self.document_snapshot(rel_path)?;
        if before.source.version != expected_source_version {
            return Err(KernelError::DocumentChanged);
        }
        if !matches!(
            before.source.state,
            DocumentState::Managed | DocumentState::Unmanaged
        ) {
            return Err(KernelError::DocumentUnavailable {
                detail: format!(
                    "{} requires resolution: {:?}",
                    before.source.rel_path, before.source.state
                ),
            });
        }
        let after = before
            .content
            .propose(before.content.version(), edits)
            .map_err(invalid)?;
        Ok(PreparedDocumentChange { before, after })
    }
}

fn invalid(error: DocumentError) -> KernelError {
    KernelError::DocumentInvalid {
        detail: error.to_string(),
    }
}

pub(crate) fn document_path(vault: &Path, supplied: &str) -> Result<(String, PathBuf)> {
    let invalid = || KernelError::DocumentInvalid {
        detail: "expected a Markdown path inside the vault, outside .gilune".into(),
    };
    let portable = supplied.replace('\\', "/");
    let components: Vec<_> = portable.split('/').collect();
    if components.iter().any(|part| {
        part.is_empty()
            || *part == "."
            || *part == ".."
            || part.eq_ignore_ascii_case(".gilune")
            || part.contains([':', '\0'])
            || part.ends_with([' ', '.'])
    }) {
        return Err(invalid());
    }
    let relative = Path::new(&portable);
    if relative
        .components()
        .any(|part| !matches!(part, Component::Normal(_)))
        || !relative
            .extension()
            .is_some_and(|ext| ext.eq_ignore_ascii_case("md"))
    {
        return Err(invalid());
    }
    let root = std::fs::canonicalize(vault).map_err(|source| KernelError::Io {
        path: vault.into(),
        source,
    })?;
    let requested = root.join(relative);
    let canonical = std::fs::canonicalize(&requested).map_err(|source| KernelError::Io {
        path: requested.clone(),
        source,
    })?;
    let inside = canonical.strip_prefix(&root).map_err(|_| invalid())?;
    if inside
        .components()
        .any(|part| part.as_os_str().eq_ignore_ascii_case(".gilune"))
        || !canonical.is_file()
    {
        return Err(invalid());
    }
    // A path alias must not acquire a second source identity for the same file.
    // Refuse links/junctions here; the visible original path remains available.
    let mut walk = root;
    for component in relative.components() {
        walk.push(component);
        let metadata = std::fs::symlink_metadata(&walk).map_err(|source| KernelError::Io {
            path: walk.clone(),
            source,
        })?;
        if metadata.file_type().is_symlink() {
            return Err(invalid());
        }
    }
    Ok((portable, canonical))
}

#[cfg(test)]
mod tests;
