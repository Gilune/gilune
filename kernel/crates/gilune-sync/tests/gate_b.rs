//! ADR 0019 gate B, points 5 to 10: **pairing is a real boundary**.
//!
//! Every scenario here runs between devices that share nothing but a
//! socket. Each has its own vault, its own kernel store, its own
//! device-local identity outside that vault, and a listener thread
//! holding a second kernel handle — the multi-instance shape the owner
//! sanctioned on 2026-08-31, and the closest a single `cargo test` gets
//! to two processes. A scenario that passes here passed across a Noise
//! session, because there is no other way for these devices to reach one
//! another.

mod paired;

use gilune_kernel::alerts;
use gilune_sync::rate::RateLimiter;
use gilune_sync::SyncError;
use paired::{copy_tree, Device};

// ------------------------------------------------------------------ point 5

#[test]
fn both_pairing_paths_produce_the_same_roster_state() {
    // The short code.
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    let by_code = joiner.pair_by_code(&listener).expect("pair by code");

    // And the QR, between a second pair of devices — the same exchange
    // with a longer password (§ 3), so the two must agree in every field
    // that describes the pairing rather than the machine.
    let other_listener = Device::new("Desktop").listening();
    let other_joiner = Device::new("Phone").listening();
    let by_qr = other_joiner.pair_by_qr(&other_listener).expect("pair by QR");

    assert_eq!(by_code.state, "paired");
    assert_eq!(by_qr.state, "paired");
    assert_eq!(by_code.name, by_qr.name, "both learn the listener's name");
    assert_eq!(by_code.name, "Desktop");
    assert_eq!(by_code.static_pub, listener.identity_pub());
    assert_eq!(by_qr.static_pub, other_listener.identity_pub());
    assert_eq!(by_code.device_id, listener.device_id());
    assert_eq!(by_qr.device_id, other_listener.device_id());
    assert_eq!(
        by_code.fingerprint.len(),
        by_qr.fingerprint.len(),
        "one fingerprint format, whichever path produced it"
    );

    // And both sides of each pairing know each other, which is what
    // "roster state" means: pairing is pairwise and both rosters are it.
    for (a, b) in [(&listener, &joiner), (&other_listener, &other_joiner)] {
        let theirs = a.peer(b).expect("the listener knows the joiner");
        let ours = b.peer(a).expect("the joiner knows the listener");
        assert_eq!(theirs.static_pub, b.identity_pub());
        assert_eq!(theirs.device_id, b.device_id());
        assert_eq!(theirs.name, b.name);
        assert_eq!(ours.state, "paired");
        assert_eq!(theirs.state, "paired");
    }
}

#[test]
fn a_pairing_by_either_path_carries_a_capture_between_the_two_devices() {
    for by_qr in [false, true] {
        let listener = Device::new("Desktop").listening().with_processor();
        let joiner = Device::new("Phone").listening().with_processor();
        if by_qr {
            joiner.pair_by_qr(&listener).expect("pair");
        } else {
            joiner.pair_by_code(&listener).expect("pair");
        }

        let _ = listener.store.capture_bytes("wav", &[5u8; 4096]);
        assert_eq!(listener.work(), 1, "the device that captured it does the work");

        let report = joiner.sync();
        assert!(report.halted.is_empty(), "{:?}", report.halted);
        assert_eq!(report.applied, 2, "the capture and the transcription revision");
        assert_eq!(report.blobs_completed, 1, "and the bytes that go with them");
        assert_eq!(
            listener.digest(),
            joiner.digest(),
            "two devices, one state — over an encrypted session this time"
        );
    }
}

// ------------------------------------------------------------------ point 6

#[test]
fn a_wrong_code_fails_and_leaks_nothing_about_the_right_one() {
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    let offer = listener.service.start_pairing().expect("window");

    let refused = joiner
        .service
        .pair_with_code(&joiner.store, &offer.address, "000000")
        .expect_err("a wrong code must not pair");

    let said = refused.to_string();
    assert!(
        !said.contains(&offer.code),
        "a failure that quotes the real code is an oracle: {said}"
    );
    assert!(
        !said.contains(&listener.identity_pub()) && !said.contains(&offer.fingerprint),
        "nor may it hand over the identity it was fishing for: {said}"
    );
    assert!(joiner.service.peers().is_empty(), "nothing is stored on a failure");
    assert!(listener.service.peers().is_empty());
}

#[test]
fn a_code_is_single_use() {
    let listener = Device::new("Desktop").listening();
    let first = Device::new("Phone").listening();
    let second = Device::new("Tablet").listening();
    let offer = listener.service.start_pairing().expect("window");

    first
        .service
        .pair_with_code(&first.store, &offer.address, &offer.code)
        .expect("the first device pairs");

    let refused = second
        .service
        .pair_with_code(&second.store, &offer.address, &offer.code)
        .expect_err("the same code must not pair a second device");
    assert!(matches!(refused, SyncError::Refused { .. }));
    assert_eq!(
        listener.service.peers().len(),
        1,
        "a used code cannot be replayed by somebody who watched it being typed"
    );
}

#[test]
fn a_code_expires() {
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    // A window that stands for a millisecond rather than three minutes.
    // The bound is configuration (§ 8), so this is the shipped code path
    // with a number named, not a special case.
    let offer = listener.service.start_pairing_with(1, 5).expect("window");
    std::thread::sleep(std::time::Duration::from_millis(20));

    let refused = joiner
        .service
        .pair_with_code(&joiner.store, &offer.address, &offer.code)
        .expect_err("an expired code must not pair");
    assert!(refused.to_string().contains("expired"), "{refused}");
    assert!(listener.service.peers().is_empty());
}

#[test]
fn the_attempt_limit_abandons_the_window_and_the_right_code_stops_working() {
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    let offer = listener.service.start_pairing_with(60_000, 3).expect("window");

    for attempt in 0..3 {
        let wrong = format!("{:06}", 100_000 + attempt);
        assert_ne!(wrong, offer.code, "the test's guesses must actually be wrong");
        assert!(joiner
            .service
            .pair_with_code(&joiner.store, &offer.address, &wrong)
            .is_err());
    }

    // The window is spent. Even the correct code is now worthless, which
    // is the whole point: "the user gets a new code, not an unbounded
    // oracle".
    let refused = joiner
        .service
        .pair_with_code(&joiner.store, &offer.address, &offer.code)
        .expect_err("the window is abandoned");
    assert!(refused.to_string().contains("new one"), "{refused}");
    assert!(listener.service.peers().is_empty());

    // And asking for a new window is what recovers, with a new secret.
    let fresh = listener.service.start_pairing().expect("a new window");
    assert_ne!(fresh.code, offer.code);
    joiner
        .service
        .pair_with_code(&joiner.store, &fresh.address, &fresh.code)
        .expect("a fresh code pairs");
}

#[test]
fn repeated_wrong_codes_are_rate_limited_with_backoff() {
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    // The shipped defaults, rather than the test harness's relaxed ones:
    // this scenario is about the bound itself (§ 8, per-peer limits on
    // establishment with backoff).
    joiner.service.configure_rate_limits(RateLimiter::default());
    let offer = listener.service.start_pairing_with(60_000, 100).expect("window");

    let mut refusals = Vec::new();
    for attempt in 0..6 {
        let wrong = format!("{:06}", 200_000 + attempt);
        let refused = joiner
            .service
            .pair_with_code(&joiner.store, &offer.address, &wrong)
            .expect_err("wrong");
        refusals.push(refused.to_string());
    }

    assert!(
        refusals.iter().any(|r| r.contains("too many attempts")),
        "a guesser must be slowed down, not merely told no: {refusals:?}"
    );
    assert!(
        refusals.last().expect("a refusal").contains("try again in"),
        "and told to wait rather than how close it is"
    );
    assert!(listener.service.peers().is_empty());
}

// ------------------------------------------------------------------ point 7

#[test]
fn an_unpaired_device_is_refused_and_never_learns_the_listeners_identity() {
    let listener = Device::new("Desktop").listening();
    // A listener that holds a real pairing, so it is not refusing merely
    // because it has nobody at all.
    let friend = Device::new("Phone").listening();
    friend.pair_by_code(&listener).expect("pair");

    let stranger = Device::new("Stranger").listening();
    // The stranger knows the address and even the identity it wants to
    // reach — it simply has no pair secret.
    let refused = gilune_sync::handshake::dial_session(
        gilune_sync::secure::dial(&listener.address()).expect("connect"),
        gilune_kernel::limits::Limits::DEFAULT,
        &gilune_sync::identity::mint_identity().expect("mint"),
        &gilune_sync::handshake::Greeting::new("stranger", "Stranger", ""),
        &listener.identity_pub(),
        [0u8; 32],
    );

    let said = match refused {
        Ok(_) => panic!("an unpaired device must be refused"),
        Err(e) => e.to_string(),
    };
    assert!(
        !said.contains(&listener.identity_pub()),
        "the refusal must not disclose the static key it was fishing for: {said}"
    );
    assert_eq!(
        listener.service.peers().len(),
        1,
        "and the stranger is not recorded as anything"
    );
    let _ = stranger;
}

#[test]
fn holding_a_peers_address_and_key_but_not_its_secret_gets_nowhere() {
    let listener = Device::new("Desktop").listening();
    let friend = Device::new("Phone").listening();
    friend.pair_by_code(&listener).expect("pair");

    // A device that has somehow learned everything public about the
    // listener. Without the pair secret the handshake cannot start.
    let impostor = Device::new("Impostor").listening();
    let refused = gilune_sync::handshake::dial_session(
        gilune_sync::secure::dial(&listener.address()).expect("connect"),
        gilune_kernel::limits::Limits::DEFAULT,
        &gilune_sync::identity::mint_identity().expect("mint"),
        &gilune_sync::handshake::Greeting::new(&impostor.device_id(), "Phone", ""),
        &listener.identity_pub(),
        [9u8; 32],
    );
    assert!(refused.is_err(), "impersonating a name is not holding a key");
}

// ------------------------------------------------------------------ point 8

#[test]
fn a_revoked_device_is_refused() {
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    joiner.pair_by_code(&listener).expect("pair");

    listener.capture("before the revocation");
    assert_eq!(joiner.sync().applied, 1, "syncing works while paired");

    listener
        .service
        .revoke_peer(&listener.store, &joiner.identity_pub())
        .expect("revoke");

    listener.capture("after the revocation");
    let report = joiner.sync();
    assert_eq!(report.applied, 0, "a revoked device receives nothing further");
    assert!(!report.halted.is_empty(), "and is told it was refused");
    assert_eq!(
        listener
            .service
            .peers()
            .iter()
            .find(|p| p.static_pub == joiner.identity_pub())
            .expect("the row stays")
            .state,
        "revoked"
    );
    // Either side may revoke, and the other side's own attempt to serve
    // is equally dead: the pair secret is gone from one roster, so the
    // handshake cannot complete in either direction.
    assert!(listener.sync().applied == 0);
}

#[test]
fn either_side_may_revoke_and_it_ends_the_pairing_both_ways() {
    // § 9: "Revocation is available in either device's settings". Here
    // the *joiner* revokes the device it paired with, which is the
    // direction the roster's own tests do not cover.
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    joiner.pair_by_code(&listener).expect("pair");
    listener.capture("before");
    assert_eq!(joiner.sync().applied, 1);

    joiner
        .service
        .revoke_peer(&joiner.store, &listener.identity_pub())
        .expect("the joiner revokes");

    listener.capture("after");
    assert_eq!(
        joiner.sync().applied,
        0,
        "the side that revoked cannot dial: its own pair secret is gone"
    );
    // And the other side cannot reach in either, because the revoking
    // device no longer offers that secret as a candidate.
    joiner.capture("written after the revocation");
    let report = listener.sync();
    assert!(
        report.applied == 0 && !report.halted.is_empty(),
        "a revocation binds both directions: {report:?}"
    );
}

#[test]
fn revocation_does_not_retract_what_was_already_applied() {
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    joiner.pair_by_code(&listener).expect("pair");
    listener.capture("a thought");
    joiner.sync();
    let held = joiner.digest();

    listener
        .service
        .revoke_peer(&listener.store, &joiner.identity_pub())
        .expect("revoke");

    assert_eq!(
        joiner.digest(),
        held,
        "revocation protects what comes next; the UI must not imply an undo"
    );
}

#[test]
fn revoking_mid_session_closes_it_at_an_operation_boundary_with_nothing_half_applied() {
    let listener = Device::new("Desktop").listening();
    let joiner = Device::new("Phone").listening();
    joiner.pair_by_code(&listener).expect("pair");

    for n in 0..3 {
        listener.capture(&format!("thought {n}"));
    }
    let listener_device = listener.device_id();

    // A session the test drives request by request, so the revocation
    // lands at a known point rather than a lucky one.
    let mut session = joiner.dial(&listener).expect("dial");
    let offered = session.devices().expect("the first request is served");
    assert!(offered.iter().any(|d| d.device_id == listener_device));

    let before_frontier = joiner.frontier(&listener);
    let before_digest = joiner.digest();

    listener
        .service
        .revoke_peer(&listener.store, &joiner.identity_pub())
        .expect("revoke mid-session");

    // The serving loop asks the roster between requests, so this one
    // never runs. Apply is one transaction per operation and none of
    // them started, which is what "nothing half-applied" means here.
    let after = session.have_after(&listener_device, before_frontier, 64);
    assert!(after.is_err(), "the live session is closed at once");

    assert_eq!(joiner.frontier(&listener), before_frontier, "the frontier did not move");
    assert_eq!(joiner.digest(), before_digest, "and nothing landed part-way");
    assert_eq!(
        joiner.holds_from(&listener_device),
        0,
        "no operation of the revoked peer's was written"
    );
    let _ = session.close();
}

#[test]
fn a_third_replica_refuses_a_revoked_devices_operations_however_they_arrive() {
    // A full-replica mesh: A knows B and C, and B knows C. That is the
    // shape § 9 is about — "in a full-replica mesh a third paired device
    // holds and would otherwise relay them".
    let a = Device::new("Desktop").listening();
    let b = Device::new("Phone").listening();
    let c = Device::new("Tablet").listening();
    b.pair_by_code(&a).expect("A and B pair");
    c.pair_by_code(&a).expect("A and C pair");
    c.pair_by_code(&b).expect("B and C pair");

    b.capture("something B wrote");
    c.sync();
    let b_device = b.device_id();
    assert!(c.holds_from(&b_device) > 0, "C holds B's history, as a full replica does");

    // A revokes B — and then hears from C, which is holding B's work and
    // will offer it on.
    a.service
        .revoke_peer(&a.store, &b.identity_pub())
        .expect("revoke B on A");
    c.capture("something C wrote");
    let report = c.sync();

    assert_eq!(
        a.holds_from(&b_device),
        0,
        "a revoked device is excluded whoever delivers its operations"
    );
    let alerts = a.alerts_of(alerts::SYNC_REVOKED_DEVICE);
    assert_eq!(alerts.len(), 1, "and the refusal is visible, never silent");
    assert_eq!(alerts[0].subject, b_device);
    assert!(
        report.halted.iter().any(|h| h.device_id == b_device),
        "C is told which stream was refused: {:?}",
        report.halted
    );

    // The refusal is one stream, not the session: C's own work still
    // reaches A.
    assert!(
        a.holds_from(&c.device_id()) > 0,
        "refusing a third device's history must not cost the peer's own"
    );
}

#[test]
fn re_pairing_a_revoked_device_lets_its_operations_in_again() {
    let a = Device::new("Desktop").listening();
    let b = Device::new("Phone").listening();
    b.pair_by_code(&a).expect("pair");
    a.service.revoke_peer(&a.store, &b.identity_pub()).expect("revoke");
    assert_eq!(a.store.refused_devices().expect("refusals"), vec![b.device_id()]);

    b.capture("after the revocation");
    b.pair_by_code(&a).expect("re-pairing is a new pairing");

    assert!(
        a.store.refused_devices().expect("refusals").is_empty(),
        "a device welcomed back is no longer refused"
    );
    assert!(
        a.alerts_of(alerts::SYNC_REVOKED_DEVICE).is_empty(),
        "and nobody has to dismiss a notice about it"
    );
    b.sync();
    assert!(a.holds_from(&b.device_id()) > 0);
}

// ------------------------------------------------------------------ point 9

#[test]
fn a_vault_copied_to_another_machine_re_enrols_and_leaves_the_original_alone() {
    let original = Device::new("Desktop");
    original.capture("written on the first machine");
    let original_device = original.device_id();
    let original_identity = original.identity_pub();
    assert_eq!(
        original.store.identity_pub().expect("marker"),
        Some(original_identity.clone())
    );

    // The vault, copied the way a person copies a folder. The
    // device-local identity is not in it — that is the whole design.
    let copy_vault = tempfile::tempdir().expect("copy");
    copy_tree(original.vault_path.as_path(), copy_vault.path());
    let copy_local = tempfile::tempdir().expect("elsewhere");
    let copy = Device::at("Copy", copy_vault, copy_local);

    assert_eq!(
        copy.service.enrolment().as_str(),
        "re-enrolled",
        "a vault whose identity this machine does not hold came from elsewhere"
    );
    assert_ne!(copy.device_id(), original_device, "a new device id");
    assert_ne!(copy.identity_pub(), original_identity, "and a new identity");
    assert!(
        copy.service
            .enrolment()
            .notice()
            .expect("a notice")
            .contains("new device"),
        "and the user is told, rather than wondering why their laptop appears twice"
    );

    // A fresh sequence space: the new identity has written nothing.
    copy.capture("written on the second machine");
    assert_eq!(
        copy.holds_from(&copy.device_id()),
        1,
        "the new device starts at sequence one"
    );
    // And it still holds the old device's history, as any full replica
    // holds a third device's — with a frontier that says so, which is
    // what stops a later session halting on an operation id it already
    // has.
    assert!(copy.holds_from(&original_device) > 0);
    assert_eq!(
        copy.store.sync_frontier(&original_device).expect("frontier"),
        copy.holds_from(&original_device)
    );

    // The original is untouched.
    assert_eq!(original.device_id(), original_device);
    assert_eq!(original.identity_pub(), original_identity);
    assert_eq!(
        original.store.identity_pub().expect("marker"),
        Some(original_identity)
    );
    assert_eq!(original.holds_from(&original_device), 1);
}

#[test]
fn the_original_and_its_copy_can_pair_and_converge_as_two_devices() {
    let original = Device::new("Desktop").listening();
    original.capture("from the first");
    let copy_vault = tempfile::tempdir().expect("copy");
    copy_tree(original.vault_path.as_path(), copy_vault.path());
    let copy = Device::at("Laptop", copy_vault, tempfile::tempdir().expect("local")).listening();
    copy.capture("from the second");

    copy.pair_by_code(&original).expect("pair");
    let report = copy.sync();
    assert!(
        report.halted.is_empty(),
        "a copied vault must not halt its parent on an operation id both already hold: {:?}",
        report.halted
    );
    original.sync();
    assert_eq!(
        original.digest(),
        copy.digest(),
        "they converge as two devices, which is what re-enrolment made them"
    );
}

// ----------------------------------------------------------------- point 10

/// The update-shaped scenario that *is* expressible today.
///
/// An application update ends the process, replaces the binary and leaves
/// device-local state where it was — that last part is ADR 0016's own
/// gate, and this joins it. What a `cargo test` can express is the
/// process half: every handle, the listener thread and the service are
/// dropped, and a new device is opened at the same two directories. What
/// it cannot express is the installer half, and that is stated in the
/// report rather than implied by a green test here.
#[test]
fn an_identity_and_its_pairings_outlive_every_handle_that_made_them() {
    let vault = tempfile::tempdir().expect("vault");
    let local = tempfile::tempdir().expect("device-local");
    let peer = Device::new("Phone").listening();

    let (identity, device_id, fingerprint, protection) = {
        let device = Device::open("Desktop", vault.path(), local.path()).listening();
        assert_eq!(device.service.enrolment().as_str(), "adopted", "first run");
        peer.pair_by_code(&device).expect("pair");
        device.capture("before the update");
        peer.sync();
        let facts = device.service.facts();
        (
            device.identity_pub(),
            device.device_id(),
            facts.fingerprint,
            facts.key_protection,
        )
    };

    // A new process, the same machine, the same vault.
    let after = Device::open("Desktop", vault.path(), local.path()).listening();
    let facts = after.service.facts();

    assert_eq!(
        after.service.enrolment().as_str(),
        "continued",
        "the same device, not a new one: an update must never look like a copy"
    );
    assert_eq!(after.identity_pub(), identity, "the same identity key");
    assert_eq!(after.device_id(), device_id, "and the same device id");
    assert_eq!(facts.fingerprint, fingerprint);
    assert_eq!(facts.key_protection, protection);

    let paired_address = peer
        .peer_by_key(&identity)
        .expect("the peer knows the device")
        .address;

    // The roster came back with it, secrets and all — which only a real
    // session can prove, because a stored secret that no longer opens a
    // handshake has not survived in any sense that matters.
    let remembered = after.peer(&peer).expect("the pairing survived");
    assert_eq!(remembered.state, "paired");
    after.capture("after the update");
    let report = after.sync();
    assert!(report.halted.is_empty(), "{:?}", report.halted);
    assert_eq!(
        peer.holds_from(&device_id),
        2,
        "the stored pair secret still opens a session, so it survived intact"
    );

    // And the limitation this exposes, pinned rather than left to be
    // discovered. The peer's roster still holds the address recorded at
    // pairing and has learned nothing since, because M5b has no
    // discovery: if the restarted device came back on a different port,
    // only the restarted side can reconnect. That is exactly what M5c's
    // cached-address-then-manual-entry-then-diagnostic chain is for, and
    // it is why discovery is the next stage rather than a nicety.
    let cached = peer.peer(&after).expect("the peer still knows the device");
    assert_eq!(
        cached.address, paired_address,
        "no address is learned without discovery or a session the peer itself opened"
    );
}

#[test]
fn the_identity_key_is_nowhere_inside_the_vault() {
    let device = Device::new("Desktop");
    let identity_file = std::fs::read_to_string(
        gilune_sync::IdentityStore::new(&device.local_path).identity_path(&device.device_id()),
    )
    .expect("the identity file");
    assert!(
        !identity_file.is_empty(),
        "there is an identity, and it is in the device-local directory"
    );

    // Nothing anywhere under the vault may hold the public key the vault
    // records — never mind the private one. `meta.identity_pub` is the
    // marker and lives in the kernel database, which is the one place it
    // belongs; the *file* must not be reachable by copying the folder.
    let mut found = Vec::new();
    walk(&device.vault_path, &mut found);
    for path in found {
        let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
        assert!(
            !name.contains("identity") && !name.contains("roster"),
            "{} is inside the vault, and a copied vault is a cloned identity",
            path.display()
        );
    }
}

fn walk(dir: &std::path::Path, into: &mut Vec<std::path::PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            walk(&path, into);
        } else {
            into.push(path);
        }
    }
}
