use super::tests::{descriptor, setup};
use super::*;
use crate::plugins::Contribution;

fn enable(store: &Store) {
    let mut d = descriptor();
    d.contributions.push(Contribution {
        slot: TYPE_SLOT.into(),
        id: "org.example.cards/defaults".into(),
        label: "Card defaults".into(),
        config: json!({"version":1,"schema":1,"default_folder":"Settings","template":"",
            "defaults_for":"org.example.cards/card"})
        .to_string(),
    });
    store.register_plugin(&d).unwrap();
}
fn target(store: &Store) -> PageDefinition {
    store
        .page_definitions("org.example.cards")
        .unwrap()
        .remove(0)
}
fn defaults(store: &Store, path: &str, source: &str) -> CreatePage {
    CreatePage {
        type_id: "org.example.cards/defaults".into(),
        definition_version: store
            .page_definitions("org.example.cards")
            .unwrap()
            .remove(1)
            .version,
        request_id: ids::new_id(),
        rel_path: path.into(),
        source: source.into(),
    }
}
fn create(store: &Store, request: &CreatePage) -> Result<PageReceipt> {
    store.create_page("org.example.cards", request)
}

#[test]
fn portable_defaults_are_revision_backed_moveable_and_only_initialize_future_pages() {
    let (dir, store, original) = setup();
    let first = create(&store, &original).unwrap();
    store.project().unwrap();
    let original_bytes = std::fs::read(store.vault().join(&first.rel_path)).unwrap();
    enable(&store);
    let baseline = target(&store);
    let source = "---\r\nfolder: \"\" # root\r\nunknown: {keep: true}\r\n---\r\n---\r\nname: Default\r\n---\r\n# Custom 🦊\r\n";
    let request = defaults(&store, "Settings/cards.md", source);
    let receipt = create(&store, &request).unwrap();
    assert!(target(&store).defaults_problem.unwrap().contains("awaits publication"));
    store.project().unwrap();
    crate::probe::reset_file_reads();
    let effective = target(&store);
    assert_eq!(crate::probe::file_reads(), 0);
    assert_eq!(effective.default_folder, "");
    assert_eq!(
        effective.template,
        "---\r\nname: Default\r\n---\r\n# Custom 🦊\r\n"
    );
    assert!(effective.defaults_problem.is_none());
    assert_ne!(effective.version, baseline.version);
    let next = CreatePage {
        request_id: ids::new_id(),
        rel_path: "next.md".into(),
        source: effective.template.clone(),
        definition_version: effective.version,
        ..original.clone()
    };
    create(&store, &next).unwrap();
    store.project().unwrap();
    assert_eq!(
        std::fs::read(store.vault().join(&first.rel_path)).unwrap(),
        original_bytes
    );
    std::fs::rename(
        store.vault().join(&receipt.rel_path),
        store.vault().join("moved-defaults.md"),
    )
    .unwrap();
    store.reconcile_path("moved-defaults.md").unwrap();
    assert_eq!(
        target(&store).defaults_path.as_deref(),
        Some("moved-defaults.md")
    );
    assert_eq!(create(&store, &request).unwrap(), receipt);
    drop(store);
    let reopened = Store::open(dir.path()).unwrap();
    assert_eq!(target(&reopened).template, next.source);
    reopened
        .set_plugin_enabled("org.example.cards", false)
        .unwrap();
    assert!(reopened.page_definitions("org.example.cards").is_err());
    assert!(reopened.vault().join("moved-defaults.md").exists());
}

#[test]
fn stale_forms_and_unreconciled_configuration_cannot_create_pages() {
    let (_dir, store, original) = setup();
    enable(&store);
    let before = target(&store);
    let request = defaults(
        &store,
        "Settings/cards.md",
        "---\nfolder: Cards\n---\nfirst",
    );
    let receipt = create(&store, &request).unwrap();
    store.project().unwrap();
    let stale = CreatePage {
        definition_version: before.version,
        ..original.clone()
    };
    assert!(matches!(
        create(&store, &stale),
        Err(KernelError::DocumentChanged)
    ));
    let current = CreatePage {
        definition_version: target(&store).version,
        ..original
    };
    let file = store.vault().join(&receipt.rel_path);
    let source = std::fs::read_to_string(&file).unwrap();
    std::fs::write(&file, source.replace("first", "second")).unwrap();
    // Reading definitions is committed data only; creation verifies the known file.
    assert_eq!(target(&store).template, "first");
    assert!(matches!(
        create(&store, &current),
        Err(KernelError::DocumentChanged)
    ));
    store.reconcile_path(&receipt.rel_path).unwrap();
    assert_eq!(target(&store).template, "second");
    assert!(matches!(
        create(&store, &current),
        Err(KernelError::DocumentChanged)
    ));
    // Same UUID still returns the committed configuration receipt after its edit.
    assert_eq!(create(&store, &request).unwrap(), receipt);
}

#[test]
fn malformed_defaults_block_only_the_target_and_remain_editable() {
    let (_dir, store, original) = setup();
    enable(&store);
    for source in [
        "---\nfolder: ../bad\n---\nbody",
        "---\nfolder: true\n---\nbody",
        "---\nfolder: Cards\n---\n---\ngilune-id: forged\n---\n",
    ] {
        assert!(create(&store, &defaults(&store, "Settings/cards.md", source)).is_err());
    }
    let request = defaults(&store, "Settings/cards.md", "---\nfolder: Cards\n---\nbody");
    let receipt = create(&store, &request).unwrap();
    store.project().unwrap();
    assert!(create(
        &store,
        &defaults(&store, "Settings/duplicate.md", &request.source)
    )
    .is_err());
    let path = store.vault().join(&receipt.rel_path);
    let source = std::fs::read_to_string(&path).unwrap();
    std::fs::write(&path, source.replace("folder: Cards", "folder: ../bad")).unwrap();
    store.reconcile_path(&receipt.rel_path).unwrap();
    let bad = target(&store);
    assert!(bad.defaults_problem.is_some());
    assert_eq!(bad.defaults_path.as_deref(), Some("Settings/cards.md"));
    assert!(matches!(
        create(
            &store,
            &CreatePage {
                definition_version: bad.version,
                ..original
            }
        ),
        Err(KernelError::DocumentUnavailable { .. })
    ));
    assert!(store.page_definitions("org.example.cards").unwrap()[1]
        .defaults_problem
        .is_none());
    std::fs::write(&path, source).unwrap();
    store.reconcile_path(&receipt.rel_path).unwrap();
    assert!(target(&store).defaults_problem.is_none());
}

#[test]
fn offline_duplicate_defaults_survive_sync_and_never_choose_a_winner() {
    let (_a, store, _) = setup();
    let (_b, replica, _) = setup();
    enable(&store);
    enable(&replica);
    create(
        &store,
        &defaults(&store, "Settings/a.md", "---\nfolder: A\n---\nA"),
    )
    .unwrap();
    create(
        &replica,
        &defaults(&replica, "Settings/b.md", "---\nfolder: B\n---\nB"),
    )
    .unwrap();
    for (device, _) in store.operation_devices().unwrap() {
        for op in store.operations_after(&device, 0, 100).unwrap() {
            replica
                .apply_operation("fixture", &op, crate::limits::Limits::DEFAULT)
                .unwrap();
        }
    }
    replica.project().unwrap();
    assert!(target(&replica)
        .defaults_problem
        .unwrap()
        .contains("Multiple or conflicting"));
    assert!(replica.vault().join("Settings/a.md").exists());
    assert!(replica.vault().join("Settings/b.md").exists());
}

#[test]
fn deleting_defaults_requires_resolution_before_shipped_values_resume() {
    let (_dir, store, _) = setup();
    enable(&store);
    let baseline = target(&store);
    let receipt = create(&store, &defaults(&store, "Settings/cards.md", "---\nfolder: Custom\n---\ncustom")).unwrap();
    store.project().unwrap();
    std::fs::remove_file(store.vault().join(&receipt.rel_path)).unwrap();
    store.reconcile_path(&receipt.rel_path).unwrap();
    assert!(target(&store).defaults_problem.is_some());
    let proposal = store.open_proposals().unwrap().remove(0).0;
    store.resolve_tombstone(&proposal, true).unwrap();
    let restored = target(&store);
    assert!(restored.defaults_problem.is_none());
    assert!(restored.defaults_path.is_none());
    assert_eq!(restored.template, baseline.template);
    assert_eq!(restored.default_folder, baseline.default_folder);
}

#[test]
fn defaults_declarations_cannot_cross_owners_duplicate_targets_or_form_chains() {
    let (_dir, store, _) = setup();
    for target in ["org.other/card", "org.example.cards/defaults"] {
        let mut d = descriptor();
        d.contributions.push(Contribution { slot: TYPE_SLOT.into(), id: "org.example.cards/defaults".into(), label: "Defaults".into(),
            config: json!({"version":1,"schema":1,"default_folder":"","template":"","defaults_for":target}).to_string() });
        store.register_plugin(&d).unwrap();
        assert!(store.page_definitions("org.example.cards").is_err());
    }
    for second_target in ["org.example.cards/card", "org.example.cards/defaults"] {
        enable(&store);
        let mut d = store
            .plugin("org.example.cards")
            .unwrap()
            .unwrap()
            .descriptor;
        d.contributions.push(Contribution { slot: TYPE_SLOT.into(), id: "org.example.cards/second-defaults".into(), label: "Other defaults".into(),
            config: json!({"version":1,"schema":1,"default_folder":"","template":"","defaults_for":second_target}).to_string() });
        store.register_plugin(&d).unwrap();
        assert!(store.page_definitions("org.example.cards").is_err());
    }
}
