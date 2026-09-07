use super::*;
use gilune_document::TextSnapshot;
use rusqlite::types::Value;

fn database() -> Connection {
    let db = Connection::open_in_memory().unwrap();
    crate::search_text::register(&db).unwrap();
    db.execute_batch(SCHEMA).unwrap();
    for namespace in [TEXT_NAMESPACE, "example.one", "example.two"] {
        db.execute(
            "INSERT INTO namespaces(namespace,version,cursor_seq) VALUES(?1,1,0)",
            [namespace],
        )
        .unwrap();
    }
    db
}

fn plans(text: &str, seq: i64) -> Vec<(&'static str, Plan)> {
    let snapshot = TextSnapshot::new(text.into()).unwrap();
    [TEXT_NAMESPACE, "example.one", "example.two"]
        .into_iter()
        .map(|namespace| {
            let rows = if namespace == TEXT_NAMESPACE {
                Rows::Lines(
                    text_rows("a.md", text),
                    Some((
                        snapshot.version().into(),
                        scoped::encode_layout(snapshot.layout()),
                    )),
                )
            } else {
                Rows::Facts(
                    snapshot
                        .layout()
                        .items
                        .iter()
                        .map(|item| FactRow {
                            line: None,
                            item_key: Some(item.key as i64),
                            source_version: Some(snapshot.version().into()),
                            predicate: format!("{namespace}.value"),
                            value: text[item.key as usize..item.range.end as usize].into(),
                            num: None,
                            typed: true,
                        })
                        .collect(),
                    Some(snapshot.version().into()),
                )
            };
            (
                namespace,
                Plan {
                    expected_cursor: seq - 1,
                    expected_version: 1,
                    steps: vec![(
                        seq,
                        Step::Put {
                            rel_path: "a.md".into(),
                            rows,
                        },
                    )],
                    cursor: seq,
                    failure: None,
                    stopped: false,
                },
            )
        })
        .collect()
}

fn contents(db: &Connection) -> Vec<Vec<Value>> {
    db.prepare("SELECT namespace,rel_path,line,item_key,source_version,predicate,value,num,typed,t.kind,t.local_key FROM facts f LEFT JOIN index_targets t ON t.id=f.target_id ORDER BY namespace,f.rel_path,f.line,f.item_key,f.predicate,f.value,f.num,f.typed").unwrap()
        .query_map([], |r| (0..11).map(|i|r.get(i)).collect()).unwrap().collect::<rusqlite::Result<_>>().unwrap()
}

fn count(db: &Connection, sql: &str) -> i64 {
    db.query_row(sql, [], |r| r.get(0)).unwrap()
}

#[test]
fn single_line_edit_writes_only_changed_fact_values_across_independent_owners() {
    let db = database();
    let text = "- entry old\n".repeat(1000);
    publish(&db, &plans(&text, 1)).unwrap().unwrap();
    db.execute_batch("CREATE TABLE writes(kind); CREATE TRIGGER fact_write AFTER UPDATE ON fact_values BEGIN INSERT INTO writes VALUES('fact'); END; CREATE TRIGGER target_write AFTER UPDATE ON target_slots BEGIN INSERT INTO writes VALUES('target'); END; CREATE TRIGGER frame_write AFTER UPDATE ON fact_frames BEGIN INSERT INTO writes VALUES('frame'); END; CREATE TRIGGER slot_write AFTER UPDATE ON fact_location_slots BEGIN INSERT INTO writes VALUES('slot'); END;").unwrap();
    let changed = text.replacen("old", "new", 1);
    publish(&db, &plans(&changed, 2)).unwrap().unwrap();
    assert_eq!(
        count(&db, "SELECT count(*) FROM writes WHERE kind='fact'"),
        2
    );
    assert_eq!(
        count(&db, "SELECT count(*) FROM writes WHERE kind='target'"),
        0
    );
    assert_eq!(
        count(&db, "SELECT count(*) FROM writes WHERE kind='frame'"),
        1
    );
    assert_eq!(
        count(&db, "SELECT count(*) FROM writes WHERE kind='slot'"),
        0
    );
    assert_eq!(count(&db, "SELECT count(*) FROM facts f JOIN source_maps s ON s.rel_path=f.rel_path AND s.source_version=f.source_version WHERE target_id IS NOT NULL"), 2000);
    let reference = database();
    publish(&reference, &plans(&changed, 1)).unwrap();
    assert_eq!(contents(&db), contents(&reference));
}

#[test]
fn structural_edits_and_revision_revisits_match_complete_replacement() {
    let db = database();
    let variants = [
        "- a 😀\r\n  - child\r\n- b\r\n",
        "prefix\r\n- a 😀\r\n  - child\r\n- b longer\r\n",
        "```\n- hidden\n```\n- live\n",
        "ordinary\n",
        "",
        "- a 😀\r\n  - child\r\n- b\r\n",
    ];
    for (i, text) in variants.iter().enumerate() {
        publish(&db, &plans(text, i as i64 + 1)).unwrap().unwrap();
        let reference = database();
        publish(&reference, &plans(text, 1)).unwrap();
        assert_eq!(contents(&db), contents(&reference), "variant {i}");
        assert_eq!(count(&db, "SELECT count(*) FROM fact_locations l WHERE NOT EXISTS(SELECT 1 FROM fact_values f WHERE f.location_id=l.id)"), 0);
        assert_eq!(count(&db, "SELECT count(*) FROM fact_frames r WHERE NOT EXISTS(SELECT 1 FROM fact_location_slots l WHERE l.frame_id=r.id)"), 0);
    }
}

#[test]
fn missing_or_failed_owner_keeps_old_evidence_unbound_and_other_owner_advances() {
    for missing in [false, true] {
        let db = database();
        publish(&db, &plans("- old\n", 1)).unwrap();
        let old_version: String = db
            .query_row("SELECT source_version FROM source_maps", [], |r| r.get(0))
            .unwrap();
        let mut next = plans("- new longer\n", 2);
        if missing {
            next.pop();
        } else {
            next[2].1.steps.clear();
            next[2].1.failure = Some("refused".into());
            next[2].1.stopped = true;
            next[2].1.cursor = 1;
        }
        publish(&db, &next).unwrap().unwrap();
        let stale: (String, Option<i64>) = db
            .query_row(
                "SELECT source_version,target_id FROM facts WHERE namespace='example.two'",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(stale, (old_version, None));
        assert_eq!(
            count(
                &db,
                "SELECT count(target_id) FROM facts WHERE namespace='example.one'"
            ),
            1
        );
        assert_eq!(
            count(
                &db,
                "SELECT cursor_seq FROM namespaces WHERE namespace='example.two'"
            ),
            1
        );
    }
}

#[test]
fn publication_failure_or_obsolete_plan_cannot_advance_any_provenance_or_cursor() {
    let db = database();
    publish(&db, &plans("- old\n", 1)).unwrap();
    let before = contents(&db);
    let mut obsolete = plans("- new\n", 2);
    obsolete[2].1.expected_version = 99;
    assert!(publish(&db, &obsolete).unwrap().is_none());
    assert_eq!(contents(&db), before);
    db.execute_batch("CREATE TRIGGER fail_publication BEFORE UPDATE ON namespaces WHEN NEW.namespace='example.two' BEGIN SELECT RAISE(ABORT,'injected'); END;").unwrap();
    assert!(publish(&db, &plans("- new\n", 2)).is_err());
    assert_eq!(contents(&db), before);
    assert_eq!(count(&db, "SELECT min(cursor_seq) FROM namespaces"), 1);
    assert_eq!(count(&db, "SELECT max(cursor_seq) FROM namespaces"), 1);
}

#[test]
fn inserting_and_removing_an_item_does_not_rewrite_surviving_fact_values() {
    let db = database();
    let original = (0..1000)
        .map(|i| format!("- entry {i}\n"))
        .collect::<String>();
    publish(&db, &plans(&original, 1)).unwrap();
    db.execute_batch("CREATE TABLE touched(id); CREATE TRIGGER touched_fact AFTER UPDATE ON fact_values BEGIN INSERT INTO touched VALUES(NEW.id); END;").unwrap();
    for (seq, text) in [(2, format!("- inserted 😀\n{original}")), (3, original)] {
        db.execute("DELETE FROM touched", []).unwrap();
        publish(&db, &plans(&text, seq)).unwrap();
        assert_eq!(
            count(
                &db,
                "SELECT count(*) FROM touched w JOIN fact_values f ON f.id=w.id"
            ),
            0
        );
        let reference = database();
        publish(&reference, &plans(&text, 1)).unwrap();
        assert_eq!(contents(&db), contents(&reference));
    }
}

#[test]
fn an_existing_empty_revision_frame_uses_conservative_publication() {
    let db = database();
    publish(&db, &plans("- old\n", 1)).unwrap();
    let next = TextSnapshot::new("- next\n".into()).unwrap();
    db.execute(
        "INSERT INTO fact_frames(rel_path,source_version) VALUES('a.md',?1)",
        [next.version()],
    )
    .unwrap();
    publish(&db, &plans(next.text(), 2)).unwrap().unwrap();
    let reference = database();
    publish(&reference, &plans(next.text(), 1)).unwrap();
    assert_eq!(contents(&db), contents(&reference));
    assert_eq!(count(&db, "SELECT count(*) FROM fact_frames"), 1);
}

#[test]
fn revisiting_a_revision_held_by_a_failed_owner_does_not_merge_its_evidence() {
    let db = database();
    publish(&db, &plans("- original\n", 1)).unwrap();
    let mut next = plans("- changed\n", 2);
    next[2].1.steps.clear();
    next[2].1.failure = Some("refused".into());
    next[2].1.stopped = true;
    next[2].1.cursor = 1;
    publish(&db, &next).unwrap().unwrap();
    assert_eq!(count(&db, "SELECT count(*) FROM fact_frames"), 2);
    let mut revisited = plans("- original\n", 3);
    revisited[2].1.expected_cursor = 1;
    publish(&db, &revisited).unwrap().unwrap();
    let reference = database();
    publish(&reference, &plans("- original\n", 1)).unwrap();
    assert_eq!(contents(&db), contents(&reference));
    assert_eq!(count(&db, "SELECT count(*) FROM fact_frames"), 1);
}

#[test]
fn position_blocks_bound_single_edit_writes_and_match_full_rebuild() {
    let db = database();
    let mut text: String = (0..10_000).map(|i| format!("- entry {i} old\n")).collect();
    publish(&db, &plans(&text, 1)).unwrap().unwrap();
    db.execute_batch("CREATE TABLE geometry_writes(kind); CREATE TRIGGER geometry_target AFTER UPDATE ON target_slots BEGIN INSERT INTO geometry_writes VALUES('target'); END; CREATE TRIGGER geometry_block AFTER UPDATE ON position_blocks BEGIN INSERT INTO geometry_writes VALUES('block'); END; CREATE TRIGGER geometry_location AFTER UPDATE ON fact_location_slots BEGIN INSERT INTO geometry_writes VALUES('location'); END; CREATE TRIGGER geometry_text AFTER UPDATE ON text_rows BEGIN INSERT INTO geometry_writes VALUES('text'); END;").unwrap();
    for (i, next) in [
        text.replacen("old", "longer 😀", 1),
        format!("prefix\n{text}"),
        format!("- inserted\n{text}"),
        text.replacen("- entry 0 old\n", "", 1),
    ]
    .into_iter()
    .enumerate()
    {
        db.execute("DELETE FROM geometry_writes", []).unwrap();
        publish(&db, &plans(&next, i as i64 + 2)).unwrap().unwrap();
        let full = database();
        publish(&full, &plans(&next, 1)).unwrap().unwrap();
        assert_eq!(contents(&db), contents(&full));
        for sql in ["SELECT kind,local_key,map_version,start_byte,end_byte,own_ranges FROM index_targets ORDER BY kind,local_key", "SELECT line,field,text,folded FROM lines ORDER BY field,line,text"] {
            let values=|db:&Connection| {let mut stmt=db.prepare(sql).unwrap();let n=stmt.column_count();stmt.query_map([],|r|(0..n).map(|i|r.get::<_,Value>(i)).collect::<rusqlite::Result<Vec<_>>>()).unwrap().collect::<rusqlite::Result<Vec<_>>>().unwrap()};
            assert_eq!(values(&db),values(&full));
        }
        assert_eq!(count(&db, "SELECT count(*) FROM prepared_geometry"), 0);
        assert_eq!(
            count(&db, "SELECT count(*) FROM target_slots WHERE source_id<0"),
            0
        );
        assert!(
            count(
                &db,
                "SELECT count(*) FROM geometry_writes WHERE kind='target'"
            ) < 600
        );
        assert!(
            count(
                &db,
                "SELECT count(*) FROM geometry_writes WHERE kind='location'"
            ) < 10
        );
        assert!(
            count(
                &db,
                "SELECT count(*) FROM geometry_writes WHERE kind='text'"
            ) < 10
        );
        println!(
            "POSITION_EDIT {i}: targets={} blocks={} locations={} text={}",
            count(
                &db,
                "SELECT count(*) FROM geometry_writes WHERE kind='target'"
            ),
            count(
                &db,
                "SELECT count(*) FROM geometry_writes WHERE kind='block'"
            ),
            count(
                &db,
                "SELECT count(*) FROM geometry_writes WHERE kind='location'"
            ),
            count(
                &db,
                "SELECT count(*) FROM geometry_writes WHERE kind='text'"
            )
        );
        text = next;
    }
    let _ = text;
}

#[test]
fn unavailable_owners_keep_original_coordinates_after_equal_layout_then_block_movement() {
    let db = database();
    publish(&db, &plans("- one\n- two\n", 1)).unwrap().unwrap();
    let mut second = plans("- new\n- two\n", 2);
    second.retain(|(ns, _)| *ns != "example.one");
    publish(&db, &second).unwrap().unwrap();
    let evidence = |db: &Connection| {
        db.prepare("SELECT item_key,source_version,value,target_id FROM facts WHERE namespace='example.one' ORDER BY rowid").unwrap()
            .query_map([],|r|Ok((r.get::<_,i64>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?,r.get::<_,Option<i64>>(3)?))).unwrap().collect::<rusqlite::Result<Vec<_>>>().unwrap()
    };
    let old = evidence(&db);
    assert!(old.iter().all(|r| r.3.is_none()));
    let mut third = plans("prefix\n- new\n- two\n", 3);
    third.retain(|(ns, _)| *ns != "example.one");
    publish(&db, &third).unwrap().unwrap();
    assert_eq!(evidence(&db), old);
}

#[test]
fn repeated_reordering_and_deletion_do_not_fragment_blocks_into_per_target_coordinates() {
    let db = database();
    let mut lines: Vec<_> = (0..512).map(|i| format!("- entry {i}\n")).collect();
    publish(&db, &plans(&lines.concat(), 1)).unwrap().unwrap();
    for i in 0..80 {
        let moved = lines.remove((i * 37) % lines.len());
        lines.insert(0, moved);
        if i % 3 == 0 {
            lines.remove(lines.len() / 2);
        }
        let text = lines.concat();
        publish(&db, &plans(&text, i as i64 + 2)).unwrap().unwrap();
        assert!(count(&db, "SELECT count(*) FROM position_blocks") < 40);
        let maximum = count(
            &db,
            "SELECT MAX(n) FROM (SELECT count(*) AS n FROM target_slots GROUP BY block_id)",
        );
        assert!(maximum <= 128);
        if i % 20 == 0 || i == 79 {
            let full = database();
            publish(&full, &plans(&text, 1)).unwrap().unwrap();
            assert_eq!(contents(&db), contents(&full));
            let ordered = |db: &Connection| {
                db.prepare("SELECT t.local_key FROM target_slots x JOIN index_targets t ON t.id=x.id WHERE x.kind=2 ORDER BY x.local_key").unwrap().query_map([],|r|r.get::<_,i64>(0)).unwrap().collect::<rusqlite::Result<Vec<_>>>().unwrap()
            };
            assert_eq!(ordered(&db), ordered(&full));
        }
    }
}
