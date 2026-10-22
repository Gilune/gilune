use super::*;
use crate::documents::ByteSpan;

#[test]
fn undo_cannot_rebind_a_removed_property_to_matching_body_prose() {
    let published = "---\nlabel: blue\nother: keep\n---\nlabel: blue\nother: keep\n";
    let restored = published.replacen("label: blue", "label: red", 1);
    let edited = published.replacen("label: blue\nother: keep", "unrelated: true", 1);
    assert!(inverse_edits(published, &restored, &edited).is_err());
}

#[test]
fn a_moved_parent_during_interruption_does_not_prevent_opening_the_vault() {
    let (dir, store, path, _plan, entry) = fixture(true);
    let recovery = store.refactoring_recovery_dir().unwrap();
    std::fs::rename(
        store.vault().join(&path),
        recovery.join(format!("{}.original", entry.id)),
    )
    .unwrap();
    std::fs::rename(store.vault().join("Captures"), store.vault().join("Moved")).unwrap();
    store
        .conn()
        .execute(
            "UPDATE refactoring_entries SET state='applying' WHERE entry_id=?1",
            [&entry.id],
        )
        .unwrap();
    drop(store);
    let reopened = Store::open(dir.path()).unwrap();
    assert_eq!(
        reopened.refactoring_entry(&entry.id).unwrap().state,
        "conflict"
    );
    assert_eq!(
        reopened
            .refactoring_recovery_copies(&entry.id)
            .unwrap()
            .len(),
        1
    );
    assert!(!dir.path().join(&path).exists());
}

#[test]
fn managed_rename_and_offline_edit_sync_as_ordinary_revisions() {
    use crate::limits::Limits;
    let (_root, a, path, plan, entry) = fixture(true);
    let other = tempfile::tempdir().unwrap();
    let b = Store::open(other.path()).unwrap();
    let device = a.device_id().unwrap();
    for op in a.operations_after(&device, 0, 100).unwrap() {
        b.apply_operation("a", &op, Limits::DEFAULT).unwrap();
    }
    for blob in b.blobs_awaiting_bytes().unwrap() {
        b.write_blob_chunk(&blob.blob_id, 0, b"label=red\n", Limits::DEFAULT)
            .unwrap();
        assert!(b.finish_blob(&blob.blob_id).unwrap());
    }
    b.project().unwrap();
    let old = read(&b.vault().join(&path)).unwrap();
    assert!(old.contains("label=red"), "initial peer projection: {old}");
    std::fs::write(
        b.vault().join(&path),
        old.replace("label=red", "offline label=red"),
    )
    .unwrap();
    let reconciled = b.reconcile_path(&path).unwrap();
    assert_eq!(reconciled.edits, 1, "{reconciled:?}");
    a.apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
        .unwrap();
    let ops = a.operations_after(&device, 0, 100).unwrap();
    for op in &ops {
        b.apply_operation("a", op, Limits::DEFAULT).unwrap();
    }
    for op in b.operations_after(&b.device_id().unwrap(), 0, 100).unwrap() {
        a.apply_operation("b", &op, Limits::DEFAULT).unwrap();
    }
    for store in [&a, &b] {
        let mut statement = store
            .conn()
            .prepare("SELECT payload FROM revisions")
            .unwrap();
        let payloads = statement
            .query_map([], |r| r.get::<_, String>(0))
            .unwrap()
            .collect::<std::result::Result<Vec<_>, _>>()
            .unwrap();
        assert!(payloads.iter().any(|p| p.contains("label=blue")));
        assert!(payloads.iter().any(|p| p.contains("offline label=red")));
    }
    assert!(
        b.refactoring_plans().unwrap().is_empty(),
        "execution journal is local"
    );
    let third = tempfile::tempdir().unwrap();
    let c = Store::open(third.path()).unwrap();
    assert!(
        c.apply_operation("a", ops.last().unwrap(), Limits::DEFAULT)
            .is_err(),
        "gap is explicit"
    );
    for op in &ops {
        c.apply_operation("a", op, Limits::DEFAULT).unwrap();
    }
    assert!(c.refactoring_plans().unwrap().is_empty());
}

#[test]
fn retained_external_bytes_can_be_reviewed_and_restored_without_provider() {
    let (_dir, store, path, plan, entry) = fixture(false);
    store
        .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
        .unwrap();
    let copies = store.refactoring_recovery_copies(&entry.id).unwrap();
    assert_eq!(copies.len(), 1);
    assert_eq!(copies[0].1, "label=red\n");
    let recovery = store
        .prepare_refactoring_recovery(&entry.id, &copies[0].0)
        .unwrap();
    assert_eq!(
        read(&store.vault().join(&path)).unwrap(),
        "label=blue\n",
        "preview is read-only"
    );
    let restore = store
        .refactoring_entries(&recovery.id, 0, 10)
        .unwrap()
        .remove(0);
    store
        .apply_refactoring_entry(
            &recovery.id,
            recovery.review_token.as_ref().unwrap(),
            &restore.id,
        )
        .unwrap();
    assert_eq!(read(&store.vault().join(path)).unwrap(), "label=red\n");
    assert!(store
        .prepare_refactoring_recovery(&entry.id, "../outside")
        .is_err());
}

type EditHook = Box<dyn FnOnce(&Path, &Path)>;
thread_local! {static EDIT_HOOK:std::cell::RefCell<Option<(&'static str,EditHook)>>=const {std::cell::RefCell::new(None)};}
pub(super) fn edit_at(point: &str, target: &Path, captured: &Path) {
    EDIT_HOOK.with(|slot| {
        let mut held = slot.borrow_mut();
        if held.as_ref().is_some_and(|(name, _)| *name == point) {
            let (_, act) = held.take().unwrap();
            act(target, captured);
        }
    });
}

fn fixture(
    managed: bool,
) -> (
    tempfile::TempDir,
    Store,
    String,
    RefactoringPlan,
    RefactoringEntry,
) {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let path = if managed {
        let receipt = store.capture_bytes("txt", b"label=red\n").unwrap();
        store.project().unwrap();
        receipt.rel_path.replace(".txt", ".md")
    } else {
        // Local-only by the person's mark (ADR 0007, 2026-10-22): the one
        // case in which a file the kernel did not write stays unmanaged.
        std::fs::write(dir.path().join(".gilune-local"), "").unwrap();
        std::fs::write(dir.path().join("note.md"), "label=red\n").unwrap();
        "note.md".into()
    };
    store.reconcile_path(&path).unwrap();
    let snapshot = store.document_snapshot(&path).unwrap();
    let start = snapshot.content.text().find("red").unwrap() as u64;
    let plan = store
        .create_refactoring_plan("fixture.labels", "1", "red->blue", "Rename label")
        .unwrap();
    let entry = store
        .add_refactoring_entry(
            &plan.id,
            &path,
            &snapshot.source.version,
            &[TextEdit {
                range: ByteSpan {
                    start,
                    end: start + 3,
                },
                replacement: "blue".into(),
            }],
        )
        .unwrap();
    let plan = store.seal_refactoring_plan(&plan.id).unwrap();
    (dir, store, path, plan, entry)
}

#[test]
fn managed_change_commits_one_revision_and_operation_and_preserves_capture() {
    let (dir, store, path, plan, entry) = fixture(true);
    let before: i64 = store
        .conn()
        .query_row("SELECT count(*) FROM revisions", [], |r| r.get(0))
        .unwrap();
    let applied = store
        .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
        .unwrap();
    assert_eq!(applied.state, "applied");
    assert!(read(&dir.path().join(&path))
        .unwrap()
        .contains("label=blue"));
    assert_eq!(
        store.document_snapshot(&path).unwrap().source.state,
        DocumentState::Managed
    );
    assert_eq!(store.refactoring_plan(&plan.id).unwrap().state, "completed");
    let replay = store
        .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
        .unwrap();
    assert_eq!(replay.id, entry.id);
    let after: i64 = store
        .conn()
        .query_row("SELECT count(*) FROM revisions", [], |r| r.get(0))
        .unwrap();
    assert_eq!(after, before + 1);
    let origin: String = store
        .conn()
        .query_row(
            "SELECT origin FROM revisions WHERE revision_id=?1",
            [&entry.id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(origin, format!("refactoring:{}", plan.id));
    assert_eq!(
        read(&dir.path().join(path.replace(".md", ".txt"))).unwrap(),
        "label=red\n"
    );
}

#[test]
fn unmanaged_change_stays_plain_and_newer_text_rejects_the_review() {
    let (dir, store, path, plan, entry) = fixture(false);
    assert_eq!(
        store
            .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
            .unwrap()
            .state,
        "applied"
    );
    assert_eq!(read(&dir.path().join(path)).unwrap(), "label=blue\n");
    assert_eq!(
        store.document_snapshot("note.md").unwrap().source.state,
        DocumentState::Unmanaged
    );
    let (_dir, store, path, plan, entry) = fixture(false);
    std::fs::write(store.vault().join(&path), "my later text").unwrap();
    assert_eq!(
        store
            .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
            .unwrap()
            .state,
        "conflict"
    );
    assert_eq!(read(&store.vault().join(path)).unwrap(), "my later text");
}

#[test]
fn review_token_cancellation_and_entry_ownership_bound_every_write() {
    let (_dir, store, _path, plan, entry) = fixture(false);
    assert!(store
        .apply_refactoring_entry(&plan.id, "wrong", &entry.id)
        .is_err());
    assert!(store
        .add_refactoring_issue(&plan.id, "other.md", "late candidate")
        .is_err());
    store.set_refactoring_cancelled(&plan.id, true).unwrap();
    assert!(store
        .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
        .is_err());
    store.set_refactoring_cancelled(&plan.id, false).unwrap();
    assert_eq!(
        store
            .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
            .unwrap()
            .state,
        "applied"
    );
}

#[test]
fn undo_is_a_new_guarded_revision_and_preserves_later_nonoverlapping_text() {
    for managed in [false, true] {
        let (_dir, store, path, plan, entry) = fixture(managed);
        store
            .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
            .unwrap();
        let undo = store.prepare_refactoring_undo(&plan.id).unwrap();
        let inverse = store
            .refactoring_entries(&undo.id, 0, 10)
            .unwrap()
            .remove(0);
        assert_eq!(inverse.state, "pending", "{}", inverse.detail);
        assert_eq!(
            store
                .apply_refactoring_entry(&undo.id, undo.review_token.as_ref().unwrap(), &inverse.id)
                .unwrap()
                .state,
            "applied"
        );
        assert!(read(&store.vault().join(path))
            .unwrap()
            .contains("label=red"));
    }
    let published = "prefix\nlabel=blue\n".to_string() + &"unchanged\n".repeat(12);
    let restored = published.replace("blue", "red");
    let later = published.clone() + "later user text\n";
    let edit = inverse_edit(&published, &restored, &later).unwrap();
    let snapshot = documents::TextSnapshot::new(later).unwrap();
    let result = snapshot.propose(snapshot.version(), &[edit]).unwrap();
    assert_eq!(result.text(), format!("{restored}later user text\n"));
    assert!(inverse_edit(&published, &restored, &published.replace("blue", "green")).is_err());
}

#[test]
fn external_edits_at_replacement_windows_are_preserved_and_reported() {
    for point in [
        "before-displacement",
        "after-displacement",
        "after-publication",
    ] {
        let (_dir, store, path, plan, entry) = fixture(false);
        EDIT_HOOK.with(|slot| {
            *slot.borrow_mut() = Some((
                point,
                Box::new(move |target, captured| {
                    let path = if point == "after-publication" {
                        captured
                    } else {
                        target
                    };
                    std::fs::write(path, "external editor's later words").unwrap();
                }),
            ))
        });
        let result = store
            .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
            .unwrap();
        assert_eq!(result.state, "conflict", "{point}");
        assert_eq!(
            read(&store.vault().join(path)).unwrap(),
            "external editor's later words",
            "{point}"
        );
    }
}

#[test]
fn undo_separates_frontmatter_and_body_around_protected_delimiters() {
    let before = "---\nconnections: {role: '@red'}\n---\n@red\n";
    let after = "---\nconnections: {role: '@blue'}\n---\n@blue\n";
    let edits = inverse_edits(after, before, after).unwrap();
    assert_eq!(edits.len(), 2);
    let snapshot = documents::TextSnapshot::new(after.into()).unwrap();
    assert_eq!(
        snapshot.propose(snapshot.version(), &edits).unwrap().text(),
        before
    );
}

#[test]
#[ignore = "subprocess crash harness"]
fn crash_child() {
    let root = std::env::var("GILUNE_REFACTOR_TEST_ROOT").unwrap();
    let store = Store::open(root).unwrap();
    let plan = store.refactoring_plans().unwrap().remove(0);
    let entry = store.refactoring_entries(&plan.id, 0, 1).unwrap().remove(0);
    store
        .apply_refactoring_entry(&plan.id, plan.review_token.as_ref().unwrap(), &entry.id)
        .unwrap();
}

#[test]
fn interrupted_publication_recovers_without_plugins_or_duplicate_history() {
    for point in [
        "refactor-journal",
        "refactor-displaced",
        "refactor-published",
        "refactor-revision",
        "refactor-committed",
    ] {
        let (dir, store, path, plan, entry) = fixture(true);
        let original = read(&dir.path().join(&path)).unwrap();
        drop(store);
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--ignored", "--exact", "refactoring::tests::crash_child"])
            .env("GILUNE_REFACTOR_TEST_ROOT", dir.path())
            .env("GILUNE_CRASHPOINT", point)
            .status()
            .unwrap();
        assert!(!status.success(), "{point}");
        let store = Store::open(dir.path()).unwrap();
        let result = store.refactoring_entry(&entry.id).unwrap();
        if point == "refactor-committed" {
            assert_eq!(result.state, "applied");
            assert_eq!(
                store
                    .apply_refactoring_entry(
                        &plan.id,
                        plan.review_token.as_ref().unwrap(),
                        &entry.id
                    )
                    .unwrap()
                    .state,
                "applied"
            );
        } else {
            assert_eq!(result.state, "conflict", "{point}");
            assert_eq!(read(&dir.path().join(&path)).unwrap(), original, "{point}");
        }
        assert_eq!(
            store.document_snapshot(&path).unwrap().source.state,
            DocumentState::Managed,
            "{point}"
        );
    }
}
