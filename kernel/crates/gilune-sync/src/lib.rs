//! Transport for Gilune operations (ADR 0019 § 6). This crate owns the
//! seam between the kernel's semantics and whatever carries them: the
//! `Transport` traits, the framing, the exchange loop and the quotas.
//! It depends on `gilune-kernel`; nothing depends on it but the FFI.
//!
//! M5a is this crate without a radio. There is no discovery, no pairing
//! and no encryption here — those are M5b and M5c — and the only
//! implementation is a loopback socket, which exists so the whole S4
//! scenario suite can run against the production kernel before any of
//! that lands. A transport this crate can drive is one the kernel never
//! has to know about: ADR 0008's shared folder becomes another
//! implementation of the same traits.

pub mod exchange;
pub mod loopback;
pub mod transport;

pub use exchange::{ExchangeReport, Halted};
pub use transport::{DeviceOffer, Frame, Listener, Session, Transport};

/// Errors crossing this crate's boundary. A peer is untrusted input, so
/// "the peer did something wrong" is a distinct outcome from "this
/// machine could not do its part" — the first ends a session, the second
/// is a fault here.
#[derive(Debug, thiserror::Error)]
pub enum SyncError {
    #[error(transparent)]
    Kernel(#[from] gilune_kernel::KernelError),
    #[error("transport io: {0}")]
    Io(#[from] std::io::Error),
    /// The peer sent something this protocol does not allow. The session
    /// ends; nothing it had already delivered is undone.
    #[error("protocol: {detail}")]
    Protocol { detail: String },
    /// A frame declared a length past what this device will read. Refused
    /// before the bytes are accepted, never allocated for.
    #[error("frame declares {declared} bytes, over the {limit}-byte limit")]
    OversizeFrame { declared: usize, limit: usize },
}

pub type Result<T> = std::result::Result<T, SyncError>;
