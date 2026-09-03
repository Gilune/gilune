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
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

/// The listener's preferred port, in the dynamic range (ADR 0019 § 1).
/// If it is taken an ephemeral one is used, and the actual port is what
/// the QR and the settings screen show — never the preferred one.
pub const DEFAULT_PORT: u16 = 51837;

/// What M5b binds to.
///
/// **Deliberately the loopback interface at this stage.** M5b's scope is
/// "two processes on one machine, no discovery"; binding a routable
/// interface is what raises the Windows Security Alert, and ADR 0019 § 11
/// puts the firewall UX in M5c together with the discovery that needs it.
/// M5c changes this constant and adds the explanation that has to appear
/// immediately before the prompt.
pub const DEFAULT_BIND_HOST: &str = "127.0.0.1";

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
}

impl SyncShared {
    fn greeting(&self) -> Greeting {
        Greeting::new(
            &self.device_id,
            &self.name,
            &self.advertised.lock().unwrap_or_else(|e| e.into_inner()),
        )
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
        // Wake the blocked accept so the thread can see the flag.
        let _ = std::net::TcpStream::connect(&self.address);
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
    bind_host: String,
}

impl SyncService {
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
        // The kernel's refusal list is a projection of the roster and is
        // refreshed from it here, so a restart cannot lose a revocation.
        store.set_refused_devices(&roster.revoked_device_ids())?;
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
            }),
            listening: Mutex::new(None),
            identities,
            device_id,
            enrolment,
            bind_host: DEFAULT_BIND_HOST.to_string(),
        })
    }

    /// Bind somewhere other than the loopback. Not reachable from the
    /// FFI in M5b — it exists so a test can be explicit about what it
    /// binds, and so M5c has one line to change.
    pub fn bind_host(&mut self, host: &str) {
        self.bind_host = host.to_string();
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

    /// Rewrite the kernel's refusal list from the roster. Called after
    /// every roster change: the roster is the authority and this is the
    /// projection of it that `apply` consults.
    fn publish_refusals(&self, store: &Store) -> Result<()> {
        let revoked = self.roster().revoked_device_ids();
        store.set_refused_devices(&revoked)?;
        Ok(())
    }

    // ------------------------------------------------------------ the listener

    pub fn enabled(&self) -> bool {
        self.listening
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_some()
    }

    pub fn listening_on(&self) -> Option<String> {
        self.listening
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .map(|l| l.address.clone())
    }

    /// Switch sync on or off. **This is the moment the socket is bound**
    /// (ADR 0019 § 2), and switching off closes it and abandons any
    /// pairing window with it.
    pub fn set_enabled(&self, on: bool) -> Result<Option<String>> {
        let mut listening = self.listening.lock().unwrap_or_else(|e| e.into_inner());
        if !on {
            *self.shared.window.lock().unwrap_or_else(|e| e.into_inner()) = None;
            self.shared
                .advertised
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clear();
            if let Some(mut live) = listening.take() {
                live.stop();
            }
            return Ok(None);
        }
        if let Some(live) = listening.as_ref() {
            return Ok(Some(live.address.clone()));
        }
        let socket = TcpListener::bind((self.bind_host.as_str(), DEFAULT_PORT))
            .or_else(|_| TcpListener::bind((self.bind_host.as_str(), 0)))?;
        let address = socket.local_addr()?.to_string();
        *self
            .shared
            .advertised
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = address.clone();
        let stopping = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&stopping);
        let shared = Arc::clone(&self.shared);
        let thread = std::thread::spawn(move || accept_loop(shared, socket, flag));
        *listening = Some(Listening {
            address: address.clone(),
            stopping,
            thread: Some(thread),
        });
        Ok(Some(address))
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
        // operations stop being refused — which means the projection has
        // to be rewritten here too.
        self.publish_refusals(store)?;
        Ok(summary(&peer))
    }

    // ---------------------------------------------------------------- peers

    pub fn peers(&self) -> Vec<PeerSummary> {
        self.roster().peers().iter().map(summary).collect()
    }

    /// Revoke a peer: local, immediate, and it takes the pair secret with
    /// it. A live session ends at the next operation boundary because
    /// `serve` asks the roster between requests.
    pub fn revoke_peer(&self, store: &Store, static_pub: &str) -> Result<bool> {
        let revoked = self.roster().revoke(static_pub)?;
        if revoked {
            self.publish_refusals(store)?;
        }
        Ok(revoked)
    }

    // ----------------------------------------------------------------- sync

    /// Sync with every paired peer whose address this device knows.
    /// M5b has no discovery, so a peer with no cached address is skipped
    /// rather than hunted for.
    pub fn sync_now(&self, store: &Store) -> Result<ExchangeReport> {
        let transport = self.transport();
        let targets: Vec<(String, String, String)> = self
            .roster()
            .peers()
            .iter()
            .filter(|p| p.state == PeerState::Paired && !p.address.is_empty())
            .map(|p| {
                let called = if p.name.is_empty() { p.fingerprint() } else { p.name.clone() };
                (p.static_pub.clone(), p.address.clone(), called)
            })
            .collect();
        let mut total = ExchangeReport::default();
        for (static_pub, address, called) in targets {
            let mut session = match transport.connect(&format!("{static_pub}@{address}")) {
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
                    total.blobs_pending += report.blobs_pending;
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
            pairing: self.pairing(),
            peers: self.peers(),
        }
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

/// One peer at a time, as the loopback server is: M5b proves the
/// boundary, and a concurrent listener is a bound M5c argues for on its
/// own evidence rather than inherits here.
fn accept_loop(shared: Arc<SyncShared>, socket: TcpListener, stopping: Arc<AtomicBool>) {
    // Its own kernel handle, exactly as a second process would open —
    // multi-instance on one vault is supported use (owner decision
    // 2026-08-31), and it keeps a blocking accept off the FFI's lock.
    let Ok(store) = Store::open(&shared.vault) else { return };
    for stream in socket.incoming() {
        if stopping.load(Ordering::SeqCst) {
            return;
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
        let _ = answer(&shared, &store, stream, &caller);
    }
}

fn answer(
    shared: &Arc<SyncShared>,
    store: &Store,
    stream: std::net::TcpStream,
    caller: &str,
) -> Result<()> {
    let now = ids::now_ms();
    let mut channel = PlainChannel::new(stream, shared.limits);
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
                    let revoked = shared
                        .roster
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .revoked_device_ids();
                    store.set_refused_devices(&revoked)?;
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
                let mut roster = shared.roster.lock().unwrap_or_else(|e| e.into_inner());
                roster.seen(&static_pub, &established.peer.device_id, "")?;
            }
            let roster = Arc::clone(&shared.roster);
            let pinned = static_pub.clone();
            // Consulted between requests. Revoking takes the pair secret
            // away, so this goes false the instant the roster changes and
            // the session ends on the next boundary with nothing
            // half-applied.
            let permitted = move || {
                roster
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
