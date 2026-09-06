use super::*;

#[test]
fn independent_reader_keeps_old_target_bindings_while_producers_advance() {
    let f = fixture("- old 😀\r\n");
    let reader = rusqlite::Connection::open_with_flags(
        f.dir.path().join(".gilune/local/index.db"),
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    let tx = reader.unchecked_transaction().unwrap();
    let old_ids = current_ids(&tx); // Establish a real WAL snapshot.
    assert_integrity(&tx);
    let old_version: String = tx
        .query_row(
            "SELECT source_version FROM source_maps WHERE rel_path='a.md'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    f.fail.store(true, Ordering::SeqCst);
    std::fs::write(f.dir.path().join("a.md"), "- new 😀\r\n- another\r\n").unwrap();
    f.store.reconcile().unwrap();
    // Production source-map replacement and an independent producer have committed.
    // The already-open reader still sees the old internally consistent bindings.
    assert_eq!(current_ids(&tx), old_ids);
    assert_integrity(&tx);
    assert_eq!(
        tx.query_row(
            "SELECT source_version FROM source_maps WHERE rel_path='a.md'",
            [],
            |r| r.get::<_, String>(0)
        )
        .unwrap(),
        old_version
    );
    tx.commit().unwrap();
    assert_integrity(&reader);
    assert!(current_ids(&reader).iter().all(|id| !old_ids.contains(id)));
    assert_ne!(
        reader
            .query_row(
                "SELECT source_version FROM source_maps WHERE rel_path='a.md'",
                [],
                |r| r.get::<_, String>(0)
            )
            .unwrap(),
        old_version
    );
    let stale: (i64, i64) = reader
        .query_row(
            "SELECT count(*),count(target_id) FROM facts WHERE namespace='example.one'",
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .unwrap();
    assert!(stale.0 > 0);
    assert_eq!(
        stale.1, 0,
        "failed producer evidence cannot bind to the replacement source"
    );
    let ready: i64 = reader
        .query_row(
            "SELECT count(*) FROM facts WHERE namespace='example.two' AND target_id IS NOT NULL",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert!(ready > 0);
    f.fail.store(false, Ordering::SeqCst);
    f.store.rebuild_index(Some("example.one")).unwrap();
    assert_integrity(&reader);
    let rebound: i64 = reader
        .query_row(
            "SELECT count(*) FROM facts WHERE namespace='example.one' AND target_id IS NOT NULL",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(rebound, ready);
}
