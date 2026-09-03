//! The sync service: identity, roster, listener and the operations a
//! shell can ask for. This is what the FFI holds.
//!
//! Everything the ADR asks the *device* to own lives here, and it owns it
//! in one place so that the properties are checkable rather than
//! distributed: the listener binds only when sync is switched on (§ 2),
//! a pairing window is single-use, expiring and attempt-limited (§ 3),
//! establishment is rate-limited per peer (§ 8), revocation is local,
//! immediate and closes a live session at an operation boundary (§ 9),
//! and the kernel's refusal list is rewritten from the roster every time
//! the roster changes, so a revoked device's operations are refused
//! whoever relays them.

use crate::discovery::PeerDiscovery;
use crate::exchange;
use crate::handshake::{self, Greeting, Intent};
use crate::identity::{self, Enrolment, Identity, IdentityStore, KeyWrap};
use crate::pairing::{self, Credential, PairingWindow};
use crate::protocol;
use crate::qr;
use crate::rate::RateLimiter;
use crate::roster::{PeerState, Roster};
use crate::secure::{dial, SecureTransport};
use crate::transport::Transport;
use crate::wire::PlainChannel;
use crate::{ExchangeReport, Result, SyncError};
use gilune_kernel::ids;
use gilune_kernel::limits::Limits;
use gilune_kernel::store::Store;
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

/// The listener's preferred port, in the dynamic range (ADR 0019 § 1).
/// If it is taken an ephemeral one is used, and the actual port is what
/// the QR and the settings screen show — never the preferred one.
///
/// **A fixed default is what makes a restarted device reachable.** M5b
/// took whatever port was free, so a device that came back from a restart
/// was no longer at the address every peer had cached for it; a peer then
/// had nothing to dial and no way to find out. Preferring one port turns
/// the common case — one Gilune per machine — into "the address a peer
/// cached is still right", and discovery answers the uncommon one.
pub const DEFAULT_PORT: u16 = 51837;

/// What the listener binds.
///
/// **Every interface, which is what makes this milestone live.** M5b
/// deliberately bound the loopback: its scope was two processes on one
/// machine and binding a routable interface is what raises the Windows
/// Security Alert. M5c is the stage that wants the radio, so the bind is
/// routable and the shells own the explanation that has to appear
/// immediately before the prompt (§ 2) — and the prompt happens at
/// `set_enabled(true)` and nowhere else, which is the property that keeps
/// capture from ever being behind it.
pub const DEFAULT_BIND_HOST: &str = "0.0.0.0";

/// How many peers may be served at once.
///
/// M5b served one at a time, which was honest for a boundary proof and
/// wrong for a mesh: a phone mid-session made a laptop's Sync-now hang
/// until it finished. Thread-per-session is right at this scale — a
/// personal LAN holds a handful of the same user's own devices — and the
/// cap is what stops a hostile peer turning "accept another one" into
/// unbounded threads. Over the cap a caller is closed on, not queued:
/// resumption from a frontier is free, so being turned away costs a peer
/// nothing but a retry.
pub const MAX_CONCURRENT_SESSIONS: usize = 8;

/// The IPv4 addresses this machine can actually be dialled at, best
/// first.
///
/// Read from the interface table (`getifaddrs` / `GetAdaptersAddresses`)
/// and **not** by opening a socket. The std-only way to answer this is to
/// bind a UDP socket and `connect` it at a private-range address to learn
/// the preferred outbound interface — and that is a bind, in a program
/// whose § 2 discipline is that it binds when the user switches sync on
/// and at no other moment. `listening_on` is read by every settings
/// refresh; a socket opened behind a status query would be exactly the
/// kind of surprise the firewall UX was designed to avoid.
///
/// **IPv4 only, for the reason `discovery::first_address` gives**: a
/// link-local IPv6 address needs the scope id of the interface it was
/// heard on, and `host:port` cannot carry one.
///
/// The honest limitation: a machine with Hyper-V, WSL or a VPN adapter
/// has several routable addresses and this cannot know which one a phone
/// shares a network with. That is why `listening_addresses` reports all
/// of them for a settings screen to show, and why § 1's manual entry is
/// the answer when the first guess is the wrong one.
pub fn routable_addresses() -> Vec<std::net::Ipv4Addr> {
    let Ok(interfaces) = if_addrs::get_if_addrs() else { return Vec::new() };
    let mut addresses: Vec<std::net::Ipv4Addr> = interfaces
        .into_iter()
        .filter(|i| i.is_oper_up())
        .filter_map(|i| match i.ip() {
            std::net::IpAddr::V4(ip) => Some(ip),
            std::net::IpAddr::V6(_) => None,
        })
        .filter(is_routable)
        .collect();
    addresses.sort();
    addresses.dedup();
    addresses
}

/// Whether an address is one a peer on a LAN could dial. Never the
/// unspecified address, never the loopback, never what an interface has
/// when DHCP did not answer.
fn is_routable(ip: &std::net::Ipv4Addr) -> bool {
    !ip.is_unspecified()
        && !ip.is_loopback()
        && !ip.is_link_local()
        && !ip.is_multicast()
        && !ip.is_broadcast()
        && !ip.is_documentation()
}

/// What a device should publish for a listener bound at `bound`.
///
/// **`0.0.0.0` is not an address.** The listener binds every interface
/// (§ 2), and `local_addr` reports that bind verbatim — so the string
/// that reached the pairing window, the QR's fourth field and both
/// shells' settings screens was `0.0.0.0:51837`, which no peer can dial
/// and nothing downstream substituted. A phone that scanned that QR was
/// being told to connect to itself.
///
/// So an unspecified bind publishes a routable address of this machine
/// instead, keeping the port the listener actually took. Anything else —
/// a deliberate `127.0.0.1` for a test or a host that narrowed its own
/// bind — is published exactly as it was asked for: substituting there
/// would be overruling a caller who said what they meant.
///
/// A machine with no routable address at all publishes the loopback. It
/// is not reachable from anywhere else, and saying `127.0.0.1` is the
/// true description of that; `0.0.0.0` would be a false one.
pub fn publishable(bound: &str, candidates: &[std::net::Ipv4Addr]) -> String {
    let Some((host, port)) = bound.rsplit_once(':') else {
        return bound.to_string();
    };
    let unspecified = match host.trim_matches(['[', ']']).parse::<std::net::IpAddr>() {
        Ok(ip) => ip.is_unspecified(),
        Err(_) => false,
    };
    if !unspecified {
        return bound.to_string();
    }
    match candidates.iter().find(|ip| is_routable(ip)) {
        Some(ip) => format!("{ip}:{port}"),
        None => format!("127.0.0.1:{port}"),
    }
}

/// Why a peer could not be reached, in the words the user gets. § 1's
/// last step is "a diagnostic that names AP client isolation as the
/// likely cause" — words, not a spinner — and this is where those words
/// are decided, once, rather than in each shell.
///
/// Takes whether the browse was able to say the wanted device was among
/// what it saw.
///
/// A device that answered a browse but speaks a protocol this build
/// cannot is **not** a device that is switched off, and telling someone
/// their laptop is asleep when it is sitting in front of them running a
/// newer Gilune is the kind of wrong answer that costs an evening.
pub fn unreachable_because(
    called: &str,
    cached: &str,
    looked: bool,
    seen: usize,
    version_mismatch: bool,
) -> String {
    if version_mismatch {
        return format!(
            "{called} is on this network, but it is running a version of Gilune that this \
             one cannot exchange notes with. Updating both devices to the same version is \
             what fixes it; nothing is wrong with either vault in the meantime."
        );
    }
    if !looked {
        return format!(
            "{called} did not answer at {cached}, and this device has no way to look for \
             it. Open that device's sync settings, read the address it shows, and enter \
             it here."
        );
    }
    if seen == 0 {
        return format!(
            "{called} did not answer at {cached}, and no device could be found on this \
             network. Many guest, hotel and office networks stop devices talking to each \
             other directly — if this is one of those, no amount of waiting will help and \
             the two devices need a network of their own. Otherwise check that sync is \
             switched on over there, and that Windows was allowed through the firewall on \
             private networks."
        );
    }
    format!(
        "{called} did not answer at {cached}. {seen} other device(s) are visible on this \
         network, so this network is not the problem — that one is probably switched off, \
         asleep, or has sync turned off."
    )
}

/// A peer as a settings screen sees it. No secrets: the pair secret never
/// leaves the roster.
#[derive(Debug, Clone)]
pub struct PeerSummary {
    pub static_pub: String,
    pub fingerprint: String,
    pub device_id: String,
    pub name: String,
    pub address: String,
    pub state: String,
    pub paired_ms: i64,
    pub last_seen_ms: i64,
}

/// What is on offer while a pairing window is open.
#[derive(Debug, Clone)]
pub struct PairingOffer {
    pub code: String,
    pub address: String,
    pub fingerprint: String,
    pub expires_ms: i64,
    pub attempts_left: u32,
}

/// A device seen on the network, as a settings screen sees it.
#[derive(Debug, Clone)]
pub struct FoundDevice {
    pub fingerprint: String,
    pub address: String,
    /// Whether this build can talk to it at all — a device on the same
    /// LAN running a different protocol version is visible and not
    /// reachable, and saying so beats a pairing that fails.
    pub compatible: bool,
    /// The static key, when this is a device already in the roster. Empty
    /// for a stranger, which is also what makes it offerable to pair.
    pub static_pub: String,
    /// `paired`, `revoked` or `unknown`.
    pub state: String,
}

/// The facts a sync settings screen shows.
#[derive(Debug, Clone)]
pub struct SyncFacts {
    pub enabled: bool,
    pub device_id: String,
    pub device_name: String,
    pub fingerprint: String,
    pub identity_pub: String,
    /// What actually protected the identity key — the adapter's own name,
    /// or `none`, so a screen can say what is true rather than what was
    /// intended.
    pub key_protection: String,
    pub enrolment: String,
    pub enrolment_notice: Option<String>,
    pub listening_on: Option<String>,
    /// Whether the user has switched sync on, as opposed to whether it is
    /// listening right now. The two differ exactly when a machine was
    /// meant to be reachable and could not bind — a busy port, a firewall
    /// that said no — which is a state worth being able to describe
    /// rather than showing as a switch that silently flicked itself off.
    pub wanted: bool,
    /// Whether this device is currently advertising itself. Separate from
    /// `enabled` on purpose: a machine can bind its listener and still
    /// fail to open UDP 5353, and § 1's fallback chain is exactly what
    /// happens then — so a screen that showed one flag for both would be
    /// telling a user their device is findable when it is only reachable.
    pub advertising: bool,
    /// Which route discovery took, or `none`. A diagnostic has to be able
    /// to say whether nothing was found or nothing could look.
    pub discovery: String,
    pub pairing: Option<PairingOffer>,
    pub peers: Vec<PeerSummary>,
}

/// State the listener thread and the shell both reach.
pub struct SyncShared {
    vault: PathBuf,
    limits: Limits,
    identity: Identity,
    device_id: String,
    name: String,
    /// Where this device is currently listening, or empty. Travels in the
    /// encrypted greeting so a peer this device dialled can dial back
    /// later — § 1's "recorded at pairing and refreshed after every
    /// successful session", from the side that would otherwise only ever
    /// see a source port.
    advertised: Mutex<String>,
    roster: Arc<Mutex<Roster>>,
    window: Mutex<Option<PairingWindow>>,
    rate: Mutex<RateLimiter>,
    /// How many peers are being served right now. Bounded by
    /// `MAX_CONCURRENT_SESSIONS`, and decremented by a guard rather than
    /// by the end of the handler, because a handler can end by panicking.
    live: Arc<AtomicUsize>,
    /// Whether this device is still willing to answer.
    ///
    /// Cleared when sync is switched off, and consulted by every live
    /// session between requests — **the same mechanism revocation uses**,
    /// for the same reason: apply is one transaction per operation, so
    /// the boundary between two requests is also a boundary between two
    /// whole operations, and stopping there cannot half-apply anything.
    ///
    /// The alternative was to join the session threads when the listener
    /// stops, and it was wrong: a session waits up to a minute for a peer
    /// that has gone quiet, so switching sync off would freeze the
    /// settings screen for as long as that took.
    serving: Arc<AtomicBool>,
    /// Whether this device will keep talking on connections **it** opened.
    ///
    /// Separate from `serving`, and it starts true rather than false,
    /// because dialling out and answering are genuinely different states:
    /// Android's background worker opens a kernel handle, configures sync
    /// and calls `sync_now` **without ever binding a listener** (that is
    /// § 2 working — a background pass must never open an inbound
    /// socket), so a device that could only dial while it was listening
    /// would be a phone that never synced in the background at all.
    ///
    /// What it does carry is the withdrawal: switching sync off clears
    /// it, and an outbound session then ends at its next operation
    /// boundary exactly as an inbound one does.
    outbound: Arc<AtomicBool>,
    /// How long an accepted caller has to get through establishment
    /// before its socket is closed. Configuration with a documented
    /// default, like every other bound in § 8.
    handshake_timeout: Mutex<std::time::Duration>,
}

impl SyncShared {
    fn greeting(&self) -> Greeting {
        Greeting::new(
            &self.device_id,
            &self.name,
            &self.advertised.lock().unwrap_or_else(|e| e.into_inner()),
        )
    }

    fn handshake_timeout(&self) -> std::time::Duration {
        *self
            .handshake_timeout
            .lock()
            .unwrap_or_else(|e| e.into_inner())
    }
}

struct Listening {
    address: String,
    stopping: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Listening {
    fn stop(&mut self) {
        self.stopping.store(true, Ordering::SeqCst);
        // Wake the blocked accept so the thread can see the flag. The
        // bind is every interface now, and `0.0.0.0` is not somewhere a
        // connect can be aimed on every platform — the loopback reaches
        // the same listener and always exists.
        let knock = match self.address.rsplit_once(':') {
            Some((_, port)) => format!("127.0.0.1:{port}"),
            None => self.address.clone(),
        };
        let _ = std::net::TcpStream::connect(&knock);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

impl Drop for Listening {
    fn drop(&mut self) {
        self.stop();
    }
}

pub struct SyncService {
    shared: Arc<SyncShared>,
    listening: Mutex<Option<Listening>>,
    identities: IdentityStore,
    device_id: String,
    enrolment: Enrolment,
    bind_host: Mutex<String>,
    preferred_port: Mutex<u16>,
    /// How this device finds others and lets itself be found. Registered
    /// by the host — `MdnsDiscovery` on desktop, a Kotlin `NsdManager`
    /// adapter on Android — and `NoDiscovery` until one is, which is a
    /// supported state rather than a broken one: § 1's cached address and
    /// manual entry both work without it.
    discovery: Mutex<Arc<dyn PeerDiscovery>>,
    /// Whether the responder is currently up. Not derived from `enabled`:
    /// binding TCP can succeed while opening UDP 5353 fails, and a screen
    /// must be able to say "reachable but not findable" because that is
    /// the state the fallback chain exists for.
    advertising: AtomicBool,
}

impl SyncService {
    /// The preferred port, reachable from a caller that only holds the
    /// service. Same number as the module constant — one fact, two
    /// spellings, because a settings screen asks the service and a test
    /// asks the type.
    pub const DEFAULT_PORT: u16 = DEFAULT_PORT;

    /// Enrol this machine against this vault and stand the service up.
    /// Nothing binds a socket here: the listener waits for
    /// `set_enabled(true)`, so a user who never turns sync on never sees a
    /// firewall prompt and capture is never behind one.
    pub fn configure(
        store: &Store,
        identity_root: impl Into<PathBuf>,
        device_name: &str,
        wrap: Option<Arc<dyn KeyWrap>>,
        limits: Limits,
    ) -> Result<SyncService> {
        let identities = IdentityStore::new(identity_root);
        let (identity, enrolment) = identity::enrol(store, &identities, wrap.as_deref())?;
        let device_id = store.device_id()?;
        let roster = Roster::open(identities.roster_path(&device_id), wrap)?;
        // The roster's revocations are folded **into** the kernel's set,
        // never over it.
        //
        // **The defect this closes.** The roster is device-local and keyed
        // by `device_id`, and re-enrolment mints a new `device_id` (§ 4) —
        // so a vault that arrives on a machine holding no identity for it
        // opens a roster that does not exist yet, and rewriting the
        // kernel's list from that empty roster erased every revocation the
        // vault carried and dismissed the alerts that went with them. A
        // revoked device would have been welcomed back by the act of
        // restoring a backup, silently.
        //
        // The refusal set is therefore durable vault state that revocation
        // adds to and only an explicit re-pairing takes from. The previous
        // device's roster is gone with its identity, and that is fine: the
        // kernel's set survived, which is the whole point of keeping it
        // here.
        store.reconcile_refused_devices(&roster.revoked_device_ids())?;
        let name = if device_name.trim().is_empty() {
            "this device".to_string()
        } else {
            device_name.chars().take(64).collect()
        };
        Ok(SyncService {
            shared: Arc::new(SyncShared {
                vault: store.vault().to_path_buf(),
                limits,
                identity,
                device_id: device_id.clone(),
                name,
                advertised: Mutex::new(String::new()),
                roster: Arc::new(Mutex::new(roster)),
                window: Mutex::new(None),
                rate: Mutex::new(RateLimiter::default()),
                live: Arc::new(AtomicUsize::new(0)),
                serving: Arc::new(AtomicBool::new(false)),
                outbound: Arc::new(AtomicBool::new(true)),
                handshake_timeout: Mutex::new(handshake::HANDSHAKE_TIMEOUT),
            }),
            listening: Mutex::new(None),
            identities,
            device_id,
            enrolment,
            bind_host: Mutex::new(DEFAULT_BIND_HOST.to_string()),
            preferred_port: Mutex::new(DEFAULT_PORT),
            discovery: Mutex::new(Arc::new(crate::discovery::NoDiscovery)),
            advertising: AtomicBool::new(false),
        })
    }

    /// Whether the user has ever switched sync on, on this machine, for
    /// this vault. Survives a restart; see `IdentityStore::settings_path`.
    pub fn wanted(&self) -> bool {
        self.identities.sync_wanted(&self.device_id)
    }

    /// Bind if the user left sync switched on, and answer whether it is
    /// now listening.
    ///
    /// **This does not weaken § 2.** The lazy-bind rule is that nothing
    /// binds before the user first opts in — so a user who never turns
    /// sync on never meets the firewall prompt, and capture is never
    /// behind it. Once they have opted in, binding at launch *is* the
    /// feature working: the prompt already happened at the first enable,
    /// and a device that forgot it was reachable every time it started
    /// would be a device nothing could ever sync with unprompted.
    ///
    /// Separate from `configure` on purpose. Enrolment must not fail
    /// because a port is busy or a firewall said no, so the bind is a
    /// second, fallible step a host takes deliberately — and a host that
    /// never calls it simply starts switched off.
    pub fn resume(&self) -> Result<Option<String>> {
        if !self.wanted() {
            return Ok(None);
        }
        self.set_enabled(true)
    }

    /// Bind somewhere other than every interface. A test uses it to stay
    /// off the network; a host that knows its situation may narrow the
    /// bind deliberately.
    pub fn bind_host(&self, host: &str) {
        *self.bind_host.lock().unwrap_or_else(|e| e.into_inner()) = host.to_string();
    }

    /// Prefer a port other than the documented default.
    ///
    /// The default is what makes a restarted device findable again at a
    /// cached address, so this exists for the case that breaks it: two
    /// Gilunes on one machine, where the second one would otherwise take
    /// an ephemeral port and move on every restart. Naming a port each is
    /// what lets a two-process scenario be about sync rather than about
    /// port allocation.
    pub fn prefer_port(&self, port: u16) {
        *self.preferred_port.lock().unwrap_or_else(|e| e.into_inner()) = port;
    }

    /// Register how this device finds others (§ 1). Takes effect at the
    /// next `set_enabled(true)`; registering while sync is already on
    /// starts the new adapter advertising immediately, because a host
    /// that swapped its adapter meant to.
    pub fn set_discovery(&self, discovery: Arc<dyn PeerDiscovery>) {
        let previous =
            std::mem::replace(&mut *self.discovery.lock().unwrap_or_else(|e| e.into_inner()), discovery);
        previous.withdraw();
        self.advertising.store(false, Ordering::SeqCst);
        if self.enabled() {
            self.begin_advertising();
        }
    }

    fn discovery(&self) -> Arc<dyn PeerDiscovery> {
        Arc::clone(&self.discovery.lock().unwrap_or_else(|e| e.into_inner()))
    }

    /// Whether the responder is up right now.
    pub fn advertising(&self) -> bool {
        self.advertising.load(Ordering::SeqCst)
    }

    /// Which route discovery takes on this host, or `none`.
    pub fn discovery_route(&self) -> String {
        self.discovery().name()
    }

    /// Put this device on the network, if it can be. A responder that
    /// will not start is **not** a failure to switch sync on: the
    /// listener is bound and every peer holding a cached address can
    /// still reach it, which is precisely the situation § 1's fallback
    /// chain was written for. So the outcome is recorded and not raised.
    fn begin_advertising(&self) {
        let Some(address) = self.listening_on() else { return };
        let Some(port) = address.rsplit_once(':').and_then(|(_, p)| p.parse().ok()) else {
            return;
        };
        let ad = crate::discovery::Advertisement {
            fingerprint: identity::fingerprint_compact(self.shared.identity.public()),
            port,
        };
        let started = self.discovery().advertise(&ad).is_ok();
        self.advertising.store(started, Ordering::SeqCst);
    }

    pub fn enrolment(&self) -> &Enrolment {
        &self.enrolment
    }

    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    pub fn identity_public_hex(&self) -> String {
        self.shared.identity.public_hex()
    }

    fn roster(&self) -> std::sync::MutexGuard<'_, Roster> {
        self.shared.roster.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Configure how long an accepted caller has to finish establishing
    /// itself. § 8's rule about bounds: configuration with a documented
    /// default, so "a silent caller is closed on" can be a test rather
    /// than a fifteen-second wait.
    pub fn configure_handshake_timeout(&self, timeout: std::time::Duration) {
        *self
            .shared
            .handshake_timeout
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = timeout;
    }

    // ------------------------------------------------------------ the listener

    pub fn enabled(&self) -> bool {
        self.listening
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_some()
    }

    /// Where a peer should be told to reach this device, or `None` when
    /// it is not listening. Never the bind: see `publishable`.
    pub fn listening_on(&self) -> Option<String> {
        self.listening
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .map(|l| l.address.clone())
    }

    /// Every address this device could be reached at, best first.
    ///
    /// The QR and the pairing window carry exactly one (§ 3), because a
    /// scanner has to be handed one thing to dial. A settings screen has
    /// room to show them all, and on a machine with a VPN or a Hyper-V
    /// adapter that is the difference between a person reading the right
    /// address off the screen and giving up.
    pub fn listening_addresses(&self) -> Vec<String> {
        let Some(bound) = self.listening_on() else { return Vec::new() };
        let Some((host, port)) = bound.rsplit_once(':') else { return vec![bound] };
        // A host that narrowed its own bind is reachable at that and
        // nowhere else; only an every-interface bind has candidates.
        let unspecified = self
            .bind_host
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .parse::<std::net::IpAddr>()
            .map(|ip| ip.is_unspecified())
            .unwrap_or(false);
        if !unspecified {
            return vec![format!("{host}:{port}")];
        }
        let routable = routable_addresses();
        if routable.is_empty() {
            return vec![format!("127.0.0.1:{port}")];
        }
        routable.iter().map(|ip| format!("{ip}:{port}")).collect()
    }

    /// Switch sync on or off. **This is the moment the socket is bound**
    /// (ADR 0019 § 2), and switching off closes it and abandons any
    /// pairing window with it.
    pub fn set_enabled(&self, on: bool) -> Result<Option<String>> {
        // Recorded first, and recorded whatever happens next: the user's
        // choice is a fact even if this machine then fails to bind. A
        // flag written only on success would silently forget the choice
        // exactly on the machines where remembering it matters.
        let _ = self.identities.set_sync_wanted(&self.device_id, on);
        let mut listening = self.listening.lock().unwrap_or_else(|e| e.into_inner());
        if !on {
            *self.shared.window.lock().unwrap_or_else(|e| e.into_inner()) = None;
            self.shared
                .advertised
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clear();
            // The responder goes first. Withdrawing after the listener
            // closed would leave a window in which this device is
            // advertised at a port that answers nothing, which is the one
            // failure a discovering peer cannot tell from a bad network.
            self.discovery().withdraw();
            self.advertising.store(false, Ordering::SeqCst);
            // Live sessions stop at their next operation boundary rather
            // than being waited for: a peer that has gone quiet holds a
            // session open for a minute, and switching sync off must not
            // take a minute. **Both directions**: a session this device
            // dialled is as much a live session as one it answered.
            self.shared.serving.store(false, Ordering::SeqCst);
            self.shared.outbound.store(false, Ordering::SeqCst);
            if let Some(mut live) = listening.take() {
                live.stop();
            }
            return Ok(None);
        }
        if let Some(live) = listening.as_ref() {
            return Ok(Some(live.address.clone()));
        }
        let host = self.bind_host.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let preferred = *self.preferred_port.lock().unwrap_or_else(|e| e.into_inner());
        // The preferred port first, an ephemeral one if it is taken, and
        // whichever was actually taken is what gets published (§ 1).
        let socket = TcpListener::bind((host.as_str(), preferred))
            .or_else(|_| TcpListener::bind((host.as_str(), 0)))?;
        // What was bound, and then what a peer can dial. `local_addr` on
        // an every-interface bind answers `0.0.0.0:<port>`, which is the
        // string that used to reach the pairing window, the QR and both
        // shells' settings screens — and which no device on the network
        // could ever connect to.
        let address = publishable(&socket.local_addr()?.to_string(), &routable_addresses());
        self.shared.outbound.store(true, Ordering::SeqCst);
        *self
            .shared
            .advertised
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = address.clone();
        let stopping = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&stopping);
        let shared = Arc::clone(&self.shared);
        shared.serving.store(true, Ordering::SeqCst);
        let thread = std::thread::spawn(move || accept_loop(shared, socket, flag));
        *listening = Some(Listening {
            address: address.clone(),
            stopping,
            thread: Some(thread),
        });
        // Held no longer: advertising reads `listening_on`, which takes
        // this same lock.
        drop(listening);
        self.begin_advertising();
        Ok(Some(address))
    }

    // ------------------------------------------------------------ discovery

    /// Look for devices on this network (§ 1). Blocking, for `browse`'s
    /// own duration: the callers are a button and a background pass, and
    /// neither wants an answer that is still arriving.
    ///
    /// Every device seen is reported, paired or not — a stranger is what
    /// a person is looking at when they are about to pair one, and a
    /// paired device appearing here is how "it is on and I still cannot
    /// reach it" becomes visible rather than mysterious.
    pub fn discover(&self, timeout: std::time::Duration) -> Result<Vec<FoundDevice>> {
        let mine = identity::fingerprint_compact(self.shared.identity.public());
        let found = self.discovery().browse(timeout)?;
        let roster = self.roster();
        Ok(found
            .into_iter()
            // Never offer this device to itself. It is advertising on the
            // same network and it answers its own browse.
            .filter(|d| d.fingerprint != mine)
            .map(|d| {
                let known = roster
                    .peers()
                    .iter()
                    .find(|p| p.fingerprint_compact() == d.fingerprint)
                    .map(|p| (p.static_pub.clone(), p.state.as_str().to_string()));
                let (static_pub, state) = known.unwrap_or_default();
                FoundDevice {
                    compatible: d.compatible(),
                    fingerprint: d.fingerprint,
                    address: d.address,
                    static_pub,
                    state: if state.is_empty() { "unknown".to_string() } else { state },
                }
            })
            .collect())
    }

    /// Record an address a person typed for a paired device (§ 1, step
    /// two of the fallback chain). Deliberately a roster write rather
    /// than a one-shot dial: an address that worked once is the address
    /// to try first next time, which is the whole cached-address rule.
    pub fn set_peer_address(&self, static_pub: &str, address: &str) -> Result<bool> {
        let address = address.trim();
        if address.is_empty() || !address.contains(':') {
            return Err(SyncError::Refused {
                detail: "an address is a host and a port, like 192.168.1.20:51837".to_string(),
            });
        }
        self.roster().set_address(static_pub, address)
    }

    // ------------------------------------------------------------- pairing

    /// Open a pairing window and return what to show. One window at a
    /// time, and asking again replaces the old one — which is also how a
    /// user gets a new code after theirs was abandoned.
    pub fn start_pairing(&self) -> Result<PairingOffer> {
        self.start_pairing_with(pairing::DEFAULT_WINDOW_MS, pairing::DEFAULT_MAX_ATTEMPTS)
    }

    /// The same, with the bounds named. § 8's rule that "the numbers are
    /// configuration with documented defaults, not constants scattered
    /// through the exchange loop" applies to the guessing bounds too, and
    /// naming them here is what lets "a code expires" be a test rather
    /// than a three-minute wait.
    pub fn start_pairing_with(&self, window_ms: i64, max_attempts: u32) -> Result<PairingOffer> {
        let Some(address) = self.listening_on() else {
            return Err(SyncError::Refused {
                detail: "turn sync on before pairing a device".to_string(),
            });
        };
        let window = PairingWindow::open(
            &address,
            &identity::fingerprint_compact(self.shared.identity.public()),
            ids::now_ms(),
            window_ms,
            max_attempts,
        )?;
        let offer = offer_from(&window);
        *self.shared.window.lock().unwrap_or_else(|e| e.into_inner()) = Some(window);
        Ok(offer)
    }

    /// Replace the establishment rate limits. Configuration, like every
    /// other bound in § 8: a host that knows its situation may loosen or
    /// tighten them, and a test may name them.
    pub fn configure_rate_limits(&self, limiter: RateLimiter) {
        *self.shared.rate.lock().unwrap_or_else(|e| e.into_inner()) = limiter;
    }

    /// The text the open window's QR encodes.
    ///
    /// **Deliberately absent from the FFI.** ADR 0019 § 7 keeps the
    /// pairing secret from ever crossing the boundary as a string a shell
    /// might log — the shell gets `pairing_qr`'s module matrix and
    /// nothing else. This exists so the exchange can be driven end to end
    /// in a test, and so a scanner's payload has one definition.
    pub fn pairing_qr_payload(&self) -> Result<String> {
        let window = self.shared.window.lock().unwrap_or_else(|e| e.into_inner());
        let window = window.as_ref().ok_or(SyncError::Refused {
            detail: "no pairing is open".to_string(),
        })?;
        if let Some(closed) = window.state(ids::now_ms()) {
            return Err(SyncError::Refused { detail: closed.as_str().to_string() });
        }
        Ok(window.qr_payload())
    }

    /// A session with one paired peer, for a caller that wants to drive
    /// the exchange itself rather than take `sync_now`'s whole round.
    pub fn dial_peer(&self, static_pub: &str) -> Result<Box<dyn crate::Session>> {
        let address = self
            .roster()
            .find(static_pub)
            .map(|p| p.address.clone())
            .unwrap_or_default();
        if address.is_empty() {
            return Err(SyncError::Refused {
                detail: "no address is known for that device".to_string(),
            });
        }
        self.transport().connect(&format!("{static_pub}@{address}"))
    }

    fn transport(&self) -> SecureTransport {
        SecureTransport::new(
            self.shared.identity.clone(),
            self.shared.greeting(),
            Arc::clone(&self.shared.roster),
            self.shared.limits,
            Arc::clone(&self.shared.outbound),
        )
    }

    pub fn pairing(&self) -> Option<PairingOffer> {
        let window = self.shared.window.lock().unwrap_or_else(|e| e.into_inner());
        let window = window.as_ref()?;
        if window.state(ids::now_ms()).is_some() {
            return None;
        }
        Some(offer_from(window))
    }

    /// The module matrix for the open window's QR (§ 7). The secret it
    /// carries never crosses as a string.
    pub fn pairing_qr(&self) -> Result<qr::Matrix> {
        let window = self.shared.window.lock().unwrap_or_else(|e| e.into_inner());
        let window = window.as_ref().ok_or(SyncError::Refused {
            detail: "no pairing is open".to_string(),
        })?;
        if let Some(closed) = window.state(ids::now_ms()) {
            return Err(SyncError::Refused { detail: closed.as_str().to_string() });
        }
        qr::encode(&window.qr_payload())
    }

    pub fn pair_with_code(&self, store: &Store, address: &str, code: &str) -> Result<PeerSummary> {
        let code = pairing::normalise_code(code)?;
        self.join(store, address, Credential::Code, code.as_bytes(), None)
    }

    pub fn pair_from_qr(&self, store: &Store, payload: &str) -> Result<PeerSummary> {
        let scanned = pairing::decode_qr_payload(payload)?;
        let address = scanned.address.clone();
        self.join(
            store,
            &address,
            Credential::Qr,
            &scanned.secret,
            Some(scanned.fingerprint),
        )
    }

    /// The joining half of a pairing, for both credentials.
    fn join(
        &self,
        store: &Store,
        address: &str,
        credential: Credential,
        secret: &[u8],
        expect_fingerprint: Option<String>,
    ) -> Result<PeerSummary> {
        let now = ids::now_ms();
        self.shared
            .rate
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .check(address, now)?;
        let attempt = dial(address).and_then(|stream| {
            handshake::dial_pairing(
                stream,
                self.shared.limits,
                &self.shared.identity,
                &self.shared.greeting(),
                credential,
                secret,
            )
        });
        let mut established = match attempt {
            Ok(established) => established,
            Err(e) => {
                self.shared
                    .rate
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .failed(address, now);
                return Err(e);
            }
        };
        // A QR names the device it came from. If the handshake produced a
        // different identity, the code was photographed off somebody
        // else's screen or redirected — refuse, and store nothing.
        if let Some(expected) = expect_fingerprint {
            let actual = identity::fingerprint_compact(&established.remote_static);
            if actual != expected {
                self.shared
                    .rate
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .failed(address, now);
                return Err(SyncError::Refused {
                    detail: "that device is not the one the pairing code names".to_string(),
                });
            }
        }
        // Wait for the far end to say it wrote the pairing down. Both
        // rosters end up holding it, or neither side believes it did.
        if let Err(e) = handshake::await_paired(&mut established.channel) {
            self.shared
                .rate
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .failed(address, now);
            return Err(e);
        }
        self.shared
            .rate
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .succeeded(address);
        let peer = self.roster().pair(
            &established.remote_static_hex(),
            &established.peer.device_id,
            &established.peer.name,
            address,
            established.derived_secret,
        )?;
        // Re-pairing a device that was revoked is a new pairing, so its
        // operations stop being refused. An explicit welcome back is the
        // **only** thing that takes a device out of the kernel's refusal
        // set, and it withdraws the standing alert with it: nobody should
        // have to dismiss a notice about a device they have just paired.
        store.unrefuse_device(&established.peer.device_id)?;
        Ok(summary(&peer))
    }

    // ---------------------------------------------------------------- peers

    pub fn peers(&self) -> Vec<PeerSummary> {
        self.roster().peers().iter().map(summary).collect()
    }

    /// Revoke a peer: local, immediate, and it takes the pair secret with
    /// it. A live session ends at the next operation boundary — inbound
    /// because `serve` asks the roster between requests, outbound because
    /// a dialled session asks the same question before every request it
    /// writes.
    pub fn revoke_peer(&self, store: &Store, static_pub: &str) -> Result<bool> {
        let device_id = self
            .roster()
            .find(static_pub)
            .map(|p| p.device_id.clone())
            .unwrap_or_default();
        let revoked = self.roster().revoke(static_pub)?;
        if revoked && !device_id.is_empty() {
            // Added to the kernel's durable set, not written over it: a
            // revocation is a decision this vault carries from here on,
            // whatever happens to the device-local roster afterwards.
            store.refuse_device(&device_id)?;
        }
        Ok(revoked)
    }

    // ----------------------------------------------------------------- sync

    /// Reach one paired peer, down § 1's chain.
    ///
    /// **The order is the decision.** A cached address is tried first and
    /// discovery only runs when it fails — which is what makes the
    /// phone's background job cheap, and what answers a network that
    /// filters multicast but routes unicast. Whatever answers is written
    /// back to the roster, so the expensive step pays for itself once and
    /// the next session is a unicast dial again.
    ///
    /// When the chain runs out the error is § 1's third step: words that
    /// name what is probably wrong, never a spinner. A caller has already
    /// been given the manual-entry route by then — `set_peer_address` is
    /// how a person supplies the address this could not find.
    fn reach(
        &self,
        transport: &SecureTransport,
        static_pub: &str,
        cached: &str,
        called: &str,
        browsed: &mut Browsed,
    ) -> Result<Box<dyn crate::Session>> {
        if !cached.is_empty() {
            match transport.connect(&format!("{static_pub}@{cached}")) {
                Ok(session) => return Ok(session),
                // A refusal from a peer that *answered* is a different
                // fact from an address that answered nothing: being told
                // no is not something discovery can fix, so the chain
                // stops here rather than browsing the network over it.
                Err(e @ SyncError::Protocol { .. }) => return Err(e),
                Err(_) => {}
            }
        }
        let looked = self.discovery().name() != "none";
        // **One browse per round, however many peers are unreachable.** A
        // browse costs 2.5 s — measured, and twice what exchanging a
        // hundred operations costs — so a laptop with three peers asleep
        // used to spend seven and a half seconds asking the same question
        // three times and getting the same answer. The answer describes
        // the network, not the peer, so it is asked once and shared.
        let seen = browsed.get(self);
        let wanted = self
            .roster()
            .find(static_pub)
            .map(|p| p.fingerprint_compact())
            .unwrap_or_default();
        let ours = seen
            .iter()
            .find(|d| !wanted.is_empty() && d.fingerprint == wanted);
        let found = ours.filter(|d| d.compatible());
        if let Some(found) = found {
            let session = transport.connect(&format!("{static_pub}@{}", found.address))?;
            // Discovery is expensive and this is what stops it being
            // paid twice: the address that worked becomes the cached one.
            let _ = self.roster().set_address(static_pub, &found.address);
            return Ok(session);
        }
        Err(SyncError::Refused {
            detail: unreachable_because(
                called,
                if cached.is_empty() { "any address this device knows" } else { cached },
                looked,
                seen.len(),
                // Seen, and not something this build can talk to. A
                // different fact from "not seen", and a different fix.
                ours.is_some(),
            ),
        })
    }

    /// Sync with every paired peer, reaching each down the chain in § 1.
    pub fn sync_now(&self, store: &Store) -> Result<ExchangeReport> {
        // A responder that failed to start once is retried here rather
        // than left down until somebody toggles the switch. An adapter
        // can report failure for a registration that in fact completes
        // late — the Android one waits a bounded time for
        // `onServiceRegistered` — and a device that is listening but has
        // given up on being findable is precisely the "reachable but not
        // findable" state § 1's chain exists to avoid needing.
        if self.enabled() && !self.advertising() {
            self.begin_advertising();
        }
        let transport = self.transport();
        let mut browsed = Browsed::default();
        let targets: Vec<(String, String, String)> = self
            .roster()
            .peers()
            .iter()
            .filter(|p| p.state == PeerState::Paired)
            .map(|p| {
                let called = if p.name.is_empty() { p.fingerprint() } else { p.name.clone() };
                (p.static_pub.clone(), p.address.clone(), called)
            })
            .collect();
        let mut total = ExchangeReport::default();
        for (static_pub, address, called) in targets {
            let mut session = match self.reach(
                &transport,
                &static_pub,
                &address,
                &called,
                &mut browsed,
            ) {
                Ok(session) => session,
                Err(e) => {
                    // A connection that never opened is not one device's
                    // *stream* halting, so it is named by the peer rather
                    // than by an originating device id — what the shell
                    // shows has to make sense to a person.
                    total.halted.push(crate::Halted {
                        device_id: called,
                        reason: e.to_string(),
                    });
                    continue;
                }
            };
            match exchange::sync(store, session.as_mut(), self.shared.limits) {
                Ok(report) => {
                    total.applied += report.applied;
                    total.duplicates += report.duplicates;
                    total.uninterpreted += report.uninterpreted;
                    total.sent += report.sent;
                    total.chunks += report.chunks;
                    total.blobs_completed += report.blobs_completed;
                    // **Not a sum.** Every other counter here is work this
                    // round did, so adding is right; this one is a count
                    // of rows still waiting in *this vault*, answered
                    // once per session. Adding it across three peers
                    // would report one outstanding blob as three, and
                    // `incomplete()` reads it — so a shell would keep
                    // being told there was more to come. The last session
                    // has the most recent answer; taking the largest is
                    // the same thing without depending on the order.
                    total.blobs_pending = total.blobs_pending.max(report.blobs_pending);
                    total.bytes += report.bytes;
                    total.halted.extend(report.halted);
                    total.ended_on_cap |= report.ended_on_cap;
                }
                Err(e) => total.halted.push(crate::Halted {
                    device_id: called,
                    reason: e.to_string(),
                }),
            }
            let _ = session.close();
        }
        Ok(total)
    }

    // --------------------------------------------------------------- status

    pub fn facts(&self) -> SyncFacts {
        SyncFacts {
            enabled: self.enabled(),
            device_id: self.device_id.clone(),
            device_name: self.shared.name.clone(),
            fingerprint: self.shared.identity.fingerprint(),
            identity_pub: self.shared.identity.public_hex(),
            key_protection: self.identities.protection(&self.device_id),
            enrolment: self.enrolment.as_str().to_string(),
            enrolment_notice: self.enrolment.notice(),
            listening_on: self.listening_on(),
            wanted: self.wanted(),
            advertising: self.advertising(),
            discovery: self.discovery_route(),
            pairing: self.pairing(),
            peers: self.peers(),
        }
    }
}

/// One round's answer to "what is on this network", asked at most once.
///
/// The measurement gate C recorded is the reason this type exists: a cold
/// browse costs 2.5 s against an exchange's 1.2 s, so browsing per
/// unreachable peer made the expensive half of a sync scale with how many
/// devices were asleep. What a browse returns describes the network and
/// not the peer that prompted it, so one answer serves every peer in the
/// round — and it is still *lazy*, so a round in which every cached
/// address works browses not at all, which is § 1's rule intact.
#[derive(Default)]
struct Browsed {
    answer: Option<Vec<crate::discovery::Discovered>>,
}

impl Browsed {
    fn get(&mut self, service: &SyncService) -> &[crate::discovery::Discovered] {
        if self.answer.is_none() {
            let discovery = service.discovery();
            let mine = identity::fingerprint_compact(service.shared.identity.public());
            let seen = if discovery.name() == "none" {
                Vec::new()
            } else {
                discovery
                    .browse(crate::discovery::DEFAULT_BROWSE)
                    .unwrap_or_default()
                    .into_iter()
                    // This device is advertising on the same network and
                    // answers its own browse. Counting it would make the
                    // diagnostic say "1 other device is visible" on a
                    // network where this machine is entirely alone —
                    // which is the network the diagnostic most needs to
                    // describe correctly.
                    .filter(|d| d.fingerprint != mine)
                    .collect()
            };
            self.answer = Some(seen);
        }
        self.answer.as_deref().unwrap_or_default()
    }
}

fn offer_from(window: &PairingWindow) -> PairingOffer {
    PairingOffer {
        code: window.code().to_string(),
        address: window.address.clone(),
        fingerprint: window.fingerprint.clone(),
        expires_ms: window.expires_ms,
        attempts_left: window.remaining_attempts(),
    }
}

fn summary(peer: &crate::roster::Peer) -> PeerSummary {
    PeerSummary {
        static_pub: peer.static_pub.clone(),
        fingerprint: peer.fingerprint(),
        device_id: peer.device_id.clone(),
        name: peer.name.clone(),
        address: peer.address.clone(),
        state: peer.state.as_str().to_string(),
        paired_ms: peer.paired_ms,
        last_seen_ms: peer.last_seen_ms,
    }
}

// ------------------------------------------------------------- the listener

/// Accept peers and serve each on its own thread, up to
/// `MAX_CONCURRENT_SESSIONS`.
///
/// M5b served one at a time and said so: a boundary proof does not need
/// concurrency. A mesh does — with one worker, a phone in the middle of a
/// long first synchronisation makes every other device's Sync-now wait
/// behind it, and on a desktop that is an always-available listener the
/// wait is unbounded. Thread-per-session is right at this scale (a
/// handful of one person's own devices) and the cap is what keeps
/// "another peer connected" from meaning "another thread, forever".
///
/// Each session opens **its own kernel handle**, exactly as a second
/// process would — multi-instance on one vault is supported use (owner
/// decision 2026-08-31), and it is also what makes thread-per-session
/// possible at all, since a `Store` is not shared across threads.
fn accept_loop(shared: Arc<SyncShared>, socket: TcpListener, stopping: Arc<AtomicBool>) {
    for stream in socket.incoming() {
        if stopping.load(Ordering::SeqCst) {
            break;
        }
        let Ok(stream) = stream else { continue };
        let _ = stream.set_nodelay(true);
        let caller = stream
            .peer_addr()
            .map(|a| a.ip().to_string())
            .unwrap_or_else(|_| "unknown".to_string());
        let now = ids::now_ms();
        if shared
            .rate
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .check(&caller, now)
            .is_err()
        {
            // Held up. Closing without a word is the whole answer: a
            // caller being backed off learns nothing from us.
            continue;
        }
        if shared.live.load(Ordering::SeqCst) >= MAX_CONCURRENT_SESSIONS {
            // Full. Dropping the stream is the refusal, and it costs the
            // caller one retry: resuming from a frontier is free, which
            // is exactly why turning a peer away is safe.
            continue;
        }
        shared.live.fetch_add(1, Ordering::SeqCst);
        let session_shared = Arc::clone(&shared);
        // Detached, deliberately. The handle is not kept because nothing
        // wants to wait for it: switching sync off is answered by the
        // `serving` flag at the next operation boundary, and the counter
        // above is what bounds how many can be in flight.
        match std::thread::Builder::new()
            .name("gilune-sync-session".to_string())
            .spawn(move || {
                // The guard, not the end of the function: a handler that
                // panics must still give its slot back, or a crash in one
                // session would shrink the listener for good.
                let _slot = SessionSlot(Arc::clone(&session_shared.live));
                let Ok(store) = Store::open(&session_shared.vault) else { return };
                let _ = answer(&session_shared, &store, stream, &caller);
            }) {
            Ok(_detached) => {}
            Err(_) => {
                shared.live.fetch_sub(1, Ordering::SeqCst);
            }
        }
    }
}

/// Holds one of `MAX_CONCURRENT_SESSIONS`, and gives it back however the
/// session ends.
struct SessionSlot(Arc<AtomicUsize>);

impl Drop for SessionSlot {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

fn answer(
    shared: &Arc<SyncShared>,
    store: &Store,
    stream: std::net::TcpStream,
    caller: &str,
) -> Result<()> {
    let now = ids::now_ms();
    // **Before a byte is read.** An accepted socket carries no timeouts
    // at all, and every read that follows blocks for as long as the
    // caller likes — so eight sockets opened and left silent took every
    // session slot this listener has and held them until the process
    // ended. The dialling side has always bounded its own establishment;
    // this is the same bound applied by the side that did not choose to
    // be in the conversation. It is replaced by `SESSION_TIMEOUT` the
    // moment a handshake completes.
    //
    // A failure to set it is not a reason to refuse the caller — some of
    // these calls cannot fail on any platform we ship — but it is a
    // reason not to pretend the bound is there, so it is best-effort and
    // the slot cap remains the backstop.
    let _ = handshake::timed(&stream, shared.handshake_timeout());
    let mut channel = PlainChannel::new(stream, shared.limits);
    // A caller that connects and says nothing now fails here, on a
    // timeout, which lands in the same arm a malformed hello does: it
    // costs the caller's address one rate-limiter failure, so silence is
    // no longer the one way to knock on this door for free.
    let intent = match handshake::read_hello(&mut channel) {
        Ok(intent) => intent,
        Err(e) => {
            shared
                .rate
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .failed(caller, now);
            return Err(e);
        }
    };

    match intent {
        Intent::Pair(credential) => {
            let secret = {
                let window = shared.window.lock().unwrap_or_else(|e| e.into_inner());
                match window.as_ref() {
                    Some(window) => window.secret(credential, now),
                    None => Err(SyncError::Refused {
                        detail: "that device is not expecting to pair".to_string(),
                    }),
                }
            };
            let secret = match secret {
                Ok(secret) => secret,
                Err(e) => {
                    // The window is closed, expired or abandoned. Saying
                    // so is a kindness to a person and no help to a
                    // guesser: there is no code to guess at any more.
                    let _ = handshake::answer_refused(&mut channel, &e.to_string());
                    return Err(e);
                }
            };
            let outcome = handshake::accept_pairing(
                channel,
                shared.limits,
                &shared.identity,
                &shared.greeting(),
                credential,
                &secret,
            );
            match outcome {
                Ok(mut established) => {
                    // Single use: a correct code is exactly what somebody
                    // who watched it being typed would also have.
                    if let Some(window) =
                        shared.window.lock().unwrap_or_else(|e| e.into_inner()).as_mut()
                    {
                        window.consume();
                    }
                    shared
                        .rate
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .succeeded(caller);
                    // The joiner dialled us, so this connection's source
                    // port is not where it listens. It told us its port
                    // inside the encrypted handshake; the *host* comes
                    // from the socket it actually arrived on, so a peer
                    // cannot make this device record an address it chose.
                    let address = match established.peer.advertised_port() {
                        Some(port) if port != 0 => format!("{caller}:{port}"),
                        _ => format!("{caller}:0"),
                    };
                    shared
                        .roster
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .pair(
                            &established.remote_static_hex(),
                            &established.peer.device_id,
                            &established.peer.name,
                            &address,
                            established.derived_secret,
                        )?;
                    // A device that has just been paired is welcome, and
                    // that is the only thing that takes one out of the
                    // kernel's durable refusal set — the standing alert
                    // goes with it.
                    store.unrefuse_device(&established.peer.device_id)?;
                    // Only now is the joiner told. Until this frame the
                    // pairing existed on one side only, and a joiner that
                    // returned before it would believe in a roster entry
                    // the far end had not written.
                    handshake::confirm_paired(&mut established.channel)?;
                    Ok(())
                }
                Err(e) => {
                    // One online guess spent. The window abandons itself
                    // at its limit and clears its secrets with it.
                    if let Some(window) =
                        shared.window.lock().unwrap_or_else(|e| e.into_inner()).as_mut()
                    {
                        window.fail();
                    }
                    shared
                        .rate
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .failed(caller, now);
                    Err(e)
                }
            }
        }
        Intent::Session => {
            let candidates = shared
                .roster
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .candidate_secrets();
            let established = match handshake::accept_session(
                channel,
                shared.limits,
                &shared.identity,
                &shared.greeting(),
                &candidates,
            ) {
                Ok(established) => established,
                Err(e) => {
                    shared
                        .rate
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .failed(caller, now);
                    return Err(e);
                }
            };
            let static_pub = established.remote_static_hex();
            shared
                .rate
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .succeeded(caller);
            {
                // What a session teaches the *listening* side about where
                // its peer can be reached. The dialling side learns this
                // for free — it dialled — but a device that only ever
                // receives would otherwise keep whatever address pairing
                // recorded, and that is exactly the address that goes
                // stale when the peer restarts on a different port.
                //
                // Same rule as pairing: the **host** comes from the socket
                // the peer actually arrived on, and only the **port** is
                // believed from what it said inside the encrypted
                // handshake — so a peer cannot make this device record an
                // address it chose.
                let learned = match established.peer.advertised_port() {
                    Some(port) if port != 0 => format!("{caller}:{port}"),
                    _ => String::new(),
                };
                let mut roster = shared.roster.lock().unwrap_or_else(|e| e.into_inner());
                roster.seen(&static_pub, &established.peer.device_id, &learned)?;
            }
            let roster = Arc::clone(&shared.roster);
            let serving = Arc::clone(&shared.serving);
            let pinned = static_pub.clone();
            // Consulted between requests, and answering two questions
            // with one mechanism. Revoking takes the pair secret away, so
            // this goes false the instant the roster changes; switching
            // sync off clears `serving`, so a live session ends then too.
            // Either way the session ends on the next boundary with
            // nothing half-applied, because apply is one transaction per
            // operation and the boundary between two requests is also a
            // boundary between two whole operations.
            let permitted = move || {
                serving.load(Ordering::SeqCst)
                    && roster
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .secret_for(&pinned)
                        .is_some()
            };
            let mut channel = established.channel;
            protocol::serve(store, &mut channel, &shared.limits, &permitted)?;
            Ok(())
        }
    }
}
