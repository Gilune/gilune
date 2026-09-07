//! Reconstruct returned item snippets from indexed line ranges, never whole maps.
use super::*;

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
        "SELECT id FROM target_slots WHERE source_id=?1 AND kind=0 AND local_key=0",
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
    let (source,path,version,map_version,key,end,own,base): (i64,String,String,u32,u64,u64,String,u64) = db.query_row(
        "SELECT s.id,s.rel_path,s.source_version,t.map_version,t.start_byte+b.base_byte,t.end_byte+b.base_byte,t.own_ranges,b.base_byte FROM target_slots t JOIN position_blocks b ON b.id=t.block_id JOIN source_maps s ON s.id=t.source_id WHERE t.id=?1 AND t.kind=2",
        [target], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?,r.get(6)?,r.get(7)?)))?;
    let own: Vec<(u64, u64)> = serde_json::from_str(&own)?;
    let own = own
        .into_iter()
        .map(|(a, b)| {
            Ok((
                a.checked_add(base)
                    .ok_or_else(|| invalid("position overflow"))?,
                b.checked_add(base)
                    .ok_or_else(|| invalid("position overflow"))?,
            ))
        })
        .collect::<Result<Vec<_>>>()?;
    let mut item = super::text_window::TextWindow::at(db, source, key)?.item(
        db,
        source,
        &path,
        version,
        map_version,
        key,
        end,
        own,
    )?;
    if with_facts {
        item.facts = facts(db, target)?;
    }
    Ok(item)
}
