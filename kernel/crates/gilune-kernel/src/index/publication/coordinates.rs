//! Reuse private coordinate slots during an atomic, fully prepared publication.
//! These slots have no identity outside their source revision. Complete fresh
//! producer output is still compared before commit; no old meaning is inferred.
use super::*;

struct Target {
    id: i64,
    block: i64,
    base_key: i64,
    base_byte: i64,
    order: i64,
    kind: i64,
    key: i64,
    map: i64,
    start: i64,
    end: i64,
    own: String,
}

pub(super) fn advance(
    db: &Connection,
    path: &str,
    old: &str,
    version: &str,
    layout: &str,
    lines: &[LineRow],
) -> Result<bool> {
    let _profile = crate::update_profile::span("advance_coordinate_slots", "");
    let previous_layout: String = db.query_row(
        "SELECT layout FROM source_maps WHERE rel_path=?1",
        [path],
        |r| r.get(0),
    )?;
    if previous_layout == layout {
        db.prepare_cached(
            "UPDATE fact_frames SET source_version=?1 WHERE rel_path=?2 AND source_version=?3",
        )?
        .execute(params![version, path, old])?;
        db.execute("INSERT OR REPLACE INTO prepared_geometry(source_id,layout) SELECT id,?2 FROM source_maps WHERE rel_path=?1", params![path,layout])?;
        return Ok(true);
    }
    // Project the proposed layout without publishing a temporary source map.
    let sql = "SELECT 0,0,json_extract(?1,'$.version'),0,json_extract(?1,'$.length'),json_array(json_array(0,json_extract(?1,'$.length'))) UNION ALL SELECT 1,json_extract(value,'$[0]'),json_extract(?1,'$.version'),json_extract(value,'$[1]'),json_extract(value,'$[2]'),json_array(json_array(json_extract(value,'$[1]'),json_extract(value,'$[2]'))) FROM json_each(?1,'$.lines') UNION ALL SELECT 2,json_extract(value,'$.key'),json_extract(?1,'$.version'),json_extract(value,'$.key'),json_extract(value,'$.end'),json_extract(value,'$.own') FROM json_each(?1,'$.items') ORDER BY 1,2";
    let next: Vec<Target> = db
        .prepare_cached(sql)?
        .query_map([layout], |r| {
            Ok(Target {
                id: 0,
                block: 0,
                base_key: 0,
                base_byte: 0,
                order: 0,
                kind: r.get(0)?,
                key: r.get(1)?,
                map: r.get(2)?,
                start: r.get(3)?,
                end: r.get(4)?,
                own: r.get(5)?,
            })
        })?
        .collect::<rusqlite::Result<_>>()?;
    let previous: Vec<Target> = db.prepare_cached("SELECT t.id,t.kind,t.local_key,t.map_version,t.start_byte,t.end_byte,t.own_ranges,x.block_id,x.local_key,b.base_key,b.base_byte FROM index_targets t JOIN target_slots x ON x.id=t.id JOIN position_blocks b ON b.id=x.block_id JOIN source_maps s ON s.id=t.source_id WHERE s.rel_path=?1 ORDER BY t.kind,t.local_key")?.query_map([path], |r| Ok(Target { id:r.get(0)?, kind:r.get(1)?, key:r.get(2)?, map:r.get(3)?, start:r.get(4)?, end:r.get(5)?, own:r.get(6)?, block:r.get(7)?, order:r.get(8)?, base_key:r.get(9)?,base_byte:r.get(10)? }))?.collect::<rusqlite::Result<_>>()?;
    let old_text: BTreeMap<i64, String> = db
        .prepare_cached(
            "SELECT line,text FROM lines WHERE rel_path=?1 AND field='body' AND line IS NOT NULL",
        )?
        .query_map([path], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<rusqlite::Result<_>>()?;
    let new_text: BTreeMap<i64, &str> = lines
        .iter()
        .filter(|l| l.field == "body")
        .filter_map(|l| l.line.map(|n| (n, l.text.as_str())))
        .collect();
    let old_lines: Vec<_> = previous.iter().filter(|t| t.kind == 1).collect();
    let new_lines: Vec<_> = next.iter().filter(|t| t.kind == 1).collect();
    let text_at = |t: &Target, ranges: &[&Target]| {
        if t.kind == 1 {
            Some(t.key)
        } else {
            ranges
                .partition_point(|l| l.start <= t.start)
                .checked_sub(1)
                .map(|i| ranges[i].key)
        }
    };
    let mut pairs = Vec::new();
    for kind in 0..=2 {
        let old_kind: Vec<_> = previous.iter().filter(|t| t.kind == kind).collect();
        let new_kind: Vec<_> = next.iter().filter(|t| t.kind == kind).collect();
        let mut available: BTreeMap<&str, std::collections::VecDeque<&Target>> = BTreeMap::new();
        for a in &old_kind {
            if let Some(text) = text_at(a, &old_lines).and_then(|n| old_text.get(&n)) {
                available.entry(text).or_default().push_back(a);
            }
        }
        let mut used = BTreeSet::new();
        let mut remaining = Vec::new();
        for b in new_kind {
            let matched = text_at(b, &new_lines)
                .and_then(|n| new_text.get(&n))
                .and_then(|text| available.get_mut(text))
                .and_then(std::collections::VecDeque::pop_front);
            if let Some(a) = matched {
                used.insert(a.id);
                pairs.push((a, b));
            } else {
                remaining.push(b);
            }
        }
        // Equal first-line text is only a slot-reuse preference, not evidence.
        // Fresh output below decides every value, even for repeated/nested text.
        pairs.extend(
            old_kind
                .into_iter()
                .filter(|a| !used.contains(&a.id))
                .zip(remaining),
        );
    }
    blocks::publish(db, path, old, version, layout, &previous, &next, &pairs)?;
    Ok(true)
}

mod blocks;
