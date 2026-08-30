//! The kernel store: one SQLite database under `.gilune/local/` inside
//! the vault (ADR 0002, ADR 0007 layout). Device-local by contract —
//! never synced, never exported; the vault's visible files are the
//! projection, this is the authority. Opening runs migrations and then
//! capture recovery; the schema grows only through the registry below so
//! every version is a tested upgrade path (constitution rule 12).

use crate::{capture, fs, ids, KernelError, Result};
use std::path::{Path, PathBuf};

/// Newest schema this kernel writes. An older database upgrades on open;
/// a newer one is refused (fail closed, rule 10) rather than guessed at.
pub const SCHEMA_VERSION: i64 = 3;

pub struct Store {
    conn: rusqlite::Connection,
    vault: PathBuf,
}

impl Store {
    /// Open (or create) the store for a vault directory, running any
    /// pending migrations and then startup recovery. The vault directory
    /// itself is created if absent; a path that exists but is not a
    /// directory is refused.
    pub fn open(vault: impl Into<PathBuf>) -> Result<Store> {
        let vault = vault.into();
        if vault.exists() && !vault.is_dir() {
            return Err(KernelError::VaultUnusable {
                reason: format!("{} exists and is not a directory", vault.display()),
            });
        }
        let local = vault.join(".gilune").join("local");
        let staging = local.join("staging");
        std::fs::create_dir_all(&staging).map_err(|e| KernelError::Io {
            path: staging.clone(),
            source: e,
        })?;
        fs::fsync_dir(&local)?;

        let conn = rusqlite::Connection::open(local.join("kernel.db"))?;
        conn.pragma_update(None, "journal_mode", "wal")?;
        conn.pragma_update(None, "foreign_keys", "on")?;
        migrate(&conn)?;
        conn.execute(
            "INSERT OR IGNORE INTO meta (key, value) VALUES ('device_id', ?1)",
            [ids::new_id()],
        )?;
        let store = Store { conn, vault };
        capture::recover(&store)?;
        Ok(store)
    }

    pub fn schema_version(&self) -> Result<i64> {
        current_version(&self.conn)
    }

    pub fn device_id(&self) -> Result<String> {
        let id = self.conn.query_row(
            "SELECT value FROM meta WHERE key = 'device_id'",
            [],
            |row| row.get(0),
        )?;
        Ok(id)
    }

    pub fn vault(&self) -> &Path {
        &self.vault
    }

    pub(crate) fn conn(&self) -> &rusqlite::Connection {
        &self.conn
    }

    pub(crate) fn local_dir(&self) -> PathBuf {
        self.vault.join(".gilune").join("local")
    }

    pub(crate) fn staging_dir(&self) -> PathBuf {
        self.local_dir().join("staging")
    }

    pub(crate) fn lost_found_dir(&self) -> PathBuf {
        self.local_dir().join("lost+found")
    }

    /// Counters for diagnostics and the crash harness. Cheap; safe on any
    /// schema-current store.
    pub fn status(&self) -> Result<Status> {
        let count = |sql: &str| -> Result<i64> {
            Ok(self.conn.query_row(sql, [], |row| row.get(0))?)
        };
        Ok(Status {
            sources: count("SELECT COUNT(*) FROM objects WHERE type_id = 'org.memory.source'")?,
            blobs_ready: count("SELECT COUNT(*) FROM blobs WHERE state = 'ready'")?,
            blobs_committed: count("SELECT COUNT(*) FROM blobs WHERE state = 'committed'")?,
            events: count("SELECT COUNT(*) FROM events")?,
            jobs_pending: count("SELECT COUNT(*) FROM jobs WHERE state IN ('pending','leased')")?,
            jobs_quarantined: count("SELECT COUNT(*) FROM jobs WHERE state = 'quarantined'")?,
            operations: count("SELECT COUNT(*) FROM operations")?,
            projections: count("SELECT COUNT(*) FROM projections")?,
            proposals_open: count("SELECT COUNT(*) FROM proposals WHERE state = 'open'")?,
            quarantined_files: count("SELECT COUNT(*) FROM quarantine")?,
            import_candidates: count("SELECT COUNT(*) FROM imports")?,
        })
    }

    /// Full integrity check: every blob row's bytes exist where the row
    /// says, with the digest the row records. Returns problems rather than
    /// failing fast so callers can report all of them.
    pub fn verify(&self) -> Result<Vec<String>> {
        let mut problems = Vec::new();
        let mut stmt = self
            .conn
            .prepare("SELECT blob_id, rel_path, sha256, bytes, state FROM blobs")?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, String>(4)?,
            ))
        })?;
        for row in rows {
            let (blob_id, rel_path, sha256, bytes, state) = row?;
            if state != "ready" {
                problems.push(format!("blob {blob_id} is {state}, not ready"));
                continue;
            }
            let path = self.vault.join(&rel_path);
            match std::fs::read(&path) {
                Err(_) => problems.push(format!("blob {blob_id} missing at {rel_path}")),
                Ok(data) => {
                    if data.len() as i64 != bytes {
                        problems.push(format!(
                            "blob {blob_id} size mismatch at {rel_path}: {} != {bytes}",
                            data.len()
                        ));
                    } else if ids::sha256_hex(&data) != sha256 {
                        problems.push(format!("blob {blob_id} content hash mismatch at {rel_path}"));
                    }
                }
            }
        }
        Ok(problems)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Status {
    pub sources: i64,
    pub blobs_ready: i64,
    pub blobs_committed: i64,
    pub events: i64,
    pub jobs_pending: i64,
    pub jobs_quarantined: i64,
    pub operations: i64,
    pub projections: i64,
    pub proposals_open: i64,
    pub quarantined_files: i64,
    pub import_candidates: i64,
}

fn current_version(conn: &rusqlite::Connection) -> Result<i64> {
    let version = conn
        .query_row(
            "SELECT value FROM meta WHERE key = 'schema_version'",
            [],
            |row| row.get::<_, i64>(0),
        )
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(0),
            e => Err(e),
        })?;
    Ok(version)
}

/// Migrations are append-only: entry N upgrades schema version N to N+1
/// inside one transaction. Editing a shipped entry is forbidden; fixing
/// a bad migration means shipping the next one.
const MIGRATIONS: &[&str] = &[
    // v0 -> v1: the capture core. Payloads are canonical JSON documents;
    // rel_path values are '/'-separated (03-data-model).
    "CREATE TABLE objects (
         object_id TEXT PRIMARY KEY,
         type_id TEXT NOT NULL,
         type_schema INTEGER NOT NULL,
         created_ms INTEGER NOT NULL,
         deleted INTEGER NOT NULL DEFAULT 0
     );
     CREATE TABLE revisions (
         revision_id TEXT PRIMARY KEY,
         object_id TEXT NOT NULL REFERENCES objects(object_id),
         payload TEXT NOT NULL,
         origin TEXT NOT NULL,
         created_ms INTEGER NOT NULL
     );
     CREATE TABLE heads (
         object_id TEXT NOT NULL REFERENCES objects(object_id),
         revision_id TEXT NOT NULL REFERENCES revisions(revision_id),
         PRIMARY KEY (object_id, revision_id)
     );
     CREATE TABLE blobs (
         blob_id TEXT PRIMARY KEY,
         object_id TEXT NOT NULL REFERENCES objects(object_id),
         rel_path TEXT NOT NULL,
         sha256 TEXT NOT NULL,
         bytes INTEGER NOT NULL,
         state TEXT NOT NULL CHECK (state IN ('committed','ready'))
     );
     CREATE TABLE events (
         event_id TEXT PRIMARY KEY,
         event_type TEXT NOT NULL,
         subject_id TEXT NOT NULL,
         payload TEXT NOT NULL,
         created_ms INTEGER NOT NULL
     );
     CREATE TABLE subscriptions (
         subscription_id TEXT PRIMARY KEY,
         event_type TEXT NOT NULL,
         enabled INTEGER NOT NULL DEFAULT 1
     );
     CREATE TABLE jobs (
         job_id TEXT PRIMARY KEY,
         subscription_id TEXT NOT NULL REFERENCES subscriptions(subscription_id),
         event_id TEXT NOT NULL REFERENCES events(event_id),
         state TEXT NOT NULL CHECK (state IN ('pending','leased','done','quarantined')),
         attempts INTEGER NOT NULL DEFAULT 0,
         not_before_ms INTEGER NOT NULL DEFAULT 0,
         lease_until_ms INTEGER,
         last_error TEXT,
         UNIQUE (subscription_id, event_id)
     );
     CREATE TABLE operations (
         op_id TEXT PRIMARY KEY,
         device_id TEXT NOT NULL,
         op_seq INTEGER NOT NULL,
         kind TEXT NOT NULL,
         target_id TEXT NOT NULL,
         payload TEXT NOT NULL,
         created_ms INTEGER NOT NULL,
         UNIQUE (device_id, op_seq)
     );",
    // v1 -> v2: the vault projection layer (ADR 0007). `projections`
    // records what the kernel last wrote where, so reconciliation can
    // tell external edits from its own output; `proposals` holds
    // reconciliation outcomes awaiting a user decision; `quarantine` and
    // `imports` record files the reconciler must not touch.
    "CREATE TABLE projections (
         object_id TEXT PRIMARY KEY REFERENCES objects(object_id),
         rel_path TEXT NOT NULL UNIQUE,
         revision_id TEXT NOT NULL,
         content_sha256 TEXT NOT NULL
     );
     CREATE TABLE proposals (
         proposal_id TEXT PRIMARY KEY,
         object_id TEXT NOT NULL REFERENCES objects(object_id),
         kind TEXT NOT NULL CHECK (kind IN ('tombstone')),
         state TEXT NOT NULL CHECK (state IN ('open','accepted','rejected')),
         created_ms INTEGER NOT NULL
     );
     CREATE TABLE quarantine (
         rel_path TEXT PRIMARY KEY,
         reason TEXT NOT NULL,
         created_ms INTEGER NOT NULL
     );
     CREATE TABLE imports (
         rel_path TEXT PRIMARY KEY,
         created_ms INTEGER NOT NULL
     );",
    // v2 -> v3: processing (constitution rules 2 and 7). `processing_runs`
    // makes handler execution idempotent — one run per (processor,
    // idempotency key), no matter how often the job is delivered.
    // `provenance` links every interpretation back to its inputs.
    "CREATE TABLE processing_runs (
         run_id TEXT PRIMARY KEY,
         processor_id TEXT NOT NULL,
         idempotency_key TEXT NOT NULL,
         event_id TEXT NOT NULL,
         created_ms INTEGER NOT NULL,
         UNIQUE (processor_id, idempotency_key)
     );
     CREATE TABLE provenance (
         object_id TEXT NOT NULL REFERENCES objects(object_id),
         target_id TEXT NOT NULL,
         relation TEXT NOT NULL,
         processor_id TEXT,
         PRIMARY KEY (object_id, target_id, relation)
     );",
];

fn migrate(conn: &rusqlite::Connection) -> Result<()> {
    // The meta table is bootstrap infrastructure, not a migration: it must
    // exist before a version can be read at all.
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value NOT NULL);",
    )?;
    let mut version = current_version(conn)?;
    if version > SCHEMA_VERSION {
        return Err(KernelError::SchemaTooNew {
            found: version,
            supported: SCHEMA_VERSION,
        });
    }
    while version < SCHEMA_VERSION {
        let step = MIGRATIONS[version as usize];
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let applied: Result<()> = (|| {
            conn.execute_batch(step)?;
            conn.execute(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?1)",
                [version + 1],
            )?;
            Ok(())
        })();
        match applied {
            Ok(()) => conn.execute_batch("COMMIT;")?,
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                return Err(e);
            }
        }
        version += 1;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_creates_local_state_and_reports_version() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        assert_eq!(store.schema_version().expect("version"), SCHEMA_VERSION);
        assert!(dir.path().join(".gilune/local/kernel.db").exists());
    }

    #[test]
    fn reopen_is_idempotent_and_device_identity_is_stable() {
        let dir = tempfile::tempdir().expect("tempdir");
        let first = Store::open(dir.path()).expect("first open");
        let device = first.device_id().expect("device id");
        drop(first);
        let store = Store::open(dir.path()).expect("reopen");
        assert_eq!(store.schema_version().expect("version"), SCHEMA_VERSION);
        assert_eq!(store.device_id().expect("device id"), device);
    }

    #[test]
    fn a_newer_schema_is_refused_not_guessed_at() {
        let dir = tempfile::tempdir().expect("tempdir");
        drop(Store::open(dir.path()).expect("open"));
        let conn =
            rusqlite::Connection::open(dir.path().join(".gilune/local/kernel.db")).expect("raw");
        conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?1)",
            [SCHEMA_VERSION + 1],
        )
        .expect("bump");
        drop(conn);
        assert!(matches!(
            Store::open(dir.path()),
            Err(KernelError::SchemaTooNew { .. })
        ));
    }

    #[test]
    fn a_file_where_the_vault_should_be_is_refused() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("not-a-dir");
        std::fs::write(&path, b"occupied").expect("write");
        assert!(matches!(
            Store::open(&path),
            Err(KernelError::VaultUnusable { .. })
        ));
    }
}
