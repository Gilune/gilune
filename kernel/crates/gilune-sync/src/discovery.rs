//! Finding a device that nobody typed an address for (ADR 0019 § 1).
//!
//! The service is `_gilune._tcp`, and **the advertisement carries no
//! personal data**: the instance name is derived from the static-key
//! fingerprint and the TXT record carries the protocol version and that
//! fingerprint and nothing else. A paired peer recognises the
//! fingerprint; an observer on a café network learns that a Gilune device
//! is present — unavoidable for any advertised service — and not whose,
//! nor what it is called. The human device name is exchanged inside the
//! encrypted handshake and never here.
//!
//! `PeerDiscovery` is a capability trait with two implementations, split
//! by platform because the platforms genuinely differ:
//!
//! - **`MdnsDiscovery`** (this file) wraps `mdns-sd`, for desktop and for
//!   any future headless host. It has no async runtime, so it works from
//!   the kernel's synchronous world and from ADR 0008's UI-less agent,
//!   which has no JVM to borrow an implementation from.
//! - **Android's `NsdManager`**, through a Kotlin adapter registered
//!   across the FFI. The platform owns the multicast socket, so the app
//!   needs no `MulticastLock` and no `CHANGE_WIFI_MULTICAST_STATE`. This
//!   is ADR 0001's rule applied literally — Rust does not call a platform
//!   API, an adapter implements the trait.
//!
//! **Discovery is never on the connect path when it can be avoided.**
//! `service::reach` tries a paired peer's cached address first and only
//! browses when that fails, which is what makes the phone's background
//! job cheap and what answers a network that filters multicast but routes
//! unicast.

use crate::{Result, SyncError, PROTOCOL};
use std::sync::Mutex;
use std::time::Duration;

/// The DNS-SD service type, as `mdns-sd` wants it spelled — with the
/// trailing domain.
///
/// `NsdManager` wants the bare `_gilune._tcp`, and that spelling lives in
/// the Kotlin adapter (`Discovery.kt`'s `SYNC_SERVICE_TYPE`) because that
/// is the only place that can use it: it never crosses the FFI. The two
/// therefore **can** drift, and this comment is where a reader finds out
/// that they are a pair — a bare constant here that nothing referred to
/// only made it look as though they could not.
pub const SERVICE_TYPE_FQ: &str = "_gilune._tcp.local.";

/// TXT keys. Two of them, and that is the whole record.
pub const TXT_VERSION: &str = "v";
pub const TXT_FINGERPRINT: &str = "fp";

/// How long a browse waits before answering with what it has. Chosen
/// against the standing budget — "LAN sync of 100 small operations
/// between two idle devices: under 5 s" — which puts a cold browse
/// *inside* the five seconds, so a browse that ran to five seconds on its
/// own would have spent the whole budget before a byte moved.
pub const DEFAULT_BROWSE: Duration = Duration::from_millis(2500);

/// What this device puts on the wire. Constructed from the fingerprint
/// and the port, and there is deliberately no constructor taking a name:
/// the type cannot express a personal advertisement.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Advertisement {
    pub fingerprint: String,
    pub port: u16,
}

/// A device seen on the network. `fingerprint` is how a paired peer is
/// recognised — the roster is keyed by static public key, and the
/// fingerprint is that key's short form, so matching one to the other is
/// a comparison rather than a trust decision.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Discovered {
    pub fingerprint: String,
    pub protocol: String,
    /// `host:port`, ready to dial.
    pub address: String,
}

impl Discovered {
    /// Whether this device speaks a protocol this build can talk. A
    /// mismatch is not an error — it is a device on the same LAN running
    /// a different version, and the honest thing is to show it as
    /// unreachable rather than to fail a browse over it.
    pub fn compatible(&self) -> bool {
        self.protocol == PROTOCOL
    }
}

/// The capability. Advertising and browsing are separate verbs because
/// they fail separately: a device behind a firewall rule that blocks
/// inbound UDP can still browse, and saying so is better than reporting
/// one failure for both.
pub trait PeerDiscovery: Send + Sync {
    /// A name for what implements this, for a diagnostic that has to say
    /// which route was tried.
    fn name(&self) -> String;

    /// Start advertising. Called when sync is switched on, never before
    /// (§ 2) — this is the second inbound bind, on UDP 5353, and it is
    /// not an escape from the first.
    fn advertise(&self, ad: &Advertisement) -> Result<()>;

    /// Stop advertising. Called when sync is switched off.
    fn withdraw(&self);

    /// Browse for up to `timeout` and answer with what was seen. Blocking
    /// by contract: the callers are a background thread and a button.
    fn browse(&self, timeout: Duration) -> Result<Vec<Discovered>>;
}

/// The honest no-op, for a host that has registered no adapter. It is not
/// a silent one: `browse` answers empty and `name` says so, which is what
/// lets the diagnostic in `service` distinguish "nothing was found" from
/// "nothing could look".
pub struct NoDiscovery;

impl PeerDiscovery for NoDiscovery {
    fn name(&self) -> String {
        "none".to_string()
    }
    fn advertise(&self, _ad: &Advertisement) -> Result<()> {
        Ok(())
    }
    fn withdraw(&self) {}
    fn browse(&self, _timeout: Duration) -> Result<Vec<Discovered>> {
        Ok(Vec::new())
    }
}

/// `mdns-sd`, held open for the life of the service.
///
/// The daemon is created lazily on the first `advertise` or `browse`, not
/// in the constructor: creating it opens the UDP 5353 socket, and § 2 is
/// explicit that nothing binds until the user turns sync on. A service
/// constructed at startup that never syncs must never have touched a
/// socket, and the only way to be sure of that is for the constructor not
/// to be able to.
pub struct MdnsDiscovery {
    daemon: Mutex<Option<mdns_sd::ServiceDaemon>>,
    /// The full name currently registered, so `withdraw` can unregister
    /// exactly what `advertise` registered rather than guess at it.
    registered: Mutex<Option<String>>,
}

impl Default for MdnsDiscovery {
    fn default() -> Self {
        Self::new()
    }
}

impl MdnsDiscovery {
    pub fn new() -> MdnsDiscovery {
        MdnsDiscovery {
            daemon: Mutex::new(None),
            registered: Mutex::new(None),
        }
    }

    /// The daemon, started if this is the first call.
    ///
    /// **The Windows coexistence question lives here** (research note
    /// § 1.1): Windows 10+ ships its own mDNS responder bound to UDP
    /// 5353, and whether a second responder binds alongside it cleanly
    /// was UNVERIFIED at design time. It is measured in
    /// `tests/gate_c.rs`. Whatever the answer, the failure is *returned*
    /// rather than panicked, because § 1's fallback chain is what happens
    /// next and a device that cannot advertise can still be reached at a
    /// typed address.
    fn daemon(&self) -> Result<mdns_sd::ServiceDaemon> {
        let mut held = self.daemon.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(daemon) = held.as_ref() {
            return Ok(daemon.clone());
        }
        let daemon = mdns_sd::ServiceDaemon::new().map_err(|e| SyncError::Refused {
            detail: format!("this machine would not open a discovery socket: {e}"),
        })?;
        *held = Some(daemon.clone());
        Ok(daemon)
    }
}

impl PeerDiscovery for MdnsDiscovery {
    fn name(&self) -> String {
        "mdns-sd".to_string()
    }

    fn advertise(&self, ad: &Advertisement) -> Result<()> {
        let daemon = self.daemon()?;
        // The instance name IS the fingerprint. DNS-SD instance names are
        // visible to anyone on the network, and § 1 makes that a design
        // constraint rather than an accident: what leaks is a random-
        // looking identifier, not a person's laptop's name.
        let instance = ad.fingerprint.replace(' ', "");
        let properties = [
            (TXT_VERSION, PROTOCOL),
            (TXT_FINGERPRINT, ad.fingerprint.as_str()),
        ];
        let info = mdns_sd::ServiceInfo::new(
            SERVICE_TYPE_FQ,
            &instance,
            &format!("{instance}.local."),
            (),
            ad.port,
            &properties[..],
        )
        .map_err(|e| SyncError::Refused {
            detail: format!("this device's advertisement could not be built: {e}"),
        })?
        // Let the daemon track this machine's addresses itself. A laptop
        // that moves between Wi-Fi and Ethernet changes address without
        // telling us, and an advertisement pinned to the address it had
        // at switch-on would point at nothing.
        .enable_addr_auto();
        let fullname = info.get_fullname().to_string();
        daemon.register(info).map_err(|e| SyncError::Refused {
            detail: format!("this device could not advertise itself: {e}"),
        })?;
        *self.registered.lock().unwrap_or_else(|e| e.into_inner()) = Some(fullname);
        Ok(())
    }

    fn withdraw(&self) {
        let fullname = self
            .registered
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        let daemon = self.daemon.lock().unwrap_or_else(|e| e.into_inner());
        if let (Some(daemon), Some(fullname)) = (daemon.as_ref(), fullname) {
            let _ = daemon.unregister(&fullname);
        }
    }

    fn browse(&self, timeout: Duration) -> Result<Vec<Discovered>> {
        let daemon = self.daemon()?;
        let receiver = daemon.browse(SERVICE_TYPE_FQ).map_err(|e| SyncError::Refused {
            detail: format!("this device could not look for others: {e}"),
        })?;
        // Read once per browse, not once per answer: the interface table
        // is a system call and it does not change between two packets.
        let local = crate::addressing::local_addresses();
        let deadline = std::time::Instant::now() + timeout;
        let mut found: Vec<Discovered> = Vec::new();
        while let Some(remaining) = deadline.checked_duration_since(std::time::Instant::now()) {
            let Ok(event) = receiver.recv_timeout(remaining) else { break };
            let mdns_sd::ServiceEvent::ServiceResolved(info) = event else { continue };
            let Some(address) = first_address(&info, &local) else { continue };
            let fingerprint = property(&info, TXT_FINGERPRINT);
            if fingerprint.is_empty() {
                continue;
            }
            // A device that answers on two interfaces is one device. Keyed
            // by fingerprint, first address wins: they are the same peer
            // and a second row would be a second entry in somebody's
            // settings screen for one laptop.
            if found.iter().any(|d| d.fingerprint == fingerprint) {
                continue;
            }
            found.push(Discovered {
                fingerprint,
                protocol: property(&info, TXT_VERSION),
                address,
            });
        }
        let _ = daemon.stop_browse(SERVICE_TYPE_FQ);
        Ok(found)
    }
}

fn property(info: &mdns_sd::ResolvedService, key: &str) -> String {
    info.get_property_val_str(key).unwrap_or_default().to_string()
}

/// One dialable `host:port` for a resolved service.
///
/// **IPv4 only, and that is a stated limitation rather than an
/// oversight.** A link-local IPv6 address is only dialable with the scope
/// id of the interface it was heard on, `TcpStream::connect` takes no
/// scope, and carrying one through the roster's `host:port` string would
/// be a wire-format change for a case no home network needs — every LAN
/// that routes Gilune at all routes v4. A device reachable only over IPv6
/// is therefore not discovered, and § 1's manual `host:port` entry is
/// what answers it.
fn first_address(info: &mdns_sd::ResolvedService, local: &[std::net::Ipv4Addr]) -> Option<String> {
    best_address(
        &info.get_addresses_v4().into_iter().collect::<Vec<_>>(),
        info.get_port(),
        local,
    )
}

/// The one address to record for a peer that answered on several.
///
/// A machine answers on every interface it has, and the order matters
/// because only one address gets dialled. **The loopback must sort last**,
/// which was found by running the live test rather than by reading: a
/// responder advertises 127.0.0.1 alongside its real address, and a plain
/// numeric sort puts the loopback first — so a peer on *another* machine
/// would be recorded at 127.0.0.1 and every dial to it would arrive back
/// at ourselves. Invisible on one machine, fatal on two.
///
/// The rest of the ordering, and the dropping of addresses that name no
/// device at all, is `addressing`'s — the same ranking this machine
/// publishes itself by, so the two sides of a dial cannot disagree about
/// what a good address is.
pub(crate) fn best_address(
    offered: &[std::net::Ipv4Addr],
    port: u16,
    local: &[std::net::Ipv4Addr],
) -> Option<String> {
    crate::addressing::ranked(offered, local)
        .into_iter()
        .next()
        .map(|ip| format!("{ip}:{port}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_advertisement_carries_a_fingerprint_and_a_port_and_can_carry_nothing_else() {
        // Not a style assertion: § 1's privacy property is that the type
        // has no field a name could be put in. If this ever compiles with
        // a third field, the reviewer who added it has to come here.
        let ad = Advertisement { fingerprint: "ab cd ef".to_string(), port: 51837 };
        assert_eq!(ad.port, 51837);
        assert_eq!(ad.fingerprint, "ab cd ef");
    }

    #[test]
    fn a_peer_speaking_another_protocol_is_visible_and_not_compatible() {
        let ours = Discovered {
            fingerprint: "aa".into(),
            protocol: PROTOCOL.to_string(),
            address: "10.0.0.2:51837".into(),
        };
        let theirs = Discovered { protocol: "gilune-sync/99".to_string(), ..ours.clone() };
        assert!(ours.compatible());
        assert!(
            !theirs.compatible(),
            "a newer device on the same LAN is a device we cannot talk to, not a broken browse"
        );
    }

    #[test]
    fn a_host_with_no_adapter_finds_nothing_and_says_which_route_that_was() {
        let none = NoDiscovery;
        assert_eq!(none.name(), "none");
        assert!(none.browse(Duration::from_millis(1)).expect("browse").is_empty());
        // Advertising is a no-op rather than an error: a host with no
        // discovery is a supported configuration (§ 1's manual entry),
        // not a broken one.
        none.advertise(&Advertisement { fingerprint: "aa".into(), port: 1 })
            .expect("no-op");
    }

    #[test]
    fn a_peers_loopback_address_is_the_last_one_this_device_would_dial() {
        // Found by running the live test, not by reading the code. A
        // responder advertises 127.0.0.1 beside its real address; a plain
        // numeric sort puts the loopback first, so a peer on **another**
        // machine gets recorded at 127.0.0.1 and every dial to it arrives
        // back at ourselves. It works perfectly on one machine and fails
        // completely on two, which is the worst shape a defect can have.
        let real: std::net::Ipv4Addr = "192.168.1.20".parse().expect("ip");
        let loopback: std::net::Ipv4Addr = "127.0.0.1".parse().expect("ip");
        let link_local: std::net::Ipv4Addr = "169.254.3.4".parse().expect("ip");

        assert_eq!(
            best_address(&[loopback, real, link_local], 51837, &[]).as_deref(),
            Some("192.168.1.20:51837"),
            "a routable address first, never the loopback of the machine doing the dialling"
        );
        assert_eq!(
            best_address(&[loopback, link_local], 51837, &[]).as_deref(),
            Some("169.254.3.4:51837"),
            "and what DHCP never answered for beats what reaches only ourselves"
        );
    }

    /// **Second-order test for the shared classifier**, from the side that
    /// dials. The publishing side offers every candidate it has; this
    /// decides which of them a peer on *this* machine's network is
    /// actually at, and it must not be answered by a numeric sort.
    #[test]
    fn a_resolved_peer_is_dialled_on_this_machines_own_segment_first() {
        let vpn: std::net::Ipv4Addr = "10.8.0.2".parse().expect("ip");
        let lan: std::net::Ipv4Addr = "192.168.1.20".parse().expect("ip");
        let mine: std::net::Ipv4Addr = "192.168.1.77".parse().expect("ip");
        assert_eq!(
            best_address(&[vpn, lan], 51837, &[mine]).as_deref(),
            Some("192.168.1.20:51837"),
            "the peer is on the LAN in front of us; the 10.x is somebody's VPN"
        );

        // And a response carrying an address that names no device is never
        // what gets dialled, whatever else it arrived with.
        let bogus: std::net::Ipv4Addr = "0.0.0.0".parse().expect("ip");
        assert_eq!(
            best_address(&[bogus, lan], 51837, &[mine]).as_deref(),
            Some("192.168.1.20:51837")
        );
        assert_eq!(
            best_address(&[bogus], 51837, &[mine]),
            None,
            "a peer that offered only that offered nothing to dial"
        );
    }

    #[test]
    fn creating_the_mdns_adapter_binds_nothing() {
        // § 2's rule is that the socket waits for the switch. The
        // constructor is the place that would break it silently, so it is
        // the place with a test.
        let adapter = MdnsDiscovery::new();
        assert!(
            adapter.daemon.lock().expect("lock").is_none(),
            "constructing a discovery adapter must not open UDP 5353"
        );
        assert_eq!(adapter.name(), "mdns-sd");
    }
}
