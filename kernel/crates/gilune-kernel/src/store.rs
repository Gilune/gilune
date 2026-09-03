//! The kernel store: one SQLite database under `.gilune/local/` inside
//! the vault (ADR 0002, ADR 0007 layout). Device-local by contract —
//! never synced, never exported; the vault's visible files are the
//! projection, this is the authority. Opening runs migrations and then
//! capture recovery; the schema grows only through the registry below so
//! every version is a tested upgrade path (constitution rule 12).

use crate::{capture, fs, ids, operations, transcript_merge, KernelError, Result};
use std::path::{Path, PathBuf};

/// Newest schema this kernel writes. An older database upgrades on open;
/// a newer one is refused (fail closed, rule 10) rather than guessed at.
pub const SCHEMA_VERSION: i64 = 5;

pub struct Store {
    conn: rusqlite::Connection,
    vault: PathBuf,
    cache: std::cell::RefCell<Cache>,
}

/// Two facts the apply path asks for on **every** operation and which
/// almost never change: this device's own id, and the set of devices
/// whose operations it refuses.
///
/// Neither is cached blindly, because multi-instance is supported use
/// (owner decision 2026-08-31) and a second process may revoke a device
/// on the same vault. `device_id` is invalidated by the only thing that
/// changes it here (`reenrol_as_new_device`), and the refused set is
/// keyed on the raw stored text: the primary-key read still happens every
/// time and only the JSON parse is saved. A cache that outlived another
/// process's revocation would be a revoked device's operations applying.
#[derive(Default)]
struct Cache {
    device_id: Option<String>,
    refused_raw: Option<String>,
    refused: Vec<String>,
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
        // Multiple processes share one vault by design (app, agent, CLI —
        // owner decision 2026-08-31): writers queue instead of failing.
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        migrate(&conn)?;
        conn.execute(
            "INSERT OR IGNORE INTO meta (key, value) VALUES ('device_id', ?1)",
            [ids::new_id()],
        )?;
        let store = Store { conn, vault, cache: std::cell::RefCell::default() };
        capture::recover(&store)?;
        // A data merge, not a schema step: it depends on object state that
        // recovery may still have been completing (ADR 0013), and it is
        // flagged rather than versioned so it can be re-run to converge.
        transcript_merge::run(&store)?;
        // Likewise a data step, and for the same reason: rebuilding the
        // envelopes of operations written before v5 reads the objects and
        // revisions they describe, which recovery may just have settled.
        operations::adopt_legacy_envelopes(&store)?;
        Ok(store)
    }

    pub fn schema_version(&self) -> Result<i64> {
        current_version(&self.conn)
    }

    pub fn device_id(&self) -> Result<String> {
        let cached = self.cache.borrow().device_id.clone();
        if let Some(id) = cached {
            return Ok(id);
        }
        let id: String = self.conn.query_row(
            "SELECT value FROM meta WHERE key = 'device_id'",
            [],
            |row| row.get(0),
        )?;
        self.cache.borrow_mut().device_id = Some(id.clone());
        Ok(id)
    }

    /// Forget the cached device id. Called by the one thing that changes
    /// it (`reenrol_as_new_device`), so re-enrolment cannot leave this
    /// handle answering with the identity it has just left behind.
    pub(crate) fn forget_device_id(&self) {
        self.cache.borrow_mut().device_id = None;
    }

    /// Run something against the refused-device set, refreshing it from
    /// the store first. The read is a primary-key lookup and happens
    /// every time; only the parse is cached, keyed on the text it parsed.
    pub(crate) fn with_refused<T>(&self, act: impl FnOnce(&[String]) -> T) -> Result<T> {
        let raw: Option<String> = self
            .conn
            .query_row(
                "SELECT value FROM meta WHERE key = ?1",
                [crate::operations::REFUSED_DEVICES],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(KernelError::from(e)),
            })?;
        let Some(raw) = raw else { return Ok(act(&[])) };
        {
            let cache = self.cache.borrow();
            if cache.refused_raw.as_deref() == Some(raw.as_str()) {
                return Ok(act(&cache.refused));
            }
        }
        let parsed: serde_json::Value =
            serde_json::from_str(&raw).unwrap_or(serde_json::Value::Null);
        let devices: Vec<String> = parsed
            .as_array()
            .map(|rows| {
                rows.iter()
                    .filter_map(|v| v.as_str())
                    .map(str::to_string)
                    .collect()
            })
            .unwrap_or_default();
        let mut cache = self.cache.borrow_mut();
        cache.refused_raw = Some(raw);
        cache.refused = devices;
        Ok(act(&cache.refused))
    }

    /// The public half of the identity key belonging to the device that
    /// owns `device_id`, or `None` on a vault no M5 kernel has opened.
    /// Written by M5b, whose re-enrolment rule (ADR 0019 § 4) reads it to
    /// tell "same device" from "this vault came from elsewhere"; the
    /// accessors live here so that rule has one home when it arrives.
    pub fn identity_pub(&self) -> Result<Option<String>> {
        self.conn
            .query_row(
                "SELECT value FROM meta WHERE key = 'identity_pub'",
                [],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(KernelError::from(e)),
            })
    }

    pub fn set_identity_pub(&self, public_key: &str) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES ('identity_pub', ?1)",
            [public_key],
        )?;
        Ok(())
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
            blobs_committed: count(
                "SELECT COUNT(*) FROM blobs WHERE state = 'committed' AND origin = 'local'",
            )?,
            blobs_awaiting: count(
                "SELECT COUNT(*) FROM blobs WHERE state = 'committed' AND origin = 'sync'",
            )?,
            alerts: count("SELECT COUNT(*) FROM integrity_alerts")?,
            events: count("SELECT COUNT(*) FROM events")?,
            jobs_pending: count("SELECT COUNT(*) FROM jobs WHERE state IN ('pending','leased')")?,
            jobs_parked: count(
                "SELECT COUNT(*) FROM jobs j
                 JOIN subscriptions s ON s.subscription_id = j.subscription_id
                 WHERE j.state IN ('pending','leased') AND s.enabled = 0",
            )?,
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
            .prepare("SELECT blob_id, rel_path, sha256, bytes, state, origin FROM blobs")?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, String>(4)?,
                row.get::<_, String>(5)?,
            ))
        })?;
        for row in rows {
            let (blob_id, rel_path, sha256, bytes, state, origin) = row?;
            if state != "ready" {
                // Bytes still arriving over a transport are a documented
                // pending state, not damage: the operation applied before
                // its content finished, exactly as ADR 0005 intends, and
                // `status().blobs_awaiting` is where it is visible.
                if origin != "sync" {
                    problems.push(format!("blob {blob_id} is {state}, not ready"));
                }
                continue;
            }
            let path = self.vault.join(&rel_path);
            // Streamed, never resident: a full check of a vault holding
            // hour-long recordings must not need the largest of them in
            // memory. The length comes back from the same pass, so the
            // file is opened once and read once.
            match std::fs::File::open(&path)
                .and_then(|mut file| crate::blobs::digest_reader(&mut file))
            {
                Err(_) => problems.push(format!("blob {blob_id} missing at {rel_path}")),
                Ok((digest, len)) => {
                    if len as i64 != bytes {
                        problems.push(format!(
                            "blob {blob_id} size mismatch at {rel_path}: {len} != {bytes}"
                        ));
                    } else if digest != sha256 {
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
    /// Blobs whose operation has applied but whose bytes are still in
    /// transit. A pending state, not damage: `verify` says so too.
    pub blobs_awaiting: i64,
    pub alerts: i64,
    pub events: i64,
    /// Work that is waiting, whether or not anything can currently take
    /// it. Deliberately unchanged in meaning: a status line that quietly
    /// stopped counting parked work would make "nothing is happening"
    /// look like "there is nothing to do".
    pub jobs_pending: i64,
    /// Of the waiting work, how much belongs to a subscription that is
    /// switched off — parked by a disabled plugin, not stuck (ADR 0011:
    /// off means nothing is queued and waiting jobs are parked). A subset
    /// of `jobs_pending`, so the two are read together: `jobs_pending`
    /// minus `jobs_parked` is what an enabled plugin could run now.
    ///
    /// It does **not** count work whose plugin is enabled but whose
    /// implementation this build does not carry — Android with no local
    /// whisper, say. That job is claimable and simply never claimed here,
    /// which is by design (ADR 0006) and is not visible as a row.
    pub jobs_parked: i64,
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
    // v3 -> v4: the plugin registry (ADR 0011). Vault state, not device
    // state, so the app, the CLI and the agent agree on what may run.
    // A removed plugin keeps its row as a tombstone: re-registering a
    // bundled implementation on the next launch must not resurrect what
    // someone deliberately took away. Grants live in their own table so
    // an update that declares a new capability cannot inherit consent.
    "CREATE TABLE plugins (
         plugin_id TEXT PRIMARY KEY,
         name TEXT NOT NULL,
         class TEXT NOT NULL,
         profile TEXT NOT NULL,
         required INTEGER NOT NULL DEFAULT 0,
         processor_id TEXT,
         manifest TEXT NOT NULL,
         settings TEXT NOT NULL DEFAULT '{}',
         state TEXT NOT NULL CHECK (state IN ('enabled','disabled','removed')),
         installed_ms INTEGER NOT NULL
     );
     CREATE TABLE plugin_grants (
         plugin_id TEXT NOT NULL REFERENCES plugins(plugin_id),
         capability TEXT NOT NULL,
         granted_ms INTEGER NOT NULL,
         PRIMARY KEY (plugin_id, capability)
     );",
    // v4 -> v5: operations become bidirectional (ADR 0019 M5a). The
    // kernel has written operations since v1 and read none; this step
    // adds everything reading one requires.
    //
    // `sync_frontiers` is the per-device acknowledged position — how far
    // this device has applied another's stream. It is deliberately not
    // MAX(op_seq): a halted stream may hold operations past its frontier.
    //
    // `envelope` stores the bytes as received, not merely the columns
    // parsed out of them. A full replica must forward a third device's
    // operations byte-exactly, and any signature added later must verify
    // over the bytes that were signed (ADR 0019 § 9).
    //
    // `events.origin_device` is the origin-device processing policy: work
    // belongs to the device that captured it, so an applied operation
    // announces itself locally but queues nothing, and `backfill_jobs`
    // must not catch up another device's captures either.
    //
    // `blobs.origin` separates bytes this device staged from bytes that
    // are still arriving over a transport, so startup recovery does not
    // hunt for a stage file that was never here and `verify` does not
    // report an in-flight transfer as damage.
    //
    // `meta.op_seq` retires: the local sequence is MAX(op_seq) for this
    // device, which is what the global counter held while this kernel
    // wrote nobody's operations but its own, and which stays correct once
    // it writes other devices' rows too. Deleting the key is the
    // migration of the counter — one fact, one home.
    //
    // `meta.identity_pub` needs nothing: meta is key-value, so M5b writes
    // the key without a schema change.
    "CREATE TABLE sync_frontiers (
         device_id TEXT PRIMARY KEY,
         applied_seq INTEGER NOT NULL
     );
     CREATE TABLE integrity_alerts (
         alert_id TEXT PRIMARY KEY,
         kind TEXT NOT NULL,
         subject TEXT NOT NULL,
         detail TEXT NOT NULL,
         raised_ms INTEGER NOT NULL,
         UNIQUE (kind, subject)
     );
     ALTER TABLE operations ADD COLUMN envelope BLOB NOT NULL DEFAULT x'';
     ALTER TABLE events ADD COLUMN origin_device TEXT NOT NULL DEFAULT '';
     ALTER TABLE blobs ADD COLUMN origin TEXT NOT NULL DEFAULT 'local';
     UPDATE events
        SET origin_device =
            COALESCE((SELECT value FROM meta WHERE key = 'device_id'), '')
      WHERE origin_device = '';
     DELETE FROM meta WHERE key = 'op_seq';",
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
            crate::crashpoints::crashpoint("migration.during");
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
    fn an_older_store_upgrades_in_place_without_losing_what_it_held() {
        let dir = tempfile::tempdir().expect("tempdir");
        let local = dir.path().join(".gilune").join("local");
        std::fs::create_dir_all(&local).expect("dirs");
        let conn = rusqlite::Connection::open(local.join("kernel.db")).expect("raw");
        conn.execute_batch("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value NOT NULL);")
            .expect("meta");
        let previous = SCHEMA_VERSION - 1;
        for step in &MIGRATIONS[..previous as usize] {
            conn.execute_batch(step).expect("migration step");
        }
        conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?1)",
            [previous],
        )
        .expect("stamp version");
        conn.execute(
            "INSERT INTO objects (object_id, type_id, type_schema, created_ms)
             VALUES ('kept', 'org.memory.source', 1, 1)",
            [],
        )
        .expect("existing data");
        drop(conn);

        let store = Store::open(dir.path()).expect("open a store one version behind");
        assert_eq!(store.schema_version().expect("version"), SCHEMA_VERSION);
        assert_eq!(
            store.status().expect("status").sources,
            1,
            "an upgrade never costs the user what they already had"
        );
        store
            .register_plugin(&crate::vault::plugin_descriptor())
            .expect("the new schema is usable straight after the upgrade");
        assert_eq!(store.installed_plugins().expect("list").len(), 1);
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
