//! Frozen schema-6 insertion strategy versus batch-resolved IDs on disposable copies.
use super::*;
use std::time::Instant;
type WideFact = (Option<i64>, Option<i64>, String, String, Option<f64>, bool, String);

// Keep the historical control independent of subsequent production writer changes.
fn legacy_put(db: &rusqlite::Connection, namespace: &str, path: &str, facts: &[FactRow], version: &str) -> Result<()> {
    db.execute("INSERT INTO fact_sources(namespace,rel_path,source_version) VALUES(?,?,?)", rusqlite::params![namespace,path,version])?;
    let mut insert = db.prepare_cached("INSERT INTO facts(rel_path,line,namespace,predicate,value,num,typed,item_key,source_version) VALUES(?,?,?,?,?,?,?,?,?)")?;
    for f in facts {
        insert.execute(rusqlite::params![path,f.line,namespace,f.predicate,f.value,f.num,f.typed as i64,f.item_key,f.source_version])?;
    }
    Ok(())
}

fn prebound_put(
    db: &rusqlite::Connection,
    namespace: &str,
    path: &str,
    facts: &[FactRow],
    version: &str,
) -> Result<()> {
    let predicates: BTreeMap<String, i64> = db
        .prepare("SELECT predicate,id FROM index_predicates WHERE namespace=?")?
        .query_map([namespace], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<rusqlite::Result<_>>()?;
    let targets: BTreeMap<(i64,i64),i64> = db.prepare("SELECT t.kind,t.local_key,t.id FROM index_targets t JOIN source_maps s ON s.id=t.source_id WHERE s.rel_path=? AND s.source_version=?")?
        .query_map(rusqlite::params![path,version], |r| Ok(((r.get(0)?,r.get(1)?),r.get(2)?)))?.collect::<rusqlite::Result<_>>()?;
    db.execute(
        "INSERT INTO fact_sources(namespace,rel_path,source_version) VALUES(?,?,?)",
        rusqlite::params![namespace, path, version],
    )?;
    let mut stmt=db.prepare_cached("INSERT INTO facts(rel_path,line,namespace,predicate,value,num,typed,item_key,source_version,target_id,predicate_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)")?;
    for f in facts {
        let kind = if f.item_key.is_some() {
            2
        } else if f.line.is_some() {
            1
        } else {
            0
        };
        let target = targets
            .get(&(kind, f.item_key.or(f.line).unwrap_or(0)))
            .unwrap();
        stmt.execute(rusqlite::params![
            path,
            f.line,
            namespace,
            f.predicate,
            f.value,
            f.num,
            f.typed as i64,
            f.item_key,
            version,
            target,
            predicates[&f.predicate]
        ])?;
    }
    Ok(())
}

#[test]
#[ignore = "manual native write controls: GILUNE_SHAPE_DIR"]
fn profile_real_update_write_path() {
    let root = std::path::PathBuf::from(std::env::var("GILUNE_SHAPE_DIR").unwrap());
    let source = rusqlite::Connection::open_with_flags(
        root.join("items-10000/.gilune/local/index.db"),
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    crate::search_text::register(&source).unwrap();
    let (path, layout): (String, String) = source
        .query_row("SELECT rel_path,layout FROM source_maps", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .unwrap();
    let mut facts: BTreeMap<String, Vec<FactRow>> = BTreeMap::new();
    for row in source.prepare("SELECT namespace,line,item_key,source_version,predicate,value,num,typed FROM facts ORDER BY rowid").unwrap().query_map([],|r|Ok((r.get::<_,String>(0)?,FactRow{line:r.get(1)?,item_key:r.get(2)?,source_version:r.get(3)?,predicate:r.get(4)?,value:r.get(5)?,num:r.get(6)?,typed:r.get(7)?}))).unwrap() {
        let (namespace,fact)=row.unwrap();facts.entry(namespace).or_default().push(fact);
    }
    let mut lines: Vec<LineRow> = source
        .prepare("SELECT line,field,text FROM lines ORDER BY rowid")
        .unwrap()
        .query_map([], |r| {
            let field: String = r.get(1)?;
            Ok(LineRow {
                line: r.get(0)?,
                field: match field.as_str() {
                    "body" => "body",
                    "name" => "name",
                    "path" => "path",
                    _ => panic!("unknown field"),
                },
                text: r.get(2)?,
            })
        })
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap();
    let first_key = facts
        .values()
        .flatten()
        .filter_map(|f| f.item_key)
        .min()
        .unwrap();
    let run = tempfile::Builder::new()
        .prefix("native-write-")
        .tempdir_in(&root)
        .unwrap()
        .keep();
    let mut results = vec![];
    for mode in ["current_triggers", "batch_ids", "production_guarded", "production_without_insert_trigger"] {
        let db_path = run.join(format!("{mode}.db"));
        source
            .execute("VACUUM INTO ?", [db_path.to_str().unwrap()])
            .unwrap();
        let db = rusqlite::Connection::open(&db_path).unwrap();
        crate::search_text::register(&db).unwrap();
        db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL")
            .unwrap();
        db.execute_batch("DROP TRIGGER IF EXISTS fact_ids_insert").unwrap();
        if mode == "current_triggers" {
            db.execute_batch(include_str!("../../writer/tests/legacy_insert.sql")).unwrap();
        } else if mode == "production_guarded" {
            let guarded = include_str!("../../writer/tests/legacy_insert.sql").replacen("ON facts BEGIN", "ON facts WHEN NEW.predicate_id IS NULL BEGIN", 1);
            db.execute_batch(&guarded).unwrap();
        }
        if mode == "batch_ids" {
            db.execute_batch("DROP TRIGGER fact_ids_update")
                .unwrap();
        }
        for sample in 0..4 {
            let version = format!("profile-{sample}");
            let due = if sample % 2 == 0 {
                "2026-10-01"
            } else {
                "2026-09-01"
            };
            for fact in facts.values_mut().flatten() {
                fact.source_version = Some(version.clone());
                if fact.item_key == Some(first_key) && fact.predicate == "org.memory.tasks.due" {
                    fact.value = due.into();
                }
            }
            let line = lines
                .iter_mut()
                .find(|l| l.field == "body" && l.text.contains("Work 0 "))
                .unwrap();
            line.text = format!("- [ ] Work 0 assign: @alice due:{due}");
            let start = Instant::now();
            let mut phases = vec![];
            for ns in [
                TEXT_NAMESPACE,
                FRONT_MATTER_NAMESPACE,
                "org.memory.tasks",
                "org.memory.people",
            ] {
                db.execute_batch("BEGIN IMMEDIATE").unwrap();
                let start = Instant::now();
                forget_path(&db, ns, &path).unwrap();
                let delete_ms = start.elapsed().as_secs_f64() * 1000.0;
                let start = Instant::now();
                if ns == TEXT_NAMESPACE {
                    // Reuse actual line/map write code without changing its data layout.
                    let rows = Rows::Lines(
                        lines
                            .iter()
                            .map(|l| LineRow {
                                line: l.line,
                                field: l.field,
                                text: l.text.clone(),
                            })
                            .collect(),
                        Some((version.clone(), layout.clone())),
                    );
                    put(&db, ns, &path, &rows).unwrap();
                } else if mode == "batch_ids" {
                    prebound_put(&db, ns, &path, &facts[ns], &version).unwrap();
                } else if mode.starts_with("production_") {
                    crate::index::writer::put_facts(&db, ns, &path, &facts[ns], Some(&version)).unwrap();
                } else {
                    legacy_put(&db, ns, &path, &facts[ns], &version).unwrap();
                }
                let insert_ms = start.elapsed().as_secs_f64() * 1000.0;
                let start = Instant::now();
                db.execute_batch("COMMIT").unwrap();
                let commit_ms = start.elapsed().as_secs_f64() * 1000.0;
                phases.push(serde_json::json!({"namespace":ns,"delete_ms":delete_ms,"insert_ms":insert_ms,"commit_ms":commit_ms}));
            }
            let elapsed = start.elapsed().as_secs_f64() * 1000.0;
            assert_integrity(&db);
            let expected: usize = facts.values().map(Vec::len).sum();
            assert_eq!(
                db.query_row("SELECT count(*) FROM facts", [], |r| r.get::<_, usize>(0))
                    .unwrap(),
                expected
            );
            for (ns, expected) in &facts {
                let actual:Vec<WideFact>=db.prepare("SELECT line,item_key,predicate,value,num,typed,source_version FROM facts WHERE namespace=? ORDER BY rowid").unwrap().query_map([ns],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?,r.get(6)?))).unwrap().collect::<rusqlite::Result<_>>().unwrap();
                let expected: Vec<_> = expected
                    .iter()
                    .map(|f| {
                        (
                            f.line,
                            f.item_key,
                            f.predicate.clone(),
                            f.value.clone(),
                            f.num,
                            f.typed,
                            version.clone(),
                        )
                    })
                    .collect();
                assert_eq!(actual, expected);
            }
            let row = serde_json::json!({"mode":mode,"sample":sample,"warmup":sample==0,"total_ms":elapsed,"phases":phases});
            println!("NATIVE_WRITE {row}");
            results.push(row);
        }
    }
    std::fs::write(
        run.join("results.json"),
        serde_json::to_vec_pretty(&results).unwrap(),
    )
    .unwrap();
    println!("NATIVE_WRITE_OUTPUT {}", run.display());
}
