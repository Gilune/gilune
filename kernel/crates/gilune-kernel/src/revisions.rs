//! An object's current document and how it changes: reading the head, and
//! adding the revision that replaces it. Every origin goes through here —
//! an external edit, a processor revising its subject (ADR 0013), a
//! migration — so the head, the `create-revision` operation that carries
//! the change to other devices, and the `revision-added` event can never
//! drift apart.

use crate::capture::record_operation;
use crate::{ids, Result};

pub const REVISION_ADDED_EVENT: &str = "org.memory.revision-added";

/// Whether a new revision announces itself. Ordinary revisions do —
/// subscribers exist to react to them. A one-time migration does not:
/// re-announcing work a processor already did would queue it again.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Announce {
    Event,
    Silent,
}

/// The object's current head document, or `None` if it has no head.
pub(crate) fn head_payload(
    conn: &rusqlite::Connection,
    object_id: &str,
) -> Result<Option<String>> {
    conn.query_row(
        "SELECT r.payload FROM heads h
         JOIN revisions r ON r.revision_id = h.revision_id
         WHERE h.object_id = ?1
         ORDER BY r.created_ms DESC LIMIT 1",
        [object_id],
        |row| row.get(0),
    )
    .map(Some)
    .or_else(|e| match e {
        rusqlite::Error::QueryReturnedNoRows => Ok(None),
        e => Err(e.into()),
    })
}

/// Insert the revision, make it the object's only head, and record the
/// operation. Runs inside the caller's transaction — the caller owns
/// BEGIN and COMMIT — so a committed change and its operation are
/// inseparable.
///
/// Replacing *every* head is a v0 simplification (ADR 0013): until sync
/// lands an object has one head, so there is nothing to merge. A
/// concurrent-heads-aware merge arrives with sync, which is what makes
/// multiple heads possible (constitution rule 6).
pub(crate) fn add_revision(
    conn: &rusqlite::Connection,
    object_id: &str,
    revision_id: &str,
    payload: &str,
    origin: &str,
    created_ms: i64,
    announce: Announce,
) -> Result<()> {
    conn.execute(
        "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        rusqlite::params![revision_id, object_id, payload, origin, created_ms],
    )?;
    conn.execute("DELETE FROM heads WHERE object_id = ?1", [object_id])?;
    conn.execute(
        "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
        [object_id, revision_id],
    )?;
    record_operation(
        conn,
        "create-revision",
        object_id,
        &serde_json::json!({
            "revision_id": revision_id,
            "payload": payload,
            "origin": origin,
            "created_ms": created_ms,
        })
        .to_string(),
        created_ms,
    )?;
    if announce == Announce::Event {
        conn.execute(
            "INSERT INTO events (event_id, event_type, subject_id, payload, created_ms)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            rusqlite::params![
                ids::new_id(),
                REVISION_ADDED_EVENT,
                object_id,
                payload,
                created_ms
            ],
        )?;
    }
    Ok(())
}
