use super::*;
use gilune_document::TextSnapshot;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

mod profile;
mod snapshots;
mod write_path;

struct Locations {
    namespace: &'static str,
    fail: Arc<AtomicBool>,
    calls: Arc<AtomicUsize>,
}
impl NoteIndexer for Locations {
    fn index(&self, _: &str, _: &str) -> std::result::Result<Vec<Fact>, String> {
        unreachable!()
    }
    fn uses_scoped_input(&self) -> bool {
        true
    }
    fn index_scoped(
        &self,
        _: &str,
        s: &TextSnapshot,
    ) -> std::result::Result<Vec<scoped::ScopedFact>, String> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if self.fail.load(Ordering::SeqCst) {
            return Err("fixture refusal".into());
        }
        let mut locations = vec![Location::DocumentRoot];
        locations.extend(
            s.layout()
                .lines
                .iter()
                .map(|l| Location::BodyLine { number: l.number }),
        );
        locations.extend(
            s.layout()
                .items
                .iter()
                .map(|i| Location::MarkdownItem { key: i.key }),
        );
        Ok(locations
            .into_iter()
            .map(|location| scoped::ScopedFact {
                location,
                predicate: format!("{}.present", self.namespace),
                value: "yes".into(),
            })
            .collect())
    }
}
struct Legacy;
impl NoteIndexer for Legacy {
    fn index(&self, _: &str, _: &str) -> std::result::Result<Vec<Fact>, String> {
        Ok(vec![Fact {
            line: Some(1),
            predicate: "example.legacy.present".into(),
            value: "yes".into(),
        }])
    }
}
struct Fixture {
    dir: tempfile::TempDir,
    store: Store,
    fail: Arc<AtomicBool>,
    calls: Arc<AtomicUsize>,
}
fn fixture(text: &str) -> Fixture {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    std::fs::write(dir.path().join(".gilune-local"), "").unwrap(); // local-only vault (ADR 0007, 2026-10-22)
    let fail = Arc::new(AtomicBool::new(false));
    let calls = Arc::new(AtomicUsize::new(0));
    for namespace in ["example.one", "example.two"] {
        store
            .register_indexer(
                namespace,
                1,
                &[],
                Arc::new(Locations {
                    namespace,
                    fail: if namespace == "example.one" {
                        fail.clone()
                    } else {
                        Arc::new(AtomicBool::new(false))
                    },
                    calls: calls.clone(),
                }),
            )
            .unwrap();
    }
    store
        .register_indexer("example.legacy", 1, &[], Arc::new(Legacy))
        .unwrap();
    std::fs::write(dir.path().join("a.md"), text).unwrap();
    store.reconcile().unwrap();
    Fixture {
        dir,
        store,
        fail,
        calls,
    }
}
fn current_ids(db: &rusqlite::Connection) -> Vec<i64> {
    db.prepare("SELECT id FROM index_targets ORDER BY id")
        .unwrap()
        .query_map([], |r| r.get(0))
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap()
}
fn assert_integrity(db: &rusqlite::Connection) {
    let broken:i64=db.query_row("SELECT count(*) FROM facts f LEFT JOIN index_predicates p ON p.id=f.predicate_id WHERE p.namespace IS NOT f.namespace OR p.predicate IS NOT f.predicate",[],|r|r.get(0)).unwrap();
    assert_eq!(broken, 0, "every fact keeps its owning predicate binding");
    let broken:i64=db.query_row("SELECT count(*) FROM facts f LEFT JOIN index_targets t ON t.id=f.target_id LEFT JOIN source_maps s ON s.id=t.source_id WHERE f.target_id IS NOT NULL AND (s.rel_path IS NOT f.rel_path OR s.source_version IS NOT f.source_version OR t.kind != CASE WHEN f.item_key IS NOT NULL THEN 2 WHEN f.line IS NOT NULL THEN 1 ELSE 0 END OR t.local_key != COALESCE(f.item_key,f.line,0))",[],|r|r.get(0)).unwrap();
    assert_eq!(
        broken, 0,
        "no cross-source or cross-location numeric binding"
    );
    let missing:i64=db.query_row("SELECT count(*) FROM facts f JOIN source_maps s ON s.rel_path=f.rel_path AND s.source_version=f.source_version WHERE f.target_id IS NULL",[],|r|r.get(0)).unwrap();
    assert_eq!(missing, 0, "valid scoped facts have a target");
    let orphan:i64=db.query_row("SELECT count(*) FROM index_targets t LEFT JOIN source_maps s ON s.id=t.source_id WHERE s.id IS NULL",[],|r|r.get(0)).unwrap();
    assert_eq!(orphan, 0);
    let legacy: i64 = db
        .query_row(
            "SELECT count(*) FROM facts WHERE source_version IS NULL AND target_id IS NOT NULL",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(legacy, 0, "legacy facts are not silently promoted");
}

#[test]
fn root_line_and_item_ranges_match_the_shared_parser_for_crlf_and_unicode() {
    let text =
        "---\r\ntitle: Test\r\n---\r\n- Parent 😀\r\n  continuation\r\n  - Child é\r\n- Other\r\n";
    let f = fixture(text);
    let snapshot = TextSnapshot::new(text.into()).unwrap();
    f.store.with_index(|db|{
        assert_integrity(db);
        let root=resolve(db,"a.md".into(),snapshot.version().into(),Location::DocumentRoot)?;
        assert_eq!(root.range,ByteSpan{start:0,end:text.len() as u64});
        assert_eq!(root.own_ranges,vec![root.range]);
        for line in &snapshot.layout().lines {
            let resolved=resolve(db,"a.md".into(),snapshot.version().into(),Location::BodyLine{number:line.number})?;
            assert_eq!(resolved.range,line.range);assert_eq!(resolved.own_ranges,vec![line.range]);
        }
        for item in &snapshot.layout().items {
            let resolved=resolve(db,"a.md".into(),snapshot.version().into(),Location::MarkdownItem{key:item.key})?;
            assert_eq!(resolved.range,item.range);assert_eq!(resolved.own_ranges,item.own_ranges);
            assert_eq!(resolved.map_version,snapshot.layout().version);
        }
        assert_eq!(current_ids(db).len(),1+snapshot.layout().lines.len()+snapshot.layout().items.len());
        let pairs:i64=db.query_row("SELECT count(*) FROM facts a JOIN facts b ON a.target_id=b.target_id WHERE a.namespace='example.one' AND b.namespace='example.two'",[],|r|r.get(0))?;
        assert_eq!(pairs as usize,current_ids(db).len());
        assert!(resolve(db,"a.md".into(),"another-version".into(),Location::DocumentRoot).is_err());
        Ok(())
    }).unwrap();
}

#[test]
fn a_failed_producer_cannot_bind_old_facts_to_reused_offsets_in_a_new_revision() {
    let f = fixture("- old\n");
    let ids = f.store.with_index(|db| Ok(current_ids(db))).unwrap();
    f.fail.store(true, Ordering::SeqCst);
    std::fs::write(f.dir.path().join("a.md"), "- new\n").unwrap();
    f.store.reconcile().unwrap();
    f.store.with_index(|db|{
        assert_integrity(db);
        assert_eq!(current_ids(db),ids,"unchanged coordinates reuse private IDs without validating old evidence");
        let held:(i64,i64)=db.query_row("SELECT count(*),count(target_id) FROM facts WHERE namespace='example.one'",[],|r|Ok((r.get(0)?,r.get(1)?)))?;
        assert!(held.0>0);assert_eq!(held.1,0,"old evidence survives without a current target binding");
        let current:i64=db.query_row("SELECT count(*) FROM facts WHERE namespace='example.two' AND target_id IS NOT NULL",[],|r|r.get(0))?;assert!(current>0);
        Ok(())
    }).unwrap();
    f.fail.store(false, Ordering::SeqCst);
    f.store.advance_index().unwrap();
    f.store
        .with_index(|db| {
            assert_integrity(db);
            Ok(())
        })
        .unwrap();
}

#[test]
fn text_only_rebuild_rebinds_same_revision_without_rerunning_plugins() {
    let f = fixture("- unchanged\n");
    let calls = f.calls.load(Ordering::SeqCst);
    let ids = f.store.with_index(|db| Ok(current_ids(db))).unwrap();
    f.store.rebuild_index(Some(TEXT_NAMESPACE)).unwrap();
    assert_eq!(calls, f.calls.load(Ordering::SeqCst));
    f.store.with_index(|db|{
        assert_integrity(db);assert!(current_ids(db).iter().all(|id|!ids.contains(id)));
        let bound:i64=db.query_row("SELECT count(*) FROM facts WHERE namespace='example.one' AND target_id IS NOT NULL",[],|r|r.get(0))?;assert!(bound>0);Ok(())
    }).unwrap();
}

#[test]
fn moves_removal_and_deletion_preserve_namespace_and_source_boundaries() {
    let f = fixture("- moved\n");
    std::fs::rename(f.dir.path().join("a.md"), f.dir.path().join("b.md")).unwrap();
    f.store.reconcile().unwrap();
    f.store
        .with_index(|db| {
            assert_integrity(db);
            let old: i64 = db.query_row(
                "SELECT count(*) FROM source_maps WHERE rel_path='a.md'",
                [],
                |r| r.get(0),
            )?;
            assert_eq!(old, 0);
            Ok(())
        })
        .unwrap();
    f.store.forget_plugin_namespaces("example.one").unwrap();
    f.store.with_index(|db|{
        assert_integrity(db);let removed:i64=db.query_row("SELECT count(*) FROM index_predicates WHERE namespace='example.one'",[],|r|r.get(0))?;assert_eq!(removed,0);
        let others:i64=db.query_row("SELECT count(*) FROM facts WHERE namespace='example.two' AND target_id IS NOT NULL",[],|r|r.get(0))?;assert!(others>0);Ok(())
    }).unwrap();
    std::fs::remove_file(f.dir.path().join("b.md")).unwrap();
    f.store.reconcile().unwrap();
    f.store
        .with_index(|db| {
            assert_integrity(db);
            assert!(current_ids(db).is_empty());
            Ok(())
        })
        .unwrap();
}

#[test]
fn failed_transactions_restore_targets_bindings_and_predicate_ids() {
    let f = fixture("- kept\n");
    f.store.with_index(|db|{
        let ids=current_ids(db);
        db.execute_batch("BEGIN IMMEDIATE; UPDATE source_maps SET source_version='uncommitted'; DELETE FROM facts WHERE namespace='example.two'; ROLLBACK;")?;
        assert_eq!(current_ids(db),ids);assert_integrity(db);
        Ok(())
    }).unwrap();
}

#[test]
fn older_indexes_are_discarded_and_sources_rebuild_without_document_changes() {
    for previous in ["5", "6", "7", "8", "9", "10", "11", "12"] {
        let f = fixture("- portable\n");
        let before = std::fs::read(f.dir.path().join("a.md")).unwrap();
        f.store
            .with_index(|db| {
                if previous == "12" {
                    // Schema 12 stored public coordinates and indexed lines
                    // in physical tables. Rebuild must handle both object kinds.
                    db.execute_batch(
                        "CREATE TEMP TABLE old_targets AS SELECT * FROM index_targets;
                        DROP VIEW index_targets;
                        CREATE TABLE index_targets AS SELECT * FROM old_targets;
                        DROP TABLE old_targets;
                        CREATE TEMP TABLE old_lines AS SELECT * FROM lines;
                        DROP VIEW lines;
                        CREATE TABLE lines AS SELECT * FROM old_lines;
                        DROP TABLE old_lines;",
                    )?;
                }
                if previous == "11" {
                    // Exercise the actual old table shape, not only a changed
                    // version marker on the new schema's compatibility view.
                    db.execute_batch(
                        "CREATE TEMP TABLE old_locations AS SELECT * FROM fact_locations;
                        DROP VIEW fact_locations;
                        CREATE TABLE fact_locations AS SELECT * FROM old_locations;
                        DROP TABLE old_locations;
                        DROP TABLE fact_location_slots;
                        DROP TABLE fact_frames;",
                    )?;
                }
                db.execute(
                    "UPDATE index_meta SET value=?1 WHERE key='index_schema_version'",
                    [previous],
                )?;
                Ok(())
            })
            .unwrap();
        // Opening again detects the disposable-index version mismatch. Existing
        // callbacks are intentionally re-registered, as on an ordinary process start.
        drop(f.store);
        let store = Store::open(f.dir.path()).unwrap();
        store
            .register_indexer(
                "example.one",
                1,
                &[],
                Arc::new(Locations {
                    namespace: "example.one",
                    fail: f.fail,
                    calls: f.calls,
                }),
            )
            .unwrap();
        store.rebuild_index(None).unwrap();
        store
            .with_index(|db| {
                assert_integrity(db);
                assert!(!current_ids(db).is_empty());
                assert_eq!(
                    meta(db, "index_schema_version")?,
                    Some(SCHEMA_VERSION.to_string())
                );
                Ok(())
            })
            .unwrap();
        assert_eq!(std::fs::read(f.dir.path().join("a.md")).unwrap(), before);
    }
}
