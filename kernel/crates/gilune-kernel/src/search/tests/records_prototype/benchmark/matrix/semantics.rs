//! Exhaustive small scalar-fact fixtures complement the real DSL performance corpus.
use super::*;

#[test]
fn multivalued_scalar_constraints_negation_and_paging_match_independent_truth_table() {
    let db = Connection::open_in_memory().unwrap();
    Registry::default().install(&db);
    db.execute_batch("CREATE TABLE f(target INTEGER NOT NULL,predicate INTEGER NOT NULL,value TEXT NOT NULL,PRIMARY KEY(target,predicate,value)) WITHOUT ROWID; CREATE INDEX f_value ON f(predicate,value,target); INSERT INTO sources VALUES(1,'fixture.md','v1');").unwrap();
    let variants = [
        (STATE, "open"),
        (STATE, "done"),
        (DUE, "2026-08-01"),
        (DUE, "2026-09-15"),
        (DUE, "2026-10-01"),
        (CONNECTION, "assign:alice"),
        (CONNECTION, "assign:bob"),
    ];
    for mask in 0..128i64 {
        db.execute("INSERT INTO targets VALUES(?,1,1,?)", [mask + 1, mask + 1])
            .unwrap();
        for (bit, (p, v)) in variants.iter().enumerate() {
            if mask & (1 << bit) != 0 {
                db.execute(
                    "INSERT INTO f VALUES(?,?,?)",
                    rusqlite::params![mask + 1, fact_id(p), v],
                )
                .unwrap();
            }
        }
    }
    // A page-level fact must not become an item match.
    db.execute_batch(
        "INSERT INTO targets VALUES(1000,1,0,0); INSERT INTO f VALUES(1000,0,'open');",
    )
    .unwrap();
    let constraints = [
        (STATE, COp::Eq, "open"),
        (STATE, COp::Ne, "done"),
        (DUE, COp::Ge, "2026-09-01"),
        (DUE, COp::Lt, "2026-10-01"),
        (DUE, COp::NotExists, ""),
        (CONNECTION, COp::Eq, "assign:alice"),
        (CONNECTION, COp::Eq, "assign:bob"),
    ];
    for a in 0..constraints.len() {
        for b in 0..constraints.len() {
            for negated in [false, true] {
                let selected = [constraints[a], constraints[b]];
                let q = SearchRequest {
                    clauses: vec![SearchClause {
                        constraints: selected
                            .iter()
                            .map(|(p, op, v)| Constraint::FactAt {
                                predicate: (*p).into(),
                                op: *op,
                                value: (*v).into(),
                                locality: FactLocality::Exact,
                            })
                            .collect(),
                        negated,
                        case_sensitive: false,
                        residual: None,
                        never: false,
                    }],
                    line_mode: true,
                    extension_candidates: false,
                    group_by_note: false,
                    body_needles: vec![],
                    name_needles: vec![],
                    sort_predicate: String::new(),
                    required_namespaces: vec![],
                    extension_key: String::new(),
                    page_size: 3,
                    max_lines: 1,
                    cursor: None,
                };
                let expected: Vec<i64> = (0..128i64)
                    .filter(|mask| {
                        let bit = |n: u32| (*mask & (1i64 << n)) != 0i64;
                        let truth = [
                            bit(0),
                            !bit(1),
                            bit(3) || bit(4),
                            bit(2) || bit(3),
                            !(bit(2) || bit(3) || bit(4)),
                            bit(5),
                            bit(6),
                        ];
                        (truth[a] && truth[b]) != negated
                    })
                    .map(|mask| mask + 1)
                    .collect();
                assert_eq!(compact_count(&db, &q), expected.len() as i64);
                let mut actual = vec![];
                let mut after = None;
                loop {
                    let keys = compact_page(&db, &q, after.as_ref(), 3);
                    let more = keys.len() > 3;
                    let page: Vec<_> = keys.into_iter().take(3).collect();
                    actual.extend(page.iter().map(|k| k.id));
                    after = page.last().cloned();
                    if !more {
                        break;
                    }
                }
                assert_eq!(actual, expected, "constraints {a},{b}, negated={negated}");
            }
        }
    }
}
