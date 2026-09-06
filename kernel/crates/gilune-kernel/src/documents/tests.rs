use super::*;

fn vault() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    (dir, store)
}
fn captured() -> (tempfile::TempDir, Store, String) {
    let (dir, store) = vault();
    let receipt = store
        .capture_bytes("txt", b"- fixture\n  label=red")
        .unwrap();
    store.project().unwrap();
    (dir, store, receipt.rel_path.replace(".txt", ".md"))
}
fn replacement(snapshot: &DocumentSnapshot) -> TextEdit {
    let start = snapshot.content.text().find("red").unwrap() as u64;
    TextEdit {
        range: ByteSpan {
            start,
            end: start + 3,
        },
        replacement: "blue".into(),
    }
}

#[test]
fn unmanaged_snapshot_and_preparation_preserve_bytes_without_adoption_or_writes() {
    let (_dir, store) = vault();
    let raw = "\u{feff}---\r\nname: fixture\r\nnested:\r\n  opaque: [1, 2]\r\n---\r\n- 😀 label=red\r\n  continuation\r\n";
    std::fs::write(store.vault().join("outside.md"), raw).unwrap();
    let before = store.status().unwrap();
    let snapshot = store.document_snapshot("outside.md").unwrap();
    assert_eq!(snapshot.source.state, DocumentState::Unmanaged);
    assert!(snapshot.source.object_id.is_none());
    assert_eq!(snapshot.content.text(), raw);
    let prepared = store
        .prepare_document_change(
            "outside.md",
            &snapshot.source.version,
            &[replacement(&snapshot)],
        )
        .unwrap();
    assert_eq!(prepared.after.text(), raw.replace("red", "blue"));
    assert_eq!(
        std::fs::read_to_string(store.vault().join("outside.md")).unwrap(),
        raw
    );
    assert_eq!(store.status().unwrap().projections, before.projections);
    let objects: i64 = store
        .conn()
        .query_row("SELECT count(*) FROM objects", [], |row| row.get(0))
        .unwrap();
    assert_eq!(objects, 0, "reading and preparing never adopts the file");
}

#[test]
fn current_managed_projection_has_verified_identity_and_revision() {
    let (_dir, store, rel) = captured();
    let s = store.document_snapshot(&rel).unwrap();
    assert_eq!(s.source.state, DocumentState::Managed);
    assert_eq!(
        s.source.object_id.as_deref(),
        s.content.metadata_string("gilune-id")
    );
    assert_eq!(
        s.source.revision_id.as_deref(),
        s.content.metadata_string("gilune-revision")
    );
    let original = s.content.text().to_string();
    let prepared = store
        .prepare_document_change(&rel, &s.source.version, &[replacement(&s)])
        .unwrap();
    assert_eq!(prepared.after.text(), original.replace("red", "blue"));
    assert_eq!(
        store.document_snapshot(&rel).unwrap().source.version,
        s.source.version
    );
}

#[test]
fn externally_changed_bytes_cannot_claim_the_old_revision_or_use_old_offsets() {
    let (_dir, store, rel) = captured();
    let before = store.document_snapshot(&rel).unwrap();
    let changed = before
        .content
        .text()
        .replace("label=red", "extra text 😀 label=red");
    std::fs::write(store.vault().join(&rel), &changed).unwrap();
    assert!(matches!(
        store.prepare_document_change(&rel, &before.source.version, &[replacement(&before)]),
        Err(KernelError::DocumentChanged)
    ));
    let after = store.document_snapshot(&rel).unwrap();
    assert_eq!(after.source.state, DocumentState::NeedsReconciliation);
    assert_eq!(after.source.object_id, before.source.object_id);
    assert!(after.source.revision_id.is_none());
    assert!(matches!(
        store.prepare_document_change(&rel, &after.source.version, &[]),
        Err(KernelError::DocumentUnavailable { .. })
    ));
    assert_eq!(
        std::fs::read_to_string(store.vault().join(&rel)).unwrap(),
        changed
    );
    store.reconcile_path(&rel).unwrap();
    store.project().unwrap();
    assert_eq!(
        store.document_snapshot(&rel).unwrap().source.state,
        DocumentState::Managed
    );
}

#[test]
fn changing_the_head_invalidates_source_version_even_when_file_bytes_are_unchanged() {
    let (_dir, store, rel) = captured();
    let before = store.document_snapshot(&rel).unwrap();
    let object = before.source.object_id.as_ref().unwrap();
    let payload = revisions::head_payload(store.conn(), object)
        .unwrap()
        .unwrap();
    store
        .write_txn(|| {
            revisions::add_revision(
                store.conn(),
                object,
                &crate::ids::new_id(),
                &payload,
                "fixture",
                crate::ids::now_ms(),
                revisions::Announce::Silent,
            )
        })
        .unwrap();
    let after = store.document_snapshot(&rel).unwrap();
    assert_eq!(after.content.version(), before.content.version());
    assert_ne!(after.source.version, before.source.version);
    assert_eq!(after.source.state, DocumentState::NeedsReconciliation);
    assert!(after.source.revision_id.is_none());
    assert!(matches!(
        store.prepare_document_change(&rel, &before.source.version, &[]),
        Err(KernelError::DocumentChanged)
    ));
}

#[test]
fn identity_spoofing_quarantine_and_deletion_have_explicit_uneditable_states() {
    let (_dir, store, rel) = captured();
    let original = store.document_snapshot(&rel).unwrap();
    std::fs::write(store.vault().join("copy.md"), original.content.text()).unwrap();
    let copied = store.document_snapshot("copy.md").unwrap();
    assert_eq!(copied.source.state, DocumentState::UnverifiedIdentity);
    assert!(copied.source.object_id.is_none());
    store
        .conn()
        .execute(
            "INSERT INTO quarantine (rel_path, reason, created_ms) VALUES (?1, 'fixture', 1)",
            [&rel],
        )
        .unwrap();
    let quarantined = store.document_snapshot(&rel).unwrap();
    assert_eq!(quarantined.source.state, DocumentState::Quarantined);
    assert!(quarantined.source.revision_id.is_none());
    assert!(matches!(
        store.prepare_document_change(&rel, &quarantined.source.version, &[]),
        Err(KernelError::DocumentUnavailable { .. })
    ));
    store
        .conn()
        .execute("DELETE FROM quarantine WHERE rel_path = ?1", [&rel])
        .unwrap();
    store
        .conn()
        .execute(
            "UPDATE objects SET deleted = 1 WHERE object_id = ?1",
            [&original.source.object_id.unwrap()],
        )
        .unwrap();
    let deleted = store.document_snapshot(&rel).unwrap();
    assert_eq!(deleted.source.state, DocumentState::Deleted);
    assert!(deleted.source.revision_id.is_none());
}

#[test]
fn managed_move_preserves_object_identity_but_changes_source_version() {
    let (_dir, store, rel) = captured();
    let before = store.document_snapshot(&rel).unwrap();
    std::fs::rename(store.vault().join(&rel), store.vault().join("moved.md")).unwrap();
    store.reconcile().unwrap();
    let after = store.document_snapshot("moved.md").unwrap();
    assert_eq!(after.source.document_key, before.source.document_key);
    assert_ne!(after.source.version, before.source.version);
    assert_eq!(after.source.state, DocumentState::Managed);
}

#[test]
fn concurrent_heads_and_pending_deletion_are_not_editable_snapshots() {
    let (_dir, store, rel) = captured();
    let before = store.document_snapshot(&rel).unwrap();
    let object = before.source.object_id.as_ref().unwrap();
    let old_head = before.source.revision_id.as_ref().unwrap();
    let payload = revisions::head_payload(store.conn(), object)
        .unwrap()
        .unwrap();
    store
        .write_txn(|| {
            revisions::add_revision(
                store.conn(),
                object,
                &crate::ids::new_id(),
                &payload,
                "fixture",
                crate::ids::now_ms(),
                revisions::Announce::Silent,
            )?;
            store.conn().execute(
                "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                [object, old_head],
            )?;
            Ok(())
        })
        .unwrap();
    let conflicted = store.document_snapshot(&rel).unwrap();
    assert_eq!(conflicted.source.state, DocumentState::Conflicted);
    assert!(conflicted.source.revision_id.is_none());
    assert!(matches!(
        store.prepare_document_change(&rel, &conflicted.source.version, &[]),
        Err(KernelError::DocumentUnavailable { .. })
    ));
    store
        .conn()
        .execute(
            "DELETE FROM heads WHERE object_id = ?1 AND revision_id <> ?2",
            [object, old_head],
        )
        .unwrap();
    store.conn().execute("INSERT INTO proposals (proposal_id, object_id, kind, state, created_ms) VALUES ('fixture', ?1, 'tombstone', 'open', 1)", [object]).unwrap();
    let pending = store.document_snapshot(&rel).unwrap();
    assert_eq!(pending.source.state, DocumentState::PendingDeletion);
    assert!(pending.source.revision_id.is_none());
    assert!(matches!(
        store.prepare_document_change(&rel, &pending.source.version, &[]),
        Err(KernelError::DocumentUnavailable { .. })
    ));
}

#[test]
fn document_reads_reject_path_escapes_and_malformed_sources() {
    let (_dir, store) = vault();
    std::fs::write(store.vault().join("valid.MD"), "hello").unwrap();
    assert!(store.document_snapshot("valid.MD").is_ok());
    for path in [
        "../valid.md",
        "/valid.md",
        "C:/valid.md",
        ".gilune/local/fake.md",
        ".GILUNE/local/fake.md",
        "valid.md:stream",
        "./valid.md",
        "a//b.md",
        "a/../valid.md",
        "a./b.md",
        "valid.txt",
    ] {
        assert!(
            matches!(
                store.document_snapshot(path),
                Err(KernelError::DocumentInvalid { .. })
            ),
            "{path}"
        );
    }
    std::fs::write(store.vault().join("broken.md"), "---\nkey: [\n---\n").unwrap();
    assert!(matches!(
        store.document_snapshot("broken.md"),
        Err(KernelError::DocumentInvalid { .. })
    ));
    std::fs::write(store.vault().join("invalid.md"), [0xff, 0xfe]).unwrap();
    assert!(matches!(
        store.document_snapshot("invalid.md"),
        Err(KernelError::Io { .. })
    ));
}

#[test]
fn source_versions_are_bound_to_the_local_vault_but_survive_reopening() {
    let (dir, store) = vault();
    let (_other_dir, other) = vault();
    for vault in [&store, &other] {
        std::fs::write(vault.vault().join("fixture.md"), "identical").unwrap();
    }
    let one = store.document_snapshot("fixture.md").unwrap();
    let two = other.document_snapshot("fixture.md").unwrap();
    assert_eq!(one.content.version(), two.content.version());
    assert_ne!(one.source.version, two.source.version);
    assert!(matches!(
        other.prepare_document_change("fixture.md", &one.source.version, &[]),
        Err(KernelError::DocumentChanged)
    ));
    drop(store);
    let reopened = Store::open(dir.path()).unwrap();
    assert_eq!(
        reopened
            .document_snapshot("fixture.md")
            .unwrap()
            .source
            .version,
        one.source.version
    );
}
