//! Spike S1 diagnostic kernel.
//!
//! A deliberately small surface that exercises every boundary mechanism
//! ADR 0001's acceptance gate names: records with optionals, typed errors,
//! a real SQLite open + migration, chunked blob streaming (no whole-blob
//! copy crosses the FFI), async work with cancellation observable from the
//! Rust side, callback/event delivery, and a panic to test crash mapping.
//! No domain types: this is plumbing evidence, not product.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

uniffi::setup_scaffolding!();

// ---------------------------------------------------------------- contract

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum KernelError {
    #[error("storage: {detail}")]
    Storage { detail: String },
    #[error("not found: {id}")]
    NotFound { id: String },
    #[error("invalid: {detail}")]
    Invalid { detail: String },
    #[error("cancelled")]
    Cancelled,
}

impl From<rusqlite::Error> for KernelError {
    fn from(e: rusqlite::Error) -> Self {
        KernelError::Storage { detail: e.to_string() }
    }
}
impl From<std::io::Error> for KernelError {
    fn from(e: std::io::Error) -> Self {
        KernelError::Storage { detail: e.to_string() }
    }
}

type Result<T> = std::result::Result<T, KernelError>;

/// A record with an optional field, so the boundary proves Option mapping.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Record {
    pub id: String,
    pub body: String,
    pub note: Option<String>,
    pub created_ms: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct KernelInfo {
    pub kernel_version: String,
    pub schema_version: i64,
    pub db_path: String,
    pub opened_ms: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct KernelEvent {
    pub kind: String,
    pub detail: String,
    pub at_ms: i64,
}

/// Implemented in Kotlin; called from Rust. Wake-up/event delivery evidence.
#[uniffi::export(with_foreign)]
pub trait EventListener: Send + Sync {
    fn on_event(&self, event: KernelEvent);
}

// ------------------------------------------------------------------ kernel

struct Inner {
    conn: rusqlite::Connection,
    dir: PathBuf,
}

#[derive(uniffi::Object)]
pub struct Kernel {
    inner: Mutex<Inner>,
    listeners: Mutex<Vec<Arc<dyn EventListener>>>,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("clock before epoch")
        .as_millis() as i64
}

static LAST_PANIC: Mutex<Option<String>> = Mutex::new(None);

/// The crash-symbolication gate item: capture message + backtrace for the
/// last panic so the shell can fetch a diagnosable report across the
/// boundary (uniffi only carries the panic message into the exception).
fn install_panic_hook() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let default = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let backtrace = std::backtrace::Backtrace::force_capture();
            *LAST_PANIC.lock().unwrap() = Some(format!("{info}\nbacktrace:\n{backtrace}"));
            default(info);
        }));
    });
}

#[uniffi::export]
pub fn last_panic_report() -> Option<String> {
    LAST_PANIC.lock().unwrap().clone()
}

/// Two migration steps run on open so "kernel initialisation and migration"
/// is a real code path, not a stub.
fn migrate(conn: &rusqlite::Connection) -> Result<i64> {
    conn.pragma_update(None, "journal_mode", "wal")?;
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
    )?;
    let version: i64 = conn
        .query_row("SELECT value FROM meta WHERE key = 'schema_version'", [], |r| {
            r.get::<_, String>(0)
        })
        .map(|v| v.parse().unwrap_or(0))
        .unwrap_or(0);
    if version < 1 {
        conn.execute_batch(
            "CREATE TABLE records (
                 id TEXT PRIMARY KEY, body TEXT NOT NULL, created_ms INTEGER NOT NULL
             );
             INSERT OR REPLACE INTO meta VALUES ('schema_version', '1');",
        )?;
    }
    if version < 2 {
        conn.execute_batch(
            "ALTER TABLE records ADD COLUMN note TEXT;
             INSERT OR REPLACE INTO meta VALUES ('schema_version', '2');",
        )?;
    }
    Ok(2)
}

#[uniffi::export]
impl Kernel {
    /// Open (or create) a kernel at `dir`, running migrations.
    #[uniffi::constructor]
    pub fn open(dir: String) -> Result<Arc<Self>> {
        install_panic_hook();
        let dir = PathBuf::from(dir);
        std::fs::create_dir_all(dir.join("blobs"))?;
        let conn = rusqlite::Connection::open(dir.join("s1.db"))?;
        migrate(&conn)?;
        Ok(Arc::new(Self {
            inner: Mutex::new(Inner { conn, dir }),
            listeners: Mutex::new(Vec::new()),
        }))
    }

    pub fn info(&self) -> Result<KernelInfo> {
        let inner = self.inner.lock().unwrap();
        let schema_version: i64 = inner
            .conn
            .query_row("SELECT value FROM meta WHERE key = 'schema_version'", [], |r| {
                r.get::<_, String>(0)
            })
            .map(|v| v.parse().unwrap_or(0))?;
        Ok(KernelInfo {
            kernel_version: env!("CARGO_PKG_VERSION").to_string(),
            schema_version,
            db_path: inner.dir.join("s1.db").to_string_lossy().to_string(),
            opened_ms: now_ms(),
        })
    }

    /// One transaction; emits an event so commit â†’ wake-up is observable.
    pub fn put_record(&self, body: String, note: Option<String>) -> Result<Record> {
        if body.is_empty() {
            return Err(KernelError::Invalid { detail: "empty body".into() });
        }
        let record = Record {
            id: uuid::Uuid::now_v7().to_string(),
            body,
            note,
            created_ms: now_ms(),
        };
        {
            let inner = self.inner.lock().unwrap();
            inner.conn.execute(
                "INSERT INTO records (id, body, note, created_ms) VALUES (?1, ?2, ?3, ?4)",
                rusqlite::params![record.id, record.body, record.note, record.created_ms],
            )?;
        }
        self.emit("record-committed", &record.id);
        Ok(record)
    }

    pub fn get_record(&self, id: String) -> Result<Option<Record>> {
        let inner = self.inner.lock().unwrap();
        let found = inner
            .conn
            .query_row(
                "SELECT id, body, note, created_ms FROM records WHERE id = ?1",
                [&id],
                |r| {
                    Ok(Record {
                        id: r.get(0)?,
                        body: r.get(1)?,
                        note: r.get(2)?,
                        created_ms: r.get(3)?,
                    })
                },
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        Ok(found)
    }

    pub fn record_count(&self) -> Result<u64> {
        let inner = self.inner.lock().unwrap();
        let n: i64 = inner.conn.query_row("SELECT COUNT(*) FROM records", [], |r| r.get(0))?;
        Ok(n as u64)
    }

    pub fn subscribe(&self, listener: Arc<dyn EventListener>) {
        self.listeners.lock().unwrap().push(listener);
    }

    /// Chunked blob write: bytes cross the boundary chunk by chunk, staged
    /// to disk, promoted by rename on finish â€” never held whole in memory.
    pub fn open_blob_write(self: Arc<Self>, name: String) -> Result<Arc<BlobWriter>> {
        if name.contains(['/', '\\', ':']) {
            return Err(KernelError::Invalid { detail: "blob name must be a bare name".into() });
        }
        let dir = self.inner.lock().unwrap().dir.clone();
        let stage = dir.join("blobs").join(format!(".stage-{name}"));
        let file = std::fs::File::create(&stage)?;
        Ok(Arc::new(BlobWriter {
            kernel: self.clone(),
            state: Mutex::new(Some(WriterState {
                file,
                stage,
                target: dir.join("blobs").join(name),
                hasher: <sha2::Sha256 as sha2::Digest>::new(),
                bytes: 0,
            })),
        }))
    }

    pub fn open_blob_read(&self, name: String) -> Result<Arc<BlobReader>> {
        let dir = self.inner.lock().unwrap().dir.clone();
        let path = dir.join("blobs").join(&name);
        let file = std::fs::File::open(&path)
            .map_err(|_| KernelError::NotFound { id: name })?;
        Ok(Arc::new(BlobReader { state: Mutex::new(file) }))
    }

    /// Deliberate panic, so the shell can record how a Rust panic surfaces
    /// in Kotlin (exception type, message, symbolication).
    pub fn trigger_panic(&self) {
        panic!("deliberate spike-s1 panic: boundary diagnostic");
    }
}

/// Async work with observable cancellation: if the Kotlin coroutine is
/// cancelled, the Rust future is dropped and the guard emits `async-dropped`
/// â€” the shell asserts that event to prove Rust-side cleanup ran.
#[uniffi::export(async_runtime = "tokio")]
impl Kernel {
    pub async fn slow_add(self: Arc<Self>, a: i64, b: i64, delay_ms: u64) -> Result<i64> {
        struct DropGuard {
            kernel: Arc<Kernel>,
            completed: AtomicBool,
        }
        impl Drop for DropGuard {
            fn drop(&mut self) {
                if !self.completed.load(Ordering::SeqCst) {
                    self.kernel.emit("async-dropped", "slow_add cancelled before completion");
                }
            }
        }
        let guard = DropGuard { kernel: self.clone(), completed: AtomicBool::new(false) };
        tokio::time::sleep(std::time::Duration::from_millis(delay_ms)).await;
        guard.completed.store(true, Ordering::SeqCst);
        self.emit("async-completed", "slow_add finished");
        Ok(a + b)
    }
}

impl Kernel {
    fn emit(&self, kind: &str, detail: &str) {
        let event = KernelEvent {
            kind: kind.to_string(),
            detail: detail.to_string(),
            at_ms: now_ms(),
        };
        for l in self.listeners.lock().unwrap().iter() {
            l.on_event(event.clone());
        }
    }
}

// ---------------------------------------------------------------- streaming

struct WriterState {
    file: std::fs::File,
    stage: PathBuf,
    target: PathBuf,
    hasher: sha2::Sha256,
    bytes: u64,
}

#[derive(uniffi::Object)]
pub struct BlobWriter {
    kernel: Arc<Kernel>,
    state: Mutex<Option<WriterState>>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct BlobHandle {
    pub name: String,
    pub sha256: String,
    pub bytes: u64,
}

#[uniffi::export]
impl BlobWriter {
    pub fn write_chunk(&self, chunk: Vec<u8>) -> Result<()> {
        use sha2::Digest;
        use std::io::Write;
        let mut state = self.state.lock().unwrap();
        let s = state.as_mut().ok_or(KernelError::Invalid { detail: "writer finished".into() })?;
        s.file.write_all(&chunk)?;
        s.hasher.update(&chunk);
        s.bytes += chunk.len() as u64;
        Ok(())
    }

    /// fsync, rename into place, report hash â€” S2's promote shape in miniature.
    pub fn finish(&self) -> Result<BlobHandle> {
        use sha2::Digest;
        let s = self
            .state
            .lock()
            .unwrap()
            .take()
            .ok_or(KernelError::Invalid { detail: "writer finished".into() })?;
        s.file.sync_all()?;
        drop(s.file);
        std::fs::rename(&s.stage, &s.target)?;
        let handle = BlobHandle {
            name: s.target.file_name().unwrap().to_string_lossy().to_string(),
            sha256: hex::encode(s.hasher.finalize()),
            bytes: s.bytes,
        };
        self.kernel.emit("blob-ready", &handle.name);
        Ok(handle)
    }
}

#[derive(uniffi::Object)]
pub struct BlobReader {
    state: Mutex<std::fs::File>,
}

#[uniffi::export]
impl BlobReader {
    /// Returns None at end of stream. Chunk size is the caller's choice, so
    /// a deliberately slow consumer is a valid test, not an error.
    pub fn read_chunk(&self, max_bytes: u32) -> Result<Option<Vec<u8>>> {
        use std::io::Read;
        let mut buf = vec![0u8; max_bytes as usize];
        let n = self.state.lock().unwrap().read(&mut buf)?;
        if n == 0 {
            return Ok(None);
        }
        buf.truncate(n);
        Ok(Some(buf))
    }
}

// -------------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;

    fn kernel() -> (tempfile::TempDir, Arc<Kernel>) {
        let dir = tempfile::tempdir().unwrap();
        let k = Kernel::open(dir.path().to_string_lossy().to_string()).unwrap();
        (dir, k)
    }

    #[test]
    fn open_migrate_put_get_roundtrip() {
        let (_dir, k) = kernel();
        assert_eq!(k.info().unwrap().schema_version, 2);
        let r = k.put_record("hello".into(), Some("note".into())).unwrap();
        let got = k.get_record(r.id.clone()).unwrap().unwrap();
        assert_eq!(got.body, "hello");
        assert_eq!(got.note.as_deref(), Some("note"));
        assert!(k.get_record("missing".into()).unwrap().is_none());
        assert!(matches!(
            k.put_record("".into(), None),
            Err(KernelError::Invalid { .. })
        ));
    }

    #[test]
    fn reopen_preserves_data_and_reruns_migration_idempotently() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().to_string_lossy().to_string();
        let k = Kernel::open(path.clone()).unwrap();
        k.put_record("survives".into(), None).unwrap();
        drop(k);
        let k = Kernel::open(path).unwrap();
        assert_eq!(k.record_count().unwrap(), 1);
        assert_eq!(k.info().unwrap().schema_version, 2);
    }

    #[test]
    fn blob_stream_roundtrip_hashes_match() {
        let (_dir, k) = kernel();
        let payload: Vec<u8> = (0..1_000_000u32).map(|i| (i % 251) as u8).collect();
        let w = k.clone().open_blob_write("big.bin".into()).unwrap();
        for chunk in payload.chunks(64 * 1024) {
            w.write_chunk(chunk.to_vec()).unwrap();
        }
        let handle = w.finish().unwrap();
        assert_eq!(handle.bytes, payload.len() as u64);

        let r = k.open_blob_read("big.bin".into()).unwrap();
        let mut back = Vec::new();
        while let Some(chunk) = r.read_chunk(64 * 1024).unwrap() {
            back.extend_from_slice(&chunk);
        }
        assert_eq!(back, payload);
        use sha2::Digest;
        assert_eq!(handle.sha256, hex::encode(sha2::Sha256::digest(&back)));
    }

    #[test]
    fn panic_report_carries_message_and_backtrace() {
        let (_dir, k) = kernel();
        let caught = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| k.trigger_panic()));
        assert!(caught.is_err());
        let report = last_panic_report().expect("hook should have captured");
        assert!(report.contains("deliberate spike-s1 panic"));
        assert!(report.contains("backtrace:"));
        // Symbol quality is platform evidence, printed for the README.
        println!("RUST-HOST PANIC REPORT:\n{}", &report[..report.len().min(1500)]);
    }

    struct Collect(Mutex<Vec<KernelEvent>>);
    impl EventListener for Collect {
        fn on_event(&self, event: KernelEvent) {
            self.0.lock().unwrap().push(event);
        }
    }

    #[test]
    fn events_fire_on_commit() {
        let (_dir, k) = kernel();
        let c = Arc::new(Collect(Mutex::new(Vec::new())));
        k.subscribe(c.clone());
        k.put_record("ping".into(), None).unwrap();
        let seen = c.0.lock().unwrap();
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].kind, "record-committed");
    }

    #[tokio::test]
    async fn async_completes_and_cancellation_emits_drop_event() {
        let (_dir, k) = kernel();
        let c = Arc::new(Collect(Mutex::new(Vec::new())));
        k.subscribe(c.clone());
        assert_eq!(k.clone().slow_add(2, 3, 10).await.unwrap(), 5);

        let fut = k.clone().slow_add(1, 1, 60_000);
        // Poll once, then drop: the guard must emit async-dropped.
        tokio::select! {
            _ = fut => panic!("should not complete"),
            _ = tokio::time::sleep(std::time::Duration::from_millis(50)) => {}
        }
        let kinds: Vec<String> =
            c.0.lock().unwrap().iter().map(|e| e.kind.clone()).collect();
        assert!(kinds.contains(&"async-completed".to_string()));
        assert!(kinds.contains(&"async-dropped".to_string()));
    }
}
