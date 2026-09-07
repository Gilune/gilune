//! Reconstruct returned item snippets from indexed line ranges, never whole maps.
use super::*;
use gilune_document::ByteSpan;

#[cfg(test)]
thread_local! {
    pub(super) static WORK: std::cell::Cell<(usize,usize)> = const { std::cell::Cell::new((0,0)) };
}

pub(super) fn facts(db: &Connection, target: i64) -> Result<Vec<SearchProperty>> {
    Ok(db
        .prepare_cached("SELECT p.predicate,f.value FROM fact_values f JOIN index_predicates p ON p.id=f.predicate_id WHERE f.target_id=?1 ORDER BY f.id")?
        .query_map([target], |r| {
            Ok(SearchProperty {
                key: r.get(0)?,
                value: r.get(1)?,
            })
        })?
        .collect::<rusqlite::Result<_>>()?)
}

pub(super) fn hit(db: &Connection, source: i64, path: String) -> Result<SearchHit> {
    #[cfg(test)]
    if position_prototype::enabled() {
        return position_prototype::hit(db, source, path);
    }
    let root = db.query_row(
        "SELECT id FROM index_targets WHERE source_id=?1 AND kind=0 AND local_key=0",
        [source],
        |r| r.get(0),
    )?;
    let name = db.query_row(
        "SELECT text FROM lines WHERE rel_path=?1 AND field='name'",
        [&path],
        |r| r.get(0),
    )?;
    Ok(SearchHit {
        facts: facts(db, root)?,
        rel_path: path,
        name,
        name_matched: false,
        lines: vec![],
        items: vec![],
        more_matches: 0,
        match_count: 0,
    })
}

pub(super) fn item(db: &Connection, target: i64) -> Result<SearchItem> {
    item_content(db, target, true)
}

pub(super) fn item_content(db: &Connection, target: i64, with_facts: bool) -> Result<SearchItem> {
    #[cfg(test)]
    if position_prototype::enabled() {
        return position_prototype::item(db, target, with_facts);
    }
    #[cfg(test)]
    WORK.with(|w| {
        let (items, lines) = w.get();
        w.set((items + 1, lines));
    });
    let (source,path,version,map_version,key,end,own): (i64,String,String,u32,u64,u64,String) = db.query_row(
        "SELECT s.id,s.rel_path,s.source_version,t.map_version,t.start_byte,t.end_byte,t.own_ranges FROM index_targets t JOIN source_maps s ON s.id=t.source_id WHERE t.id=?1 AND t.kind=2",
        [target], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?,r.get(6)?)))?;
    let own: Vec<(u64, u64)> = serde_json::from_str(&own)?;
    let first_line: u32 = db.query_row("SELECT local_key FROM index_targets WHERE source_id=?1 AND kind=1 AND start_byte<=?2 ORDER BY start_byte DESC LIMIT 1", rusqlite::params![source,key], |r| r.get(0))?;
    let mut text = String::new();
    for &(start, finish) in &own {
        let line_start: u64 = db.query_row("SELECT start_byte FROM index_targets WHERE source_id=?1 AND kind=1 AND start_byte<=?2 ORDER BY start_byte DESC LIMIT 1", rusqlite::params![source,start], |r| r.get(0))?;
        if !text.is_empty() && !text.ends_with('\n') {
            text.push('\n');
        }
        let mut stmt = db.prepare_cached("SELECT t.local_key,t.start_byte,t.end_byte,l.text FROM index_targets t LEFT JOIN lines l ON l.rel_path=?2 AND l.field='body' AND l.line=t.local_key WHERE t.source_id=?1 AND t.kind=1 AND t.start_byte>=?3 AND t.start_byte<?4 ORDER BY t.start_byte")?;
        let mut rows = stmt.query(rusqlite::params![source, path, line_start, finish])?;
        while let Some(row) = rows.next()? {
            #[cfg(test)]
            WORK.with(|w| {
                let (items, lines) = w.get();
                w.set((items, lines + 1));
            });
            let (number, lo, end, body): (u32, u64, u64, String) =
                (row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?);
            let a = if start == key && number == first_line {
                0
            } else {
                start.saturating_sub(lo).min(body.len() as u64) as usize
            };
            let b = finish.saturating_sub(lo).min(body.len() as u64) as usize;
            if b > a {
                text.push_str(
                    body.get(a..b)
                        .ok_or_else(|| invalid("invalid item UTF-8 range"))?,
                );
            }
            if finish > lo + body.len() as u64 && end > lo + body.len() as u64 {
                text.push('\n');
            }
        }
    }
    while let Some(newline) = text.rfind('\n') {
        if text[newline + 1..].trim().is_empty() {
            text.truncate(newline);
        } else {
            break;
        }
    }
    Ok(SearchItem {
        key,
        source_version: version,
        map_version,
        range: ByteSpan { start: key, end },
        own_ranges: own
            .into_iter()
            .map(|(start, end)| ByteSpan { start, end })
            .collect(),
        first_line,
        text,
        ranges: vec![],
        facts: if with_facts {
            facts(db, target)?
        } else {
            vec![]
        },
    })
}
