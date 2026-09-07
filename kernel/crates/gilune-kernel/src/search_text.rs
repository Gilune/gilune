//! Case-insensitive search and UTF-16 offsets shared by indexing and results.

/// Simple character folding, without expanding a character into several.
/// This preserves the character-wise case-insensitive meaning of the JVM API.
pub(crate) fn fold(text: &str) -> String {
    text.chars().map(fold_char).collect()
}

/// Exclusive upper bound for a literal prefix in SQLite's BINARY UTF-8 order.
/// UTF-8 preserves scalar ordering. Carry past trailing maximum scalars and
/// skip the surrogate gap; an empty/all-maximum prefix has no upper bound.
pub(crate) fn prefix_end(prefix: &str) -> Option<String> {
    for (offset, c) in prefix.char_indices().rev() {
        let next = match c as u32 {
            0x10ffff => continue,
            0xd7ff => 0xe000,
            n => n + 1,
        };
        let mut end = prefix[..offset].to_string();
        end.push(char::from_u32(next).expect("next Unicode scalar"));
        return Some(end);
    }
    None
}

fn fold_char(c: char) -> char {
    let mut upper = c.to_uppercase();
    let first = upper.next().unwrap_or(c);
    let upper = if upper.next().is_none() { first } else { c };
    upper.to_lowercase().next().unwrap_or(upper)
}

pub(crate) fn ranges(text: &str, needles: &[String]) -> Vec<(u32, u32)> {
    let chars: Vec<char> = text.chars().collect();
    let folded: Vec<char> = chars.iter().copied().map(fold_char).collect();
    let mut offsets = vec![0u32];
    for c in &chars {
        offsets.push(offsets.last().copied().unwrap_or(0) + c.len_utf16() as u32);
    }
    let mut found = Vec::new();
    for needle in needles {
        let wanted: Vec<char> = needle.chars().map(fold_char).collect();
        if wanted.is_empty() || wanted.len() > folded.len() {
            continue;
        }
        for (at, window) in folded.windows(wanted.len()).enumerate() {
            if window == wanted {
                found.push((offsets[at], offsets[at + wanted.len()]));
            }
        }
    }
    found.sort_unstable();
    let mut merged: Vec<(u32, u32)> = Vec::new();
    for (start, end) in found {
        match merged.last_mut() {
            Some(last) if start < last.1 => last.1 = last.1.max(end),
            _ => merged.push((start, end)),
        }
    }
    merged
}

pub(crate) fn register(db: &rusqlite::Connection) -> rusqlite::Result<()> {
    use rusqlite::functions::FunctionFlags;
    db.create_scalar_function(
        "gilune_fold",
        1,
        FunctionFlags::SQLITE_UTF8 | FunctionFlags::SQLITE_DETERMINISTIC,
        |ctx| Ok(fold(&ctx.get::<String>(0)?)),
    )?;
    crate::index::positions::register(db)?;
    Ok(())
}
