use super::*;

fn capture() -> (tempfile::TempDir, Store, String, String) {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let receipt = store.capture_bytes("txt", b"original capture\n").unwrap();
    store.project().unwrap();
    (dir, store, receipt.rel_path.replace(".txt", ".md"), receipt.source_id)
}
fn read(store: &Store, path: &str) -> String {
    std::fs::read_to_string(store.vault().join(path)).unwrap()
}
fn save(store: &Store, path: &str, text: &str) {
    std::fs::write(store.vault().join(path), text).unwrap();
    assert_eq!(store.reconcile_path(path).unwrap().edits, 1);
}
const PROPERTIES: &str = "# portable properties\nname: 'Alice # Smith' # keep quotes\nhandle: alice.smith\npreferences:\n  colours: [blue, green]\n  details: |\n    first: line\n    second line\nunknown: &prefs {nested: true}\ncopy: *prefs\n\n";

#[test]
fn properties_comments_and_body_survive_revisions_restart_and_rebuild() {
    let (dir, store, path, id) = capture();
    let initial = read(&store, &path);
    let edited = initial
        .replacen("---\n", &format!("---\n{PROPERTIES}"), 1)
        .replace("original capture\n", "# Freeform\n\nUnicode: 🦊 café\n");
    save(&store, &path, &edited);
    let payload = crate::revisions::head_payload(store.conn(), &id).unwrap().unwrap();
    let payload: serde_json::Value = serde_json::from_str(&payload).unwrap();
    assert!(payload[header::PAYLOAD_KEY]["source"].as_str().unwrap().contains(PROPERTIES));
    assert_eq!(payload["text"], "# Freeform\n\nUnicode: 🦊 café\n");
    assert_eq!(
        std::fs::read_to_string(store.vault().join(path.replace(".md", ".txt"))).unwrap(),
        "original capture\n"
    );
    store.project().unwrap();
    let projected = read(&store, &path);
    assert!(projected.contains(PROPERTIES));
    let snapshot = gilune_document::TextSnapshot::new(projected.clone()).unwrap();
    assert_eq!(
        snapshot.metadata_string("gilune-revision"),
        Some(crate::revisions::heads(store.conn(), &id).unwrap()[0].as_str())
    );
    assert_eq!(store.reconcile_path(&path).unwrap().edits, 0);
    drop(store);
    let reopened = Store::open(dir.path()).unwrap();
    // Rebuild from revisions without ingesting a deletion proposal.
    std::fs::remove_file(reopened.vault().join(&path)).unwrap();
    reopened.project().unwrap();
    assert_eq!(read(&reopened, &path), projected);
}

#[test]
fn crlf_bom_property_deletion_and_header_only_edits_round_trip() {
    let (_dir, store, path, _) = capture();
    let edited = format!(
        "\u{feff}{}",
        read(&store, &path)
            .replacen("---\n", &format!("---\n{PROPERTIES}"), 1)
            .replace('\n', "\r\n")
    );
    save(&store, &path, &edited);
    store.project().unwrap();
    let first = read(&store, &path);
    assert!(first.starts_with("\u{feff}---\r\n"));
    assert!(first.contains(&PROPERTIES.replace('\n', "\r\n")));
    assert!(!first.replace("\r\n", "").contains('\n'));
    let changed = first
        .replace("handle: alice.smith\r\n", "")
        .replace("name: 'Alice # Smith'", "name: 'Alice Jones'");
    save(&store, &path, &changed);
    store.project().unwrap();
    let result = read(&store, &path);
    assert!(!result.contains("handle:"));
    assert!(result.contains("name: 'Alice Jones' # keep quotes\r\n"));
    assert!(result.ends_with("original capture\r\n"));
    assert_eq!(store.reconcile_path(&path).unwrap().edits, 0);
}

#[test]
fn valid_foreign_yaml_stays_an_unmanaged_import() {
    let (_dir, store, _, _) = capture();
    let text = format!("---\n{PROPERTIES}---\nordinary note\n");
    std::fs::write(store.vault().join("foreign.md"), &text).unwrap();
    let result = store.reconcile_path("foreign.md").unwrap();
    assert_eq!((result.imports, result.quarantined), (1, 0));
    assert_eq!(
        store.document_snapshot("foreign.md").unwrap().source.state,
        crate::documents::DocumentState::Unmanaged
    );
    store.project().unwrap();
    assert_eq!(read(&store, "foreign.md"), text);
}

#[test]
fn malformed_or_structurally_coupled_managed_yaml_is_preserved_and_quarantined() {
    for injection in ["name: [broken\n", "gilune-revision: duplicate\n", "<<: {gilune-schema: 9}\n"]
    {
        let (_dir, store, path, _) = capture();
        let text = read(&store, &path).replacen("---\n", &format!("---\n{injection}"), 1);
        std::fs::write(store.vault().join(&path), &text).unwrap();
        assert_eq!(store.reconcile_path(&path).unwrap().quarantined, 1);
        store.project().unwrap();
        assert_eq!(read(&store, &path), text);
    }
}

#[test]
fn authoritative_fields_change_without_reformatting_adjacent_comments() {
    let (_dir, store, path, _) = capture();
    let text = read(&store, &path)
        .replace("gilune-schema: 1\n", "'gilune-schema': 99 # schema comment\n# adjacent comment\n")
        .replacen("---\n", "---\ngilune-conflicted: true # retain this comment\n", 1);
    save(&store, &path, &text);
    store.project().unwrap();
    let result = read(&store, &path);
    assert!(result.contains("'gilune-schema': 1 # schema comment\n# adjacent comment\n"));
    assert!(result.contains("# retain this comment\n"));
    assert!(!result.contains("gilune-conflicted:"));
}

#[test]
fn revision_operations_carry_the_envelope_to_another_store() {
    let (_dir, store, path, id) = capture();
    let edited = read(&store, &path).replacen("---\n", &format!("---\n{PROPERTIES}"), 1);
    save(&store, &path, &edited);
    store.project().unwrap();
    let replica_dir = tempfile::tempdir().unwrap();
    let replica = Store::open(replica_dir.path()).unwrap();
    for (device, _) in store.operation_devices().unwrap() {
        for operation in store.operations_after(&device, 0, 100).unwrap() {
            replica.apply_operation("test", &operation, crate::limits::Limits::DEFAULT).unwrap();
        }
    }
    replica.project().unwrap();
    let rel: String = replica
        .conn()
        .query_row("SELECT rel_path FROM projections WHERE object_id = ?1", [&id], |row| row.get(0))
        .unwrap();
    assert_eq!(read(&replica, &rel), read(&store, &path));
    assert_eq!(replica.reconcile_path(&rel).unwrap().edits, 0);
}

#[test]
fn invalid_retained_envelope_cannot_fall_back_to_a_lossy_render() {
    let (_dir, store, path, id) = capture();
    let before = read(&store, &path);
    let mut payload: serde_json::Value =
        serde_json::from_str(&crate::revisions::head_payload(store.conn(), &id).unwrap().unwrap())
            .unwrap();
    payload[header::PAYLOAD_KEY] = serde_json::json!("---\nbroken\n---\n");
    store
        .write_txn(|| {
            crate::revisions::add_revision(
                store.conn(),
                &id,
                "invalid-envelope",
                &payload.to_string(),
                "fixture",
                ids::now_ms(),
                Announce::Event,
            )
        })
        .unwrap();
    assert_eq!(store.project().unwrap().held_back, 1);
    assert_eq!(read(&store, &path), before);
}

#[test]
fn a_processor_revision_cannot_erase_or_replace_source_properties() {
    use crate::processor::{OutputTarget, ProposedOutput};
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    store.register_plugin(&crate::processor::fake_transcriber_descriptor()).unwrap();
    let receipt = store.capture_bytes("wav", &[1; 64]).unwrap();
    store.project().unwrap();
    let path = receipt.rel_path.replace(".wav", ".md");
    let edited = read(&store, &path).replacen("---\n", &format!("---\n{PROPERTIES}"), 1);
    save(&store, &path, &edited);
    let job = store.claim_next_job(ids::now_ms(), 30_000).unwrap().unwrap();
    store.deliver_outputs("transcribe", &job, &[ProposedOutput {
        type_id: "ignored".into(), type_schema: 1, target: OutputTarget::ReviseSubject,
        expected_head: None,
        payload: serde_json::json!({"text": "processed body\n", header::PAYLOAD_KEY: {"version": 1, "source": "---\nname: stolen\n---\n"}}).to_string(),
    }]).unwrap();
    store.project().unwrap();
    let result = read(&store, &path);
    assert!(result.contains(PROPERTIES));
    assert!(!result.contains("stolen"));
    assert!(result.ends_with("processed body\n"));
}


#[test]
fn foreign_yaml_can_use_flow_mappings_and_merges_without_adoption() {
    let (_dir, store, _, _) = capture();
    for (name, text) in [
        ("flow.md", "---\n{name: Example, tags: [a, b]}\n---\nbody\n"),
        ("merge.md", "---\ndefaults: &defaults {name: Example}\n<<: *defaults\n---\nbody\n"),
    ] {
        std::fs::write(store.vault().join(name), text).unwrap();
        let result = store.reconcile_path(name).unwrap();
        assert_eq!((result.imports, result.quarantined), (1, 0));
        assert_eq!(read(&store, name), text);
        assert_eq!(store.document_snapshot(name).unwrap().source.state, crate::documents::DocumentState::Unmanaged);
    }
}


#[test]
fn first_publication_cannot_replace_a_file_created_by_another_writer() {
    let dir=tempfile::tempdir().unwrap();let path=dir.path().join("race.md");
    std::fs::write(&path,"other writer").unwrap();
    assert!(!write_new_projection(&path,b"new page").unwrap());
    assert_eq!(std::fs::read_to_string(&path).unwrap(),"other writer");
    std::fs::remove_file(&path).unwrap();
    assert!(write_new_projection(&path,b"new page").unwrap());
    assert_eq!(std::fs::read_to_string(&path).unwrap(),"new page");
}
