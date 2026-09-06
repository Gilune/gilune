//! Diagnostic adapter from actual Kotlin DSL output, not a production executor.
//! Unsupported query shapes are reported explicitly and never silently simplified.
use super::*;
use crate::index::{Constraint, ConstraintOp as COp, FactLocality, TextTarget};
use crate::search::{SearchClause, SearchRequest};
use serde_json::{json, Value as Json};
mod shapes;
mod semantics;
mod writes;
mod production;

#[test]
#[ignore = "manual compact count access-path control"]
fn profile_dsl_matrix_count_access_paths() {
    let out = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_DIR").unwrap());
    let run = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_RUN").unwrap());
    let db = Connection::open(run.join("facts.db")).unwrap();
    let cases: Vec<Json> =
        serde_json::from_slice(&std::fs::read(out.join("dsl-cases.json")).unwrap()).unwrap();
    let mut results = vec![];
    for c in cases.iter().filter(|c| {
        [
            "all",
            "open",
            "alice_month_due",
            "not_week",
            "two_assignees",
        ]
        .contains(&c["name"].as_str().unwrap())
    }) {
        let q = request(c);
        let mut params = vec![];
        let condition = where_sql(&q, &mut params);
        let mut result = json!({"name":c["name"]});
        result["complete_count_ms"] = json!(ms(median(
            (0..5).map(|_| time(|| compact_count(&db, &q)).0).collect()
        )));
        result["probe_ms"] = json!(ms(median(
            (0..5).map(|_| time(|| sparse(&db, &q)).0).collect()
        )));
        let mut expected = None;
        for (name, hint) in [("automatic", ""), ("target_table_scan", " NOT INDEXED")] {
            let sql = format!("SELECT count(*) FROM targets t{hint} WHERE {condition}");
            let mut samples = vec![];
            for _ in 0..5 {
                let (d, n) = time(|| {
                    db.prepare_cached(&sql)
                        .unwrap()
                        .query_row(params_from_iter(params.clone()), |r| r.get::<_, i64>(0))
                        .unwrap()
                });
                if let Some(old) = expected {
                    assert_eq!(old, n)
                } else {
                    expected = Some(n)
                }
                samples.push(d);
            }
            result[name] = json!(ms(median(samples)));
            plan(
                &db,
                &format!("{}_{}", c["name"], name),
                &sql,
                params.clone(),
            );
        }
        println!("COUNT_CONTROL {result}");
        results.push(result);
    }
    std::fs::write(
        run.join("count-controls.json"),
        serde_json::to_vec_pretty(&results).unwrap(),
    )
    .unwrap();
}

fn strings(v: &Json) -> Vec<String> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|s| s.as_str().unwrap().to_string())
        .collect()
}
fn request(v: &Json) -> SearchRequest {
    let locality = |c: &Json| match c["locality"].as_str().unwrap() {
        "EXACT" => FactLocality::Exact,
        "DOCUMENT_ROOT" => FactLocality::DocumentRoot,
        "INHERITED" => FactLocality::Inherited,
        other => panic!("locality {other}"),
    };
    let clauses = v["clauses"]
        .as_array()
        .unwrap()
        .iter()
        .map(|clause| {
            let constraints = clause["constraints"]
                .as_array()
                .unwrap()
                .iter()
                .map(|c| {
                    let predicate = c["predicate"].as_str().unwrap_or_default().to_string();
                    let value = c["value"].as_str().unwrap_or_default().to_string();
                    let op = match c["op"].as_str().unwrap_or("EXISTS") {
                        "EQ" => COp::Eq,
                        "NE" => COp::Ne,
                        "EXISTS" => COp::Exists,
                        "NOT_EXISTS" => COp::NotExists,
                        "LT" => COp::Lt,
                        "LE" => COp::Le,
                        "GT" => COp::Gt,
                        "GE" => COp::Ge,
                        "CONTAINS" => COp::Contains,
                        "PREFIX" => COp::Prefix,
                        other => panic!("op {other}"),
                    };
                    match c["kind"].as_str().unwrap() {
                        "fact" => Constraint::Fact {
                            predicate,
                            op,
                            value,
                        },
                        "at" => Constraint::FactAt {
                            predicate,
                            op,
                            value,
                            locality: locality(c),
                        },
                        "in" => Constraint::FactIn {
                            predicate,
                            values: strings(&c["values"]),
                            locality: locality(c),
                        },
                        "text" => Constraint::Text {
                            target: match c["target"].as_str().unwrap() {
                                "BODY" => TextTarget::Body,
                                "NAME" => TextTarget::Name,
                                "PATH" => TextTarget::Path,
                                "ANY" => TextTarget::Any,
                                other => panic!("text {other}"),
                            },
                            query: value,
                        },
                        other => panic!("constraint {other}"),
                    }
                })
                .collect();
            SearchClause {
                constraints,
                negated: clause["negated"].as_bool().unwrap(),
                case_sensitive: clause["sensitive"].as_bool().unwrap(),
                never: clause["never"].as_bool().unwrap(),
                residual: clause["residual"].as_u64().map(|x| x as u32),
            }
        })
        .collect();
    SearchRequest {
        clauses,
        line_mode: true,
        extension_candidates: false,
        group_by_note: v["grouped"].as_bool().unwrap(),
        body_needles: strings(&v["body_needles"]),
        name_needles: strings(&v["name_needles"]),
        sort_predicate: v["sort"].as_str().unwrap().into(),
        required_namespaces: strings(&v["required"]),
        extension_key: String::new(),
        page_size: 50,
        max_lines: 1,
        cursor: None,
    }
}

fn supported(q: &SearchRequest) -> Result<(), String> {
    if q.group_by_note {
        return Err("grouped projection is not implemented by prototype".into());
    }
    if !q.sort_predicate.is_empty() && q.sort_predicate != DUE {
        return Err("undeclared sort".into());
    }
    for clause in &q.clauses {
        if clause.residual.is_some() || clause.case_sensitive {
            return Err("residual/case-sensitive matching".into());
        }
        for c in &clause.constraints {
            let p = match c {
                Constraint::FactAt {
                    predicate,
                    locality: FactLocality::Exact,
                    ..
                }
                | Constraint::FactIn {
                    predicate,
                    locality: FactLocality::Exact,
                    ..
                } => predicate,
                _ => return Err("text or root/inherited scope is outside prototype".into()),
            };
            if ![STATE, DUE, CONNECTION].contains(&p.as_str()) {
                return Err(format!("predicate not loaded by fixture: {p}"));
            }
        }
    }
    Ok(())
}

/// One fact's row condition. NE means absence of EQ, not existence of a different value.
fn row_condition(c: &Constraint, alias: &str, params: &mut Vec<Value>) -> (String, bool) {
    let (predicate, op, values) = match c {
        Constraint::FactAt {
            predicate,
            op,
            value,
            ..
        } => (predicate, Some(*op), vec![value.clone()]),
        Constraint::FactIn {
            predicate, values, ..
        } => (predicate, None, values.clone()),
        _ => unreachable!(),
    };
    params.push(Value::Integer(fact_id(predicate)));
    let mut parts = vec![format!("{alias}.predicate=?")];
    let negative = matches!(op, Some(COp::Ne | COp::NotExists));
    match op {
        Some(COp::Exists | COp::NotExists) => {}
        None => {
            parts.push(if values.is_empty() {
                "0".into()
            } else {
                format!("{alias}.value IN ({})", vec!["?"; values.len()].join(","))
            });
            params.extend(values.into_iter().map(Value::Text));
        }
        Some(op) => {
            let symbol = match op {
                COp::Eq | COp::Ne => "=",
                COp::Lt => "<",
                COp::Le => "<=",
                COp::Gt => ">",
                COp::Ge => ">=",
                _ => panic!("unprofiled operation"),
            };
            parts.push(format!("{alias}.value{symbol}?"));
            params.push(Value::Text(values[0].clone()));
        }
    }
    (parts.join(" AND "), negative)
}
fn where_sql(q: &SearchRequest, params: &mut Vec<Value>) -> String {
    let mut clauses = vec!["t.kind=1".to_string()];
    for clause in &q.clauses {
        let mut parts = vec![];
        if clause.never {
            parts.push("0".into());
        } else {
            for c in &clause.constraints {
                let (condition, negative) = row_condition(c, "x", params);
                parts.push(format!(
                    "{}EXISTS(SELECT 1 FROM f x WHERE x.target=t.id AND {condition})",
                    if negative { "NOT " } else { "" }
                ));
            }
        }
        let joined = if parts.is_empty() {
            "1".into()
        } else {
            parts.join(" AND ")
        };
        clauses.push(format!(
            "{}({joined})",
            if clause.negated { "NOT " } else { "" }
        ));
    }
    clauses.join(" AND ")
}
fn sparse(db: &Connection, q: &SearchRequest) -> Option<Vec<i64>> {
    for clause in &q.clauses {
        if clause.negated {
            continue;
        }
        if clause.never {
            return Some(vec![]);
        }
        for c in &clause.constraints {
            let mut params = vec![];
            let (condition, negative) = row_condition(c, "x", &mut params);
            if negative {
                continue;
            }
            let sql = format!("SELECT DISTINCT x.target FROM f x WHERE {condition} LIMIT 65");
            let ids = db
                .prepare_cached(&sql)
                .unwrap()
                .query_map(params_from_iter(params), |r| r.get(0))
                .unwrap()
                .collect::<rusqlite::Result<Vec<i64>>>()
                .unwrap();
            if ids.len() <= 64 {
                return Some(ids);
            }
        }
    }
    None
}
#[derive(Clone, Debug, PartialEq)]
struct Key {
    missing: bool,
    value: String,
    id: i64,
}
fn page_sql(
    q: &SearchRequest,
    after: Option<&Key>,
    missing: bool,
    limit: usize,
    seed: Option<&[i64]>,
) -> (String, Vec<Value>) {
    let ordered = !q.sort_predicate.is_empty();
    let mut params = vec![];
    let mut condition = where_sql(q, &mut params);
    if let Some(ids) = seed {
        condition.push_str(&format!(
            " AND t.id IN ({})",
            ids.iter().map(i64::to_string).collect::<Vec<_>>().join(",")
        ));
    }
    let (select, from, order) = if ordered && !missing {
        condition.push_str(" AND d.predicate=1");
        // The fixture asserts one due value per target; this is NOT an SDK cardinality promise.
        for clause in &q.clauses {
            if clause.negated || clause.never {
                continue;
            }
            for c in &clause.constraints {
                if let Constraint::FactAt { predicate, op, .. } = c {
                    if predicate == DUE && !matches!(op, COp::NotExists | COp::Ne) {
                        let (direct, _) = row_condition(c, "d", &mut params);
                        condition.push_str(&format!(" AND {direct}"));
                    }
                }
            }
        }
        if let Some(a) = after {
            condition.push_str(" AND (d.value,d.target)>(?,?)");
            params.extend([Value::Text(a.value.clone()), Value::Integer(a.id)]);
        }
        (
            "d.target,d.value",
            "f d JOIN targets t ON t.id=d.target",
            "d.value,d.target",
        )
    } else {
        if ordered {
            condition.push_str(
                " AND NOT EXISTS(SELECT 1 FROM f d WHERE d.target=t.id AND d.predicate=1)",
            );
        }
        if let Some(a) = after {
            if !ordered || a.missing {
                condition.push_str(" AND t.id>?");
                params.push(Value::Integer(a.id));
            }
        }
        ("t.id,''", "targets t", "t.id")
    };
    params.push(Value::Integer(limit as i64));
    (
        format!("SELECT {select} FROM {from} WHERE {condition} ORDER BY {order} LIMIT ?"),
        params,
    )
}
fn compact_page(db: &Connection, q: &SearchRequest, after: Option<&Key>, limit: usize) -> Vec<Key> {
    let tx = db.unchecked_transaction().unwrap();
    let ready: bool = tx
        .query_row("SELECT ready FROM state", [], |r| r.get(0))
        .unwrap();
    assert!(ready);
    let seed = sparse(&tx, q);
    let ordered = !q.sort_predicate.is_empty();
    let mut result = vec![];
    let start = if after.is_some_and(|a| a.missing) {
        1
    } else {
        0
    };
    for phase in start..=usize::from(ordered) {
        let missing = phase == 1;
        let (sql, params) = page_sql(q, after, missing, limit + 1 - result.len(), seed.as_deref());
        result.extend(
            tx.prepare_cached(&sql)
                .unwrap()
                .query_map(params_from_iter(params), |r| {
                    Ok(Key {
                        missing,
                        value: r.get(1)?,
                        id: r.get(0)?,
                    })
                })
                .unwrap()
                .collect::<rusqlite::Result<Vec<_>>>()
                .unwrap(),
        );
        if result.len() > limit {
            break;
        }
    }
    result
}
fn compact_count(db: &Connection, q: &SearchRequest) -> i64 {
    let tx = db.unchecked_transaction().unwrap();
    let ready: bool = tx
        .query_row("SELECT ready FROM state", [], |r| r.get(0))
        .unwrap();
    assert!(ready);
    let mut params = vec![];
    let mut condition = where_sql(q, &mut params);
    if let Some(ids) = sparse(&tx, q) {
        condition.push_str(&format!(
            " AND t.id IN ({})",
            ids.iter().map(i64::to_string).collect::<Vec<_>>().join(",")
        ));
    }
    tx.query_row(
        &format!("SELECT count(*) FROM targets t WHERE {condition}"),
        params_from_iter(params),
        |r| r.get(0),
    )
    .unwrap()
}
fn project(origin: &Connection, data: &Data, keys: &[Key], limit: usize) -> usize {
    hydrate(
        origin,
        data,
        &keys
            .iter()
            .take(limit)
            .map(|k| Row {
                target: k.id,
                value: Value::Text(k.value.clone()),
            })
            .collect::<Vec<_>>(),
    )
}
fn reference(data: &Data, q: &SearchRequest) -> Vec<Key> {
    let mut matches = vec![];
    for t in &data.targets {
        if t.kind != 1 {
            continue;
        }
        let holds = q.clauses.iter().all(|clause| {
            let matched = !clause.never
                && clause.constraints.iter().all(|c| match c {
                    Constraint::FactIn {
                        predicate, values, ..
                    } => t
                        .facts
                        .iter()
                        .any(|(p, v)| p == predicate && values.contains(v)),
                    Constraint::FactAt {
                        predicate,
                        op,
                        value,
                        ..
                    } => {
                        let values: Vec<_> = t
                            .facts
                            .iter()
                            .filter(|(p, _)| p == predicate)
                            .map(|(_, v)| v)
                            .collect();
                        match op {
                            COp::Exists => !values.is_empty(),
                            COp::NotExists => values.is_empty(),
                            COp::Ne => !values.contains(&value),
                            _ => values.iter().any(|v| match op {
                                COp::Eq => *v == value,
                                COp::Lt => *v < value,
                                COp::Le => *v <= value,
                                COp::Gt => *v > value,
                                COp::Ge => *v >= value,
                                _ => unreachable!(),
                            }),
                        }
                    }
                    _ => unreachable!(),
                });
            matched != clause.negated
        });
        if holds {
            let due = t
                .facts
                .iter()
                .find(|(p, _)| p == &q.sort_predicate)
                .map(|(_, v)| v.clone());
            matches.push(Key {
                missing: !q.sort_predicate.is_empty() && due.is_none(),
                value: due.unwrap_or_default(),
                id: t.id,
            });
        }
    }
    matches.sort_by(|a, b| (a.missing, &a.value, a.id).cmp(&(b.missing, &b.value, b.id)));
    matches
}
fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1000.0
}
fn native(
    db: &Connection,
    q: &SearchRequest,
    after: Option<crate::search::Rank>,
) -> crate::search::SearchPage {
    super::super::super::legacy_search(db, q, None, "matrix".into(), "matrix".into(), after, vec![])
        .unwrap()
}
fn check_page(data: &Data, page: &crate::search::SearchPage, keys: &[Key]) {
    assert!(page.index_complete, "{:?}", page.issues);
    let actual: Vec<_> = page
        .hits
        .iter()
        .map(|h| (h.rel_path.as_str(), h.items[0].key as i64))
        .collect();
    let expected: Vec<_> = keys
        .iter()
        .take(page.hits.len())
        .map(|k| {
            let t = &data.targets[k.id as usize - 1];
            (data.sources[t.source as usize - 1].path.as_str(), t.anchor)
        })
        .collect();
    assert_eq!(actual, expected);
}
fn storage(db: &Connection, path: &std::path::Path) -> Json {
    db.execute("VACUUM INTO ?", [path.to_str().unwrap()])
        .unwrap();
    let objects = if let Ok(mut s) =
        db.prepare("SELECT name,sum(pgsize) FROM dbstat GROUP BY name ORDER BY sum(pgsize) DESC")
    {
        s.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))
            .unwrap()
            .map(|r| {
                let (n, b) = r.unwrap();
                json!({"name":n,"bytes":b})
            })
            .collect::<Vec<_>>()
    } else {
        vec![]
    };
    json!({"allocated_bytes":size(db),"vacuum_bytes":std::fs::metadata(path).unwrap().len(),"objects":objects})
}

#[test]
#[ignore = "manual DSL matrix: GILUNE_MATRIX_DIR and GILUNE_CROSS_PLUGIN_INDEX"]
fn profile_exported_dsl_matrix() {
    let out = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_DIR").unwrap());
    let run = tempfile::Builder::new()
        .prefix("run-")
        .tempdir_in(&out)
        .unwrap()
        .keep();
    let origin = Connection::open_with_flags(
        std::env::var("GILUNE_CROSS_PLUGIN_INDEX").unwrap(),
        OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    let current = Connection::open_with_flags(
        out.join("schema6-vault/.gilune/local/index.db"),
        OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    for db in [&origin, &current] {
        crate::search_text::register(db).unwrap();
    }
    for (db, expected) in [(&origin, "5"), (&current, "6")] {
        let schema: String = db
            .query_row(
                "SELECT value FROM index_meta WHERE key='index_schema_version'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            schema, expected,
            "comparison must identify the actual production schemas"
        );
    }
    let data = load(&origin);
    assert_eq!(data.targets.iter().filter(|t| t.kind == 1).count(), 50_000);
    assert!(data
        .targets
        .iter()
        .all(|t| t.facts.iter().filter(|(p, _)| p == DUE).count() <= 1));
    let now = load(&current);
    assert_eq!(now.targets.len(), data.targets.len());
    for (a, b) in now.targets.iter().zip(&data.targets) {
        assert_eq!((&a.facts, a.kind, a.anchor), (&b.facts, b.kind, b.anchor));
    }
    let facts = Connection::open(run.join("facts.db")).unwrap();
    facts
        .execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;")
        .unwrap();
    let (build, ()) = time(|| {
        facts.execute_batch("BEGIN").unwrap();
        Registry::default().install(&facts);
        facts.execute_batch("CREATE TABLE f(target INTEGER NOT NULL REFERENCES targets(id) ON DELETE CASCADE,predicate INTEGER NOT NULL,value TEXT NOT NULL,PRIMARY KEY(target,predicate,value)) WITHOUT ROWID; CREATE INDEX f_value ON f(predicate,value,target);").unwrap();
        base(&facts, &data);
        for t in &data.targets {
            facts_for(&facts, t);
        }
        facts.execute_batch("COMMIT; ANALYZE").unwrap();
    });
    let records = Connection::open(run.join("records.db")).unwrap();
    let mut registry = Registry::default();
    tasks::declare(&mut registry);
    people::declare(&mut registry);
    records.execute_batch("BEGIN").unwrap();
    registry.install(&records);
    base(&records, &data);
    for t in &data.targets {
        records_for(&records, &registry, t);
    }
    records.execute_batch("COMMIT").unwrap();
    let storage = json!({"old_whole":storage(&origin,&run.join("old-vacuum.db")),"transitional_whole":storage(&current,&run.join("current-vacuum.db")),
        "compact_query_only":storage(&facts,&run.join("facts-vacuum.db")),"records_query_only":storage(&records,&run.join("records-vacuum.db")),"compact_build_ms":ms(build)});
    println!("STORAGE {storage}");
    let cases: Vec<Json> =
        serde_json::from_slice(&std::fs::read(out.join("dsl-cases.json")).unwrap()).unwrap();
    let mut results = vec![];
    for case in cases {
        let label = case["name"].as_str().unwrap();
        if let Ok(filter) = std::env::var("GILUNE_MATRIX_FILTER") {
            if !filter.split(',').any(|name| name == label) {
                continue;
            }
        }
        let q = request(&case);
        println!("BEGIN {label} DSL={}", case["dsl"]);
        if q.clauses.iter().any(|c| c.residual.is_some()) {
            results.push(
                json!({"case":case,"unsupported":"Kotlin residual","ffi_ms":case["residual_ms"]}),
            );
            continue;
        }
        crate::probe::reset_file_reads();
        let (first, old) = time(|| native(&origin, &q, None));
        let mut old_samples = vec![];
        for _ in 0..3 {
            let (d, p) = time(|| native(&origin, &q, None));
            assert_eq!(p, old);
            old_samples.push(d);
        }
        let mut result = json!({"case":case,"matches":old.total_hits,"old_first_ms":ms(first),"old_warm_ms":ms(median(old_samples))});
        match supported(&q) {
            Err(reason) => {
                result["unsupported"] = json!(reason);
            }
            Ok(()) => {
                let expected = reference(&data, &q);
                assert_eq!(expected.len() as u64, old.total_hits, "{label}");
                check_page(&data, &old, &expected);
                let (first, first_keys) = time(|| compact_page(&facts, &q, None, 50));
                assert_eq!(
                    first_keys,
                    expected.iter().take(51).cloned().collect::<Vec<_>>()
                );
                let mut select = vec![];
                let mut projected = vec![];
                let mut counts = vec![];
                for _ in 0..5 {
                    select.push(time(|| compact_page(&facts, &q, None, 50)).0);
                    projected.push(
                        time(|| {
                            let keys = compact_page(&facts, &q, None, 50);
                            project(&origin, &data, &keys, 50)
                        })
                        .0,
                    );
                    let (d, count) = time(|| compact_count(&facts, &q));
                    assert_eq!(count, expected.len() as i64);
                    counts.push(d);
                }
                result["compact_first_select_ms"] = json!(ms(first));
                result["compact_select_ms"] = json!(ms(median(select)));
                result["compact_project_ms"] = json!(ms(median(projected)));
                result["compact_count_ms"] = json!(ms(median(counts)));
                // Complete ordered identity comparison, without projection, on every supported filter.
                let mut all = vec![];
                let mut after = None;
                let (enumerate, ()) = time(|| loop {
                    let keys = compact_page(&facts, &q, after.as_ref(), 500);
                    let more = keys.len() > 500;
                    all.extend(keys.into_iter().take(500));
                    after = all.last().cloned();
                    if !more {
                        break;
                    }
                });
                assert_eq!(all, expected, "all ordered identities: {label}");
                result["compact_all_select_ms"] = json!(ms(enumerate));
                if [
                    "all",
                    "alice_month_due",
                    "all_due_nullable",
                    "not_either",
                    "missing_due_sorted",
                ]
                .contains(&label)
                {
                    let mut large = q.clone();
                    large.page_size = 500;
                    let (d, p) = time(|| native(&origin, &large, None));
                    check_page(&data, &p, &expected);
                    result["old_first500_ms"] = json!(ms(d));
                    result["compact_first500_project_ms"] = json!(ms(time(|| {
                        let keys = compact_page(&facts, &q, None, 500);
                        project(&origin, &data, &keys, 500)
                    })
                    .0));
                    if expected.len() > 9000 {
                        let after = &expected[8999];
                        let t = &data.targets[after.id as usize - 1];
                        let rank = crate::search::Rank {
                            priority: if q.sort_predicate.is_empty() {
                                1
                            } else {
                                u8::from(after.missing)
                            },
                            value: after.value.clone(),
                            matches: std::cmp::Reverse(0),
                            path: data.sources[t.source as usize - 1].path.clone(),
                            line: t.anchor as u64,
                        };
                        let (d, p) = time(|| native(&origin, &large, Some(rank)));
                        check_page(&data, &p, &expected[9000..]);
                        result["old_page19_ms"] = json!(ms(d));
                        result["compact_page19_project_ms"] = json!(ms(time(|| {
                            let keys = compact_page(&facts, &q, Some(after), 500);
                            assert_eq!(
                                keys,
                                expected
                                    .iter()
                                    .skip(9000)
                                    .take(501)
                                    .cloned()
                                    .collect::<Vec<_>>()
                            );
                            project(&origin, &data, &keys, 500)
                        })
                        .0));
                    }
                    let (d, ()) = time(|| {
                        for chunk in expected.chunks(500) {
                            project(&origin, &data, chunk, 500);
                        }
                    });
                    result["compact_all_projection_only_ms"] = json!(ms(d));
                    let seed = sparse(&facts, &q);
                    let (sql, params) = page_sql(&q, None, false, 51, seed.as_deref());
                    plan(&facts, label, &sql, params);
                    let mut params = vec![];
                    let condition = where_sql(&q, &mut params);
                    plan(
                        &facts,
                        &format!("count_{label}"),
                        &format!("SELECT count(*) FROM targets t WHERE {condition}"),
                        params,
                    );
                }
            }
        }
        if [
            "all",
            "alice_month_due",
            "all_due_nullable",
            "body_sparse",
            "root_property",
            "not_week",
        ]
        .contains(&label)
        {
            let (d, p) = time(|| native(&current, &q, None));
            assert_eq!(p.total_hits, old.total_hits);
            result["transitional_first_ms"] = json!(ms(d));
        }
        assert_eq!(crate::probe::file_reads(), 0);
        println!("RESULT {result}");
        results.push(result);
        std::fs::write(
            run.join("results.json"),
            serde_json::to_vec_pretty(&json!({"storage":storage,"results":results})).unwrap(),
        )
        .unwrap();
    }
    if std::env::var("GILUNE_MATRIX_FILTER").is_ok() {
        let cases: Vec<Json> =
            serde_json::from_slice(&std::fs::read(out.join("dsl-cases.json")).unwrap()).unwrap();
        let q = request(cases.iter().find(|c| c["name"] == "all").unwrap());
        let mut controls = vec![];
        for (label, action) in [
            ("initial", ""),
            ("large_cache", "PRAGMA cache_size=-32768;"),
            (
                "reset_small_cache",
                "PRAGMA cache_size=-2000; PRAGMA shrink_memory;",
            ),
            (
                "checkpointed",
                "PRAGMA wal_checkpoint(TRUNCATE); PRAGMA shrink_memory;",
            ),
        ] {
            facts.execute_batch(action).unwrap();
            let elapsed = ms(median(
                (0..5)
                    .map(|_| {
                        time(|| {
                            let n = compact_count(&facts, &q);
                            assert_eq!(n, 50000)
                        })
                        .0
                    })
                    .collect(),
            ));
            let r = json!({"phase":label,"complete_count_ms":elapsed});
            println!("CACHE_CONTROL {r}");
            controls.push(r);
        }
        std::fs::write(
            run.join("cache-controls.json"),
            serde_json::to_vec_pretty(&controls).unwrap(),
        )
        .unwrap();
    }
    println!("MATRIX_OUTPUT {}", run.display());
}

#[test]
#[ignore = "read-only verification of all old/current corpus facts"]
fn verify_matrix_complete_fact_identity() {
    let run = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_RUN").unwrap());
    let db = Connection::open_with_flags(
        run.join("current-vacuum.db"),
        OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    crate::search_text::register(&db).unwrap();
    let uri = format!(
        "file:{}?mode=ro",
        run.join("old-vacuum.db")
            .to_str()
            .unwrap()
            .replace('\\', "/")
    );
    db.execute("ATTACH DATABASE ? AS baseline", [uri]).unwrap();
    let fields = "rel_path,line,item_key,source_version,namespace,predicate,value,num,typed";
    let sql=format!("SELECT count(*) FROM (SELECT {fields},count(*) FROM baseline.facts GROUP BY {fields} EXCEPT SELECT {fields},count(*) FROM main.facts GROUP BY {fields})");
    let differences: i64 = db.query_row(&sql, [], |r| r.get(0)).unwrap();
    assert_eq!(differences, 0);
    let counts: (i64, i64) = db
        .query_row(
            "SELECT (SELECT count(*) FROM baseline.facts),(SELECT count(*) FROM main.facts)",
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .unwrap();
    assert_eq!(counts.0, counts.1);
    println!(
        "CORPUS_IDENTITY facts={} grouped_value_differences={differences}",
        counts.0
    );
}

#[test]
#[ignore = "manual planner/statistics controls after the DSL matrix"]
fn profile_dsl_matrix_statistics_controls() {
    let out = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_DIR").unwrap());
    let run = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_RUN").unwrap());
    let db = Connection::open(run.join("old-vacuum.db")).unwrap();
    crate::search_text::register(&db).unwrap();
    let facts = Connection::open(run.join("facts.db")).unwrap();
    let cases: Vec<Json> =
        serde_json::from_slice(&std::fs::read(out.join("dsl-cases.json")).unwrap()).unwrap();
    let labels = [
        "all",
        "not_done",
        "alice_month_due",
        "not_bob_month",
        "not_either",
        "not_week",
        "all_due_nullable",
        "body_sparse",
        "root_property",
    ];
    let selected: Vec<_> = cases
        .iter()
        .filter(|c| labels.contains(&c["name"].as_str().unwrap()))
        .collect();
    let explain = |db: &Connection, label: &str, sql: &str, params: Vec<Value>| {
        println!("CONTROL_SQL {label}: {sql}\nPARAMS {params:?}");
        for row in db
            .prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
            .unwrap()
            .query_map(params_from_iter(params), |r| r.get::<_, String>(3))
            .unwrap()
        {
            println!("CONTROL_PLAN {label}: {}", row.unwrap());
        }
    };
    for c in &selected {
        let q = request(c);
        let mut required = q.required_namespaces.clone();
        required.push("frontmatter".into());
        required.sort();
        required.dedup();
        let (sql, params) = super::super::super::sql(&q, &required);
        explain(&db, &format!("before_{}", c["name"]), &sql, params);
    }
    let mut vacuum_only = std::collections::BTreeMap::new();
    for c in &selected {
        let label = c["name"].as_str().unwrap();
        if ["not_done", "alice_month_due"].contains(&label) {
            let (d, p) = time(|| native(&db, &request(c), None));
            println!(
                "CONTROL_VACUUM {label}: ms={} matches={}",
                ms(d),
                p.total_hits
            );
            vacuum_only.insert(label.to_string(), ms(d));
        }
    }
    db.execute_batch("ANALYZE").unwrap();
    let mut results = vec![];
    for c in selected {
        let q = request(c);
        let label = c["name"].as_str().unwrap();
        let mut required = q.required_namespaces.clone();
        required.push("frontmatter".into());
        required.sort();
        required.dedup();
        let (sql, params) = super::super::super::sql(&q, &required);
        explain(&db, &format!("analyzed_{label}"), &sql, params);
        // A slow baseline does not need repeated unbounded minutes to demonstrate a regression.
        let handle = db.get_interrupt_handle();
        let (done, receive) = std::sync::mpsc::channel();
        let guard = std::thread::spawn(move || {
            if receive.recv_timeout(Duration::from_secs(30)).is_err() {
                handle.interrupt()
            }
        });
        let (d, p) = time(|| {
            super::super::super::legacy_search(
                &db,
                &q,
                None,
                "matrix".into(),
                "matrix".into(),
                None,
                vec![],
            )
        });
        let _ = done.send(());
        guard.join().unwrap();
        let mut r = json!({"name":label,"vacuum_only_ms":vacuum_only.get(label),"analyzed_ms":ms(d),"timeout_or_error":p.as_ref().err().map(ToString::to_string),"matches":p.as_ref().ok().map(|p|p.total_hits)});
        if p.is_ok() && d < Duration::from_secs(5) {
            r["analyzed_warm_ms"] = json!(ms(median(
                (0..3).map(|_| time(|| native(&db, &q, None)).0).collect()
            )));
        }
        if supported(&q).is_ok() {
            let mut params = vec![];
            let condition = where_sql(&q, &mut params);
            let sql = format!("SELECT count(*) FROM targets t WHERE {condition}");
            explain(&facts, &format!("compact_count_{label}"), &sql, params);
        }
        println!("CONTROL_RESULT {r}");
        results.push(r);
    }
    std::fs::write(
        run.join("controls.json"),
        serde_json::to_vec_pretty(&results).unwrap(),
    )
    .unwrap();
}

#[test]
#[ignore = "manual full-fact storage experiment; no executable API compatibility claim"]
fn profile_full_compact_storage() {
    let run = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_RUN").unwrap());
    let source = Connection::open_with_flags(
        run.join("current-vacuum.db"),
        OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    crate::search_text::register(&source).unwrap();
    let experiment = run.join("full-compact-unvacuumed.db");
    source
        .execute("VACUUM INTO ?", [experiment.to_str().unwrap()])
        .unwrap();
    let db = Connection::open(experiment).unwrap();
    crate::search_text::register(&db).unwrap();
    let unbound: i64 = db
        .query_row(
            "SELECT count(*) FROM facts WHERE target_id IS NULL OR predicate_id IS NULL",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(
        unbound, 0,
        "This storage-only fixture cannot drop stale or legacy evidence"
    );
    db.execute_batch("BEGIN;
        CREATE TABLE compact_facts(id INTEGER PRIMARY KEY,target_id INTEGER NOT NULL,predicate_id INTEGER NOT NULL,value TEXT NOT NULL,num REAL,typed INTEGER NOT NULL);
        INSERT INTO compact_facts SELECT rowid,target_id,predicate_id,value,num,typed FROM facts;
        CREATE INDEX compact_target_value ON compact_facts(target_id,predicate_id,value);
        CREATE INDEX compact_predicate_value ON compact_facts(predicate_id,value,target_id);
        CREATE INDEX compact_predicate_number ON compact_facts(predicate_id,num,target_id);
        CREATE INDEX compact_predicate_folded ON compact_facts(predicate_id,gilune_fold(value),target_id);
    ").unwrap();
    let count: i64 = db
        .query_row("SELECT count(*) FROM facts", [], |r| r.get(0))
        .unwrap();
    let copied: i64 = db
        .query_row("SELECT count(*) FROM compact_facts", [], |r| r.get(0))
        .unwrap();
    assert_eq!(count, copied);
    let difference:i64=db.query_row("SELECT count(*) FROM (
        SELECT rowid,rel_path,line,item_key,source_version,namespace,predicate,value,num,typed FROM facts
        EXCEPT
        SELECT f.id,s.rel_path,CASE WHEN t.kind=1 THEN t.local_key END,CASE WHEN t.kind=2 THEN t.local_key END,
            s.source_version,p.namespace,p.predicate,f.value,f.num,f.typed
        FROM compact_facts f JOIN index_targets t ON t.id=f.target_id JOIN source_maps s ON s.id=t.source_id
            JOIN index_predicates p ON p.id=f.predicate_id
        )",[],|r|r.get(0)).unwrap();
    assert_eq!(
        difference, 0,
        "all wide facts must reconstruct exactly, including duplicates and provenance"
    );
    db.execute_batch(
        "DROP TRIGGER source_targets_insert; DROP TRIGGER source_targets_update;
        DROP TRIGGER source_targets_before_update; DROP TRIGGER source_targets_delete;
        DROP TRIGGER IF EXISTS fact_ids_insert; DROP TRIGGER fact_ids_update;
        DROP TABLE facts; COMMIT;",
    )
    .unwrap();
    let size = storage(&db, &run.join("full-compact-vacuum.db"));
    let result = json!({"facts":count,"unbound":unbound,"roundtrip_differences":difference,"storage":size,
        "limits":"Storage-only. Includes all facts, duplicate row identity, typed/folded indexes, targets, maps and FTS. Maintenance triggers and production query APIs are not implemented. No stale/legacy rows existed in this fixture."});
    println!("FULL_STORAGE {result}");
    std::fs::write(
        run.join("full-storage.json"),
        serde_json::to_vec_pretty(&result).unwrap(),
    )
    .unwrap();
}
