use super::*;
use std::time::Instant;

/// Isolates source-map/target maintenance, not parsing, fact writes or filesystem
/// reconciliation. Disabled triggers provide a control for the metadata overhead.
#[test]
#[ignore = "manual release-mode maintenance profile"]
fn target_metadata_maintenance_profile() {
    for items in [100, 1_000, 10_000] {
        let text = "- plain item\n".repeat(items);
        let snapshot = TextSnapshot::new(text).unwrap();
        let layout = scoped::encode_layout(snapshot.layout());
        for targets in [false, true] {
            let db = rusqlite::Connection::open_in_memory().unwrap();
            crate::search_text::register(&db).unwrap();
            db.execute_batch(SCHEMA).unwrap();
            if !targets {
                db.execute_batch("DROP TRIGGER source_targets_insert; DROP TRIGGER source_targets_update; DROP TRIGGER source_targets_before_update; DROP TRIGGER source_targets_delete;").unwrap();
            }
            let mut samples = Vec::new();
            for trial in 0..4 {
                db.execute_batch("BEGIN IMMEDIATE").unwrap();
                let started = Instant::now();
                db.execute("INSERT INTO source_maps(rel_path,source_version,layout) VALUES('large.md','v',?1) ON CONFLICT(rel_path) DO UPDATE SET layout=excluded.layout", [&layout]).unwrap();
                db.execute_batch("COMMIT").unwrap();
                if trial > 0 {
                    samples.push(started.elapsed());
                }
            }
            samples.sort();
            let count: i64 = db
                .query_row("SELECT count(*) FROM index_targets", [], |r| r.get(0))
                .unwrap();
            assert_eq!(
                count,
                if targets {
                    (1 + snapshot.layout().lines.len() + snapshot.layout().items.len()) as i64
                } else {
                    0
                }
            );
            println!("target_metadata items={items} targets={targets} replace_median_ms={:.3} rows={count}", samples[1].as_secs_f64()*1000.0);
        }
    }
}
