//! Actual exported DSL through both production executor versions on identical data.
use super::*;

#[test]
#[ignore = "manual compact query plans: GILUNE_COMPACT_DB and GILUNE_MATRIX_DIR"]
fn profile_compact_query_plans() {
    let db = Connection::open(std::env::var("GILUNE_COMPACT_DB").unwrap()).unwrap();
    crate::search_text::register(&db).unwrap();
    let cases: Vec<Json> = serde_json::from_slice(&std::fs::read(std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_DIR").unwrap()).join("dsl-cases.json")).unwrap()).unwrap();
    db.execute_batch("BEGIN").unwrap();
    for name in ["all","open","alice_month","either_month"] {
        let case = cases.iter().find(|c|c["name"]==name).unwrap();
        let q = request(case);
        let mut required=q.required_namespaces.clone(); required.push(crate::index::FRONT_MATTER_NAMESPACE.into()); required.sort(); required.dedup();
        let filter=crate::search::target_query::compile(&db,&q,&required).unwrap();
        let sql=format!("SELECT COUNT(*) FROM {} WHERE {}",crate::search::target_query::FROM,filter.condition);
        let plan=db.prepare(&format!("EXPLAIN QUERY PLAN {sql}")).unwrap().query_map(params_from_iter(&filter.params),|r|r.get::<_,String>(3)).unwrap().collect::<rusqlite::Result<Vec<_>>>().unwrap();
        let mut samples=vec![];
        for _ in 0..5 {
            let mut stmt=db.prepare(&sql).unwrap();
            let (elapsed,total)=time(||stmt.query_row(params_from_iter(&filter.params),|r|r.get::<_,u64>(0)).unwrap());
            samples.push(json!({"ms":ms(elapsed),"total":total,"steps":stmt.get_status(rusqlite::StatementStatus::VmStep)}));
        }
        println!("COMPACT_PLAN {}",json!({"name":name,"sql":sql,"params":filter.params.iter().map(|v|format!("{v:?}")).collect::<Vec<_>>(),"plan":plan,"samples":samples}));
        for phase in ["after_page","after_cache_clear"] {
            if phase == "after_page" {
                let (elapsed,_)=time(||crate::search::items::search(&db,&q,None,"matrix".into(),"matrix".into(),None,vec![]).unwrap());
                println!("COMPACT_COMPLETE {}",json!({"name":name,"ms":ms(elapsed),"phases":crate::search::target_query::TIMINGS.with(|t|t.get())}));
            } else { db.flush_prepared_statement_cache(); }
            let mut stmt=db.prepare(&sql).unwrap();
            let (elapsed,_)=time(||stmt.query_row(params_from_iter(&filter.params),|r|r.get::<_,u64>(0)).unwrap());
            println!("COMPACT_CACHE {}",json!({"name":name,"phase":phase,"ms":ms(elapsed),"steps":stmt.get_status(rusqlite::StatementStatus::VmStep)}));
        }

    }
}

#[test]
#[ignore = "manual production executor matrix: GILUNE_MATRIX_RUN and GILUNE_MATRIX_DIR"]
fn profile_production_target_executor() {
    let run = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_RUN").unwrap());
    let out = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_DIR").unwrap());
    let origin = Connection::open_with_flags(
        run.join("current-vacuum.db"),
        OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    crate::search_text::register(&origin).unwrap();
    let reuse = std::env::var("GILUNE_COMPACT_DB").ok().map(std::path::PathBuf::from);
    let temp = if let Some(path) = &reuse { path.parent().unwrap().to_path_buf() } else { tempfile::Builder::new()
        .prefix("production-")
        .tempdir_in(&out)
        .unwrap()
        .keep() };
    if reuse.is_none() { origin
        .execute("VACUUM INTO ?", [temp.join("index.db").to_str().unwrap()])
        .unwrap(); }
    let db = Connection::open(temp.join("index.db")).unwrap();
    crate::search_text::register(&db).unwrap();
    if reuse.is_none() {
    db.execute_batch("CREATE INDEX IF NOT EXISTS index_targets_source_start ON index_targets(source_id,kind,start_byte); CREATE INDEX IF NOT EXISTS lines_path_field_line ON lines(rel_path,field,line);
        DROP TRIGGER IF EXISTS source_targets_insert; DROP TRIGGER IF EXISTS source_targets_update;
        DROP TRIGGER IF EXISTS source_targets_delete; DROP TRIGGER IF EXISTS source_targets_before_update;
        DROP TRIGGER IF EXISTS fact_ids_insert; DROP TRIGGER IF EXISTS fact_ids_update;
        DROP INDEX facts_compact_target_value; DROP INDEX facts_compact_predicate_value;
        ALTER TABLE facts RENAME TO old_facts;").unwrap();
    db.execute_batch(include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/index/storage.sql"))).unwrap();
    db.execute_batch("BEGIN;
        INSERT INTO fact_locations(rel_path,source_version,line,item_key) SELECT DISTINCT rel_path,source_version,line,item_key FROM old_facts;
        INSERT INTO fact_values(id,location_id,predicate_id,target_id,value,num,typed)
        SELECT f.rowid,l.id,f.predicate_id,f.target_id,f.value,f.num,f.typed FROM old_facts f JOIN fact_locations l
            ON l.rel_path=f.rel_path AND l.source_version IS f.source_version AND l.line IS f.line AND l.item_key IS f.item_key;
        DROP TABLE old_facts; UPDATE index_meta SET value='9' WHERE key='index_schema_version'; COMMIT;").unwrap();
    db.execute("ATTACH DATABASE ?1 AS baseline",[run.join("current-vacuum.db").to_str().unwrap()]).unwrap();
    let columns="rowid,rel_path,line,item_key,source_version,namespace,predicate,value,num,typed";
    for (left,right) in [("baseline.facts","main.facts"),("main.facts","baseline.facts")] {
        let different:u64=db.query_row(&format!("SELECT COUNT(*) FROM (SELECT {columns} FROM {left} EXCEPT SELECT {columns} FROM {right})"),[],|r|r.get(0)).unwrap();
        assert_eq!(different,0,"compact migration preserves every fact and duplicate");
    }
    db.execute_batch("DETACH DATABASE baseline;").unwrap();
    let lifecycle = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/index/targets.sql"));
    db.execute_batch(&lifecycle[lifecycle.find("-- A removed snapshot").unwrap()..]).unwrap();
    db.execute_batch("VACUUM; BEGIN;").unwrap();
    } else { db.execute_batch("BEGIN").unwrap(); }
    let storage = json!({"before_bytes":std::fs::metadata(run.join("current-vacuum.db")).unwrap().len(), "after_bytes":std::fs::metadata(temp.join("index.db")).unwrap().len(),"facts":db.query_row("SELECT count(*) FROM fact_values",[],|r|r.get::<_,u64>(0)).unwrap(),"locations":db.query_row("SELECT count(*) FROM fact_locations",[],|r|r.get::<_,u64>(0)).unwrap()});
    std::fs::write(temp.join("storage.json"),serde_json::to_vec_pretty(&storage).unwrap()).unwrap();
    println!("COMPACT_STORAGE {storage}");
    let cases: Vec<Json> =
        serde_json::from_slice(&std::fs::read(out.join("dsl-cases.json")).unwrap()).unwrap();
    let selected=std::env::var("GILUNE_PRODUCTION_CASES").unwrap_or_else(|_|"all,open,alice_month,alice_month_due,not_week,two_assignees,root_property,missing_root_property,alice_grouped_due".into());
    let mut results = vec![];
    for case in cases
        .iter()
        .filter(|c| selected == "*" || selected.split(',').any(|n| Some(n) == c["name"].as_str()))
    {
        let q = request(case);
        let (old_time, expected) = time(|| native(&origin, &q, None));
        let mut samples = vec![];
        for sample in 0..3 {
            crate::probe::reset_file_reads();
            crate::search::target_projection::WORK.with(|w| w.set((0, 0)));
            let (duration, actual) = time(|| {
                crate::search::items::search(
                    &db,
                    &q,
                    None,
                    "matrix".into(),
                    "matrix".into(),
                    None,
                    vec![],
                )
                .unwrap()
            });
            assert_eq!(actual, expected, "{}", case["name"]);
            assert_eq!(crate::probe::file_reads(), 0);
            let phases = if crate::search::target_query::supports(&q) {
                crate::search::target_query::TIMINGS.with(|t| t.get().map(Some))
            } else {
                [None; 4]
            };
            let work = crate::search::target_projection::WORK.with(|w| w.get());
            let (page_only, slice) = time(|| crate::search::items::execute(&db,&q,None,"matrix".into(),"matrix".into(),None,vec![],crate::search::Execution::Page).unwrap());
            assert_eq!(slice.hits,expected.hits);
            assert_eq!(slice.next_cursor,expected.next_cursor);
            samples.push(json!({"scan_ms":if crate::search::target_query::supports(&q) { None } else { Some(crate::search::target_scan::TIMINGS.with(|t|t.get())) },"sample":sample,"page_only_ms":ms(page_only),"total_ms":ms(duration),"compile_ms":phases[0],"count_ms":phases[1],"select_ms":phases[2],"project_ms":phases[3],"projected_items":work.0,"projected_lines":work.1}));
        }
        let result = json!({"name":case["name"],"dsl":case["dsl"],"sort":q.sort_predicate,"grouped":q.group_by_note,"total_hits":expected.total_hits,"legacy_ms":ms(old_time),"executor":if crate::search::target_query::supports(&q) { "sql_targets" } else { "target_text" },"samples":samples});
        println!("PRODUCTION_QUERY {result}");
        results.push(result);
    }
    std::fs::write(
        temp.join("results.json"),
        serde_json::to_vec_pretty(&results).unwrap(),
    )
    .unwrap();
    println!("PRODUCTION_OUTPUT {}", temp.display());
}
