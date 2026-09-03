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

/// Payload key naming the device that originated the object an operation
/// is *about* — written by `object-revised` and by `tombstone`.
///
/// Additive, and the whole of `sync-awaiting-referent`'s exit. Waiting
/// for something to arrive is only correct while it still might: a
/// revision or a tombstone of an object belonging to a device this vault
/// has revoked names something that can never come, and without this key
/// the receiver has no way to know that — it has never seen the object,
/// so it cannot look up whose it was. Naming the origin in the operation
/// is what turns "wait for ever" into "this is moot, carry on".
pub const OBJECT_ORIGIN_DEVICE: &str = "object_origin_device";

/// Payload key mapping each superseded revision id to the device that
/// originated it. Same reasoning as `OBJECT_ORIGIN_DEVICE`, for the heads
/// a revision names rather than the object it belongs to.
pub const SUPERSEDES_ORIGIN: &str = "supersedes_origin";

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

/// Which device originated an object, and which originated each of its
/// revisions, as far as the operations this replica holds can say.
///
/// Read from the operations themselves rather than from `objects` or
/// `revisions`, because those tables record *what* exists here and never
/// *whose* it was. A full replica holds a third device's history, so the
/// answer is frequently not this device.
#[derive(Debug, Default, Clone)]
pub(crate) struct Origins {
    /// The device whose `object-created` brought the object into being.
    pub object: Option<String>,
    /// revision id -> the device whose operation carried it.
    pub revisions: std::collections::BTreeMap<String, String>,
}

pub(crate) fn origins_of(conn: &rusqlite::Connection, object_id: &str) -> Result<Origins> {
    let mut origins = Origins::default();
    let mut stmt = conn.prepare(
        "SELECT device_id, kind, payload FROM operations
         WHERE target_id = ?1 AND kind IN (?2, ?3) ORDER BY op_seq",
    )?;
    let rows = stmt.query_map(
        rusqlite::params![object_id, OBJECT_CREATED, OBJECT_REVISED],
        |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
            ))
        },
    )?;
    for row in rows {
        let (device_id, kind, payload) = row?;
        let payload: serde_json::Value =
            serde_json::from_str(&payload).unwrap_or(serde_json::Value::Null);
        if kind == OBJECT_CREATED {
            origins.object = Some(device_id.clone());
        }
        let revision_id = text(
            payload.get("revision").unwrap_or(&serde_json::Value::Null),
            "revision_id",
        );
        if !revision_id.is_empty() {
            origins.revisions.insert(revision_id, device_id);
        }
    }
    Ok(origins)
}

/// Add the origin keys to a naming operation's payload. Only what is
/// known is written: the canonical bytes say what is true and nothing
/// else, and a receiver that finds no key falls back to inferring the
/// origin from operations it already holds.
pub(crate) fn name_origins(
    conn: &rusqlite::Connection,
    object_id: &str,
    superseded: &[String],
    carried: &mut serde_json::Value,
) -> Result<()> {
    let origins = origins_of(conn, object_id)?;
    if let Some(device) = &origins.object {
        carried[OBJECT_ORIGIN_DEVICE] = serde_json::Value::String(device.clone());
    }
    let named: serde_json::Map<String, serde_json::Value> = superseded
        .iter()
        .filter_map(|head| {
            origins
                .revisions
                .get(head)
                .map(|device| (head.clone(), serde_json::Value::String(device.clone())))
        })
        .collect();
    if !named.is_empty() {
        carried[SUPERSEDES_ORIGIN] = serde_json::Value::Object(named);
    }
    Ok(())
}

/// The payload a tombstone carries: the heads the deleter could see, and
/// whose each of them — and the object — was.
///
/// **One builder, because there were two and one of them was empty.**
/// `resolve_tombstone` names the heads it saw, which is what lets a
/// receiving projection tell "the deleter saw everything that is here"
/// from "somebody wrote something they never had in front of them". The
/// transcript merge wrote `{}` — a bare tombstone, indistinguishable on
/// every other replica from one written before the key existed, so its
/// deletions fell to the weaker pre-key fallback for ever. The merge is a
/// deletion made *here*, with the whole object in front of it, so it can
/// answer the question exactly; it simply never did.
///
/// Runs on the caller's connection, so it composes inside a transaction
/// the caller owns — which both callers need, since the heads it reads
/// must be the heads the same transaction is about to tombstone.
pub(crate) fn tombstone_payload(
    conn: &rusqlite::Connection,
    object_id: &str,
) -> Result<serde_json::Value> {
    let superseded = crate::revisions::heads(conn, object_id)?;
    let mut carried = serde_json::json!({ "supersedes": superseded });
    name_origins(conn, object_id, &superseded, &mut carried)?;
    Ok(carried)
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

/// What to do about a referent an operation names that is not held here.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Referent {
    /// It may still arrive: halt this stream at the naming operation and
    /// resume, unchanged, once it does.
    Awaited,
    /// Its origin device is refused by this vault, so it never will:
    /// a revision or a tombstone of an object this vault excludes is moot.
    Refused(String),
    /// This replica holds the operation that carries it and does not
    /// interpret that kind (rule 9). Waiting would be waiting for a plugin
    /// rather than for a peer, and the peer cannot help.
    Uninterpreted,
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
        // Revocation protects what comes next, never what is already
        // held (ADR 0019 § 9) — which is why this sits *after* the
        // duplicate check and not before it. History already applied is
        // not retracted; a revoked device's next operation is refused
        // whoever hands it over, so a third replica in a full mesh
        // cannot relay one back in.
        if self.is_refused(&envelope.device_id)? {
            let detail = format!(
                "{} is revoked on this device; its operations are refused \
                 whoever delivers them",
                envelope.device_id
            );
            return Err(self.halt(
                alerts::SYNC_REVOKED_DEVICE,
                &envelope.device_id,
                &detail,
            ));
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
                // caused, so it is raised here and not inside. What it
                // says is the raise site's to decide: this handler
                // publishes the kind and the subject it was given rather
                // than relabelling every in-transaction refusal
                // `sync-limit-exceeded` about the sending device, which
                // is what it used to do.
                if let KernelError::SyncHalted { kind, subject, reason } = &e {
                    let (kind, subject, reason) = (kind.clone(), subject.clone(), reason.clone());
                    return Err(self.halt(&kind, &subject, &reason));
                }
                return Err(e);
            }
        };
        crate::crashpoints::crashpoint("apply.commit.after");

        // The stream is healthy again: whatever was standing about it
        // stops being reported without anyone dismissing it.
        //
        // Guarded by one read, because the healthy case is the common one
        // and this was ten DELETE statements per applied operation — ten
        // writes, in WAL mode, to withdraw notices that were almost never
        // standing. A peer catching up with a thousand operations paid
        // ten thousand of them.
        let standing: i64 = conn.query_row(
            "SELECT COUNT(*) FROM integrity_alerts WHERE subject = ?1 OR subject = ?2",
            rusqlite::params![envelope.device_id, stream],
            |row| row.get(0),
        )?;
        if standing > 0 {
            for kind in [
                alerts::SYNC_GAP,
                alerts::SYNC_CORRUPT_OPERATION,
                alerts::SYNC_UNSUPPORTED_VERSION,
                alerts::SYNC_LIMIT_EXCEEDED,
                alerts::SYNC_AWAITING_REFERENT,
            ] {
                alerts::clear(conn, kind, &envelope.device_id)?;
                alerts::clear(conn, kind, stream)?;
            }
        }
        Ok(applied)
    }

    /// Raise the alert and name the halt. Split out so every refusal
    /// takes exactly one path to being visible, and so the alert kind a
    /// person is shown is the one the refusal chose.
    ///
    /// `pub(crate)` because content refusals are refusals too: `blobs.rs`
    /// had its own byte-identical copy of this, so "every refusal takes
    /// exactly one path" was true of operations and not of transfers.
    pub(crate) fn halt(&self, kind: &str, subject: &str, detail: &str) -> KernelError {
        if let Err(e) = alerts::raise(self.conn(), kind, subject, detail) {
            return e;
        }
        KernelError::SyncHalted {
            kind: kind.to_string(),
            subject: subject.to_string(),
            reason: detail.to_string(),
        }
    }

    /// What to do about something an operation names that is not here.
    ///
    /// Three answers, and only one of them waits. `sync-awaiting-referent`
    /// shipped with no exit at all: a stream halted on a naming operation
    /// and resumed *only* when the referent arrived, so a referent that
    /// could never arrive halted that device's stream for ever, and in a
    /// full-replica mesh that is one revoked device silencing a healthy
    /// one. These are the two ways it can be known that waiting is futile.
    /// **Whose unreadable operation it is decides the answer.** "This
    /// build cannot read the object" has to mean *the device the operation
    /// is about* wrote something here that this build cannot read. Asking
    /// only whether **anybody** had is what went wrong: one unknown-kind
    /// operation from an unrelated device that happened to name the same
    /// object made every revision of it forward-only, so a revision that
    /// would have applied perfectly once its `object-created` landed was
    /// instead recorded as uninterpretable and never applied at all — the
    /// frontier moved past it and nothing brought it back.
    ///
    /// **Which device that is, and why `delivered_by` is a fallback rather
    /// than a guess.** The origin key names it outright and is believed
    /// when present. It is absent in exactly two shapes, and both are the
    /// case this fallback exists for rather than an edge of it: adopted
    /// pre-v5 operations carry no origin keys at all, and a plugin-created
    /// object has no `object-created` for `name_origins` to read an origin
    /// out of, so a modern revision of one carries no key either. Halting
    /// there would halt that device's stream for ever, since the object can
    /// never appear. So the question falls back to the stream actually in
    /// front of us — is *this* device's own history here under a kind this
    /// build cannot read — which is the one device that can be reasoned
    /// about without believing anything. A third device's operation is
    /// never evidence, with the key or without it.
    fn object_verdict(
        &self,
        conn: &rusqlite::Connection,
        object_id: &str,
        named_origin: Option<&str>,
        delivered_by: &str,
    ) -> Result<Referent> {
        if let Some(device) = self.refused_origin(named_origin)? {
            return Ok(Referent::Refused(device));
        }
        let origin = named_origin.filter(|d| !d.is_empty()).unwrap_or(delivered_by);
        // The object is absent while an operation about it, from the very
        // device the operation is about, is present: that one is a kind
        // this build does not interpret, because an `object-created` it
        // could read would have created the row.
        let held = self.uninterpreted_ops_for(conn, object_id)?;
        if held.iter().any(|(device, _)| device == origin) {
            Ok(Referent::Uninterpreted)
        } else {
            Ok(Referent::Awaited)
        }
    }

    /// The same question about a superseded head rather than an object.
    ///
    /// It cannot share `object_verdict`'s fallback: here the object *does*
    /// exist, so operations about it are always present and "held ⇒
    /// uninterpreted" would answer `Uninterpreted` for every head still
    /// in flight — which is exactly the late-arrival case that must wait.
    /// So the fallback asks the narrower question: is the revision named
    /// inside an operation this build did not interpret **from the device
    /// that wrote that revision**?
    ///
    /// Same rule as `object_verdict`, and `delivered_by` means the same
    /// thing here: an unrelated device's unknown-kind operation that
    /// happens to quote the revision id is not evidence about the head at
    /// all, whether or not the naming operation carries an origin key.
    fn head_verdict(
        &self,
        conn: &rusqlite::Connection,
        object_id: &str,
        revision_id: &str,
        named_origin: Option<&str>,
        delivered_by: &str,
    ) -> Result<Referent> {
        if let Some(device) = self.refused_origin(named_origin)? {
            return Ok(Referent::Refused(device));
        }
        let origin = named_origin.filter(|d| !d.is_empty()).unwrap_or(delivered_by);
        for (device, payload) in self.uninterpreted_ops_for(conn, object_id)? {
            // Identifiers are UUIDs, so naming one is a substring match
            // over a payload whose shape this build cannot parse — which
            // is the whole point of it being uninterpreted.
            if device == origin && payload.contains(revision_id) {
                return Ok(Referent::Uninterpreted);
            }
        }
        Ok(Referent::Awaited)
    }

    fn refused_origin(&self, named_origin: Option<&str>) -> Result<Option<String>> {
        let Some(device) = named_origin.filter(|d| !d.is_empty()) else {
            return Ok(None);
        };
        if self.is_refused(device)? {
            return Ok(Some(device.to_string()));
        }
        Ok(None)
    }

    /// Operations this replica holds about an object whose kind it does
    /// not interpret — a `legacy-operation` from adoption, or a kind
    /// belonging to a plugin that is not installed here (rule 9).
    fn uninterpreted_ops_for(
        &self,
        conn: &rusqlite::Connection,
        object_id: &str,
    ) -> Result<Vec<(String, String)>> {
        let mut stmt = conn.prepare(
            "SELECT device_id, payload FROM operations
             WHERE target_id = ?1 AND kind NOT IN (?2, ?3, ?4) ORDER BY op_seq",
        )?;
        let rows = stmt.query_map(
            rusqlite::params![object_id, OBJECT_CREATED, OBJECT_REVISED, TOMBSTONE],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Record that a referent will never arrive.
    ///
    /// The standing `sync-awaiting-referent` about it is **withdrawn**,
    /// because a notice saying "waiting for this" about something that
    /// cannot come is worse than no notice at all. Where the reason is
    /// policy rather than a missing plugin, `sync-referent-refused` takes
    /// its place — informational, never damage: it says why a stream did
    /// *not* stop. A referent held under a kind this build cannot read
    /// needs no second notice, because `sync-unknown-op-kind` is already
    /// standing about that kind.
    fn referent_moot(
        &self,
        conn: &rusqlite::Connection,
        referent_id: &str,
        detail: Option<String>,
    ) -> Result<()> {
        alerts::clear(conn, alerts::SYNC_AWAITING_REFERENT, referent_id)?;
        if let Some(detail) = detail {
            alerts::raise(conn, alerts::SYNC_REFERENT_REFUSED, referent_id, &detail)?;
        }
        Ok(())
    }

    /// As `referent_moot`, and the operation itself is then preserved and
    /// forwarded without being interpreted: the frontier moves, nothing
    /// local is written, and this stream carries on.
    fn forward_only(
        &self,
        conn: &rusqlite::Connection,
        applied: &mut Applied,
        referent_id: &str,
        detail: Option<String>,
    ) -> Result<()> {
        applied.uninterpreted = true;
        self.referent_moot(conn, referent_id, detail)
    }

    /// The refusal an apply arm makes when what it names has not arrived.
    /// Inside the transaction, so nothing is half-applied; the outer
    /// handler rolls back and publishes this kind and subject verbatim.
    fn awaiting(missing_id: &str, waiting: &Envelope) -> KernelError {
        KernelError::SyncHalted {
            kind: alerts::SYNC_AWAITING_REFERENT.to_string(),
            subject: missing_id.to_string(),
            reason: format!(
                "{} at seq {} from {} names {missing_id}, which has not arrived here yet",
                waiting.kind, waiting.op_seq, waiting.device_id
            ),
        }
    }

    /// Interpret one decoded envelope inside the apply transaction.
    /// Unknown kinds are preserved and forwarded but never interpreted
    /// (constitution rule 9): forward compatibility is what lets a device
    /// carry a plugin's data it has no plugin for.
    ///
    /// **One rule for a referent that has not arrived.** Sequences are
    /// per device and carry no causal parents, and a pull orders streams
    /// by device id, so a revision, a tombstone or a named head can
    /// legitimately reach here before the operation that created what it
    /// names. Every arm answers that the same way: halt this stream at
    /// the naming operation (`sync-awaiting-referent`), apply nothing,
    /// leave the frontier where it was — exactly what a sequence gap
    /// already does. The next pass, once the other device's stream has
    /// delivered the referent, applies it and clears the alert. What it
    /// replaced: a foreign-key error that killed the whole session with
    /// no notice, a tombstone silently dropped by a nought-row UPDATE,
    /// and a `supersedes` name that matched nothing and let a superseded
    /// head come back as a conflict nobody made.
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
                        kind: alerts::SYNC_CORRUPT_OPERATION.to_string(),
                        subject: envelope.device_id.clone(),
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
                // Whatever was waiting on this object or this revision is
                // waiting no longer — and so is anything that said it
                // could never come. The second is not hypothetical: a
                // device that was revoked can be paired again
                // (`unrefuse_device`), and then the very history this
                // vault declared moot starts arriving. A notice saying it
                // never will is then a lie standing over real data.
                for referent in [&object_id, &revision_id] {
                    alerts::clear(conn, alerts::SYNC_AWAITING_REFERENT, referent)?;
                    alerts::clear(conn, alerts::SYNC_REFERENT_REFUSED, referent)?;
                }
            }
            OBJECT_REVISED => {
                let revision = payload.get("revision").unwrap_or(&serde_json::Value::Null);
                let object_id = text(payload, "object_id");
                let revision_id = text(revision, "revision_id");
                if object_id.is_empty() || revision_id.is_empty() {
                    return Err(KernelError::SyncHalted {
                        kind: alerts::SYNC_CORRUPT_OPERATION.to_string(),
                        subject: envelope.device_id.clone(),
                        reason: "object-revised names no object or no revision".to_string(),
                    });
                }
                if !row_exists(conn, "objects", "object_id", &object_id)? {
                    match self.object_verdict(
                        conn,
                        &object_id,
                        payload.get(OBJECT_ORIGIN_DEVICE).and_then(|v| v.as_str()),
                        &envelope.device_id,
                    )? {
                        Referent::Awaited => return Err(Self::awaiting(&object_id, envelope)),
                        Referent::Refused(device) => {
                            let detail = format!(
                                "{} at seq {} revises an object originated by {device}, which \
                                 this device has revoked; the operation is kept and forwarded \
                                 but nothing here changes",
                                envelope.kind, envelope.op_seq
                            );
                            self.forward_only(conn, &mut applied, &object_id, Some(detail))?;
                            return Ok(applied);
                        }
                        Referent::Uninterpreted => {
                            self.forward_only(conn, &mut applied, &object_id, None)?;
                            return Ok(applied);
                        }
                    }
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
                    //
                    // A name this device has never seen is not "no head to
                    // delete": it is a revision still in flight, whose
                    // arrival would then stand as a head nothing ever
                    // superseded — a conflict on a note nobody conflicted
                    // over. So the whole operation waits for it. A name
                    // already superseded here is present in `revisions`
                    // and deletes nothing, which is right.
                    if let Some(superseded) = payload.get("supersedes").and_then(|v| v.as_array()) {
                        let named = payload.get(SUPERSEDES_ORIGIN);
                        for head in superseded.iter().filter_map(|v| v.as_str()) {
                            if !row_exists(conn, "revisions", "revision_id", head)? {
                                let origin = named
                                    .and_then(|m| m.get(head))
                                    .and_then(|v| v.as_str());
                                match self.head_verdict(
                                    conn,
                                    &object_id,
                                    head,
                                    origin,
                                    &envelope.device_id,
                                )? {
                                    Referent::Awaited => {
                                        return Err(Self::awaiting(head, envelope))
                                    }
                                    // A head from an origin this vault
                                    // refuses, or one carried by an
                                    // operation nothing here reads, is
                                    // **satisfied**: it will never stand
                                    // as a head, so there is nothing left
                                    // for this revision to supersede.
                                    Referent::Refused(device) => {
                                        let detail = format!(
                                            "a head superseded by {} at seq {} was originated by \
                                             {device}, which this device has revoked; it can \
                                             never arrive, so the supersession is complete",
                                            envelope.kind, envelope.op_seq
                                        );
                                        self.referent_moot(conn, head, Some(detail))?;
                                        continue;
                                    }
                                    Referent::Uninterpreted => {
                                        self.referent_moot(conn, head, None)?;
                                        continue;
                                    }
                                }
                            }
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
                alerts::clear(conn, alerts::SYNC_AWAITING_REFERENT, &revision_id)?;
                alerts::clear(conn, alerts::SYNC_REFERENT_REFUSED, &revision_id)?;
            }
            TOMBSTONE => {
                if !row_exists(conn, "objects", "object_id", &envelope.target_id)? {
                    match self.object_verdict(
                        conn,
                        &envelope.target_id,
                        payload.get(OBJECT_ORIGIN_DEVICE).and_then(|v| v.as_str()),
                        &envelope.device_id,
                    )? {
                        Referent::Awaited => {
                            return Err(Self::awaiting(&envelope.target_id, envelope))
                        }
                        Referent::Refused(device) => {
                            let detail = format!(
                                "a tombstone at seq {} deletes an object originated by {device}, \
                                 which this device has revoked; the object was never accepted \
                                 here, so deleting it is moot",
                                envelope.op_seq
                            );
                            self.forward_only(
                                conn,
                                &mut applied,
                                &envelope.target_id,
                                Some(detail),
                            )?;
                            return Ok(applied);
                        }
                        Referent::Uninterpreted => {
                            self.forward_only(conn, &mut applied, &envelope.target_id, None)?;
                            return Ok(applied);
                        }
                    }
                }
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
                kind: alerts::SYNC_CORRUPT_OPERATION.to_string(),
                subject: object_id.to_string(),
                reason: "a blob was declared without an id, a path, a digest or a length"
                    .to_string(),
            });
        }
        if rel_path.starts_with('/') || rel_path.contains("..") || rel_path.contains('\\') {
            return Err(KernelError::SyncHalted {
                kind: alerts::SYNC_CORRUPT_OPERATION.to_string(),
                subject: object_id.to_string(),
                reason: format!("blob path {rel_path} escapes the vault"),
            });
        }
        let declared = declared as u64;
        if declared > limits.max_blob_bytes {
            return Err(KernelError::SyncHalted {
                kind: alerts::SYNC_LIMIT_EXCEEDED.to_string(),
                subject: blob_id,
                reason: format!(
                    "declared blob length {declared} is over the {}-byte limit",
                    limits.max_blob_bytes
                ),
            });
        }
        let available = fs::available_space(&self.local_dir())?;
        if declared.saturating_add(limits.free_space_margin_bytes) > available {
            return Err(KernelError::SyncHalted {
                kind: alerts::SYNC_LIMIT_EXCEEDED.to_string(),
                subject: blob_id,
                reason: format!(
                    "declared blob length {declared} does not fit in {available} bytes of \
                     free space with a {}-byte margin",
                    limits.free_space_margin_bytes
                ),
            });
        }
        // A declaration this device refused before is a condition that has
        // now cleared. The subjects are the raise sites' own — the blob
        // for a bound, the object for a malformed declaration — so they
        // are withdrawn here rather than by the stream-wide sweep, which
        // only knows about devices.
        for (kind, subject) in [
            (alerts::SYNC_LIMIT_EXCEEDED, blob_id.as_str()),
            (alerts::SYNC_CORRUPT_OPERATION, blob_id.as_str()),
            (alerts::SYNC_LIMIT_EXCEEDED, object_id),
            (alerts::SYNC_CORRUPT_OPERATION, object_id),
        ] {
            alerts::clear(conn, kind, subject)?;
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

// ------------------------------------------- refusal by originating device

/// Meta key holding the device ids whose operations this device refuses.
///
/// **Durable vault state, not a projection.** It was written as a mirror
/// of the device-local roster, rewritten wholesale whenever that roster
/// changed — and that made a revocation exactly as durable as the roster
/// file, which is not durable at all across the one event ADR 0019 § 4
/// plans for: a vault arriving on a machine that holds no identity for it
/// re-enrols under a **new** `device_id`, opens the roster belonging to
/// that new id, finds nothing, and rewrote this list from nothing. Every
/// revocation the vault carried was erased by the act of restoring a
/// backup, and the alerts that named them were dismissed with it.
///
/// So the direction of authority is inverted: revocation **adds** here
/// (`refuse_device`), only an explicit re-pairing takes away
/// (`unrefuse_device`), and a roster reconciles **into** it
/// (`reconcile_refused_devices`). It is kept in the vault so that refusal
/// is a property of `apply` itself rather than of a caller remembering to
/// pass a policy — every delivery path, present and future, runs into it
/// — and now so that it outlives the identity that made the decision.
///
/// Device ids only. No key material ever reaches the vault.
pub(crate) const REFUSED_DEVICES: &str = "refused_devices";

/// Meta key holding the device ids a person has explicitly re-admitted
/// after a peer claimed them as an earlier identity of its own.
///
/// **It exists because reconciliation would otherwise undo the decision.**
/// A revoked roster row keeps naming the device it revoked, and every
/// launch folds those names back into `REFUSED_DEVICES` — which is
/// correct, and is what makes a revocation survive a restart. Without a
/// record that says "this one was let back in on purpose", re-admitting a
/// claimed earlier identity would last exactly until the next open, and
/// the alert would come back with it.
///
/// So a re-admission is durable vault state in its own right, and the only
/// thing that takes it away is a later revocation — which is a person
/// changing their mind, and must win.
pub(crate) const READMITTED_DEVICES: &str = "readmitted_devices";

impl Store {
    /// Devices whose operations this one refuses to apply.
    pub fn refused_devices(&self) -> Result<Vec<String>> {
        self.with_refused(<[String]>::to_vec)
    }

    /// Refuse everything a device originates, from now on (ADR 0019 § 9).
    ///
    /// Idempotent, and the **only** way in. Answers whether this changed
    /// anything, so a caller can tell a fresh revocation from a repeat.
    /// It does not retract history and it raises no alert: an alert says
    /// something arrived and was refused, and nothing has arrived yet.
    pub fn refuse_device(&self, device_id: &str) -> Result<bool> {
        if device_id.is_empty() {
            return Ok(false);
        }
        // A revocation is the newer decision, so it overrules an earlier
        // re-admission: without this, a device let back in once could
        // never be revoked again — reconciliation would keep skipping it.
        self.forget_readmission(device_id)?;
        let mut devices = self.refused_devices()?;
        if devices.iter().any(|d| d == device_id) {
            return Ok(false);
        }
        devices.push(device_id.to_string());
        self.write_refused(&devices)?;
        Ok(true)
    }

    /// Welcome a device back, because it has been paired again.
    ///
    /// The **only** way out, and deliberately explicit: re-pairing is a
    /// decision somebody took, where an empty roster is merely a machine
    /// that has not been introduced yet. The standing alert goes with the
    /// refusal — nobody should have to dismiss a notice about a device
    /// they have just welcomed back.
    pub fn unrefuse_device(&self, device_id: &str) -> Result<bool> {
        if device_id.is_empty() {
            return Ok(false);
        }
        let devices = self.refused_devices()?;
        if !devices.iter().any(|d| d == device_id) {
            return Ok(false);
        }
        let kept: Vec<String> = devices.into_iter().filter(|d| d != device_id).collect();
        self.write_refused(&kept)?;
        alerts::clear(self.conn(), alerts::SYNC_REVOKED_DEVICE, device_id)?;
        Ok(true)
    }

    /// Record that a peer has claimed a device id it says it used before it
    /// re-enrolled — **without believing it**.
    ///
    /// The claim arrives inside the authenticated channel, which proves who
    /// is speaking and proves nothing at all about what they are saying. A
    /// device this vault refuses stays refused; what changes is that the
    /// claim becomes visible, so a person can decide (`readmit_device`).
    ///
    /// Nothing is raised about a device this vault does not refuse: there
    /// would be no decision to take, and a notice with no action behind it
    /// is a notice nobody can clear. Answers whether anything was raised.
    pub fn note_previous_identity_claim(&self, device_id: &str, detail: &str) -> Result<bool> {
        if device_id.is_empty() || !self.is_refused(device_id)? {
            return Ok(false);
        }
        alerts::raise(
            self.conn(),
            alerts::SYNC_PREVIOUS_IDENTITY_CLAIMED,
            device_id,
            detail,
        )?;
        Ok(true)
    }

    /// Welcome an **earlier** identity back, because a person said so.
    ///
    /// The deliberate counterpart of `note_previous_identity_claim`: the
    /// claim is data, this is the decision. It lifts the refusal, records
    /// the re-admission durably so the next reconciliation does not undo
    /// it, and withdraws the notice that offered the choice.
    ///
    /// Answers whether the device was actually refused before this — so a
    /// caller can tell "let back in" from "there was nothing to let in".
    pub fn readmit_device(&self, device_id: &str) -> Result<bool> {
        if device_id.is_empty() {
            return Ok(false);
        }
        let lifted = self.unrefuse_device(device_id)?;
        let mut readmitted = self.readmitted_devices()?;
        if !readmitted.iter().any(|d| d == device_id) {
            readmitted.push(device_id.to_string());
            self.write_meta_list(READMITTED_DEVICES, &readmitted)?;
        }
        alerts::clear(self.conn(), alerts::SYNC_PREVIOUS_IDENTITY_CLAIMED, device_id)?;
        Ok(lifted)
    }

    /// Devices a person has explicitly re-admitted. Read by reconciliation
    /// and by nothing else.
    pub fn readmitted_devices(&self) -> Result<Vec<String>> {
        self.meta_list(READMITTED_DEVICES)
    }

    /// Fold a device-local roster's revocations into this vault's set.
    ///
    /// Union, never replacement. A roster knows about the pairings *this*
    /// identity made; the vault knows about every device this vault has
    /// ever refused, including ones decided under an identity that no
    /// longer exists. Reconciling one into the other keeps both facts;
    /// overwriting kept only the newer and quieter one.
    ///
    /// One exception, and it is a decision rather than a heuristic: a
    /// device somebody re-admitted by hand is not folded back in. Otherwise
    /// the roster's revoked row — which is still there, and should be —
    /// would re-refuse it at every launch and the re-admission would be a
    /// button that does nothing past the next open.
    pub fn reconcile_refused_devices(&self, revoked: &[String]) -> Result<()> {
        let readmitted = self.readmitted_devices()?;
        let mut devices = self.refused_devices()?;
        let mut added = false;
        for device in revoked {
            if device.is_empty()
                || devices.iter().any(|d| d == device)
                || readmitted.iter().any(|d| d == device)
            {
                continue;
            }
            devices.push(device.clone());
            added = true;
        }
        if added {
            self.write_refused(&devices)?;
        }
        Ok(())
    }

    fn forget_readmission(&self, device_id: &str) -> Result<()> {
        let readmitted = self.readmitted_devices()?;
        if !readmitted.iter().any(|d| d == device_id) {
            return Ok(());
        }
        let kept: Vec<String> = readmitted.into_iter().filter(|d| d != device_id).collect();
        self.write_meta_list(READMITTED_DEVICES, &kept)
    }

    fn meta_list(&self, key: &str) -> Result<Vec<String>> {
        let raw: Option<String> = self
            .conn()
            .query_row("SELECT value FROM meta WHERE key = ?1", [key], |row| row.get(0))
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        let Some(raw) = raw else { return Ok(Vec::new()) };
        let parsed: serde_json::Value =
            serde_json::from_str(&raw).unwrap_or(serde_json::Value::Null);
        Ok(parsed
            .as_array()
            .map(|rows| {
                rows.iter()
                    .filter_map(|v| v.as_str())
                    .map(str::to_string)
                    .collect()
            })
            .unwrap_or_default())
    }

    fn write_meta_list(&self, key: &str, values: &[String]) -> Result<()> {
        let document = serde_json::Value::Array(
            values
                .iter()
                .map(|d| serde_json::Value::String(d.clone()))
                .collect(),
        );
        self.conn().execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?1, ?2)",
            rusqlite::params![key, document.to_string()],
        )?;
        Ok(())
    }

    fn write_refused(&self, devices: &[String]) -> Result<()> {
        let document = serde_json::Value::Array(
            devices
                .iter()
                .map(|d| serde_json::Value::String(d.clone()))
                .collect(),
        );
        self.conn().execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?1, ?2)",
            rusqlite::params![REFUSED_DEVICES, document.to_string()],
        )?;
        Ok(())
    }

    /// Asked once per applied operation, and now once per naming
    /// operation's referent too, so it answers without copying the list.
    fn is_refused(&self, device_id: &str) -> Result<bool> {
        self.with_refused(|refused| refused.iter().any(|d| d == device_id))
    }

    /// Take on a new device identity for this vault, because the vault
    /// arrived from somewhere else (ADR 0019 § 4, row three of the
    /// re-enrolment table). Returns the identity being left behind.
    ///
    /// The fresh sequence space is free — `next_seq` counts this
    /// device's own rows and there are none — but the operations the
    /// *previous* identity wrote are still here, and this device now
    /// holds them as any full replica holds a third device's history.
    /// So its frontier is seeded at what is actually held: without that,
    /// the first session with the original device would offer seq 1,
    /// find the op id already present, and halt on an identifier clash
    /// that is really just a vault that was copied.
    pub fn reenrol_as_new_device(&self, new_device_id: &str) -> Result<String> {
        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let done: Result<String> = (|| {
            let previous: String = conn.query_row(
                "SELECT value FROM meta WHERE key = 'device_id'",
                [],
                |row| row.get(0),
            )?;
            let held: i64 = conn.query_row(
                "SELECT COALESCE(MAX(op_seq), 0) FROM operations WHERE device_id = ?1",
                [&previous],
                |row| row.get(0),
            )?;
            if held > 0 {
                conn.execute(
                    "INSERT INTO sync_frontiers (device_id, applied_seq) VALUES (?1, ?2)
                     ON CONFLICT(device_id) DO UPDATE SET applied_seq = MAX(applied_seq, ?2)",
                    rusqlite::params![previous, held],
                )?;
            }
            conn.execute(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('device_id', ?1)",
                [new_device_id],
            )?;
            Ok(previous)
        })();
        match done {
            Ok(previous) => {
                conn.execute_batch("COMMIT;")?;
                // The one thing that changes this device's id, so the one
                // place the cached answer has to be dropped — otherwise
                // this handle would keep applying its own new operations
                // under the identity it has just left behind.
                self.forget_device_id();
                Ok(previous)
            }
            Err(e) => {
                let _ = conn.execute_batch("ROLLBACK;");
                Err(e)
            }
        }
    }
}

/// Whether a row this operation names is already held. `table` and
/// `column` are always literals from kernel code — never host-supplied
/// text — and the value is always a bound parameter.
fn row_exists(
    conn: &rusqlite::Connection,
    table: &str,
    column: &str,
    value: &str,
) -> Result<bool> {
    let hit: i64 = conn.query_row(
        &format!("SELECT COUNT(*) FROM {table} WHERE {column} = ?1"),
        [value],
        |row| row.get(0),
    )?;
    Ok(hit > 0)
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

    // ------------------------------------ the exit from waiting for ever

    fn store() -> (tempfile::TempDir, Store) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        (dir, store)
    }

    fn deliver(
        store: &Store,
        device: &str,
        seq: i64,
        kind: &str,
        target: &str,
        payload: serde_json::Value,
    ) -> Result<Applied> {
        let bytes = encode(&Envelope {
            version: ENVELOPE_VERSION,
            op_id: format!("{device}-{seq}"),
            device_id: device.to_string(),
            op_seq: seq,
            kind: kind.to_string(),
            target_id: target.to_string(),
            created_ms: 1_700_000_000_000 + seq,
            payload,
        });
        store.apply_operation("peer", &bytes, Limits::DEFAULT)
    }

    fn a_note(object_id: &str, revision_id: &str, text: &str) -> serde_json::Value {
        serde_json::json!({
            "object": {
                "object_id": object_id,
                "type_id": crate::capture::SOURCE_TYPE,
                "type_schema": 1,
                "created_ms": 1_700_000_000_000i64,
            },
            "revision": {
                "revision_id": revision_id,
                "payload": serde_json::json!({ "text": text }).to_string(),
                "origin": "capture",
                "created_ms": 1_700_000_000_000i64,
            },
        })
    }

    fn a_revision(
        object_id: &str,
        revision_id: &str,
        text: &str,
        extra: serde_json::Value,
    ) -> serde_json::Value {
        let mut payload = serde_json::json!({
            "object_id": object_id,
            "revision": {
                "revision_id": revision_id,
                "payload": serde_json::json!({ "text": text }).to_string(),
                "origin": "external-edit",
                "created_ms": 1_700_000_000_100i64,
            },
        });
        for (key, value) in extra.as_object().expect("an object") {
            payload[key] = value.clone();
        }
        payload
    }

    fn alerts_of(store: &Store) -> Vec<(String, String)> {
        store
            .integrity_alerts()
            .expect("alerts")
            .into_iter()
            .map(|a| (a.kind, a.subject))
            .collect()
    }

    #[test]
    fn a_revision_of_a_revoked_devices_object_carries_on_instead_of_waiting_for_ever() {
        // The fault this whole exit exists for. Device A's object never
        // reached this vault and never will, because A is revoked here —
        // so B's revision of it names something that cannot arrive, and
        // waiting for it halted B's stream permanently. One revoked
        // device silencing a healthy one, in a full-replica mesh.
        let (_dir, store) = store();
        store.refuse_device("device-a").expect("revoke A");

        let applied = deliver(
            &store,
            "device-b",
            1,
            OBJECT_REVISED,
            "obj-1",
            a_revision(
                "obj-1",
                "rev-2",
                "what b made of it",
                serde_json::json!({ OBJECT_ORIGIN_DEVICE: "device-a" }),
            ),
        )
        .expect("B's stream carries on rather than halting");
        assert!(applied.uninterpreted, "preserved and forwarded, never interpreted");
        assert_eq!(
            store.sync_frontier("device-b").expect("frontier"),
            1,
            "the frontier moves, which is what lets the rest of B's history through"
        );
        assert_eq!(
            alerts_of(&store),
            vec![(alerts::SYNC_REFERENT_REFUSED.to_string(), "obj-1".to_string())],
            "and it says why the stream did not stop"
        );
        assert!(
            !crate::alerts::is_damage(alerts::SYNC_REFERENT_REFUSED),
            "carrying on past a revoked device's history is policy, not damage"
        );

        // The second-order half, and the whole point: B's own history
        // still arrives behind it.
        deliver(&store, "device-b", 2, OBJECT_CREATED, "obj-2", a_note("obj-2", "rev-3", "b's own"))
            .expect("apply");
        assert_eq!(store.status().expect("status").sources, 1, "B is not silenced by A");
    }

    #[test]
    fn a_revision_whose_object_may_still_arrive_waits_exactly_as_it_always_did() {
        // The second-order case for the exit: it must not swallow the
        // ordinary one. A referent from a device this vault is happy to
        // hear from is in flight, not moot, and the stream halts for it.
        let (_dir, store) = store();
        let halted = deliver(
            &store,
            "device-b",
            1,
            OBJECT_REVISED,
            "obj-1",
            a_revision(
                "obj-1",
                "rev-2",
                "what b made of it",
                serde_json::json!({ OBJECT_ORIGIN_DEVICE: "device-a" }),
            ),
        );
        assert!(matches!(
            halted,
            Err(KernelError::SyncHalted { ref kind, .. })
                if kind == alerts::SYNC_AWAITING_REFERENT
        ));
        assert_eq!(store.sync_frontier("device-b").expect("frontier"), 0);
        assert_eq!(
            alerts_of(&store),
            vec![(alerts::SYNC_AWAITING_REFERENT.to_string(), "obj-1".to_string())]
        );
    }

    #[test]
    fn a_referent_held_under_a_kind_this_build_cannot_read_is_not_waited_for() {
        // The legacy shape: the operation that would have created the
        // object is here, stored and forwarded, under a kind nothing here
        // interprets (rule 9) — a `legacy-operation` from adoption, or a
        // plugin's kind this build does not carry. The object will never
        // appear however long the stream waits, because what is missing
        // is a plugin and not a peer.
        let (_dir, store) = store();
        deliver(
            &store,
            "device-a",
            1,
            "com.example.thing-made",
            "obj-1",
            serde_json::json!({ "revision_id": "rev-1", "shape": "square" }),
        )
        .expect("an unknown kind applies and forwards");

        let applied = deliver(
            &store,
            "device-a",
            2,
            OBJECT_REVISED,
            "obj-1",
            // No origin key at all: the pre-key shape, so the fallback
            // has to work this out from what is already held.
            a_revision("obj-1", "rev-2", "changed", serde_json::json!({})),
        )
        .expect("the stream carries on");
        assert!(applied.uninterpreted);
        assert_eq!(store.sync_frontier("device-a").expect("frontier"), 2);
        let kinds: Vec<String> = alerts_of(&store).into_iter().map(|(k, _)| k).collect();
        assert!(kinds.contains(&alerts::SYNC_UNKNOWN_OP_KIND.to_string()));
        assert!(
            !kinds.contains(&alerts::SYNC_AWAITING_REFERENT.to_string()),
            "nothing is being waited for: {kinds:?}"
        );
    }

    #[test]
    fn an_unrelated_devices_unreadable_operation_never_makes_a_revision_forward_only() {
        // The adversarial case, and the one that silently lost a note.
        // obj-1 belongs to device X. Device Y — nothing to do with either
        // — writes something this build cannot read that happens to name
        // obj-1: a plugin's kind, or a stranger being deliberate. Device B
        // then revises obj-1, and X's `object-created` has not arrived yet,
        // because streams are ordered per device and carry no causal
        // parents. That is the ordinary wrong-order case, and it must wait.
        //
        // Asking "is anything unreadable held about obj-1" answered yes,
        // about Y — so B's revision was recorded as uninterpretable,
        // forwarded, and the frontier moved past it. When X's
        // `object-created` arrived a moment later there was nothing left to
        // apply the revision from: the words were on the wire, were
        // accepted, and were never written.
        let (_dir, store) = store();
        deliver(
            &store,
            "device-y",
            1,
            "com.example.thing-made",
            "obj-1",
            serde_json::json!({ "shape": "square" }),
        )
        .expect("an unknown kind applies and forwards");

        let revision = || {
            a_revision(
                "obj-1",
                "rev-2",
                "the words that must not be lost",
                serde_json::json!({ OBJECT_ORIGIN_DEVICE: "device-x", "supersedes": ["rev-1"] }),
            )
        };
        let halted = deliver(&store, "device-b", 1, OBJECT_REVISED, "obj-1", revision());
        assert!(
            matches!(
                halted,
                Err(KernelError::SyncHalted { ref kind, .. })
                    if kind == alerts::SYNC_AWAITING_REFERENT
            ),
            "an unrelated device's unreadable operation was taken as evidence about X's \
             object, so B's revision was forwarded instead of waiting: {halted:?}"
        );
        assert_eq!(
            store.sync_frontier("device-b").expect("frontier"),
            0,
            "and the frontier must not move past what was not applied"
        );

        // Then X's own history arrives and the revision converges, which is
        // the whole point of waiting rather than forwarding.
        deliver(&store, "device-x", 1, OBJECT_CREATED, "obj-1", a_note("obj-1", "rev-1", "first"))
            .expect("X's object arrives");
        deliver(&store, "device-b", 1, OBJECT_REVISED, "obj-1", revision())
            .expect("and now it applies");
        let head: String = store
            .conn()
            .query_row("SELECT revision_id FROM heads WHERE object_id = 'obj-1'", [], |row| {
                row.get(0)
            })
            .expect("a head");
        assert_eq!(head, "rev-2", "the revision landed rather than being lost");
        let kinds: Vec<String> = alerts_of(&store).into_iter().map(|(k, _)| k).collect();
        assert!(
            !kinds.contains(&alerts::SYNC_AWAITING_REFERENT.to_string()),
            "and the wait cleared itself: {kinds:?}"
        );
    }

    #[test]
    fn a_superseded_head_from_a_revoked_device_is_treated_as_already_gone() {
        let (_dir, store) = store();
        store.refuse_device("device-a").expect("revoke A");
        deliver(&store, "device-b", 1, OBJECT_CREATED, "obj-1", a_note("obj-1", "rev-1", "first"))
            .expect("the object arrives");

        let applied = deliver(
            &store,
            "device-b",
            2,
            OBJECT_REVISED,
            "obj-1",
            a_revision(
                "obj-1",
                "rev-3",
                "second",
                serde_json::json!({
                    // One head B saw that this vault never will.
                    "supersedes": ["rev-2-from-a"],
                    SUPERSEDES_ORIGIN: { "rev-2-from-a": "device-a" },
                }),
            ),
        )
        .expect("the revision lands rather than waiting for a head that cannot come");
        assert!(
            !applied.uninterpreted,
            "the revision itself was interpreted; only the head it named was moot"
        );
        assert_eq!(
            crate::revisions::heads(store.conn(), "obj-1").expect("heads").len(),
            2,
            "rev-1 was not named, so it survives as a concurrent head"
        );
        assert!(alerts_of(&store)
            .contains(&(alerts::SYNC_REFERENT_REFUSED.to_string(), "rev-2-from-a".to_string())));
    }

    #[test]
    fn a_superseded_head_that_may_still_arrive_still_halts_the_stream() {
        // The second-order case for the head arm: an unnamed origin, or a
        // named one this vault is happy to hear from, is a revision in
        // flight — and applying over it would leave it standing as a head
        // nothing ever superseded, which is a conflict nobody made.
        let (_dir, store) = store();
        deliver(&store, "device-b", 1, OBJECT_CREATED, "obj-1", a_note("obj-1", "rev-1", "first"))
            .expect("the object arrives");
        let halted = deliver(
            &store,
            "device-b",
            2,
            OBJECT_REVISED,
            "obj-1",
            a_revision(
                "obj-1",
                "rev-3",
                "second",
                serde_json::json!({
                    "supersedes": ["rev-2-in-flight"],
                    SUPERSEDES_ORIGIN: { "rev-2-in-flight": "device-a" },
                }),
            ),
        );
        assert!(matches!(
            halted,
            Err(KernelError::SyncHalted { ref subject, .. }) if subject == "rev-2-in-flight"
        ));
        assert_eq!(store.sync_frontier("device-b").expect("frontier"), 1);
    }

    #[test]
    fn a_tombstone_for_a_revoked_devices_object_is_moot_rather_than_a_halt() {
        let (_dir, store) = store();
        store.refuse_device("device-a").expect("revoke A");
        let applied = deliver(
            &store,
            "device-b",
            1,
            TOMBSTONE,
            "obj-1",
            serde_json::json!({ OBJECT_ORIGIN_DEVICE: "device-a", "supersedes": ["rev-1"] }),
        )
        .expect("deleting something this vault never accepted is not a fault");
        assert!(applied.uninterpreted);
        assert_eq!(store.sync_frontier("device-b").expect("frontier"), 1);
        assert_eq!(
            alerts_of(&store),
            vec![(alerts::SYNC_REFERENT_REFUSED.to_string(), "obj-1".to_string())]
        );
    }

    #[test]
    fn a_notice_that_something_is_awaited_is_withdrawn_once_it_can_never_come() {
        // Revocation can happen *after* a stream has already halted
        // waiting. The standing notice then names something that will
        // never arrive, which is worse than no notice: it asks a person
        // to wait for a thing they themselves made impossible.
        let (_dir, store) = store();
        let revision = a_revision(
            "obj-1",
            "rev-2",
            "what b made of it",
            serde_json::json!({ OBJECT_ORIGIN_DEVICE: "device-a" }),
        );
        assert!(deliver(&store, "device-b", 1, OBJECT_REVISED, "obj-1", revision.clone()).is_err());
        assert_eq!(
            alerts_of(&store),
            vec![(alerts::SYNC_AWAITING_REFERENT.to_string(), "obj-1".to_string())]
        );

        store.refuse_device("device-a").expect("revoke A, having given up on it");
        deliver(&store, "device-b", 1, OBJECT_REVISED, "obj-1", revision).expect("carries on now");
        assert_eq!(
            alerts_of(&store),
            vec![(alerts::SYNC_REFERENT_REFUSED.to_string(), "obj-1".to_string())],
            "one notice replaces the other rather than both standing"
        );
    }

    #[test]
    fn a_notice_that_something_can_never_come_is_withdrawn_when_it_comes_after_all() {
        // The second-order case for the exit itself. Revocation is not
        // final: `unrefuse_device` exists because a device can be paired
        // again, and then the very history this vault declared moot
        // starts arriving. A standing "this can never come" over data
        // that has come is a worse lie than the one it replaced.
        let (_dir, store) = store();
        store.refuse_device("device-a").expect("revoke A");
        deliver(
            &store,
            "device-b",
            1,
            OBJECT_REVISED,
            "obj-1",
            a_revision(
                "obj-1",
                "rev-2",
                "what b made of it",
                serde_json::json!({ OBJECT_ORIGIN_DEVICE: "device-a" }),
            ),
        )
        .expect("carries on");
        assert_eq!(
            alerts_of(&store),
            vec![(alerts::SYNC_REFERENT_REFUSED.to_string(), "obj-1".to_string())]
        );

        store.unrefuse_device("device-a").expect("A is paired again");
        deliver(&store, "device-a", 1, OBJECT_CREATED, "obj-1", a_note("obj-1", "rev-1", "a's"))
            .expect("A's history is welcome again");
        assert!(
            alerts_of(&store).is_empty(),
            "the object arrived, so the notice that it never would is withdrawn"
        );
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
