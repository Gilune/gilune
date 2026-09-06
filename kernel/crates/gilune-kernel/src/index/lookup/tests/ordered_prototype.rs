//! Experimental registered ordering, keyset pages and maintained alignment.
//! Test-only: no schema/API/runtime changes to the shipped implementation.
use super::*;
use rusqlite::params;
use std::time::{Duration, Instant};

thread_local! { static PLANS: std::cell::RefCell<Option<Vec<String>>> = const { std::cell::RefCell::new(None) }; }

const ORDER: &str = "o.k0,o.k1,o.k2,o.k3,o.rel_path";
const SEED_LIMIT: usize = 64;
type Seed = BTreeSet<(String, String)>;

#[derive(Clone, Debug, PartialEq, Eq)]
struct Row {
    path: String,
    version: String,
    keys: [Vec<u8>; 4],
    priority: u32,
}
#[derive(Clone)]
struct Continuation {
    generation: String,
    fingerprint: String,
    last: Row,
}
struct Page {
    hits: Vec<LookupHit>,
    next: Option<Continuation>,
}

struct Prototype {
    layout: LookupRequest,
    count: Option<(String, String, u64)>,
}

fn generation(db: &Connection) -> String {
    db.query_row("SELECT (SELECT value FROM index_meta WHERE key='instance') || ':' || (SELECT value FROM index_meta WHERE key='generation')", [], |r| r.get(0)).unwrap()
}
fn fingerprint(q: &LookupRequest) -> String {
    let mut q = q.clone();
    q.cursor = None;
    hex::encode(Sha256::digest(format!("{q:?}").as_bytes()))
}
fn median(mut times: Vec<Duration>) -> Duration {
    times.sort();
    times[times.len() / 2]
}

impl Prototype {
    fn install(db: &Connection, layout: &LookupRequest) -> Self {
        assert!(layout.sort_predicates.len() <= 4);
        db.execute_batch("BEGIN;
            CREATE TABLE prototype_order (
                layout INTEGER NOT NULL, rel_path TEXT NOT NULL, source_version TEXT NOT NULL,
                k0 BLOB NOT NULL,k1 BLOB NOT NULL,k2 BLOB NOT NULL,k3 BLOB NOT NULL,
                PRIMARY KEY(layout,rel_path));
            CREATE INDEX prototype_order_key ON prototype_order(layout,k0,k1,k2,k3,rel_path);
            CREATE TABLE prototype_alignment(namespace TEXT PRIMARY KEY, mismatches INTEGER NOT NULL);
            CREATE TABLE prototype_dirty(rel_path TEXT PRIMARY KEY);
            CREATE TRIGGER prototype_fact_insert AFTER INSERT ON fact_values BEGIN
                INSERT OR IGNORE INTO prototype_dirty SELECT rel_path FROM fact_locations WHERE id=NEW.location_id; END;
            CREATE TRIGGER prototype_fact_delete AFTER DELETE ON fact_values BEGIN
                INSERT OR IGNORE INTO prototype_dirty SELECT rel_path FROM fact_locations WHERE id=OLD.location_id; END;
            CREATE TRIGGER prototype_fact_update AFTER UPDATE ON fact_values BEGIN
                INSERT OR IGNORE INTO prototype_dirty SELECT rel_path FROM fact_locations WHERE id=OLD.location_id;
                INSERT OR IGNORE INTO prototype_dirty SELECT rel_path FROM fact_locations WHERE id=NEW.location_id; END;
            CREATE TRIGGER prototype_map_insert_dirty AFTER INSERT ON source_maps BEGIN
                INSERT OR IGNORE INTO prototype_dirty VALUES(NEW.rel_path); END;
            CREATE TRIGGER prototype_map_delete_dirty AFTER DELETE ON source_maps BEGIN
                INSERT OR IGNORE INTO prototype_dirty VALUES(OLD.rel_path); END;
            CREATE TRIGGER prototype_map_update_dirty AFTER UPDATE ON source_maps BEGIN
                INSERT OR IGNORE INTO prototype_dirty VALUES(OLD.rel_path);
                INSERT OR IGNORE INTO prototype_dirty VALUES(NEW.rel_path); END;") .unwrap();
        for namespace in &layout.required_namespaces {
            let count: i64 = db.query_row("SELECT (SELECT count(*) FROM source_maps s LEFT JOIN fact_sources r ON r.namespace=?1 AND r.rel_path=s.rel_path WHERE r.source_version IS NOT s.source_version) + (SELECT count(*) FROM fact_sources r LEFT JOIN source_maps s ON s.rel_path=r.rel_path WHERE r.namespace=?1 AND s.rel_path IS NULL)", [namespace], |r|r.get(0)).unwrap();
            db.execute(
                "INSERT INTO prototype_alignment VALUES(?1,?2)",
                params![namespace, count],
            )
            .unwrap();
        }
        // Delta of a path's mismatch contribution, including missing sources
        // and orphan receipts. Triggers share the writer transaction/rollback.
        let receipt = |alias: &str| {
            format!("(SELECT source_version FROM fact_sources WHERE namespace=prototype_alignment.namespace AND rel_path={alias}.rel_path)")
        };
        let source = |alias: &str| {
            format!("(SELECT source_version FROM source_maps WHERE rel_path={alias}.rel_path)")
        };
        let bad = |left: &str, right: &str| format!("({left} IS NOT {right})");
        let source_add = format!(
            "{} - {}",
            bad("NEW.source_version", &receipt("NEW")),
            bad("NULL", &receipt("NEW"))
        );
        let source_remove = format!(
            "{} - {}",
            bad("NULL", &receipt("OLD")),
            bad("OLD.source_version", &receipt("OLD"))
        );
        let receipt_add = format!(
            "{} - {}",
            bad(&source("NEW"), "NEW.source_version"),
            bad(&source("NEW"), "NULL")
        );
        let receipt_remove = format!(
            "{} - {}",
            bad(&source("OLD"), "NULL"),
            bad(&source("OLD"), "OLD.source_version")
        );
        for (name, event, table, body) in [
            ("source_add","INSERT","source_maps",format!("UPDATE prototype_alignment SET mismatches=mismatches+{source_add};")),
            ("source_remove","DELETE","source_maps",format!("UPDATE prototype_alignment SET mismatches=mismatches+{source_remove};")),
            ("source_update","UPDATE","source_maps",format!("UPDATE prototype_alignment SET mismatches=mismatches+{source_remove}+{source_add};")),
            ("receipt_add","INSERT","fact_sources",format!("UPDATE prototype_alignment SET mismatches=mismatches+{receipt_add} WHERE namespace=NEW.namespace;")),
            ("receipt_remove","DELETE","fact_sources",format!("UPDATE prototype_alignment SET mismatches=mismatches+{receipt_remove} WHERE namespace=OLD.namespace;")),
            ("receipt_update","UPDATE","fact_sources",format!("UPDATE prototype_alignment SET mismatches=mismatches+{receipt_remove} WHERE namespace=OLD.namespace; UPDATE prototype_alignment SET mismatches=mismatches+{receipt_add} WHERE namespace=NEW.namespace;")),
        ] {
            db.execute_batch(&format!("CREATE TRIGGER prototype_{name} AFTER {event} ON {table} BEGIN {body} END;")).unwrap();
        }
        let prototype = Self {
            layout: layout.clone(),
            count: None,
        };
        prototype.refresh(db, None);
        db.execute_batch("COMMIT;").unwrap();
        prototype
    }

    fn refresh(&self, db: &Connection, path: Option<&str>) {
        let fields = (0..4)
            .map(|i| {
                if i < self.layout.sort_predicates.len() {
                    "MIN(CASE WHEN f.predicate=? THEN f.value END)"
                } else {
                    "NULL"
                }
            })
            .collect::<Vec<_>>()
            .join(",");
        let mut args: Vec<Value> = self
            .layout
            .sort_predicates
            .iter()
            .cloned()
            .map(Value::Text)
            .collect();
        args.push(Value::Text(
            serde_json::json!(self.layout.required_namespaces).to_string(),
        ));
        let filter = if let Some(path) = path {
            args.push(Value::Text(path.into()));
            "WHERE s.rel_path=?"
        } else {
            ""
        };
        let sql = format!("SELECT s.rel_path,s.source_version,{fields} FROM source_maps s LEFT JOIN facts f ON f.rel_path=s.rel_path AND f.source_version=s.source_version AND f.line IS NULL AND f.item_key IS NULL AND f.namespace IN (SELECT value FROM json_each(?)) {filter} GROUP BY s.rel_path,s.source_version");
        let mut statement = db.prepare(&sql).unwrap();
        let mut rows = statement.query(params_from_iter(args)).unwrap();
        if let Some(path) = path {
            db.execute(
                "DELETE FROM prototype_order WHERE layout=1 AND rel_path=?",
                [path],
            )
            .unwrap();
        } else {
            db.execute("DELETE FROM prototype_order WHERE layout=1", [])
                .unwrap();
        }
        let mut insert = db
            .prepare("INSERT INTO prototype_order VALUES(1,?1,?2,?3,?4,?5,?6)")
            .unwrap();
        while let Some(row) = rows.next().unwrap() {
            let keys: [Vec<u8>; 4] =
                std::array::from_fn(|i| match row.get::<_, Option<String>>(i + 2).unwrap() {
                    Some(text) => {
                        let mut key = vec![0];
                        key.extend(text.as_bytes());
                        key
                    }
                    None => vec![1],
                });
            insert
                .execute(params![
                    row.get::<_, String>(0).unwrap(),
                    row.get::<_, String>(1).unwrap(),
                    keys[0],
                    keys[1],
                    keys[2],
                    keys[3]
                ])
                .unwrap();
        }
        if let Some(path) = path {
            db.execute("DELETE FROM prototype_dirty WHERE rel_path=?", [path])
                .unwrap();
        } else {
            db.execute("DELETE FROM prototype_dirty", []).unwrap();
        }
    }

    fn ready(&self, db: &Connection, tip: i64) -> bool {
        let dirty: bool = db
            .query_row("SELECT EXISTS(SELECT 1 FROM prototype_dirty)", [], |r| {
                r.get(0)
            })
            .unwrap();
        if dirty {
            return false;
        }
        for ns in self
            .layout
            .required_namespaces
            .iter()
            .map(String::as_str)
            .chain([TEXT_NAMESPACE])
        {
            let ready = db.query_row("SELECT cursor_seq>=?2 AND last_error IS NULL AND (scoped OR namespace=?3 OR namespace=?4) FROM namespaces WHERE namespace=?1",params![ns,tip,TEXT_NAMESPACE,FRONT_MATTER_NAMESPACE],|r|r.get::<_,bool>(0)).unwrap_or(false);
            if !ready {
                return false;
            }
            if ns != TEXT_NAMESPACE {
                let aligned = db
                    .query_row(
                        "SELECT mismatches=0 FROM prototype_alignment WHERE namespace=?",
                        [ns],
                        |r| r.get::<_, bool>(0),
                    )
                    .unwrap_or(false);
                if !aligned {
                    return false;
                }
            }
        }
        true
    }

    fn page(
        &self,
        store: &Store,
        q: &LookupRequest,
        cursor: Option<&Continuation>,
    ) -> std::result::Result<Page, String> {
        if self.layout.sort_predicates != q.sort_predicates
            || self.layout.required_namespaces != q.required_namespaces
        {
            return Err("unregistered ordering".into());
        }
        let tip: i64 = store
            .conn()
            .query_row("SELECT COALESCE(MAX(seq),0) FROM vault_changes", [], |r| {
                r.get(0)
            })
            .unwrap();
        store.with_index(|db| {
            db.execute_batch("BEGIN;")?;
            let result = (|| {
                let gen = generation(db);
                let fp = fingerprint(q);
                if cursor.is_some_and(|c| c.generation != gen || c.fingerprint != fp) { return Err("expired cursor".into()); }
                if !self.ready(db,tip) { return Err("index or ordering incomplete".into()); }
                let tiers = tiers(q);
                let mut selected = Vec::new();
                for (priority, alternatives) in &tiers {
                    if cursor.is_some_and(|c| *priority<c.last.priority) { continue; }
                    let seed = seed_for_tier(db,q,alternatives);
                    if seed.as_ref().is_some_and(BTreeSet::is_empty) { continue; }
                    let after = cursor.filter(|c| c.last.priority==*priority).map(|c|&c.last);
                    selected.extend(select_tier(db,q,*priority,alternatives,&tiers,seed,after,q.page_size as usize + 1-selected.len()));
                    if selected.len()>q.page_size as usize { break; }
                }
                let more = selected.len()>q.page_size as usize;
                selected.truncate(q.page_size as usize);
                let next = if more { Some(Continuation { generation:gen, fingerprint:fp,last:selected.last().unwrap().clone() }) } else { None };
                let mut hits=Vec::new();
                let mut hydrate=db.prepare_cached("SELECT namespace,predicate,value FROM facts WHERE rel_path=?1 AND source_version=?2 AND line IS NULL AND item_key IS NULL ORDER BY predicate,value").unwrap();
                for row in selected {
                    let facts = hydrate.query_map(params![row.path,row.version],|r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?))).unwrap().map(|r|r.unwrap()).filter(|(ns,p,_)|q.required_namespaces.contains(ns)&&q.display_predicates.contains(p)).map(|(_,predicate,value)|LookupFact{predicate,value}).collect();
                    hits.push(LookupHit { rel_path:row.path,source_version:row.version,priority:row.priority,facts });
                }
                Ok(Page { hits,next })
            })();
            db.execute_batch("ROLLBACK;")?;
            Ok(result)
        }).unwrap()
    }

    fn count(&mut self, store: &Store, q: &LookupRequest) -> std::result::Result<u64, String> {
        if self.layout.sort_predicates != q.sort_predicates
            || self.layout.required_namespaces != q.required_namespaces
        {
            return Err("unregistered ordering".into());
        }
        let tip: i64 = store
            .conn()
            .query_row("SELECT COALESCE(MAX(seq),0) FROM vault_changes", [], |r| {
                r.get(0)
            })
            .unwrap();
        store
            .with_index(|db| {
                db.execute_batch("BEGIN;")?;
                if !self.ready(db, tip) {
                    db.execute_batch("ROLLBACK;")?;
                    return Ok(Err("index or ordering incomplete".into()));
                }
                let gen = generation(db);
                let fp = fingerprint(q);
                if let Some((g, f, count)) = &self.count {
                    if *g == gen && *f == fp {
                        db.execute_batch("ROLLBACK;")?;
                        return Ok(Ok(*count));
                    }
                }
                let (ctes, args, _) = sql(q);
                let count = db.query_row(
                    &format!("{ctes} SELECT count(*) FROM matched"),
                    params_from_iter(args),
                    |r| r.get(0),
                )?;
                db.execute_batch("ROLLBACK;")?;
                self.count = Some((gen, fp, count));
                Ok(Ok(count))
            })
            .unwrap()
    }
}

fn tiers(q: &LookupRequest) -> BTreeMap<u32, Vec<Vec<Constraint>>> {
    let mut tiers: BTreeMap<u32, Vec<Vec<Constraint>>> = BTreeMap::new();
    for a in &q.alternatives {
        tiers
            .entry(a.priority)
            .or_default()
            .push(a.constraints.clone());
    }
    if tiers.is_empty() {
        tiers.insert(0, vec![vec![]]);
    }
    tiers
}

fn seed_for_tier(
    db: &Connection,
    q: &LookupRequest,
    alternatives: &[Vec<Constraint>],
) -> Option<Seed> {
    let mut union = Seed::new();
    for alternative in alternatives {
        let mut smallest: Option<Seed> = None;
        for constraint in alternative.iter().chain(&q.constraints) {
            let piece = scoped_piece(constraint);
            if !piece.generates {
                continue;
            }
            let mut args = piece.params;
            args.push(Value::Text(
                serde_json::json!(q.required_namespaces).to_string(),
            ));
            let sql=format!("SELECT DISTINCT rel_path,source_version FROM ({}) WHERE namespace IN (SELECT value FROM json_each(?)) LIMIT {}",piece.sql,SEED_LIMIT+1);
            let rows = db
                .prepare(&sql)
                .unwrap()
                .query_map(params_from_iter(args), |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
                })
                .unwrap()
                .collect::<rusqlite::Result<Seed>>()
                .unwrap();
            if rows.len() <= SEED_LIMIT && smallest.as_ref().is_none_or(|s| rows.len() < s.len()) {
                smallest = Some(rows);
            }
            if smallest.as_ref().is_some_and(BTreeSet::is_empty) {
                break;
            }
        }
        // A broad alternative requires ordered scanning for the whole tier.
        // The seed limit is an experiment, not a tuned production threshold.
        union.extend(smallest?);
    }
    Some(union)
}

fn conjunction(q: &LookupRequest, cs: &[Constraint], args: &mut Vec<Value>) -> String {
    let parts:Vec<_>=cs.iter().map(|c| {
        let p=scoped_piece_at_root(c);
        args.extend(p.params);
        args.push(Value::Text(serde_json::json!(q.required_namespaces).to_string()));
        format!("{}EXISTS(SELECT 1 FROM ({}) f WHERE f.rel_path=o.rel_path AND f.source_version=o.source_version AND f.namespace IN (SELECT value FROM json_each(?)))",if p.generates{""}else{"NOT "},p.sql)
    }).collect();
    if parts.is_empty() {
        "1".into()
    } else {
        parts.join(" AND ")
    }
}

#[allow(clippy::too_many_arguments)] // Explicit scan inputs in this test-only experiment.
fn select_tier(
    db: &Connection,
    q: &LookupRequest,
    priority: u32,
    alternatives: &[Vec<Constraint>],
    tiers: &BTreeMap<u32, Vec<Vec<Constraint>>>,
    seed: Option<Seed>,
    after: Option<&Row>,
    limit: usize,
) -> Vec<Row> {
    let mut args = Vec::new();
    let from = if let Some(seed) = seed {
        args.push(Value::Text(serde_json::json!(seed).to_string()));
        "json_each(?) c CROSS JOIN prototype_order o ON o.layout=1 AND o.rel_path=json_extract(c.value,'$[0]') AND o.source_version=json_extract(c.value,'$[1]')"
    } else {
        "prototype_order o INDEXED BY prototype_order_key"
    };
    let mut conditions = vec!["o.layout=1".into()];
    if let Some(last) = after {
        conditions.push(format!("({ORDER})>(?,?,?,?,?)"));
        args.extend(last.keys.iter().cloned().map(Value::Blob));
        args.push(Value::Text(last.path.clone()));
    }
    conditions.push(conjunction(q, &q.constraints, &mut args));
    let tier = alternatives
        .iter()
        .map(|cs| format!("({})", conjunction(q, cs, &mut args)))
        .collect::<Vec<_>>()
        .join(" OR ");
    conditions.push(format!("({tier})"));
    for (_, earlier) in tiers.range(..priority) {
        let earlier = earlier
            .iter()
            .map(|cs| format!("({})", conjunction(q, cs, &mut args)))
            .collect::<Vec<_>>()
            .join(" OR ");
        conditions.push(format!("NOT ({earlier})"));
    }
    args.push(Value::Integer(limit as i64));
    let sql=format!("SELECT o.rel_path,o.source_version,o.k0,o.k1,o.k2,o.k3 FROM {from} JOIN source_maps s ON s.rel_path=o.rel_path AND s.source_version=o.source_version WHERE {} ORDER BY {ORDER} LIMIT ?",conditions.join(" AND "));
    let mut statement = db.prepare(&sql).unwrap();
    let rows = statement
        .query_map(params_from_iter(&args), |r| {
            Ok(Row {
                path: r.get(0)?,
                version: r.get(1)?,
                keys: [r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?],
                priority,
            })
        })
        .unwrap()
        .collect::<rusqlite::Result<Vec<_>>>()
        .unwrap();
    PLANS.with(|plans| {
        if let Some(plans) = plans.borrow_mut().as_mut() {
            plans.push(format!(
                "priority={priority} vm_steps={} sorts={}\n{}\n{}",
                statement.get_status(rusqlite::StatementStatus::VmStep),
                statement.get_status(rusqlite::StatementStatus::Sort),
                statement.expanded_sql().unwrap(),
                profile::dump_plan(db, &sql, &args)
            ));
        }
    });
    rows
}

type CountWorker = (
    std::thread::JoinHandle<(String, u64, Duration)>,
    std::sync::mpsc::Receiver<String>,
    std::sync::mpsc::SyncSender<()>,
);

// The production FFI serializes Store access. A background count must own a
// separate read connection; merely dispatching the current API to a worker
// would still hold the Store mutex and block foreground lookup calls.
fn spawn_count_reader(store: &Store, q: &LookupRequest) -> CountWorker {
    let path = store.vault().join(".gilune/local/index.db");
    let q = q.clone();
    let tip: i64 = store
        .conn()
        .query_row("SELECT COALESCE(MAX(seq),0) FROM vault_changes", [], |r| {
            r.get(0)
        })
        .unwrap();
    let (anchored_tx, anchored_rx) = std::sync::mpsc::sync_channel(0);
    let (release_tx, release_rx) = std::sync::mpsc::sync_channel(0);
    let worker = std::thread::spawn(move || {
        let db =
            Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap();
        crate::search_text::register(&db).unwrap();
        db.execute_batch("BEGIN;").unwrap();
        let gen = generation(&db);
        let reader = Prototype {
            layout: q.clone(),
            count: None,
        };
        assert!(reader.ready(&db, tip));
        anchored_tx.send(gen.clone()).unwrap();
        release_rx.recv().unwrap();
        let start = Instant::now();
        let (ctes, args, _) = sql(&q);
        let count = db
            .query_row(
                &format!("{ctes} SELECT count(*) FROM matched"),
                params_from_iter(args),
                |r| r.get(0),
            )
            .unwrap();
        db.execute_batch("ROLLBACK;").unwrap();
        (gen, count, start.elapsed())
    });
    (worker, anchored_rx, release_tx)
}

#[test]
fn prototype_pages_match_existing_query_and_guard_edits() {
    let (_dir, s) = setup();
    for (path, text) in [
        ("a.md", "kind=entry\nkey=alpha\nname=Zulu\nname=Beta\n"),
        ("b.md", "kind=entry\nkey=al\nname=Álpha\n"),
        ("c.md", "kind=entry\nkey=other\nname=ALice\n"),
        ("d.md", "kind=entry\n"),
        ("e.md", "kind=other\nkey=al\nname=First\n"),
        ("f.md", "kind=entry\nkey=al\nname=\n"),
    ] {
        write(&s, path, text);
    }
    let mut q = request("al");
    q.page_size = 1;
    let mut p = s.with_index(|db| Ok(Prototype::install(db, &q))).unwrap();
    for alternatives in [
        q.alternatives.clone(),
        vec![],
        vec![LookupAlternative {
            priority: 0,
            constraints: vec![fact("name", ConstraintOp::NotExists, "")],
        }],
        vec![
            LookupAlternative {
                priority: 1,
                constraints: vec![],
            },
            LookupAlternative {
                priority: 0,
                constraints: vec![fact("key", ConstraintOp::Eq, "al")],
            },
        ],
    ] {
        q.alternatives = alternatives;
        let mut reference = q.clone();
        reference.page_size = 500;
        let expected = s.lookup(&reference).unwrap();
        let mut actual = Vec::new();
        let mut cursor = None;
        loop {
            let page = p.page(&s, &q, cursor.as_ref()).unwrap();
            actual.extend(page.hits);
            cursor = page.next;
            if cursor.is_none() {
                break;
            }
        }
        assert_eq!(actual, expected.hits);
        assert_eq!(p.count(&s, &q).unwrap(), expected.total_hits);
    }
    q = request("al");
    q.page_size = 1;
    let cursor = p.page(&s, &q, None).unwrap().next.unwrap();
    let mut changed = q.clone();
    changed.alternatives.clear();
    assert!(p.page(&s, &changed, Some(&cursor)).is_err());
    s.with_index(|db| {
        db.execute_batch("BEGIN; UPDATE facts SET value='Aaron' WHERE rel_path='a.md' AND predicate='fixture.name';")?;
        assert!(!p.ready(db,0));
        p.refresh(db,Some("a.md"));
        db.execute_batch("UPDATE index_meta SET value=CAST(value AS INTEGER)+1 WHERE key='generation'; COMMIT;")?;
        Ok(())
    }).unwrap();
    assert!(p.page(&s, &q, Some(&cursor)).is_err());
    assert!(p.page(&s, &q, None).is_ok());
    let previous_count = p.count(&s, &q).unwrap();
    let (worker, anchored, release) = spawn_count_reader(&s, &q);
    let old_generation = anchored.recv().unwrap();
    s.with_index(|db| {
        db.execute_batch("BEGIN; UPDATE facts SET value='other' WHERE rel_path='b.md' AND predicate='fixture.key';")?;
        p.refresh(db,Some("b.md"));
        db.execute_batch("UPDATE index_meta SET value=CAST(value AS INTEGER)+1 WHERE key='generation'; COMMIT;")?;
        Ok(())
    }).unwrap();
    release.send(()).unwrap();
    let (count_generation, old_count, _) = worker.join().unwrap();
    assert_eq!(count_generation, old_generation);
    assert_eq!(old_count, previous_count);
    assert_ne!(
        count_generation,
        s.with_index(|db| Ok(generation(db))).unwrap(),
        "discard the late count from the old snapshot"
    );
    assert_eq!(p.count(&s, &q).unwrap(), s.lookup(&q).unwrap().total_hits);
    assert_ne!(p.count(&s, &q).unwrap(), old_count);
    s.with_index(|db| {
        db.execute_batch(
            "BEGIN; UPDATE namespaces SET last_error='refused' WHERE namespace='fixture';",
        )?;
        Ok(())
    })
    .unwrap();
    // Read the flags directly while this write transaction is open; public page
    // calls take their own transactions and are tested after committing below.
    s.with_index(|db| {
        assert!(!p.ready(db, 0));
        db.execute_batch("ROLLBACK;")?;
        Ok(())
    })
    .unwrap();
    s.with_index(|db| {
        for change in [
            "UPDATE source_maps SET source_version='changed' WHERE rel_path='a.md'",
            "DELETE FROM fact_sources WHERE namespace='fixture' AND rel_path='a.md'",
            "DELETE FROM source_maps WHERE rel_path='a.md'",
            "UPDATE source_maps SET rel_path='renamed.md' WHERE rel_path='a.md'",
            "UPDATE fact_sources SET rel_path='orphan.md' WHERE namespace='fixture' AND rel_path='a.md'",
            "UPDATE fact_sources SET namespace='other' WHERE namespace='fixture' AND rel_path='a.md'",
        ] {
            db.execute_batch("BEGIN;")?;db.execute_batch(change)?;
            let oracle:i64=db.query_row("SELECT (SELECT count(*) FROM source_maps s LEFT JOIN fact_sources r ON r.namespace='fixture' AND r.rel_path=s.rel_path WHERE r.source_version IS NOT s.source_version)+(SELECT count(*) FROM fact_sources r LEFT JOIN source_maps s ON s.rel_path=r.rel_path WHERE r.namespace='fixture' AND s.rel_path IS NULL)",[],|r|r.get(0))?;
            let maintained:i64=db.query_row("SELECT mismatches FROM prototype_alignment WHERE namespace='fixture'",[],|r|r.get(0))?;
            assert_eq!(maintained,oracle,"{change}");assert!(maintained>0);
            db.execute_batch("ROLLBACK;")?;
            assert!(p.ready(db,0));
        }
        Ok(())
    }).unwrap();
}

#[test]
#[ignore = "manual isolated release ordered-lookup prototype"]
fn profile_ordered_lookup_prototype_50000() {
    println!("Building 50,000-root fixture (excluded from lookup timings)");
    let (_dir, s) = profile::roots_count(50_000);
    let mut q = request("al");
    q.page_size = 500;
    let reference = s.lookup(&q).unwrap();
    let mut report = String::new();
    let receipts = s
        .with_index(|db| {
            Ok(db
                .prepare("SELECT namespace FROM fact_sources WHERE rel_path='seed.md'")?
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?)
        })
        .unwrap();
    let edit = |db: &Connection, i: usize| {
        let version = format!("{i:064x}");
        db.execute(
            "UPDATE source_maps SET source_version=? WHERE rel_path='seed.md'",
            [&version],
        )
        .unwrap();
        db.execute("UPDATE facts SET source_version=?1,value=CASE WHEN predicate='fixture.name' THEN ?2 ELSE value END WHERE rel_path='seed.md'",params![version,format!("Changed {i}")]).unwrap();
        for ns in &receipts {
            db.execute("UPDATE fact_sources SET source_version=?1 WHERE namespace=?2 AND rel_path='seed.md'",params![version,ns]).unwrap();
        }
    };
    let mut baseline_writes = Vec::new();
    for i in 0..8 {
        let start = Instant::now();
        s.with_index(|db| {
            db.execute_batch("BEGIN;")?;
            edit(db,i);
            db.execute_batch("UPDATE index_meta SET value=CAST(value AS INTEGER)+1 WHERE key='generation'; COMMIT;")?;
            Ok(())
        }).unwrap();
        if i > 0 {
            baseline_writes.push(start.elapsed());
        }
    }
    report.push_str(&format!(
        "ONE ROOT UPDATE baseline median={:?}\n",
        median(baseline_writes)
    ));
    let mut p = s
        .with_index(|db| {
            let pages: i64 = db.query_row("PRAGMA page_count", [], |r| r.get(0))?;
            let start = Instant::now();
            let p = Prototype::install(db, &q);
            let build = start.elapsed();
            let after: i64 = db.query_row("PRAGMA page_count", [], |r| r.get(0))?;
            report.push_str(&format!(
                "PROJECTION build={build:?} added_bytes={}\n",
                (after - pages) * 4096
            ));
            Ok(p)
        })
        .unwrap();
    println!("Registered ordering built; measuring pages");
    PLANS.with(|plans| *plans.borrow_mut() = Some(Vec::new()));
    assert_eq!(p.page(&s, &q, None).unwrap().hits, reference.hits);
    let first_plans = PLANS.with(|plans| plans.borrow_mut().take().unwrap());
    for size in [1, 30, 500] {
        q.page_size = size;
        let mut times = Vec::new();
        for run in 0..8 {
            let start = Instant::now();
            let page = p.page(&s, &q, None).unwrap();
            let elapsed = start.elapsed();
            assert_eq!(page.hits.len(), size as usize);
            assert!(page.next.is_some());
            if run > 0 {
                times.push(elapsed);
            }
        }
        report.push_str(&format!(
            "ORDERED first page size={size} median={:?}\n",
            median(times)
        ));
    }
    q.page_size = 500;
    let mut cursor = None;
    for _ in 0..18 {
        cursor = p.page(&s, &q, cursor.as_ref()).unwrap().next;
    }
    PLANS.with(|plans| *plans.borrow_mut() = Some(Vec::new()));
    p.page(&s, &q, cursor.as_ref()).unwrap();
    let later_plans = PLANS.with(|plans| plans.borrow_mut().take().unwrap());
    let mut times = Vec::new();
    for _ in 0..7 {
        let start = Instant::now();
        let page = p.page(&s, &q, cursor.as_ref()).unwrap();
        times.push(start.elapsed());
        assert_eq!(page.hits[0].rel_path, "09000.md");
        assert_eq!(page.hits.len(), 500);
    }
    report.push_str(&format!("ORDERED page 19 median={:?}\n", median(times)));
    let start = Instant::now();
    assert_eq!(p.count(&s, &q).unwrap(), 50_000);
    report.push_str(&format!("EXACT COUNT first={:?}\n", start.elapsed()));
    let mut times = Vec::new();
    for _ in 0..7 {
        let start = Instant::now();
        assert_eq!(p.count(&s, &q).unwrap(), 50_000);
        times.push(start.elapsed());
    }
    report.push_str(&format!("EXACT COUNT cached median={:?}\n", median(times)));
    let (worker, anchored, release) = spawn_count_reader(&s, &q);
    let count_generation = anchored.recv().unwrap();
    release.send(()).unwrap();
    let mut concurrent = Vec::new();
    let mut foreground = q.clone();
    foreground.page_size = 30;
    for _ in 0..7 {
        assert!(!worker.is_finished());
        let start = Instant::now();
        assert_eq!(p.page(&s, &foreground, None).unwrap().hits.len(), 30);
        concurrent.push(start.elapsed());
    }
    let (gen, count, elapsed) = worker.join().unwrap();
    assert_eq!(gen, count_generation);
    assert_eq!(count, 50_000);
    report.push_str(&format!("FOREGROUND 30 rows during separate-connection count median={:?}; count_elapsed={elapsed:?}\n",median(concurrent)));
    let mut paths = BTreeSet::new();
    let mut cursor = None;
    crate::probe::reset_file_reads();
    let start = Instant::now();
    loop {
        let page = p.page(&s, &q, cursor.as_ref()).unwrap();
        for hit in page.hits {
            assert!(paths.insert(hit.rel_path));
        }
        cursor = page.next;
        if cursor.is_none() {
            break;
        }
    }
    assert_eq!(paths.len(), 50_000);
    assert_eq!(crate::probe::file_reads(), 0);
    report.push_str(&format!(
        "ALL 50000 at 500/page elapsed={:?}; zero source reads\n",
        start.elapsed()
    ));
    // Update one source's derived facts and registered order in one transaction.
    let mut times = Vec::new();
    for i in 0..8 {
        let start = Instant::now();
        s.with_index(|db| {db.execute_batch("BEGIN;")?;edit(db,i+8);p.refresh(db,Some("seed.md"));assert!(p.ready(db,0));db.execute_batch("UPDATE index_meta SET value=CAST(value AS INTEGER)+1 WHERE key='generation'; COMMIT;")?;Ok(())}).unwrap();
        if i > 0 {
            times.push(start.elapsed());
        }
    }
    report.push_str(&format!(
        "ONE ROOT UPDATE plus refresh median={:?}\n",
        median(times)
    ));
    for fragment in ["missing", "alpha"] {
        let mut query = q.clone();
        query.page_size = 30;
        query.alternatives = vec![LookupAlternative {
            constraints: vec![fact("key", ConstraintOp::Eq, fragment)],
            priority: 0,
        }];
        let start = Instant::now();
        let page = p.page(&s, &query, None).unwrap();
        report.push_str(&format!(
            "EXACT {fragment:?} page rows={} elapsed={:?}\n",
            page.hits.len(),
            start.elapsed()
        ));
    }
    s.with_index(|db| {db.execute_batch("BEGIN; UPDATE facts SET value='needle' WHERE predicate='fixture.key' AND rel_path='seed.md';")?;p.refresh(db,Some("seed.md"));db.execute_batch("UPDATE index_meta SET value=CAST(value AS INTEGER)+1 WHERE key='generation'; COMMIT;")?;Ok(())}).unwrap();
    let mut selective = request("need");
    selective.page_size = 30;
    let mut times = Vec::new();
    for _ in 0..7 {
        let start = Instant::now();
        let page = p.page(&s, &selective, None).unwrap();
        times.push(start.elapsed());
        assert_eq!(page.hits.len(), 1);
        assert!(page.next.is_none());
    }
    report.push_str(&format!(
        "SELECTIVE one matching root median={:?}\n",
        median(times)
    ));
    // An absence query with no selective positive seed is deliberately hostile
    // to ordered scanning: all roots have the key, so it must prove no match.
    // Retain this limit rather than presenting broad-match timing as universal.
    let mut absence = q.clone();
    absence.page_size = 30;
    absence.alternatives = vec![LookupAlternative {
        priority: 0,
        constraints: vec![fact("key", ConstraintOp::NotExists, "")],
    }];
    let mut times = Vec::new();
    for _ in 0..7 {
        let start = Instant::now();
        let page = p.page(&s, &absence, None).unwrap();
        times.push(start.elapsed());
        assert!(page.hits.is_empty());
        assert!(page.next.is_none());
    }
    report.push_str(&format!(
        "NEGATIVE ONLY no matches median={:?}\n",
        median(times)
    ));
    report.push_str(&format!(
        "FIRST PAGE PLAN\n{}\nPAGE 19 PLAN\n{}\n",
        first_plans.join("\n"),
        later_plans.join("\n")
    ));
    println!("{report}");
    if let Some(dir) = std::env::var_os("GILUNE_LOOKUP_PROFILE_OUT") {
        let dir = std::path::PathBuf::from(dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("ordered-prototype.txt"), report).unwrap();
    }
}
