//! Test-only position-storage experiment. Never enabled in a shipped kernel.
//! A revision owns a blocked binary position map; target IDs remain selection keys.
//! The query cache is reset for EVERY request and retains only one source.
use super::*;
use std::{cell::RefCell, rc::Rc};
mod tests;

type Line = (u32, u64, u64, String);
const BLOCK: usize = 4 * 1024;
const ITEM_BLOCK: usize = 1 << 40;
const RECORD: usize = 128;
fn record_base(lines: usize) -> usize {
    (24 + lines * 24).div_ceil(BLOCK) * BLOCK
}
fn raw_word(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap())
}
#[derive(Default)]
struct State {
    enabled: bool,
    source: i64,
    blocks: BTreeMap<usize, Rc<Vec<u8>>>,
    reads: usize,
    item_base: usize,
    context: Option<(i64, String, String)>,
}
thread_local! { static STATE: RefCell<State> = RefCell::new(State::default()); }

pub(super) fn enabled() -> bool {
    STATE.with(|s| s.borrow().enabled)
}

pub(super) fn hit(db: &Connection, source: i64, path: String) -> Result<SearchHit> {
    let (root,version,name):(i64,String,String)=db.prepare_cached("SELECT t.id,s.source_version,l.text FROM index_targets t JOIN source_maps s ON s.id=t.source_id JOIN lines l ON l.rel_path=s.rel_path AND l.field='name' WHERE t.source_id=?1 AND t.kind=0 AND t.local_key=0")?.query_row([source],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?)))?;
    STATE.with(|s| s.borrow_mut().context = Some((source, path.clone(), version)));
    Ok(SearchHit {
        facts: target_projection::facts(db, root)?,
        rel_path: path,
        name,
        name_matched: false,
        lines: vec![],
        items: vec![],
        more_matches: 0,
        match_count: 0,
    })
}

pub(super) fn run<T>(f: impl FnOnce() -> T) -> T {
    struct Reset;
    impl Drop for Reset {
        fn drop(&mut self) {
            STATE.with(|s| *s.borrow_mut() = State::default());
        }
    }
    STATE.with(|s| {
        assert!(!s.borrow().enabled);
        *s.borrow_mut() = State {
            enabled: true,
            ..Default::default()
        };
    });
    let _reset = Reset;
    f()
}

fn put(bytes: &mut Vec<u8>, n: u64) {
    bytes.extend_from_slice(&n.to_le_bytes());
}
struct Snapshot<'a> {
    db: &'a Connection,
    source: i64,
}
fn word(map: &Snapshot<'_>, offset: usize) -> u64 {
    // This private test encoding only writes aligned words. No record word
    // crosses a block boundary. Delivery must turn corruption into index rebuild.
    STATE.with(|state| {
        let mut s = state.borrow_mut();
        if s.item_base == 0 {
            let bytes = map
                .db
                .prepare_cached(
                    "SELECT payload FROM prototype_position_blocks WHERE source_id=?1 AND block=0",
                )
                .unwrap()
                .query_row([map.source], |r| r.get::<_, Vec<u8>>(0))
                .unwrap();
            s.item_base = record_base(raw_word(&bytes, 8) as usize);
            s.reads += bytes.len();
            s.blocks.insert(0, Rc::new(bytes));
        }
        let block = if offset >= s.item_base {
            ITEM_BLOCK + (offset - s.item_base) / BLOCK
        } else {
            offset / BLOCK
        };
        if !s.blocks.contains_key(&block) {
            let adjacent=map.db.prepare_cached("SELECT block,payload FROM prototype_position_blocks WHERE source_id=?1 AND block>=?2 AND block<?3 ORDER BY block").unwrap()
                .query_map(rusqlite::params![map.source,block as u64,(block+4) as u64],|r|Ok((r.get::<_,u64>(0)?,r.get::<_,Vec<u8>>(1)?))).unwrap()
                .collect::<rusqlite::Result<Vec<_>>>().unwrap();
            if s.blocks.len()+adjacent.len() > 256 {
                s.blocks.clear();
            }
            for (i,bytes) in adjacent {s.reads+=bytes.len();s.blocks.insert(i as usize,Rc::new(bytes));}
        }
        let bytes = &s.blocks[&block];
        u64::from_le_bytes(
            bytes[offset % BLOCK..offset % BLOCK + 8]
                .try_into()
                .unwrap(),
        )
    })
}

fn write_blocks(db: &Connection, source: i64, version: &str, bytes: &[u8]) -> Result<()> {
    db.execute("INSERT INTO position_snapshots VALUES(?1,?2,?3) ON CONFLICT(source_id) DO UPDATE SET source_version=excluded.source_version,item_base=excluded.item_base",rusqlite::params![source,version,record_base(raw_word(bytes,8) as usize) as u64])?;
    let mut write=db.prepare_cached("INSERT INTO prototype_position_blocks VALUES(?1,?2,?3) ON CONFLICT(source_id,block) DO UPDATE SET payload=excluded.payload WHERE prototype_position_blocks.payload IS NOT excluded.payload")?;
    let base = record_base(raw_word(bytes, 8) as usize);
    for (i, chunk) in bytes.chunks(BLOCK).enumerate() {
        let block = if i * BLOCK >= base {
            ITEM_BLOCK + i - base / BLOCK
        } else {
            i
        };
        write.execute(rusqlite::params![source, block as u64, chunk])?;
    }
    db.execute(
        "DELETE FROM prototype_position_blocks WHERE source_id=?1 AND ((block>=?2 AND block<?3) OR block>=?4)",
        rusqlite::params![source, (base/BLOCK) as u64,ITEM_BLOCK as u64,(ITEM_BLOCK+(bytes.len()-base).div_ceil(BLOCK)) as u64],
    )?;
    Ok(())
}

/// Deliberately not a public/persisted encoding contract. Input comes from the
/// existing validated index. Corruption/version validation belongs to delivery.
pub(super) fn seed(db: &Connection) -> Result<()> {
    db.execute_batch("CREATE TABLE position_snapshots(source_id INTEGER PRIMARY KEY, source_version TEXT NOT NULL,item_base INTEGER NOT NULL);
        CREATE TABLE prototype_position_blocks(source_id INTEGER NOT NULL,block INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(source_id,block)) WITHOUT ROWID;
        ALTER TABLE target_slots ADD COLUMN position_slot INTEGER")?;
    let sources = db
        .prepare("SELECT id,layout FROM source_maps ORDER BY id")?
        .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for (source, layout) in sources {
        let map: index::scoped::Map = serde_json::from_str(&layout)?;
        let targets = db.prepare("SELECT id,start_byte,end_byte,own_ranges FROM index_targets WHERE source_id=?1 AND kind=2 ORDER BY id")?
            .query_map([source], |r| Ok((r.get::<_, u64>(0)?,r.get::<_, u64>(1)?,r.get::<_, u64>(2)?,r.get::<_, String>(3)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let mut bytes = Vec::new();
        for n in [
            map.version as u64,
            map.lines.len() as u64,
            targets.len() as u64,
        ] {
            put(&mut bytes, n);
        }
        for &(n, start, end) in &map.lines {
            for x in [n as u64, start, end] {
                put(&mut bytes, x);
            }
        }
        let base = record_base(map.lines.len());
        let tail_base = base + targets.len() * RECORD;
        bytes.resize(base, 0);
        let mut tails = Vec::new();
        for (slot, (id, key, end, own)) in targets.into_iter().enumerate() {
            let own: Vec<(u64, u64)> = serde_json::from_str(&own)?;
            let line = map.lines.partition_point(|l| l.1 <= key).saturating_sub(1);
            for x in [
                id,
                key,
                end,
                (tail_base + tails.len()) as u64,
                own.len() as u64,
                line as u64,
            ] {
                put(&mut bytes, x);
            }
            let (a, b) = own.first().copied().unwrap_or_default();
            put(&mut bytes, a);
            put(&mut bytes, b);
            let (n, lo, hi) = map.lines[line];
            for x in [u64::from(n), lo, hi, u64::from(map.version), 0, 0, 0, 0] {
                put(&mut bytes, x);
            }
            for &(a, b) in own.iter().skip(1) {
                put(&mut tails, a);
                put(&mut tails, b);
            }
            db.prepare_cached("UPDATE target_slots SET position_slot=?1 WHERE id=?2")?
                .execute(rusqlite::params![slot as u64, id])?;
        }
        bytes.extend(tails);
        let version: String = db.query_row(
            "SELECT source_version FROM source_maps WHERE id=?1",
            [source],
            |r| r.get(0),
        )?;
        write_blocks(db, source, &version, &bytes)?;
    }
    Ok(())
}


fn snapshot(db: &Connection, source: i64) -> Result<Snapshot<'_>> {
    STATE.with(|state| {
        let mut s = state.borrow_mut();
        if s.source != source {
            s.source = source;
            s.blocks.clear();
            s.item_base = 0;
        }
        Ok(Snapshot { db, source })
    })
}

pub(super) struct Position {
    pub map: u32,
    pub key: u64,
    pub end: u64,
    pub own: Vec<(u64, u64)>,
    first: usize,
    line: (u32, u64, u64),
}

pub(super) fn position(db: &Connection, source: i64, target: i64) -> Result<Position> {
    let slot = db
        .prepare_cached("SELECT position_slot FROM target_slots WHERE id=?1")?
        .query_row([target], |r| r.get::<_, u64>(0))?;
    position_at(db, source, slot)
}

pub(super) fn position_at(db: &Connection, source: i64, slot: u64) -> Result<Position> {
    let b = snapshot(db, source)?;
    let n = word(&b, 16) as usize;
    let first = record_base(word(&b, 8) as usize);
    if slot as usize >= n {
        return Err(invalid("missing position slot"));
    }
    let at = first + slot as usize * RECORD;
    let own = word(&b, at + 24) as usize;
    Ok(Position {
        map: word(&b, 0) as u32,
        key: word(&b, at + 8),
        end: word(&b, at + 16),
        first: word(&b, at + 40) as usize,
        line: (
            word(&b, at + 64) as u32,
            word(&b, at + 72),
            word(&b, at + 80),
        ),
        own: (0..word(&b, at + 32) as usize)
            .map(|i| {
                if i == 0 {
                    (word(&b, at + 48), word(&b, at + 56))
                } else {
                    (
                        word(&b, own + (i - 1) * 16),
                        word(&b, own + (i - 1) * 16 + 8),
                    )
                }
            })
            .collect(),
    })
}

fn line_at(b: &Snapshot<'_>, offset: u64) -> usize {
    let (mut lo, mut hi) = (0, word(b, 8) as usize);
    while lo < hi {
        let mid = lo + (hi - lo) / 2;
        if word(b, 24 + mid * 24 + 8) <= offset {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    lo.saturating_sub(1)
}

pub(super) fn lines(db: &Connection, source: i64, path: &str, offset: u64) -> Result<Vec<Line>> {
    let b = snapshot(db, source)?;
    read_lines(db, path, &b, line_at(&b, offset), 256)
}

fn read_lines(
    db: &Connection,
    path: &str,
    b: &Snapshot<'_>,
    first: usize,
    count: usize,
) -> Result<Vec<Line>> {
    let finish = (first + count).min(word(b, 8) as usize);
    let a = word(b, 24 + first * 24);
    let z = word(b, 24 + (finish - 1) * 24);
    let rows=db.prepare_cached("SELECT line,text FROM lines WHERE rel_path=?1 AND field='body' AND line>=?2 AND line<=?3 ORDER BY line")?
        .query_map(rusqlite::params![path,a,z],|r|Ok((r.get::<_,u32>(0)?,r.get::<_,String>(1)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let mut result = Vec::with_capacity(rows.len());
    for (n, body) in rows {
        let slot = first + (u64::from(n) - a) as usize;
        if slot >= finish || word(b, 24 + slot * 24) != u64::from(n) {
            return Err(invalid("position line mismatch"));
        }
        result.push((
            n,
            word(b, 24 + slot * 24 + 8),
            word(b, 24 + slot * 24 + 16),
            body,
        ));
    }
    Ok(result)
}

pub(super) fn item(db: &Connection, target: i64, with_facts: bool) -> Result<SearchItem> {
    let sql=format!("SELECT t.source_id,substr(b.payload,(t.position_slot*{RECORD})%{BLOCK}+1,{RECORD}) FROM target_slots t JOIN prototype_position_blocks b ON b.source_id=t.source_id AND b.block={ITEM_BLOCK}+(t.position_slot*{RECORD})/{BLOCK} WHERE t.id=?1 AND t.kind=2");
    let (source, raw): (i64, Vec<u8>) = db
        .prepare_cached(&sql)?
        .query_row([target], |r| Ok((r.get(0)?, r.get(1)?)))?;
    let context = STATE.with(|s| s.borrow().context.clone().filter(|c| c.0 == source));
    let (path, version) = if let Some((_, path, version)) = context {
        (path, version)
    } else {
        db.query_row(
            "SELECT rel_path,source_version FROM source_maps WHERE id=?1",
            [source],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?
    };
    let b = snapshot(db, source)?;
    let tail = raw_word(&raw, 24) as usize;
    let p = Position {
        map: raw_word(&raw, 88) as u32,
        key: raw_word(&raw, 8),
        end: raw_word(&raw, 16),
        first: raw_word(&raw, 40) as usize,
        line: (
            raw_word(&raw, 64) as u32,
            raw_word(&raw, 72),
            raw_word(&raw, 80),
        ),
        own: (0..raw_word(&raw, 32) as usize)
            .map(|i| {
                if i == 0 {
                    (raw_word(&raw, 48), raw_word(&raw, 56))
                } else {
                    (
                        word(&b, tail + (i - 1) * 16),
                        word(&b, tail + (i - 1) * 16 + 8),
                    )
                }
            })
            .collect(),
    };
    let first_line = p.line.0;
    let mut text = String::new();
    for &(start, finish) in &p.own {
        if start == finish {
            continue;
        }
        let lines = if start >= p.line.1 && finish <= p.line.2 {
            let body = db
                .prepare_cached(
                    "SELECT text FROM lines WHERE rel_path=?1 AND field='body' AND line=?2",
                )?
                .query_row(rusqlite::params![path, first_line], |r| r.get(0))?;
            vec![(first_line, p.line.1, p.line.2, body)]
        } else {
            let first = if start == p.key {
                p.first
            } else {
                line_at(&b, start)
            };
            let last = line_at(&b, finish - 1);
            read_lines(db, &path, &b, first, last - first + 1)?
        };
        if !text.is_empty() && !text.ends_with('\n') {
            text.push('\n');
        }
        for (n, lo, end, body) in lines {
            let a = if start == p.key && n == first_line {
                0
            } else {
                start.saturating_sub(lo).min(body.len() as u64) as usize
            };
            let z = finish.saturating_sub(lo).min(body.len() as u64) as usize;
            if z > a {
                text.push_str(
                    body.get(a..z)
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
        key: p.key,
        source_version: version,
        map_version: p.map,
        range: gilune_document::ByteSpan {
            start: p.key,
            end: p.end,
        },
        own_ranges: p
            .own
            .into_iter()
            .map(|(start, end)| gilune_document::ByteSpan { start, end })
            .collect(),
        first_line,
        text,
        ranges: vec![],
        facts: if with_facts {
            target_projection::facts(db, target)?
        } else {
            vec![]
        },
    })
}
