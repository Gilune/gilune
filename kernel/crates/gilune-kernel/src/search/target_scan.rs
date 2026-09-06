//! Text/relevance and opaque residual evaluation over compact targets. SQL narrows
//! facts first; callbacks inspect only indexed item content. A bounded heap retains
//! the page when relevance or a callback requires examining every candidate.
use super::*;
#[cfg(test)]
thread_local! { pub(super) static TIMINGS: std::cell::Cell<[f64;3]> = const { std::cell::Cell::new([0.0;3]) }; }

#[allow(clippy::too_many_arguments)]
pub(super) fn search(
    db: &Connection,
    q: &SearchRequest,
    extension: Option<&dyn SearchExtension>,
    required: &[String],
    generation: String,
    fingerprint: String,
    after: Option<Rank>,
    issues: Vec<String>,
) -> Result<SearchPage> {
    let mut prefilter = q.clone();
    prefilter.clauses.retain(|c| {
        c.residual.is_none()
            && !(c.negated
                && c.constraints
                    .iter()
                    .any(|f| matches!(f, Constraint::Text { .. })))
    });
    for c in &mut prefilter.clauses {
        c.constraints
            .retain(|f| !matches!(f, Constraint::Text { .. }));
    }
    let mut filter = target_query::compile(db, &prefilter, required)?;
    let mut args = filter.params;
    for clause in q
        .clauses
        .iter()
        .filter(|c| !c.negated && !c.never && c.residual.is_none())
    {
        for constraint in &clause.constraints {
            if let Constraint::Text { query, .. } = constraint {
                if query.is_empty() || query.contains('\n') {
                    continue;
                }
                // A conservative document prefilter: own item text must still
                // match, including nested boundaries and case-sensitive clauses.
                let piece = index::piece(constraint);
                let sql = target_query::bind(&piece.sql, piece.params, &mut args);
                filter.condition.push_str(&format!(
                    " AND s.rel_path IN (SELECT rel_path FROM ({sql}))"
                ));
            }
        }
    }
    let mut checks = Vec::new();
    for clause in &q.clauses {
        // Only a negated mixed fact/text clause needs an independent truth value.
        if clause.negated
            && clause.residual.is_none()
            && clause
                .constraints
                .iter()
                .any(|f| matches!(f, Constraint::Text { .. }))
        {
            let mut one = q.clone();
            let mut c = clause.clone();
            c.negated = false;
            c.constraints
                .retain(|f| !matches!(f, Constraint::Text { .. }));
            one.clauses = vec![c];
            let part = target_query::compile(db, &one, &[])?;
            let offset = args.len();
            // Shift numbered placeholders without touching bound user values.
            let mut condition = part.condition;
            for i in (1..=part.params.len()).rev() {
                condition = condition.replace(&format!("?{i}"), &format!("@p{}", offset + i));
            }
            checks.push(format!("({})", condition.replace("@p", "?")));
            args.extend(part.params);
        } else {
            checks.push(
                if clause.negated && clause.residual.is_none() {
                    "0"
                } else {
                    "1"
                }
                .into(),
            );
        }
    }
    let extra = if checks.is_empty() {
        String::new()
    } else {
        format!(",{}", checks.join(","))
    };
    let sql = format!("SELECT t.id,s.rel_path,s.id,s.source_version,t.map_version,t.local_key,t.end_byte,t.own_ranges{extra} FROM {} WHERE {} ORDER BY s.rel_path,t.local_key", target_query::FROM, filter.condition);
    let mut statement = db.prepare(&sql)?;
    let mut rows = statement.query(params_from_iter(args))?;
    let mut heap = BinaryHeap::new();
    let mut total = 0u64;
    let mut remaining = 0u64;
    let mut accept = |hit: SearchHit, anchor: u64, sort: Option<String>| {
        let rank = Rank {
            priority: if q.sort_predicate.is_empty() {
                u8::from(!hit.name_matched)
            } else {
                u8::from(sort.is_none())
            },
            value: sort.unwrap_or_default(),
            matches: std::cmp::Reverse(if q.sort_predicate.is_empty() {
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
        if heap.len() > q.page_size as usize {
            heap.pop();
        }
    };
    let mut doc: Option<SearchDocument> = None;
    let mut group: Option<SearchHit> = None;
    let mut group_sort: Option<String> = None;
    let mut group_count = 0u64;
    #[cfg(test)]
    let (started, mut sql_ms, mut text_ms) = (std::time::Instant::now(), 0.0, 0.0);
    let mut window = super::text_window::TextWindow::default();
    loop {
        #[cfg(test)]
        let fetch_started = std::time::Instant::now();
        let row = rows.next()?;
        #[cfg(test)]
        { sql_ms += fetch_started.elapsed().as_secs_f64()*1000.0; }
        let Some(row) = row else { break };
        let id: i64 = row.get(0)?;
        let path: String = row.get(1)?;
        if doc.as_ref().is_none_or(|d| d.rel_path != path) {
            if group_count > 0 && q.group_by_note {
                accept(group.take().unwrap(), 0, group_sort.take());
            }
            doc = Some(document(
                db,
                &path,
                q.clauses.iter().any(|c| c.residual.is_some()),
                false,
            )?);
            let current = doc.as_ref().unwrap();
            group = Some(SearchHit {
                facts: vec![],
                rel_path: path.clone(),
                name: current.name.clone(),
                name_matched: q
                    .name_needles
                    .iter()
                    .any(|n| !n.is_empty() && fold(&current.name).contains(&fold(n))),
                lines: vec![],
                items: vec![],
                more_matches: 0,
                match_count: 0,
            });
            group_count = 0;
            group_sort = None;
        }
        let checks: Vec<bool> = (0..q.clauses.len())
            .map(|i| row.get(i + 8))
            .collect::<rusqlite::Result<_>>()?;
        #[cfg(test)]
        let text_started = std::time::Instant::now();
        let own: String = row.get(7)?;
        let mut item = window.item(
            db,
            row.get(2)?,
            &path,
            row.get(3)?,
            row.get(4)?,
            row.get(5)?,
            row.get(6)?,
            serde_json::from_str(&own)?,
        )?;
        #[cfg(test)]
        { text_ms += text_started.elapsed().as_secs_f64()*1000.0; }
        if !items::matches(q, extension, doc.as_ref().unwrap(), &item.text, &checks)? {
            continue;
        }
        item.ranges = ranges(&item.text, &q.body_needles)
            .into_iter()
            .map(|(start, end)| SearchRange { start, end })
            .collect();
        let count = item.ranges.len() as u64;
        let sort: Option<String> = if q.sort_predicate.is_empty() {
            None
        } else {
            db.query_row("SELECT MIN(value) FROM fact_values WHERE target_id=?1 AND predicate_id IN (SELECT id FROM index_predicates WHERE predicate=?2)", rusqlite::params![id,q.sort_predicate], |r| r.get(0))?
        };
        if q.group_by_note {
            group_count += 1;
            let hit = group.as_mut().unwrap();
            hit.match_count += count;
            if let Some(value) = sort {
                if group_sort.as_ref().is_none_or(|old| value < *old) {
                    group_sort = Some(value);
                }
            }
            if hit.items.len() < q.max_lines as usize {
                hit.items.push(item);
            } else {
                hit.more_matches += 1;
            }
        } else {
            let mut hit = group.as_ref().unwrap().clone();
            hit.match_count = count;
            let key = item.key;
            if q.max_lines > 0 {
                hit.items.push(item);
            } else {
                hit.more_matches = 1;
            }
            accept(hit, key, sort);
        }
    }
    if group_count > 0 && q.group_by_note {
        accept(group.unwrap(), 0, group_sort);
    }
    #[cfg(test)]
    TIMINGS.with(|t|t.set([sql_ms,text_ms,started.elapsed().as_secs_f64()*1000.0-sql_ms-text_ms]));
    let mut ranked = heap.into_sorted_vec();
    for row in &mut ranked {
        let source: i64 = db.query_row(
            "SELECT id FROM source_maps WHERE rel_path=?1",
            [&row.hit.rel_path],
            |r| r.get(0),
        )?;
        row.hit.facts = target_projection::hit(db, source, row.hit.rel_path.clone())?.facts;
        for item in &mut row.hit.items {
            let id: i64 = db.query_row(
                "SELECT id FROM index_targets WHERE source_id=?1 AND kind=2 AND local_key=?2",
                rusqlite::params![source, item.key],
                |r| r.get(0),
            )?;
            item.facts = target_projection::facts(db, id)?;
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
