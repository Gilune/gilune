//! The one-time merge of legacy transcript objects into the notes they
//! were derived from (ADR 0013). This module is the deliberate exception
//! to "the kernel names no domain type": it exists solely to retire one,
//! and it is disposable — once every vault that can reach it has run, the
//! module goes.
//!
//! Idempotency comes from object state, never from the flag: a run
//! interrupted anywhere converges on the next open. The flag only spares
//! healthy vaults the scan.

use crate::revisions::{add_revision, head_payload, Announce};
use crate::store::Store;
use crate::{ids, Result};

const MERGED_FLAG: &str = "transcripts_merged_v1";
const TRANSCRIPT_TYPE: &str = "org.memory.transcript";
const TRANSCRIPT_ORIGIN: &str = "migration:transcript-merge";
/// Where the old model projected transcripts, and how it named them —
/// the folder `vault::projection_path` derives for a blobless object.
const LEGACY_FOLDER: &str = "Transcripts";

pub(crate) fn run(store: &Store) -> Result<()> {
    if merged(store)? {
        return Ok(());
    }
    let mut swept = true;
    for (object_id, deleted) in legacy_transcripts(store)? {
        let projected = projected_path(store, &object_id)?;
        if !deleted {
            merge_into_source(store, &object_id)?;
        }
        // Outside the transaction on purpose: the durable state is what
        // the transaction holds, and a file left behind is swept by the
        // next run, which finds the object already dead.
        let path = store.vault().join(&projected);
        if path.exists() && std::fs::remove_file(&path).is_err() {
            // Another program may hold the file open. Never fail the open
            // over it: capture must not depend on a migration finishing
            // (constitution rule 1). Leaving the flag unset retries later.
            swept = false;
        }
    }
    if swept {
        let _ = std::fs::remove_dir(store.vault().join(LEGACY_FOLDER)); // only if empty
        store.conn().execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?1, 1)",
            [MERGED_FLAG],
        )?;
    }
    Ok(())
}

fn merged(store: &Store) -> Result<bool> {
    let flag: Option<i64> = store
        .conn()
        .query_row("SELECT value FROM meta WHERE key = ?1", [MERGED_FLAG], |row| row.get(0))
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            e => Err(e),
        })?;
    Ok(flag.is_some())
}

/// Every transcript object, live or already merged: a dead one may still
/// have its file on disk from an interrupted run.
fn legacy_transcripts(store: &Store) -> Result<Vec<(String, bool)>> {
    let mut stmt = store
        .conn()
        .prepare("SELECT object_id, deleted FROM objects WHERE type_id = ?1 ORDER BY object_id")?;
    let rows = stmt.query_map([TRANSCRIPT_TYPE], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)? != 0))
    })?;
    Ok(rows.collect::<std::result::Result<_, _>>()?)
}

/// The recorded projection if there still is one, else the path the old
/// model derived — the row is gone once the merge transaction commits,
/// and a rerun must still be able to find the file.
fn projected_path(store: &Store, object_id: &str) -> Result<String> {
    let recorded: Option<String> = store
        .conn()
        .query_row(
            "SELECT rel_path FROM projections WHERE object_id = ?1",
            [object_id],
            |row| row.get(0),
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            e => Err(e),
        })?;
    Ok(recorded.unwrap_or_else(|| {
        format!("{LEGACY_FOLDER}/transcript-{}.md", ids::short(object_id))
    }))
}

/// One transcript: its text becomes a revision of its source, and the
/// transcript itself is tombstoned. A transcript with no text or no
/// source is left exactly as it is — a migration that cannot preserve
/// what it touches must not touch it (constitution rule 1).
fn merge_into_source(store: &Store, transcript_id: &str) -> Result<()> {
    let conn = store.conn();
    let Some(text) = transcript_text(conn, transcript_id)? else { return Ok(()) };
    let Some(source_id) = derived_from(conn, transcript_id)? else { return Ok(()) };

    let created_ms = ids::now_ms();
    conn.execute_batch("BEGIN IMMEDIATE;")?;
    let done: Result<()> = (|| {
        // Read inside the transaction: a second process running the same
        // migration must see the merged source, not a stale one.
        let Some(payload) = head_payload(conn, &source_id)? else { return Ok(()) };
        let mut document: serde_json::Value = serde_json::from_str(&payload)?;
        if document.get("text").and_then(|v| v.as_str()).is_none() {
            document["text"] = serde_json::Value::String(text);
            // Silent by decision (ADR 0013): the transcription this
            // carries already ran, and a migration must not churn jobs.
            add_revision(
                conn,
                &source_id,
                &ids::new_id(),
                &document.to_string(),
                TRANSCRIPT_ORIGIN,
                created_ms,
                Announce::Silent,
            )?;
        }
        // A source that already carries text keeps what it has — a merge
        // never overwrites — and the transcript retires either way, its
        // own words preserved in kernel history rather than in the vault.
        // The heads this deletion could see, named — read before the rows
        // are marked deleted, and inside this transaction, so the operation
        // describes exactly the history it is retiring.
        //
        // It used to carry `{}`, which on every other replica is
        // indistinguishable from a tombstone written before the key
        // existed: the merge knew precisely what it was deleting and said
        // nothing, so its deletions fell to the weaker fallback for ever.
        let carried = crate::operations::tombstone_payload(conn, transcript_id)?;
        conn.execute("UPDATE objects SET deleted = 1 WHERE object_id = ?1", [transcript_id])?;
        conn.execute("DELETE FROM projections WHERE object_id = ?1", [transcript_id])?;
        crate::operations::record(
            conn,
            crate::operations::TOMBSTONE,
            transcript_id,
            carried,
            created_ms,
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

fn transcript_text(conn: &rusqlite::Connection, transcript_id: &str) -> Result<Option<String>> {
    let Some(payload) = head_payload(conn, transcript_id)? else { return Ok(None) };
    let document: serde_json::Value = serde_json::from_str(&payload)?;
    Ok(document
        .get("text")
        .and_then(|v| v.as_str())
        .map(str::to_string))
}

fn derived_from(conn: &rusqlite::Connection, transcript_id: &str) -> Result<Option<String>> {
    conn.query_row(
        "SELECT target_id FROM provenance
         WHERE object_id = ?1 AND relation = 'derived-from' LIMIT 1",
        [transcript_id],
        |row| row.get(0),
    )
    .map(Some)
    .or_else(|e| match e {
        rusqlite::Error::QueryReturnedNoRows => Ok(None),
        e => Err(e.into()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A vault as the old model left it: a wav capture, a transcript
    /// object derived from it, and both projected. Written with SQL
    /// because no kernel path produces this shape any more.
    fn old_model_vault(dir: &std::path::Path) -> (String, String, String) {
        let store = Store::open(dir).expect("open");
        let receipt = store.capture_bytes("wav", &[4u8; 64]).expect("capture");
        store.project().expect("project the capture");

        let transcript_id = ids::new_id();
        let transcript_rel = format!("{LEGACY_FOLDER}/transcript-{}.md", ids::short(&transcript_id));
        let revision_id = ids::new_id();
        let payload = serde_json::json!({ "text": "the words that were spoken" }).to_string();
        let conn = store.conn();
        conn.execute(
            "INSERT INTO objects (object_id, type_id, type_schema, created_ms)
             VALUES (?1, ?2, 1, 1)",
            rusqlite::params![transcript_id, TRANSCRIPT_TYPE],
        )
        .expect("transcript object");
        conn.execute(
            "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
             VALUES (?1, ?2, ?3, 'processor:transcribe', 1)",
            rusqlite::params![revision_id, transcript_id, payload],
        )
        .expect("transcript revision");
        conn.execute(
            "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
            rusqlite::params![transcript_id, revision_id],
        )
        .expect("transcript head");
        conn.execute(
            "INSERT INTO provenance (object_id, target_id, relation, processor_id)
             VALUES (?1, ?2, 'derived-from', 'transcribe')",
            rusqlite::params![transcript_id, receipt.source_id],
        )
        .expect("provenance");
        conn.execute(
            "INSERT INTO projections (object_id, rel_path, revision_id, content_sha256)
             VALUES (?1, ?2, ?3, 'unused')",
            rusqlite::params![transcript_id, transcript_rel, revision_id],
        )
        .expect("transcript projection");
        // Opening to build it flagged it as merged; a vault from before
        // the merge existed carries no flag.
        conn.execute("DELETE FROM meta WHERE key = ?1", [MERGED_FLAG])
            .expect("clear the flag");
        drop(store);

        let file = dir.join(&transcript_rel);
        std::fs::create_dir_all(file.parent().expect("parent")).expect("mkdir");
        std::fs::write(&file, "---\ngilune-id: x\n---\nthe words that were spoken")
            .expect("projected transcript");
        (receipt.source_id, transcript_id, transcript_rel)
    }

    fn count(store: &Store, sql: &str) -> i64 {
        store.conn().query_row(sql, [], |row| row.get(0)).expect("count")
    }

    #[test]
    fn the_merges_tombstone_names_the_heads_it_retired() {
        // A bare `{}` tombstone is, on every other replica, exactly what a
        // tombstone written before the key existed looks like — so the
        // receiving projection has to fall back to the weaker pre-key
        // question rather than the exact one. The merge is a deletion made
        // here with the whole object in front of it: it can answer
        // precisely, and now it does.
        let dir = tempfile::tempdir().expect("tempdir");
        let (_source_id, transcript_id, _rel) = old_model_vault(dir.path());
        let head_before: String = {
            let store = Store::open(dir.path()).expect("read the head before the merge runs");
            // Opening ran the merge, so read the head the merge retired
            // out of the operation it wrote rather than out of `heads`.
            let payload: String = store
                .conn()
                .query_row(
                    "SELECT payload FROM operations WHERE kind = ?1 AND target_id = ?2",
                    rusqlite::params![crate::operations::TOMBSTONE, transcript_id],
                    |row| row.get(0),
                )
                .expect("the merge's tombstone");
            let document: serde_json::Value = serde_json::from_str(&payload).expect("json");
            let named = document
                .get("supersedes")
                .and_then(|v| v.as_array())
                .expect("the tombstone names the heads it saw")
                .clone();
            assert_eq!(named.len(), 1, "one head was retired: {named:?}");
            // No origin key, and that is right rather than missing: this
            // fixture's transcript object was written with raw SQL, as the
            // old model left it, so there is no `object-created` operation
            // to read an origin out of. `name_origins` writes what is
            // known and nothing else — the canonical bytes say only what
            // is true — which is the same reason the key is absent from
            // pre-M5a operations in a real vault.
            assert!(
                document.get(crate::operations::OBJECT_ORIGIN_DEVICE).is_none(),
                "an origin was asserted for an object no operation ever created: {document}"
            );
            named[0].as_str().expect("a revision id").to_string()
        };
        assert!(!head_before.is_empty());
    }

    #[test]
    fn an_old_transcript_becomes_the_text_of_the_note_it_came_from() {
        let dir = tempfile::tempdir().expect("tempdir");
        let (source_id, transcript_id, transcript_rel) = old_model_vault(dir.path());

        let store = Store::open(dir.path()).expect("open runs the merge");

        let payload = head_payload(store.conn(), &source_id)
            .expect("head")
            .expect("the source still has a head");
        let document: serde_json::Value = serde_json::from_str(&payload).expect("json");
        assert_eq!(document["text"], "the words that were spoken");
        assert_eq!(document["media"], "wav", "the merge keeps the capture's own facts");

        let dead: i64 = store
            .conn()
            .query_row(
                "SELECT deleted FROM objects WHERE object_id = ?1",
                [&transcript_id],
                |row| row.get(0),
            )
            .expect("transcript row");
        assert_eq!(dead, 1, "the transcript object is tombstoned, not erased");
        assert!(!dir.path().join(&transcript_rel).exists(), "its note is gone");
        assert!(!dir.path().join(LEGACY_FOLDER).exists(), "Transcripts stops existing");

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
                crate::operations::OBJECT_REVISED.to_string(),
                "tombstone".to_string()
            ],
            "a future sync peer converges on the same merge"
        );
        assert_eq!(count(&store, "SELECT COUNT(*) FROM events"), 1, "no job churn");
        assert!(store.verify().expect("verify").is_empty());

        // The note now renders the words rather than an audio embed.
        store.project().expect("project");
        let rel = format!("{}.md", source_id_stem(&store, &source_id));
        let note = std::fs::read_to_string(dir.path().join(&rel)).expect("note");
        assert!(note.ends_with("the words that were spoken"));
        assert!(!note.contains("![["), "the audio is metadata, not an embed");

        let operations = count(&store, "SELECT COUNT(*) FROM operations");
        drop(store);
        let reopened = Store::open(dir.path()).expect("second open");
        assert_eq!(
            count(&reopened, "SELECT COUNT(*) FROM operations"),
            operations,
            "a merged vault is merged once"
        );
    }

    #[test]
    fn an_interrupted_merge_converges_on_the_next_open() {
        let dir = tempfile::tempdir().expect("tempdir");
        let (_source_id, _transcript_id, transcript_rel) = old_model_vault(dir.path());
        {
            let _merged = Store::open(dir.path()).expect("first open");
        }
        // The transaction committed but the file removal did not, and the
        // flag was never set: exactly the state a crash leaves.
        let file = dir.path().join(&transcript_rel);
        std::fs::create_dir_all(file.parent().expect("parent")).expect("mkdir");
        std::fs::write(&file, "left behind by a crash").expect("restore the file");
        {
            let store = Store::open(dir.path()).expect("open");
            store
                .conn()
                .execute("DELETE FROM meta WHERE key = ?1", [MERGED_FLAG])
                .expect("clear the flag");
        }

        let store = Store::open(dir.path()).expect("reopen sweeps what was left");
        assert!(!file.exists(), "the leftover projection is removed");
        assert_eq!(
            count_one(
                &store,
                "SELECT COUNT(*) FROM revisions WHERE origin = ?1",
                TRANSCRIPT_ORIGIN
            ),
            1,
            "the source gains exactly one migration revision however often the merge reruns"
        );
        assert_eq!(
            count(&store, "SELECT COUNT(*) FROM operations WHERE kind = 'tombstone'"),
            1
        );
    }

    fn count_one(store: &Store, sql: &str, param: &str) -> i64 {
        store.conn().query_row(sql, [param], |row| row.get(0)).expect("count")
    }

    fn source_id_stem(store: &Store, source_id: &str) -> String {
        let payload = head_payload(store.conn(), source_id)
            .expect("head")
            .expect("head exists");
        let document: serde_json::Value = serde_json::from_str(&payload).expect("json");
        let rel = document["rel_path"].as_str().expect("rel_path").to_string();
        rel.rsplit_once('.').expect("extension").0.to_string()
    }
}
