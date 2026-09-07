//! Publish relative geometry only with complete, fresh output from every owner.
use super::*;
use crate::index::positions;

type StoredLocation = (i64, Option<i64>, Option<i64>, Option<i64>);

struct Placement<'a> {
    old: Option<&'a Target>,
    next: &'a Target,
    order: i64,
    block: i64,
    base_key: i64,
    base_byte: i64,
}

#[allow(clippy::too_many_arguments)]
pub(super) fn publish(
    db: &Connection,
    path: &str,
    old: &str,
    version: &str,
    layout: &str,
    previous: &[Target],
    next: &[Target],
    pairs: &[(&Target, &Target)],
) -> Result<()> {
    let source: i64 = db.query_row(
        "SELECT id FROM source_maps WHERE rel_path=?1",
        [path],
        |r| r.get(0),
    )?;
    let matched: BTreeMap<_, _> = pairs.iter().map(|(a, b)| ((b.kind, b.key), *a)).collect();
    let retained: BTreeSet<_> = pairs.iter().map(|(a, _)| a.id).collect();
    // Deletion materialises old coordinates before a block can move.
    for t in previous.iter().filter(|t| !retained.contains(&t.id)) {
        db.prepare_cached("DELETE FROM target_slots WHERE id=?1")?
            .execute([t.id])?;
    }
    // Bind previously literal evidence to a retained private slot. Removed and
    // invalid locations keep the old frame until the complete fact diff removes them.
    let frame: Option<i64> = db
        .query_row(
            "SELECT id FROM fact_frames WHERE rel_path=?1 AND source_version=?2",
            params![path, old],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(frame) = frame {
        let locations: Vec<StoredLocation> = db
            .prepare_cached(
                "SELECT id,target_id,line,item_key FROM fact_location_slots WHERE frame_id=?1",
            )?
            .query_map([frame], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
            })?
            .collect::<rusqlite::Result<_>>()?;
        let old_anchors: BTreeMap<_, _> =
            pairs.iter().map(|(a, _)| ((a.kind, a.key), a.id)).collect();
        let mut stale_frame = None;
        // Advance first to make the previous revision identity available.
        db.execute(
            "UPDATE fact_frames SET source_version=?1 WHERE id=?2",
            params![version, frame],
        )?;
        for (id, target, line, item) in locations {
            let anchor = match (line, item) {
                (None, None) => Some((0, 0)),
                (Some(n), None) => Some((1, n)),
                (None, Some(n)) => Some((2, n)),
                _ => None,
            };
            let binding = target
                .filter(|id| retained.contains(id))
                .or_else(|| anchor.and_then(|a| old_anchors.get(&a).copied()));
            if let Some(binding) = binding {
                if Some(binding) != target
                    || line.is_some_and(|n| n != 0)
                    || item.is_some_and(|n| n != 0)
                {
                    db.prepare_cached("UPDATE fact_location_slots SET target_id=?1,line=?2,item_key=?3 WHERE id=?4")?.execute(params![binding,line.map(|_|0),item.map(|_|0),id])?;
                }
            } else {
                let previous = match stale_frame {
                    Some(id) => id,
                    None => {
                        let id = db.query_row("INSERT INTO fact_frames(rel_path,source_version) VALUES(?1,?2) RETURNING id",params![path,old],|r|r.get::<_,i64>(0))?;
                        stale_frame = Some(id);
                        id
                    }
                };
                db.prepare_cached("UPDATE fact_location_slots SET frame_id=?1 WHERE id=?2")?
                    .execute(params![previous, id])?;
            }
        }
    }
    let mut placements = Vec::with_capacity(next.len());
    let mut used_blocks = BTreeSet::new();
    let mut block_key: i64 = db.query_row(
        "SELECT COALESCE(MAX(block_key),0) FROM position_blocks WHERE source_id=?1",
        [source],
        |r| r.get(0),
    )?;
    for kind in 0..=2 {
        let targets: Vec<_> = next.iter().filter(|t| t.kind == kind).collect();
        let olds: Vec<_> = targets
            .iter()
            .map(|t| matched.get(&(kind, t.key)).copied())
            .collect();
        let mut labels: Vec<_> = olds.iter().map(|t| t.map(|t| t.order)).collect();
        let orders = if kind == 0 {
            vec![0; targets.len()]
        } else {
            positions::order(&mut labels)?
        };
        let mut preferred: Vec<_> = olds.iter().map(|t| t.map(|t| t.block)).collect();
        let mut last = preferred.iter().find_map(|n| *n);
        for b in &mut preferred {
            if b.is_none() {
                *b = last;
            } else {
                last = *b;
            }
        }
        let mut start = 0;
        while start < targets.len() {
            let mut end = start + 1;
            while end < targets.len() && preferred[end] == preferred[start] {
                end += 1;
            }
            if end - start > 128 {
                end = start + 64;
            }
            // Coalesce adjacent small runs so repeated moves/deletions cannot
            // degrade the representation into one absolute base per target.
            while end < targets.len() && end - start < 64 {
                let mut following = end + 1;
                while following < targets.len() && preferred[following] == preferred[end] {
                    following += 1;
                }
                if following - start > 64 {
                    break;
                }
                end = following;
            }
            let base_key = targets[start].key;
            let base_byte = targets[start].start;
            let block = if let Some(block) = preferred[start].filter(|id| !used_blocks.contains(id))
            {
                db.prepare_cached("UPDATE position_blocks SET base_key=?1,base_byte=?2 WHERE id=?3 AND (base_key!=?1 OR base_byte!=?2)")?.execute(params![base_key,base_byte,block])?;
                block
            } else {
                block_key = block_key
                    .checked_add(1)
                    .ok_or_else(|| KernelError::SearchInvalid {
                        detail: "position block overflow".into(),
                    })?;
                db.query_row("INSERT INTO position_blocks(source_id,kind,block_key,base_key,base_byte) VALUES(?1,?2,?3,?4,?5) RETURNING id",params![source,kind,block_key,base_key,base_byte],|r|r.get(0))?
            };
            used_blocks.insert(block);
            for i in start..end {
                placements.push(Placement {
                    old: olds[i],
                    next: targets[i],
                    order: orders[i],
                    block,
                    base_key,
                    base_byte,
                });
            }
            start = end;
        }
    }
    // Vacate changing labels in a private temporary source partition. The
    // transaction restores every slot before source publication or rollback.
    for p in &placements {
        if let Some(a) = p.old.filter(|a| a.order != p.order) {
            db.prepare_cached("UPDATE target_slots SET source_id=-source_id WHERE id=?1")?
                .execute([a.id])?;
        }
    }
    for p in &placements {
        let b = p.next;
        let own: Vec<(i64, i64)> = serde_json::from_str(&b.own)?;
        let own = serde_json::to_string(
            &own.into_iter()
                .map(|(a, z)| (a - p.base_byte, z - p.base_byte))
                .collect::<Vec<_>>(),
        )?;
        if let Some(a) = p.old {
            let old_own: Vec<(i64, i64)> = serde_json::from_str(&a.own)?;
            let old_own = serde_json::to_string(
                &old_own
                    .into_iter()
                    .map(|(lo, hi)| (lo - a.base_byte, hi - a.base_byte))
                    .collect::<Vec<_>>(),
            )?;
            if a.order == p.order
                && a.block == p.block
                && a.key - a.base_key == b.key - p.base_key
                && a.map == b.map
                && a.start - a.base_byte == b.start - p.base_byte
                && a.end - a.base_byte == b.end - p.base_byte
                && old_own == own
            {
                continue;
            }
            db.prepare_cached("UPDATE target_slots SET source_id=?9,local_key=?1,block_id=?2,anchor=?3,map_version=?4,start_byte=?5,end_byte=?6,own_ranges=?7 WHERE id=?8")?
                .execute(params![p.order,p.block,b.key-p.base_key,b.map,b.start-p.base_byte,b.end-p.base_byte,own,a.id,source])?;
        } else {
            db.prepare_cached("INSERT INTO target_slots(source_id,kind,local_key,block_id,anchor,map_version,start_byte,end_byte,own_ranges) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)")?
                .execute(params![source,b.kind,p.order,p.block,b.key-p.base_key,b.map,b.start-p.base_byte,b.end-p.base_byte,own])?;
        }
    }
    db.execute("DELETE FROM position_blocks WHERE source_id=?1 AND NOT EXISTS(SELECT 1 FROM target_slots WHERE block_id=position_blocks.id)",[source])?;
    db.execute(
        "INSERT OR REPLACE INTO prepared_geometry(source_id,layout) VALUES(?1,?2)",
        params![source, layout],
    )?;
    Ok(())
}
