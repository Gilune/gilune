//! Test-only architectural experiment. No production registration or schema changes.
//! The engine has no domain names; fixtures own declarations and source adapters.
use rusqlite::{params_from_iter, types::Value, Connection};

mod benchmark;
mod tests;

#[derive(Clone, Copy, Debug, PartialEq)]
enum Kind {
    Text,
    Date,
    Number,
}
#[derive(Clone)]
struct Field {
    name: &'static str,
    kind: Kind,
    nullable: bool,
}
#[derive(Clone)]
struct Schema {
    owner: &'static str,
    name: &'static str,
    fields: Vec<Field>,
    // One record per target, or a set keyed by all (non-null) fields.
    many: bool,
    indexes: Vec<Vec<usize>>,
}
#[derive(Default)]
struct Registry {
    schemas: Vec<Schema>,
}
impl Registry {
    fn register(&mut self, schema: Schema) -> Result<usize, String> {
        if schema.owner.is_empty()
            || schema.name.is_empty()
            || schema.fields.is_empty()
            || schema.fields.len() > 8
        {
            return Err("invalid declaration".into());
        }
        if self
            .schemas
            .iter()
            .any(|s| s.owner == schema.owner && s.name == schema.name)
        {
            return Err("duplicate owned record".into());
        }
        let mut names = std::collections::BTreeSet::new();
        if schema
            .fields
            .iter()
            .any(|f| f.name.is_empty() || !names.insert(f.name) || (schema.many && f.nullable))
        {
            return Err("invalid field declaration".into());
        }
        if schema.indexes.len() > 4
            || schema
                .indexes
                .iter()
                .any(|i| i.is_empty() || i.iter().any(|&f| f >= schema.fields.len()))
        {
            return Err("invalid bounded index declaration".into());
        }
        let id = self.schemas.len();
        self.schemas.push(schema);
        Ok(id)
    }
    fn install(&self, db: &Connection) {
        db.execute_batch("PRAGMA foreign_keys=ON;
            CREATE TABLE sources(id INTEGER PRIMARY KEY,path TEXT UNIQUE NOT NULL,version TEXT NOT NULL);
            CREATE TABLE targets(id INTEGER PRIMARY KEY,source INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,kind INTEGER NOT NULL,anchor INTEGER NOT NULL,UNIQUE(source,kind,anchor));
            CREATE TABLE state(generation INTEGER NOT NULL,ready INTEGER NOT NULL);
            INSERT INTO state VALUES(1,1);").unwrap();
        for (id, schema) in self.schemas.iter().enumerate() {
            let columns = schema
                .fields
                .iter()
                .enumerate()
                .map(|(i, f)| {
                    format!(
                        "f{i} {} {}",
                        if f.kind == Kind::Number {
                            "REAL"
                        } else {
                            "TEXT"
                        },
                        if f.nullable { "" } else { "NOT NULL" }
                    )
                })
                .collect::<Vec<_>>();
            let key = if schema.many {
                format!(
                    "target,{}",
                    (0..schema.fields.len())
                        .map(|i| format!("f{i}"))
                        .collect::<Vec<_>>()
                        .join(",")
                )
            } else {
                "target".into()
            };
            db.execute_batch(&format!("CREATE TABLE r{id}(target INTEGER NOT NULL REFERENCES targets(id) ON DELETE CASCADE,{},PRIMARY KEY({key})) WITHOUT ROWID;",columns.join(","))).unwrap();
            for (n, fields) in schema.indexes.iter().enumerate() {
                let cols = fields
                    .iter()
                    .map(|i| format!("f{i}"))
                    .collect::<Vec<_>>()
                    .join(",");
                db.execute_batch(&format!("CREATE INDEX r{id}_i{n} ON r{id}({cols},target);"))
                    .unwrap();
            }
        }
    }
    fn insert(
        &self,
        db: &Connection,
        owner: &str,
        relation: usize,
        target: i64,
        values: &[Value],
    ) -> Result<(), String> {
        let schema = self.schemas.get(relation).ok_or("unknown record")?;
        if schema.owner != owner {
            return Err("wrong producer".into());
        }
        if values.len() != schema.fields.len() {
            return Err("wrong field count".into());
        }
        for (f, v) in schema.fields.iter().zip(values) {
            let valid = match v {
                Value::Null => f.nullable,
                Value::Real(n) => f.kind == Kind::Number && n.is_finite(),
                Value::Integer(_) => f.kind == Kind::Number,
                Value::Text(s) => f.kind == Kind::Text || (f.kind == Kind::Date && valid_date(s)),
                _ => false,
            };
            if !valid {
                return Err(format!("invalid {}", f.name));
            }
        }
        let placeholders = vec!["?"; values.len() + 1].join(",");
        let mut params = vec![Value::Integer(target)];
        params.extend_from_slice(values);
        db.prepare_cached(&format!("INSERT INTO r{relation} VALUES({placeholders})"))
            .map_err(|e| e.to_string())?
            .execute(params_from_iter(params))
            .map_err(|e| e.to_string())?;
        Ok(())
    }
}
fn valid_date(s: &str) -> bool {
    let b = s.as_bytes();
    if b.len() != 10
        || b[4] != b'-'
        || b[7] != b'-'
        || b.iter()
            .enumerate()
            .any(|(i, c)| i != 4 && i != 7 && !c.is_ascii_digit())
    {
        return false;
    }
    let parse =
        |range: std::ops::Range<usize>| std::str::from_utf8(&b[range]).ok()?.parse::<u32>().ok();
    let (Some(y), Some(m), Some(d)) = (parse(0..4), parse(5..7), parse(8..10)) else {
        return false;
    };
    let leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
    let max = match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 => {
            if leap {
                29
            } else {
                28
            }
        }
        _ => 0,
    };
    y > 0 && d > 0 && d <= max
}

#[derive(Clone, Debug)]
enum Op {
    Eq,
    Ge,
    Lt,
    In,
    Missing,
}
#[derive(Clone, Debug)]
struct Condition {
    field: usize,
    op: Op,
    values: Vec<Value>,
}
#[derive(Clone, Debug)]
struct Related {
    relation: usize,
    conditions: Vec<Condition>,
    negated: bool,
}
#[derive(Clone, Debug)]
struct Query {
    relation: usize,
    conditions: Vec<Condition>,
    related: Vec<Related>,
    order: usize,
}
#[derive(Clone, Debug)]
struct Cursor {
    generation: i64,
    fingerprint: String,
    value: Value,
    target: i64,
}
#[derive(Clone, Debug, PartialEq)]
struct Row {
    target: i64,
    value: Value,
}
struct Page {
    rows: Vec<Row>,
    next: Option<Cursor>,
}

fn conditions(alias: &str, fields: &[Condition], params: &mut Vec<Value>) -> Vec<String> {
    fields
        .iter()
        .map(|c| {
            let field = format!("{alias}.f{}", c.field);
            match c.op {
                Op::Missing => format!("{field} IS NULL"),
                Op::In => {
                    params.extend(c.values.clone());
                    if c.values.len() == 1 {
                        format!("{field} = ?")
                    } else {
                        format!("{field} IN ({})", vec!["?"; c.values.len()].join(","))
                    }
                }
                _ => {
                    params.push(c.values[0].clone());
                    format!(
                        "{field} {} ?",
                        match c.op {
                            Op::Eq => "=",
                            Op::Ge => ">=",
                            Op::Lt => "<",
                            _ => unreachable!(),
                        }
                    )
                }
            }
        })
        .collect()
}

impl Query {
    fn validate(&self, registry: &Registry) -> Result<(), String> {
        if self.related.len() > 8
            || self.conditions.len()
                + self
                    .related
                    .iter()
                    .map(|r| r.conditions.len())
                    .sum::<usize>()
                > 64
        {
            return Err("query exceeds prototype bounds".into());
        }
        let check = |relation: usize, clauses: &[Condition]| -> Result<(), String> {
            let schema = registry.schemas.get(relation).ok_or("unknown relation")?;
            for c in clauses {
                let f = schema.fields.get(c.field).ok_or("unknown field")?;
                match c.op {
                    Op::Missing if !c.values.is_empty() => {
                        return Err("missing takes no value".into())
                    }
                    Op::In if c.values.len() > 256 => return Err("set too large".into()),
                    Op::Eq | Op::Ge | Op::Lt if c.values.len() != 1 => {
                        return Err("expected one value".into())
                    }
                    _ => (),
                }
                for v in &c.values {
                    let valid = match v {
                        Value::Text(s) => {
                            f.kind == Kind::Text || (f.kind == Kind::Date && valid_date(s))
                        }
                        Value::Integer(_) => f.kind == Kind::Number,
                        Value::Real(n) => f.kind == Kind::Number && n.is_finite(),
                        _ => false,
                    };
                    if !valid {
                        return Err("query value type mismatch".into());
                    }
                }
            }
            Ok(())
        };
        check(self.relation, &self.conditions)?;
        let schema = &registry.schemas[self.relation];
        if schema.many || self.order >= schema.fields.len() {
            return Err("order requires a single record and known field".into());
        }
        if schema.fields[self.order].nullable
            && !self
                .conditions
                .iter()
                .any(|c| c.field == self.order && !matches!(c.op, Op::Missing))
        {
            return Err(
                "nullable ordering requires a present-value constraint in this prototype".into(),
            );
        }
        for r in &self.related {
            check(r.relation, &r.conditions)?;
        }
        Ok(())
    }
    fn fingerprint(&self) -> String {
        hex::encode(sha2::Sha256::digest(format!("{self:?}")))
    }
    fn sql(&self, after: Option<&Cursor>, count: bool) -> (String, Vec<Value>) {
        let mut params = vec![];
        let mut parts = conditions("b", &self.conditions, &mut params);
        for (n, r) in self.related.iter().enumerate() {
            let alias = format!("j{n}");
            let mut sub = vec![format!("{alias}.target=b.target")];
            sub.extend(conditions(&alias, &r.conditions, &mut params));
            parts.push(format!(
                "{}EXISTS(SELECT 1 FROM r{} {alias} INDEXED BY sqlite_autoindex_r{}_1 WHERE {})",
                if r.negated { "NOT " } else { "" },
                r.relation,
                r.relation,
                sub.join(" AND ")
            ));
        }
        // Prototype has non-null ascending ordered values. Nullable ordering is
        // rejected at page time rather than silently losing absent-date records.
        parts.push(format!("b.f{} IS NOT NULL", self.order));
        if let Some(c) = after {
            parts.push(format!("(b.f{},b.target)>(?,?)", self.order));
            params.extend([c.value.clone(), Value::Integer(c.target)]);
        }
        let select = if count {
            "COUNT(*)".into()
        } else {
            format!("b.target,b.f{}", self.order)
        };
        let tail = if count {
            String::new()
        } else {
            format!(" ORDER BY b.f{},b.target LIMIT ?", self.order)
        };
        (
            format!(
                "SELECT {select} FROM r{} b WHERE {}{tail}",
                self.relation,
                parts.join(" AND ")
            ),
            params,
        )
    }
    fn page(
        &self,
        db: &Connection,
        registry: &Registry,
        after: Option<&Cursor>,
        size: usize,
    ) -> Result<Page, String> {
        self.validate(registry)?;
        if size == 0 || size > 500 {
            return Err("invalid page size".into());
        }
        let snapshot = db.unchecked_transaction().map_err(|e| e.to_string())?;
        let db = &snapshot;
        // Readiness, generation, candidate probes and page selection share one
        // short read snapshot. Producer receipts are still a prototype stand-in.
        let (generation, ready): (i64, bool) = db
            .query_row("SELECT generation,ready FROM state", [], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .map_err(|e| e.to_string())?;
        if !ready {
            return Err("index pending".into());
        }
        let fingerprint = self.fingerprint();
        if after.is_some_and(|a| a.generation != generation || a.fingerprint != fingerprint) {
            return Err("expired cursor".into());
        }
        // Only offer this path where the query itself guarantees a present
        // ordered field. Absence needs a second explicit ordering tier.
        if registry.schemas[self.relation].fields[self.order].nullable
            && !self
                .conditions
                .iter()
                .any(|c| c.field == self.order && !matches!(c.op, Op::Missing))
        {
            return Err(
                "nullable ordering requires a present-value constraint in this prototype".into(),
            );
        }
        let (sql, mut params) = self.sql(after, false);
        let sql = restrict_targets(sql, &mut params, "b", self.candidates(db)?);
        params.push(Value::Integer(size as i64 + 1));
        let mut rows = db
            .prepare_cached(&sql)
            .map_err(|e| e.to_string())?
            .query_map(params_from_iter(params), |r| {
                Ok(Row {
                    target: r.get(0)?,
                    value: r.get(1)?,
                })
            })
            .map_err(|e| e.to_string())?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|e| e.to_string())?;
        let more = rows.len() > size;
        rows.truncate(size);
        let next = if more {
            rows.last().map(|r| Cursor {
                generation,
                fingerprint,
                value: r.value.clone(),
                target: r.target,
            })
        } else {
            None
        };
        Ok(Page { rows, next })
    }
    fn candidates(&self, db: &Connection) -> Result<Option<Vec<i64>>, String> {
        for r in self.related.iter().filter(|r| !r.negated) {
            let mut params = vec![];
            let parts = conditions("p", &r.conditions, &mut params);
            let sql = format!(
                "SELECT DISTINCT target FROM r{} p WHERE {} LIMIT 65",
                r.relation,
                if parts.is_empty() {
                    "1".into()
                } else {
                    parts.join(" AND ")
                }
            );
            let ids = db
                .prepare_cached(&sql)
                .map_err(|e| e.to_string())?
                .query_map(params_from_iter(params), |r| r.get(0))
                .map_err(|e| e.to_string())?
                .collect::<rusqlite::Result<Vec<i64>>>()
                .map_err(|e| e.to_string())?;
            if ids.len() <= 64 {
                return Ok(Some(ids));
            }
        }
        Ok(None)
    }
    fn count(&self, db: &Connection, registry: &Registry) -> Result<(i64, i64), String> {
        self.validate(registry)?;
        let snapshot = db.unchecked_transaction().map_err(|e| e.to_string())?;
        let db = &snapshot;
        let (generation, ready): (i64, bool) = db
            .query_row("SELECT generation,ready FROM state", [], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .map_err(|e| e.to_string())?;
        if !ready {
            return Err("index pending".into());
        }
        let (sql, mut params) = self.sql(None, true);
        let sql = restrict_targets(sql, &mut params, "b", self.candidates(db)?);
        db.query_row(&sql, params_from_iter(params), |r| r.get(0))
            .map(|n| (generation, n))
            .map_err(|e| e.to_string())
    }
}

fn restrict_targets(
    mut sql: String,
    params: &mut Vec<Value>,
    alias: &str,
    ids: Option<Vec<i64>>,
) -> String {
    if let Some(ids) = ids {
        let clause = format!(
            " AND {alias}.target IN ({})",
            vec!["?"; ids.len()].join(",")
        );
        let at = sql.find(" ORDER BY ").unwrap_or(sql.len());
        sql.insert_str(at, &clause);
        params.extend(ids.into_iter().map(Value::Integer));
    }
    sql
}

use sha2::Digest;
