//! Source-qualified facts. This is the same derived index and maintenance
//! lifecycle as legacy line facts; no callback or file read runs during queries.
use super::*;
use gilune_document::{ByteSpan, Location, MarkdownLayout, TextSnapshot};
use rusqlite::{params_from_iter, types::Value, Connection};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScopedFact {
    pub location: Location,
    pub predicate: String,
    pub value: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LocationKind {
    Any,
    DocumentRoot,
    BodyLine,
    MarkdownItem,
}

/// A conjunction over explicitly scoped facts, with complete keyset paging.
/// At least one positive fact constraint is required (as with `Store::query`).
/// Text/residual predicates belong to the complete search API, not this API.
#[derive(Debug, Clone)]
pub struct LocationQuery {
    pub constraints: Vec<Constraint>,
    pub kind: LocationKind,
    pub required_namespaces: Vec<String>,
    pub page_size: u32,
    pub cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LocatedMatch {
    pub rel_path: String,
    /// A fingerprint of the exact indexed source bytes, not a claim that the
    /// file has not changed externally since maintenance.
    pub source_version: String,
    pub map_version: u32,
    pub location: Location,
    pub range: ByteSpan,
    pub own_ranges: Vec<ByteSpan>,
}

#[derive(Debug, Clone)]
pub struct LocationPage {
    pub matches: Vec<LocatedMatch>,
    pub next_cursor: Option<String>,
    pub index_complete: bool,
    pub issues: Vec<String>,
    pub generation: String,
}

#[derive(Serialize, Deserialize)]
pub(crate) struct Map {
    pub(crate) version: u32,
    pub(crate) length: u64,
    pub(crate) lines: Vec<(u32, u64, u64)>,
    pub(crate) items: Vec<ItemMap>,
}

#[derive(Serialize, Deserialize)]
pub(crate) struct ItemMap {
    pub(crate) key: u64,
    pub(crate) end: u64,
    pub(crate) own: Vec<(u64, u64)>,
}

pub(super) fn encode_layout(layout: &MarkdownLayout) -> String {
    serde_json::to_string(&Map {
        version: layout.version,
        length: layout.source_len,
        lines: layout
            .lines
            .iter()
            .map(|l| (l.number, l.range.start, l.range.end))
            .collect(),
        items: layout
            .items
            .iter()
            .map(|i| ItemMap {
                key: i.key,
                end: i.range.end,
                own: i.own_ranges.iter().map(|r| (r.start, r.end)).collect(),
            })
            .collect(),
    })
    .expect("integer source map is serializable")
}

pub(super) fn typed_scoped_row(
    kinds: &BTreeMap<String, ValueKind>,
    snapshot: &TextSnapshot,
    fact: ScopedFact,
) -> std::result::Result<FactRow, String> {
    let (line, item_key) = match fact.location {
        Location::DocumentRoot => (None, None),
        Location::BodyLine { number } => {
            if snapshot
                .layout()
                .lines
                .binary_search_by_key(&number, |l| l.number)
                .is_err()
            {
                return Err("fact names an unknown body line".into());
            }
            (Some(number), None)
        }
        Location::MarkdownItem { key } => {
            if snapshot
                .layout()
                .items
                .binary_search_by_key(&key, |i| i.key)
                .is_err()
            {
                return Err("fact names an unknown Markdown item".into());
            }
            (
                None,
                Some(i64::try_from(key).map_err(|_| "item key exceeds storage range")?),
            )
        }
    };
    let mut row = typed_row(
        kinds,
        Fact {
            line,
            predicate: fact.predicate,
            value: fact.value,
        },
    );
    row.item_key = item_key;
    row.source_version = Some(snapshot.version().into());
    Ok(row)
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct Position {
    path: String,
    kind: u8,
    key: u64,
}
#[derive(Serialize, Deserialize)]
struct Cursor {
    version: u8,
    generation: String,
    fingerprint: String,
    after: Position,
}

fn invalid(message: &str) -> KernelError {
    KernelError::SearchInvalid {
        detail: message.into(),
    }
}

impl Store {
    pub fn query_locations(&self, request: &LocationQuery) -> Result<LocationPage> {
        if request.page_size == 0
            || request.page_size > 500
            || request.constraints.len() > 128
            || request.required_namespaces.len() > 128
            || request.required_namespaces.is_empty()
        {
            return Err(invalid("location query exceeds its bounds"));
        }
        for c in &request.constraints {
            c.validate()?;
            if !matches!(c, Constraint::FactAt { .. } | Constraint::FactIn { .. }) {
                return Err(invalid("location queries require explicit fact locality"));
            }
        }
        self.with_index(|db| {
            db.execute_batch("BEGIN;")?;
            let result = self.location_page(db, request);
            let end = db.execute_batch("ROLLBACK;");
            match result {
                Ok(page) => {
                    end?;
                    Ok(page)
                }
                Err(e) => Err(e),
            }
        })
    }

    fn location_page(&self, db: &Connection, request: &LocationQuery) -> Result<LocationPage> {
        let generation: String = db.query_row("SELECT (SELECT value FROM index_meta WHERE key='instance') || ':' || (SELECT value FROM index_meta WHERE key='generation')", [], |r| r.get(0))?;
        let mut identity = request.clone();
        identity.cursor = None;
        for c in &mut identity.constraints {
            c.canonicalize();
        }
        identity.required_namespaces.sort();
        identity.required_namespaces.dedup();
        let fingerprint = hex::encode(Sha256::digest(format!("{identity:?}").as_bytes()));
        let after = request
            .cursor
            .as_ref()
            .map(|s| {
                if s.len() > 16384 {
                    return Err(invalid("location cursor exceeds its bounds"));
                }
                let c: Cursor =
                    serde_json::from_str(s).map_err(|_| invalid("invalid location cursor"))?;
                if c.generation != generation {
                    return Err(KernelError::SearchCursorExpired);
                }
                if c.version != 1
                    || c.fingerprint != fingerprint
                    || c.after.kind > 2
                    || c.after.key > i64::MAX as u64
                {
                    return Err(invalid("invalid cursor or changed location query"));
                }
                Ok(c.after)
            })
            .transpose()?;
        let tip: i64 =
            self.conn()
                .query_row("SELECT COALESCE(MAX(seq),0) FROM vault_changes", [], |r| {
                    r.get(0)
                })?;
        let mut required = identity.required_namespaces;
        required.push(TEXT_NAMESPACE.into());
        required.sort();
        required.dedup();
        let mut issues = Vec::new();
        for ns in required {
            match db.query_row(
                "SELECT cursor_seq,last_error FROM namespaces WHERE namespace=?1",
                [&ns],
                |r| Ok((r.get::<_, i64>(0)?, r.get::<_, Option<String>>(1)?)),
            ) {
                Ok((seq, error)) => {
                    if let Some(error) = error {
                        issues.push(format!("{ns}: {error}"));
                    } else if seq < tip {
                        issues.push(format!("{ns}: indexing pending"));
                    }
                }
                Err(rusqlite::Error::QueryReturnedNoRows) => {
                    issues.push(format!("{ns}: index unavailable"))
                }
                Err(e) => return Err(e.into()),
            }
            if ns != TEXT_NAMESPACE && ns != FRONT_MATTER_NAMESPACE {
                let mismatch: bool = db.query_row("SELECT EXISTS (SELECT 1 FROM source_maps s LEFT JOIN fact_sources r ON r.namespace=?1 AND r.rel_path=s.rel_path WHERE r.source_version IS NOT s.source_version) OR EXISTS (SELECT 1 FROM fact_sources r LEFT JOIN source_maps s ON s.rel_path=r.rel_path WHERE r.namespace=?1 AND r.source_version IS NOT s.source_version)", [&ns], |r| r.get(0))?;
                if mismatch {
                    issues.push(format!("{ns}: source versions are not aligned"));
                }
            }
        }
        let mut page = LocationPage {
            matches: Vec::new(),
            next_cursor: None,
            index_complete: issues.is_empty(),
            issues,
            generation: generation.clone(),
        };
        if !request.constraints.iter().any(generates) {
            return Ok(page);
        }
        let (sql, params) = sql(request, after.as_ref());
        let mut stmt = db.prepare(&sql)?;
        let mut rows = stmt.query(params_from_iter(params))?;
        let mut last = None;
        while let Some(row) = rows.next()? {
            if page.matches.len() == request.page_size as usize {
                page.next_cursor = Some(
                    serde_json::to_string(&Cursor {
                        version: 1,
                        generation: generation.clone(),
                        fingerprint: fingerprint.clone(),
                        after: last.expect("nonempty page"),
                    })
                    .map_err(|_| invalid("cannot encode location cursor"))?,
                );
                break;
            }
            let path: String = row.get(0)?;
            let line: Option<u32> = row.get(1)?;
            let item: Option<u64> = row.get(2)?;
            let version: String = row.get(3)?;
            let kind = if item.is_some() {
                2
            } else if line.is_some() {
                1
            } else {
                0
            };
            last = Some(Position {
                path: path.clone(),
                kind,
                key: item.unwrap_or(line.unwrap_or(0) as u64),
            });
            let location = if let Some(key) = item {
                Location::MarkdownItem { key }
            } else if let Some(number) = line {
                Location::BodyLine { number }
            } else {
                Location::DocumentRoot
            };
            page.matches
                .push(targets::resolve(db, path, version, location)?);
        }
        Ok(page)
    }
}

fn sql(request: &LocationQuery, after: Option<&Position>) -> (String, Vec<Value>) {
    let pieces: Vec<_> = request.constraints.iter().map(scoped_piece).collect();
    let mut params = Vec::new();
    let namespaces = serde_json::json!(request.required_namespaces).to_string();
    let mut ctes: Vec<_> = pieces
        .iter()
        .enumerate()
        .map(|(i, p)| {
            params.extend(p.params.clone());
            params.push(Value::Text(namespaces.clone()));
            format!("c{i} AS MATERIALIZED (SELECT * FROM ({}) WHERE source_version IS NOT NULL AND namespace IN (SELECT value FROM json_each(?)))", p.sql)
        })
        .collect();
    // Any exact positive is a complete seed for a conjunction. It avoids
    // manufacturing a union of every domain's candidate set.
    let seed = pieces.iter().position(|p| p.generates && !p.broadcast_root);
    let positive = pieces
        .iter()
        .enumerate()
        .filter(|(i, p)| p.generates && seed.is_none_or(|seed| seed == *i))
        .map(|(i, _)| format!("SELECT * FROM c{i}"))
        .collect::<Vec<_>>()
        .join(" UNION ");
    ctes.push(format!("candidates AS ({positive})"));
    let mut conditions = vec!["a.source_version = s.source_version".into()];
    for ns in request.required_namespaces.iter().collect::<BTreeSet<_>>() {
        if ns != TEXT_NAMESPACE && ns != FRONT_MATTER_NAMESPACE {
            conditions.push("EXISTS (SELECT 1 FROM fact_sources r WHERE r.namespace=? AND r.rel_path=a.rel_path AND r.source_version=a.source_version)".into());
            params.push(Value::Text(ns.clone()));
        }
    }
    match request.kind {
        LocationKind::Any => {}
        LocationKind::DocumentRoot => {
            conditions.push("a.line IS NULL AND a.item_key IS NULL".into())
        }
        LocationKind::BodyLine => conditions.push("a.line IS NOT NULL".into()),
        LocationKind::MarkdownItem => conditions.push("a.item_key IS NOT NULL".into()),
    }
    for (i, p) in pieces.iter().enumerate() {
        if seed == Some(i) {
            continue;
        }
        // Tuple membership lets SQLite build each matching set once. A
        // correlated EXISTS over a materialized CTE would rescan that set for
        // every candidate. Sentinels are outside validated line/item ranges.
        let mut matching = format!("(a.rel_path,a.source_version,COALESCE(a.line,0),COALESCE(a.item_key,-1)) IN (SELECT rel_path,source_version,COALESCE(line,0),COALESCE(item_key,-1) FROM c{i})");
        if p.broadcast_root {
            matching.push_str(&format!(" OR (a.rel_path,a.source_version) IN (SELECT rel_path,source_version FROM c{i} WHERE line IS NULL AND item_key IS NULL)"));
        }
        conditions.push(format!(
            "{}({matching})",
            if p.generates { "" } else { "NOT " }
        ));
    }
    let kind = "CASE WHEN a.item_key IS NOT NULL THEN 2 WHEN a.line IS NOT NULL THEN 1 ELSE 0 END";
    let key = "COALESCE(a.item_key,a.line,0)";
    if let Some(after) = after {
        conditions.push(format!("(a.rel_path,{kind},{key}) > (?,?,?)"));
        params.extend([
            Value::Text(after.path.clone()),
            Value::Integer(after.kind.into()),
            Value::Integer(after.key as i64),
        ]);
    }
    params.push(Value::Integer(request.page_size as i64 + 1));
    (format!("WITH {} SELECT DISTINCT a.rel_path,a.line,a.item_key,a.source_version FROM candidates a JOIN source_maps s ON s.rel_path=a.rel_path WHERE {} ORDER BY a.rel_path,{kind},{key} LIMIT ?", ctes.join(","), conditions.join(" AND ")), params)
}

#[cfg(test)]
mod tests;
