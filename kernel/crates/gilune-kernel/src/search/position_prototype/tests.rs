use super::*;

#[test]
fn revision_positions_preserve_nested_unicode_and_crlf_snippets() -> Result<()> {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    std::fs::write(dir.path().join(".gilune-local"), "").unwrap(); // local-only vault (ADR 0007, 2026-10-22)
    let q = SearchRequest {
        clauses: vec![],
        line_mode: false,
        extension_candidates: false,
        group_by_note: true,
        body_needles: vec![],
        name_needles: vec![],
        sort_predicate: String::new(),
        required_namespaces: vec![],
        extension_key: String::new(),
        page_size: 50,
        max_lines: 5,
        cursor: None,
    };
    for text in [
        "---\nname: Example\n---\n- parent 😀\n  continued\n  - child café\n  after child\n\n- sibling\n",
        "Prefix\r\n\r\n> - quoted\r\n>   continuation\r\n\r\n- [ ] Hello 👩‍💻\r\n  - [x] Nested\r\n",
        "- first\n\n  ```md\n  - code\n  ```\n\n  tail\n\n- final without newline",
        "- repeated\n- repeated\n- repeated\n",
    ] {
        std::fs::write(dir.path().join("a.md"),text).unwrap();
        store.reconcile_path("a.md").unwrap();
        store.search(&q,None).unwrap();
        let db=Connection::open(dir.path().join(".gilune/local/index.db")).unwrap();
        crate::search_text::register(&db).unwrap();
        db.execute_batch("BEGIN").unwrap();
        seed(&db).unwrap();
        let targets=db.prepare("SELECT id,source_id FROM index_targets WHERE kind=2 ORDER BY local_key")?
            .query_map([],|r|Ok((r.get::<_,i64>(0)?,r.get::<_,i64>(1)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        assert!(!targets.is_empty());
        for (id,source) in targets {
            let expected=target_projection::item(&db,id).unwrap();
            let actual=run(||target_projection::item(&db,id).unwrap());
            assert_eq!(actual,expected,"{text}");
            let mut window=super::super::text_window::TextWindow::default();
            let scanned=run(|| {
                let p=position(&db,source,id).unwrap();
                window.item(&db,source,"a.md",expected.source_version.clone(),p.map,p.key,p.end,p.own).unwrap()
            });
            assert_eq!(scanned.text,expected.text);
            assert_eq!(scanned.first_line,expected.first_line);
        }
        db.execute_batch("ROLLBACK").unwrap();
    }
    Ok(())
}
