use super::*;
use crate::pages::tests::{descriptor, setup};
use crate::plugins::Contribution;

fn enable(store: &Store) {
    let mut d = descriptor();
    d.contributions.push(Contribution {
        slot: TYPE_SLOT.into(),
        id: "org.example.cards/setup".into(),
        label: "Setup".into(),
        config: json!({"version":1,"schema":1,"default_folder":"Settings","template":""})
            .to_string(),
    });
    d.contributions.push(Contribution { slot: INITIALIZERS_SLOT.into(), id: "org.example.cards/initial".into(), label: "Initial cards".into(),
        config: json!({"version":1,"receipt_type":"org.example.cards/setup","receipt_file":"cards.md","pages":[
            {"key":"one","type_id":"org.example.cards/card","relative_file":"one.md","properties":{"name":"\"One\""}},
            {"key":"two","type_id":"org.example.cards/card","relative_file":"two.md","properties":{"name":"\"Two\""}}
        ]}).to_string() });
    store.register_plugin(&d).unwrap();
}
fn initialize(store: &Store) -> Result<Vec<PageInitialization>> {
    store.initialize_pages("org.example.cards")
}
fn count(store: &Store, table: &str) -> i64 {
    store
        .conn()
        .query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0))
        .unwrap()
}

#[test]
fn initialization_is_atomic_and_retries_preserve_changes_deletions_and_receipt_edits() {
    let (dir, store, _) = setup();
    enable(&store);
    assert_eq!(initialize(&store).unwrap()[0].status, "initialized");
    assert_eq!(count(&store, "objects"), 3);
    assert!(!store.vault().join("Cards/one.md").exists());
    store.project().unwrap();
    let file = store.vault().join("Cards/one.md");
    std::fs::write(
        &file,
        std::fs::read_to_string(&file)
            .unwrap()
            .replace("One", "Renamed"),
    )
    .unwrap();
    store.reconcile_path("Cards/one.md").unwrap();
    std::fs::remove_file(store.vault().join("Cards/two.md")).unwrap();
    store.reconcile_path("Cards/two.md").unwrap();
    for (proposal, _) in store.open_proposals().unwrap() {
        store.resolve_tombstone(&proposal, true).unwrap();
    }
    std::fs::remove_file(store.vault().join("Settings/cards.md")).unwrap();
    store.reconcile_path("Settings/cards.md").unwrap();
    for (proposal, _) in store.open_proposals().unwrap() {
        store.resolve_tombstone(&proposal, true).unwrap();
    }
    drop(store);
    let store = Store::open(dir.path()).unwrap();
    enable(&store);
    crate::probe::reset_file_reads();
    assert_eq!(initialize(&store).unwrap()[0].status, "recorded");
    assert_eq!(crate::probe::file_reads(), 0);
    assert_eq!(count(&store, "objects"), 3);
    store.project().unwrap();
    assert!(!store.vault().join("Cards/two.md").exists());
    assert!(std::fs::read_to_string(&file).unwrap().contains("Renamed"));
    store
        .set_plugin_enabled("org.example.cards", false)
        .unwrap();
    assert!(initialize(&store).is_err());
    store.set_plugin_enabled("org.example.cards", true).unwrap();
    assert_eq!(initialize(&store).unwrap()[0].status, "recorded");
}

#[test]
fn failure_after_one_prepared_page_rolls_back_the_entire_initialization() {
    let (_dir, store, _) = setup();
    enable(&store);
    std::fs::create_dir_all(store.vault().join("Cards")).unwrap();
    std::fs::write(store.vault().join("Cards/two.md"), "unrelated work").unwrap();
    assert!(initialize(&store).is_err());
    assert_eq!(count(&store, "objects"), 0);
    assert_eq!(count(&store, "revisions"), 0);
    assert_eq!(count(&store, "operations"), 0);
    std::fs::remove_file(store.vault().join("Cards/two.md")).unwrap();
    assert_eq!(initialize(&store).unwrap()[0].status, "initialized");
    assert_eq!(count(&store, "objects"), 3);
}

#[test]
fn existing_or_deleted_catalogue_history_prevents_backfilling_defaults() {
    let (_dir, store, request) = setup();
    let receipt = store.create_page("org.example.cards", &request).unwrap();
    store.project().unwrap();
    std::fs::remove_file(store.vault().join(&receipt.rel_path)).unwrap();
    store.reconcile_path(&receipt.rel_path).unwrap();
    for (proposal, _) in store.open_proposals().unwrap() {
        store.resolve_tombstone(&proposal, true).unwrap();
    }
    enable(&store);
    std::fs::write(store.vault().join("unrelated-broken.md"), "---\ninvalid: [\n---\n").unwrap();
    assert_eq!(initialize(&store).unwrap()[0].status, "preserved-existing");
    assert_eq!(count(&store, "objects"), 2);
    store.project().unwrap();
    assert!(!store.vault().join("Cards/one.md").exists());
}

#[test]
fn unmanaged_exported_definitions_use_scalar_semantics_without_adoption() {
    let (_dir, store, _) = setup();
    enable(&store);
    std::fs::write(store.vault().join("broken.md"), "---\ninvalid: [\n---\n").unwrap();
    assert!(initialize(&store).is_err());
    assert_eq!(count(&store, "objects"), 0);
    std::fs::remove_file(store.vault().join("broken.md")).unwrap();
    let source = "---\ngilune-id: exported-card\ngilune-type: \"org.example.cards/card\" # exported\nname: Custom\n---\n";
    std::fs::write(store.vault().join("custom.md"), source).unwrap();
    assert_eq!(initialize(&store).unwrap()[0].status, "preserved-existing");
    assert_eq!(count(&store, "objects"), 1); // Only the receipt.
    assert_eq!(
        std::fs::read_to_string(store.vault().join("custom.md")).unwrap(),
        source
    );
}

#[test]
fn synced_receipts_and_partial_delivery_do_not_reseed_and_offline_runs_surface_collisions() {
    let (_a, a, _) = setup();
    enable(&a);
    initialize(&a).unwrap();
    let (_b, b, _) = setup();
    enable(&b);
    let device = a.device_id().unwrap();
    let ops = a.operations_after(&device, 0, 100).unwrap();
    // One seed arrives before its receipt; existing target history is enough.
    b.apply_operation("fixture", &ops[0], crate::limits::Limits::DEFAULT)
        .unwrap();
    assert_eq!(initialize(&b).unwrap()[0].status, "preserved-existing");
    for op in &ops {
        b.apply_operation("fixture", op, crate::limits::Limits::DEFAULT)
            .unwrap();
    }
    assert_eq!(initialize(&b).unwrap()[0].status, "recorded");
    assert_eq!(count(&b, "objects"), 4); // Two seeds, two honest initialization receipts.
    let (_c, c, _) = setup();
    enable(&c);
    initialize(&c).unwrap();
    for op in &ops {
        c.apply_operation("fixture", op, crate::limits::Limits::DEFAULT)
            .unwrap();
    }
    assert_eq!(count(&c, "objects"), 6);
    assert!(c.project().unwrap().held_back > 0);
    assert!(!c
        .integrity_alerts_of(crate::alerts::VAULT_PROJECTION_PATH_OCCUPIED)
        .unwrap()
        .is_empty());
    assert_eq!(initialize(&c).unwrap()[0].status, "recorded");
}

#[test]
fn missing_grants_foreign_types_and_duplicate_receipts_are_refused() {
    let (_dir, store, _) = setup();
    enable(&store);
    store
        .revoke_capability("org.example.cards", CREATE_CAPABILITY)
        .unwrap();
    assert!(initialize(&store).is_err());
    store
        .grant_capability("org.example.cards", CREATE_CAPABILITY)
        .unwrap();
    let mut d = store
        .plugin("org.example.cards")
        .unwrap()
        .unwrap()
        .descriptor;
    let mut config: Value = serde_json::from_str(&d.contributions.last().unwrap().config).unwrap();
    config["pages"][0]["type_id"] = json!("org.other/type");
    d.contributions.last_mut().unwrap().config = config.to_string();
    store.register_plugin(&d).unwrap();
    assert!(initialize(&store).is_err());
    assert_eq!(count(&store, "objects"), 0);
}
