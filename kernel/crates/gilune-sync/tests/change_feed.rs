//! A change that arrived from another device reaches the vault change
//! feed (ADR 0020 § 8.1, last path).
//!
//! **The S4 lesson, applied** (CLAUDE.md, 2026-09-03): S4's scenarios
//! never crossed device streams out of causal order, so every sync
//! scenario since is written with a third replica that pulls the streams
//! in the wrong order. Here that is not decoration — it is the case
//! where a stream halts, applies nothing, resumes, and the question is
//! whether the feed reports the vault once, correctly, at the end, or
//! reports a note that was never written.

mod replica;

use gilune_kernel::changes::{ChangeCause, ChangeKind};
use replica::{Fault, Replica};

/// Pull until nothing is outstanding.
fn converge(from: &Replica, to: &Replica) {
    let mut passes = 0;
    while to.pull_from(from).incomplete() && passes < 8 {
        passes += 1;
    }
}

fn feed(replica: &Replica) -> Vec<(ChangeKind, String, ChangeCause)> {
    replica
        .store
        .changes_since(&replica.store.change_origin().expect("origin"), 1000)
        .expect("changes")
        .changes
        .into_iter()
        .map(|c| (c.kind, c.rel_path, c.cause))
        .collect()
}

#[test]
fn a_capture_that_arrived_from_another_device_is_a_created_change_caused_by_sync() {
    let a = Replica::new();
    let b = Replica::new();
    let receipt = a.store.capture_bytes("txt", b"written on a").expect("capture");
    a.store.project().expect("project");

    b.pull_from(&a);
    assert!(feed(&b).is_empty(), "applying an operation is not projecting a file");
    b.store.project().expect("project");

    let rel = receipt.rel_path.replace(".txt", ".md");
    assert_eq!(feed(&b), vec![(ChangeKind::Created, rel, ChangeCause::Sync)]);
    // The two replicas number their own feeds; a cursor is about one
    // database and nothing else.
    assert_ne!(
        a.store.change_epoch().expect("epoch"),
        b.store.change_epoch().expect("epoch")
    );
}

#[test]
fn a_third_replica_pulling_the_streams_in_the_wrong_order_reports_the_vault_once() {
    // A wrote a note; B pulled it and edited it. C meets B first, so
    // B's revision arrives before A's object — the stream halts,
    // applies nothing, and resumes when the other stream lands.
    //
    // The feed must describe what the vault *ended up* holding: one file
    // created once, with the words that won, and no announcement of a
    // note that was never written to disk.
    let a = Replica::new();
    let b = Replica::new();
    let receipt = a.store.capture_bytes("txt", b"what a wrote").expect("capture");
    a.store.project().expect("project");
    b.pull_from(&a);
    b.store.project().expect("project");
    let rel = b.projection_of(&receipt.source_id).expect("projected on b");
    let text = std::fs::read_to_string(b.path(&rel)).expect("read");
    std::fs::write(b.path(&rel), text.replace("what a wrote", "what b made of it"))
        .expect("edit on b");
    b.store.reconcile().expect("reconcile");
    b.store.project().expect("project");

    let c = Replica::new();
    let a_device = a.store.device_id().expect("device");
    let halted = c.pull_through(&b, Fault::WithholdDevice(a_device));
    assert_eq!(halted.applied, 0, "the naming operation arrived before what it names");
    c.store.project().expect("project");
    assert!(
        feed(&c).is_empty(),
        "a halted stream wrote no file, so the feed announces none"
    );

    converge(&b, &c);
    c.store.project().expect("project");

    assert_eq!(
        feed(&c),
        vec![(ChangeKind::Created, rel.clone(), ChangeCause::Sync)],
        "one file appeared once, however many passes it took to get there"
    );
    assert!(std::fs::read_to_string(c.path(&rel))
        .expect("read")
        .contains("what b made of it"));
    assert_eq!(b.digest(), c.digest());
    assert!(c.store.verify().expect("verify").is_empty());

    // And the sequence is dense over the whole of it.
    let page = c
        .store
        .changes_since(&c.store.change_origin().expect("origin"), 1000)
        .expect("changes");
    assert_eq!(page.changes.iter().map(|ch| ch.seq).collect::<Vec<_>>(), vec![1]);
    assert!(page.complete);
}

#[test]
fn a_deletion_that_arrived_out_of_order_removes_the_file_and_says_so_once() {
    // The same shape for a tombstone, which is the operation that used
    // to be dropped silently: C meets B first, so the deletion arrives
    // before the object it deletes.
    let a = Replica::new();
    let b = Replica::new();
    let receipt = a.store.capture_bytes("txt", b"this will go").expect("capture");
    a.store.project().expect("project");
    b.pull_from(&a);
    b.store.project().expect("project");
    let rel = b.projection_of(&receipt.source_id).expect("projected on b");
    std::fs::remove_file(b.path(&rel)).expect("delete on b");
    b.store.reconcile().expect("reconcile");
    let proposal = b.store.open_proposals().expect("proposals")[0].0.clone();
    b.store.resolve_tombstone(&proposal, true).expect("accept");

    let c = Replica::new();
    let a_device = a.store.device_id().expect("device");
    let b_device = b.store.device_id().expect("device");

    // The wrong order first: the deletion, with nothing it can be about.
    assert_eq!(c.pull_through(&b, Fault::WithholdDevice(a_device)).applied, 0);
    c.store.project().expect("project");
    assert!(feed(&c).is_empty(), "a note that never arrived is never announced");

    // Then the object, and only the object — so the file really is
    // written here before the deletion lands, which is the ordering
    // where a withdrawal has something to withdraw.
    c.pull_through(&b, Fault::WithholdDevice(b_device));
    c.store.project().expect("project");
    assert_eq!(feed(&c), vec![(ChangeKind::Created, rel.clone(), ChangeCause::Sync)]);
    assert!(c.path(&rel).exists());

    // And now the deletion, which the halted stream resumes into.
    converge(&b, &c);
    c.store.project().expect("project");

    assert!(!c.path(&rel).exists());
    assert_eq!(
        feed(&c),
        vec![
            (ChangeKind::Created, rel.clone(), ChangeCause::Sync),
            (ChangeKind::Removed, rel, ChangeCause::Sync),
        ],
        "one file, written here and then taken away, and each said once"
    );
    assert_eq!(b.digest(), c.digest());
    assert!(c.store.integrity_alerts().expect("alerts").is_empty());
    assert!(c.store.verify().expect("verify").is_empty());
}
