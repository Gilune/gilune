//! ADR 0019 gate C, points 11 to 16: **it works on a live network**.
//!
//! **What this file is and is not.** The gate says scenarios C run
//! "between a real Windows desktop and a real Android device on a real
//! network", and the ratification record says those real-device scenarios
//! are expected to wait on the owner acquiring an Android device. So
//! every scenario here is the strongest thing provable *without one*: two
//! devices with separate vaults, separate device-local identities and
//! separate listener threads, reaching each other over real TCP sockets
//! on this machine, over real Noise sessions, with real mDNS on this
//! machine's own interfaces. Each test says in its own name and comments
//! which part is evidence and which part is the loopback approximation of
//! something a radio would have to confirm.
//!
//! What that *does* prove is everything about the code: the fallback
//! chain, the halt wording, the concurrency, the lazy bind, the port
//! stability. What it cannot prove is the physics — that two machines on
//! two radios find each other through a particular access point. That is
//! the hardware round, and it is labelled as waiting rather than assumed
//! away.

mod paired;

use gilune_sync::discovery::{
    Advertisement, Discovered, MdnsDiscovery, NoDiscovery, PeerDiscovery, DEFAULT_BROWSE,
};
use gilune_sync::service::SyncService;
use gilune_sync::{SyncError, PROTOCOL};
use paired::{copy_tree, Device};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// A fingerprint no other device on this developer's LAN could be showing.
/// Discovery tests run against the machine's *real* multicast, so a test
/// that matched on service type alone would be at the mercy of whatever
/// else is on the network — including another copy of this suite.
fn unique_fingerprint(tag: &str) -> String {
    format!(
        "test-{tag}-{}-{}",
        std::process::id(),
        gilune_kernel::ids::now_ms()
    )
}

// ------------------------------------------------------- the measurements

/// **Scheduled measurement 1** (research note § 1.1, marked UNVERIFIED at
/// design time): *does `mdns-sd` coexist with the Windows mDNS responder
/// on UDP 5353?*
///
/// Windows 10+ ships its own responder bound to that port. The crate's
/// own compatibility testing names Avahi, macOS and Bonjour — not
/// Windows — and the ADR called this "a measurement, not a blocker",
/// because § 1's fallback chain makes a negative result survivable.
///
/// The answer is whatever this test records. It advertises a service with
/// a fingerprint nothing else could be showing and browses for it from a
/// second daemon in the same process, which is the same question a second
/// machine would ask.
#[test]
fn mdns_coexists_with_whatever_else_holds_udp_5353_on_this_machine() {
    let fingerprint = unique_fingerprint("coexist");
    let responder = MdnsDiscovery::new();
    let advertised = responder.advertise(&Advertisement {
        fingerprint: fingerprint.clone(),
        port: 51837,
    });
    assert!(
        advertised.is_ok(),
        "binding a second responder alongside the platform's own failed: {advertised:?} \
         — if this ever fails on a supported OS, § 1's cached-address and manual-entry \
         fallbacks are what the product falls back to, and the settings screen has to \
         lead with them"
    );

    let browser = MdnsDiscovery::new();
    let found = browse_until(&browser, &fingerprint, Duration::from_secs(10));
    responder.withdraw();

    let seen = found.expect(
        "a service advertised by one daemon on this machine was not seen by another \
         within ten seconds — that is the coexistence failure the research note said to \
         measure",
    );
    assert_eq!(seen.protocol, PROTOCOL, "the TXT record carries the version");
    assert_eq!(seen.fingerprint, fingerprint);
    assert!(seen.compatible());
    assert!(
        seen.address.ends_with(":51837"),
        "the port in the record is the port the listener actually took, not the \
         preferred one: {}",
        seen.address
    );
}

/// The advertisement carries **no personal data** (§ 1). Not a review
/// note: this is the privacy property the ADR chose the instance-naming
/// scheme for, so it is asserted against what actually goes on the wire.
#[test]
fn what_goes_on_the_wire_names_no_person_and_no_device() {
    let fingerprint = unique_fingerprint("privacy");
    let responder = MdnsDiscovery::new();
    responder
        .advertise(&Advertisement { fingerprint: fingerprint.clone(), port: 51837 })
        .expect("advertise");

    let browser = MdnsDiscovery::new();
    let seen = browse_until(&browser, &fingerprint, Duration::from_secs(10));
    responder.withdraw();
    let seen = seen.expect("the advertisement");

    // Three fields reach an observer: the fingerprint, the protocol and
    // an address. A human device name is not among them — it is
    // exchanged inside the encrypted handshake and this is the assertion
    // that keeps it there.
    assert_eq!(seen.fingerprint, fingerprint);
    assert_eq!(seen.protocol, PROTOCOL);
    for personal in ["Desktop", "Phone", "David", "laptop"] {
        assert!(
            !seen.address.contains(personal),
            "an address should not carry a person's device name"
        );
    }
}

/// **Scheduled measurement 2**, gate point 16: the standing budget is
/// *"LAN sync of 100 small operations between two idle devices: under
/// 5 s"* (`architecture/spikes/README.md:37`), and *idle* puts discovery
/// and the handshake inside the five seconds.
///
/// **This is the loopback approximation and it is labelled as one.** Two
/// OS-level sockets on one machine carry no radio, no access point and no
/// contention, so the number below is a floor: it says what the software
/// costs, not what a network costs. The real-network figure waits for
/// hardware. What it does decide is the question the ADR asked it to —
/// whether the cached-address-first rule of § 1 is an optimisation or a
/// requirement — because a cold browse's own cost is measured here beside
/// the exchange's.
#[test]
fn one_hundred_operations_between_two_idle_devices_inside_the_budget() {
    let desktop = Device::new("Desktop").listening();
    let phone = Device::new("Phone").listening();
    phone.pair_by_code(&desktop).expect("pair");

    for n in 0..100 {
        desktop.capture(&format!("thought {n}"));
    }

    // Idle: a fresh session, from a cached address, exactly as the
    // phone's foreground sync does it.
    let started = Instant::now();
    let report = phone.sync();
    let exchange = started.elapsed();

    assert!(report.halted.is_empty(), "{:?}", report.halted);
    assert!(report.applied >= 100, "applied {}", report.applied);

    // And what a cold browse costs on its own, since the budget has to
    // hold with discovery inside it.
    let browser = MdnsDiscovery::new();
    let browse_started = Instant::now();
    let _ = browser.browse(DEFAULT_BROWSE);
    let browse = browse_started.elapsed();

    println!(
        "MEASUREMENT (loopback approximation, not a network figure): \
         100 operations exchanged in {exchange:?}; a cold browse costs {browse:?}; \
         together {:?} against a 5 s budget",
        exchange + browse
    );
    assert!(
        exchange + browse < Duration::from_secs(5),
        "100 operations took {exchange:?} and a cold browse {browse:?} — over the \
         5 s budget before a radio has been involved at all"
    );
    // The decision the ADR asked this measurement to make.
    assert!(
        browse >= exchange,
        "a cold browse cost {browse:?} against an exchange's {exchange:?}: discovery is \
         the expensive half, which is what makes § 1's cached-address-first rule a \
         requirement rather than an optimisation"
    );
}

// -------------------------------------------------------------- point 13

/// § 2: the listener and the responder **bind only when the user turns
/// sync on**, never at startup. A user who never enables sync never sees
/// the Windows prompt, and capture is never behind it.
#[test]
fn nothing_binds_until_sync_is_switched_on() {
    let device = Device::new("Desktop");

    assert!(!device.service.enabled());
    assert_eq!(device.service.listening_on(), None, "no socket before the switch");
    assert!(
        !device.service.advertising(),
        "and no responder either — mDNS is a second inbound bind, not an escape from \
         the first"
    );

    // Capture works with sync off, which is the property the lazy bind
    // exists to protect: preservation is never behind a firewall dialog.
    device.capture("a thought, with sync switched off");
    assert_eq!(device.store.status().expect("status").sources, 1);

    let address = device.service.set_enabled(true).expect("enable").expect("an address");
    assert!(device.service.enabled());
    assert_eq!(device.service.listening_on().as_deref(), Some(address.as_str()));

    device.service.set_enabled(false).expect("disable");
    assert_eq!(device.service.listening_on(), None, "and it closes again");
    assert!(!device.service.advertising());
}

/// § 2 again, from the other side: what the product binds is **routable**,
/// which is the whole reason the Windows Security Alert appears at all.
/// M5b bound the loopback deliberately; a regression to it would be
/// invisible — every test in this suite would still pass, and no device
/// on the network could reach this one.
#[test]
fn the_shipped_bind_is_routable_rather_than_the_loopback() {
    assert_eq!(
        gilune_sync::service::DEFAULT_BIND_HOST,
        "0.0.0.0",
        "binding the loopback would make sync work perfectly in every test and reach no \
         device at all"
    );
}

/// The routable bind, actually performed. **Ignored by default and that
/// is the point**: this is the test that raises the Windows Security
/// Alert, which is exactly the behaviour § 2 specifies and exactly what
/// an unattended suite must not do. Run it deliberately:
///
/// ```text
/// cargo test -p gilune-sync --test gate_c -- --ignored the_routable
/// ```
///
/// Gate point 13 asks that the prompt appear when sync is switched on and
/// **not before**. The first half is what this test causes; the second
/// half is `nothing_binds_until_sync_is_switched_on`, which needs no
/// prompt to prove because there is nothing to prompt about.
#[test]
#[ignore = "binds a routable interface, which raises the Windows firewall prompt"]
fn the_routable_bind_and_the_responder_both_come_up_together() {
    let device = Device::new("Desktop")
        .on_the_network()
        .discovering(Arc::new(MdnsDiscovery::new()));

    assert!(!device.service.advertising(), "nothing before the switch");
    let address = device.service.set_enabled(true).expect("enable").expect("address");
    assert!(
        address.starts_with("0.0.0.0:"),
        "the listener took every interface: {address}"
    );
    assert!(
        device.service.advertising(),
        "the responder comes up with the listener — if this fails on a supported OS, a \
         device is reachable but not findable, which is the state § 1's fallback chain \
         exists for and which the settings screen has to say out loud"
    );

    device.service.set_enabled(false).expect("disable");
    assert!(!device.service.advertising(), "and both go down together");
}

/// Switching sync on is **remembered across a restart**, and switching it
/// off is remembered too.
///
/// Found by the Android half: the gate on "sync when the app opens" read
/// a flag that was process-local, so a freshly launched phone always
/// believed sync was off and never synced until somebody re-toggled the
/// switch. The choice belongs beside the identity and the roster — it is
/// a fact about this machine, not about the vault (§ 4's reasoning
/// applied to one more file).
#[test]
fn switching_sync_on_is_remembered_when_the_app_starts_again() {
    let vault = tempfile::tempdir().expect("vault");
    let local = tempfile::tempdir().expect("local");

    // A device nobody has switched on yet is off, and § 2 holds: nothing
    // binds, so no firewall prompt and no port.
    {
        let device = Device::open("Desktop", vault.path(), local.path());
        assert!(!device.service.wanted(), "a fresh device has never opted in");
        assert_eq!(device.service.resume().expect("resume"), None);
        assert_eq!(device.service.listening_on(), None, "resume must not bind an opt-out");
    }

    // The user switches it on.
    let chosen = {
        let device = Device::open("Desktop", vault.path(), local.path());
        let address = device.service.set_enabled(true).expect("enable").expect("address");
        assert!(device.service.wanted());
        address
    };

    // The app starts again. It comes back reachable, at the same address,
    // without anybody touching the switch.
    {
        let device = Device::open("Desktop", vault.path(), local.path());
        assert!(device.service.wanted(), "the choice survived the restart");
        assert!(!device.service.enabled(), "and still nothing has bound yet");
        let address = device.service.resume().expect("resume").expect("an address");
        assert_eq!(address, chosen, "and it came back where its peers expect it");
        assert!(device.service.enabled());

        // Switching off is remembered just as firmly.
        device.service.set_enabled(false).expect("disable");
        assert!(!device.service.wanted());
    }
    {
        let device = Device::open("Desktop", vault.path(), local.path());
        assert_eq!(
            device.service.resume().expect("resume"),
            None,
            "a device switched off stays off across a restart"
        );
    }
}

/// The persisted choice is **device-local**, not vault state. A vault
/// copied to another machine must not switch sync on there: that is § 4's
/// reasoning about identity applied to the one other thing that describes
/// a machine rather than a notebook.
#[test]
fn a_copied_vault_does_not_carry_sync_being_switched_on() {
    let vault = tempfile::tempdir().expect("vault");
    let local = tempfile::tempdir().expect("local");
    {
        let device = Device::open("Desktop", vault.path(), local.path());
        device.service.set_enabled(true).expect("enable");
    }

    // The vault travels; the device-local directory does not, because
    // that is what device-local means.
    let elsewhere = tempfile::tempdir().expect("elsewhere");
    let other_local = tempfile::tempdir().expect("other local");
    copy_tree(vault.path(), elsewhere.path());

    let arrived = Device::open("Laptop", elsewhere.path(), other_local.path());
    assert!(
        !arrived.service.wanted(),
        "a copied vault must not open a port on the machine it arrived at"
    );
    assert_eq!(arrived.service.resume().expect("resume"), None);
}

/// The port is **stable across a restart** (§ 1: "a fixed default port in
/// the dynamic range; if that port is taken it takes an ephemeral one, and
/// the actual port is always published"). This is M5b's
/// restarted-device-unreachable gap: a device that came back on a new
/// ephemeral port was no longer at the address its peer had cached, and
/// with no discovery there was nothing to do about it.
#[test]
fn a_device_that_restarts_comes_back_on_the_same_port() {
    let vault = tempfile::tempdir().expect("vault");
    let local = tempfile::tempdir().expect("local");

    let first = {
        let device = Device::open("Desktop", vault.path(), local.path());
        device.service.set_enabled(true).expect("enable").expect("address")
    };
    // The device is dropped here: its listener stops and its socket
    // closes, which is what a process ending does.

    let device = Device::open("Desktop", vault.path(), local.path());
    let second = device.service.set_enabled(true).expect("enable").expect("address");

    assert_eq!(
        first, second,
        "a restarted device took a different port, so every peer's cached address for \
         it is stale — which is the M5b gap this is here to keep shut"
    );
}

/// And the port a fresh install prefers is the **documented default**,
/// not whatever happened to be free. Asserted against a service built
/// without the suite's own per-device port, because that is the shipped
/// behaviour and the harness deliberately overrides it.
#[test]
fn a_fresh_device_prefers_the_documented_default_port() {
    let vault = tempfile::tempdir().expect("vault");
    let local = tempfile::tempdir().expect("local");
    let store = gilune_kernel::store::Store::open(vault.path()).expect("open");
    let service = SyncService::configure(
        &store,
        local.path(),
        "Desktop",
        None,
        gilune_kernel::limits::Limits::DEFAULT,
    )
    .expect("configure");
    service.bind_host("127.0.0.1");

    let address = service.set_enabled(true).expect("enable").expect("address");
    assert!(
        address.ends_with(&format!(":{}", SyncService::DEFAULT_PORT)),
        "a fresh device should take the documented default so a peer's cached address \
         survives a restart: {address}"
    );
    assert_eq!(SyncService::DEFAULT_PORT, 51837, "the wire contract's number");
}

/// When the preferred port *is* taken, the fallback is an ephemeral one
/// and **the actual port is what is published** — never the preferred one.
/// Two Gilunes on one machine is the case, and the M5b listener had no
/// way to tell a peer which port it really took.
#[test]
fn a_taken_port_falls_to_an_ephemeral_one_and_the_real_one_is_published() {
    let wanted = free_port();
    let first = Device::new("Desktop").preferring(wanted).listening();
    let second = Device::new("Laptop").preferring(wanted).listening();

    let one = first.address();
    let two = second.address();
    assert_ne!(one, two, "two devices on one machine cannot share a port");
    assert!(one.ends_with(&format!(":{wanted}")), "the first took what it asked for: {one}");
    assert!(!two.ends_with(&format!(":{wanted}")), "the second could not: {two}");

    // And the second is reachable at what it published, which is the
    // whole reason publishing the actual port matters: it dialled the
    // first, so the first only ever saw a source port.
    let paired = second.pair_by_code(&first).expect("pair");
    assert_eq!(paired.state, "paired");
    let back = first.peer(&second).expect("the listener knows the joiner");
    let real_port = &two[two.rfind(':').expect("port")..];
    assert!(
        back.address.ends_with(real_port),
        "the listening side recorded the joiner's real listening port ({two}) rather \
         than the source port of the connection it arrived on ({})",
        back.address
    );

    // Which means the first can now dial the second, unprompted — the
    // direction that did not exist before the greeting carried a port.
    second.capture("written on the laptop");
    let report = first.sync();
    assert!(report.halted.is_empty(), "{:?}", report.halted);
    assert!(report.applied >= 1, "the desktop reached the laptop at its published port");
}

/// A session refreshes the cached address, so a peer that **moved port**
/// between sessions is found again the next time without anyone typing
/// anything. This is the other half of the restart gap: the port is
/// stable when it can be, and recovered when it cannot.
#[test]
fn a_peer_that_moves_port_is_relearned_from_its_next_session() {
    let desktop = Device::new("Desktop").listening();
    let phone_vault = tempfile::tempdir().expect("vault");
    let phone_local = tempfile::tempdir().expect("local");

    let stale = {
        let phone = Device::open("Phone", phone_vault.path(), phone_local.path());
        phone.service.set_enabled(true).expect("enable");
        phone.pair_by_code(&desktop).expect("pair");
        desktop.peer_by_key(&phone.service.identity_public_hex()).expect("known").address
    };

    // The phone comes back somewhere else — a port collision after a
    // restart, or a second Gilune that got there first.
    let phone = Device::open("Phone", phone_vault.path(), phone_local.path());
    phone.service.prefer_port(free_port());
    let moved = phone.service.set_enabled(true).expect("enable").expect("address");
    assert_ne!(stale, moved, "the phone is somewhere the desktop has not been told about");

    // The phone syncs — it dials, so it needs no help — and that session
    // is what teaches the desktop where the phone now is.
    phone.capture("written after the phone moved");
    let report = phone.sync();
    assert!(report.halted.is_empty(), "{:?}", report.halted);

    let relearned = desktop
        .peer_by_key(&phone.service.identity_public_hex())
        .expect("known")
        .address;
    let moved_port = &moved[moved.rfind(':').expect("port")..];
    assert!(
        relearned.ends_with(moved_port),
        "the desktop still believes the phone is at {relearned}, so the next thing it \
         tries to send goes nowhere"
    );

    // Proof, not inference: the desktop now reaches the phone unprompted.
    desktop.capture("written on the desktop afterwards");
    let back = desktop.sync();
    assert!(back.halted.is_empty(), "{:?}", back.halted);
    assert!(back.sent >= 1);
}

/// A free port, taken and released. Racy by nature and that is
/// acceptable: the product falls to an ephemeral port when its choice is
/// taken, so the worst a lost race costs is one assertion, and these
/// tests are the ones that would say so loudly.
fn free_port() -> u16 {
    std::net::TcpListener::bind("127.0.0.1:0")
        .expect("a free port")
        .local_addr()
        .expect("its address")
        .port()
}

// -------------------------------------------------------------- helpers

/// Browse repeatedly until `fingerprint` appears or `budget` runs out.
///
/// Repeating is not padding: a browse is a question asked into a network,
/// and the first one can land before the responder's announcement has
/// propagated. What the test asserts is that the device becomes findable,
/// not that it is findable within one particular packet's timing.
fn browse_until(
    discovery: &dyn PeerDiscovery,
    fingerprint: &str,
    budget: Duration,
) -> Option<Discovered> {
    let deadline = Instant::now() + budget;
    while Instant::now() < deadline {
        let found = discovery.browse(DEFAULT_BROWSE).unwrap_or_default();
        if let Some(hit) = found.into_iter().find(|d| d.fingerprint == fingerprint) {
            return Some(hit);
        }
    }
    None
}

/// A discovery adapter a test drives by hand: it answers with whatever it
/// was told to answer with, so the *chain* in `service::reach` can be
/// exercised without waiting on a network that might be having a bad day.
/// The mDNS implementation is measured above; this one is about the
/// decisions taken around it.
#[derive(Default)]
pub struct ScriptedDiscovery {
    pub answers: std::sync::Mutex<Vec<Discovered>>,
    pub browses: std::sync::atomic::AtomicUsize,
    pub advertised: std::sync::Mutex<Option<Advertisement>>,
}

impl PeerDiscovery for ScriptedDiscovery {
    fn name(&self) -> String {
        "scripted".to_string()
    }
    fn advertise(&self, ad: &Advertisement) -> gilune_sync::Result<()> {
        *self.advertised.lock().expect("lock") = Some(ad.clone());
        Ok(())
    }
    fn withdraw(&self) {
        *self.advertised.lock().expect("lock") = None;
    }
    fn browse(&self, _timeout: Duration) -> gilune_sync::Result<Vec<Discovered>> {
        self.browses.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        Ok(self.answers.lock().expect("lock").clone())
    }
}

impl ScriptedDiscovery {
    fn answering(devices: Vec<Discovered>) -> Arc<ScriptedDiscovery> {
        Arc::new(ScriptedDiscovery {
            answers: std::sync::Mutex::new(devices),
            ..Default::default()
        })
    }

    fn browsed(&self) -> usize {
        self.browses.load(std::sync::atomic::Ordering::SeqCst)
    }
}

/// What a device would advertise if it were advertising: its own
/// fingerprint and the port it actually took.
fn advertisement_of(device: &Device) -> Discovered {
    Discovered {
        // The compact spelling, which is what a DNS-SD instance name and
        // a TXT record carry — never the readable dashed one a settings
        // screen shows.
        fingerprint: gilune_sync::identity::fingerprint_compact(
            &hex::decode(device.service.identity_public_hex()).expect("a hex key"),
        ),
        protocol: PROTOCOL.to_string(),
        address: device.address(),
    }
}

// -------------------------------------------------------------- point 11

/// **The ADR 0008 six**, over real sockets between two devices with
/// nothing in common but a network: discover, pair, sync, converge, with
/// **no relay present**; an unpaired device refused; a revoked device
/// excluded; and sync resuming when devices rejoin.
///
/// Honest label: *discover* here runs through a scripted adapter rather
/// than through this machine's multicast. That is deliberate and it is
/// not a weakening — `mdns_coexists_with_whatever_else_holds_udp_5353`
/// above proves the real responder and the real browser on real
/// multicast, and this proves what the **service** does with what a
/// browse returns. Between them the whole path is covered; running both
/// halves in one test would make a scenario about revocation depend on
/// the developer's Wi-Fi.
#[test]
fn two_devices_discover_pair_sync_and_converge_with_no_relay() {
    let desktop = Device::new("Desktop").listening().with_processor();
    let phone = Device::new("Phone").listening().with_processor();

    // Discover. The phone has never been told where the desktop is.
    let discovery = ScriptedDiscovery::answering(vec![advertisement_of(&desktop)]);
    let phone = phone.discovering(discovery.clone());
    let seen = phone.service.discover(Duration::from_millis(50)).expect("browse");
    assert_eq!(seen.len(), 1, "the desktop, and not the phone itself");
    assert_eq!(seen[0].state, "unknown", "a stranger, which is what makes it offerable");
    assert!(seen[0].compatible);
    assert_eq!(seen[0].address, desktop.address());

    // Pair, at the address discovery produced.
    let offer = desktop.service.start_pairing().expect("a window");
    phone
        .service
        .pair_with_code(&phone.store, &seen[0].address, &offer.code)
        .expect("pair at the discovered address");

    // A browse now recognises it, which is what "paired" has to mean on
    // a screen that also shows strangers.
    let again = phone.service.discover(Duration::from_millis(50)).expect("browse");
    assert_eq!(again[0].state, "paired");
    assert_eq!(again[0].static_pub, desktop.service.identity_public_hex());

    // Sync, and converge. No relay: the only thing between these two
    // devices is a socket.
    // A recording each, so the exchange carries blobs and processing
    // rather than only text: a typed note has nothing to transcribe, and
    // a convergence that never moved bytes would be the easy half.
    desktop.store.capture_bytes("wav", &[3u8; 4096]).expect("capture");
    phone.store.capture_bytes("wav", &[4u8; 4096]).expect("capture");
    assert_eq!(desktop.work(), 1, "each device transcribes its own");
    assert_eq!(phone.work(), 1);
    let report = phone.sync();
    assert!(report.halted.is_empty(), "{:?}", report.halted);
    desktop.sync();
    phone.sync();
    assert_eq!(
        phone.digest(),
        desktop.digest(),
        "two replicas that exchanged over a socket must agree about everything"
    );

    // An unpaired device is refused. It holds no pair secret, so it
    // cannot complete a handshake at all — default-deny is a property of
    // the handshake, not a check after one.
    let stranger = Device::new("Someone else").listening();
    let refused = stranger
        .service
        .dial_peer(&desktop.service.identity_public_hex())
        // A `Session` has no `Debug` and should not grow one for a test:
        // reducing the success case to a word is enough to fail loudly.
        .map(|_| "a session");
    assert!(
        matches!(refused, Err(SyncError::Refused { .. })),
        "an unpaired device got somewhere: {refused:?}"
    );

    // A revoked device is excluded — and stays excluded whoever carries
    // its work, because refusal is by originating device on apply.
    desktop
        .service
        .revoke_peer(&desktop.store, &phone.service.identity_public_hex())
        .expect("revoke");
    phone.capture("written after the phone was revoked");
    phone.work();
    let after = phone.sync();
    assert!(
        !after.halted.is_empty(),
        "a revoked device should not be able to push anything: {after:?}"
    );

    // Rejoining. Re-pairing is a new pairing, not a restoration, and
    // sync resumes from the frontier rather than from the beginning.
    let offer = desktop.service.start_pairing().expect("a window");
    phone
        .service
        .pair_with_code(&phone.store, &offer.address, &offer.code)
        .expect("re-pair");
    let resumed = phone.sync();
    assert!(resumed.halted.is_empty(), "{:?}", resumed.halted);
    desktop.sync();
    phone.sync();
    assert_eq!(
        phone.digest(),
        desktop.digest(),
        "sync resumed correctly when the devices rejoined"
    );
    assert!(discovery.browsed() >= 2, "discovery was actually consulted");
}

/// **Point 15, the P0 loop across two devices**: a capture made on one
/// device is transcribed on that device, syncs to the other, and projects
/// **byte-identically**.
///
/// Honest label: two processes on one machine, so the transcription is
/// the deterministic stand-in processor rather than whisper, and the
/// radio is a loopback socket. What it proves is the whole chain —
/// capture, work, operation, transfer, blob, apply, project — and that
/// the file the second device writes is the same bytes as the first's.
/// The real-device version waits for hardware.
#[test]
fn a_capture_transcribed_on_one_device_projects_byte_identically_on_the_other() {
    let phone = Device::new("Phone").listening().with_processor();
    let desktop = Device::new("Desktop").listening().with_processor();
    phone.pair_by_code(&desktop).expect("pair");

    // Capture on the phone, and let the phone do its own work — origin
    // device processing, which is what keeps a recording's words from
    // being computed twice.
    phone.store.capture_bytes("wav", &[9u8; 8192]).expect("capture");
    assert_eq!(phone.work(), 1, "the device that captured it transcribes it");
    phone.store.project().expect("project");

    let report = desktop.sync();
    assert!(report.halted.is_empty(), "{:?}", report.halted);
    assert_eq!(report.blobs_completed, 1, "the audio came with it");
    assert_eq!(
        desktop.store.status().expect("status").blobs_awaiting,
        0,
        "and nothing is still in transit"
    );
    desktop.store.project().expect("project");

    // Byte-identical, file for file. Not "the same note" — the same
    // bytes, which is what a Markdown vault being the user-visible store
    // makes a testable claim.
    let mine = vault_files(&phone.vault_path);
    let theirs = vault_files(&desktop.vault_path);
    assert!(!mine.is_empty(), "the phone projected something");
    assert_eq!(
        mine, theirs,
        "the note and its audio must land on the second device byte for byte"
    );
    assert_eq!(phone.digest(), desktop.digest());
}

/// Every Markdown note and attachment in a vault, as `(relative path,
/// bytes)`. Deliberately not a digest: when this fails, the assertion
/// should be able to say *which* file differs.
fn vault_files(root: &std::path::Path) -> std::collections::BTreeMap<String, Vec<u8>> {
    let mut files = std::collections::BTreeMap::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            // The kernel's own database is device-local state, not vault
            // content, and two devices are not expected to have identical
            // ones — they have different identities.
            if path.file_name().is_some_and(|n| n.to_string_lossy().starts_with(".gilune")) {
                continue;
            }
            if path.is_dir() {
                stack.push(path);
            } else if let Ok(bytes) = std::fs::read(&path) {
                let rel = path
                    .strip_prefix(root)
                    .unwrap_or(&path)
                    .to_string_lossy()
                    .replace('\\', "/");
                files.insert(rel, bytes);
            }
        }
    }
    files
}

// -------------------------------------------------------------- point 12

/// § 1's chain, in order: **the cached address first, and discovery only
/// when it fails.** This is the rule the 5 s measurement above turned
/// from an optimisation into a requirement, so it is asserted rather than
/// assumed — by counting browses.
#[test]
fn a_cached_address_is_tried_first_and_discovery_only_when_it_fails() {
    let desktop = Device::new("Desktop").listening();
    let discovery = ScriptedDiscovery::answering(vec![advertisement_of(&desktop)]);
    let phone = Device::new("Phone").listening().discovering(discovery.clone());
    phone.pair_by_code(&desktop).expect("pair");

    desktop.capture("something to carry");
    let report = phone.sync();
    assert!(report.halted.is_empty(), "{:?}", report.halted);
    assert_eq!(
        discovery.browsed(),
        0,
        "a peer reachable at its cached address must never cost a browse — a cold browse \
         measured 2.5 s on this machine against an exchange's 1.2 s, which is the whole \
         argument for this ordering"
    );
}

/// And when the cached address **has** gone stale, discovery is what
/// recovers it — without anybody typing anything.
#[test]
fn discovery_recovers_a_peer_whose_cached_address_went_stale() {
    let desktop = Device::new("Desktop").listening();
    let discovery = ScriptedDiscovery::answering(vec![advertisement_of(&desktop)]);
    let phone = Device::new("Phone").listening().discovering(discovery.clone());
    phone.pair_by_code(&desktop).expect("pair");

    // The desktop moved and nobody told the phone. A closed port is the
    // honest shape of that: the address resolves and answers nothing.
    let stale = format!("127.0.0.1:{}", free_port());
    phone
        .service
        .set_peer_address(&desktop.service.identity_public_hex(), &stale)
        .expect("stale it");

    desktop.capture("written while the phone had the wrong address");
    let report = phone.sync();
    assert!(
        report.halted.is_empty(),
        "discovery should have found the desktop after the cached address failed: {:?}",
        report.halted
    );
    assert!(report.applied >= 1);
    assert!(discovery.browsed() >= 1, "and it did have to look");

    // The address discovery produced is now the cached one, so the
    // expensive step is paid once.
    let cached = phone
        .peer_by_key(&desktop.service.identity_public_hex())
        .expect("known")
        .address;
    assert_eq!(cached, desktop.address(), "what worked becomes what is tried first");
    let browses = discovery.browsed();
    phone.sync();
    assert_eq!(discovery.browsed(), browses, "and the next session costs no browse at all");
}

/// The third step is **manual entry**, and it has to work on a network
/// where discovery finds nothing at all.
#[test]
fn a_typed_address_reaches_a_peer_that_nothing_could_find() {
    let desktop = Device::new("Desktop").listening();
    // A network that filters multicast: browsing works and returns
    // nothing, which is the shape of AP client isolation.
    let blind = ScriptedDiscovery::answering(Vec::new());
    let phone = Device::new("Phone").listening().discovering(blind.clone());
    phone.pair_by_code(&desktop).expect("pair");

    let stale = format!("127.0.0.1:{}", free_port());
    let key = desktop.service.identity_public_hex();
    phone.service.set_peer_address(&key, &stale).expect("stale it");

    desktop.capture("written before anybody typed an address");
    let failed = phone.sync();
    assert_eq!(failed.applied, 0);
    assert_eq!(failed.halted.len(), 1);

    // A person reads the address off the desktop's settings screen and
    // types it. That is the whole of step three.
    assert!(phone.service.set_peer_address(&key, &desktop.address()).expect("type it"));
    let report = phone.sync();
    assert!(report.halted.is_empty(), "{:?}", report.halted);
    assert!(report.applied >= 1);
    assert!(blind.browsed() >= 1, "it did look before giving up");
}

/// And the last step is **words, not a spinner** (§ 1): when nothing can
/// be reached and nothing can be found, the failure names AP client
/// isolation as the likely cause. Asserted on the wording itself, because
/// the wording *is* the deliverable — a person on a hotel network needs
/// to be told that waiting will not help.
#[test]
fn an_unreachable_peer_on_a_silent_network_names_what_is_probably_wrong() {
    let desktop = Device::new("Desktop").listening();
    let blind = ScriptedDiscovery::answering(Vec::new());
    let phone = Device::new("Phone").listening().discovering(blind);
    phone.pair_by_code(&desktop).expect("pair");
    phone
        .service
        .set_peer_address(
            &desktop.service.identity_public_hex(),
            &format!("127.0.0.1:{}", free_port()),
        )
        .expect("stale it");

    let report = phone.sync();
    let reason = report.halted.first().expect("a halt").reason.clone();
    assert!(
        reason.contains("devices talking to each other"),
        "the diagnostic must name client isolation rather than spin: {reason}"
    );
    assert!(
        reason.contains("no amount of waiting will help"),
        "and it must say that waiting is not the answer: {reason}"
    );
    assert!(
        reason.contains("firewall"),
        "and name the other likely cause on Windows: {reason}"
    );
    // Named by the device, not by a device id: what a shell shows has to
    // make sense to a person.
    assert_eq!(report.halted[0].device_id, "Desktop");

    // A network where other devices *are* visible is a different
    // diagnostic, because it is a different problem — and telling
    // somebody their network is at fault when it is not is worse than
    // saying nothing.
    let other = Device::new("Somebody's tablet").listening();
    let phone = Device::new("Phone2")
        .listening()
        .discovering(ScriptedDiscovery::answering(vec![advertisement_of(&other)]));
    phone.pair_by_code(&desktop).expect("pair");
    phone
        .service
        .set_peer_address(
            &desktop.service.identity_public_hex(),
            &format!("127.0.0.1:{}", free_port()),
        )
        .expect("stale it");
    let elsewhere = phone.sync();
    let reason = &elsewhere.halted.first().expect("a halt").reason;
    assert!(
        reason.contains("this network is not the problem"),
        "a network with other devices on it is not an isolated one: {reason}"
    );
}

/// A peer that **is** on the network but speaks a version this build
/// cannot is its own diagnostic. Telling somebody their laptop is asleep
/// when it is in front of them running a newer Gilune is the kind of
/// wrong answer that costs an evening.
#[test]
fn a_peer_running_an_incompatible_version_is_named_as_that_and_not_as_absent() {
    let desktop = Device::new("Desktop").listening();
    let phone = Device::new("Phone").listening();
    phone.pair_by_code(&desktop).expect("pair");

    // The desktop is visible, and unreachable for a reason that is not
    // the network's doing and not the desktop being off.
    let newer = Discovered {
        protocol: "gilune-sync/99".to_string(),
        ..advertisement_of(&desktop)
    };
    phone
        .service
        .set_discovery(ScriptedDiscovery::answering(vec![newer]));
    phone
        .service
        .set_peer_address(
            &desktop.service.identity_public_hex(),
            &format!("127.0.0.1:{}", free_port()),
        )
        .expect("stale it");

    let report = phone.sync();
    let reason = report.halted.first().expect("a halt").reason.clone();
    assert!(
        reason.contains("version of Gilune that this one cannot"),
        "a version mismatch must be named as one: {reason}"
    );
    assert!(
        !reason.contains("switched off") && !reason.contains("asleep"),
        "and must not blame the device for being off when it plainly is not: {reason}"
    );
    assert!(
        !reason.contains("devices talking to each other"),
        "nor blame the network, which is working perfectly: {reason}"
    );
}

/// A host that registered no adapter at all gets the third diagnostic:
/// nothing was found because nothing could look, which is a different
/// sentence from nothing being there.
#[test]
fn a_host_with_no_discovery_says_so_rather_than_blaming_the_network() {
    let desktop = Device::new("Desktop").listening();
    let phone = Device::new("Phone").listening();
    phone.service.set_discovery(Arc::new(NoDiscovery));
    phone.pair_by_code(&desktop).expect("pair");

    // Cached address first, and it works — no discovery needed.
    desktop.capture("something to carry");
    let report = phone.sync();
    assert!(report.halted.is_empty(), "{:?}", report.halted);
    assert!(report.applied >= 1);

    // And when it stops working, the diagnostic is honest about why
    // nothing was found.
    phone
        .service
        .set_peer_address(
            &desktop.service.identity_public_hex(),
            &format!("127.0.0.1:{}", free_port()),
        )
        .expect("stale it");
    let blind = phone.sync();
    let reason = &blind.halted.first().expect("a halt").reason;
    assert!(
        reason.contains("no way to look for it"),
        "nothing could look is not the same fact as nothing was there: {reason}"
    );
    assert!(reason.contains("enter it here"), "and it offers the route that works: {reason}");
}

/// Switching sync off must be **immediate**, even with a peer mid-session.
///
/// The obvious implementation — stop the listener, wait for the session
/// threads — freezes for as long as the quietest peer takes, which is the
/// session timeout. So a live session is ended the way a revocation ends
/// one: a flag consulted at the next operation boundary. This test is
/// about the *latency*, which is the property that would otherwise
/// regress silently.
#[test]
fn switching_sync_off_does_not_wait_for_a_peer_that_has_gone_quiet() {
    let desktop = Device::new("Desktop").listening();
    let phone = Device::new("Phone").listening();
    phone.pair_by_code(&desktop).expect("pair");

    // A session the phone opens and then simply stops using — the peer
    // that wandered out of range, which is what the timeout is for.
    let mut idle = phone.dial(&desktop).expect("a session");
    idle.devices().expect("one request, then silence");

    let started = Instant::now();
    desktop.service.set_enabled(false).expect("switch off");
    let took = started.elapsed();

    assert_eq!(desktop.service.listening_on(), None);
    assert!(
        took < Duration::from_secs(5),
        "switching sync off took {took:?} — it waited for a session rather than ending \
         it, and a settings screen cannot freeze for a peer that stopped talking"
    );
    let _ = idle.close();
}

// ---------------------------------------------------- the concurrent listener

/// M5b served **one peer at a time** and said so. A mesh cannot: a phone
/// in the middle of a long first synchronisation would make every other
/// device's Sync-now wait behind it, and on a desktop that is an
/// always-available listener the wait is unbounded.
#[test]
fn one_device_serves_several_peers_at_once() {
    let desktop = Device::new("Desktop").listening();
    let peers: Vec<Device> = (0..4)
        .map(|n| {
            let device = Device::new(&format!("Phone {n}")).listening();
            device.pair_by_code(&desktop).expect("pair");
            device.capture(&format!("written on phone {n}"));
            device
        })
        .collect();
    let expected: Vec<(String, String)> = peers
        .iter()
        .map(|d| (d.name.clone(), d.device_id()))
        .collect();

    // All four at once, each on its own thread, all against the one
    // listener. With M5b's serial listener this comes back with halts:
    // three of the four are waiting on a socket the accept loop has not
    // reached. Each thread takes its device **by value** — a `Store`
    // holds a `rusqlite` connection, which is `Send` and deliberately not
    // `Sync`, so a device belongs to one thread at a time exactly as a
    // process would own it.
    let handles: Vec<_> = peers
        .into_iter()
        .map(|device| std::thread::spawn(move || (device.name.clone(), device.sync())))
        .collect();

    for handle in handles {
        let (name, report) = handle.join().expect("a sync");
        assert!(report.halted.is_empty(), "{name} was refused: {:?}", report.halted);
    }
    // And the desktop actually received all four, which is what
    // distinguishes "nobody errored" from "everybody was served".
    for (name, device_id) in expected {
        assert!(
            desktop.holds_from(&device_id) > 0,
            "the desktop is missing {name}'s work"
        );
    }
}
