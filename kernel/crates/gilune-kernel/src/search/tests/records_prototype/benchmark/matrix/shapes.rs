//! Same authored tasks and DSL requests, varying only document boundaries.
use super::*;

#[test]
#[ignore = "manual document-shape benchmark: GILUNE_SHAPE_DIR and GILUNE_MATRIX_DIR"]
fn profile_document_shapes() {
    let root = std::path::PathBuf::from(std::env::var("GILUNE_SHAPE_DIR").unwrap());
    let matrix = std::path::PathBuf::from(std::env::var("GILUNE_MATRIX_DIR").unwrap());
    let cases: Vec<Json> =
        serde_json::from_slice(&std::fs::read(matrix.join("dsl-cases.json")).unwrap()).unwrap();
    let run = tempfile::Builder::new()
        .prefix("query-")
        .tempdir_in(&root)
        .unwrap()
        .keep();
    let mut results = vec![];
    for shape in [10, 100, 1000, 10000] {
        let origin = Connection::open_with_flags(
            root.join(format!("items-{shape}/.gilune/local/index.db")),
            OpenFlags::SQLITE_OPEN_READ_ONLY,
        )
        .unwrap();
        crate::search_text::register(&origin).unwrap();
        let data = load(&origin);
        assert_eq!(data.targets.iter().filter(|t| t.kind == 1).count(), 10000);
        assert_eq!(data.sources.len(), 10000 / shape);
        let facts = Connection::open(run.join(format!("facts-{shape}.db"))).unwrap();
        facts
            .execute_batch("PRAGMA journal_mode=WAL; BEGIN")
            .unwrap();
        Registry::default().install(&facts);
        facts.execute_batch("CREATE TABLE f(target INTEGER NOT NULL REFERENCES targets(id) ON DELETE CASCADE,predicate INTEGER NOT NULL,value TEXT NOT NULL,PRIMARY KEY(target,predicate,value)) WITHOUT ROWID; CREATE INDEX f_value ON f(predicate,value,target);").unwrap();
        base(&facts, &data);
        for target in &data.targets {
            facts_for(&facts, target);
        }
        facts.execute_batch("COMMIT; ANALYZE").unwrap();
        for case in cases
            .iter()
            .filter(|c| ["alice_month", "alice_month_due"].contains(&c["name"].as_str().unwrap()))
        {
            let mut q = request(case);
            supported(&q).unwrap();
            let expected = reference(&data, &q);
            assert_eq!(expected.len(), 3500);
            for page_size in [50, 500] {
                q.page_size = page_size;
                crate::probe::reset_file_reads();
                let mut old_ms = vec![];
                let mut select_ms = vec![];
                let mut project_ms = vec![];
                let mut count_ms = vec![];
                for _ in 0..3 {
                    let (d, page) = time(|| native(&origin, &q, None));
                    assert_eq!(page.total_hits, expected.len() as u64);
                    assert_eq!(page.hits.len(), page_size as usize);
                    check_page(&data, &page, &expected);
                    old_ms.push(d);
                    let (d, keys) = time(|| compact_page(&facts, &q, None, page_size as usize));
                    assert_eq!(
                        keys,
                        expected
                            .iter()
                            .take(page_size as usize + 1)
                            .cloned()
                            .collect::<Vec<_>>()
                    );
                    select_ms.push(d);
                    project_ms.push(time(|| project(&origin, &data, &keys, page_size as usize)).0);
                    let (d, count) = time(|| compact_count(&facts, &q));
                    assert_eq!(count, expected.len() as i64);
                    count_ms.push(d);
                }
                let mut all = vec![];
                let mut after = None;
                let (enumerate, ()) = time(|| loop {
                    let keys = compact_page(&facts, &q, after.as_ref(), page_size as usize);
                    let more = keys.len() > page_size as usize;
                    all.extend(keys.into_iter().take(page_size as usize));
                    after = all.last().cloned();
                    if !more {
                        break;
                    }
                });
                assert_eq!(all, expected);
                assert_eq!(crate::probe::file_reads(), 0, "queries read indexed data only");
                let row = json!({"items_per_document":shape,"documents":data.sources.len(),"case":case["name"],"dsl":case["dsl"],"page_size":page_size,"matches":expected.len(),"existing_page_count_ms":ms(median(old_ms)),"compact_select_ms":ms(median(select_ms)),"projection_only_ms":ms(median(project_ms)),"compact_count_ms":ms(median(count_ms)),"enumerate_keys_ms":ms(enumerate)});
                println!("SHAPE_QUERY {row}");
                results.push(row);
                std::fs::write(
                    run.join("results.json"),
                    serde_json::to_vec_pretty(&results).unwrap(),
                )
                .unwrap();
            }
        }
    }
    assert_eq!(results.len(), 16);
    println!("SHAPE_OUTPUT {}", run.display());
}
