//! The paired transport: M5a's `Transport` seam, with a Noise session
//! where the loopback had a bare socket.
//!
//! Nothing above this file changes between the two. `exchange::sync` runs
//! against a `ChannelSession` either way, and `protocol::serve` answers
//! one either way — which is the point of the seam and the reason the S4
//! suite is evidence about the code that carries a real pairing.
//!
//! A peer address here is `<static-key-hex>@<host:port>`: the trait's own
//! documentation says `peer` is transport-specific, and this transport
//! needs to know *which* device it is dialling before it can choose a
//! `psk`. The address half is the cached one from the roster or the one a
//! person typed; M5c makes it discoverable.

use crate::handshake::{self, Greeting};
use crate::identity::Identity;
use crate::protocol::ChannelSession;
use crate::roster::Roster;
use crate::transport::{Listener, Session, Transport};
use crate::{Result, SyncError};
use gilune_kernel::limits::Limits;
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// How long to wait for a socket to open before giving up on an address.
/// A cached address that has gone stale must fail quickly, because M5c's
/// fallback chain is what happens next.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// The same, per candidate, when there is a *list* of addresses to try.
///
/// A machine with a hypervisor, a VPN and a LAN offers three or four
/// addresses and only one of them is the device (`addressing` explains why
/// nothing here can say which). Trying each at the full timeout would put
/// twenty seconds behind a pairing that should take one, so a list gets a
/// short wait each: an address that answers answers immediately on a LAN,
/// and an address with nothing behind it costs this and not five seconds.
pub const CANDIDATE_CONNECT_TIMEOUT: Duration = Duration::from_secs(2);

pub fn split_peer(peer: &str) -> Result<(&str, &str)> {
    peer.split_once('@').ok_or(SyncError::Refused {
        detail: "a paired peer is named <key>@<host:port>".to_string(),
    })
}

/// `host:port`, split once and in one place.
///
/// This was open-coded as `rsplit_once(':')` in five places — the
/// publisher, the settings list, the advertiser, the listener's wake-up
/// knock and the greeting's port — each with its own idea of what to do
/// with a bracketed IPv6 host and with a port that does not parse. A
/// device that publishes one thing and advertises another is not a bug
/// anybody sees until two machines disagree, so there is one function.
pub fn split_address(address: &str) -> Option<(&str, u16)> {
    let (host, port) = address.rsplit_once(':')?;
    let port: u16 = port.parse().ok()?;
    Some((host.trim_matches(['[', ']']), port))
}

/// The port an address names, if it names one.
pub fn port_of(address: &str) -> Option<u16> {
    split_address(address).map(|(_, port)| port)
}

/// Open a socket with a bounded wait, resolving the name first.
pub fn dial(address: &str) -> Result<TcpStream> {
    dial_within(address, CONNECT_TIMEOUT)
}

/// The same, with the wait named. A caller working down a list of
/// candidates wants a short one; a caller with the single address a person
/// typed wants the patient one.
pub fn dial_within(address: &str, timeout: Duration) -> Result<TcpStream> {
    let mut last: Option<std::io::Error> = None;
    let resolved = address
        .to_socket_addrs()
        .map_err(|e| SyncError::Refused {
            detail: format!("{address} is not an address this device can reach: {e}"),
        })?;
    for candidate in resolved {
        match TcpStream::connect_timeout(&candidate, timeout) {
            Ok(stream) => return Ok(stream),
            Err(e) => last = Some(e),
        }
    }
    Err(SyncError::Refused {
        detail: match last {
            Some(e) => format!("{address} did not answer: {e}"),
            None => format!("{address} resolved to nowhere"),
        },
    })
}

/// Dial the first of several candidates that answers, and say which one
/// it was.
///
/// **The device decides, because nothing else can.** A machine publishes
/// every address it has (`addressing::local_addresses` says why it cannot
/// pick), so a QR carries a list and this is the side that resolves it:
/// the candidates are tried in the order they were ranked, and the one
/// that answers is the one recorded in the roster. A single-candidate list
/// behaves exactly as `dial` always did, including the patient timeout,
/// because one address is what a person typed and there is nothing to
/// hurry on to.
pub fn dial_first(addresses: &[String]) -> Result<(TcpStream, String)> {
    let timeout = if addresses.len() > 1 {
        CANDIDATE_CONNECT_TIMEOUT
    } else {
        CONNECT_TIMEOUT
    };
    let mut last: Option<SyncError> = None;
    for address in addresses {
        match dial_within(address, timeout) {
            Ok(stream) => return Ok((stream, address.clone())),
            Err(e) => last = Some(e),
        }
    }
    Err(last.unwrap_or(SyncError::Refused {
        detail: "that pairing code names no address this device can reach".to_string(),
    }))
}

pub struct SecureTransport {
    identity: Identity,
    greeting: Greeting,
    roster: Arc<Mutex<Roster>>,
    limits: Limits,
    /// Whether this device is still willing to talk at all. Cleared when
    /// sync is switched off, and consulted by a live outbound session at
    /// every operation boundary — the dialling half of the check the
    /// listener has always made.
    outbound: Arc<AtomicBool>,
}

impl SecureTransport {
    pub fn new(
        identity: Identity,
        greeting: Greeting,
        roster: Arc<Mutex<Roster>>,
        limits: Limits,
        outbound: Arc<AtomicBool>,
    ) -> SecureTransport {
        SecureTransport { identity, greeting, roster, limits, outbound }
    }
}

impl Transport for SecureTransport {
    fn connect(&self, peer: &str) -> Result<Box<dyn Session>> {
        let (static_pub, address) = split_peer(peer)?;
        // No pair secret, no session. There is nothing else to check:
        // being unpaired or revoked is the same fact here, and it stops
        // the connection before a byte of Noise is written.
        let psk = self
            .roster
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .secret_for(static_pub)
            .ok_or(SyncError::Refused {
                detail: "this device is not paired with that one".to_string(),
            })?;
        let established = handshake::dial_session(
            dial(address)?,
            self.limits,
            &self.identity,
            &self.greeting,
            static_pub,
            psk,
        )?;
        // What a successful session teaches: the peer's device id, and
        // that this address really did reach it.
        self.roster
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .seen(static_pub, &established.peer.device_id, address)?;
        // The same two questions the listener asks between requests, asked
        // by the side that dialled. Without this a device that had opened
        // the connection kept pushing to a peer it had just revoked, and
        // kept pushing after its own user switched sync off, until the
        // exchange loop happened to run out of work — the roster was
        // consulted once, at connect, and never again.
        let roster = Arc::clone(&self.roster);
        let outbound = Arc::clone(&self.outbound);
        let pinned = static_pub.to_string();
        let permitted = Box::new(move || {
            outbound.load(Ordering::SeqCst)
                && roster
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .secret_for(&pinned)
                    .is_some()
        });
        Ok(Box::new(ChannelSession::with_permission(
            Box::new(established.channel),
            self.limits,
            permitted,
        )))
    }

    fn listen(&self, _bind: &str) -> Result<Box<dyn Listener>> {
        // Listening is the service's own loop, because accepting has to
        // branch on whether the caller came to pair or to sync, and a
        // `Listener` that yields only sessions cannot express that.
        Err(SyncError::Refused {
            detail: "paired listening belongs to the sync service, not to this trait".to_string(),
        })
    }
}
