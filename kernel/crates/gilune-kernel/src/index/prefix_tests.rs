use super::*;
use rusqlite::{params_from_iter, Connection};

#[test]
fn indexed_prefix_preserves_literal_unicode_semantics_without_read_time_folding() {
    let db = Connection::open_in_memory().unwrap();
    let folds = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let observed = folds.clone();
    db.create_scalar_function(
        "gilune_fold",
        1,
        rusqlite::functions::FunctionFlags::SQLITE_UTF8
            | rusqlite::functions::FunctionFlags::SQLITE_DETERMINISTIC,
        move |ctx| {
            observed.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            Ok(crate::search_text::fold(&ctx.get::<String>(0)?))
        },
    )
    .unwrap();
    db.execute_batch(SCHEMA).unwrap();
    let mut values: Vec<String> = [
        "",
        "ALpha",
        "a%_\\'x",
        "a[bc]*?",
        "İstanbul",
        "ı",
        "Σςσ",
        "Straße",
        "STRASSE",
        "é",
        "e\u{301}",
        "😀a",
        "\0a",
        "a\0b",
        "\u{d7ff}x",
        "\u{e000}x",
        "\u{10ffff}",
        "\u{10ffff}\u{10ffff}x",
        "a\u{10ffff}x",
        "b",
    ]
    .into_iter()
    .map(str::to_string)
    .collect();
    // Exercise each UTF-8 width transition and case-fold expansion boundary.
    for scalar in [
        0x7e, 0x7f, 0x80, 0x7ff, 0x800, 0xd7fe, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10fffe, 0x10ffff,
    ] {
        values.push(format!("{}tail", char::from_u32(scalar).unwrap()));
    }
    for (id, value) in values.iter().enumerate() {
        db.execute("INSERT INTO facts(rel_path,namespace,predicate,value) VALUES (?1,'test','test.key',?2)", rusqlite::params![id.to_string(), value]).unwrap();
    }
    let prefixes: BTreeSet<String> = values
        .iter()
        .flat_map(|v| {
            v.char_indices()
                .map(|(i, _)| v[..i].to_string())
                .chain(std::iter::once(v.clone()))
        })
        .chain(["missing".to_string(), "AL".to_string()])
        .collect();
    for prefix in prefixes {
        let piece = fact_piece_from("test.key", ConstraintOp::Prefix, &prefix, "facts");
        let expected = db.prepare("SELECT rel_path FROM facts WHERE predicate='test.key' AND instr(gilune_fold(value),?1)=1 ORDER BY rel_path").unwrap().query_map([crate::search_text::fold(&prefix)], |r|r.get::<_,String>(0)).unwrap().collect::<rusqlite::Result<Vec<_>>>().unwrap();
        folds.store(0, std::sync::atomic::Ordering::Relaxed);
        let actual = db
            .prepare(&format!("{} ORDER BY rel_path", piece.sql))
            .unwrap()
            .query_map(params_from_iter(&piece.params), |r| r.get::<_, String>(0))
            .unwrap()
            .collect::<rusqlite::Result<Vec<_>>>()
            .unwrap();
        assert_eq!(actual, expected, "prefix {prefix:?}");
        assert_eq!(
            folds.load(std::sync::atomic::Ordering::Relaxed),
            0,
            "index supplies precomputed folds for {prefix:?}"
        );
    }
    let piece = fact_piece_from("test.key", ConstraintOp::Prefix, "al", "facts");
    let plan = db
        .prepare(&format!("EXPLAIN QUERY PLAN {}", piece.sql))
        .unwrap()
        .query_map(params_from_iter(&piece.params), |r| r.get::<_, String>(3))
        .unwrap()
        .collect::<rusqlite::Result<Vec<_>>>()
        .unwrap()
        .join("\n");
    assert!(
        plan.contains("facts_compact_predicate_folded (predicate_id=? AND <expr>>? AND <expr><?)"),
        "{plan}"
    );
    db.execute("UPDATE facts SET value='ALtered' WHERE rel_path='0'", [])
        .unwrap();
    let changed: i64 = db.query_row("SELECT count(*) FROM facts WHERE predicate='test.key' AND gilune_fold(value)>='alte' AND gilune_fold(value)<'altf'",[],|r|r.get(0)).unwrap();
    assert_eq!(changed, 1);
    db.execute("DELETE FROM facts WHERE rel_path='0'", [])
        .unwrap();
    let removed: i64 = db.query_row("SELECT count(*) FROM facts WHERE predicate='test.key' AND gilune_fold(value)>='alte' AND gilune_fold(value)<'altf'",[],|r|r.get(0)).unwrap();
    assert_eq!(removed, 0);
}
