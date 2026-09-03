//! The seam (ADR 0019 § 6). A transport carries opaque envelope bytes and
//! blob chunks and understands neither; the kernel applies them and knows
//! nothing about sockets. Everything genuinely new in M5b and M5c —
//! discovery, pairing, Noise — happens on one side of this line, and
//! nothing above it has to change.
//!
//! § 6 names five verbs: `connect`, `listen`, `have_after`,
//! `send_envelope`, `send_chunk`. Two of those establish a connection and
//! three use one, so they are split across `Transport` (establish) and
//! `Session` (exchange) — the same seam with the lifetimes written down.
//! `Session` carries two verbs beyond the five, both of which the pull
//! direction needs and neither of which the ADR's sketch had to name:
//! `devices`, because a replica cannot ask `have_after` for a device
//! until it knows which devices the peer holds, and `have_blob`, because
//! `send_chunk` is the pushing side of a transfer and a puller needs the
//! other side of it.

use crate::Result;
use gilune_kernel::operations::BlobNeed;

/// What a peer holds for one device: the highest sequence number it can
/// serve. A replica compares it against its own frontier to decide
/// whether there is anything to ask for at all — which is what makes a
/// no-op sync cost one round trip.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceOffer {
    pub device_id: String,
    pub highest_seq: i64,
}

/// One unit of payload from a peer. Deliberately not parsed here: the
/// transport moves bytes, and every judgement about them belongs to
/// `gilune_kernel::operations`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    Envelope(Vec<u8>),
    Chunk {
        blob_id: String,
        offset: u64,
        bytes: Vec<u8>,
    },
}

/// Establishing a connection. Implementations differ in everything a
/// socket adds and a folder does not; this trait is what they have in
/// common.
pub trait Transport {
    /// Dial a peer. `peer` is transport-specific — a `host:port` for the
    /// loopback and LAN transports, a directory for ADR 0008's relay.
    fn connect(&self, peer: &str) -> Result<Box<dyn Session>>;

    /// Accept connections at `bind`. The returned listener reports the
    /// address actually taken, which a caller advertises: the requested
    /// port may be busy.
    fn listen(&self, bind: &str) -> Result<Box<dyn Listener>>;
}

pub trait Listener: Send {
    /// The address this listener actually took.
    fn address(&self) -> String;
    /// Block until a peer connects.
    fn accept(&self) -> Result<Box<dyn Session>>;
}

/// One established exchange. Every method may fail; a failed session is
/// simply over, and the frontier means resuming costs nothing.
pub trait Session {
    /// What the peer holds, per originating device.
    fn devices(&mut self) -> Result<Vec<DeviceOffer>>;

    /// The peer's operations from `device` after `seq`, in sequence
    /// order, at most `max_ops` of them. Bytes exactly as the peer holds
    /// them: a replica forwards a third device's history unaltered.
    fn have_after(&mut self, device: &str, seq: i64, max_ops: usize) -> Result<Vec<Frame>>;

    /// Content of a blob the peer holds, from `offset`, at most
    /// `max_bytes`. An empty answer means the peer has no more to give —
    /// it may not hold the bytes either.
    fn have_blob(&mut self, blob_id: &str, offset: u64, max_bytes: usize) -> Result<Vec<Frame>>;

    /// Offer one operation to the peer. The peer applies it under its own
    /// bounds and its own halt policy: pushing is delivery, never
    /// permission. It answers with the blob content it now needs, if any
    /// — which is how content follows an operation without this crate
    /// ever having to look inside a payload.
    fn send_envelope(&mut self, bytes: &[u8]) -> Result<Option<BlobNeed>>;

    /// Offer one chunk of blob content to the peer, at `offset`.
    fn send_chunk(&mut self, blob_id: &str, offset: u64, bytes: &[u8]) -> Result<()>;

    /// End the session cleanly. Ending is never truncating: what was
    /// applied is applied, and what was not is asked for again.
    fn close(&mut self) -> Result<()>;
}
