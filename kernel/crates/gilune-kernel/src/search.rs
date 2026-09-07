//! Complete, paged search results from a consistent index snapshot.
//! Plugins supply predicates; this service owns execution and result construction.

use crate::index::{self, Constraint, IndexedFact, TextTarget};
use crate::search_text::{fold, ranges};
use crate::store::Store;
use crate::{KernelError, Result};
use rusqlite::{params_from_iter, types::Value, Connection};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BinaryHeap};

mod items;
#[cfg(test)]
mod position_prototype;
mod target_query;
mod target_scan;
mod text_window;
mod anchor_page;
mod target_projection;
mod reader;
pub use reader::{SearchQuery, SearchSlice, SearchCount};

#[derive(Clone, Copy, PartialEq, Eq)]
enum Execution { Complete, Page, Count }

pub const MAX_PAGE_SIZE: u32 = 500;
pub const MAX_SNIPPET_LINES: u32 = 200;

/// A conjunction within one term. Negation applies to the whole term.
/// A residual delegates only its predicate, over an indexed snapshot.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchClause {
    pub constraints: Vec<Constraint>,
    pub negated: bool,
    pub case_sensitive: bool,
    pub residual: Option<u32>,
    pub never: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchRequest {
    pub clauses: Vec<SearchClause>,
    pub line_mode: bool,
    pub extension_candidates: bool,
    pub group_by_note: bool,
    pub body_needles: Vec<String>,
    pub name_needles: Vec<String>,
    /// Ascending lexical fact value, absent last. Empty means relevance.
    pub sort_predicate: String,
    pub required_namespaces: Vec<String>,
    /// Identity/configuration of any callback, bound into the continuation.
    pub extension_key: String,
    pub page_size: u32,
    pub max_lines: u32,
    pub cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchDocument {
    pub rel_path: String,
    pub name: String,
    pub body: String,
    pub properties: Vec<SearchProperty>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchProperty {
    pub key: String,
    pub value: String,
}

/// Pure plugin predicate. Calls occur on the querying worker, never in UI
/// composition. Implementations must not re-enter the store or perform I/O.
pub trait SearchExtension: Send + Sync {
    fn matches(&self, key: u32, document: &SearchDocument) -> std::result::Result<bool, String>;
    fn candidates(&self, _document: &SearchDocument) -> std::result::Result<Vec<u32>, String> {
        Ok(Vec::new())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchRange {
    pub start: u32,
    pub end: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchLine {
    pub number: u32,
    pub text: String,
    /// UTF-16, start inclusive and end exclusive.
    pub ranges: Vec<SearchRange>,
    pub facts: Vec<SearchProperty>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchHit {
    /// Document-root facts, hydrated only for returned hits (or indexed sorting).
    pub facts: Vec<SearchProperty>,
    pub rel_path: String,
    pub name: String,
    pub name_matched: bool,
    pub lines: Vec<SearchLine>,
    /// Item-mode results are explicit targets, not fictitious physical lines.
    pub items: Vec<SearchItem>,
    pub more_matches: u64,
    pub match_count: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchItem {
    pub key: u64,
    pub source_version: String,
    pub map_version: u32,
    pub range: gilune_document::ByteSpan,
    pub own_ranges: Vec<gilune_document::ByteSpan>,
    pub first_line: u32,
    pub text: String,
    pub ranges: Vec<SearchRange>,
    pub facts: Vec<SearchProperty>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchPage {
    pub hits: Vec<SearchHit>,
    pub total_hits: u64,
    pub next_cursor: Option<String>,
    pub index_complete: bool,
    pub issues: Vec<String>,
    pub generation: String,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct Rank {
    priority: u8,
    value: String,
    matches: std::cmp::Reverse<u64>,
    path: String,
    line: u64,
}

struct Ranked {
    rank: Rank,
    hit: SearchHit,
}
impl PartialEq for Ranked {
    fn eq(&self, other: &Self) -> bool {
        self.rank == other.rank
    }
}
impl Eq for Ranked {}
impl PartialOrd for Ranked {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for Ranked {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.rank.cmp(&other.rank)
    }
}

impl Store {
    /// Search never reads vault files or silently repairs the index. Each page
    /// uses one read transaction; callers can request maintenance separately.
    pub fn search(
        &self,
        request: &SearchRequest,
        extension: Option<&dyn SearchExtension>,
    ) -> Result<SearchPage> {
        self.search_mode(request, extension, false)
    }

    /// The same request language, ranking, paging and extension contract over
    /// source-qualified Markdown items. Target membership is contributed facts.
    pub fn search_items(&self, request: &SearchRequest, extension: Option<&dyn SearchExtension>) -> Result<SearchPage> {
        if !request.line_mode || request.extension_candidates {
            return Err(invalid("item search requires indexed item membership, without a line extractor"));
        }
        self.search_mode(request, extension, true)
    }

    fn search_mode(&self, request: &SearchRequest, extension: Option<&dyn SearchExtension>, item_mode: bool) -> Result<SearchPage> {
        validate(request)?;
        self.with_index(|db| {
            db.execute_batch("BEGIN;")?;
            let result = search_snapshot(db, request, extension, item_mode, Execution::Complete, || {
                Ok(self.conn().query_row("SELECT COALESCE(MAX(seq),0) FROM vault_changes", [], |r| r.get(0))?)
            });
            let end = db.execute_batch("ROLLBACK;");
            match result {
                Ok(page) => {
                    end?;
                    Ok(page)
                }
                Err(error) => Err(error),
            }
        })
    }

}

    fn search_snapshot(
        db: &Connection,
        request: &SearchRequest,
        extension: Option<&dyn SearchExtension>,
        item_mode: bool,
        execution: Execution,
        tip: impl FnOnce() -> Result<i64>,
    ) -> Result<SearchPage> {
        let generation: String = db.query_row(
            "SELECT (SELECT value FROM index_meta WHERE key='instance') || ':' || (SELECT value FROM index_meta WHERE key='generation')", [], |row| row.get(0))?;
        let mut identity = request.clone();
        identity.cursor = None;
        for constraint in identity.clauses.iter_mut().flat_map(|c| &mut c.constraints) {
            constraint.canonicalize();
        }
        let fingerprint = hex::encode(Sha256::digest(format!("{item_mode}:{identity:?}").as_bytes()));
        let after = decode_cursor(request.cursor.as_deref(), &generation, &fingerprint)?;
        // Read the feed after establishing the index snapshot: concurrent work
        // can conservatively report lag, but cannot disguise it as current.
        let tip = tip()?;
        let mut required = request.required_namespaces.clone();
        required.extend([
            index::TEXT_NAMESPACE.to_string(),
            index::FRONT_MATTER_NAMESPACE.to_string(),
        ]);
        required.sort();
        required.dedup();
        let mut issues = Vec::new();
        let mut scoped_required=Vec::new();
        for namespace in required {
            let state = db.query_row(
                "SELECT cursor_seq, last_error FROM namespaces WHERE namespace=?1",
                [&namespace],
                |row| Ok((row.get::<_, i64>(0)?, row.get::<_, Option<String>>(1)?)),
            );
            match state {
                Ok((seq, error)) => {
                    if let Some(error) = error {
                        issues.push(format!("{namespace}: {error}"));
                    } else if seq < tip {
                        issues.push(format!("{namespace}: indexing pending"));
                    }
                }
                Err(rusqlite::Error::QueryReturnedNoRows) => {
                    issues.push(format!("{namespace}: index unavailable"))
                }
                Err(error) => return Err(error.into()),
            }
            let scoped: bool=db.query_row("SELECT COALESCE((SELECT scoped FROM namespaces WHERE namespace=?1),0)",[&namespace],|r| r.get(0))?;
            if scoped {
                let mismatch: bool=db.query_row("SELECT EXISTS (SELECT 1 FROM source_maps s LEFT JOIN fact_sources r ON r.namespace=?1 AND r.rel_path=s.rel_path WHERE r.source_version IS NOT s.source_version) OR EXISTS (SELECT 1 FROM fact_sources r LEFT JOIN source_maps s ON s.rel_path=r.rel_path WHERE r.namespace=?1 AND r.source_version IS NOT s.source_version)",[&namespace],|r| r.get(0))?;
                if mismatch { issues.push(format!("{namespace}: source versions are not aligned")); }
                scoped_required.push(namespace);
            }
        }
        if item_mode {
            return items::execute(db, request, extension, generation, fingerprint, after, issues, execution);
        }
        let (mut sql, mut params) = build_sql(request);
        if !scoped_required.is_empty() {
            let checks=scoped_required.iter().map(|ns| {
                params.push(Value::Text(ns.clone()));
                "EXISTS (SELECT 1 FROM fact_sources r WHERE r.namespace=? AND r.rel_path=s.rel_path AND r.source_version=s.source_version)"
            }).collect::<Vec<_>>().join(" AND ");
            sql=format!("SELECT selected.* FROM ({sql}) selected JOIN source_maps s ON s.rel_path=selected.rel_path WHERE {checks} ORDER BY selected.rel_path,selected.line");
        }
        if anchor_page::supports(request) {
            return anchor_page::search(db, request, &sql, params, generation, fingerprint, after, issues, execution);
        }
        let mut stmt = db.prepare(&sql)?;
        let mut rows = stmt.query(params_from_iter(params.iter()))?;
        let mut pending_path: Option<String> = None;
        let mut selected = Vec::new();
        let mut heap = BinaryHeap::new();
        let mut total = 0u64;
        let mut remaining = 0u64;
        let mut consume = |path: &str, selected: &[u32]| -> Result<()> {
            let has_extension = request.extension_candidates
                || request.clauses.iter().any(|c| c.residual.is_some());
            let document = document(
                db,
                path,
                has_extension,
                has_extension || request.line_mode || !request.body_needles.is_empty(),
            )?;
            let mut indexed_facts: BTreeMap<u32, Vec<SearchProperty>> = BTreeMap::new();
            for fact in if request.sort_predicate.is_empty() {
                Vec::new()
            } else {
                facts(db, path)?
            } {
                indexed_facts
                    .entry(fact.line.unwrap_or(0))
                    .or_default()
                    .push(SearchProperty {
                        key: fact.predicate,
                        value: fact.value,
                    });
            }
            let lines: Vec<&str> = document.body.split('\n').collect();
            let mut kept = Vec::new();
            if request.line_mode {
                let candidates = if request.extension_candidates {
                    Some(
                        extension
                            .ok_or_else(|| invalid("query requires a type extractor"))?
                            .candidates(&document)
                            .map_err(|e| invalid(&e))?,
                    )
                } else {
                    None
                };
                for &number in selected {
                    if candidates
                        .as_ref()
                        .is_some_and(|lines| !lines.contains(&number))
                    {
                        continue;
                    }
                    let Some(text) = number.checked_sub(1).and_then(|n| lines.get(n as usize))
                    else {
                        continue;
                    };
                    if request.clauses.iter().all(|c| c.residual.is_none()) {
                        kept.push(number);
                    } else {
                        let narrowed = SearchDocument {
                            body: (*text).to_string(),
                            rel_path: document.rel_path.clone(),
                            name: document.name.clone(),
                            properties: document.properties.clone(),
                        };
                        if residuals(request, extension, &narrowed)? {
                            kept.push(number);
                        }
                    }
                }
                if kept.is_empty() {
                    return Ok(());
                }
            } else if !residuals(request, extension, &document)? {
                return Ok(());
            }
            let groups: Vec<Vec<u32>> = if request.line_mode && !request.group_by_note {
                kept.into_iter().map(|line| vec![line]).collect()
            } else {
                vec![kept]
            };
            for group in groups {
                let hit = make_hit(&document, &indexed_facts, &lines, &group, request);
                let anchor = if request.line_mode && !request.group_by_note {
                    group.first().copied().unwrap_or(0)
                } else {
                    0
                };
                let sort_value = indexed_facts
                    .get(&anchor)
                    .into_iter()
                    .flatten()
                    .chain(indexed_facts.get(&0).into_iter().flatten())
                    .filter(|f| f.key == request.sort_predicate)
                    .map(|f| f.value.clone())
                    .min();
                let rank = if request.sort_predicate.is_empty() {
                    Rank {
                        priority: u8::from(!hit.name_matched),
                        value: String::new(),
                        matches: std::cmp::Reverse(hit.match_count),
                        path: path.to_string(),
                        line: anchor.into(),
                    }
                } else {
                    Rank {
                        priority: u8::from(sort_value.is_none()),
                        value: sort_value.unwrap_or_default(),
                        matches: std::cmp::Reverse(0),
                        path: path.to_string(),
                        line: anchor.into(),
                    }
                };
                total += 1;
                if after.as_ref().is_some_and(|previous| rank <= *previous) {
                    continue;
                }
                remaining += 1;
                heap.push(Ranked { rank, hit });
                if heap.len() > request.page_size as usize {
                    heap.pop();
                }
            }
            Ok(())
        };
        while let Some(row) = rows.next()? {
            let path: String = row.get(0)?;
            if pending_path
                .as_ref()
                .is_some_and(|previous| previous != &path)
            {
                if let Some(previous) = &pending_path {
                    consume(previous, &selected)?;
                }
                selected.clear();
            }
            pending_path = Some(path);
            if let Some(line) = row.get::<_, Option<u32>>(1)? {
                selected.push(line);
            }
        }
        if let Some(path) = pending_path {
            consume(&path, &selected)?;
        }
        let mut ranked = heap.into_sorted_vec();
        // Hydrate each surviving document once, even when a page contains
        // hundreds of individually ranked lines from it. Keep at most one
        // document's fact rows in memory; result order remains the ranking's.
        if request.sort_predicate.is_empty() {
            let mut by_path: BTreeMap<String, Vec<usize>> = BTreeMap::new();
            for (index, result) in ranked.iter().enumerate() {
                {
                    by_path
                        .entry(result.hit.rel_path.clone())
                        .or_default()
                        .push(index);
                }
            }
            for (path, indices) in by_path {
                let facts = facts(db, &path)?;
                for index in indices {
                    ranked[index].hit.facts = facts.iter().filter(|f| f.line.is_none())
                        .map(|f| SearchProperty { key: f.predicate.clone(), value: f.value.clone() }).collect();
                    for line in &mut ranked[index].hit.lines {
                        line.facts = facts
                            .iter()
                            .filter(|f| f.line.is_none() || f.line == Some(line.number))
                            .map(|f| SearchProperty {
                                key: f.predicate.clone(),
                                value: f.value.clone(),
                            })
                            .collect();
                    }
                }
            }
        }
        let next_cursor = if remaining > ranked.len() as u64 {
            ranked
                .last()
                .map(|last| encode_cursor(&last.rank, &generation, &fingerprint))
        } else {
            None
        };
        Ok(SearchPage {
            hits: ranked.into_iter().map(|r| r.hit).collect(),
            total_hits: total,
            next_cursor,
            index_complete: issues.is_empty(),
            issues,
            generation,
        })
    }

fn residuals(
    request: &SearchRequest,
    extension: Option<&dyn SearchExtension>,
    document: &SearchDocument,
) -> Result<bool> {
    for clause in &request.clauses {
        if let Some(key) = clause.residual {
            let plugin = extension
                .ok_or_else(|| invalid("query requires an unavailable plugin predicate"))?;
            let matched = plugin
                .matches(key, document)
                .map_err(|detail| invalid(&detail))?;
            if matched == clause.negated {
                return Ok(false);
            }
        }
    }
    Ok(true)
}

fn document(
    db: &Connection,
    path: &str,
    with_properties: bool,
    with_body: bool,
) -> Result<SearchDocument> {
    let mut stmt =
        db.prepare_cached("SELECT field, text FROM lines WHERE rel_path=?1 AND (field='name' OR (?2 AND field='body')) ORDER BY line")?;
    let mut name = String::new();
    let mut body = Vec::new();
    for row in stmt.query_map(rusqlite::params![path, with_body], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
    })? {
        let (field, text) = row?;
        match field.as_str() {
            "name" => name = text,
            "body" => body.push(text),
            _ => {}
        }
    }
    let properties = if with_properties {
        let mut props = db.prepare_cached("SELECT predicate, value FROM facts WHERE namespace='frontmatter' AND rel_path=?1 ORDER BY rowid")?;
        let properties = props
            .query_map([path], |row| {
                let key: String = row.get(0)?;
                Ok(SearchProperty {
                    key: key.strip_prefix("frontmatter.").unwrap_or(&key).to_string(),
                    value: row.get(1)?,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        properties
    } else {
        Vec::new()
    };
    Ok(SearchDocument {
        rel_path: path.to_string(),
        name,
        body: body.join("\n"),
        properties,
    })
}

fn facts(db: &Connection, path: &str) -> Result<Vec<IndexedFact>> {
    let mut stmt = db.prepare_cached(
        "SELECT namespace, line, predicate, value FROM facts WHERE rel_path=?1 AND item_key IS NULL ORDER BY rowid",
    )?;
    let rows = stmt.query_map([path], |row| {
        Ok(IndexedFact {
            rel_path: path.to_string(),
            namespace: row.get(0)?,
            line: row.get(1)?,
            predicate: row.get(2)?,
            value: row.get(3)?,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

fn make_hit(
    document: &SearchDocument,
    facts: &BTreeMap<u32, Vec<SearchProperty>>,
    body_lines: &[&str],
    selected: &[u32],
    request: &SearchRequest,
) -> SearchHit {
    let name_matched = request
        .name_needles
        .iter()
        .any(|n| !n.is_empty() && fold(&document.name).contains(&fold(n)));
    let mut lines = Vec::new();
    let mut count = 0u64;
    let mut more = 0u64;
    let candidates: Box<dyn Iterator<Item = (usize, &str)> + '_> = if request.line_mode {
        Box::new(
            selected
                .iter()
                .filter_map(|n| n.checked_sub(1))
                .filter_map(|n| body_lines.get(n as usize).map(|line| (n as usize, *line))),
        )
    } else {
        Box::new(body_lines.iter().copied().enumerate())
    };
    for (index, text) in candidates {
        let number = index as u32 + 1;
        let found = ranges(text, &request.body_needles);
        if !request.line_mode && found.is_empty() {
            continue;
        }
        count += found.len() as u64;
        if lines.len() >= request.max_lines as usize {
            more += 1;
            continue;
        }
        lines.push(SearchLine {
            number,
            text: text.to_string(),
            ranges: found
                .into_iter()
                .map(|(start, end)| SearchRange { start, end })
                .collect(),
            facts: facts
                .get(&number)
                .into_iter()
                .flatten()
                .chain(facts.get(&0).into_iter().flatten())
                .cloned()
                .collect(),
        });
    }
    SearchHit {
        facts: facts.get(&0).cloned().unwrap_or_default(),
        rel_path: document.rel_path.clone(),
        name: document.name.clone(),
        name_matched,
        lines,
        items: Vec::new(),
        more_matches: more,
        match_count: count,
    }
}

/// Constraints are materialised once, then joined as sets. Document mode
/// intersects paths; line mode intersects anchors, broadcasting note facts.
fn build_sql(request: &SearchRequest) -> (String, Vec<Value>) {
    let mut ctes = Vec::new();
    let mut params = Vec::new();
    let mut terms = Vec::new();
    for clause in &request.clauses {
        if clause.residual.is_some() {
            continue;
        }
        let mut parts = Vec::new();
        for constraint in &clause.constraints {
            let mut part = if request.line_mode { index::piece(constraint) } else { index::document_piece(constraint) };
            if let Constraint::Text { target, query } = constraint {
                if !request.line_mode
                    && query.contains('\n')
                    && matches!(target, TextTarget::Any | TextTarget::Body)
                {
                    let column = if clause.case_sensitive {
                        "text"
                    } else {
                        "folded"
                    };
                    part.sql = format!("SELECT rel_path, NULL AS line FROM (SELECT rel_path, group_concat({column}, char(10)) AS body FROM (SELECT rel_path, {column} FROM lines WHERE field='body' ORDER BY rel_path,line) GROUP BY rel_path) WHERE instr(body, ?) > 0");
                    part.params = vec![Value::Text(if clause.case_sensitive {
                        query.clone()
                    } else {
                        fold(query)
                    })];
                } else if clause.case_sensitive {
                    let fields = match target {
                        TextTarget::Any => "field IN ('body','name')",
                        TextTarget::Body => "field='body'",
                        TextTarget::Name => "field='name'",
                        TextTarget::Path => "field='path'",
                    };
                    part.sql = format!(
                        "SELECT rel_path, line FROM lines WHERE {fields} AND instr(text, ?) > 0"
                    );
                    part.params = vec![Value::Text(query.clone())];
                }
            }
            let cte = format!("s{}", ctes.len());
            ctes.push(format!("{cte} AS MATERIALIZED ({})", part.sql));
            params.extend(part.params);
            let condition = if request.line_mode && !part.broadcast_root {
                format!("(u.rel_path,u.line) IN (SELECT rel_path,line FROM {cte} WHERE line IS NOT NULL)")
            } else if request.line_mode {
                format!("((u.rel_path,u.line) IN (SELECT rel_path,line FROM {cte} WHERE line IS NOT NULL) OR u.rel_path IN (SELECT rel_path FROM {cte} WHERE line IS NULL))")
            } else {
                format!("u.rel_path IN (SELECT rel_path FROM {cte})")
            };
            parts.push(if part.generates {
                condition
            } else {
                format!("NOT ({condition})")
            });
        }
        let term = if clause.never {
            "0".to_string()
        } else if parts.is_empty() {
            "1".to_string()
        } else {
            parts.join(" AND ")
        };
        terms.push(if clause.negated {
            format!("NOT ({term})")
        } else {
            format!("({term})")
        });
    }
    let prefix = if ctes.is_empty() {
        String::new()
    } else {
        format!("WITH {} ", ctes.join(","))
    };
    let field = if request.line_mode { "body" } else { "name" };
    let condition = if terms.is_empty() {
        "1".to_string()
    } else {
        terms.join(" AND ")
    };
    (format!("{prefix}SELECT u.rel_path,u.line FROM lines u WHERE u.field='{field}' AND {condition} ORDER BY u.rel_path,u.line"), params)
}

fn encode_cursor(rank: &Rank, generation: &str, fingerprint: &str) -> String {
    serde_json::json!({"v":1,"generation":generation,"request":fingerprint,
        "after":[rank.priority,rank.value,rank.matches.0,rank.path,rank.line]})
    .to_string()
}

fn decode_cursor(
    cursor: Option<&str>,
    generation: &str,
    fingerprint: &str,
) -> Result<Option<Rank>> {
    let Some(cursor) = cursor else {
        return Ok(None);
    };
    let value: serde_json::Value =
        serde_json::from_str(cursor).map_err(|_| invalid("invalid search continuation"))?;
    if value["v"] != 1 || value["generation"] != generation || value["request"] != fingerprint {
        return Err(KernelError::SearchCursorExpired);
    }
    let a = &value["after"];
    Ok(Some(Rank {
        priority: a[0]
            .as_u64()
            .and_then(|v| u8::try_from(v).ok())
            .ok_or_else(|| invalid("invalid rank"))?,
        value: a[1]
            .as_str()
            .ok_or_else(|| invalid("invalid rank"))?
            .to_string(),
        matches: std::cmp::Reverse(a[2].as_u64().ok_or_else(|| invalid("invalid rank"))?),
        path: a[3]
            .as_str()
            .ok_or_else(|| invalid("invalid rank"))?
            .to_string(),
        line: a[4]
            .as_u64()
            .ok_or_else(|| invalid("invalid rank"))?,
    }))
}

fn invalid(detail: &str) -> KernelError {
    KernelError::SearchInvalid {
        detail: detail.to_string(),
    }
}

#[cfg(test)]
mod tests;

fn validate(request: &SearchRequest) -> Result<()> {
        if request.page_size == 0
            || request.page_size > MAX_PAGE_SIZE
            || request.max_lines > MAX_SNIPPET_LINES
            || request.clauses.len() > 64
            || request
                .clauses
                .iter()
                .map(|c| c.constraints.len())
                .sum::<usize>()
                > 128
        {
            return Err(invalid("search request exceeds its bounds"));
        }
        for constraint in request.clauses.iter().flat_map(|c| &c.constraints) {
            constraint.validate()?;
        }
    Ok(())
}

#[cfg(test)]
mod absolute_baseline;
