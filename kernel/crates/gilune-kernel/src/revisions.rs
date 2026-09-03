//! An object's current document and how it changes: reading the head,
//! and adding the revision that supersedes it. Every origin goes through
//! here — an external edit, a processor revising its subject (ADR 0013),
//! a migration — so the heads, the operation that carries the change to
//! other devices, and the `revision-added` event can never drift apart.
//!
//! An object may have more than one head. That is ADR 0005's contract,
//! not an error state: two devices that edited the same note offline both
//! wrote something true, and preserving both is the only answer that
//! never loses one. Two rules keep it usable:
//!
//! - **A local revision supersedes every head it can see.** The writer
//!   had them all in front of it, so its revision replaces them all —
//!   which is also how a person resolves a conflict: by editing the note.
//! - **Readers see the newest head**, by `created_ms` then `revision_id`.
//!   Deterministic everywhere, so replicas render identical bytes, and
//!   the heads it did not choose are surfaced rather than hidden — the
//!   projection writes `gilune-conflicted: true`.

use crate::operations;
use crate::{ids, Result};

pub const REVISION_ADDED_EVENT: &str = "org.memory.revision-added";

/// The one rule for which of several heads a reader sees, as an ORDER BY
/// over the revisions joined under `alias`. A function rather than a
/// repeated clause: replicas must agree on the answer, so the ordering is
/// one fact with one home. `alias` is always a literal from kernel code —
/// never host-supplied text.
pub fn newest_head_first(alias: &str) -> String {
    format!("ORDER BY {alias}.created_ms DESC, {alias}.revision_id DESC")
}

/// Whether a new revision announces itself. Ordinary revisions do —
/// subscribers exist to react to them. A one-time migration does not:
/// re-announcing work a processor already did would queue it again.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Announce {
    Event,
    Silent,
}

/// The object's current head document, or `None` if it has no head. With
/// concurrent heads this is the newest of them.
pub(crate) fn head_payload(
    conn: &rusqlite::Connection,
    object_id: &str,
) -> Result<Option<String>> {
    conn.query_row(
        &format!(
            "SELECT r.payload FROM heads h
             JOIN revisions r ON r.revision_id = h.revision_id
             WHERE h.object_id = ?1 {} LIMIT 1",
            newest_head_first("r")
        ),
        [object_id],
        |row| row.get(0),
    )
    .map(Some)
    .or_else(|e| match e {
        rusqlite::Error::QueryReturnedNoRows => Ok(None),
        e => Err(e.into()),
    })
}

/// Every head an object currently has, newest first. One head is the
/// ordinary case and stays the ordinary case: nothing produces a second
/// but a concurrent revision arriving from another device.
pub fn heads(conn: &rusqlite::Connection, object_id: &str) -> Result<Vec<String>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT r.revision_id FROM heads h
         JOIN revisions r ON r.revision_id = h.revision_id
         WHERE h.object_id = ?1 {}",
        newest_head_first("r")
    ))?;
    let rows = stmt.query_map([object_id], |row| row.get(0))?;
    Ok(rows.collect::<std::result::Result<_, _>>()?)
}

/// Insert the revision, supersede every head it was written over, and
/// record the operation. Runs inside the caller's transaction — the
/// caller owns BEGIN and COMMIT — so a committed change and its
/// operation are inseparable.
///
/// The heads it supersedes are named in the operation, so another device
/// applies exactly the same supersession and keeps exactly the same
/// concurrent heads. Naming them is what replaced "delete every head":
/// deleting locally and telling nobody which is what made concurrent
/// heads unrepresentable before v5.
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
    let superseded = heads(conn, object_id)?;
    for head in &superseded {
        conn.execute(
            "DELETE FROM heads WHERE object_id = ?1 AND revision_id = ?2",
            [object_id, head.as_str()],
        )?;
    }
    conn.execute(
        "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
        [object_id, revision_id],
    )?;
    let mut carried = serde_json::json!({
        "object_id": object_id,
        "revision": {
            "revision_id": revision_id,
            "payload": payload,
            "origin": origin,
            "created_ms": created_ms,
        },
        "supersedes": superseded,
    });
    // Whose object this is, and whose each superseded head was. Additive
    // keys, and the exit from `sync-awaiting-referent`: a receiver that
    // has never seen the object cannot look up whose it was, so a
    // revision of an object belonging to a device it has revoked would
    // otherwise halt its stream waiting for something that can never come
    // (ADR 0019 § 9). Read before the operation below is recorded, so it
    // describes the history this revision was written over.
    operations::name_origins(conn, object_id, &superseded, &mut carried)?;
    if announce == Announce::Event {
        // A silent revision carries no key at all rather than a null one:
        // the canonical bytes say only what is true.
        carried["event_type"] = serde_json::Value::String(REVISION_ADDED_EVENT.to_string());
    }
    operations::record(conn, operations::OBJECT_REVISED, object_id, carried, created_ms)?;
    if announce == Announce::Event {
        conn.execute(
            "INSERT INTO events
             (event_id, event_type, subject_id, payload, created_ms, origin_device)
             VALUES (?1, ?2, ?3, ?4, ?5,
                     (SELECT value FROM meta WHERE key = 'device_id'))",
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

#[cfg(test)]
mod tests {
    use crate::ids;
    use crate::store::Store;

    #[test]
    fn a_local_revision_supersedes_every_head_it_could_see() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let receipt = store.capture_bytes("txt", b"one head").expect("capture");
        let conn = store.conn();

        // Stand in for a concurrent head arriving from another device.
        conn.execute(
            "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
             VALUES ('remote-rev', ?1, '{\"text\":\"theirs\"}', 'sync', 2)",
            [&receipt.source_id],
        )
        .expect("remote revision");
        conn.execute(
            "INSERT INTO heads (object_id, revision_id) VALUES (?1, 'remote-rev')",
            [&receipt.source_id],
        )
        .expect("remote head");
        assert_eq!(
            super::heads(conn, &receipt.source_id).expect("heads").len(),
            2,
            "the object is genuinely conflicted before the edit"
        );

        conn.execute_batch("BEGIN IMMEDIATE;").expect("begin");
        super::add_revision(
            conn,
            &receipt.source_id,
            "mine",
            "{\"text\":\"resolved\"}",
            "external-edit",
            3,
            super::Announce::Event,
        )
        .expect("revise");
        conn.execute_batch("COMMIT;").expect("commit");

        assert_eq!(
            super::heads(conn, &receipt.source_id).expect("heads"),
            vec!["mine".to_string()],
            "editing a conflicted note is how a person resolves it"
        );
        let superseded: String = conn
            .query_row(
                "SELECT payload FROM operations WHERE kind = ?1",
                [crate::operations::OBJECT_REVISED],
                |row| row.get(0),
            )
            .expect("operation");
        let payload: serde_json::Value = serde_json::from_str(&superseded).expect("json");
        let named: Vec<&str> = payload["supersedes"]
            .as_array()
            .expect("array")
            .iter()
            .filter_map(|v| v.as_str())
            .collect();
        assert_eq!(named.len(), 2, "the operation names both, so replicas agree");
        assert!(named.contains(&"remote-rev"));
    }

    #[test]
    fn the_newest_head_is_the_one_readers_see_and_it_is_deterministic() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let receipt = store.capture_bytes("txt", b"original").expect("capture");
        let conn = store.conn();
        // Two heads at the *same* millisecond, both newer than the
        // capture: the tie-break must still give every device the same
        // answer.
        let concurrent_ms = ids::now_ms() + 10_000;
        for revision_id in ["aaa", "zzz"] {
            conn.execute(
                "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
                 VALUES (?1, ?2, ?3, 'sync', ?4)",
                rusqlite::params![
                    revision_id,
                    receipt.source_id,
                    format!("{{\"text\":\"{revision_id}\"}}"),
                    concurrent_ms
                ],
            )
            .expect("revision");
            conn.execute(
                "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                [&receipt.source_id, &revision_id.to_string()],
            )
            .expect("head");
        }
        assert_eq!(
            store.object_text(&receipt.source_id).expect("text").expect("some"),
            "zzz",
            "highest revision id breaks the tie, on every replica alike"
        );
    }
}
