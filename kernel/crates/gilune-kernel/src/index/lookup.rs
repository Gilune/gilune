//! Ranked, paged lookup of source-qualified document roots. Plugins declare
//! predicates, alternatives and ordering; only the index executes them.
use super::*;
use rusqlite::{params_from_iter, types::Value, Connection};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Debug, Clone)]
pub struct LookupAlternative {
    pub constraints: Vec<Constraint>,
    /// Lower priorities sort first. A root matching several alternatives uses
    /// the lowest priority and appears once.
    pub priority: u32,
}

#[derive(Debug, Clone)]
pub struct LookupRequest {
    pub constraints: Vec<Constraint>,
    /// OR of conjunctions. Empty means no additional restriction, priority 0.
    pub alternatives: Vec<LookupAlternative>,
    /// Ascending minimum value per predicate; missing values sort last.
    pub sort_predicates: Vec<String>,
    pub display_predicates: Vec<String>,
    pub required_namespaces: Vec<String>,
    pub page_size: u32,
    pub cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LookupFact {
    pub predicate: String,
    pub value: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LookupHit {
    pub rel_path: String,
    pub source_version: String,
    pub priority: u32,
    pub facts: Vec<LookupFact>,
}
#[derive(Debug, Clone)]
pub struct LookupPage {
    pub hits: Vec<LookupHit>,
    pub total_hits: u64,
    pub next_cursor: Option<String>,
    pub generation: String,
    pub index_complete: bool,
    pub issues: Vec<String>,
}

#[derive(Serialize, Deserialize)]
struct Cursor {
    version: u8,
    generation: String,
    fingerprint: String,
    offset: u64,
}
fn invalid(detail: &str) -> KernelError {
    KernelError::SearchInvalid {
        detail: detail.into(),
    }
}
impl Store {
    pub fn lookup(&self, request: &LookupRequest) -> Result<LookupPage> {
        let mut identity = request.clone();
        identity.cursor = None;
        if !(1..=500).contains(&request.page_size)
            || request.alternatives.len() > 16
            || request.sort_predicates.len() > 4
            || request.display_predicates.len() > 32
            || request.required_namespaces.is_empty()
            || request.required_namespaces.len() > 32
            || request.constraints.len()
                + request
                    .alternatives
                    .iter()
                    .map(|a| a.constraints.len())
                    .sum::<usize>()
                > 128
        {
            return Err(invalid("lookup exceeds its bounds"));
        }
        for c in identity.constraints.iter_mut().chain(
            identity
                .alternatives
                .iter_mut()
                .flat_map(|a| &mut a.constraints),
        ) {
            c.validate()?;
            if !matches!(
                c,
                Constraint::FactAt {
                    locality: FactLocality::DocumentRoot,
                    ..
                } | Constraint::FactIn {
                    locality: FactLocality::DocumentRoot,
                    ..
                }
            ) {
                return Err(invalid("lookup requires document-root fact constraints"));
            }
            c.canonicalize();
        }
        if request
            .sort_predicates
            .iter()
            .chain(&request.display_predicates)
            .chain(&request.required_namespaces)
            .any(|s| s.is_empty() || s.len() > 1024)
        {
            return Err(invalid("invalid lookup predicate or namespace"));
        }
        identity.required_namespaces.sort();
        identity.required_namespaces.dedup();
        identity.display_predicates.sort();
        identity.display_predicates.dedup();
        let fingerprint = hex::encode(Sha256::digest(format!("{identity:?}").as_bytes()));
        #[cfg(test)]
        tests::profile::mark("request validation/canonicalisation/fingerprint");
        self.with_index(|db| {
            db.execute_batch("BEGIN;")?;
            #[cfg(test)]
            tests::profile::mark("index access/transaction begin");
            let result = self.lookup_page(db, &identity, request.cursor.as_deref(), &fingerprint);
            let end = db.execute_batch("ROLLBACK;");
            #[cfg(test)]
            tests::profile::mark("result/cursor assembly/transaction end");
            match result {
                Ok(page) => {
                    end?;
                    Ok(page)
                }
                Err(e) => Err(e),
            }
        })
    }

    fn lookup_page(
        &self,
        db: &Connection,
        request: &LookupRequest,
        cursor: Option<&str>,
        fingerprint: &str,
    ) -> Result<LookupPage> {
        let generation: String = db.query_row("SELECT (SELECT value FROM index_meta WHERE key='instance') || ':' || (SELECT value FROM index_meta WHERE key='generation')", [], |r| r.get(0))?;
        let offset = if let Some(raw) = cursor {
            if raw.len() > 4096 {
                return Err(invalid("lookup cursor exceeds its bounds"));
            }
            let c: Cursor =
                serde_json::from_str(raw).map_err(|_| invalid("invalid lookup cursor"))?;
            if c.version != 1 || c.generation != generation || c.fingerprint != fingerprint {
                return Err(KernelError::SearchCursorExpired);
            }
            if c.offset > i64::MAX as u64 {
                return Err(invalid("invalid lookup offset"));
            }
            c.offset
        } else {
            0
        };
        let tip: i64 =
            self.conn()
                .query_row("SELECT COALESCE(MAX(seq),0) FROM vault_changes", [], |r| {
                    r.get(0)
                })?;
        let mut issues = Vec::new();
        #[cfg(test)]
        tests::profile::mark("generation/cursor/feed tip");
        let mut required = request.required_namespaces.clone();
        required.push(TEXT_NAMESPACE.into());
        required.sort();
        required.dedup();
        for ns in required {
            match db.query_row(
                "SELECT cursor_seq,last_error,scoped FROM namespaces WHERE namespace=?1",
                [&ns],
                |r| {
                    Ok((
                        r.get::<_, i64>(0)?,
                        r.get::<_, Option<String>>(1)?,
                        r.get::<_, bool>(2)?,
                    ))
                },
            ) {
                Ok((seq, error, scoped)) => {
                    if let Some(e) = error {
                        issues.push(format!("{ns}: {e}"));
                    } else if seq < tip {
                        issues.push(format!("{ns}: indexing pending"));
                    }
                    if !scoped && ns != TEXT_NAMESPACE && ns != FRONT_MATTER_NAMESPACE {
                        issues.push(format!("{ns}: source-qualified input required"));
                    }
                }
                Err(rusqlite::Error::QueryReturnedNoRows) => {
                    issues.push(format!("{ns}: index unavailable"))
                }
                Err(e) => return Err(e.into()),
            }
            if ns != TEXT_NAMESPACE {
                let mismatch: bool = db.query_row("SELECT EXISTS (SELECT 1 FROM source_maps s LEFT JOIN fact_sources r ON r.namespace=?1 AND r.rel_path=s.rel_path WHERE r.source_version IS NOT s.source_version) OR EXISTS (SELECT 1 FROM fact_sources r LEFT JOIN source_maps s ON s.rel_path=r.rel_path WHERE r.namespace=?1 AND r.source_version IS NOT s.source_version)", [&ns], |r| r.get(0))?;
                if mismatch {
                    issues.push(format!("{ns}: source versions are not aligned"));
                }
            }
        }
        #[cfg(test)]
        tests::profile::mark("namespace/source freshness SQL");
        let (ctes, params, order) = sql(request);
        let mut page_params = params.clone();
        page_params.push(Value::Integer(request.page_size.into()));
        page_params.push(Value::Integer(offset as i64));
        #[cfg(test)]
        tests::profile::mark("SQL composition/parameters");
        let mut stmt = db.prepare(&format!("{ctes} SELECT rel_path,source_version,priority,COUNT(*) OVER() FROM ranked WHERE priority < 4294967296 ORDER BY {order} LIMIT ? OFFSET ?"))?;
        #[cfg(test)]
        tests::profile::mark("SQLite prepare");
        let mut total = 0;
        let mut hits = stmt
            .query_map(params_from_iter(page_params), |r| {
                total = r.get::<_, u64>(3)?;
                Ok(LookupHit {
                    rel_path: r.get(0)?,
                    source_version: r.get(1)?,
                    priority: r.get(2)?,
                    facts: Vec::new(),
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        #[cfg(test)]
        tests::profile::mark("SQLite execute/result rows");
        // A caller-supplied offset beyond the result set still receives its
        // correct total. Ordinary pages get totals in the same selection pass.
        if hits.is_empty() && offset > 0 {
            total = db.query_row(
                &format!("{ctes} SELECT COUNT(*) FROM matched WHERE priority < 4294967296"),
                params_from_iter(params),
                |r| r.get(0),
            )?;
        }
        // Hydrate only retained roots, and only requested fields from declared producers.
        for hit in &mut hits {
            let mut statement = db.prepare_cached("SELECT namespace,predicate,value FROM facts WHERE rel_path=?1 AND source_version=?2 AND line IS NULL AND item_key IS NULL ORDER BY predicate,value")?;
            for row in
                statement.query_map(rusqlite::params![hit.rel_path, hit.source_version], |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                    ))
                })?
            {
                let (ns, predicate, value) = row?;
                if request.required_namespaces.contains(&ns)
                    && request.display_predicates.contains(&predicate)
                {
                    hit.facts.push(LookupFact { predicate, value });
                }
            }
        }
        #[cfg(test)]
        tests::profile::mark("selected-root fact hydration");
        let next = offset + hits.len() as u64;
        let next_cursor = if next < total && !hits.is_empty() {
            Some(serde_json::to_string(&Cursor {
                version: 1,
                generation: generation.clone(),
                fingerprint: fingerprint.into(),
                offset: next,
            })?)
        } else {
            None
        };
        Ok(LookupPage {
            hits,
            total_hits: total,
            next_cursor,
            generation,
            index_complete: issues.is_empty(),
            issues,
        })
    }
}

fn compact_condition(c: &Constraint, namespaces: &[String]) -> (String, Vec<Value>, bool) {
    let (predicate, condition, values, negative) = match c {
        Constraint::FactAt { predicate, op, value, .. } => {
            let (sql, args) = fact_value_condition(*op, value);
            (predicate, sql, args, !op.generates())
        }
        Constraint::FactIn { predicate, values, .. } => (predicate,
            " AND value IN (SELECT value FROM json_each(?))".into(),
            vec![Value::Text(serde_json::json!(values).to_string())], false),
        _ => unreachable!("validated root lookup"),
    };
    let mut params = vec![Value::Text(predicate.clone()), Value::Text(serde_json::json!(namespaces).to_string())];
    params.extend(values);
    (format!("predicate_id IN (SELECT id FROM index_predicates WHERE predicate=? AND namespace IN (SELECT value FROM json_each(?))){condition}"), params, negative)
}

fn sql(request: &LookupRequest) -> (String, Vec<Value>, String) {
    let mut params = Vec::new();
    let mut ctes = Vec::new();
    let mut conjunction = |constraints: &[Constraint]| {
        let mut positive = Vec::new();
        let mut negative = Vec::new();
        for c in constraints {
            let (condition, args, negated) = compact_condition(c, &request.required_namespaces);
            let id = ctes.len();
            ctes.push(format!("c{id} AS (SELECT target_id AS id FROM fact_values WHERE target_id IS NOT NULL AND {condition})"));
            params.extend(args);
            let select = format!("SELECT id FROM c{id}");
            if negated { negative.push(select); } else { positive.push(select); }
        }
        if positive.is_empty() { positive.push("SELECT id FROM target_slots WHERE kind=0".into()); }
        let mut select = positive.join(" INTERSECT ");
        for exclude in negative { select.push_str(" EXCEPT "); select.push_str(&exclude); }
        let name = format!("g{}", ctes.len());
        ctes.push(format!("{name} AS ({select})"));
        name
    };
    let mut alternatives: Vec<_> = request.alternatives.iter().map(|a| {
        let group = conjunction(&a.constraints);
        format!("SELECT id,{} AS priority FROM {group}", a.priority)
    }).collect();
    if alternatives.is_empty() {
        let group = conjunction(&request.constraints);
        alternatives.push(format!("SELECT id,0 AS priority FROM {group}"));
    }
    ctes.push(format!("alternatives AS ({})", alternatives.join(" UNION ALL ")));
    ctes.push("candidates AS MATERIALIZED (SELECT id,MIN(priority) AS priority FROM alternatives GROUP BY id)".into());
    let mut conditions = vec!["t.kind=0".to_string()];
    if !request.alternatives.is_empty() {
        for c in &request.constraints {
            let (condition, args, negated) = compact_condition(c, &request.required_namespaces);
            conditions.push(format!("{}EXISTS (SELECT 1 FROM fact_values WHERE target_id=t.id AND {condition})", if negated { "NOT " } else { "" }));
            params.extend(args);
        }
    }
    for ns in &request.required_namespaces {
        conditions.push("EXISTS (SELECT 1 FROM fact_sources r WHERE r.rel_path=s.rel_path AND r.source_version=s.source_version AND r.namespace=?)".into());
        params.push(Value::Text(ns.clone()));
    }
    ctes.push(format!("matched AS MATERIALIZED (SELECT t.id,s.rel_path,s.source_version,b.priority FROM candidates b CROSS JOIN target_slots t ON t.id=b.id CROSS JOIN source_maps s ON s.id=t.source_id WHERE {})", conditions.join(" AND ")));
    let mut sorts = Vec::new();
    let mut order = vec!["priority".to_string()];
    for (i, predicate) in request.sort_predicates.iter().enumerate() {
        sorts.push(format!("(SELECT MIN(value) FROM fact_values WHERE target_id=m.id AND predicate_id IN (SELECT id FROM index_predicates WHERE predicate=? AND namespace IN (SELECT value FROM json_each(?)))) AS s{i}"));
        params.push(Value::Text(predicate.clone()));
        params.push(Value::Text(serde_json::json!(request.required_namespaces).to_string()));
        order.push(format!("s{i} IS NULL,s{i}"));
    }
    order.push("rel_path".into());
    ctes.push(if sorts.is_empty() { "ranked AS (SELECT * FROM matched)".into() }
        else { format!("ranked AS (SELECT m.*,{} FROM matched m)", sorts.join(",")) });
    (format!("WITH {}", ctes.join(",")), params, order.join(","))
}

#[cfg(test)]
mod tests;
