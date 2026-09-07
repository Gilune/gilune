//! A bounded physical-line window for sequential item text evaluation. It avoids
//! repeated SQL per item without decoding a whole map or retaining a whole file.
use super::*;
use gilune_document::ByteSpan;

type Line = (u32, u64, u64, String);
#[derive(Default)]
pub(super) struct TextWindow {
    source: i64,
    limit_to_span: bool,
    lines: Vec<Line>,
}

impl TextWindow {
    pub(super) fn at(db: &Connection, source: i64, offset: u64) -> Result<Self> {
        let lines = index::positions::lines(db, source, offset, 1)?;
        #[cfg(test)]
        target_projection::WORK.with(|w| {
            let (items, read) = w.get();
            w.set((items, read + lines.len()));
        });
        Ok(Self {
            source,
            lines,
            limit_to_span: true,
        })
    }

    fn ensure(
        &mut self,
        db: &Connection,
        source: i64,
        _path: &str,
        offset: u64,
        finish: Option<u64>,
    ) -> Result<()> {
        if self.source == source
            && self.lines.first().is_some_and(|l| l.1 <= offset)
            && self.lines.last().is_some_and(|l| offset < l.2)
        {
            return Ok(());
        }
        #[cfg(test)]
        if position_prototype::enabled() {
            self.lines = position_prototype::lines(db, source, _path, offset)?;
            self.source = source;
            return Ok(());
        }
        self.lines = if let Some(finish) = finish.filter(|_| self.limit_to_span) {
            index::positions::lines_until(db, source, offset, finish)?
        } else {
            index::positions::lines(db, source, offset, 256)?
        };
        self.source = source;
        #[cfg(test)]
        target_projection::WORK.with(|w| {
            let (items, lines) = w.get();
            w.set((items, lines + self.lines.len()));
        });
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn item(
        &mut self,
        db: &Connection,
        source: i64,
        path: &str,
        version: String,
        map_version: u32,
        key: u64,
        end: u64,
        own: Vec<(u64, u64)>,
    ) -> Result<SearchItem> {
        self.ensure(db, source, path, key, None)?;
        let first_line = self
            .lines
            .iter()
            .rev()
            .find(|l| l.1 <= key)
            .ok_or_else(|| invalid("item has no indexed line"))?
            .0;
        let mut text = String::new();
        for &(start, finish) in &own {
            if !text.is_empty() && !text.ends_with('\n') {
                text.push('\n');
            }
            let mut cursor = start;
            while cursor < finish {
                self.ensure(db, source, path, cursor, Some(finish))?;
                let before = cursor;
                for (number, lo, line_end, body) in
                    self.lines.iter().filter(|l| l.2 > before && l.1 < finish)
                {
                    let a = if start == key && *number == first_line {
                        0
                    } else {
                        start.saturating_sub(*lo).min(body.len() as u64) as usize
                    };
                    let b = finish.saturating_sub(*lo).min(body.len() as u64) as usize;
                    if b > a {
                        text.push_str(
                            body.get(a..b)
                                .ok_or_else(|| invalid("invalid item UTF-8 range"))?,
                        );
                    }
                    if finish > *lo + body.len() as u64 && *line_end > *lo + body.len() as u64 {
                        text.push('\n');
                    }
                    cursor = *line_end;
                }
                if cursor <= before {
                    return Err(invalid("item range exceeds indexed text"));
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
        #[cfg(test)]
        target_projection::WORK.with(|w| {
            let (items, lines) = w.get();
            w.set((items + 1, lines));
        });
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
            facts: vec![],
        })
    }
}
