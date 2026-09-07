//! Complete search over shared Markdown item locations. Domain membership is
//! opaque facts; text comes from the existing indexed lines and source ranges.
use super::*;
#[cfg(test)]
use crate::index::scoped::{ItemMap, Map};
#[cfg(test)]
use gilune_document::ByteSpan;

#[cfg(test)]
#[path = "tests/item_profile.rs"]
mod profile;

#[cfg(test)]
#[path = "tests/records_prototype/mod.rs"]
mod records_prototype;

#[cfg(test)]
pub(super) fn search(db: &Connection, request: &SearchRequest, extension: Option<&dyn SearchExtension>, generation: String, fingerprint: String, after: Option<Rank>, issues: Vec<String>) -> Result<SearchPage> {
    execute(db, request, extension, generation, fingerprint, after, issues, Execution::Complete)
}

#[allow(clippy::too_many_arguments)]
pub(super) fn execute(db: &Connection, request: &SearchRequest, extension: Option<&dyn SearchExtension>, generation: String, fingerprint: String, after: Option<Rank>, mut issues: Vec<String>, execution: Execution) -> Result<SearchPage> {
    if target_query::supports(request) {
        let required = requirements(db, request, &mut issues)?;
        target_query::execute(db, request, &required, generation, fingerprint, after, issues, execution)
    } else {
        let required = requirements(db, request, &mut issues)?;
        super::target_scan::search(db, request, extension, &required, generation, fingerprint, after, issues)
    }
}

pub(super) fn requirements(db: &Connection, request: &SearchRequest, issues: &mut Vec<String>) -> Result<Vec<String>> {
    let mut required = request.required_namespaces.clone();
    required.push(index::FRONT_MATTER_NAMESPACE.into());
    required.sort();
    required.dedup();
    for ns in &required {
        if ns == index::TEXT_NAMESPACE {
            continue;
        }
        let mismatch: bool = db.query_row("SELECT EXISTS (SELECT 1 FROM source_maps s LEFT JOIN fact_sources r ON r.namespace=?1 AND r.rel_path=s.rel_path WHERE r.source_version IS NOT s.source_version) OR EXISTS (SELECT 1 FROM fact_sources r LEFT JOIN source_maps s ON s.rel_path=r.rel_path WHERE r.namespace=?1 AND r.source_version IS NOT s.source_version)", [ns], |r| r.get(0))?;
        if mismatch {
            issues.push(format!("{ns}: item source versions are not aligned"));
        }
    }
    Ok(required)
}

#[cfg(test)]
pub(super) fn legacy_search(
    db: &Connection, request: &SearchRequest, extension: Option<&dyn SearchExtension>,
    generation: String, fingerprint: String, after: Option<Rank>, mut issues: Vec<String>,
) -> Result<SearchPage> {
    let required = requirements(db, request, &mut issues)?;
    let (sql, params) = sql(request, &required);
    let mut stmt = db.prepare(&sql)?;
    let mut rows = stmt.query(params_from_iter(params))?;
    let mut current_path = String::new();
    let mut selected: Vec<(u64, Vec<bool>)> = Vec::new();
    let mut heap = BinaryHeap::new();
    let mut total = 0;
    let mut remaining = 0;
    let mut accept = |hit: SearchHit, anchor: u64, sort: Option<String>| {
        let rank = Rank {
            priority: if request.sort_predicate.is_empty() {
                u8::from(!hit.name_matched)
            } else {
                u8::from(sort.is_none())
            },
            value: sort.unwrap_or_default(),
            matches: std::cmp::Reverse(if request.sort_predicate.is_empty() {
                hit.match_count
            } else {
                0
            }),
            path: hit.rel_path.clone(),
            line: anchor,
        };
        total += 1;
        if after.as_ref().is_some_and(|a| rank <= *a) {
            return;
        }
        remaining += 1;
        heap.push(Ranked { rank, hit });
        if heap.len() > request.page_size as usize {
            heap.pop();
        }
    };
    let mut consume = |path: &str, selected: &[(u64, Vec<bool>)]| -> Result<()> {
        if selected.is_empty() {
            return Ok(());
        }
        let (version, raw): (String, String) = db.query_row(
            "SELECT source_version,layout FROM source_maps WHERE rel_path=?1",
            [path],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        let map: Map = serde_json::from_str(&raw)?;
        let doc = document(db, path, true, true)?;
        let lines: Vec<_> = doc.body.split('\n').collect();
        let sorted_facts = if request.sort_predicate.is_empty() {
            BTreeMap::new()
        } else {
            facts(db, path, &version)?
        };
        let mut grouped = SearchHit {
            rel_path: path.into(),
            name: doc.name.clone(),
            facts: Vec::new(),
            lines: Vec::new(),
            items: Vec::new(),
            name_matched: request
                .name_needles
                .iter()
                .any(|n| !n.is_empty() && fold(&doc.name).contains(&fold(n))),
            more_matches: 0,
            match_count: 0,
        };
        let mut group_sort: Option<String> = None;
        let mut count = 0u64;
        for (key, checks) in selected {
            let item = &map.items[map
                .items
                .binary_search_by_key(key, |i| i.key)
                .map_err(|_| invalid("indexed item is absent from its source map"))?];
            let (first_line, text) = item_text(item, &map, &lines)?;
            if !matches(request, extension, &doc, &text, checks)? {
                continue;
            }
            let highlights: Vec<_> = ranges(&text, &request.body_needles)
                .into_iter()
                .map(|(start, end)| SearchRange { start, end })
                .collect();
            let match_count = highlights.len() as u64;
            let sort = sorted_facts
                .get(&Some(*key))
                .into_iter()
                .flatten()
                .filter(|f| f.key == request.sort_predicate)
                .map(|f| f.value.clone())
                .min();
            count += 1;
            let target = || SearchItem {
                key: *key,
                source_version: version.clone(),
                map_version: map.version,
                range: ByteSpan {
                    start: item.key,
                    end: item.end,
                },
                own_ranges: item
                    .own
                    .iter()
                    .map(|(start, end)| ByteSpan {
                        start: *start,
                        end: *end,
                    })
                    .collect(),
                first_line,
                text,
                ranges: highlights,
                facts: Vec::new(),
            };
            if request.group_by_note {
                grouped.match_count += match_count;
                if let Some(value) = sort {
                    if group_sort.as_ref().is_none_or(|old| value < *old) {
                        group_sort = Some(value);
                    }
                }
                if grouped.items.len() < request.max_lines as usize {
                    grouped.items.push(target());
                } else {
                    grouped.more_matches += 1;
                }
            } else {
                let mut hit = grouped.clone();
                hit.match_count = match_count;
                if request.max_lines > 0 {
                    hit.items.push(target());
                } else {
                    hit.more_matches = 1;
                }
                accept(hit, *key, sort);
            }
        }
        if request.group_by_note && count > 0 {
            accept(grouped, 0, group_sort);
        }
        Ok(())
    };
    while let Some(row) = rows.next()? {
        let path: String = row.get(0)?;
        if path != current_path {
            consume(&current_path, &selected)?;
            selected.clear();
            current_path = path;
        }
        selected.push((
            row.get(1)?,
            (0..request.clauses.len())
                .map(|i| row.get(i + 2))
                .collect::<rusqlite::Result<_>>()?,
        ));
    }
    consume(&current_path, &selected)?;
    let mut ranked = heap.into_sorted_vec();
    let mut by_path: BTreeMap<String, Vec<usize>> = BTreeMap::new();
    for (i, r) in ranked.iter().enumerate() {
        by_path.entry(r.hit.rel_path.clone()).or_default().push(i);
    }
    for (path, indices) in by_path {
        let version: String = db.query_row(
            "SELECT source_version FROM source_maps WHERE rel_path=?1",
            [&path],
            |r| r.get(0),
        )?;
        let facts = facts(db, &path, &version)?;
        for i in indices {
            let hit = &mut ranked[i].hit;
            hit.facts = facts.get(&None).cloned().unwrap_or_default();
            for item in &mut hit.items {
                item.facts = facts.get(&Some(item.key)).cloned().unwrap_or_default();
            }
        }
    }
    let next_cursor = if remaining > ranked.len() as u64 {
        ranked
            .last()
            .map(|r| encode_cursor(&r.rank, &generation, &fingerprint))
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

#[cfg(test)]
fn facts(
    db: &Connection,
    path: &str,
    version: &str,
) -> Result<BTreeMap<Option<u64>, Vec<SearchProperty>>> {
    let mut statement=db.prepare_cached("SELECT item_key,predicate,value FROM facts WHERE rel_path=?1 AND source_version=?2 AND line IS NULL ORDER BY rowid")?;
    let mut facts: BTreeMap<Option<u64>, Vec<SearchProperty>> = BTreeMap::new();
    for row in statement.query_map(rusqlite::params![path, version], |r| {
        Ok((
            r.get::<_, Option<u64>>(0)?,
            SearchProperty {
                key: r.get(1)?,
                value: r.get(2)?,
            },
        ))
    })? {
        let (key, value) = row?;
        facts.entry(key).or_default().push(value);
    }
    Ok(facts)
}

/// Reconstruct only an item's own bytes from indexed physical lines. The map
/// retains original UTF-8/CRLF coordinates; displayed text uses normalized LF.
#[cfg(test)]
fn item_text(item: &ItemMap, map: &Map, lines: &[&str]) -> Result<(u32, String)> {
    let first = map
        .lines
        .partition_point(|l| l.1 <= item.key)
        .checked_sub(1)
        .ok_or_else(|| invalid("item has no source line"))?;
    let mut text = String::new();
    for &(start, end) in &item.own {
        let mut line = map
            .lines
            .partition_point(|l| l.1 <= start)
            .saturating_sub(1);
        if !text.is_empty() && !text.ends_with('\n') {
            text.push('\n');
        }
        while let Some(&(number, line_start, line_end)) = map.lines.get(line) {
            if line_start >= end {
                break;
            }
            let source = lines
                .get(number as usize - 1)
                .ok_or_else(|| invalid("source map and indexed lines disagree"))?;
            let lo = if start == item.key && line == first {
                0
            } else {
                start.saturating_sub(line_start).min(source.len() as u64) as usize
            };
            let hi = end.saturating_sub(line_start).min(source.len() as u64) as usize;
            if hi > lo {
                text.push_str(
                    source
                        .get(lo..hi)
                        .ok_or_else(|| invalid("invalid item UTF-8 range"))?,
                );
            }
            if end > line_start + source.len() as u64 && line_end > line_start + source.len() as u64
            {
                text.push('\n');
            }
            line += 1;
        }
    }
    while let Some(newline) = text.rfind('\n') {
        if text[newline + 1..].trim().is_empty() {
            text.truncate(newline);
        } else {
            break;
        }
    }
    Ok((map.lines[first].0, text))
}

pub(super) fn matches(
    request: &SearchRequest,
    extension: Option<&dyn SearchExtension>,
    doc: &SearchDocument,
    body: &str,
    checks: &[bool],
) -> Result<bool> {
    for (clause, facts) in request.clauses.iter().zip(checks) {
        let matched = if clause.never {
            false
        } else if let Some(key) = clause.residual {
            extension
                .ok_or_else(|| invalid("query requires an unavailable plugin predicate"))?
                .matches(
                    key,
                    &SearchDocument {
                        body: body.into(),
                        rel_path: doc.rel_path.clone(),
                        name: doc.name.clone(),
                        properties: doc.properties.clone(),
                    },
                )
                .map_err(|e| invalid(&e))?
        } else {
            *facts
                && clause.constraints.iter().all(|c| match c {
                    Constraint::Text { target, query } => {
                        let contains = |text: &str| {
                            if clause.case_sensitive {
                                text.contains(query)
                            } else {
                                fold(text).contains(&fold(query))
                            }
                        };
                        match target {
                            TextTarget::Any => contains(body) || contains(&doc.name),
                            TextTarget::Body => contains(body),
                            TextTarget::Name => contains(&doc.name),
                            TextTarget::Path => contains(&doc.rel_path),
                        }
                    }
                    _ => true,
                })
        };
        if matched == clause.negated {
            return Ok(false);
        }
    }
    Ok(true)
}

#[cfg(test)]
fn sql(request: &SearchRequest, required: &[String]) -> (String, Vec<Value>) {
    let mut ctes = Vec::new();
    let mut seed = None;
    let mut params = Vec::new();
    let mut conditions = Vec::new();
    let mut checks = Vec::new();
    let mut next = 0;
    for clause in &request.clauses {
        let mut parts = Vec::new();
        if clause.residual.is_none() {
            for constraint in &clause.constraints {
                if matches!(constraint, Constraint::Text { .. }) {
                    continue;
                }
                let part = index::scoped_piece(constraint);
                let id = next;
                next += 1;
                ctes.push(format!("c{id} AS MATERIALIZED ({})", part.sql));
                params.extend(part.params);
                if seed.is_none()
                    && !clause.negated
                    && !clause.never
                    && part.generates
                    && !part.broadcast_root
                {
                    seed = Some(id);
                    continue; // This exact positive already defines the candidate set.
                }
                let mut matching=format!("(u.rel_path,u.source_version,u.item_key) IN (SELECT rel_path,source_version,item_key FROM c{id} WHERE item_key IS NOT NULL)");
                if part.broadcast_root {
                    matching.push_str(&format!(" OR (u.rel_path,u.source_version) IN (SELECT rel_path,source_version FROM c{id} WHERE item_key IS NULL AND line IS NULL)"));
                }
                parts.push(format!(
                    "{}({matching})",
                    if part.generates { "" } else { "NOT " }
                ));
            }
        }
        let fact = if parts.is_empty() {
            "1".into()
        } else {
            parts.join(" AND ")
        };
        let text = clause
            .constraints
            .iter()
            .any(|c| matches!(c, Constraint::Text { .. }));
        // Do not evaluate each fact set twice, once to select a candidate and
        // again to return an already-known truth value. Only a negated mixed
        // fact/text clause needs its fact result evaluated alongside item text.
        checks.push(if clause.negated && text {
            format!("({fact})")
        } else if clause.negated && clause.residual.is_none() {
            "0".into()
        } else {
            "1".into()
        });
        if clause.never {
            conditions.push(if clause.negated { "1" } else { "0" }.into());
        } else if clause.residual.is_none() {
            let text = clause
                .constraints
                .iter()
                .any(|c| matches!(c, Constraint::Text { .. }));
            if !clause.negated {
                conditions.push(format!("({fact})"));
            } else if !text {
                conditions.push(format!("NOT ({fact})"));
            }
        }
    }
    ctes.push(match seed {
        Some(id)=>format!("items AS (SELECT DISTINCT rel_path,source_version,item_key FROM c{id} WHERE item_key IS NOT NULL)"),
        None=>"items AS (SELECT s.rel_path,s.source_version,json_extract(j.value,'$.key') AS item_key FROM source_maps s,json_each(s.layout,'$.items') j)".into(),
    });
    for ns in required {
        if ns == index::TEXT_NAMESPACE {
            continue;
        }
        conditions.push("EXISTS (SELECT 1 FROM fact_sources r WHERE r.namespace=? AND r.rel_path=u.rel_path AND r.source_version=u.source_version)".into());
        params.push(Value::Text(ns.clone()));
    }
    if conditions.is_empty() {
        conditions.push("1".into());
    }
    let columns = if checks.is_empty() {
        String::new()
    } else {
        format!(",{}", checks.join(","))
    };
    (format!("WITH {} SELECT u.rel_path,u.item_key{columns} FROM items u JOIN source_maps s ON s.rel_path=u.rel_path AND s.source_version=u.source_version WHERE {} ORDER BY u.rel_path,u.item_key",ctes.join(","),conditions.join(" AND ")),params)
}
