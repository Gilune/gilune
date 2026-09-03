//! Transport for Gilune operations (ADR 0019 § 6). This crate owns the
//! seam between the kernel's semantics and whatever carries them: the
//! `Transport` traits, identity and pairing, the Noise session, the
//! framing, the exchange loop and the quotas. It depends on
//! `gilune-kernel`; nothing depends on it but the FFI.
//!
//! M5a was this crate without a radio: no discovery, no pairing, no
//! encryption, and one loopback implementation that let the whole S4
//! scenario suite run against the production kernel first.
//!
//! M5b makes pairing a real boundary. Two devices agree a secret over
//! SPAKE2, bind their static identities with Noise `XXpsk0`, and store
//! each other in a device-local roster; every connection after that is the
//! same pattern with the stored pair secret as its `psk`, so an unpaired
//! or revoked peer cannot complete a handshake at all — **default-deny is
//! a property of the handshake, not a check performed after one**. Still
//! no discovery: an address comes from a QR or from a person typing it,
//! and M5c makes it findable.

pub mod exchange;
pub mod handshake;
pub mod identity;
pub mod loopback;
pub mod pairing;
pub mod protocol;
pub mod qr;
pub mod rate;
pub mod roster;
pub mod secure;
pub mod service;
pub mod transport;
pub mod wire;

pub use exchange::{ExchangeReport, Halted};
pub use identity::{Enrolment, Identity, IdentityStore, KeyWrap};
pub use roster::{Peer, PeerState, Roster};
pub use service::SyncService;
pub use transport::{DeviceOffer, Frame, Listener, Session, Transport};

/// The protocol this build speaks. Agreed in the Noise handshake's
/// prologue, so a version mismatch fails the handshake before any
/// operation is parsed (ADR 0019 § 5).
pub const PROTOCOL: &str = "gilune-sync/1";

/// One pattern for every connection, pairing and session alike
/// (ADR 0019 § 5). `XX` exchanges both static identities and
/// authenticates both parties; `psk0` mixes the pre-shared key in before
/// the first message, so no handshake message is intelligible without it.
pub const NOISE_PARAMS: &str = "Noise_XXpsk0_25519_ChaChaPoly_BLAKE2s";

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
    /// The device-local identity or roster could not be read or written.
    /// Never a peer's fault, and never survivable by pretending there was
    /// no identity: a device that quietly forgets who it is forgets every
    /// pairing with it.
    #[error("identity: {detail}")]
    Identity { detail: String },
    /// The peer is not one this device will talk to — unpaired, revoked,
    /// or asking to pair when no pairing window is open. Deliberately one
    /// variant: the far end learns that it was refused and nothing more.
    #[error("refused: {detail}")]
    Refused { detail: String },
}

pub type Result<T> = std::result::Result<T, SyncError>;
