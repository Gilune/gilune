//! The vault change feed, at the boundary (ADR 0020 § 2.1).
//!
//! The feed's rules are proven in the kernel; what is proven here is
//! that a shell can take them — that the cursor round-trips, that the
//! kinds and causes cross as values rather than as strings a shell would
//! have to recognise, and that a cursor this vault cannot answer from
//! arrives as its own error and not as prose in a message.
//!
//! Nothing in the shells reads this yet (that is stage S). It is
//! surfaced now because the stage that builds on it is next, and a
//! boundary nobody has called is a boundary nobody has tested.

use gilune_ffi::{ChangeCause, ChangeKind, Cursor, Gilune, GiluneError};

fn open() -> (tempfile::TempDir, std::sync::Arc<Gilune>) {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    (dir, gilune)
}

#[test]
fn a_capture_crosses_the_boundary_as_one_created_change() {
    let (_dir, gilune) = open();
    let origin = gilune.change_origin().expect("origin");
    assert_eq!(origin.seq, 0);
    assert!(!origin.epoch.is_empty(), "a cursor is only ever about one database");

    let receipt = gilune.capture_text("what a shell saved".to_string()).expect("capture");
    gilune.project().expect("project");

    let page = gilune.changes_since(origin.clone(), 100).expect("changes");
    assert!(page.complete);
    assert_eq!(page.changes.len(), 1);
    assert_eq!(page.changes[0].kind, ChangeKind::Created);
    assert_eq!(page.changes[0].cause, ChangeCause::Capture);
    assert_eq!(page.changes[0].rel_path, receipt.rel_path.replace(".txt", ".md"));
    assert_eq!(page.changes[0].object_id.as_deref(), Some(receipt.source_id.as_str()));
    assert_eq!(page.next.epoch, origin.epoch);
    assert_eq!(page.next.seq, 1);

    // Resuming from where the page left off is the same call and says
    // nothing twice.
    let again = gilune.changes_since(page.next, 100).expect("changes");
    assert!(again.changes.is_empty());
    assert!(again.complete);
}

#[test]
fn an_editors_own_save_is_in_the_feed_before_anything_refreshes() {
    // ADR 0020 § 8.7 at the boundary: the shell writes the file it is
    // editing and calls `reconcilePath`, and the change is there.
    let (dir, gilune) = open();
    let receipt = gilune.capture_text("first draft".to_string()).expect("capture");
    gilune.project().expect("project");
    let settled = gilune
        .changes_since(gilune.change_origin().expect("origin"), 100)
        .expect("changes")
        .next;

    let rel = receipt.rel_path.replace(".txt", ".md");
    let path = dir.path().join(&rel);
    let edited = std::fs::read_to_string(&path).expect("read").replace("first draft", "second");
    std::fs::write(&path, edited).expect("the shell's own save");

    let summary = gilune.reconcile_path(rel.clone()).expect("reconcile one file");
    assert_eq!(summary.edits, 1);
    let page = gilune.changes_since(settled, 100).expect("changes");
    assert_eq!(page.changes.len(), 1);
    assert_eq!(page.changes[0].kind, ChangeKind::Modified);
    assert_eq!(page.changes[0].cause, ChangeCause::ExternalEdit);
    assert_eq!(page.changes[0].rel_path, rel);
}

#[test]
fn a_cursor_this_vault_cannot_answer_from_arrives_as_its_own_error() {
    // A shell's answer to this is specific — rebuild from
    // `changeOrigin` — so it has to be recognisable without matching
    // prose, which is the mistake the sync surface already made once.
    let (_dir, gilune) = open();
    gilune.capture_text("something to be past".to_string()).expect("capture");
    gilune.project().expect("project");
    let epoch = gilune.change_origin().expect("origin").epoch;

    for refused in [
        Cursor { epoch: "another-vaults-epoch".to_string(), seq: 1 },
        Cursor { epoch: epoch.clone(), seq: 99 },
    ] {
        match gilune.changes_since(refused.clone(), 10) {
            Err(GiluneError::CursorUnknown { .. }) => {}
            other => panic!("{refused:?} should have been refused, got {other:?}"),
        }
    }
    assert_eq!(
        gilune
            .changes_since(Cursor { epoch, seq: 0 }, 10)
            .expect("the rebuild a refused cursor sends a shell to")
            .changes
            .len(),
        1
    );
}

#[test]
fn a_path_that_is_not_a_projected_file_in_this_vault_is_refused() {
    let (_dir, gilune) = open();
    for outside in ["../elsewhere.md", "Captures/recording.wav"] {
        assert!(
            gilune.reconcile_path(outside.to_string()).is_err(),
            "{outside} was not refused"
        );
    }
}
