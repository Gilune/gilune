//! Bounded projection for declarative document/physical-line APIs. Their legacy
//! anchor semantics are preserved over the same compact fact store.
use super::*;
use rusqlite::OptionalExtension;

pub(super) fn supports(q: &SearchRequest) -> bool {
    q.body_needles.is_empty()
        && q.name_needles.is_empty()
        && !q.extension_candidates
        && q.clauses.iter().all(|c| c.residual.is_none())
}

#[allow(clippy::too_many_arguments)]
pub(super) fn search(
    db: &Connection,
    q: &SearchRequest,
    candidates: &str,
    mut args: Vec<Value>,
    generation: String,
    fingerprint: String,
    after: Option<Rank>,
    issues: Vec<String>,
    execution: Execution,
) -> Result<SearchPage> {
    let candidate_args = args.clone();
    let grouped = !q.line_mode || q.group_by_note;
    let selected = if grouped {
        "SELECT rel_path,0 AS line,COUNT(*) AS n FROM matched GROUP BY rel_path"
    } else {
        "SELECT rel_path,line,1 AS n FROM matched"
    };
    let base = format!("WITH matched AS ({candidates}), selected AS ({selected})");
    let total = if execution == Execution::Page {
        0
    } else {
        db.query_row(
            &format!("{base} SELECT COUNT(*) FROM selected"),
            params_from_iter(&args),
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
    let sort = if sorted {
        args.push(Value::Text(q.sort_predicate.clone()));
        "(SELECT MIN(value) FROM facts f WHERE f.rel_path=u.rel_path AND f.item_key IS NULL AND (f.line IS NULL OR f.line=u.line) AND f.predicate=?)"
    } else {
        "NULL"
    };
    let priority = if sorted { "(sort_value IS NULL)" } else { "1" };
    let continuation = if let Some(a) = after {
        args.extend([
            Value::Integer(a.priority.into()),
            Value::Text(a.value),
            Value::Text(a.path),
            Value::Integer(a.line as i64),
        ]);
        "(priority,value,rel_path,line)>(?,?,?,?)"
    } else {
        "1"
    };
    args.push(Value::Integer(i64::from(q.page_size) + 1));
    let order = if sorted {
        "priority,value,rel_path,line"
    } else {
        "rel_path,line"
    };
    let sql = format!("{base}, valued AS (SELECT u.*,{sort} AS sort_value FROM selected u), ranked AS (SELECT *,{priority} AS priority,COALESCE(sort_value,'') AS value FROM valued) SELECT rel_path,line,n,priority,value FROM ranked WHERE {continuation} ORDER BY {order} LIMIT ?");
    let mut selected = db
        .prepare(&sql)?
        .query_map(params_from_iter(args), |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, u32>(1)?,
                r.get::<_, u64>(2)?,
                r.get::<_, u8>(3)?,
                r.get::<_, String>(4)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let more = selected.len() > q.page_size as usize;
    selected.truncate(q.page_size as usize);
    let next_cursor = if more {
        selected.last().map(|(path, line, _, priority, value)| {
            encode_cursor(
                &Rank {
                    priority: *priority,
                    value: value.clone(),
                    matches: std::cmp::Reverse(0),
                    path: path.clone(),
                    line: u64::from(*line),
                },
                &generation,
                &fingerprint,
            )
        })
    } else {
        None
    };
    let mut hits = Vec::new();
    for (path, line, n, _, _) in selected {
        let doc = document(db, &path, false, false)?;
        let source: Option<i64> = db
            .query_row(
                "SELECT id FROM source_maps WHERE rel_path=?1",
                [&path],
                |r| r.get(0),
            )
            .optional()?;
        let properties = |number: Option<u32>, inherited: bool| -> Result<Vec<SearchProperty>> {
            let mut targets = vec![];
            if let Some(source) = source {
                if let Some(id) = index::positions::target(
                    db,
                    source,
                    if number.is_some() { 1 } else { 0 },
                    i64::from(number.unwrap_or(0)),
                )? {
                    targets.push(id);
                }
                if inherited && number.is_some() {
                    if let Some(id) = index::positions::target(db, source, 0, 0)? {
                        targets.push(id);
                    }
                }
            }
            let ids = serde_json::to_string(&targets)?;
            Ok(db.prepare_cached("SELECT p.predicate,f.value FROM fact_values f JOIN index_predicates p ON p.id=f.predicate_id WHERE f.location_id IN (SELECT l.id FROM fact_frames r CROSS JOIN fact_location_slots l INDEXED BY fact_location_slots_literal ON l.frame_id=r.id WHERE r.rel_path=?1 AND l.target_id IS NULL AND l.item_key IS NULL AND (l.line IS ?2 OR (?3 AND l.line IS NULL)) UNION ALL SELECT l.id FROM fact_location_slots l JOIN fact_frames r ON r.id=l.frame_id WHERE r.rel_path=?1 AND l.target_id IN (SELECT value FROM json_each(?4)) AND l.item_key IS NULL) ORDER BY f.id")?
                .query_map(rusqlite::params![path,number,inherited,ids], |r| Ok(SearchProperty {key:r.get(0)?,value:r.get(1)?}))?.collect::<rusqlite::Result<_>>()?)
        };
        let roots = properties(None, false)?;
        let numbers: Vec<u32> = if !q.line_mode || q.max_lines == 0 {
            vec![]
        } else if !grouped {
            vec![line]
        } else {
            let mut parameters = candidate_args.clone();
            parameters.extend([
                Value::Text(path.clone()),
                Value::Integer(q.max_lines.into()),
            ]);
            db.prepare(&format!("WITH matched AS ({candidates}) SELECT line FROM matched WHERE rel_path=? ORDER BY line LIMIT ?"))?
                .query_map(params_from_iter(parameters), |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?
        };
        let mut lines = Vec::new();
        for number in numbers {
            let target = if let Some(source) = source {
                index::positions::target(db, source, 1, i64::from(number))?
            } else {
                None
            };
            let text = if let Some(target) = target {
                db.query_row(
                    "SELECT text FROM text_rows WHERE target_id=?1",
                    [target],
                    |r| r.get(0),
                )?
            } else {
                db.query_row("SELECT text FROM text_rows WHERE rel_path=?1 AND field='body' AND target_id IS NULL AND line=?2",rusqlite::params![path,number],|r|r.get(0))?
            };
            let line_facts = if sorted {
                properties(Some(number), false)?
                    .into_iter()
                    .chain(roots.clone())
                    .collect()
            } else {
                properties(Some(number), true)?
            };
            lines.push(SearchLine {
                number,
                text,
                ranges: vec![],
                facts: line_facts,
            });
        }
        let more_matches = if q.line_mode {
            n - lines.len() as u64
        } else {
            0
        };
        hits.push(SearchHit {
            rel_path: path,
            name: doc.name,
            facts: roots,
            name_matched: false,
            lines,
            items: vec![],
            more_matches,
            match_count: 0,
        });
    }
    Ok(SearchPage {
        hits,
        total_hits: total,
        next_cursor,
        index_complete: issues.is_empty(),
        issues,
        generation,
    })
}
