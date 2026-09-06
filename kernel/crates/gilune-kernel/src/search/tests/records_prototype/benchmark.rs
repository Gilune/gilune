//! Domain-specific adapters are deliberately confined to this benchmark fixture.
//! Input is the real Kotlin plugin index. This bridge is not a new plugin ABI.
use super::*;
use rusqlite::{params, OpenFlags};
use std::collections::BTreeMap;
use std::time::{Duration, Instant};

const STATE: &str = "org.memory.tasks.state";
const DUE: &str = "org.memory.tasks.due";
const CONNECTION: &str = "org.memory.people.connection";

mod matrix;

mod tasks {
    use super::*;
    pub(super) fn declare(r: &mut Registry) -> usize {
        r.register(Schema {
            owner: "org.memory.tasks",
            name: "task",
            fields: vec![
                Field {
                    name: "state",
                    kind: Kind::Text,
                    nullable: false,
                },
                Field {
                    name: "due",
                    kind: Kind::Date,
                    nullable: true,
                },
            ],
            many: false,
            indexes: vec![vec![0, 1]],
        })
        .unwrap()
    }
    pub(super) fn records(facts: &[(String, String)]) -> Vec<Vec<Value>> {
        facts
            .iter()
            .find(|(p, _)| p == STATE)
            .map(|(_, state)| {
                vec![vec![
                    Value::Text(state.clone()),
                    facts
                        .iter()
                        .find(|(p, _)| p == DUE)
                        .map(|(_, d)| Value::Text(d.clone()))
                        .unwrap_or(Value::Null),
                ]]
            })
            .unwrap_or_default()
    }
}
mod people {
    use super::*;
    pub(super) fn declare(r: &mut Registry) -> usize {
        r.register(Schema {
            owner: "org.memory.people",
            name: "connection",
            fields: vec![
                Field {
                    name: "keyword",
                    kind: Kind::Text,
                    nullable: false,
                },
                Field {
                    name: "person",
                    kind: Kind::Text,
                    nullable: false,
                },
            ],
            many: true,
            indexes: vec![vec![0, 1]],
        })
        .unwrap()
    }
    pub(super) fn records(facts: &[(String, String)]) -> Vec<Vec<Value>> {
        facts
            .iter()
            .filter(|(p, _)| p == CONNECTION)
            .map(|(_, v)| {
                let pair: Vec<String> = serde_json::from_str(v).unwrap();
                assert_eq!(pair.len(), 2);
                pair.into_iter().map(Value::Text).collect()
            })
            .collect()
    }
}
struct Source {
    id: i64,
    path: String,
    version: String,
}
#[derive(Clone)]
struct Target {
    id: i64,
    source: i64,
    kind: i64,
    anchor: i64,
    facts: Vec<(String, String)>,
}
struct Data {
    sources: Vec<Source>,
    targets: Vec<Target>,
}
fn load(db: &Connection) -> Data {
    for ns in ["org.memory.tasks", "org.memory.people", "frontmatter"] {
        let mismatch:bool=db.query_row("SELECT EXISTS(SELECT 1 FROM source_maps s LEFT JOIN fact_sources r ON r.rel_path=s.rel_path AND r.namespace=? WHERE r.source_version IS NOT s.source_version)",[ns],|r|r.get(0)).unwrap();
        assert!(
            !mismatch,
            "fixture producer {ns} must match source versions"
        );
    }
    let mut sources = vec![];
    let mut targets = vec![];
    let mut maps = db
        .prepare("SELECT rel_path,source_version,layout FROM source_maps ORDER BY rel_path")
        .unwrap();
    for row in maps
        .query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })
        .unwrap()
    {
        let (path, version, layout) = row.unwrap();
        let source = sources.len() as i64 + 1;
        let mut facts: BTreeMap<Option<i64>, Vec<(String, String)>> = BTreeMap::new();
        let mut stmt=db.prepare_cached("SELECT item_key,predicate,value FROM facts WHERE rel_path=? AND source_version=? AND line IS NULL AND predicate IN (?,?,?) ORDER BY rowid").unwrap();
        for f in stmt
            .query_map(params![path, version, STATE, DUE, CONNECTION], |r| {
                Ok((
                    r.get::<_, Option<i64>>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                ))
            })
            .unwrap()
        {
            let (key, p, v) = f.unwrap();
            facts.entry(key).or_default().push((p, v));
        }
        targets.push(Target {
            id: targets.len() as i64 + 1,
            source,
            kind: 0,
            anchor: 0,
            facts: facts.remove(&None).unwrap_or_default(),
        });
        let map: serde_json::Value = serde_json::from_str(&layout).unwrap();
        for item in map["items"].as_array().unwrap() {
            let anchor = item["key"].as_i64().unwrap();
            targets.push(Target {
                id: targets.len() as i64 + 1,
                source,
                kind: 1,
                anchor,
                facts: facts.remove(&Some(anchor)).unwrap_or_default(),
            });
        }
        sources.push(Source {
            id: source,
            path,
            version,
        });
    }
    Data { sources, targets }
}
fn base(db: &Connection, data: &Data) {
    for s in &data.sources {
        db.prepare_cached("INSERT INTO sources VALUES(?,?,?)")
            .unwrap()
            .execute(params![s.id, s.path, s.version])
            .unwrap();
    }
    for t in &data.targets {
        db.prepare_cached("INSERT INTO targets VALUES(?,?,?,?)")
            .unwrap()
            .execute(params![t.id, t.source, t.kind, t.anchor])
            .unwrap();
    }
}
fn records_for(db: &Connection, registry: &Registry, t: &Target) {
    for (id, records) in [
        (0, tasks::records(&t.facts)),
        (1, people::records(&t.facts)),
    ] {
        for record in records {
            registry
                .insert(db, registry.schemas[id].owner, id, t.id, &record)
                .unwrap();
        }
    }
}
fn fact_id(p: &str) -> i64 {
    match p {
        STATE => 0,
        DUE => 1,
        CONNECTION => 2,
        _ => unreachable!(),
    }
}
fn facts_for(db: &Connection, t: &Target) {
    for (p, v) in &t.facts {
        db.prepare_cached("INSERT OR IGNORE INTO f VALUES(?,?,?)")
            .unwrap()
            .execute(params![t.id, fact_id(p), v])
            .unwrap();
    }
}
fn size(db: &Connection) -> i64 {
    db.query_row("SELECT (SELECT page_count FROM pragma_page_count)*(SELECT page_size FROM pragma_page_size)",[],|r|r.get(0)).unwrap()
}
fn time<T>(f: impl FnOnce() -> T) -> (Duration, T) {
    let start = Instant::now();
    let value = f();
    (start.elapsed(), value)
}
fn median(mut d: Vec<Duration>) -> Duration {
    d.sort();
    d[d.len() / 2]
}
fn query(persons: &[&str], negated: bool) -> Query {
    let text = |s: &str| Value::Text(s.into());
    Query {
        relation: 0,
        conditions: vec![
            Condition {
                field: 0,
                op: Op::Eq,
                values: vec![text("open")],
            },
            Condition {
                field: 1,
                op: Op::Ge,
                values: vec![text("2026-09-01")],
            },
            Condition {
                field: 1,
                op: Op::Lt,
                values: vec![text("2026-10-01")],
            },
        ],
        related: if persons.is_empty() {
            vec![]
        } else {
            vec![Related {
                relation: 1,
                negated,
                conditions: vec![
                    Condition {
                        field: 0,
                        op: Op::Eq,
                        values: vec![text("assign")],
                    },
                    Condition {
                        field: 1,
                        op: Op::In,
                        values: persons.iter().map(|s| text(s)).collect(),
                    },
                ],
            }]
        },
        order: 1,
    }
}

// Improved EAV baseline: interned target/predicate IDs, covering indexes,
// coalesced date bounds, EXISTS probes, keyset continuation, deferred count.
// The pair encoder belongs to this People fixture, not the generic SQL engine.
fn fact_sql(
    persons: &[&str],
    negated: bool,
    after: Option<&Cursor>,
    count: bool,
) -> (String, Vec<Value>) {
    let mut params = vec![
        Value::Text("2026-09-01".into()),
        Value::Text("2026-10-01".into()),
        Value::Text("open".into()),
    ];
    let mut conditions = vec![
        "d.predicate=1",
        "d.value>=?",
        "d.value<?",
        "EXISTS(SELECT 1 FROM f s WHERE s.target=d.target AND s.predicate=0 AND s.value=?)",
    ];
    let related;
    if !persons.is_empty() {
        related=format!("{}EXISTS(SELECT 1 FROM f c WHERE c.target=d.target AND c.predicate=2 AND c.value IN ({}))",if negated {"NOT "}else{""},vec!["?";persons.len()].join(","));
        conditions.push(&related);
        params.extend(
            persons
                .iter()
                .map(|p| Value::Text(serde_json::json!(["assign", p]).to_string())),
        );
    }
    if let Some(c) = after {
        conditions.push("(d.value,d.target)>(?,?)");
        params.extend([c.value.clone(), Value::Integer(c.target)]);
    }
    let select = if count {
        "COUNT(*)"
    } else {
        "d.target,d.value"
    };
    let tail = if count {
        ""
    } else {
        " ORDER BY d.value,d.target LIMIT ?"
    };
    (
        format!(
            "SELECT {select} FROM f d WHERE {}{tail}",
            conditions.join(" AND ")
        ),
        params,
    )
}
fn fact_page(
    db: &Connection,
    persons: &[&str],
    negated: bool,
    after: Option<&Cursor>,
    limit: usize,
) -> Page {
    let snapshot = db.unchecked_transaction().unwrap();
    let db = &snapshot;
    let (generation, ready): (i64, bool) = db
        .query_row("SELECT generation,ready FROM state", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .unwrap();
    assert!(ready);
    let fingerprint = format!("{persons:?}:{negated}");
    if let Some(c) = after {
        assert_eq!(c.generation, generation);
        assert_eq!(c.fingerprint, fingerprint);
    }
    let (sql, mut params) = fact_sql(persons, negated, after, false);
    let sql = restrict_targets(sql, &mut params, "d", fact_candidates(db, persons, negated));
    params.push(Value::Integer(limit as i64 + 1));
    let mut rows = db
        .prepare_cached(&sql)
        .unwrap()
        .query_map(params_from_iter(params), |r| {
            Ok(Row {
                target: r.get(0)?,
                value: r.get(1)?,
            })
        })
        .unwrap()
        .collect::<rusqlite::Result<Vec<_>>>()
        .unwrap();
    let more = rows.len() > limit;
    rows.truncate(limit);
    let next = if more {
        rows.last().map(|r| Cursor {
            generation,
            fingerprint,
            value: r.value.clone(),
            target: r.target,
        })
    } else {
        None
    };
    Page { rows, next }
}

fn fact_candidates(db: &Connection, persons: &[&str], negated: bool) -> Option<Vec<i64>> {
    if negated || persons.is_empty() {
        return None;
    }
    let params = persons
        .iter()
        .map(|p| Value::Text(serde_json::json!(["assign", p]).to_string()));
    let sql = format!(
        "SELECT DISTINCT target FROM f WHERE predicate=2 AND value IN ({}) LIMIT 65",
        vec!["?"; persons.len()].join(",")
    );
    let ids = db
        .prepare_cached(&sql)
        .unwrap()
        .query_map(params_from_iter(params), |r| r.get(0))
        .unwrap()
        .collect::<rusqlite::Result<Vec<i64>>>()
        .unwrap();
    (ids.len() <= 64).then_some(ids)
}

fn fact_count(db: &Connection, persons: &[&str], negated: bool) -> i64 {
    let snapshot = db.unchecked_transaction().unwrap();
    let db = &snapshot;
    let ready: bool = db
        .query_row("SELECT ready FROM state", [], |r| r.get(0))
        .unwrap();
    assert!(ready);
    let (sql, mut params) = fact_sql(persons, negated, None, true);
    let sql = restrict_targets(sql, &mut params, "d", fact_candidates(db, persons, negated));
    db.query_row(&sql, params_from_iter(params), |r| r.get(0))
        .unwrap()
}

fn hydrate(origin: &Connection, data: &Data, rows: &[Row]) -> usize {
    // Identical post-page projection for both models, from the existing source
    // maps/text/facts. Excludes FFI, rendering and live-write concurrency.
    let mut by_source: BTreeMap<i64, Vec<&Target>> = BTreeMap::new();
    for r in rows {
        let target = &data.targets[r.target as usize - 1];
        by_source.entry(target.source).or_default().push(target);
    }
    let mut bytes = 0;
    for (source, targets) in by_source {
        let s = &data.sources[source as usize - 1];
        let raw: String = origin
            .query_row(
                "SELECT layout FROM source_maps WHERE rel_path=?",
                [&s.path],
                |r| r.get(0),
            )
            .unwrap();
        let map: crate::index::scoped::Map = serde_json::from_str(&raw).unwrap();
        let doc = super::super::document(origin, &s.path, true, true).unwrap();
        let lines: Vec<_> = doc.body.split('\n').collect();
        let facts = super::super::facts(origin, &s.path, &s.version).unwrap();
        for target in targets {
            let item = &map.items[map
                .items
                .binary_search_by_key(&(target.anchor as u64), |i| i.key)
                .unwrap()];
            let (_, text) = super::super::item_text(item, &map, &lines).unwrap();
            bytes += text.len();
            bytes += facts
                .get(&Some(target.anchor as u64))
                .unwrap()
                .iter()
                .map(|f| f.key.len() + f.value.len())
                .sum::<usize>();
        }
    }
    bytes
}
fn plan(db: &Connection, label: &str, sql: &str, params: Vec<Value>) {
    println!("SQL {label}: {sql}\nPARAMS {params:?}");
    for row in db
        .prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
        .unwrap()
        .query_map(params_from_iter(params.clone()), |r| r.get::<_, String>(3))
        .unwrap()
    {
        println!("PLAN {label}: {}", row.unwrap());
    }
    let mut stmt = db.prepare(sql).unwrap();
    let mut rows = stmt.query(params_from_iter(params)).unwrap();
    let mut count = 0;
    while rows.next().unwrap().is_some() {
        count += 1;
    }
    drop(rows);
    println!(
        "WORK {label}: rows={count} vm_steps={} fullscan_steps={} sorts={}",
        stmt.get_status(rusqlite::StatementStatus::VmStep),
        stmt.get_status(rusqlite::StatementStatus::FullscanStep),
        stmt.get_status(rusqlite::StatementStatus::Sort)
    );
}

#[test]
#[ignore = "manual architecture comparison over real plugin facts; set GILUNE_CROSS_PLUGIN_INDEX"]
fn compare_structured_records_and_compact_facts() {
    let path = std::env::var("GILUNE_CROSS_PLUGIN_INDEX")
        .expect("run the Kotlin cross-plugin fixture first");
    let origin = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap();
    crate::search_text::register(&origin).unwrap();
    let (read, data) = time(|| load(&origin));
    assert_eq!(data.sources.len(), 500);
    assert_eq!(data.targets.iter().filter(|t| t.kind == 1).count(), 50_000);
    println!(
        "FIXTURE real plugin data read={read:?} targets={} documents={}",
        data.targets.len(),
        data.sources.len()
    );
    let mut registry = Registry::default();
    assert_eq!(tasks::declare(&mut registry), 0);
    assert_eq!(people::declare(&mut registry), 1);
    let mut production = super::super::profile::request();
    production.sort_predicate = DUE.into();
    let call = || {
        super::super::legacy_search(
            &origin,
            &production,
            None,
            "fixture".into(),
            "fixture".into(),
            None,
            vec![],
        )
        .unwrap()
    };
    let baseline = call();
    assert!(baseline.index_complete);
    assert_eq!(baseline.total_hits, 17_500);
    let baseline_time = median((0..3).map(|_| time(call).0).collect());
    let keys: BTreeMap<(&str, i64), i64> = data
        .targets
        .iter()
        .map(|t| {
            (
                (data.sources[t.source as usize - 1].path.as_str(), t.anchor),
                t.id,
            )
        })
        .collect();
    let baseline_targets = baseline
        .hits
        .iter()
        .map(|h| keys[&(h.rel_path.as_str(), h.items[0].key as i64)])
        .collect::<Vec<_>>();
    println!("BASELINE native_existing first50_due_sorted_with_count={baseline_time:?} (no Store entry/FFI)");
    let dir = tempfile::tempdir().unwrap();
    let record_db = Connection::open(dir.path().join("records.db")).unwrap();
    let fact_db = Connection::open(dir.path().join("facts.db")).unwrap();
    for (name, db) in [("records", &record_db), ("facts", &fact_db)] {
        db.execute_batch(
            "PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;",
        )
        .unwrap();
        let (build, ()) = time(|| {
            db.execute_batch("BEGIN").unwrap();
            if name == "records" {
                registry.install(db);
            } else {
                Registry::default().install(db);
                db.execute_batch("CREATE TABLE f(target INTEGER NOT NULL REFERENCES targets(id) ON DELETE CASCADE,predicate INTEGER NOT NULL,value TEXT NOT NULL,PRIMARY KEY(target,predicate,value)) WITHOUT ROWID;
                    CREATE INDEX f_value ON f(predicate,value,target);").unwrap();
            }
            base(db, &data);
            for t in &data.targets {
                if name == "records" {
                    records_for(db, &registry, t);
                } else {
                    facts_for(db, t);
                }
            }
            db.execute_batch("COMMIT").unwrap();
        });
        println!(
            "BUILD {name}: elapsed={build:?} query_store_bytes={}",
            size(db)
        );
        db.execute_batch("ANALYZE").unwrap();
    }
    // One additional derived connection creates a genuinely sparse match without
    // changing the original fixture or pretending it came from its source text.
    let last = data.targets.last().unwrap().id;
    registry
        .insert(
            &record_db,
            "org.memory.people",
            1,
            last,
            &[Value::Text("assign".into()), Value::Text("needle".into())],
        )
        .unwrap();
    fact_db
        .execute(
            "INSERT INTO f VALUES(?,2,?)",
            params![last, "[\"assign\",\"needle\"]"],
        )
        .unwrap();

    for (label, persons, negative, expected) in [
        ("alice", vec!["alice"], false, 17_500),
        ("alice_bob", vec!["alice", "bob"], false, 25_000),
        ("sparse", vec!["needle"], false, 1),
        ("absent", vec!["absent"], false, 0),
        ("open_month", vec![], false, 32_500),
        ("not_bob", vec!["bob"], true, 25_000),
    ] {
        let q = query(&persons, negative);
        for (name, db) in [("records", &record_db), ("facts", &fact_db)] {
            let fetch = |cursor: Option<&Cursor>, size| {
                if name == "records" {
                    q.page(db, &registry, cursor, size).unwrap()
                } else {
                    fact_page(db, &persons, negative, cursor, size)
                }
            };
            let first = fetch(None, 50);
            if label == "alice" {
                assert_eq!(
                    first.rows.iter().map(|r| r.target).collect::<Vec<_>>(),
                    baseline_targets
                );
            }
            let select = median((0..7).map(|_| time(|| fetch(None, 50)).0).collect());
            let full = median(
                (0..5)
                    .map(|_| {
                        time(|| {
                            let p = fetch(None, 50);
                            hydrate(&origin, &data, &p.rows)
                        })
                        .0
                    })
                    .collect(),
            );
            let count = median(
                (0..5)
                    .map(|_| {
                        time(|| {
                            let n = if name == "records" {
                                q.count(db, &registry).unwrap().1
                            } else {
                                fact_count(db, &persons, negative)
                            };
                            assert_eq!(n, expected);
                        })
                        .0
                    })
                    .collect(),
            );
            println!("PAGE {name} case={label} matched={expected} first50_select={select:?} first50_with_text_facts={full:?} exact_count={count:?}");
            if label == "alice" || label == "not_bob" {
                let (sql, params) = if name == "records" {
                    q.sql(None, true)
                } else {
                    fact_sql(&persons, negative, None, true)
                };
                plan(db, &format!("{name}_{label}_count"), &sql, params);
            }
            if label == "alice" {
                let (sql, mut params) = if name == "records" {
                    q.sql(None, false)
                } else {
                    fact_sql(&persons, negative, None, false)
                };
                params.push(Value::Integer(51));
                plan(db, name, &sql, params);
                let first500 = median(
                    (0..5)
                        .map(|_| {
                            time(|| {
                                let p = fetch(None, 500);
                                hydrate(&origin, &data, &p.rows)
                            })
                            .0
                        })
                        .collect(),
                );
                let mut cursor = None;
                for _ in 0..18 {
                    cursor = fetch(cursor.as_ref(), 500).next;
                }
                let page19 = median(
                    (0..5)
                        .map(|_| {
                            time(|| {
                                let p = fetch(cursor.as_ref(), 500);
                                hydrate(&origin, &data, &p.rows)
                            })
                            .0
                        })
                        .collect(),
                );
                let (all_time, all) = time(|| {
                    let mut cursor = None;
                    let mut all = vec![];
                    loop {
                        let p = fetch(cursor.as_ref(), 500);
                        hydrate(&origin, &data, &p.rows);
                        all.extend(p.rows);
                        cursor = p.next;
                        if cursor.is_none() {
                            break;
                        }
                    }
                    all
                });
                assert_eq!(all.len(), expected as usize);
                for row in &all {
                    let t = &data.targets[row.target as usize - 1];
                    assert_eq!(t.kind, 1);
                    assert!(t.facts.iter().any(|(p, v)| p == STATE && v == "open"));
                    assert!(t
                        .facts
                        .iter()
                        .any(|(p, v)| p == CONNECTION && v == "[\"assign\",\"alice\"]"));
                    assert!(t.facts.iter().any(|(p, v)| p == DUE
                        && v.as_str() >= "2026-09-01"
                        && v.as_str() < "2026-10-01"));
                }
                let oracle=record_db.prepare("SELECT target,f1 FROM r0 WHERE f0='open' AND f1>='2026-09-01' AND f1<'2026-10-01' AND EXISTS(SELECT 1 FROM r1 WHERE r1.target=r0.target AND f0='assign' AND f1='alice') ORDER BY f1,target").unwrap().query_map([],|r|Ok(Row{target:r.get(0)?,value:r.get(1)?})).unwrap().collect::<rusqlite::Result<Vec<_>>>().unwrap();
                assert_eq!(all, oracle);
                println!("ENUM {name} first500_with_text_facts={first500:?} page19_only={page19:?} all17500_with_text_facts={all_time:?}");
                assert!(first.next.is_some());
            }
        }
    }

    // Transactional whole-source replacement (100 task rows and their connection
    // records), without parsing/FFI/text maintenance. Check rollback and cursor
    // invalidation in addition to measuring the derived write amplification.
    let source = &data.sources[0];
    for (name, db) in [("records", &record_db), ("facts", &fact_db)] {
        let before = size(db);
        let q = query(&["alice"], false);
        let old_cursor = if name == "records" {
            q.page(db, &registry, None, 50).unwrap().next
        } else {
            fact_page(db, &["alice"], false, None, 50).next
        };
        let samples = (0..9)
            .map(|n| {
                time(|| {
                    db.execute_batch("BEGIN IMMEDIATE").unwrap();
                    // foreign_keys is connection-scoped and enabled before this transaction.
                    db.execute("DELETE FROM targets WHERE source=?", [source.id])
                        .unwrap();
                    db.execute(
                        "UPDATE sources SET version=? WHERE id=?",
                        params![format!("replacement-{n}"), source.id],
                    )
                    .unwrap();
                    for t in data.targets.iter().filter(|t| t.source == source.id) {
                        db.prepare_cached("INSERT INTO targets VALUES(?,?,?,?)")
                            .unwrap()
                            .execute(params![t.id, t.source, t.kind, t.anchor])
                            .unwrap();
                        let mut changed = t.clone();
                        for (p, v) in &mut changed.facts {
                            if p == STATE {
                                *v = "done".into();
                            }
                        }
                        if name == "records" {
                            records_for(db, &registry, &changed);
                        } else {
                            facts_for(db, &changed);
                        }
                    }
                    db.execute_batch("UPDATE state SET generation=generation+1; COMMIT")
                        .unwrap();
                })
                .0
            })
            .collect::<Vec<_>>();
        println!(
            "UPDATE {name} replace_100_tasks_median={:?} store_growth={}",
            median(samples),
            size(db) - before
        );
        let count = if name == "records" {
            assert!(q.page(db, &registry, old_cursor.as_ref(), 50).is_err());
            q.count(db, &registry).unwrap().1
        } else {
            let generation: i64 = db
                .query_row("SELECT generation FROM state", [], |r| r.get(0))
                .unwrap();
            assert_ne!(generation, old_cursor.unwrap().generation);
            fact_count(db, &["alice"], false)
        };
        assert_eq!(
            count, 17_465,
            "closing one source's tasks changes only its 35 matching connections"
        );
    }
    println!("PROTOTYPE complete; original index opened read-only; no application/API changes");
}
