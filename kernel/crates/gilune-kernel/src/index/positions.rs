//! Relative source coordinates with private, sparsely allocated ordering labels.
//! Blocks move without rewriting their unchanged members. All public coordinates
//! are reconstructed against the source revision inside the reader's transaction.
use super::*;
use rusqlite::{params, Connection};

pub(crate) fn register(db: &Connection) -> rusqlite::Result<()> {
    use rusqlite::functions::FunctionFlags;
    db.create_scalar_function(
        "gilune_shift_ranges",
        2,
        FunctionFlags::SQLITE_UTF8 | FunctionFlags::SQLITE_DETERMINISTIC,
        |ctx| {
            let text: String = ctx.get(0)?;
            let delta: i64 = ctx.get(1)?;
            if delta == 0 {
                return Ok(text);
            }
            let mut ranges: Vec<(i64, i64)> = serde_json::from_str(&text)
                .map_err(|e| rusqlite::Error::UserFunctionError(Box::new(e)))?;
            for (a, b) in &mut ranges {
                *a = a.checked_add(delta).ok_or(rusqlite::Error::InvalidQuery)?;
                *b = b.checked_add(delta).ok_or(rusqlite::Error::InvalidQuery)?;
                if *a < 0 || *b < *a {
                    return Err(rusqlite::Error::InvalidQuery);
                }
            }
            serde_json::to_string(&ranges)
                .map_err(|e| rusqlite::Error::UserFunctionError(Box::new(e)))
        },
    )?;
    Ok(())
}

pub(super) const GAP: i64 = 1 << 32;

/// Preserve ordered labels and redistribute only a neighbourhood when its gap
/// is exhausted. An arbitrary permutation may necessarily move many labels.
pub(super) fn order(labels: &mut [Option<i64>]) -> Result<Vec<i64>> {
    let mut last = 0;
    for label in labels.iter_mut() {
        match *label {
            Some(n) if n > last && n < i64::MAX => last = n,
            _ => *label = None,
        }
    }
    let mut i = 0;
    while i < labels.len() {
        if labels[i].is_some() {
            i += 1;
            continue;
        }
        let mut start = i;
        let mut end = i;
        while end < labels.len() && labels[end].is_none() {
            end += 1;
        }
        loop {
            let lo = if start == 0 {
                0
            } else {
                labels[start - 1].unwrap_or(0)
            };
            let hi = if end == labels.len() {
                i64::MAX
            } else {
                labels[end].unwrap_or(i64::MAX)
            };
            let count = i64::try_from(end - start + 1).map_err(|_| invalid())?;
            let step = ((hi - lo) / count).min(GAP);
            if step > 0 {
                for (n, label) in labels[start..end].iter_mut().enumerate() {
                    *label = Some(lo + step * (n as i64 + 1));
                }
                i = end;
                break;
            }
            if start == 0 && end == labels.len() {
                return Err(invalid());
            }
            start = start.saturating_sub(64);
            end = (end + 64).min(labels.len());
            while start > 0 && labels[start - 1].is_none() {
                start -= 1;
            }
            while end < labels.len() && labels[end].is_none() {
                end += 1;
            }
        }
    }
    labels.iter().map(|n| n.ok_or_else(invalid)).collect()
}

fn invalid() -> KernelError {
    KernelError::SearchInvalid {
        detail: "invalid indexed position; rebuild index".into(),
    }
}

/// Resolve a public anchor through a bounded block predecessor lookup.
pub(crate) fn target(db: &Connection, source: i64, kind: i64, key: i64) -> Result<Option<i64>> {
    Ok(db.prepare_cached("SELECT t.id FROM position_blocks b JOIN target_slots t ON t.block_id=b.id AND t.anchor=?3-b.base_key WHERE b.id=(SELECT id FROM position_blocks WHERE source_id=?1 AND kind=?2 AND base_key<=?3 ORDER BY base_key DESC LIMIT 1)")?
        .query_row(params![source,kind,key], |r| r.get(0)).optional()?)
}

/// Bounded line window in private order, resolving coordinates only for these rows.
pub(crate) fn lines(
    db: &Connection,
    source: i64,
    offset: u64,
    limit: usize,
) -> Result<Vec<(u32, u64, u64, String)>> {
    lines_range(db, source, offset, limit, None)
}

pub(crate) fn lines_until(
    db: &Connection,
    source: i64,
    offset: u64,
    finish: u64,
) -> Result<Vec<(u32, u64, u64, String)>> {
    lines_range(db, source, offset, 256, Some(finish))
}

fn lines_range(
    db: &Connection,
    source: i64,
    offset: u64,
    limit: usize,
    finish: Option<u64>,
) -> Result<Vec<(u32, u64, u64, String)>> {
    if limit == 1 {
        return Ok(db.prepare_cached("SELECT t.anchor+b.base_key,t.start_byte+b.base_byte,t.end_byte+b.base_byte,l.text FROM position_blocks b JOIN target_slots t ON t.id=(SELECT id FROM target_slots WHERE block_id=b.id AND start_byte<=?2-b.base_byte ORDER BY start_byte DESC LIMIT 1) LEFT JOIN text_rows l ON l.target_id=t.id WHERE b.id=(SELECT id FROM position_blocks WHERE source_id=?1 AND kind=1 AND base_byte<=?2 ORDER BY base_byte DESC LIMIT 1)")?
            .query_map(params![source,offset],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?)))?.collect::<rusqlite::Result<_>>()?);
    }
    let (block,base): (i64,i64) = db.prepare_cached("SELECT id,base_byte FROM position_blocks WHERE source_id=?1 AND kind=1 AND base_byte<=?2 ORDER BY base_byte DESC LIMIT 1")?
        .query_row(params![source,offset], |r|Ok((r.get(0)?,r.get(1)?)))?;
    let order: i64 = db.prepare_cached("SELECT local_key FROM target_slots WHERE block_id=?1 AND start_byte<=?2 ORDER BY start_byte DESC LIMIT 1")?
        .query_row(params![block,offset as i64-base], |r|r.get(0))?;
    let end_order: i64 = if let Some(finish) = finish {
        db.prepare_cached("SELECT t.local_key FROM position_blocks b JOIN target_slots t ON t.id=(SELECT id FROM target_slots WHERE block_id=b.id AND start_byte<?2-b.base_byte ORDER BY start_byte DESC LIMIT 1) WHERE b.id=(SELECT id FROM position_blocks WHERE source_id=?1 AND kind=1 AND base_byte<?2 ORDER BY base_byte DESC LIMIT 1)")?.query_row(params![source,finish],|r|r.get(0))?
    } else {
        i64::MAX
    };
    Ok(db.prepare_cached("SELECT t.anchor+b.base_key,t.start_byte+b.base_byte,t.end_byte+b.base_byte,l.text FROM target_slots t JOIN position_blocks b ON b.id=t.block_id LEFT JOIN text_rows l ON l.target_id=t.id WHERE t.source_id=?1 AND t.kind=1 AND t.local_key>=?2 AND t.local_key<=?4 ORDER BY t.local_key LIMIT ?3")?
        .query_map(params![source,order,limit as i64,end_order], |r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?)))?.collect::<rusqlite::Result<_>>()?)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn repeated_insertions_rebalance_a_neighbourhood_and_preserve_order() {
        let mut values: Vec<_> = (1..=10_000).map(|n| Some(n * GAP)).collect();
        for _ in 0..250 {
            let previous = values.clone();
            values.insert(1, None);
            let assigned = order(&mut values).unwrap();
            assert!(assigned.windows(2).all(|w| w[0] < w[1]));
            let changed = previous
                .iter()
                .zip(values.iter().take(1).chain(values.iter().skip(2)))
                .filter(|(a, b)| a != b)
                .count();
            assert!(changed < 256, "relabelled {changed}");
        }
    }
    #[test]
    fn malformed_ranges_and_overflow_are_errors() {
        let db = Connection::open_in_memory().unwrap();
        register(&db).unwrap();
        for (text, delta) in [
            ("bad", 1),
            ("[[0,1]]", -1),
            ("[[9223372036854775807,9223372036854775807]]", 1),
            ("[[4,2]]", 1),
        ] {
            assert!(db
                .query_row(
                    "SELECT gilune_shift_ranges(?1,?2)",
                    params![text, delta],
                    |r| r.get::<_, String>(0)
                )
                .is_err());
        }
    }
}
