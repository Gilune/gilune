use super::*;
use crate::plugins::{Contribution, PluginClass, PluginDescriptor, PluginProfile};

pub(super) fn descriptor() -> PluginDescriptor {
    PluginDescriptor {
        plugin_id:"org.example.cards".into(),name:"Cards fixture".into(),class:PluginClass::Domain,
        profile:PluginProfile::Bundled,required:false,processor_id:None,subscribes:None,
        emits:vec![],capabilities:vec![CREATE_CAPABILITY.into()],
        contributions:vec![Contribution {slot:TYPE_SLOT.into(),id:"org.example.cards/card".into(),label:"Card".into(),
            config:include_str!("../../../../../contracts/pages/fixtures/card-v1.json").into()}],
    }
}
pub(super) fn setup() -> (tempfile::TempDir, Store, CreatePage) {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let d = descriptor();
    store.register_plugin(&d).unwrap();
    store.grant_capability(&d.plugin_id, CREATE_CAPABILITY).unwrap();
    let definition = store.page_definitions(&d.plugin_id).unwrap().remove(0);
    let request = CreatePage {
        type_id: definition.type_id,
        definition_version: definition.version,
        request_id: ids::new_id(),
        rel_path: "Cards/example.md".into(),
        source: definition.template,
    };
    (dir, store, request)
}
fn create(store: &Store, request: &CreatePage) -> Result<PageReceipt> {
    store.create_page("org.example.cards", request)
}

#[test]
fn creation_is_durable_before_publication_and_retries_do_not_duplicate_or_revert() {
    let (dir, store, request) = setup();
    let receipt = create(&store, &request).unwrap();
    assert!(!store.vault().join(&receipt.rel_path).exists());
    assert_eq!(store.revision_count(&receipt.object_id).unwrap(), 1);
    drop(store);
    let store = Store::open(dir.path()).unwrap();
    assert_eq!(create(&store, &request).unwrap(), receipt);
    store.project().unwrap();
    let before = std::fs::read_to_string(store.vault().join(&receipt.rel_path)).unwrap();
    assert!(before.contains("name: Example\nunknown: {nested: true}\n"));
    let snapshot = store.document_snapshot(&receipt.rel_path).unwrap();
    assert_eq!(snapshot.source.object_id.as_deref(), Some(receipt.object_id.as_str()));
    assert_eq!(snapshot.content.metadata_string("gilune-type"), Some(request.type_id.as_str()));
    std::fs::write(
        store.vault().join(&receipt.rel_path),
        before.replace("name: Example", "name: Edited"),
    )
    .unwrap();
    store.reconcile_path(&receipt.rel_path).unwrap();
    store.project().unwrap();
    assert_eq!(create(&store, &request).unwrap(), receipt);
    assert_eq!(store.revision_count(&receipt.object_id).unwrap(), 2);
    assert!(std::fs::read_to_string(store.vault().join(&receipt.rel_path))
        .unwrap()
        .contains("name: Edited"));
    let mut reused = request;
    reused.source.push_str("different");
    assert!(matches!(create(&store, &reused), Err(KernelError::DocumentInvalid { .. })));
}
#[test]
fn undeclared_foreign_stale_disabled_removed_and_ungranted_creation_is_refused() {
    let (_dir, store, request) = setup();
    let mut foreign = request.clone();
    foreign.type_id = "org.other/card".into();
    assert!(create(&store, &foreign).is_err());
    let mut stale = request.clone();
    stale.definition_version = "old".into();
    assert!(matches!(create(&store, &stale), Err(KernelError::DocumentChanged)));
    store.set_plugin_enabled("org.example.cards", false).unwrap();
    assert!(create(&store, &request).is_err());
    store.set_plugin_enabled("org.example.cards", true).unwrap();
    store
        .conn()
        .execute("DELETE FROM plugin_grants WHERE plugin_id='org.example.cards'", [])
        .unwrap();
    assert!(create(&store, &request).is_err());
    store.remove_plugin("org.example.cards").unwrap();
    assert!(create(&store, &request).is_err());
    assert_eq!(
        store.conn().query_row("SELECT count(*) FROM objects", [], |r| r.get::<_, i64>(0)).unwrap(),
        0
    );
}
#[test]
fn namespace_claims_and_duplicate_definitions_are_rejected() {
    let (_dir, store, _) = setup();
    let mut d = descriptor();
    d.contributions[0].id = "org.other/card".into();
    store.register_plugin(&d).unwrap();
    assert!(store.page_definitions(&d.plugin_id).is_err());
    d = descriptor();
    d.contributions.push(d.contributions[0].clone());
    store.register_plugin(&d).unwrap();
    assert!(store.page_definitions(&d.plugin_id).is_err());
}
#[test]
fn source_identity_and_unsafe_paths_are_refused_without_side_effects() {
    let (_dir, store, request) = setup();
    for source in [
        "---\ngilune-id: forged\n---\nbody",
        "---\nGILUNE-type: forged\n---\nbody",
        "---\nbad: [\n---\n",
    ] {
        let mut r = request.clone();
        r.source = source.into();
        assert!(create(&store, &r).is_err());
    }
    for path in [
        "../bad.md",
        ".gilune/bad.md",
        "C:/bad.md",
        "CON.md",
        "Cards/bad.md:stream",
        "Cards/bad?.md",
        "Cards/../bad.md",
    ] {
        let mut r = request.clone();
        r.rel_path = path.into();
        assert!(create(&store, &r).is_err());
    }
    assert_eq!(
        store.conn().query_row("SELECT count(*) FROM objects", [], |r| r.get::<_, i64>(0)).unwrap(),
        0
    );
}
#[test]
fn reserved_paths_and_files_appearing_after_commit_are_never_replaced() {
    let (_dir, store, request) = setup();
    let receipt = create(&store, &request).unwrap();
    let mut other = request.clone();
    other.request_id = ids::new_id();
    assert!(create(&store, &other).is_err());
    std::fs::create_dir_all(store.vault().join("Cards")).unwrap();
    std::fs::write(store.vault().join(&receipt.rel_path), "external work").unwrap();
    assert_eq!(store.project().unwrap().held_back, 1);
    assert_eq!(
        store.integrity_alerts_of(crate::alerts::VAULT_PROJECTION_PATH_OCCUPIED).unwrap()[0]
            .subject,
        receipt.object_id
    );
    assert_eq!(
        std::fs::read_to_string(store.vault().join(&receipt.rel_path)).unwrap(),
        "external work"
    );
    // Removing the conflicting file allows recovery of the committed page.
    std::fs::remove_file(store.vault().join(&receipt.rel_path)).unwrap();
    store.project().unwrap();
    assert!(store.document_snapshot(&receipt.rel_path).unwrap().source.object_id.is_some());
    assert!(store
        .integrity_alerts_of(crate::alerts::VAULT_PROJECTION_PATH_OCCUPIED)
        .unwrap()
        .is_empty());
}
#[test]
fn a_page_projects_on_a_replica_without_its_plugin_and_retains_identity_after_a_move() {
    let (_dir, store, request) = setup();
    let receipt = create(&store, &request).unwrap();
    store.project().unwrap();
    let target = tempfile::tempdir().unwrap();
    let replica = Store::open(target.path()).unwrap();
    for (device, _) in store.operation_devices().unwrap() {
        for op in store.operations_after(&device, 0, 100).unwrap() {
            replica.apply_operation("fixture", &op, crate::limits::Limits::DEFAULT).unwrap();
        }
    }
    assert!(replica.installed_plugins().unwrap().is_empty());
    replica.project().unwrap();
    assert_eq!(
        std::fs::read(store.vault().join(&receipt.rel_path)).unwrap(),
        std::fs::read(replica.vault().join(&receipt.rel_path)).unwrap()
    );
    std::fs::rename(store.vault().join(&receipt.rel_path), store.vault().join("moved.md")).unwrap();
    store.reconcile_path("moved.md").unwrap();
    store.project().unwrap();
    assert_eq!(
        store.document_snapshot("moved.md").unwrap().source.object_id,
        Some(receipt.object_id)
    );
}
#[test]
fn bom_and_crlf_plain_templates_become_pages_without_rewriting_the_body() {
    let (_dir, store, mut request) = setup();
    request.source = "\u{feff}hello 🦊\r\nworld\r\n".into();
    let receipt = create(&store, &request).unwrap();
    store.project().unwrap();
    let snapshot = store.document_snapshot(&receipt.rel_path).unwrap();
    assert!(snapshot.content.text().starts_with("\u{feff}---\r\n"));
    assert_eq!(snapshot.content.body(), "hello 🦊\r\nworld\r\n");
}

#[test]
fn oversized_encoded_pages_roll_back_the_object_revision_and_operation() {
    let (_dir, store, mut request) = setup();
    request.source = "\0".repeat(400_000);
    assert!(create(&store, &request).is_err());
    for table in ["objects", "revisions", "operations", "events"] {
        let count: i64 = store
            .conn()
            .query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 0);
    }
}
