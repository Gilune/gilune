//! Asking a source's processors to run again — a person's request, never
//! the kernel's own idea (ADR 0029).
//!
//! A processing run is keyed `{processor_id}:{event_id}` (`processor.rs`),
//! which is what makes delivery exactly-once — and also what makes a
//! second answer impossible: the source's one `source-ready` event has
//! been answered, and answering it again is a duplicate by construction.
//! So asking again is a **new event** about the same subject, committed
//! with its jobs in one transaction exactly as a capture commits its
//! first. Every guarantee after that is the ordinary one: a lease, an
//! idempotent run, outputs that name the head they read.
//!
//! The kernel does not know what a transcriber is. It knows a source, its
//! preserved bytes, the processors subscribed to sources, and whether the
//! document's text is still what a processor last wrote — which is what a
//! shell needs before it offers to write over it.

use crate::capture::{SOURCE_READY_EVENT, SOURCE_TYPE};
use crate::processor::ORIGIN_PREFIX;
use crate::store::Store;
use crate::{ids, KernelError, Result};

/// The event-payload key that marks a request to run again. A processor
/// that skips a subject which already has an answer — the app-side
/// providers do, so a catch-up never overwrites — reads this as "the
/// person asked; answer anyway".
pub const AGAIN_KEY: &str = "again";

/// A source as the file that holds its preserved bytes sees it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceFile {
    pub object_id: String,
    /// Where its note is, when it has one. An attached capture (dictation)
    /// has none: its words went into another note.
    pub projection_rel_path: Option<String>,
    /// The document's text is not what a processor last wrote: somebody
    /// typed into it, or wrote it before any processor ran. Running again
    /// replaces it, so a shell asks first.
    pub text_edited: bool,
}

impl Store {
    /// The source whose preserved bytes are the file at `rel_path`, or
    /// `None` when the file is not one.
    ///
    /// By path first; then by digest, through the same rule reconcile
    /// uses for a recording that was moved — so a voice note renamed
    /// before that rule existed, whose recording the kernel still looked
    /// for under the old name, is found and followed here.
    pub fn source_for_file(&self, rel_path: &str) -> Result<Option<SourceFile>> {
        let by_path: Option<String> = self
            .conn()
            .query_row(
                "SELECT b.object_id FROM blobs b JOIN objects o ON o.object_id = b.object_id
                 WHERE b.rel_path = ?1 AND b.state = 'ready' AND o.deleted = 0
                   AND o.type_id = ?2
                 ORDER BY b.rowid DESC LIMIT 1",
                rusqlite::params![rel_path, SOURCE_TYPE],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        let object_id = match by_path {
            Some(id) => id,
            None => match self.follow_moved_recording(rel_path)? {
                Some(id) => id,
                None => return Ok(None),
            },
        };
        let projection_rel_path: Option<String> = self
            .conn()
            .query_row(
                "SELECT rel_path FROM projections WHERE object_id = ?1",
                [&object_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        let text_edited = self.text_edited(&object_id)?;
        Ok(Some(SourceFile { object_id, projection_rel_path, text_edited }))
    }

    /// Whether the head's text differs from the text of the newest
    /// revision a processor wrote. Compared trimmed: an editor's trailing
    /// newline is not an edit anybody made.
    fn text_edited(&self, object_id: &str) -> Result<bool> {
        let text_of = |payload: &str| -> Result<String> {
            let document: serde_json::Value = serde_json::from_str(payload)?;
            Ok(document.get("text").and_then(|v| v.as_str()).unwrap_or("").trim().to_string())
        };
        let head = crate::revisions::head_payload(self.conn(), object_id)?
            .map(|p| text_of(&p))
            .transpose()?
            .unwrap_or_default();
        if head.is_empty() {
            return Ok(false);
        }
        let processed: Option<String> = self
            .conn()
            .query_row(
                "SELECT payload FROM revisions
                 WHERE object_id = ?1 AND substr(origin, 1, length(?2)) = ?2
                 ORDER BY created_ms DESC, revision_id DESC LIMIT 1",
                rusqlite::params![object_id, ORIGIN_PREFIX],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        Ok(match processed {
            Some(payload) => text_of(&payload)? != head,
            None => true,
        })
    }

    /// Ask every enabled processor subscribed to sources to run over this
    /// one again. Returns the jobs queued: zero means nothing subscribed
    /// is switched on, which a shell says in words rather than waiting.
    ///
    /// Only a live source with its preserved bytes: the request is for a
    /// fresh answer from the same evidence, and without the evidence
    /// there is nothing to answer from.
    pub fn process_again(&self, object_id: &str) -> Result<i64> {
        let standing: Option<(String, i64)> = self
            .conn()
            .query_row(
                "SELECT type_id, deleted FROM objects WHERE object_id = ?1",
                [object_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        match standing {
            Some((type_id, 0)) if type_id == SOURCE_TYPE => {}
            _ => {
                return Err(KernelError::DocumentInvalid {
                    detail: format!("{object_id} is not a source that can be processed again"),
                })
            }
        }
        let held: i64 = self.conn().query_row(
            "SELECT COUNT(*) FROM blobs WHERE object_id = ?1 AND state = 'ready'",
            [object_id],
            |row| row.get(0),
        )?;
        if held == 0 {
            return Err(KernelError::DocumentInvalid {
                detail: format!("{object_id} has no preserved bytes on this device"),
            });
        }
        let payload = crate::revisions::head_payload(self.conn(), object_id)?.ok_or_else(|| {
            KernelError::DocumentInvalid { detail: format!("{object_id} has no head") }
        })?;
        let mut document: serde_json::Value = serde_json::from_str(&payload)?;
        document[AGAIN_KEY] = serde_json::Value::Bool(true);
        let payload = document.to_string();
        let event_id = ids::new_id();
        self.write_txn(|| {
            let conn = self.conn();
            conn.execute(
                "INSERT INTO events
                 (event_id, event_type, subject_id, payload, created_ms, origin_device)
                 VALUES (?1, ?2, ?3, ?4, ?5,
                         (SELECT value FROM meta WHERE key = 'device_id'))",
                rusqlite::params![event_id, SOURCE_READY_EVENT, object_id, payload, ids::now_ms()],
            )?;
            Ok(conn.execute(
                "INSERT INTO jobs (job_id, subscription_id, event_id, state)
                 SELECT ?1 || subscription_id, subscription_id, ?1, 'pending'
                 FROM subscriptions WHERE event_type = ?2 AND enabled = 1",
                rusqlite::params![event_id, SOURCE_READY_EVENT],
            )? as i64)
        })
    }
}

#[cfg(test)]
mod tests;
