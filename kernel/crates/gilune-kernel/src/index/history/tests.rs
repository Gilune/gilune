//! Line history (ADR 0025), pinned against real revisions: what an
//! adoption leaves undated, what an edit dates, and that the cache extends
//! to the same answer a replay from the beginning gives.

use super::{carry, replay, Revision};
use crate::index::body_of;
use crate::store::Store;

fn vault() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().expect("tempdir");
    let store = Store::open(dir.path()).expect("open");
    (dir, store)
}

fn write(store: &Store, rel: &str, text: &str) {
    std::fs::write(store.vault().join(rel), text).expect("write");
}

fn body(store: &Store, rel: &str) -> String {
    body_of(&std::fs::read_to_string(store.vault().join(rel)).expect("read"))
}

/// Replace the body under the header the kernel wrote, as another app would.
fn edit(store: &Store, rel: &str, new_body: &str) {
    let text = std::fs::read_to_string(store.vault().join(rel)).expect("read");
    let old = body_of(&text);
    let header = text.strip_suffix(old.as_str()).expect("body is the tail");
    write(store, rel, &format!("{header}{new_body}"));
}

/// Every revision of the one object, re-dated in order to `first`, `first
/// + 1000`, … so a test can name the day each line was written.
fn redate(store: &Store, first: i64) {
    let ids: Vec<String> = {
        let mut statement = store
            .conn()
            .prepare("SELECT revision_id FROM revisions ORDER BY created_ms, revision_id")
            .expect("prepare");
        let rows = statement.query_map([], |row| row.get(0)).expect("query");
        rows.map(|row| row.expect("row")).collect()
    };
    for (index, id) in ids.iter().enumerate() {
        store
            .conn()
            .execute(
                "UPDATE revisions SET created_ms = ?1 WHERE revision_id = ?2",
                rusqlite::params![first + 1000 * index as i64, id],
            )
            .expect("redate");
    }
    store.with_index(|db| Ok(db.execute("DELETE FROM line_history", [])?)).expect("forget cache");
}

fn history(store: &Store, rel: &str) -> Vec<Option<i64>> {
    store.line_history(rel, &body(store, rel))
}

fn reconcile(store: &Store) {
    store.reconcile().expect("reconcile");
}

#[test]
fn an_adopted_note_is_undated_and_an_edit_dates_only_the_lines_it_wrote() {
    let (_dir, store) = vault();
    write(&store, "list.md", "- [ ] milk\n- [ ] bread\n");
    reconcile(&store);
    assert_eq!(history(&store, "list.md"), vec![None, None, None], "adoption says nothing about when");

    edit(&store, "list.md", "- [ ] milk\n- [x] bread\n");
    reconcile(&store);
    redate(&store, 10_000);
    let dated = history(&store, "list.md");
    assert_eq!(dated[0], None, "an untouched line keeps the adoption's silence");
    assert_eq!(dated[1], Some(11_000), "the tick is dated by the revision that recorded it");
}

#[test]
fn an_unrelated_edit_moves_no_date_and_a_later_tick_moves_its_own() {
    let (_dir, store) = vault();
    write(&store, "list.md", "- [ ] milk\n");
    reconcile(&store);
    edit(&store, "list.md", "- [x] milk\n- [ ] eggs\n");
    reconcile(&store);
    edit(&store, "list.md", "intro\n- [x] milk\n- [x] eggs\n");
    reconcile(&store);
    redate(&store, 0);
    assert_eq!(history(&store, "list.md"), vec![Some(2_000), Some(1_000), Some(2_000), None]);
}

#[test]
fn lines_on_disk_that_no_revision_recorded_are_unknown() {
    let (_dir, store) = vault();
    write(&store, "list.md", "- [ ] milk\n");
    reconcile(&store);
    edit(&store, "list.md", "- [x] milk\n");
    reconcile(&store);
    redate(&store, 0);
    let recorded = body(&store, "list.md");
    let unrecorded = format!("{recorded}- [x] typed just now\n");
    let dated = store.line_history("list.md", &unrecorded);
    assert_eq!(dated[0], Some(1_000));
    assert_eq!(dated[1], None, "not yet recorded, so not yet dated");
}

#[test]
fn the_cache_extends_to_what_a_replay_from_the_beginning_gives() {
    let (_dir, store) = vault();
    write(&store, "list.md", "- [ ] a\n");
    reconcile(&store);
    for step in ["- [x] a\n", "- [x] a\n- [ ] b\n", "- [x] a\n- [x] b\n"] {
        edit(&store, "list.md", step);
        reconcile(&store);
    }
    redate(&store, 0);
    let warm = history(&store, "list.md");
    edit(&store, "list.md", "- [x] a\n- [x] b\n- [ ] c\n");
    reconcile(&store);
    let extended = history(&store, "list.md");
    store.with_index(|db| Ok(db.execute("DELETE FROM line_history", [])?)).expect("forget cache");
    assert_eq!(extended, history(&store, "list.md"), "incremental equals rebuilt");
    assert_eq!(&extended[..2], &warm[..2], "the older lines keep their dates");
}

#[test]
fn a_synced_revision_older_than_the_cache_replays_from_the_beginning() {
    let (_dir, store) = vault();
    write(&store, "list.md", "- [ ] a\n");
    reconcile(&store);
    edit(&store, "list.md", "- [x] a\n");
    reconcile(&store);
    redate(&store, 5_000);
    assert_eq!(history(&store, "list.md")[0], Some(6_000));
    // A revision arriving late from another device, dated before the
    // cached replay's last one, rewrites the past: the tick was earlier.
    let object: String = store
        .conn()
        .query_row("SELECT object_id FROM projections WHERE rel_path = 'list.md'", [], |r| r.get(0))
        .expect("object");
    store
        .conn()
        .execute(
            "INSERT INTO revisions (revision_id, object_id, payload, origin, created_ms)
             VALUES ('zz-late', ?1, '{\"text\":\"- [x] a\\n\"}', 'sync', 5500)",
            [&object],
        )
        .expect("insert");
    assert_eq!(history(&store, "list.md")[0], Some(5_500), "the cache noticed and replayed");
}

#[test]
fn a_moved_line_keeps_its_date_and_a_changed_one_takes_the_new() {
    let dated = |text: &str, since| (text.to_string(), Some(since));
    let old = vec![dated("a", 1), dated("b", 2), dated("c", 3)];
    assert_eq!(
        carry(&old, &["b", "c", "a2"], Some(9)),
        vec![dated("b", 2), dated("c", 3), dated("a2", 9)],
    );
    let adoption = Revision { id: "r".into(), created_ms: 4, origin: crate::vault::adopt::IMPORT_ORIGIN.into(), text: Some("x\ny".into()) };
    assert_eq!(replay(Vec::new(), &[adoption]), vec![("x".to_string(), None), ("y".to_string(), None)]);
}
