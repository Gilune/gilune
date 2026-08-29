//! SQLite schema and open/migrate. WAL journal, full synchronous: the spike
//! measures truthfulness, not raw speed.

use crate::{ids, Kernel};
use anyhow::Result;
use rusqlite::Connection;
use std::path::Path;

pub fn open_db(path: &Path) -> Result<Connection> {
    let conn = Connection::open(path)?;
    // journal_mode returns the resulting mode as a row, so query it.
    let _mode: String = conn.query_row("PRAGMA journal_mode = WAL", [], |r| r.get(0))?;
    conn.pragma_update(None, "synchronous", "FULL")?;
    conn.pragma_update(None, "foreign_keys", "ON")?;
    conn.busy_timeout(std::time::Duration::from_secs(10))?;
    Ok(conn)
}

pub fn migrate(k: &mut Kernel) -> Result<()> {
    let tx = k.conn.transaction()?;
    tx.execute_batch(
        r#"
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS objects (
            object_id TEXT PRIMARY KEY,
            type_id TEXT NOT NULL,
            schema_version INTEGER NOT NULL,
            class TEXT NOT NULL CHECK (class IN ('source','artifact','proposal')),
            origin_device TEXT NOT NULL,
            created_operation TEXT NOT NULL,
            display_created_at INTEGER NOT NULL,
            tombstone_json TEXT
        );
        CREATE TABLE IF NOT EXISTS revisions (
            revision_id TEXT PRIMARY KEY,
            object_id TEXT NOT NULL REFERENCES objects(object_id),
            based_on_json TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_operation TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS heads (
            object_id TEXT NOT NULL REFERENCES objects(object_id),
            revision_id TEXT NOT NULL REFERENCES revisions(revision_id),
            PRIMARY KEY (object_id, revision_id)
        );
        CREATE TABLE IF NOT EXISTS operations (
            operation_id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            device_seq INTEGER NOT NULL,
            kind TEXT NOT NULL,
            target_id TEXT NOT NULL,
            committed_at INTEGER NOT NULL,
            UNIQUE (device_id, device_seq)
        );
        CREATE TABLE IF NOT EXISTS blobs (
            blob_id TEXT PRIMARY KEY,
            state TEXT NOT NULL CHECK (state IN ('committed','ready')),
            media_type TEXT NOT NULL,
            byte_len INTEGER NOT NULL,
            sha256 TEXT NOT NULL,
            rel_path TEXT NOT NULL,
            stage_id TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS blob_refs (
            blob_id TEXT NOT NULL REFERENCES blobs(blob_id),
            object_id TEXT NOT NULL REFERENCES objects(object_id),
            PRIMARY KEY (blob_id, object_id)
        );
        CREATE TABLE IF NOT EXISTS events (
            event_id TEXT PRIMARY KEY,
            event_type TEXT NOT NULL,
            schema_version INTEGER NOT NULL,
            transaction_id TEXT NOT NULL,
            producer TEXT NOT NULL,
            subject_object_id TEXT NOT NULL,
            subject_revision_id TEXT NOT NULL,
            causation_event_id TEXT,
            correlation_id TEXT NOT NULL,
            committed_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS subscriptions (
            subscription_id TEXT PRIMARY KEY,
            event_type TEXT NOT NULL,
            handler TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1
        );
        CREATE TABLE IF NOT EXISTS jobs (
            job_id TEXT PRIMARY KEY,
            event_id TEXT NOT NULL REFERENCES events(event_id),
            subscription_id TEXT NOT NULL REFERENCES subscriptions(subscription_id),
            handler TEXT NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('pending','leased','completed','quarantined')),
            attempts INTEGER NOT NULL DEFAULT 0,
            lease_owner TEXT,
            lease_expires_at INTEGER,
            next_eligible_at INTEGER NOT NULL,
            last_error TEXT,
            processing_run_id TEXT,
            UNIQUE (event_id, subscription_id)
        );
        CREATE TABLE IF NOT EXISTS processing_runs (
            run_id TEXT PRIMARY KEY,
            idempotency_key TEXT NOT NULL UNIQUE,
            processor TEXT NOT NULL,
            processor_version TEXT NOT NULL,
            input_revisions_json TEXT NOT NULL,
            output_ids_json TEXT NOT NULL,
            status TEXT NOT NULL,
            finished_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS provenance (
            provenance_id TEXT PRIMARY KEY,
            artifact_object_id TEXT NOT NULL REFERENCES objects(object_id),
            run_id TEXT NOT NULL,
            inputs_json TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS vault_state (
            object_id TEXT PRIMARY KEY REFERENCES objects(object_id),
            rel_path TEXT NOT NULL,
            projected_revision TEXT NOT NULL,
            content_sha256 TEXT NOT NULL,
            missing INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS quarantine_files (
            rel_path TEXT PRIMARY KEY,
            reason TEXT NOT NULL,
            seen_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS integrity_alerts (
            alert_id TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            subject TEXT NOT NULL,
            detail TEXT NOT NULL,
            raised_at INTEGER NOT NULL
        );
        "#,
    )?;
    // schema version + device identity
    tx.execute(
        "INSERT OR IGNORE INTO meta (key, value) VALUES ('schema_version', '1')",
        [],
    )?;
    tx.execute(
        "INSERT OR IGNORE INTO meta (key, value) VALUES ('device_id', ?1)",
        [ids::new_id()],
    )?;
    tx.commit()?;
    Ok(())
}

pub fn device_id(conn: &Connection) -> Result<String> {
    Ok(conn.query_row(
        "SELECT value FROM meta WHERE key = 'device_id'",
        [],
        |r| r.get(0),
    )?)
}

/// Next per-device monotonic operation sequence (call inside a transaction).
pub fn next_device_seq(tx: &rusqlite::Transaction, device_id: &str) -> Result<i64> {
    let max: Option<i64> = tx.query_row(
        "SELECT MAX(device_seq) FROM operations WHERE device_id = ?1",
        [device_id],
        |r| r.get(0),
    )?;
    Ok(max.unwrap_or(0) + 1)
}

pub fn raise_alert(conn: &Connection, kind: &str, subject: &str, detail: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO integrity_alerts (alert_id, kind, subject, detail, raised_at)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        rusqlite::params![ids::new_id(), kind, subject, detail, Kernel::now_ms()],
    )?;
    Ok(())
}
