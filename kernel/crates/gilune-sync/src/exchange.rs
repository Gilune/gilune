//! The exchange loop: frontier-driven, resumable, and bounded.
//!
//! Every decision here follows from one fact — the receiver's frontier is
//! authoritative. A session that ends early costs nothing, because the
//! next one asks from the same place; a stream that halts costs nothing
//! but that stream, because the frontier did not move; and a peer that
//! sends too much is stopped without anything being half-applied,
//! because the kernel applies one operation per transaction.
//!
//! The loop is deliberately dull. Everything that judges an operation
//! lives in `gilune_kernel::operations`; this file moves bytes and counts
//! them against `Limits`.

use crate::transport::{Frame, Session};
use crate::{Result, SyncError};
use gilune_kernel::limits::Limits;
use gilune_kernel::store::Store;
use gilune_kernel::KernelError;
use std::collections::BTreeMap;

/// One peer's stream that stopped, and why. Not an error for the session
/// as a whole: other devices' streams carry on, and this one resumes from
/// its frontier when the condition clears.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Halted {
    pub device_id: String,
    pub reason: String,
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct ExchangeReport {
    pub applied: usize,
    pub duplicates: usize,
    /// Applied and forwarded, but of a kind nothing here interprets.
    pub uninterpreted: usize,
    pub sent: usize,
    pub chunks: usize,
    pub blobs_completed: usize,
    pub blobs_pending: usize,
    pub bytes: u64,
    pub halted: Vec<Halted>,
    /// The session reached a cap and ended cleanly. Not a failure: the
    /// next session resumes exactly here.
    pub ended_on_cap: bool,
}

impl ExchangeReport {
    /// Whether anything at all still needs a further session.
    pub fn incomplete(&self) -> bool {
        self.ended_on_cap || self.blobs_pending > 0 || !self.halted.is_empty()
    }

    /// Operations this session has moved in either direction. One budget
    /// for the whole session, so a peer cannot double a cap by pushing
    /// and pulling at once.
    fn budget_spent(&self) -> usize {
        self.applied + self.duplicates + self.sent
    }
}

/// How many operations to ask for in one request. Small enough that a
/// halt costs one round trip rather than a whole batch, large enough that
/// a hundred operations is not a hundred round trips.
const BATCH: usize = 64;

/// Give the peer what it lacks, then take what this device lacks. One
/// call converges a pair as far as the caps allow; `report.incomplete()`
/// says whether to call again.
pub fn sync(store: &Store, session: &mut dyn Session, limits: Limits) -> Result<ExchangeReport> {
    let mut report = push(store, session, limits)?;
    let pulled = pull(store, session, limits)?;
    report.applied += pulled.applied;
    report.duplicates += pulled.duplicates;
    report.uninterpreted += pulled.uninterpreted;
    report.chunks += pulled.chunks;
    report.blobs_completed += pulled.blobs_completed;
    report.blobs_pending = pulled.blobs_pending;
    report.bytes += pulled.bytes;
    report.halted.extend(pulled.halted);
    report.ended_on_cap |= pulled.ended_on_cap;
    Ok(report)
}

/// Offer the peer every operation it does not hold, and the content of
/// any blob it says it now needs. The peer applies under its own bounds:
/// pushing is delivery, never permission.
pub fn push(store: &Store, session: &mut dyn Session, limits: Limits) -> Result<ExchangeReport> {
    let mut report = ExchangeReport::default();
    let theirs: BTreeMap<String, i64> = session
        .devices()?
        .into_iter()
        .map(|offer| (offer.device_id, offer.highest_seq))
        .collect();

    for (device_id, ours) in store.operation_devices()? {
        let mut cursor = *theirs.get(&device_id).unwrap_or(&0);
        while cursor < ours {
            if report.budget_spent() >= limits.max_ops_per_session
                || report.bytes >= limits.max_bytes_per_session
            {
                report.ended_on_cap = true;
                return Ok(report);
            }
            let envelopes = store.operations_after(&device_id, cursor, BATCH)?;
            if envelopes.is_empty() {
                break;
            }
            for envelope in envelopes {
                report.bytes += envelope.len() as u64;
                report.sent += 1;
                match session.send_envelope(&envelope) {
                    Ok(Some(need)) => {
                        report.chunks += offer_blob(store, session, &need, limits, &mut report.bytes)?;
                    }
                    Ok(None) => {}
                    // The peer refused this operation. That is its halt,
                    // recorded here and not retried: it will ask again
                    // from its own frontier once the condition clears.
                    Err(SyncError::Protocol { detail }) => {
                        report.halted.push(Halted {
                            device_id: device_id.clone(),
                            reason: detail,
                        });
                        return Ok(report);
                    }
                    Err(e) => return Err(e),
                }
                cursor += 1;
            }
        }
    }
    Ok(report)
}

/// Stream the content of one blob to a peer that has just said it needs
/// it, from wherever that peer had got to.
fn offer_blob(
    store: &Store,
    session: &mut dyn Session,
    need: &gilune_kernel::operations::BlobNeed,
    limits: Limits,
    spent: &mut u64,
) -> Result<usize> {
    let mut offset = need.received;
    let mut chunks = 0;
    while offset < need.bytes {
        if *spent >= limits.max_bytes_per_session {
            break;
        }
        let bytes = store.blob_chunk(&need.blob_id, offset, limits.chunk_bytes)?;
        if bytes.is_empty() {
            break;
        }
        match session.send_chunk(&need.blob_id, offset, &bytes) {
            Ok(_) => {}
            // The peer refused the content: its own bound, its own alert.
            Err(SyncError::Protocol { .. }) => break,
            Err(e) => return Err(e),
        }
        offset += bytes.len() as u64;
        *spent += bytes.len() as u64;
        chunks += 1;
    }
    Ok(chunks)
}

/// Take everything this device lacks, per originating device, in
/// sequence order, halting one stream at a time and never the session.
pub fn pull(store: &Store, session: &mut dyn Session, limits: Limits) -> Result<ExchangeReport> {
    let mut report = ExchangeReport::default();
    let local = store.device_id()?;

    for offer in session.devices()? {
        if offer.device_id == local {
            continue;
        }
        loop {
            let frontier = store.sync_frontier(&offer.device_id)?;
            if frontier >= offer.highest_seq {
                break;
            }
            if report.budget_spent() >= limits.max_ops_per_session
                || report.bytes >= limits.max_bytes_per_session
            {
                report.ended_on_cap = true;
                return Ok(report);
            }
            let room = limits.max_ops_per_session - report.budget_spent();
            let frames = session.have_after(&offer.device_id, frontier, BATCH.min(room))?;
            if frames.is_empty() {
                break;
            }
            let mut halted = false;
            for frame in frames {
                let Frame::Envelope(bytes) = frame else { continue };
                if report.bytes + bytes.len() as u64 > limits.max_bytes_per_session {
                    report.ended_on_cap = true;
                    return Ok(report);
                }
                report.bytes += bytes.len() as u64;
                match store.apply_operation(&offer.device_id, &bytes, limits) {
                    Ok(applied) => {
                        if applied.duplicate {
                            report.duplicates += 1;
                        } else {
                            report.applied += 1;
                        }
                        if applied.uninterpreted {
                            report.uninterpreted += 1;
                        }
                    }
                    Err(KernelError::SyncHalted { device_id, reason }) => {
                        report.halted.push(Halted { device_id, reason });
                        halted = true;
                        break;
                    }
                    Err(e) => return Err(SyncError::Kernel(e)),
                }
                if report.budget_spent() >= limits.max_ops_per_session {
                    report.ended_on_cap = true;
                    return Ok(report);
                }
            }
            if halted {
                break;
            }
        }
    }

    let (chunks, completed, pending) = fetch_blobs(store, session, limits, &mut report)?;
    report.chunks += chunks;
    report.blobs_completed = completed;
    report.blobs_pending = pending;
    Ok(report)
}

/// Fetch the content of every blob whose operation has applied here but
/// whose bytes have not arrived. Resumes from what is already staged, so
/// an interrupted transfer costs only what it had not yet moved.
fn fetch_blobs(
    store: &Store,
    session: &mut dyn Session,
    limits: Limits,
    report: &mut ExchangeReport,
) -> Result<(usize, usize, usize)> {
    let mut chunks = 0;
    let mut completed = 0;
    let mut pending = 0;
    for need in store.blobs_awaiting_bytes()? {
        let mut offset = need.received;
        while offset < need.bytes {
            if report.bytes >= limits.max_bytes_per_session {
                report.ended_on_cap = true;
                break;
            }
            let frames = session.have_blob(&need.blob_id, offset, limits.chunk_bytes)?;
            let mut moved = false;
            for frame in frames {
                let Frame::Chunk { blob_id, offset: at, bytes } = frame else { continue };
                if blob_id != need.blob_id || bytes.is_empty() {
                    continue;
                }
                report.bytes += bytes.len() as u64;
                offset = store.write_blob_chunk(&blob_id, at, &bytes, limits)?;
                chunks += 1;
                moved = true;
            }
            if !moved {
                break; // the peer has no more of this blob to give
            }
        }
        if store.finish_blob(&need.blob_id)? {
            completed += 1;
        } else {
            pending += 1;
        }
    }
    Ok((chunks, completed, pending))
}
