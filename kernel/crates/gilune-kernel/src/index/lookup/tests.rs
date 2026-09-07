use super::*;
use crate::index::scoped::ScopedFact;
use gilune_document::{Location, TextSnapshot};
use std::sync::Arc;

pub(super) mod profile;
mod ordered_prototype;

#[test]
fn old_index_rebuild_preserves_sources_and_populates_folded_keys() {
    let (dir, s) = setup();
    let source = "kind=entry\nkey=Álpha\nname=Mixed case\n";
    write(&s, "entry.md", source);
    s.with_index(|db| {
        db.execute_batch("DROP INDEX facts_compact_predicate_folded; UPDATE index_meta SET value='4' WHERE key='index_schema_version';")?;
        Ok(())
    }).unwrap();
    drop(s);
    let s = Store::open(dir.path()).unwrap();
    s.register_indexer("fixture", 1, &[], Arc::new(Labels))
        .unwrap();
    s.reconcile().unwrap();
    assert_eq!(
        s.lookup(&request("ál")).unwrap().hits[0].rel_path,
        "entry.md"
    );
    assert_eq!(
        std::fs::read_to_string(s.vault().join("entry.md")).unwrap(),
        source
    );
    s.with_index(|db| {
        assert_eq!(
            super::super::meta(db, "index_schema_version")?,
            Some(crate::index::SCHEMA_VERSION.to_string())
        );
        Ok(())
    })
    .unwrap();
}

#[test]
fn conjunctions_negative_alternatives_duplicates_and_multiple_sort_values() {
    let (_dir, s) = setup();
    write(
        &s,
        "a.md",
        "kind=entry\nkey=alpha\nkey=alpha\nname=Zulu\nname=Beta\n",
    );
    write(&s, "b.md", "kind=entry\nkey=beta\nname=Alpha\n");
    write(&s, "c.md", "kind=entry\n");
    write(&s, "d.md", "kind=other\nkey=alpha\nname=Alpha\n");
    let mut q = request("");
    q.page_size = 30;
    q.alternatives = vec![
        LookupAlternative {
            constraints: vec![fact("name", ConstraintOp::NotExists, "")],
            priority: 2,
        },
        LookupAlternative {
            constraints: vec![
                fact("key", ConstraintOp::Ne, "beta"),
                fact("key", ConstraintOp::Prefix, "a"),
                fact("name", ConstraintOp::Exists, ""),
            ],
            priority: 1,
        },
        LookupAlternative {
            constraints: vec![fact("key", ConstraintOp::Eq, "alpha")],
            priority: 3,
        },
    ];
    let rows = s.lookup(&q).unwrap();
    assert_eq!(rows.total_hits, 2);
    assert_eq!(
        rows.hits
            .iter()
            .map(|r| (r.rel_path.as_str(), r.priority))
            .collect::<Vec<_>>(),
        vec![("a.md", 1), ("c.md", 2)]
    );
    q.alternatives.push(LookupAlternative {
        constraints: vec![],
        priority: 0,
    });
    let rows = s.lookup(&q).unwrap();
    assert_eq!(rows.total_hits, 3);
    assert_eq!(
        rows.hits
            .iter()
            .map(|r| r.rel_path.as_str())
            .collect::<Vec<_>>(),
        vec!["b.md", "a.md", "c.md"]
    );
    assert!(rows.hits.iter().all(|r| r.priority == 0));
    q.constraints = vec![fact("key", ConstraintOp::NotExists, "")];
    assert_eq!(s.lookup(&q).unwrap().hits[0].rel_path, "c.md");
}

struct Labels;
impl NoteIndexer for Labels {
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
    ) -> std::result::Result<Vec<ScopedFact>, String> {
        Ok(s.text()
            .lines()
            .filter_map(|line| line.split_once('='))
            .map(|(key, value)| ScopedFact {
                location: if key == "child" {
                    s.location_at(s.text().find("- child").unwrap() as u64)
                        .unwrap()
                } else {
                    Location::DocumentRoot
                },
                predicate: if key == "child" {
                    "fixture.key".into()
                } else {
                    format!("fixture.{key}")
                },
                value: value.into(),
            })
            .collect())
    }
}
fn setup() -> (tempfile::TempDir, Store) {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    store
        .register_indexer("fixture", 1, &[], Arc::new(Labels))
        .unwrap();
    (dir, store)
}
fn write(store: &Store, path: &str, text: &str) {
    std::fs::write(store.vault().join(path), text).unwrap();
    store.reconcile().unwrap();
}
fn fact(predicate: &str, op: ConstraintOp, value: &str) -> Constraint {
    Constraint::FactAt {
        predicate: format!("fixture.{predicate}"),
        op,
        value: value.into(),
        locality: FactLocality::DocumentRoot,
    }
}
fn request(fragment: &str) -> LookupRequest {
    LookupRequest {
        constraints: vec![fact("kind", ConstraintOp::Eq, "entry")],
        alternatives: vec![
            LookupAlternative {
                constraints: vec![fact("key", ConstraintOp::Eq, fragment)],
                priority: 0,
            },
            LookupAlternative {
                constraints: vec![fact("key", ConstraintOp::Prefix, fragment)],
                priority: 1,
            },
            LookupAlternative {
                constraints: vec![fact("name", ConstraintOp::Prefix, fragment)],
                priority: 2,
            },
        ],
        sort_predicates: vec!["fixture.name".into(), "fixture.key".into()],
        display_predicates: vec!["fixture.key".into(), "fixture.name".into()],
        required_namespaces: vec!["fixture".into()],
        page_size: 2,
        cursor: None,
    }
}

#[test]
fn alternatives_rank_deduplicate_and_keep_collisions_visible() {
    let (_dir, s) = setup();
    for (path, source) in [
        ("a.md", "kind=entry\nkey=alpine\nname=Zulu\n"),
        ("b.md", "kind=entry\nkey=al\nname=Zebra\n"),
        ("c.md", "kind=entry\nkey=al\nname=Omega\n"),
        ("d.md", "kind=entry\nkey=other\nname=alpha\n"),
        ("e.md", "kind=entry\nkey=alpine\nname=alpha\n"),
        ("f.md", "kind=other\nkey=al\nname=alpha\n"),
        ("g.md", "kind=entry\n- child\nchild=al\n"),
    ] {
        write(&s, path, source);
    }
    crate::probe::reset_file_reads();
    let mut q = request("al");
    let mut all = Vec::new();
    loop {
        let page = s.lookup(&q).unwrap();
        assert!(page.index_complete, "{:?}", page.issues);
        assert_eq!(page.total_hits, 5);
        all.extend(page.hits);
        q.cursor = page.next_cursor;
        if q.cursor.is_none() {
            break;
        }
    }
    assert_eq!(
        all.iter()
            .map(|h| (h.rel_path.as_str(), h.priority))
            .collect::<Vec<_>>(),
        vec![
            ("c.md", 0),
            ("b.md", 0),
            ("a.md", 1),
            ("e.md", 1),
            ("d.md", 2)
        ]
    );
    assert!(all
        .iter()
        .all(|h| h.facts.len() == 2 && !h.source_version.is_empty()));
    assert_eq!(crate::probe::file_reads(), 0);
    write(&s, "wild.md", "kind=entry\nkey=a%_\\'x\nname=literal\n");
    assert_eq!(
        s.lookup(&request("a%_\\'")).unwrap().hits[0].rel_path,
        "wild.md"
    );
    q = request("does not match");
    assert_eq!(s.lookup(&q).unwrap().total_hits, 0);
    q.alternatives.clear();
    q.sort_predicates.clear();
    assert_eq!(s.lookup(&q).unwrap().total_hits, 7);
}

#[test]
fn cursor_and_source_evidence_are_bound_to_query_and_generation() {
    let (_dir, s) = setup();
    for p in ["a.md", "b.md", "c.md"] {
        write(&s, p, "kind=entry\nkey=alpha\nname=Alpha\n");
    }
    let mut q = request("al");
    q.cursor = s.lookup(&q).unwrap().next_cursor;
    let mut changed = q.clone();
    changed.alternatives[0].priority = 9;
    assert!(matches!(
        s.lookup(&changed),
        Err(KernelError::SearchCursorExpired)
    ));
    std::fs::write(s.vault().join("a.md"), "kind=other\n").unwrap();
    assert_eq!(s.lookup(&request("al")).unwrap().total_hits, 3);
    s.register_indexer("fixture", 2, &[], Arc::new(Labels))
        .unwrap();
    s.advance_index().unwrap();
    let page = s.lookup(&request("al")).unwrap();
    assert!(!page.index_complete);
    assert_eq!(page.total_hits, 2);
    assert!(matches!(
        s.lookup(&q),
        Err(KernelError::SearchCursorExpired)
    ));
    s.reconcile().unwrap();
    let rebuilt = s.lookup(&request("al")).unwrap();
    assert!(rebuilt.index_complete);
    assert_eq!(rebuilt.total_hits, 2);
    let mut missing = request("al");
    missing.required_namespaces.push("unavailable".into());
    let p = s.lookup(&missing).unwrap();
    assert!(!p.index_complete);
    assert!(p.hits.is_empty());
}

#[test]
fn negatives_scopes_bounds_and_undeclared_producers() {
    let (_dir, s) = setup();
    write(&s, "a.md", "kind=entry\nkey=alpha\nname=Alpha\n");
    write(&s, "b.md", "kind=entry\nkey=beta\n");
    let mut q = request("");
    q.alternatives.clear();
    q.constraints
        .push(fact("name", ConstraintOp::NotExists, ""));
    assert_eq!(s.lookup(&q).unwrap().hits[0].rel_path, "b.md");
    q.constraints.push(Constraint::FactIn {
        predicate: "fixture.key".into(),
        values: vec!["alpha".into()],
        locality: FactLocality::DocumentRoot,
    });
    assert_eq!(s.lookup(&q).unwrap().total_hits, 0);
    q = request("");
    q.page_size = 0;
    assert!(s.lookup(&q).is_err());
    q = request("");
    q.constraints[0] = Constraint::Fact {
        predicate: "fixture.kind".into(),
        op: ConstraintOp::Eq,
        value: "entry".into(),
    };
    assert!(s.lookup(&q).is_err());
    q = request("");
    q.required_namespaces = vec![FRONT_MATTER_NAMESPACE.into()];
    assert_eq!(s.lookup(&q).unwrap().total_hits, 0);
    q = request("");
    q.alternatives.clear();
    q.page_size = 1;
    let first = s.lookup(&q).unwrap();
    assert_eq!(first.hits[0].rel_path, "a.md");
    q.cursor = first.next_cursor;
    assert_eq!(s.lookup(&q).unwrap().hits[0].rel_path, "b.md");
}

#[test]
fn complete_lookup_pages_beyond_ten_thousand_indexed_roots() {
    let (_dir, s) = setup();
    write(&s, "seed.md", "kind=entry\nkey=alpha\nname=Alpha\n");
    // Scale the derived database independently of filesystem/indexer throughput.
    s.with_index(|db| {
        db.execute_batch("BEGIN;")?;
        let seed: Vec<crate::index::FactRow> = db.prepare("SELECT line,item_key,source_version,predicate,value,num,typed FROM facts WHERE rel_path='seed.md' AND namespace='fixture' ORDER BY rowid")?
            .query_map([],|r|Ok(crate::index::FactRow {line:r.get(0)?,item_key:r.get(1)?,source_version:r.get(2)?,predicate:r.get(3)?,value:r.get(4)?,num:r.get(5)?,typed:r.get(6)?}))?.collect::<rusqlite::Result<_>>()?;
        for i in 0..10_005 {
            let path=format!("{i:05}.md");
            db.execute("INSERT INTO source_maps(rel_path,source_version,layout) SELECT ?1,source_version,layout FROM source_maps WHERE rel_path='seed.md'",[&path])?;
            db.execute("INSERT INTO fact_sources SELECT namespace,?1,source_version FROM fact_sources WHERE rel_path='seed.md'",[&path])?;
            crate::index::writer::put_facts(db,"fixture",&path,&seed,None)?;
        }
        db.execute_batch("COMMIT;")?;Ok(())
    }).unwrap();
    let mut q = request("al");
    q.page_size = 30;
    s.lookup(&q).unwrap();
    let mut first_times = Vec::new();
    for _ in 0..3 {
        let started = std::time::Instant::now();
        let first = s.lookup(&q).unwrap();
        first_times.push(started.elapsed());
        assert_eq!(first.hits.len(), 30);
    }
    first_times.sort();
    q.page_size = 500;
    let enumeration_started = std::time::Instant::now();
    let mut paths = BTreeSet::new();
    crate::probe::reset_file_reads();
    loop {
        let p = s.lookup(&q).unwrap();
        assert!(p.index_complete);
        assert_eq!(p.total_hits, 10_006);
        for h in p.hits {
            assert!(paths.insert(h.rel_path));
        }
        q.cursor = p.next_cursor;
        if q.cursor.is_none() {
            break;
        }
    }
    assert_eq!(paths.len(), 10_006);
    assert_eq!(crate::probe::file_reads(), 0);
    println!(
        "LOOKUP 10006 indexed roots; first 30 median {:?}; all 500/page {:?}; zero source reads",
        first_times[1],
        enumeration_started.elapsed()
    );
    // A selective lookup must not regress to population-wide sorting or a
    // correlated scan of every equal base value. Count SQLite work, not clock
    // time, so this remains meaningful on slow/contended test machines.
    s.with_index(|db| {
        db.execute(
            "UPDATE facts SET value='needle' WHERE rel_path='seed.md' AND predicate='fixture.key'",
            [],
        )?;
        let mut selective = request("need");
        selective.page_size = 30;
        let (ctes, mut args, order) = sql(&selective);
        args.extend([Value::Integer(30), Value::Integer(0)]);
        let mut stmt = db.prepare(&format!(
            "{ctes} SELECT rel_path,COUNT(*) OVER() FROM ranked ORDER BY {order} LIMIT ? OFFSET ?"
        ))?;
        let rows = stmt
            .query_map(params_from_iter(args), |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, u64>(1)?))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        assert_eq!(rows, vec![("seed.md".into(), 1)]);
        let steps = stmt.get_status(rusqlite::StatementStatus::VmStep);
        assert!(
            steps < 3_000,
            "selective lookup performed {steps} VM steps across 10,006 roots"
        );
        assert_eq!(stmt.get_status(rusqlite::StatementStatus::AutoIndex), 0);
        Ok(())
    })
    .unwrap();
}
