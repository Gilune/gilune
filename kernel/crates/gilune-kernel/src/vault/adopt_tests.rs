//! Every file in the vault becomes something sync can carry (ADR 0007,
//! amendment 2026-10-22), pinned on one device. The two-device half —
//! that what is adopted here arrives there — is `gilune-sync`'s gate C.

use super::adopt::{ATTACHMENT_TYPE, LOCAL_MARKER, NOTE_TYPE};
use crate::changes::{ChangeCause, ChangeKind};
use crate::store::Store;

fn vault() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().expect("tempdir");
    let store = Store::open(dir.path()).expect("open");
    (dir, store)
}

fn count(store: &Store, table: &str) -> i64 {
    store
        .conn()
        .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0))
        .expect("count")
}

fn operations_of_kind(store: &Store, kind: &str) -> i64 {
    store
        .conn()
        .query_row("SELECT COUNT(*) FROM operations WHERE kind = ?1", [kind], |r| r.get(0))
        .expect("count")
}

fn type_of(store: &Store, object_id: &str) -> String {
    store
        .conn()
        .query_row("SELECT type_id FROM objects WHERE object_id = ?1", [object_id], |r| r.get(0))
        .expect("type")
}

fn feed(store: &Store) -> Vec<(ChangeKind, String, ChangeCause)> {
    store
        .changes_since(&store.change_origin().expect("origin"), 1000)
        .expect("changes")
        .changes
        .into_iter()
        .map(|c| (c.kind, c.rel_path, c.cause))
        .collect()
}

fn write(store: &Store, rel: &str, bytes: &[u8]) {
    let path = store.vault().join(rel);
    std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
    std::fs::write(path, bytes).expect("write");
}

fn read(store: &Store, rel: &str) -> String {
    std::fs::read_to_string(store.vault().join(rel)).expect("read")
}

// ------------------------------------------------------------- markdown

#[test]
fn a_plain_markdown_file_is_adopted_with_a_header_and_its_body_kept_byte_for_byte() {
    let (_dir, store) = vault();
    write(&store, "Notes/plan.md", b"just my notes\n- with a list\n");

    let summary = store.reconcile().expect("reconcile");
    assert_eq!((summary.adopted, summary.imports, summary.quarantined), (1, 0, 0));
    assert_eq!(count(&store, "objects"), 1);
    assert_eq!(operations_of_kind(&store, crate::operations::OBJECT_CREATED), 1, "one operation to carry");
    let object_id: String = store
        .conn()
        .query_row("SELECT object_id FROM objects", [], |r| r.get(0))
        .expect("the note");
    assert_eq!(type_of(&store, &object_id), NOTE_TYPE);

    let rendered = read(&store, "Notes/plan.md");
    assert!(rendered.starts_with(&format!("---\ngilune-id: {object_id}\n")), "{rendered}");
    assert!(rendered.ends_with("---\njust my notes\n- with a list\n"), "the body is untouched: {rendered}");
    assert_eq!(
        feed(&store),
        vec![(ChangeKind::Created, "Notes/plan.md".to_string(), ChangeCause::Reconcile)],
        "announced once, as a note the vault gained"
    );

    // Settled: nothing is read, written or announced again.
    assert_eq!(store.reconcile().expect("again"), super::ReconcileSummary::default());
    assert_eq!(store.project().expect("project").written, 0);
    assert_eq!(read(&store, "Notes/plan.md"), rendered);
    assert_eq!(feed(&store).len(), 1);

    // And an edit afterwards is the ordinary external edit of a note.
    write(&store, "Notes/plan.md", rendered.replace("just my notes", "my notes, revised").as_bytes());
    assert_eq!(store.reconcile().expect("edit").edits, 1);
    assert_eq!(operations_of_kind(&store, crate::operations::OBJECT_REVISED), 1);
}

#[test]
fn foreign_front_matter_survives_adoption_as_the_persons_own_keys() {
    let (_dir, store) = vault();
    write(&store, "Reading.md", b"---\ntitle: Dune\nrating: 5\n---\nworth it\n");
    assert_eq!(store.reconcile().expect("reconcile").adopted, 1);
    let rendered = read(&store, "Reading.md");
    assert!(rendered.contains("gilune-id: "), "{rendered}");
    assert!(rendered.contains("title: Dune\n"), "{rendered}");
    assert!(rendered.contains("rating: 5\n"), "{rendered}");
    assert!(rendered.ends_with("---\nworth it\n"), "{rendered}");
    let snapshot = gilune_document::TextSnapshot::new(rendered).expect("parses");
    assert_eq!(snapshot.metadata_string("title"), Some("Dune"));
}

#[test]
fn a_file_carrying_an_id_nobody_holds_is_adopted_under_that_id() {
    // The crash window after adoption wrote the header, and a file copied
    // from another device's vault, both look exactly like this.
    let (_dir, store) = vault();
    let id = "01999999-aaaa-7bbb-8ccc-ddddeeeeffff";
    write(
        &store,
        "Copied.md",
        format!("---\ngilune-id: {id}\ngilune-type: org.memory.note\ngilune-schema: 1\n---\ncarried over\n")
            .as_bytes(),
    );
    assert_eq!(store.reconcile().expect("reconcile").adopted, 1);
    assert_eq!(type_of(&store, id), NOTE_TYPE);
    assert!(read(&store, "Copied.md").ends_with("---\ncarried over\n"));
    assert_eq!(store.reconcile().expect("again"), super::ReconcileSummary::default());
}

#[test]
fn a_header_the_envelope_cannot_carry_losslessly_stays_a_candidate_and_stops_nothing() {
    let (_dir, store) = vault();
    write(&store, "Aliased.md", b"---\nbase: &b {name: Example}\n<<: *b\n---\nbody\n");
    write(&store, "Plain.md", b"plain\n");
    let summary = store.reconcile().expect("the pass survives the file it cannot adopt");
    assert_eq!((summary.adopted, summary.imports, summary.quarantined), (1, 1, 0), "{summary:?}");
    assert_eq!(read(&store, "Aliased.md"), "---\nbase: &b {name: Example}\n<<: *b\n---\nbody\n");
    assert!(read(&store, "Plain.md").starts_with("---\ngilune-id: "));
}

// ---------------------------------------------------------- local-only

#[test]
fn a_local_only_file_is_never_adopted_whether_marked_by_key_or_by_folder() {
    let (_dir, store) = vault();
    write(&store, "Diary/today.md", b"---\ngilune-sync: local\n---\nmine alone\n");
    write(&store, "Private/.gilune-local", b"");
    write(&store, "Private/Deep/secret.md", b"also mine\n");
    write(&store, "Private/photo.png", b"PNGbytes");

    let summary = store.reconcile().expect("reconcile");
    assert_eq!((summary.adopted, summary.attachments), (0, 0), "{summary:?}");
    assert_eq!(summary.imports, 2, "both notes are candidates for the index");
    assert_eq!(count(&store, "objects"), 0);
    assert_eq!(count(&store, "operations"), 0, "nothing of them can leave");
    assert_eq!(read(&store, "Diary/today.md"), "---\ngilune-sync: local\n---\nmine alone\n");
    assert_eq!(read(&store, "Private/Deep/secret.md"), "also mine\n");

    // Taking the mark off adopts the note on the next pass.
    write(&store, "Diary/today.md", b"mine, now shared\n");
    assert_eq!(store.reconcile().expect("reconcile").adopted, 1);
    std::fs::remove_file(store.vault().join("Private").join(LOCAL_MARKER)).expect("unmark");
    let summary = store.reconcile().expect("reconcile");
    assert_eq!((summary.adopted, summary.attachments), (1, 1));
    assert_eq!(count(&store, "objects"), 3);
}

#[test]
fn a_synced_note_entering_local_scope_sends_nothing_and_leaving_sends_one_revision() {
    let (_dir, store) = vault();
    let receipt = store.capture_bytes("txt", b"shared at first").expect("capture");
    store.project().expect("project");
    let rel = receipt.rel_path.replace(".txt", ".md");
    let object_id = receipt.source_id.clone();
    let first_head = crate::revisions::heads(store.conn(), &object_id).expect("heads")[0].clone();
    assert_eq!(count(&store, "operations"), 1);

    // The person adds the mark to the note's own header.
    let rendered = read(&store, &rel);
    let marked = rendered.replacen("---\n", "---\ngilune-sync: local\n", 1).replace("shared at first", "edited in private");
    write(&store, &rel, marked.as_bytes());
    assert_eq!(store.reconcile().expect("reconcile").edits, 1);
    assert!(store.is_local_object(&object_id).expect("local"), "in local scope");
    assert_eq!(count(&store, "operations"), 1, "the edit left no operation");
    assert_eq!(count(&store, "revisions"), 2, "but the revision is real");
    let sent_before: String = store
        .conn()
        .query_row("SELECT last_sent_revision FROM local_objects WHERE object_id = ?1", [&object_id], |r| r.get(0))
        .expect("remembered");
    assert_eq!(sent_before, first_head, "the head peers were sent");

    // A second private edit: still nothing leaves.
    let again = read(&store, &rel).replace("edited in private", "edited twice");
    write(&store, &rel, again.as_bytes());
    store.reconcile().expect("reconcile");
    assert_eq!(count(&store, "operations"), 1);

    // Taking the mark off sends ONE revision that supersedes the head
    // peers last saw, then the edit as an ordinary revision over it.
    let unmarked = read(&store, &rel).replace("gilune-sync: local\n", "").replace("edited twice", "back in the open");
    write(&store, &rel, unmarked.as_bytes());
    store.reconcile().expect("reconcile");
    assert!(!store.is_local_object(&object_id).expect("local"));
    let ops: Vec<(String, String)> = {
        let mut stmt = store
            .conn()
            .prepare("SELECT kind, payload FROM operations WHERE target_id = ?1 ORDER BY op_seq")
            .expect("prepare");
        let rows = stmt.query_map([&object_id], |r| Ok((r.get(0)?, r.get(1)?))).expect("rows");
        rows.collect::<Result<_, _>>().expect("ops")
    };
    assert_eq!(ops.len(), 3, "created, rejoin, edit: {ops:?}");
    let rejoin: serde_json::Value = serde_json::from_str(&ops[1].1).expect("json");
    assert_eq!(
        rejoin["supersedes"],
        serde_json::json!([first_head]),
        "peers are told it replaces what they hold, never the private history"
    );
    assert!(ops[2].1.contains("back in the open"));
}

#[test]
fn deleting_a_local_note_tells_nobody() {
    let (_dir, store) = vault();
    let receipt = store.capture_bytes("txt", b"to be kept here then deleted").expect("capture");
    store.project().expect("project");
    let rel = receipt.rel_path.replace(".txt", ".md");
    let marked = read(&store, &rel).replacen("---\n", "---\ngilune-sync: local\n", 1);
    write(&store, &rel, marked.as_bytes());
    store.reconcile().expect("reconcile");
    std::fs::remove_file(store.vault().join(&rel)).expect("delete");
    store.reconcile().expect("reconcile");
    for (proposal, _) in store.open_proposals().expect("proposals") {
        store.resolve_tombstone(&proposal, true).expect("accept");
    }
    assert_eq!(operations_of_kind(&store, crate::operations::TOMBSTONE), 0);
    assert!(!store.is_local_object(&receipt.source_id).expect("local"), "the row goes with the object");
}

// ---------------------------------------------------------- attachments

#[test]
fn an_attachment_is_adopted_in_place_and_an_edit_is_a_new_revision_with_a_new_blob() {
    let (_dir, store) = vault();
    write(&store, "Pictures/a.png", b"\x89PNG not really");

    let summary = store.reconcile().expect("reconcile");
    assert_eq!((summary.attachments, summary.adopted), (1, 0));
    let object_id: String = store
        .conn()
        .query_row("SELECT object_id FROM objects", [], |r| r.get(0))
        .expect("the attachment");
    assert_eq!(type_of(&store, &object_id), ATTACHMENT_TYPE);
    assert_eq!(std::fs::read(store.vault().join("Pictures/a.png")).expect("bytes"), b"\x89PNG not really");
    let (rel, state, origin): (String, String, String) = store
        .conn()
        .query_row("SELECT rel_path, state, origin FROM blobs", [], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
        .expect("blob row");
    assert_eq!((rel.as_str(), state.as_str(), origin.as_str()), ("Pictures/a.png", "ready", "local"));
    let created: String = store
        .conn()
        .query_row("SELECT payload FROM operations WHERE kind = 'object-created'", [], |r| r.get(0))
        .expect("op");
    assert!(created.contains("\"blob\""), "the operation declares the bytes: {created}");
    assert_eq!(
        feed(&store),
        vec![(ChangeKind::Created, "Pictures/a.png".to_string(), ChangeCause::Reconcile)]
    );

    // No note is projected for it, and nothing is re-read when settled.
    store.project().expect("project");
    assert!(!store.vault().join("Pictures/a.md").exists());
    crate::probe::reset_file_reads();
    assert_eq!(store.reconcile().expect("again"), super::ReconcileSummary::default());
    assert_eq!(crate::probe::file_reads(), 0, "the stat rule answers for an unchanged file");

    // Edited in place: a new blob, a new revision, one operation that
    // carries the new bytes' declaration.
    std::thread::sleep(std::time::Duration::from_millis(20));
    write(&store, "Pictures/a.png", b"\x89PNG edited");
    let summary = store.reconcile().expect("reconcile");
    assert_eq!(summary.edits, 1, "{summary:?}");
    assert_eq!(count(&store, "blobs"), 2);
    assert_eq!(count(&store, "revisions"), 2);
    let revised: String = store
        .conn()
        .query_row("SELECT payload FROM operations WHERE kind = 'object-revised'", [], |r| r.get(0))
        .expect("op");
    assert!(revised.contains("\"blob\""), "{revised}");
    let head = crate::revisions::head_payload(store.conn(), &object_id).expect("head").expect("some");
    assert!(head.contains(&crate::ids::sha256_hex(b"\x89PNG edited")), "the head names the new bytes");
}

#[test]
fn a_missing_attachment_is_a_tombstone_proposal_and_dot_files_are_nobodys() {
    let (_dir, store) = vault();
    write(&store, "Docs/paper.pdf", b"%PDF");
    write(&store, ".obsidian/workspace.json", b"{}");
    write(&store, "Docs/.hidden.bin", b"x");
    write(&store, "Docs/.tmp-partial.pdf", b"x");
    let summary = store.reconcile().expect("reconcile");
    assert_eq!(summary.attachments, 1, "only the paper: {summary:?}");

    std::fs::remove_file(store.vault().join("Docs/paper.pdf")).expect("delete");
    let summary = store.reconcile().expect("reconcile");
    assert_eq!(summary.tombstone_proposals, 1);
    assert_eq!(store.open_proposals().expect("proposals").len(), 1);
    assert_eq!(store.reconcile().expect("again").tombstone_proposals, 0, "proposed once");
}

#[test]
fn a_recordings_bytes_are_a_source_and_never_an_attachment() {
    let (_dir, store) = vault();
    let receipt = store.capture_bytes("wav", b"RIFF....").expect("capture");
    store.project().expect("project");
    assert_eq!(count(&store, "objects"), 1);
    let summary = store.reconcile().expect("reconcile");
    assert_eq!((summary.attachments, summary.edits), (0, 0), "{summary:?}");
    assert_eq!(count(&store, "objects"), 1, "the recording is its capture, not a second object");
    assert!(store.vault().join(&receipt.rel_path).exists());
}

// ------------------------------------------------- the path both want

/// **This device's own note is never the one held back.** A note that
/// arrived from elsewhere and wants the same path as a note made here
/// that has no file yet goes beside — whatever order the two ids sort
/// in. The arrival here is given an id that sorts first, which is the
/// order that used to leave the person's own note in history with no
/// file.
#[test]
fn an_arrival_that_sorts_first_still_goes_beside_this_devices_own_note() {
    let (_dir, store) = vault();
    let receipt = store.capture_bytes("txt", b"made here, not yet projected").expect("capture");
    let own_path = receipt.rel_path.replace(".txt", ".md");

    let arrival = "00000000-0000-7000-8000-000000000001";
    let payload = serde_json::json!({
        "text": "arrived from elsewhere\n",
        "path": own_path,
        super::header::PAYLOAD_KEY: { "version": 1, "source": "---\n---\n" },
    })
    .to_string();
    let bytes = crate::operations::encode(&crate::operations::Envelope {
        version: crate::operations::ENVELOPE_VERSION,
        op_id: "elsewhere-1".to_string(),
        device_id: "device-elsewhere".to_string(),
        op_seq: 1,
        kind: crate::operations::OBJECT_CREATED.to_string(),
        target_id: arrival.to_string(),
        created_ms: 1_700_000_000_000,
        payload: serde_json::json!({
            "object": { "object_id": arrival, "type_id": NOTE_TYPE, "type_schema": 1, "created_ms": 1_700_000_000_000i64 },
            "revision": { "revision_id": "elsewhere-rev-1", "payload": payload, "origin": "external-import", "created_ms": 1_700_000_000_000i64 },
        }),
    });
    store.apply_operation("test", &bytes, crate::limits::Limits::DEFAULT).expect("applies");

    let summary = store.project().expect("project");
    assert_eq!(summary.held_back, 0, "{summary:?}");
    assert!(read(&store, &own_path).ends_with("made here, not yet projected"), "the own note has its path");
    let beside = own_path.replace(".md", &format!(" (from {}).md", crate::ids::short("device-elsewhere")));
    assert!(read(&store, &beside).ends_with("arrived from elsewhere\n"), "the arrival went beside: {beside}");
}
