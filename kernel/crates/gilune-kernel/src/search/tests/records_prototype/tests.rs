use super::*;
mod robustness;

fn unrelated() -> (Registry, usize, Connection) {
    let mut registry = Registry::default();
    let id = registry
        .register(Schema {
            owner: "fixture.readings",
            name: "measurement",
            fields: vec![
                Field {
                    name: "sensor",
                    kind: Kind::Text,
                    nullable: false,
                },
                Field {
                    name: "reading",
                    kind: Kind::Number,
                    nullable: false,
                },
            ],
            many: false,
            indexes: vec![vec![0, 1]],
        })
        .unwrap();
    let db = Connection::open_in_memory().unwrap();
    registry.install(&db);
    db.execute_batch(
        "INSERT INTO sources VALUES(1,'readings.md','revision-one');
        INSERT INTO targets VALUES(1,1,1,10),(2,1,1,20),(3,1,1,30);",
    )
    .unwrap();
    (registry, id, db)
}
fn text(s: &str) -> Value {
    Value::Text(s.into())
}
#[test]
fn unrelated_schema_types_ownership_and_keyset_contract() {
    let (registry, id, db) = unrelated();
    for (target, number) in [(1, 4), (2, 4), (3, 9)] {
        registry
            .insert(
                &db,
                "fixture.readings",
                id,
                target,
                &[text("outside"), Value::Integer(number)],
            )
            .unwrap();
    }
    assert!(registry
        .insert(&db, "other", id, 1, &[text("outside"), Value::Integer(4)])
        .is_err());
    assert!(registry
        .insert(
            &db,
            "fixture.readings",
            id,
            4,
            &[text("outside"), Value::Integer(4)]
        )
        .is_err());
    assert!(registry
        .insert(
            &db,
            "fixture.readings",
            id,
            1,
            &[text("outside"), text("4")]
        )
        .is_err());
    assert!(registry
        .insert(
            &db,
            "fixture.readings",
            id,
            1,
            &[text("outside"), Value::Integer(4)]
        )
        .is_err());
    let q = Query {
        relation: id,
        conditions: vec![Condition {
            field: 0,
            op: Op::Eq,
            values: vec![text("outside")],
        }],
        related: vec![],
        order: 1,
    };
    let first = q.page(&db, &registry, None, 1).unwrap();
    assert!(db.is_autocommit());
    assert_eq!(q.count(&db, &registry).unwrap(), (1, 3));
    assert_eq!(first.rows[0].target, 1);
    let second = q.page(&db, &registry, first.next.as_ref(), 1).unwrap();
    assert_eq!(second.rows[0].target, 2);
    let third = q.page(&db, &registry, second.next.as_ref(), 1).unwrap();
    assert_eq!(third.rows[0].target, 3);
    assert!(third.next.is_none());
    let mut changed = q.clone();
    changed.conditions[0].values = vec![text("inside")];
    assert!(changed
        .page(&db, &registry, first.next.as_ref(), 1)
        .is_err());
    db.execute_batch("UPDATE state SET ready=0").unwrap();
    assert!(q.page(&db, &registry, None, 1).is_err());
    assert!(q.count(&db, &registry).is_err());
    assert!(db.is_autocommit());
    db.execute_batch("UPDATE state SET ready=1,generation=generation+1")
        .unwrap();
    assert!(q.page(&db, &registry, first.next.as_ref(), 1).is_err());
    db.execute_batch(
        "BEGIN; DELETE FROM sources; UPDATE state SET generation=generation+1; ROLLBACK;",
    )
    .unwrap();
    assert_eq!(q.page(&db, &registry, None, 3).unwrap().rows.len(), 3);
    db.execute_batch("DELETE FROM sources").unwrap();
    assert!(q.page(&db, &registry, None, 3).unwrap().rows.is_empty());
}

#[test]
fn typed_date_validation_and_declaration_collisions() {
    assert!(valid_date("2024-02-29"));
    assert!(!valid_date("2026-02-29"));
    assert!(!valid_date("2026-13-01"));
    assert!(!valid_date("2026-09-00"));
    let (mut registry, _, _) = unrelated();
    assert!(registry.register(registry.schemas[0].clone()).is_err());
    let mut schema = registry.schemas[0].clone();
    schema.owner = "another";
    assert!(registry.register(schema.clone()).is_ok());
    schema.owner = "third";
    schema.indexes = vec![vec![7]];
    assert!(registry.register(schema).is_err());
}

#[test]
fn correlated_record_membership_negation_and_missing_are_explicit() {
    let (mut registry, base, _) = unrelated();
    let links = registry
        .register(Schema {
            owner: "fixture.labels",
            name: "labels",
            fields: vec![
                Field {
                    name: "key",
                    kind: Kind::Text,
                    nullable: false,
                },
                Field {
                    name: "value",
                    kind: Kind::Text,
                    nullable: false,
                },
            ],
            many: true,
            indexes: vec![vec![0, 1]],
        })
        .unwrap();
    let db = Connection::open_in_memory().unwrap();
    registry.install(&db);
    db.execute_batch("INSERT INTO sources VALUES(1,'a.md','v1'); INSERT INTO targets VALUES(1,1,1,10),(2,1,1,20),(3,1,0,0);").unwrap();
    for target in [1, 2] {
        registry
            .insert(
                &db,
                "fixture.readings",
                base,
                target,
                &[text("room"), Value::Integer(target)],
            )
            .unwrap();
    }
    for (target, key, value) in [
        (1, "colour", "red"),
        (1, "texture", "blue"),
        (3, "colour", "blue"),
    ] {
        registry
            .insert(
                &db,
                "fixture.labels",
                links,
                target,
                &[text(key), text(value)],
            )
            .unwrap();
    }
    let mut q = Query {
        relation: base,
        conditions: vec![],
        related: vec![Related {
            relation: links,
            negated: false,
            conditions: vec![
                Condition {
                    field: 0,
                    op: Op::Eq,
                    values: vec![text("colour")],
                },
                Condition {
                    field: 1,
                    op: Op::In,
                    values: vec![text("blue")],
                },
            ],
        }],
        order: 1,
    };
    assert!(q.page(&db, &registry, None, 10).unwrap().rows.is_empty());
    q.related[0].conditions[1].values = vec![text("red"), text("blue")];
    assert_eq!(
        q.page(&db, &registry, None, 10)
            .unwrap()
            .rows
            .iter()
            .map(|r| r.target)
            .collect::<Vec<_>>(),
        vec![1]
    );
    q.related[0].negated = true;
    assert_eq!(
        q.page(&db, &registry, None, 10)
            .unwrap()
            .rows
            .iter()
            .map(|r| r.target)
            .collect::<Vec<_>>(),
        vec![2]
    );
    q.related[0].conditions[1].values.clear();
    assert_eq!(q.page(&db, &registry, None, 10).unwrap().rows.len(), 2);
    q.related.clear();
    q.conditions = vec![Condition {
        field: 0,
        op: Op::Missing,
        values: vec![],
    }];
    assert!(q.page(&db, &registry, None, 10).unwrap().rows.is_empty());
    q.conditions = vec![Condition {
        field: 1,
        op: Op::Ge,
        values: vec![text("bad")],
    }];
    assert!(q.page(&db, &registry, None, 10).is_err());
}
