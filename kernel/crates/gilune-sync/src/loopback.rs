//! A `Transport` over a plain TCP socket on the loopback interface, and
//! the server that answers one from a vault.
//!
//! This was M5a's whole point (ADR 0019 § 6): the S4 scenarios were proven
//! over a shared folder, where every message was a file and nothing could
//! be truncated, reordered or oversized by a sender. A socket can do all
//! three. Running the same scenarios over one, against the production
//! kernel, is what separates semantics that work from semantics that
//! worked on a filesystem.
//!
//! It is still deliberately not the LAN transport: there is no discovery,
//! no handshake and no encryption here, and a peer is trusted because it
//! is this machine. M5b's `secure` module replaces exactly the
//! establishment half — the framing, the conversation and the exchange
//! loop above it are the same code, which is why the suite below and the
//! suite over a paired session are testing one implementation.

use crate::protocol::{self, ChannelSession};
use crate::transport::{Listener, Session, Transport};
use crate::wire::PlainChannel;
use crate::Result;
use gilune_kernel::limits::Limits;
use gilune_kernel::store::Store;
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub struct LoopbackTransport {
    limits: Limits,
}

impl LoopbackTransport {
    pub fn new(limits: Limits) -> LoopbackTransport {
        LoopbackTransport { limits }
    }
}

impl Default for LoopbackTransport {
    fn default() -> Self {
        LoopbackTransport::new(Limits::DEFAULT)
    }
}

impl Transport for LoopbackTransport {
    fn connect(&self, peer: &str) -> Result<Box<dyn Session>> {
        let stream = TcpStream::connect(peer)?;
        stream.set_nodelay(true)?;
        Ok(Box::new(ChannelSession::new(
            Box::new(PlainChannel::new(stream, self.limits)),
            self.limits,
        )))
    }

    fn listen(&self, bind: &str) -> Result<Box<dyn Listener>> {
        let listener = TcpListener::bind(bind)?;
        Ok(Box::new(LoopbackListener {
            listener,
            limits: self.limits,
        }))
    }
}

struct LoopbackListener {
    listener: TcpListener,
    limits: Limits,
}

impl Listener for LoopbackListener {
    fn address(&self) -> String {
        self.listener
            .local_addr()
            .map(|a| a.to_string())
            .unwrap_or_default()
    }

    fn accept(&self) -> Result<Box<dyn Session>> {
        let (stream, _) = self.listener.accept()?;
        stream.set_nodelay(true)?;
        Ok(Box::new(ChannelSession::new(
            Box::new(PlainChannel::new(stream, self.limits)),
            self.limits,
        )))
    }
}

/// A vault answering loopback sessions. It opens its own kernel handle,
/// exactly as a second process would: multi-instance on one vault is
/// supported use (owner decision 2026-08-31), and a peer that is a
/// separate handle is a truer rehearsal of M5b than one that shares a
/// connection.
pub struct LoopbackServer {
    address: String,
    stopping: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl LoopbackServer {
    /// Start serving `vault` on an ephemeral loopback port.
    pub fn start(vault: impl Into<PathBuf>, limits: Limits) -> Result<LoopbackServer> {
        let vault = vault.into();
        let listener = TcpListener::bind("127.0.0.1:0")?;
        let address = listener.local_addr()?.to_string();
        let stopping = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&stopping);
        let thread = std::thread::spawn(move || {
            let Ok(store) = Store::open(&vault) else { return };
            for stream in listener.incoming() {
                if flag.load(Ordering::SeqCst) {
                    return;
                }
                let Ok(stream) = stream else { return };
                let _ = stream.set_nodelay(true);
                // One peer at a time: M5a proves semantics, and a
                // concurrent server is a bound M5c must argue for on its
                // own evidence rather than inherit here.
                let mut channel = PlainChannel::new(stream, limits);
                let _ = protocol::serve(&store, &mut channel, &limits, &|| true);
            }
        });
        Ok(LoopbackServer {
            address,
            stopping,
            thread: Some(thread),
        })
    }

    pub fn address(&self) -> &str {
        &self.address
    }
}

impl Drop for LoopbackServer {
    fn drop(&mut self) {
        self.stopping.store(true, Ordering::SeqCst);
        // Wake the blocked accept so the thread can see the flag.
        let _ = TcpStream::connect(&self.address);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
