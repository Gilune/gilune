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
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// How long to wait for a socket to open before giving up on an address.
/// A cached address that has gone stale must fail quickly, because M5c's
/// fallback chain is what happens next.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

pub fn split_peer(peer: &str) -> Result<(&str, &str)> {
    peer.split_once('@').ok_or(SyncError::Refused {
        detail: "a paired peer is named <key>@<host:port>".to_string(),
    })
}

/// Open a socket with a bounded wait, resolving the name first.
pub fn dial(address: &str) -> Result<TcpStream> {
    let mut last: Option<std::io::Error> = None;
    let resolved = address
        .to_socket_addrs()
        .map_err(|e| SyncError::Refused {
            detail: format!("{address} is not an address this device can reach: {e}"),
        })?;
    for candidate in resolved {
        match TcpStream::connect_timeout(&candidate, CONNECT_TIMEOUT) {
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

pub struct SecureTransport {
    identity: Identity,
    greeting: Greeting,
    roster: Arc<Mutex<Roster>>,
    limits: Limits,
}

impl SecureTransport {
    pub fn new(
        identity: Identity,
        greeting: Greeting,
        roster: Arc<Mutex<Roster>>,
        limits: Limits,
    ) -> SecureTransport {
        SecureTransport { identity, greeting, roster, limits }
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
        Ok(Box::new(ChannelSession::new(
            Box::new(established.channel),
            self.limits,
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
