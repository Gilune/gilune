//! Reproducible release-only diagnostic harness. Never changes a user's index.
//! All hooks in the implementation are cfg(test), absent from shipped binaries.
use super::*;
use rusqlite::StatementStatus;
use std::cell::RefCell;
use std::time::{Duration, Instant};

type Phases = Vec<(&'static str, Duration)>;
type LookupRows = Vec<(String, String, u32, u64)>;

#[test]
#[ignore = "manual isolated release page size and hydration profile"]
fn profile_lookup_page_size_and_hydration() {
    profile_page_sizes(10_006);
}

#[test]
#[ignore = "manual isolated release 50,000-root lookup profile"]
fn profile_lookup_fifty_thousand_matches() {
    profile_page_sizes(50_000);
}

fn profile_page_sizes(root_count: u64) {
    println!("Preparing {root_count} indexed roots; setup excluded from query timings");
    let (_dir, store) = roots_count(root_count);
    println!("Fixture ready");
    // Every broad query matches the same roots; only LIMIT changes.
    for (fragment, size, total) in [
        ("al", 1, root_count),
        ("al", 30, root_count),
        ("al", 500, root_count),
        ("needle", 1, 1),
    ] {
        if fragment == "needle" {
            store.with_index(|db| {
                db.execute("UPDATE facts SET value='needle' WHERE rel_path='seed.md' AND predicate='fixture.key'", [])?;
                Ok(())
            }).unwrap();
        }
        let mut query = request(fragment);
        query.page_size = size;
        store.lookup(&query).unwrap();
        crate::probe::reset_file_reads();
        let mut samples: BTreeMap<&str, Vec<Duration>> = BTreeMap::new();
        let mut totals = Vec::new();
        for _ in 0..7 {
            TRACE.with(|t| *t.borrow_mut() = Some((Instant::now(), Vec::new())));
            let started = Instant::now();
            let page = store.lookup(&query).unwrap();
            totals.push(started.elapsed());
            assert_eq!(page.total_hits, total);
            assert_eq!(page.hits.len(), size as usize);
            let (_, phases) = TRACE.with(|t| t.borrow_mut().take().unwrap());
            for (name, duration) in phases {
                samples.entry(name).or_default().push(duration);
            }
        }
        assert_eq!(crate::probe::file_reads(), 0);
        println!(
            "PROFILE matches={total} page_size={size} total_median={:?}",
            median(totals)
        );
        for (name, times) in samples {
            println!("  {name}: {:?}", median(times));
        }
    }
}

#[test]
#[ignore = "manual isolated release page latency measurement"]
fn profile_nineteenth_lookup_page() {
    let (_dir, store) = roots();
    let mut first = request("al");
    first.page_size = 500;
    let mut nineteenth = first.clone();
    // Obtain the real continuation cursor. None of these preceding pages is
    // included in the measured nineteenth-page call.
    for _ in 0..18 {
        nineteenth.cursor = Some(store.lookup(&nineteenth).unwrap().next_cursor.unwrap());
    }
    crate::probe::reset_file_reads();
    let started = Instant::now();
    let page = store.lookup(&nineteenth).unwrap();
    let initial = started.elapsed();
    assert_eq!(page.hits.len(), 500);
    assert_eq!(page.total_hits, 10_006);
    assert_eq!(page.hits.first().unwrap().rel_path, "09000.md");
    assert_eq!(page.hits.last().unwrap().rel_path, "09499.md");
    let mut first_times = Vec::new();
    let mut nineteenth_times = Vec::new();
    for run in 0..7 {
        // Alternate order to reduce systematic first/second measurement bias.
        for late in if run % 2 == 0 {
            [false, true]
        } else {
            [true, false]
        } {
            let request = if late { &nineteenth } else { &first };
            let started = Instant::now();
            let page = store.lookup(request).unwrap();
            let elapsed = started.elapsed();
            assert_eq!(page.hits.len(), 500);
            if late {
                nineteenth_times.push(elapsed);
            } else {
                first_times.push(elapsed);
            }
        }
    }
    assert_eq!(crate::probe::file_reads(), 0);
    println!("LOOKUP 10006 roots; 500/page; page 19 initial {initial:?}; page 19 median {:?}, range {:?}..{:?}; page 1 median {:?}; excludes preceding pages; zero source reads", median(nineteenth_times.clone()), nineteenth_times.iter().min().unwrap(), nineteenth_times.iter().max().unwrap(), median(first_times));
}

thread_local! { static TRACE: RefCell<Option<(Instant, Phases)>> = const { RefCell::new(None) }; }
pub(crate) fn mark(name: &'static str) {
    TRACE.with(|trace| {
        if let Some((last, phases)) = trace.borrow_mut().as_mut() {
            phases.push((name, last.elapsed()));
            *last = Instant::now();
        }
    });
}
fn median(mut values: Vec<Duration>) -> Duration {
    values.sort();
    values[values.len() / 2]
}

fn roots() -> (tempfile::TempDir, Store) {
    roots_count(10_006)
}

pub(super) fn roots_count(root_count: u64) -> (tempfile::TempDir, Store) {
    assert!(root_count > 0);
    let (dir, store) = setup();
    write(&store, "seed.md", "kind=entry\nkey=alpha\nname=Alpha\n");
    store.with_index(|db| {
        db.execute_batch("BEGIN;")?;
        // Freeze seed receipts once: filtering the growing receipt table by
        // path alone on each iteration makes fixture creation quadratic.
        db.execute_batch("CREATE TEMP TABLE profile_seed_receipts AS SELECT namespace,source_version FROM fact_sources WHERE rel_path='seed.md';")?;
        for i in 0..root_count - 1 {
            let path=format!("{i:05}.md");
            db.execute("INSERT INTO source_maps(rel_path,source_version,layout) SELECT ?1,source_version,layout FROM source_maps WHERE rel_path='seed.md'",[&path])?;
            db.execute("INSERT INTO fact_sources SELECT namespace,?1,source_version FROM profile_seed_receipts",[&path])?;
            db.execute("INSERT INTO facts(namespace,rel_path,line,item_key,source_version,predicate,value,predicate_id,target_id) SELECT f.namespace,?1,f.line,f.item_key,f.source_version,f.predicate,f.value,f.predicate_id,t.id FROM facts f JOIN source_maps s ON s.rel_path=?1 AND s.source_version=f.source_version LEFT JOIN index_targets t ON t.source_id=s.id AND t.kind=CASE WHEN f.item_key IS NOT NULL THEN 2 WHEN f.line IS NOT NULL THEN 1 ELSE 0 END AND t.local_key=COALESCE(f.item_key,f.line,0) WHERE f.rel_path='seed.md'",[&path])?;
        }
        db.execute_batch("DROP TABLE profile_seed_receipts; COMMIT;")?; Ok(())
    }).unwrap();
    (dir, store)
}
fn statement(q: &LookupRequest) -> (String, Vec<Value>) {
    let (ctes, mut args, order) = sql(q);
    args.extend([Value::Integer(q.page_size.into()), Value::Integer(0)]);
    (format!("{ctes} SELECT rel_path,source_version,priority,COUNT(*) OVER() FROM ranked WHERE priority < 4294967296 ORDER BY {order} LIMIT ? OFFSET ?"),args)
}
pub(super) fn dump_plan(db: &Connection, sql: &str, args: &[Value]) -> String {
    db.prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
        .unwrap()
        .query_map(params_from_iter(args), |r| {
            Ok(format!(
                "{} | {} | {}",
                r.get::<_, i64>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, String>(3)?
            ))
        })
        .unwrap()
        .collect::<rusqlite::Result<Vec<_>>>()
        .unwrap()
        .join("\n")
}
fn run_sql(db: &Connection, sql: &str, args: &[Value]) -> (Duration, LookupRows, String) {
    let mut times = Vec::new();
    let mut result = Vec::new();
    let mut stats = String::new();
    for run in 0..8 {
        let mut s = db.prepare(sql).unwrap();
        let start = Instant::now();
        result = s
            .query_map(params_from_iter(args), |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
            })
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        if run > 0 {
            times.push(start.elapsed());
        }
        stats = format!(
            "vm_steps={} fullscan_steps={} sorts={} autoindex_rows={} statement_bytes={}",
            s.get_status(StatementStatus::VmStep),
            s.get_status(StatementStatus::FullscanStep),
            s.get_status(StatementStatus::Sort),
            s.get_status(StatementStatus::AutoIndex),
            s.get_status(StatementStatus::MemUsed)
        );
    }
    (median(times), result, stats)
}

#[test]
#[ignore = "manual isolated release lookup profile; run with --ignored --nocapture --test-threads=1"]
fn profile_lookup_stages_sql_and_indexes() {
    let (_dir, store) = roots();
    let mut q = request("al");
    q.page_size = 30;
    let output = std::env::var_os("GILUNE_LOOKUP_PROFILE_OUT").map(std::path::PathBuf::from);
    let mut report = String::new();
    store
        .with_index(|db| {
            let (raw, args) = statement(&q);
            println!("INITIAL PLAN\n{}", dump_plan(db, &raw, &args));
            Ok(())
        })
        .unwrap();
    store.lookup(&q).unwrap();
    let mut samples: BTreeMap<&str, Vec<Duration>> = BTreeMap::new();
    let mut totals = Vec::new();
    for _ in 0..7 {
        TRACE.with(|t| *t.borrow_mut() = Some((Instant::now(), Vec::new())));
        let started = Instant::now();
        let page = store.lookup(&q).unwrap();
        totals.push(started.elapsed());
        assert_eq!(page.total_hits, 10_006);
        let (_, phases) = TRACE.with(|t| t.borrow_mut().take().unwrap());
        for (name, duration) in phases {
            samples.entry(name).or_default().push(duration);
        }
    }
    report.push_str(&format!(
        "Direct Store::lookup total median {:?}\n",
        median(totals)
    ));
    for (name, samples) in samples {
        report.push_str(&format!("{name}: {:?}\n", median(samples)));
    }
    store.with_index(|db| {
        report.push_str(&format!("SQLite {}\n",rusqlite::version()));
        for pragma in ["temp_store","cache_size","page_size","page_count","automatic_index"] {
            let value:i64=db.query_row(&format!("PRAGMA {pragma}"),[],|r|r.get(0))?;report.push_str(&format!("{pragma}={value}\n"));
        }
        let indexes=db.prepare("SELECT name,coalesce(sql,'primary/unique autoindex') FROM sqlite_master WHERE type='index' ORDER BY name")?.query_map([],|r|Ok(format!("{}: {}",r.get::<_,String>(0)?,r.get::<_,String>(1)?)))?.collect::<rusqlite::Result<Vec<_>>>()?;
        report.push_str(&format!("INDEXES\n{}\n",indexes.join("\n")));
        let (raw,args)=statement(&q);
        let (elapsed,baseline,stats)=run_sql(db,&raw,&args);
        report.push_str(&format!("BASELINE main statement {elapsed:?}; {stats}\nPLAN\n{}\n",dump_plan(db,&raw,&args)));
        let mut prepared=db.prepare(&raw)?;
        { let mut rows=prepared.query(params_from_iter(args.iter()))?;while rows.next()?.is_some(){} }
        let expanded=prepared.expanded_sql().unwrap();
        if let Some(path)=&output {std::fs::create_dir_all(path).unwrap();std::fs::write(path.join("lookup-baseline.sql"),&expanded).unwrap();}

        for (name,query) in [("no alternatives",{let mut x=q.clone();x.alternatives.clear();x}), ("no sort fields",{let mut x=q.clone();x.sort_predicates.clear();x})] {
            let (sql,args)=statement(&query);let (time,rows,stats)=run_sql(db,&sql,&args);
            assert_eq!(rows[0].3,10_006);report.push_str(&format!("ABLATION {name}: {time:?}; {stats}\n"));
        }
        let inline=expanded.replace(" AS MATERIALIZED", " AS NOT MATERIALIZED");
        let (time,rows,stats)=run_sql(db,&inline,&[]);assert_eq!(rows,baseline);
        report.push_str(&format!("EXPERIMENT not materialized: {time:?}; {stats}\n{}\n",dump_plan(db,&inline,&[])));

        db.execute_batch("PRAGMA temp_store=MEMORY;")?;
        let (time,rows,stats)=run_sql(db,&raw,&args);assert_eq!(rows,baseline);
        report.push_str(&format!("EXPERIMENT temp_store MEMORY: {time:?}; {stats}\n"));
        db.execute_batch("PRAGMA temp_store=DEFAULT;")?;

        let before:i64=db.query_row("PRAGMA page_count",[],|r|r.get(0))?;
        db.execute_batch("CREATE INDEX profile_root_cover ON facts(namespace,rel_path,source_version,predicate,value) WHERE line IS NULL AND item_key IS NULL;")?;
        let after:i64=db.query_row("PRAGMA page_count",[],|r|r.get(0))?;
        let (time,rows,stats)=run_sql(db,&raw,&args);assert_eq!(rows,baseline);
        report.push_str(&format!("EXPERIMENT root covering index: {time:?}; added_pages={}; {stats}\n{}\n",after-before,dump_plan(db,&raw,&args)));
        db.execute_batch("DROP INDEX profile_root_cover;")?;

        let free_before:i64=db.query_row("PRAGMA freelist_count",[],|r|r.get(0))?;
        db.execute_batch("DROP INDEX facts_predicate_folded;")?;
        let free_after:i64=db.query_row("PRAGMA freelist_count",[],|r|r.get(0))?;
        let (time,rows,stats)=run_sql(db,&raw,&args);assert_eq!(rows,baseline);
        report.push_str(&format!("EXPERIMENT current query without folded index: {time:?}; {stats}\nFOLDED INDEX pages={}\n",free_after-free_before));
        let build=Instant::now();
        db.execute_batch("CREATE INDEX facts_predicate_folded ON facts(predicate,gilune_fold(value));")?;
        report.push_str(&format!("FOLDED INDEX rebuild {:?}\n",build.elapsed()));

        // A second distribution to distinguish work proportional to matches
        // from work proportional to every root. All fixture keys are artificial.
        db.execute("UPDATE facts SET value='entry.' || rel_path WHERE predicate='fixture.key'",[])?;
        db.execute("UPDATE facts SET value='label ' || rel_path WHERE predicate='fixture.name'",[])?;
        for fragment in ["entry.00000", "does-not-exist", ""] {
            let mut selective=request(fragment);selective.page_size=30;
            if fragment.is_empty(){selective.alternatives.clear();}
            let (sql,args)=statement(&selective);let (time,rows,stats)=run_sql(db,&sql,&args);
            report.push_str(&format!("DISTRIBUTION fragment={fragment:?}: {time:?}; total={}; {stats}\n",rows.first().map(|r|r.3).unwrap_or(0)));
        }
        Ok(())
    }).unwrap();
    for fragment in ["entry.00000", "does-not-exist", ""] {
        let mut query = request(fragment);
        query.page_size = 30;
        if fragment.is_empty() {
            query.alternatives.clear();
        }
        let mut times = Vec::new();
        for run in 0..8 {
            let started = Instant::now();
            let page = store.lookup(&query).unwrap();
            assert_eq!(
                page.total_hits,
                match fragment {
                    "entry.00000" => 1,
                    "" => 10_006,
                    _ => 0,
                }
            );
            if run > 0 {
                times.push(started.elapsed());
            }
        }
        report.push_str(&format!(
            "DIRECT fragment={fragment:?}: {:?}\n",
            median(times)
        ));
    }
    if let Some(path) = output {
        std::fs::write(path.join("lookup-profile.txt"), &report).unwrap();
    }
    println!("{report}");
}
