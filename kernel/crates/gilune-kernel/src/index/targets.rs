//! Compact, snapshot-local identities shared by independently contributed facts.
//! IDs are derived implementation details; callers receive source provenance.
use super::*;
use gilune_document::{ByteSpan, Location};

pub(super) fn resolve(
    db: &rusqlite::Connection,
    path: String,
    version: String,
    location: Location,
) -> Result<scoped::LocatedMatch> {
    let (kind, key) = match location {
        Location::DocumentRoot => (0, 0),
        Location::BodyLine { number } => (1, i64::from(number)),
        Location::MarkdownItem { key } => (2, i64::try_from(key).map_err(|_| invalid())?),
    };
    let mut statement = db.prepare_cached(
        "SELECT t.map_version,t.start_byte,t.end_byte,t.own_ranges
         FROM source_maps s JOIN index_targets t ON t.source_id=s.id
         WHERE s.rel_path=?1 AND s.source_version=?2 AND t.kind=?3 AND t.local_key=?4",
    )?;
    let (map_version, start, end, own): (u32, u64, u64, String) = statement
        .query_row(rusqlite::params![path, version, kind, key], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
        })
        .map_err(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => invalid(),
            _ => e.into(),
        })?;
    let own: Vec<(u64, u64)> = serde_json::from_str(&own).map_err(|_| invalid())?;
    Ok(scoped::LocatedMatch {
        rel_path: path,
        source_version: version,
        map_version,
        location,
        range: ByteSpan { start, end },
        own_ranges: own
            .into_iter()
            .map(|(start, end)| ByteSpan { start, end })
            .collect(),
    })
}

fn invalid() -> KernelError {
    KernelError::SearchInvalid {
        detail: "indexed location is absent or invalid; rebuild index".into(),
    }
}

#[cfg(test)]
mod tests;
