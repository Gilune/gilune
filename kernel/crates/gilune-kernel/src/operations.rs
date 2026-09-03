//! Operations: the kernel's sync substrate, in both directions (ADR 0005,
//! ADR 0019 § 6). This module owns the operation envelope and its
//! versioned codec, the local per-device sequence, the per-device
//! frontier, `record` and `apply`, and the halt policy that makes a
//! hostile or broken peer visible instead of silent.
//!
//! The envelope is a public contract with portable test vectors under
//! `kernel/vectors/operations-v1/`. Two rules make it one:
//!
//! - **Bytes are authority.** An applied operation is stored exactly as
//!   it was received, because a full replica forwards a third device's
//!   operations byte-exactly and any signature added later must verify
//!   over the bytes that were signed. The columns beside it are a parsed
//!   projection, never the source of truth on the wire.
//! - **Encoding is deterministic.** The same operation always encodes to
//!   the same bytes, on every platform, so a replica can re-derive what
//!   it forwards and two devices agree on what they are talking about.

use crate::alerts;
use crate::limits::Limits;
use crate::store::Store;
use crate::{fs, ids, KernelError, Result};

/// Codec version. A version this kernel does not know is a halt, not a
/// guess: an envelope it cannot parse is one it cannot safely apply.
pub const ENVELOPE_VERSION: i64 = 1;

/// An object came into existence, with its first revision and any bytes
/// it carries. Both a capture and a processor's derived object are this
/// operation; they differ in payload, not in kind.
pub const OBJECT_CREATED: &str = "object-created";
/// An object's document changed. Carries the heads it supersedes, which
/// is what lets concurrent edits keep both (ADR 0005).
pub const OBJECT_REVISED: &str = "object-revised";
/// An object was deleted. History keeps the object; the projection loses
/// its file.
pub const TOMBSTONE: &str = "tombstone";

const MAX_ID_CHARS: usize = 128;
const MAX_KIND_CHARS: usize = 64;

/// One operation on the wire. Field order in the encoded form is
/// alphabetical and fixed; see `encode`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Envelope {
    pub version: i64,
    pub op_id: String,
    pub device_id: String,
    pub op_seq: i64,
    pub kind: String,
    pub target_id: String,
    pub created_ms: i64,
    pub payload: serde_json::Value,
}

/// Why an envelope was refused. Every variant is a halt: the stream
/// stops, nothing past the fault applies, and the condition is visible.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodeError {
    /// Larger than `Limits::max_envelope_bytes`.
    TooLarge { bytes: usize, limit: usize },
    /// Not UTF-8, not JSON, or not a JSON object.
    Malformed { detail: String },
    /// A required field is absent, of the wrong type, or out of range.
    Field { field: &'static str, detail: String },
    /// A codec version this kernel cannot read.
    UnsupportedVersion { found: i64, supported: i64 },
}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DecodeError::TooLarge { bytes, limit } => {
                write!(f, "envelope is {bytes} bytes, over the {limit}-byte limit")
            }
            DecodeError::Malformed { detail } => write!(f, "malformed envelope: {detail}"),
            DecodeError::Field { field, detail } => write!(f, "field {field}: {detail}"),
            DecodeError::UnsupportedVersion { found, supported } => write!(
                f,
                "envelope version {found} is newer than this kernel reads ({supported})"
            ),
        }
    }
}

/// Encode an envelope to its canonical bytes. Deterministic on every
/// platform: compact JSON with keys in a single fixed order, which
/// `serde_json`'s sorted maps give us and a test pins.
pub fn encode(envelope: &Envelope) -> Vec<u8> {
    serde_json::json!({
        "created_ms": envelope.created_ms,
        "device_id": envelope.device_id,
        "kind": envelope.kind,
        "op_id": envelope.op_id,
        "op_seq": envelope.op_seq,
        "payload": envelope.payload,
        "target_id": envelope.target_id,
        "version": envelope.version,
    })
    .to_string()
    .into_bytes()
}

/// Decode bytes that arrived from somewhere else. Adversarial by
/// assumption: every field is checked for presence, type and range
/// before any of it is believed, and the size cap is applied before the
/// parser is handed the bytes at all.
pub fn decode(bytes: &[u8], limits: &Limits) -> std::result::Result<Envelope, DecodeError> {
    if bytes.len() > limits.max_envelope_bytes {
        return Err(DecodeError::TooLarge {
            bytes: bytes.len(),
            limit: limits.max_envelope_bytes,
        });
    }
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| {
        DecodeError::Malformed { detail: e.to_string() }
    })?;
    let object = value.as_object().ok_or_else(|| DecodeError::Malformed {
        detail: "top level is not a JSON object".to_string(),
    })?;

    let version = integer(object, "version")?;
    if version != ENVELOPE_VERSION {
        return Err(DecodeError::UnsupportedVersion {
            found: version,
            supported: ENVELOPE_VERSION,
        });
    }
    let op_id = identifier(object, "op_id")?;
    let device_id = identifier(object, "device_id")?;
    let target_id = identifier(object, "target_id")?;
    let kind = bounded_text(object, "kind", MAX_KIND_CHARS)?;
    let op_seq = integer(object, "op_seq")?;
    if op_seq < 1 {
        return Err(DecodeError::Field {
            field: "op_seq",
            detail: format!("{op_seq} is not a positive sequence number"),
        });
    }
    let created_ms = integer(object, "created_ms")?;
    if created_ms < 0 {
        return Err(DecodeError::Field {
            field: "created_ms",
            detail: "a timestamp before the epoch".to_string(),
        });
    }
    let payload = object
        .get("payload")
        .ok_or(DecodeError::Field { field: "payload", detail: "absent".to_string() })?;
    if !payload.is_object() {
        return Err(DecodeError::Field {
            field: "payload",
            detail: "not a JSON object".to_string(),
        });
    }

    Ok(Envelope {
        version,
        op_id,
        device_id,
        op_seq,
        kind,
        target_id,
        created_ms,
        payload: payload.clone(),
    })
}

fn integer(
    object: &serde_json::Map<String, serde_json::Value>,
    field: &'static str,
) -> std::result::Result<i64, DecodeError> {
    let value = object
        .get(field)
        .ok_or(DecodeError::Field { field, detail: "absent".to_string() })?;
    // `as_i64` is the whole range check: a JSON number beyond i64, a
    // float, or a numeric string all fail here rather than truncating.
    value.as_i64().ok_or_else(|| DecodeError::Field {
        field,
        detail: format!("{value} is not an integer in range"),
    })
}

fn bounded_text(
    object: &serde_json::Map<String, serde_json::Value>,
    field: &'static str,
    max_chars: usize,
) -> std::result::Result<String, DecodeError> {
    let value = object
        .get(field)
        .ok_or(DecodeError::Field { field, detail: "absent".to_string() })?;
    let text = value.as_str().ok_or(DecodeError::Field {
        field,
        detail: "not a string".to_string(),
    })?;
    if text.is_empty() {
        return Err(DecodeError::Field { field, detail: "empty".to_string() });
    }
    if text.chars().count() > max_chars {
        return Err(DecodeError::Field {
            field,
            detail: format!("longer than {max_chars} characters"),
        });
    }
    Ok(text.to_string())
}

fn identifier(
    object: &serde_json::Map<String, serde_json::Value>,
    field: &'static str,
) -> std::result::Result<String, DecodeError> {
    let text = bounded_text(object, field, MAX_ID_CHARS)?;
    // An identifier reaches file names, alert subjects and log lines.
    // Control characters in any of those are somebody's exploit.
    if text.chars().any(|c| c.is_control()) {
        return Err(DecodeError::Field {
            field,
            detail: "contains control characters".to_string(),
        });
    }
    Ok(text)
}

// ------------------------------------------------------------ the write side

/// Record a local operation inside the caller's transaction, so a
/// committed change and the operation carrying it to other devices are
/// inseparable. The sequence is this device's own: `MAX(op_seq)` for it,
/// plus one, which is monotonic under the enclosing `BEGIN IMMEDIATE`
/// and stays correct now that the table also holds other devices' rows.
pub(crate) fn record(
    conn: &rusqlite::Connection,
    kind: &str,
    target_id: &str,
    payload: serde_json::Value,
    created_ms: i64,
) -> Result<String> {
    let device_id: String = conn.query_row(
        "SELECT value FROM meta WHERE key = 'device_id'",
        [],
        |row| row.get(0),
    )?;
    let op_seq = next_seq(conn, &device_id)?;
    let envelope = Envelope {
        version: ENVELOPE_VERSION,
        op_id: ids::new_id(),
        device_id,
        op_seq,
        kind: kind.to_string(),
        target_id: target_id.to_string(),
        created_ms,
        payload,
    };
    insert_operation(conn, &envelope, &encode(&envelope))?;
    Ok(envelope.op_id)
}

fn next_seq(conn: &rusqlite::Connection, device_id: &str) -> Result<i64> {
    let highest: i64 = conn.query_row(
        "SELECT COALESCE(MAX(op_seq), 0) FROM operations WHERE device_id = ?1",
        [device_id],
        |row| row.get(0),
    )?;
    Ok(highest + 1)
}

fn insert_operation(
    conn: &rusqlite::Connection,
    envelope: &Envelope,
    bytes: &[u8],
) -> Result<()> {
    conn.execute(
        "INSERT INTO operations
         (op_id, device_id, op_seq, kind, target_id, payload, created_ms, envelope)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        rusqlite::params![
            envelope.op_id,
            envelope.device_id,
            envelope.op_seq,
            envelope.kind,
            envelope.target_id,
            envelope.payload.to_string(),
            envelope.created_ms,
            bytes
        ],
    )?;
    Ok(())
}

// ------------------------------------------------------------- the read side

/// What applying one operation did.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Applied {
    pub op_id: String,
    pub device_id: String,
    pub op_seq: i64,
    /// The envelope had already been applied, or originated here: the
    /// frontier did not move and nothing was written.
    pub duplicate: bool,
    /// The kind is one no plugin here understands. The operation is
    /// stored and will be forwarded verbatim; nothing was interpreted.
    pub uninterpreted: bool,
    /// Bytes this device now needs before the object is whole.
    pub blob_wanted: Option<BlobNeed>,
}

/// A blob referenced by an applied operation whose content has not
/// arrived. `received` is how many bytes are already staged, so a
/// transfer resumes where it stopped rather than starting again.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BlobNeed {
    pub blob_id: String,
    pub sha256: String,
    pub bytes: u64,
    pub received: u64,
}

impl Store {
    /// The highest sequence number applied from a device. Not
    /// `MAX(op_seq)`: a halted stream can hold operations past its
    /// frontier, and the frontier is what may be resumed from.
    pub fn sync_frontier(&self, device_id: &str) -> Result<i64> {
        Ok(self.conn().query_row(
            "SELECT COALESCE((SELECT applied_seq FROM sync_frontiers WHERE device_id = ?1), 0)",
            [device_id],
            |row| row.get(0),
        )?)
    }

    /// Every device this one has applied anything from, with its
    /// frontier. The local device is absent: it has no frontier because
    /// it never applies its own operations.
    pub fn sync_frontiers(&self) -> Result<Vec<(String, i64)>> {
        let mut stmt = self
            .conn()
            .prepare("SELECT device_id, applied_seq FROM sync_frontiers ORDER BY device_id")?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Every device whose operations this replica holds, with the highest
    /// sequence it holds for each — what a peer is told when it asks what
    /// is available. A full replica offers other devices' history, which
    /// is what lets a third device converge without meeting the second.
    pub fn operation_devices(&self) -> Result<Vec<(String, i64)>> {
        let mut stmt = self.conn().prepare(
            "SELECT device_id, MAX(op_seq) FROM operations
             WHERE LENGTH(envelope) > 0
             GROUP BY device_id ORDER BY device_id",
        )?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Raw envelopes from one device after `seq`, in sequence order,
    /// bounded by count. Byte-exact: what is forwarded is what arrived.
    /// Contiguity is not asserted here — the receiver's gap check is the
    /// one that matters, and it is the one that halts.
    pub fn operations_after(
        &self,
        device_id: &str,
        seq: i64,
        max_ops: usize,
    ) -> Result<Vec<Vec<u8>>> {
        let mut stmt = self.conn().prepare(
            "SELECT envelope FROM operations
             WHERE device_id = ?1 AND op_seq > ?2 AND LENGTH(envelope) > 0
             ORDER BY op_seq LIMIT ?3",
        )?;
        let rows = stmt.query_map(
            rusqlite::params![device_id, seq, max_ops as i64],
            |row| row.get::<_, Vec<u8>>(0),
        )?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Apply one operation received from `stream` — the peer that
    /// delivered these bytes, which is not necessarily the device that
    /// originated them.
    ///
    /// One transaction, so nothing is ever half-applied. Idempotent: an
    /// envelope already applied moves nothing and reports `duplicate`.
    /// Every refusal raises a visible alert and returns
    /// `KernelError::SyncHalted`, leaving the frontier where it was, so
    /// the stream resumes from exactly the same place once the condition
    /// clears.
    pub fn apply_operation(
        &self,
        stream: &str,
        bytes: &[u8],
        limits: Limits,
    ) -> Result<Applied> {
        let envelope = match decode(bytes, &limits) {
            Ok(envelope) => envelope,
            Err(error) => {
                let kind = match error {
                    DecodeError::TooLarge { .. } => alerts::SYNC_LIMIT_EXCEEDED,
                    DecodeError::UnsupportedVersion { .. } => alerts::SYNC_UNSUPPORTED_VERSION,
                    _ => alerts::SYNC_CORRUPT_OPERATION,
                };
                return Err(self.halt(kind, stream, &error.to_string()));
            }
        };

        // A device never applies its own operations: it wrote them, and
        // reapplying its own sequence space is how a fork starts.
        if envelope.device_id == self.device_id()? {
            return Ok(Applied {
                op_id: envelope.op_id,
                device_id: envelope.device_id,
                op_seq: envelope.op_seq,
                duplicate: true,
                uninterpreted: false,
                blob_wanted: None,
            });
        }

        let frontier = self.sync_frontier(&envelope.device_id)?;
        if envelope.op_seq <= frontier {
            return Ok(Applied {
                op_id: envelope.op_id,
                device_id: envelope.device_id,
                op_seq: envelope.op_seq,
                duplicate: true,
                uninterpreted: false,
                blob_wanted: None,
            });
        }
        if envelope.op_seq != frontier + 1 {
            let detail = format!(
                "expected seq {}, this envelope is {}",
                frontier + 1,
                envelope.op_seq
            );
            return Err(self.halt(alerts::SYNC_GAP, &envelope.device_id, &detail));
        }
        // An operation id already held, at a sequence the frontier says is
        // new, is not a duplicate — duplicates were caught above. It is
        // one identifier claiming two places in history, which no honest
        // sender produces. Halt on it by name rather than letting the
        // primary key raise a storage error nobody can act on.
        let reused: i64 = conn_count(self.conn(), &envelope.op_id)?;
        if reused > 0 {
            let detail = format!(
                "operation id {} is already held, but arrived again at seq {}",
                envelope.op_id, envelope.op_seq
            );
            return Err(self.halt(
                alerts::SYNC_CORRUPT_OPERATION,
                &envelope.device_id,
                &detail,
            ));
        }

        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let applied: Result<Applied> = (|| {
            insert_operation(conn, &envelope, bytes)?;
            let outcome = self.interpret(conn, &envelope, &limits)?;
            conn.execute(
                "INSERT INTO sync_frontiers (device_id, applied_seq) VALUES (?1, ?2)
                 ON CONFLICT(device_id) DO UPDATE SET applied_seq = ?2",
                rusqlite::params![envelope.device_id, envelope.op_seq],
            )?;
            crate::crashpoints::crashpoint("apply.commit.inside");
            Ok(outcome)
        })();
        let applied = match applied {
            Ok(applied) => {
                conn.execute_batch("COMMIT;")?;
                applied
            }
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                // The alert must outlive the rollback that the refusal
                // caused, so it is raised here and not inside.
                if let KernelError::SyncHalted { reason, .. } = &e {
                    let reason = reason.clone();
                    return Err(self.halt(
                        alerts::SYNC_LIMIT_EXCEEDED,
                        &envelope.device_id,
                        &reason,
                    ));
                }
                return Err(e);
            }
        };
        crate::crashpoints::crashpoint("apply.commit.after");

        // The stream is healthy again: whatever was standing about it
        // stops being reported without anyone dismissing it.
        for kind in [
            alerts::SYNC_GAP,
            alerts::SYNC_CORRUPT_OPERATION,
            alerts::SYNC_UNSUPPORTED_VERSION,
            alerts::SYNC_LIMIT_EXCEEDED,
        ] {
            alerts::clear(conn, kind, &envelope.device_id)?;
            alerts::clear(conn, kind, stream)?;
        }
        Ok(applied)
    }

    /// Raise the alert and name the halt. Split out so every refusal
    /// takes exactly one path to being visible.
    fn halt(&self, kind: &str, subject: &str, detail: &str) -> KernelError {
        if let Err(e) = alerts::raise(self.conn(), kind, subject, detail) {
            return e;
        }
        KernelError::SyncHalted {
            device_id: subject.to_string(),
            reason: detail.to_string(),
        }
    }

    /// Interpret one decoded envelope inside the apply transaction.
    /// Unknown kinds are preserved and forwarded but never interpreted
    /// (constitution rule 9): forward compatibility is what lets a device
    /// carry a plugin's data it has no plugin for.
    fn interpret(
        &self,
        conn: &rusqlite::Connection,
        envelope: &Envelope,
        limits: &Limits,
    ) -> Result<Applied> {
        let mut applied = Applied {
            op_id: envelope.op_id.clone(),
            device_id: envelope.device_id.clone(),
            op_seq: envelope.op_seq,
            duplicate: false,
            uninterpreted: false,
            blob_wanted: None,
        };
        let payload = &envelope.payload;
        match envelope.kind.as_str() {
            OBJECT_CREATED => {
                let object = payload.get("object").unwrap_or(&serde_json::Value::Null);
                let revision = payload.get("revision").unwrap_or(&serde_json::Value::Null);
                let object_id = text(object, "object_id");
                let revision_id = text(revision, "revision_id");
                if object_id.is_empty() || revision_id.is_empty() {
                    return Err(KernelError::SyncHalted {
                        device_id: envelope.device_id.clone(),
                        reason: "object-created names no object or no revision".to_string(),
                    });
                }
                conn.execute(
                    "INSERT OR IGNORE INTO objects
                     (object_id, type_id, type_schema, created_ms, deleted)
                     VALUES (?1, ?2, ?3, ?4, 0)",
                    rusqlite::params![
                        object_id,
                        text(object, "type_id"),
                        number(object, "type_schema").unwrap_or(1),
                        number(object, "created_ms").unwrap_or(envelope.created_ms)
                    ],
                )?;
                conn.execute(
                    "INSERT OR IGNORE INTO revisions
                     (revision_id, object_id, payload, origin, created_ms)
                     VALUES (?1, ?2, ?3, ?4, ?5)",
                    rusqlite::params![
                        revision_id,
                        object_id,
                        text(revision, "payload"),
                        text(revision, "origin"),
                        number(revision, "created_ms").unwrap_or(envelope.created_ms)
                    ],
                )?;
                conn.execute(
                    "INSERT OR IGNORE INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                    [&object_id, &revision_id],
                )?;
                if let Some(blob) = payload.get("blob").filter(|b| b.is_object()) {
                    applied.blob_wanted = self.accept_blob(conn, &object_id, blob, limits)?;
                }
                if let Some(derived_from) = payload.get("derived_from").and_then(|v| v.as_str()) {
                    conn.execute(
                        "INSERT OR IGNORE INTO provenance
                         (object_id, target_id, relation, processor_id)
                         VALUES (?1, ?2, 'derived-from', ?3)",
                        rusqlite::params![
                            object_id,
                            derived_from,
                            payload.get("processor_id").and_then(|v| v.as_str())
                        ],
                    )?;
                }
                self.announce(conn, envelope, &object_id, text(revision, "payload").as_str())?;
            }
            OBJECT_REVISED => {
                let revision = payload.get("revision").unwrap_or(&serde_json::Value::Null);
                let object_id = text(payload, "object_id");
                let revision_id = text(revision, "revision_id");
                if object_id.is_empty() || revision_id.is_empty() {
                    return Err(KernelError::SyncHalted {
                        device_id: envelope.device_id.clone(),
                        reason: "object-revised names no object or no revision".to_string(),
                    });
                }
                let known: i64 = conn.query_row(
                    "SELECT COUNT(*) FROM revisions WHERE revision_id = ?1",
                    [&revision_id],
                    |row| row.get(0),
                )?;
                if known == 0 {
                    conn.execute(
                        "INSERT INTO revisions
                         (revision_id, object_id, payload, origin, created_ms)
                         VALUES (?1, ?2, ?3, ?4, ?5)",
                        rusqlite::params![
                            revision_id,
                            object_id,
                            text(revision, "payload"),
                            text(revision, "origin"),
                            number(revision, "created_ms").unwrap_or(envelope.created_ms)
                        ],
                    )?;
                    // The revision supersedes exactly the heads it names.
                    // Any head it does not name was concurrent with it and
                    // is preserved — this line is the whole of ADR 0005's
                    // concurrent-heads contract on the receiving side.
                    if let Some(superseded) = payload.get("supersedes").and_then(|v| v.as_array()) {
                        for head in superseded.iter().filter_map(|v| v.as_str()) {
                            conn.execute(
                                "DELETE FROM heads WHERE object_id = ?1 AND revision_id = ?2",
                                [&object_id, &head.to_string()],
                            )?;
                        }
                    }
                    conn.execute(
                        "INSERT OR IGNORE INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                        [&object_id, &revision_id],
                    )?;
                    self.announce(
                        conn,
                        envelope,
                        &object_id,
                        text(revision, "payload").as_str(),
                    )?;
                }
            }
            TOMBSTONE => {
                conn.execute(
                    "UPDATE objects SET deleted = 1 WHERE object_id = ?1",
                    [&envelope.target_id],
                )?;
            }
            unknown => {
                applied.uninterpreted = true;
                alerts::raise(
                    conn,
                    alerts::SYNC_UNKNOWN_OP_KIND,
                    unknown,
                    &format!(
                        "operations of kind {unknown} are preserved and forwarded \
                         but not applied here (latest: {})",
                        envelope.op_id
                    ),
                )?;
            }
        }
        Ok(applied)
    }

    /// Announce an applied change locally without queueing work for it.
    /// The origin device processes its own captures (ADR 0005): recording
    /// `origin_device` is what keeps that true through `backfill_jobs`
    /// too, which would otherwise catch up another device's history.
    fn announce(
        &self,
        conn: &rusqlite::Connection,
        envelope: &Envelope,
        subject_id: &str,
        payload: &str,
    ) -> Result<()> {
        let Some(event_type) = envelope.payload.get("event_type").and_then(|v| v.as_str()) else {
            return Ok(());
        };
        conn.execute(
            "INSERT INTO events
             (event_id, event_type, subject_id, payload, created_ms, origin_device)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            rusqlite::params![
                ids::new_id(),
                event_type,
                subject_id,
                payload,
                envelope.created_ms,
                envelope.device_id
            ],
        )?;
        Ok(())
    }

    /// Record a blob an operation declares, without trusting a byte of
    /// what it declares. The length is checked against the cap and
    /// against real free space **before** anything is written, and it is
    /// never used as an allocation — the bytes stream into staging and
    /// are hashed as they go (ADR 0019 § 8, retiring the spike's
    /// `Vec::with_capacity(byte_len)`).
    fn accept_blob(
        &self,
        conn: &rusqlite::Connection,
        object_id: &str,
        blob: &serde_json::Value,
        limits: &Limits,
    ) -> Result<Option<BlobNeed>> {
        let blob_id = text(blob, "blob_id");
        let rel_path = text(blob, "rel_path");
        let sha256 = text(blob, "sha256");
        let declared = number(blob, "bytes").unwrap_or(-1);
        if blob_id.is_empty() || rel_path.is_empty() || sha256.is_empty() || declared < 0 {
            return Err(KernelError::SyncHalted {
                device_id: object_id.to_string(),
                reason: "a blob was declared without an id, a path, a digest or a length"
                    .to_string(),
            });
        }
        if rel_path.starts_with('/') || rel_path.contains("..") || rel_path.contains('\\') {
            return Err(KernelError::SyncHalted {
                device_id: object_id.to_string(),
                reason: format!("blob path {rel_path} escapes the vault"),
            });
        }
        let declared = declared as u64;
        if declared > limits.max_blob_bytes {
            return Err(KernelError::SyncHalted {
                device_id: blob_id,
                reason: format!(
                    "declared blob length {declared} is over the {}-byte limit",
                    limits.max_blob_bytes
                ),
            });
        }
        let available = fs::available_space(&self.local_dir())?;
        if declared.saturating_add(limits.free_space_margin_bytes) > available {
            return Err(KernelError::SyncHalted {
                device_id: blob_id,
                reason: format!(
                    "declared blob length {declared} does not fit in {available} bytes of \
                     free space with a {}-byte margin",
                    limits.free_space_margin_bytes
                ),
            });
        }
        conn.execute(
            "INSERT OR IGNORE INTO blobs
             (blob_id, object_id, rel_path, sha256, bytes, state, origin)
             VALUES (?1, ?2, ?3, ?4, ?5, 'committed', 'sync')",
            rusqlite::params![blob_id, object_id, rel_path, sha256, declared as i64],
        )?;
        let ready: i64 = conn.query_row(
            "SELECT COUNT(*) FROM blobs WHERE blob_id = ?1 AND state = 'ready'",
            [&blob_id],
            |row| row.get(0),
        )?;
        if ready > 0 {
            return Ok(None);
        }
        Ok(Some(BlobNeed {
            blob_id: blob_id.clone(),
            sha256,
            bytes: declared,
            received: crate::blobs::staged_bytes(self, &blob_id),
        }))
    }
}

fn conn_count(conn: &rusqlite::Connection, op_id: &str) -> Result<i64> {
    Ok(conn.query_row(
        "SELECT COUNT(*) FROM operations WHERE op_id = ?1",
        [op_id],
        |row| row.get(0),
    )?)
}

fn text(value: &serde_json::Value, field: &str) -> String {
    value
        .get(field)
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string()
}

fn number(value: &serde_json::Value, field: &str) -> Option<i64> {
    value.get(field).and_then(|v| v.as_i64())
}

// -------------------------------------------------- adopting pre-v5 history

/// Rebuild the envelopes of operations written before schema v5, and
/// reconcile their kind names with the codec's.
///
/// The kernel wrote four kind names it never read: `create-source`,
/// `create-object`, `create-revision` and `tombstone`. Because no
/// production operation has ever been applied anywhere, ADR 0019
/// authorises reconciling them once — and this is that once. A row whose
/// operation cannot be rebuilt is not dropped and not left empty: it is
/// re-encoded under a `legacy-operation` kind, which every replica
/// preserves and forwards without interpreting, so the local sequence
/// space stays dense and a peer never sees a gap that will not close.
///
/// A data step rather than a schema step, flagged so it can be re-run to
/// converge — the `transcript_merge` precedent.
pub(crate) fn adopt_legacy_envelopes(store: &Store) -> Result<()> {
    const FLAG: &str = "operations_adopted_v5";
    let conn = store.conn();
    let done: i64 = conn.query_row(
        "SELECT COUNT(*) FROM meta WHERE key = ?1",
        [FLAG],
        |row| row.get(0),
    )?;
    if done > 0 {
        return Ok(());
    }
    let legacy: Vec<(String, String, String, String, i64)> = {
        let mut stmt = conn.prepare(
            "SELECT op_id, kind, target_id, payload, created_ms FROM operations
             WHERE LENGTH(envelope) = 0 ORDER BY device_id, op_seq",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?))
        })?;
        rows.collect::<std::result::Result<_, _>>()?
    };

    conn.execute_batch("BEGIN IMMEDIATE;")?;
    let adopted: Result<()> = (|| {
        for (op_id, kind, target_id, payload, created_ms) in &legacy {
            let (kind, payload) = rebuild_legacy(conn, kind, target_id, payload)?;
            let (device_id, op_seq): (String, i64) = conn.query_row(
                "SELECT device_id, op_seq FROM operations WHERE op_id = ?1",
                [op_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )?;
            let envelope = Envelope {
                version: ENVELOPE_VERSION,
                op_id: op_id.clone(),
                device_id,
                op_seq,
                kind,
                target_id: target_id.clone(),
                created_ms: *created_ms,
                payload,
            };
            conn.execute(
                "UPDATE operations SET kind = ?2, payload = ?3, envelope = ?4 WHERE op_id = ?1",
                rusqlite::params![
                    op_id,
                    envelope.kind,
                    envelope.payload.to_string(),
                    encode(&envelope)
                ],
            )?;
        }
        crate::crashpoints::crashpoint("adoption.during");
        conn.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?1, ?2)",
            rusqlite::params![FLAG, legacy.len() as i64],
        )?;
        Ok(())
    })();
    match adopted {
        Ok(()) => conn.execute_batch("COMMIT;")?,
        Err(e) => {
            let _ = conn.execute_batch("ROLLBACK;");
            return Err(e);
        }
    }
    Ok(())
}

fn rebuild_legacy(
    conn: &rusqlite::Connection,
    kind: &str,
    target_id: &str,
    payload: &str,
) -> Result<(String, serde_json::Value)> {
    let preserved = || {
        (
            "legacy-operation".to_string(),
            serde_json::json!({ "kind": kind, "payload": payload }),
        )
    };
    match kind {
        "create-source" | "create-object" => {
            let object: Option<(String, i64, i64)> = conn
                .query_row(
                    "SELECT type_id, type_schema, created_ms FROM objects WHERE object_id = ?1",
                    [target_id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            let revision: Option<(String, String, String, i64)> = conn
                .query_row(
                    "SELECT revision_id, payload, origin, created_ms FROM revisions
                     WHERE object_id = ?1 ORDER BY created_ms, revision_id LIMIT 1",
                    [target_id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            let (Some((type_id, type_schema, created_ms)), Some(revision)) = (object, revision)
            else {
                return Ok(preserved());
            };
            let mut rebuilt = serde_json::json!({
                "object": {
                    "object_id": target_id,
                    "type_id": type_id,
                    "type_schema": type_schema,
                    "created_ms": created_ms,
                },
                "revision": {
                    "revision_id": revision.0,
                    "payload": revision.1,
                    "origin": revision.2,
                    "created_ms": revision.3,
                },
            });
            let blob: Option<(String, String, String, i64)> = conn
                .query_row(
                    "SELECT blob_id, rel_path, sha256, bytes FROM blobs WHERE object_id = ?1",
                    [target_id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            if let Some((blob_id, rel_path, sha256, bytes)) = blob {
                rebuilt["blob"] = serde_json::json!({
                    "blob_id": blob_id,
                    "rel_path": rel_path,
                    "sha256": sha256,
                    "bytes": bytes,
                });
            }
            let legacy: serde_json::Value =
                serde_json::from_str(payload).unwrap_or(serde_json::Value::Null);
            if let Some(derived_from) = legacy.get("derived_from").and_then(|v| v.as_str()) {
                rebuilt["derived_from"] = serde_json::Value::String(derived_from.to_string());
            }
            Ok((OBJECT_CREATED.to_string(), rebuilt))
        }
        "create-revision" => {
            let legacy: serde_json::Value = match serde_json::from_str(payload) {
                Ok(value) => value,
                Err(_) => return Ok(preserved()),
            };
            let revision_id = legacy.get("revision_id").and_then(|v| v.as_str()).unwrap_or("");
            if revision_id.is_empty() {
                return Ok(preserved());
            }
            // Before v5 a revision replaced every head, and there was
            // always exactly one: the revision immediately preceding it.
            // Naming it is what makes this history's supersession
            // explicit rather than implied by a deleted row.
            let previous: Option<String> = conn
                .query_row(
                    "SELECT revision_id FROM revisions
                     WHERE object_id = ?1 AND revision_id != ?2
                       AND (created_ms, revision_id) <
                           (SELECT created_ms, revision_id FROM revisions WHERE revision_id = ?2)
                     ORDER BY created_ms DESC, revision_id DESC LIMIT 1",
                    [target_id, revision_id],
                    |row| row.get(0),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            Ok((
                OBJECT_REVISED.to_string(),
                serde_json::json!({
                    "object_id": target_id,
                    "revision": {
                        "revision_id": revision_id,
                        "payload": legacy.get("payload").and_then(|v| v.as_str()).unwrap_or(""),
                        "origin": legacy.get("origin").and_then(|v| v.as_str()).unwrap_or(""),
                        "created_ms": legacy.get("created_ms").and_then(|v| v.as_i64()).unwrap_or(0),
                    },
                    "supersedes": previous.map(|p| vec![p]).unwrap_or_default(),
                }),
            ))
        }
        TOMBSTONE => Ok((TOMBSTONE.to_string(), serde_json::json!({}))),
        _ => Ok(preserved()),
    }
}

fn no_rows_none<T>(e: rusqlite::Error) -> Result<Option<T>> {
    match e {
        rusqlite::Error::QueryReturnedNoRows => Ok(None),
        e => Err(KernelError::Storage(e)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn envelope() -> Envelope {
        Envelope {
            version: ENVELOPE_VERSION,
            op_id: "op-1".to_string(),
            device_id: "device-a".to_string(),
            op_seq: 7,
            kind: TOMBSTONE.to_string(),
            target_id: "object-1".to_string(),
            created_ms: 1_700_000_000_000,
            payload: serde_json::json!({}),
        }
    }

    #[test]
    fn encoding_is_deterministic_whatever_order_the_payload_was_built_in() {
        let mut first = envelope();
        first.payload = serde_json::json!({ "b": 2, "a": 1, "c": { "z": 1, "y": 2 } });
        let mut second = envelope();
        second.payload = serde_json::json!({ "c": { "y": 2, "z": 1 }, "a": 1, "b": 2 });
        assert_eq!(
            encode(&first),
            encode(&second),
            "the same operation must always be the same bytes, or replicas cannot \
             forward what they did not themselves encode"
        );
        assert_eq!(encode(&first), encode(&first.clone()));
    }

    #[test]
    fn a_round_trip_preserves_every_field() {
        let original = envelope();
        let bytes = encode(&original);
        assert_eq!(decode(&bytes, &Limits::DEFAULT).expect("decode"), original);
    }

    #[test]
    fn an_envelope_over_the_size_cap_is_refused_before_it_is_parsed() {
        let mut big = envelope();
        big.payload = serde_json::json!({ "text": "x".repeat(4096) });
        let limits = Limits { max_envelope_bytes: 256, ..Limits::DEFAULT };
        assert!(matches!(
            decode(&encode(&big), &limits),
            Err(DecodeError::TooLarge { .. })
        ));
    }

    #[test]
    fn a_newer_codec_version_halts_rather_than_being_guessed_at() {
        let mut future = envelope();
        future.version = ENVELOPE_VERSION + 1;
        assert!(matches!(
            decode(&encode(&future), &Limits::DEFAULT),
            Err(DecodeError::UnsupportedVersion { .. })
        ));
    }

    #[test]
    fn every_required_field_is_required() {
        let bytes = encode(&envelope());
        let whole: serde_json::Value = serde_json::from_slice(&bytes).expect("json");
        for field in [
            "version",
            "op_id",
            "device_id",
            "op_seq",
            "kind",
            "target_id",
            "created_ms",
            "payload",
        ] {
            let mut without = whole.clone();
            without.as_object_mut().expect("object").remove(field);
            assert!(
                decode(without.to_string().as_bytes(), &Limits::DEFAULT).is_err(),
                "{field} must be required"
            );
        }
    }

    #[test]
    fn out_of_range_and_wrong_typed_fields_are_refused() {
        let bytes = encode(&envelope());
        let whole: serde_json::Value = serde_json::from_slice(&bytes).expect("json");
        let bad = [
            ("op_seq", serde_json::json!(0)),
            ("op_seq", serde_json::json!(-1)),
            ("op_seq", serde_json::json!(1.5)),
            ("op_seq", serde_json::json!("3")),
            ("created_ms", serde_json::json!(-1)),
            ("device_id", serde_json::json!("")),
            ("device_id", serde_json::json!(7)),
            ("device_id", serde_json::json!("a\u{0}b")),
            ("op_id", serde_json::json!("x".repeat(MAX_ID_CHARS + 1))),
            ("kind", serde_json::json!("k".repeat(MAX_KIND_CHARS + 1))),
            ("payload", serde_json::json!([])),
            ("payload", serde_json::json!("not an object")),
        ];
        for (field, value) in bad {
            let mut broken = whole.clone();
            broken[field] = value.clone();
            assert!(
                decode(broken.to_string().as_bytes(), &Limits::DEFAULT).is_err(),
                "{field} = {value} must be refused"
            );
        }
    }

    #[test]
    fn a_sequence_number_beyond_i64_is_refused_rather_than_truncated() {
        let raw = r#"{"created_ms":1,"device_id":"d","kind":"tombstone","op_id":"o",
                      "op_seq":9223372036854775808,"payload":{},"target_id":"t","version":1}"#;
        assert!(matches!(
            decode(raw.as_bytes(), &Limits::DEFAULT),
            Err(DecodeError::Field { field: "op_seq", .. })
        ));
    }

    #[test]
    fn bytes_that_are_not_json_at_all_are_refused() {
        for raw in [
            &b"{definitely not json"[..],
            &b""[..],
            &b"[]"[..],
            &b"\"a string\""[..],
            &[0xff, 0xfe, 0xfd][..],
        ] {
            assert!(decode(raw, &Limits::DEFAULT).is_err());
        }
    }
}
