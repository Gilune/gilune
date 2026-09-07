//! Fact-only execution over private source-qualified targets. No domain dispatch.
//! Text/relevance and residual predicates remain on the complete legacy route.
use super::*;
use crate::index::{ConstraintOp, FactLocality};

#[cfg(test)]
thread_local! { pub(super) static TIMINGS: std::cell::Cell<[f64;4]> = const { std::cell::Cell::new([0.0;4]) }; }

pub(super) fn supports(q: &SearchRequest) -> bool {
    q.body_needles.is_empty()
        && q.name_needles.is_empty()
        && q.clauses.iter().all(|c| {
            c.residual.is_none()
                && c.constraints
                    .iter()
                    .all(|f| !matches!(f, Constraint::Text { .. }))
        })
}

pub(super) struct Filter {
    pub condition: String,
    pub params: Vec<Value>,
}

// Replace only the placeholders in kernel-authored value expressions. User
// values are always parameters, never SQL text.
pub(super) fn bind(sql: &str, values: Vec<Value>, params: &mut Vec<Value>) -> String {
    let mut values = values.into_iter();
    let mut result = String::new();
    for ch in sql.chars() {
        if ch == '?' {
            params.push(values.next().expect("value expression arity"));
            result.push_str(&format!("?{}", params.len()));
        } else {
            result.push(ch);
        }
    }
    assert!(values.next().is_none());
    result
}

fn predicate_ids(db: &Connection, name: &str) -> Result<String> {
    let ids = db
        .prepare_cached("SELECT id FROM index_predicates WHERE predicate=?1 ORDER BY id")?
        .query_map([name], |r| r.get::<_, i64>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    // Only database-owned integer IDs enter this SQL list. A missing dictionary
    // entry is an empty set, including for negative/unknown-predicate queries.
    Ok(if ids.is_empty() {
        "NULL".into()
    } else {
        ids.iter().map(i64::to_string).collect::<Vec<_>>().join(",")
    })
}

pub(super) fn compile(db: &Connection, q: &SearchRequest, required: &[String]) -> Result<Filter> {
    let mut params = Vec::new();
    let mut conditions = vec!["t.kind=2".to_string()];
    let mut dictionary = BTreeMap::new();
    let mut sparse = None;
    for clause in &q.clauses {
        let mut terms = Vec::new();
        if clause.never {
            terms.push("0".into());
        } else {
            for c in &clause.constraints {
                let (name, locality, negative, value_sql, values) = match c {
                    Constraint::Fact {
                        predicate,
                        op,
                        value,
                    }
                    | Constraint::FactAt {
                        predicate,
                        op,
                        value,
                        ..
                    } => {
                        let (sql, values) = index::fact_value_condition(*op, value);
                        let locality = match c {
                            Constraint::FactAt { locality, .. } => *locality,
                            _ => FactLocality::Inherited,
                        };
                        (
                            predicate,
                            locality,
                            matches!(op, ConstraintOp::Ne | ConstraintOp::NotExists),
                            sql,
                            values,
                        )
                    }
                    Constraint::FactIn {
                        predicate,
                        values,
                        locality,
                    } => (
                        predicate,
                        *locality,
                        false,
                        " AND value IN (SELECT value FROM json_each(?))".into(),
                        vec![Value::Text(serde_json::json!(values).to_string())],
                    ),
                    Constraint::Text { .. } => {
                        return Err(invalid("text requires the text executor"))
                    }
                };
                if !dictionary.contains_key(name) {
                    dictionary.insert(name.clone(), predicate_ids(db, name)?);
                }
                // An exact positive with at most 64 current targets is a complete
                // candidate bound. Probe only equality/membership/existence paths
                // covered by the value index; avoid global contains/numeric scans.
                if sparse.is_none()
                    && !clause.negated
                    && !negative
                    && locality == FactLocality::Exact
                    && matches!(
                        c,
                        Constraint::FactIn { .. }
                            | Constraint::FactAt {
                                op: ConstraintOp::Eq | ConstraintOp::Exists,
                                ..
                            }
                    )
                {
                    let sql=format!("SELECT DISTINCT target_id FROM fact_values INDEXED BY facts_compact_predicate_value WHERE target_id IS NOT NULL AND predicate_id IN ({}){value_sql} LIMIT 65",dictionary[name]);
                    let ids = db
                        .prepare(&sql)?
                        .query_map(params_from_iter(values.clone()), |r| r.get::<_, i64>(0))?
                        .collect::<rusqlite::Result<Vec<_>>>()?;
                    if ids.len() <= 64 {
                        sparse = Some(ids);
                    }
                }
                let row = format!(
                    "predicate_id IN ({}){}",
                    dictionary[name],
                    bind(&value_sql, values, &mut params)
                );
                let exact =
                    format!("EXISTS (SELECT 1 FROM fact_values WHERE target_id=t.id AND {row})");
                let root = format!("EXISTS (SELECT 1 FROM target_slots r JOIN fact_values ON fact_values.target_id=r.id WHERE r.source_id=t.source_id AND r.kind=0 AND r.local_key=0 AND {row})");
                let term = match locality {
                    FactLocality::Exact => exact,
                    FactLocality::DocumentRoot => root,
                    FactLocality::Inherited => format!("({exact} OR {root})"),
                };
                terms.push(format!("{}({term})", if negative { "NOT " } else { "" }));
            }
        }
        let term = if terms.is_empty() {
            "1".into()
        } else {
            terms.join(" AND ")
        };
        conditions.push(format!(
            "{}({term})",
            if clause.negated { "NOT " } else { "" }
        ));
    }
    for ns in required.iter().filter(|n| *n != index::TEXT_NAMESPACE) {
        conditions.push(bind("EXISTS (SELECT 1 FROM fact_sources r WHERE r.namespace=? AND r.rel_path=s.rel_path AND r.source_version=s.source_version)", vec![Value::Text(ns.clone())], &mut params));
    }
    if let Some(ids) = sparse {
        conditions.push(if ids.is_empty() {
            "0".into()
        } else {
            format!(
                "t.id IN ({})",
                ids.iter().map(i64::to_string).collect::<Vec<_>>().join(",")
            )
        });
    }
    Ok(Filter {
        condition: conditions.join(" AND "),
        params,
    })
}

pub(super) const FROM: &str = "source_maps s CROSS JOIN target_slots t ON t.source_id=s.id";

#[allow(clippy::too_many_arguments)]
pub(super) fn execute(
    db: &Connection,
    q: &SearchRequest,
    required: &[String],
    generation: String,
    fingerprint: String,
    after: Option<Rank>,
    issues: Vec<String>,
    execution: Execution,
) -> Result<SearchPage> {
    #[cfg(test)]
    let started = std::time::Instant::now();
    let filter = compile(db, q, required)?;
    #[cfg(test)]
    let compiled = started.elapsed().as_secs_f64() * 1000.0;
    // The existing public API requires an exact total in the same read snapshot.
    // Count without sorting or projecting. Independent/asynchronous counts are a
    // subsequent API change; this route does not silently weaken total_hits.
    let count = if q.group_by_note {
        "COUNT(DISTINCT s.id)"
    } else {
        "COUNT(*)"
    };
    let total = if execution == Execution::Page {
        0
    } else {
        db.query_row(
            &format!("SELECT {count} FROM {FROM} WHERE {}", filter.condition),
            params_from_iter(&filter.params),
            |r| r.get(0),
        )?
    };
    if execution == Execution::Count {
        return Ok(SearchPage {
            hits: vec![],
            total_hits: total,
            next_cursor: None,
            index_complete: issues.is_empty(),
            issues,
            generation,
        });
    }
    let sorted = !q.sort_predicate.is_empty();
    #[cfg(test)]
    let counted = started.elapsed().as_secs_f64() * 1000.0;
    let sort = if sorted {
        format!(
            "(SELECT MIN(value) FROM fact_values WHERE target_id=t.id AND predicate_id IN ({}))",
            predicate_ids(db, &q.sort_predicate)?
        )
    } else {
        "NULL".into()
    };
    // MIN is over all values of the sort predicate. A matching filter witness
    // need not be the smallest value, and plugins make no cardinality promise.
    let matched = format!(
        "SELECT t.id,t.source_id,s.rel_path,t.local_key,{sort} AS sort_value FROM {FROM} WHERE {}",
        filter.condition
    );
    let selected = if q.group_by_note {
        "SELECT source_id AS id,source_id,rel_path,0 AS local_key,MIN(sort_value) AS sort_value,COUNT(*) AS item_count FROM matched GROUP BY source_id".to_string()
    } else {
        "SELECT *,1 AS item_count FROM matched".into()
    };
    let priority = if sorted { "(sort_value IS NULL)" } else { "1" };
    let mut params = filter.params.clone();
    let continuation = if let Some(after) = &after {
        bind(
            "(priority,value,rel_path,local_key) > (?,?,?,?)",
            vec![
                Value::Integer(after.priority.into()),
                Value::Text(after.value.clone()),
                Value::Text(after.path.clone()),
                Value::Integer(after.line as i64),
            ],
            &mut params,
        )
    } else {
        "1".into()
    };
    let limit = bind(
        "?",
        vec![Value::Integer(i64::from(q.page_size) + 1)],
        &mut params,
    );
    // Constant relevance columns must not obscure the useful source/key order.
    let order = if sorted {
        "priority,value,rel_path,local_key"
    } else {
        "rel_path,local_key"
    };
    let sql = format!("WITH matched AS ({matched}), selected AS ({selected}), ranked AS (SELECT *,{priority} AS priority,COALESCE(sort_value,'') AS value FROM selected) SELECT id,source_id,rel_path,local_key,value,priority,item_count FROM ranked WHERE {continuation} ORDER BY {order} LIMIT {limit}");
    let mut rows = if sorted && !q.group_by_note {
        ordered_page(db, q, &filter, after.as_ref())?
    } else {
        select(db, &sql, params)?
    };
    #[cfg(test)]
    let selected = started.elapsed().as_secs_f64() * 1000.0;
    let more = rows.len() > q.page_size as usize;
    rows.truncate(q.page_size as usize);
    let next_cursor = if more {
        rows.last()
            .map(|r| encode_cursor(&r.rank(), &generation, &fingerprint))
    } else {
        None
    };
    let mut hits = Vec::with_capacity(rows.len());
    let mut roots = BTreeMap::new();
    for row in rows {
        if let std::collections::btree_map::Entry::Vacant(entry) = roots.entry(row.source) {
            entry.insert(target_projection::hit(db, row.source, row.path.clone())?);
        }
        let mut hit = roots[&row.source].clone();
        let ids = if q.max_lines == 0 {
            vec![]
        } else if q.group_by_note {
            let mut params = filter.params.clone();
            let source = bind("?", vec![Value::Integer(row.source)], &mut params);
            let limit = bind(
                "?",
                vec![Value::Integer(i64::from(q.max_lines))],
                &mut params,
            );
            db.prepare(&format!("SELECT t.id FROM {FROM} WHERE {} AND s.id={source} ORDER BY t.local_key LIMIT {limit}", filter.condition))?
                .query_map(params_from_iter(params), |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?
        } else {
            vec![row.id]
        };
        for id in ids {
            hit.items.push(target_projection::item(db, id)?);
        }
        hit.more_matches = row.count - hit.items.len() as u64;
        hits.push(hit);
    }
    #[cfg(test)]
    TIMINGS.with(|t| {
        t.set([
            compiled,
            counted - compiled,
            selected - counted,
            started.elapsed().as_secs_f64() * 1000.0 - selected,
        ])
    });
    Ok(SearchPage {
        hits,
        total_hits: total,
        next_cursor,
        index_complete: issues.is_empty(),
        issues,
        generation,
    })
}

struct Selection {
    id: i64,
    source: i64,
    path: String,
    key: u64,
    value: String,
    priority: u8,
    count: u64,
}

fn select(db: &Connection, sql: &str, params: Vec<Value>) -> Result<Vec<Selection>> {
    Ok(db
        .prepare(sql)?
        .query_map(params_from_iter(params), |r| {
            Ok(Selection {
                id: r.get(0)?,
                source: r.get(1)?,
                path: r.get(2)?,
                key: r.get(3)?,
                value: r.get(4)?,
                priority: r.get(5)?,
                count: r.get(6)?,
            })
        })?
        .collect::<rusqlite::Result<_>>()?)
}

fn ordered_page(
    db: &Connection,
    q: &SearchRequest,
    filter: &Filter,
    after: Option<&Rank>,
) -> Result<Vec<Selection>> {
    let ids = predicate_ids(db, &q.sort_predicate)?;
    let minimum = format!(
        "(SELECT MIN(value) FROM fact_values WHERE target_id=t.id AND predicate_id IN ({ids}))"
    );
    let mut result = Vec::new();
    if after.is_none_or(|a| a.priority == 0) {
        let mut params = filter.params.clone();
        let continuation = if let Some(a) = after {
            bind(
                "o.value>=? AND (o.value,s.rel_path,t.local_key)>(?,?,?)",
                vec![
                    Value::Text(a.value.clone()),
                    Value::Text(a.value.clone()),
                    Value::Text(a.path.clone()),
                    Value::Integer(a.line as i64),
                ],
                &mut params,
            )
        } else {
            "1".into()
        };
        let limit = bind(
            "?",
            vec![Value::Integer(i64::from(q.page_size) + 1)],
            &mut params,
        );
        // The value index drives this phase; equality to the independent MIN
        // preserves multi-value semantics. DISTINCT removes duplicate facts and
        // coincident values from independent owners without duplicating targets.
        let sql=format!("SELECT DISTINCT t.id,t.source_id,s.rel_path,t.local_key,o.value,0,1 FROM fact_values o INDEXED BY facts_compact_predicate_value CROSS JOIN target_slots t ON t.id=o.target_id CROSS JOIN source_maps s ON s.id=t.source_id WHERE o.target_id IS NOT NULL AND o.predicate_id IN ({ids}) AND o.value={minimum} AND {} AND {continuation} ORDER BY o.value,s.rel_path,t.local_key LIMIT {limit}",filter.condition);
        result = select(db, &sql, params)?;
    }
    if result.len() <= q.page_size as usize && after.is_none_or(|a| a.priority <= 1) {
        let mut params = filter.params.clone();
        let continuation = if let Some(a) = after.filter(|a| a.priority == 1) {
            bind(
                "('',s.rel_path,t.local_key)>(?,?,?)",
                vec![
                    Value::Text(a.value.clone()),
                    Value::Text(a.path.clone()),
                    Value::Integer(a.line as i64),
                ],
                &mut params,
            )
        } else {
            "1".into()
        };
        let limit = bind(
            "?",
            vec![Value::Integer(
                i64::from(q.page_size) + 1 - result.len() as i64,
            )],
            &mut params,
        );
        let sql=format!("SELECT t.id,t.source_id,s.rel_path,t.local_key,'',1,1 FROM {FROM} WHERE {} AND NOT EXISTS (SELECT 1 FROM fact_values WHERE target_id=t.id AND predicate_id IN ({ids})) AND {continuation} ORDER BY s.rel_path,t.local_key LIMIT {limit}",filter.condition);
        result.extend(select(db, &sql, params)?);
    }
    Ok(result)
}
impl Selection {
    fn rank(&self) -> Rank {
        Rank {
            priority: self.priority,
            value: self.value.clone(),
            matches: std::cmp::Reverse(0),
            path: self.path.clone(),
            line: self.key,
        }
    }
}

#[cfg(test)]
mod tests;
