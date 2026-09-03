//! The shipped desktop path, end to end, on real network machinery
//! (ADR 0019 § 1, § 2).
//!
//! `gilune-sync`'s own gate C suite proves the *decisions* — the fallback
//! chain, the port stability, the diagnostics — over loopback sockets and
//! a scripted discovery adapter, so that a scenario about revocation does
//! not depend on the developer's Wi-Fi. This file proves the other half:
//! that the pieces the desktop actually ships **fit together on a real
//! machine**. Two `Gilune` handles, the routable bind the product uses,
//! `use_bundled_discovery`'s real `mdns-sd` responder and browser, and
//! this machine's own multicast.
//!
//! **Ignored by default, and that is the point.** It binds a routable
//! interface, which is what raises the Windows Security Alert (§ 2), and
//! it sends real multicast — neither belongs in an unattended run. Run it
//! deliberately:
//!
//! ```text
//! cargo test -p gilune-ffi --test live_discovery -- --ignored --nocapture
//! ```
//!
//! Honest label: this is still one machine. It proves the software; it
//! does not prove that two machines on two radios find each other through
//! a particular access point. That is gate C's real-device round and it
//! waits on hardware.

#![cfg(feature = "whisper")]

use gilune_ffi::Gilune;

struct Device {
    _vault: tempfile::TempDir,
    _local: tempfile::TempDir,
    gilune: std::sync::Arc<Gilune>,
}

/// A device set up exactly as `Main.kt` sets the desktop up: open the
/// vault, configure sync against a device-local directory outside it,
/// then register the bundled discovery. Nothing here binds a socket —
/// that waits for the switch, which is the property § 2 is about.
fn device(name: &str) -> Device {
    let vault = tempfile::tempdir().expect("vault");
    let local = tempfile::tempdir().expect("device-local");
    let gilune = Gilune::open(vault.path().to_string_lossy().to_string()).expect("open");
    gilune
        .configure_sync(
            local.path().to_string_lossy().to_string(),
            name.to_string(),
            None,
        )
        .expect("configure sync");
    gilune.use_bundled_discovery().expect("mdns-sd");
    Device { _vault: vault, _local: local, gilune }
}

#[test]
#[ignore = "binds a routable interface and sends real multicast"]
fn two_devices_find_each_other_over_this_machines_own_network() {
    let desktop = device("Desktop");
    let laptop = device("Laptop");

    // Before the switch: nothing bound, nothing advertised, and a browse
    // is still allowed to run and find nothing of ours.
    let before = desktop.gilune.sync_status().expect("status");
    assert!(!before.enabled);
    assert!(!before.advertising);
    assert_eq!(before.discovery, "mdns-sd", "the route is registered even so");
    assert_eq!(before.listening_on, None);

    // The switch. This is the moment the socket is bound and the
    // responder comes up, and the moment Windows would ask.
    let desktop_status = desktop.gilune.set_sync_enabled(true).expect("enable");
    let laptop_status = laptop.gilune.set_sync_enabled(true).expect("enable");
    let desktop_address = desktop_status.listening_on.clone().expect("an address");
    println!("desktop listening on {desktop_address}, advertising = {}", desktop_status.advertising);
    println!(
        "laptop listening on {}, advertising = {}",
        laptop_status.listening_on.clone().unwrap_or_default(),
        laptop_status.advertising
    );
    assert!(
        desktop_status.advertising && laptop_status.advertising,
        "the responder must come up with the listener on a supported OS; if it does not, \
         a device is reachable but not findable and the settings screen has to say so"
    );
    assert!(
        desktop_address.starts_with("0.0.0.0:"),
        "the shipped bind is routable: {desktop_address}"
    );

    // Each has to see the other, and neither may see itself.
    let seen = browse_for(&laptop, &desktop_status.fingerprint);
    let mine = seen.expect(
        "the laptop could not find the desktop over this machine's own multicast within \
         fifteen seconds — that is the coexistence question the research note scheduled, \
         and a negative answer makes § 1's cached-address and manual-entry fallbacks the \
         product's primary path rather than its fallback",
    );
    println!("laptop found the desktop at {} (state {})", mine.address, mine.state);
    assert!(mine.compatible);
    assert_eq!(mine.state, "unknown", "a stranger until they have paired");
    assert!(mine.static_pub.is_empty(), "and no key is known for a stranger");

    let everything = laptop.gilune.discover_devices(2500).expect("browse");
    assert!(
        everything.iter().all(|d| d.fingerprint != laptop_status.fingerprint.replace('-', "").to_lowercase()),
        "a device answers its own browse and must never offer itself to itself"
    );

    // And the address discovery produced is one that actually pairs — the
    // whole point of publishing the real port rather than the preferred
    // one.
    let offer = desktop.gilune.start_pairing().expect("a code");
    laptop
        .gilune
        .pair_with_code(mine.address.clone(), offer.code.clone())
        .expect("pair at the discovered address");

    // Now it is recognised rather than merely visible.
    let after = browse_for(&laptop, &desktop_status.fingerprint).expect("still there");
    assert_eq!(after.state, "paired");
    assert!(!after.static_pub.is_empty());

    // And it carries a note, which is the only proof that matters.
    desktop.gilune.capture_text("written on the desktop".to_string()).expect("capture");
    let report = laptop.gilune.sync_now().expect("sync");
    assert!(report.halted.is_empty(), "{:?}", report.halted);
    assert!(report.applied >= 1, "the note came across a discovered, paired connection");

    desktop.gilune.set_sync_enabled(false).expect("disable");
    laptop.gilune.set_sync_enabled(false).expect("disable");
    assert!(!desktop.gilune.sync_status().expect("status").advertising);
}

/// Browse until `fingerprint` shows up, or fifteen seconds pass.
/// Repeating is not padding: a browse is a question asked into a network,
/// and the first one can be sent before the other device's announcement
/// has propagated.
fn browse_for(from: &Device, fingerprint: &str) -> Option<uniffi_types::FoundDevice> {
    // The advertisement carries the compact spelling; a settings screen
    // shows the readable one. Comparing the two directly is the mistake
    // this normalisation exists to prevent.
    let wanted = fingerprint.replace('-', "").to_lowercase();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
    while std::time::Instant::now() < deadline {
        let found = from.gilune.discover_devices(2500).unwrap_or_default();
        if let Some(hit) = found.into_iter().find(|d| d.fingerprint == wanted) {
            return Some(hit);
        }
    }
    None
}

mod uniffi_types {
    pub use gilune_ffi::FoundDevice;
}
