//! Every address, across the boundary (ADR 0019 § 1, § 7).
//!
//! `gilune-sync` has known since the addressing round that a machine is
//! reachable at *several* addresses — a VPN, a hypervisor's host-only
//! adapter and a real Wi-Fi card are three answers to "what is my
//! address" and only one of them is the one the phone shares. The rank
//! picks a best guess; it cannot know. So the list is the fact and the
//! single line is a convenience derived from it.
//!
//! That list stopped at the boundary: `PairingOffer` and `SyncStatus`
//! each carried one line, so a settings screen could only ever show the
//! guess, and a person on a machine with a VPN read the wrong address off
//! the screen with nothing to try next. These tests are about the
//! **boundary**, not the ranking — which candidate wins is proven in
//! `gilune-sync`'s own addressing tests. What is proven here is that a
//! shell can see all of them, and that the one line stays the first of
//! the list rather than becoming a second, disagreeing derivation.
//!
//! Nothing here binds a socket: binding a routable interface is what
//! raises the Windows firewall prompt (§ 2), and that belongs in a
//! deliberate run, never an unattended one.

use gilune_ffi::{Gilune, PairingOffer, SyncStatus};

/// A machine with a VPN and a hypervisor: three addresses, ranked, and no
/// way for the device to know which one the peer can reach.
fn several() -> Vec<String> {
    vec![
        "192.168.1.24:51837".to_string(),
        "10.8.0.6:51837".to_string(),
        "172.17.0.1:51837".to_string(),
    ]
}

fn facts_with(addresses: Vec<String>) -> gilune_sync::service::SyncFacts {
    gilune_sync::service::SyncFacts {
        enabled: true,
        device_id: "device-a".to_string(),
        device_name: "Desktop".to_string(),
        fingerprint: "ABCD-EF01-2345".to_string(),
        identity_pub: "pub".to_string(),
        key_protection: "dpapi".to_string(),
        enrolment: "continued".to_string(),
        enrolment_notice: None,
        listening_on: addresses.first().cloned(),
        listening_addresses: addresses,
        wanted: true,
        advertising: true,
        discovery: "mdns-sd".to_string(),
        pairing: None,
        peers: Vec::new(),
    }
}

fn offer_with(addresses: Vec<String>) -> gilune_sync::service::PairingOffer {
    gilune_sync::service::PairingOffer {
        code: "402913".to_string(),
        address: addresses.first().cloned().unwrap_or_default(),
        addresses,
        fingerprint: "ABCD-EF01-2345".to_string(),
        expires_ms: 180_000,
        attempts_left: 5,
    }
}

#[test]
fn a_settings_screen_is_told_every_address_and_not_only_the_best_guess() {
    let status: SyncStatus = facts_with(several()).into();

    assert_eq!(
        status.listening_addresses,
        several(),
        "the whole ranked list crosses the boundary in order; a screen that \
         showed only the guess is why a person on a VPN read the wrong \
         address off it"
    );
    assert_eq!(
        status.listening_on.as_deref(),
        Some(several()[0].as_str()),
        "and the one line stays the first of the list rather than becoming \
         a second derivation that can disagree with it"
    );
}

#[test]
fn a_qr_and_a_typed_code_offer_the_same_addresses() {
    let offer: PairingOffer = offer_with(several()).into();

    assert_eq!(offer.addresses, several(), "the QR carries all of them");
    assert_eq!(
        offer.address,
        several()[0],
        "the line a person types is the first of the list"
    );
    assert_eq!(offer.code, "402913");
    assert_eq!(offer.attempts_left, 5);
}

#[test]
fn a_device_with_one_address_says_so_without_an_empty_second_answer() {
    let one = vec!["192.168.1.24:51837".to_string()];
    let status: SyncStatus = facts_with(one.clone()).into();

    assert_eq!(status.listening_addresses, one);
    assert_eq!(status.listening_on.as_deref(), Some(one[0].as_str()));
}

#[test]
fn a_device_that_is_not_listening_offers_no_address_at_all() {
    let status: SyncStatus = facts_with(Vec::new()).into();

    assert_eq!(status.listening_on, None);
    assert!(
        status.listening_addresses.is_empty(),
        "the two agree at the empty end too — a screen must not draw a \
         candidate list for a device that is not reachable"
    );
}

/// The same emptiness, through the shipped path rather than a constructed
/// record: a real handle with sync configured and never switched on.
/// Nothing binds, which is the § 2 property, and the address surface is
/// empty rather than guessing at interfaces it has not bound.
#[test]
fn a_real_handle_that_never_switched_sync_on_publishes_nothing() {
    let vault = tempfile::tempdir().expect("vault");
    let local = tempfile::tempdir().expect("device-local");
    let gilune = Gilune::open(vault.path().to_string_lossy().to_string()).expect("open");
    gilune
        .configure_sync(
            local.path().to_string_lossy().to_string(),
            "Desktop".to_string(),
            None,
        )
        .expect("configure sync");

    let status = gilune.sync_status().expect("status");
    assert!(!status.enabled);
    assert_eq!(status.listening_on, None);
    assert!(status.listening_addresses.is_empty());
}
