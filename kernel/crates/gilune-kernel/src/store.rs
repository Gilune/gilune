//! The kernel store: one SQLite database under `.gilune/local/` inside
//! the vault (ADR 0002, ADR 0007 layout). Device-local by contract —
//! never synced, never exported; the vault's visible files are the
//! projection, this is the authority. Opening runs migrations; the
//! schema grows only through the registry below so every version is a
//! tested upgrade path (constitution rule 12).

use crate::{fs, KernelError, Result};
use std::path::{Path, PathBuf};

/// Newest schema this kernel writes. An older database upgrades on open;
/// a newer one is refused (fail closed, rule 10) rather than guessed at.
/// Version 0 is the bootstrap (meta table only); the first real schema
/// arrives with the capture tables in M1.
pub const SCHEMA_VERSION: i64 = 0;

pub struct Store {
    conn: rusqlite::Connection,
    vault: PathBuf,
}

impl Store {
    /// Open (or create) the store for a vault directory, running any
    /// pending migrations. The vault directory itself is created if
    /// absent; a path that exists but is not a directory is refused.
    pub fn open(vault: impl Into<PathBuf>) -> Result<Store> {
        let vault = vault.into();
        if vault.exists() && !vault.is_dir() {
            return Err(KernelError::VaultUnusable {
                reason: format!("{} exists and is not a directory", vault.display()),
            });
        }
        let local = vault.join(".gilune").join("local");
        std::fs::create_dir_all(&local).map_err(|e| KernelError::Io {
            path: local.clone(),
            source: e,
        })?;
        fs::fsync_dir(&local)?;

        let conn = rusqlite::Connection::open(local.join("kernel.db"))?;
        conn.pragma_update(None, "journal_mode", "wal")?;
        conn.pragma_update(None, "foreign_keys", "on")?;
        migrate(&conn)?;
        Ok(Store { conn, vault })
    }

    pub fn schema_version(&self) -> Result<i64> {
        current_version(&self.conn)
    }

    pub fn vault(&self) -> &Path {
        &self.vault
    }
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
const MIGRATIONS: &[&str] = &[];

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
    fn reopen_is_idempotent_and_preserves_version() {
        let dir = tempfile::tempdir().expect("tempdir");
        drop(Store::open(dir.path()).expect("first open"));
        let store = Store::open(dir.path()).expect("reopen");
        assert_eq!(store.schema_version().expect("version"), SCHEMA_VERSION);
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
