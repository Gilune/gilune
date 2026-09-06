//! Common Markdown structure over exact source bytes. List membership comes
//! from the parser, with no recognition of any plugin's domain syntax.

use crate::{physical_lines, ByteSpan, DocumentError};
use pulldown_cmark::{Event, Options, Parser, Tag, TagEnd};

pub const MARKDOWN_MAP_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Location {
    DocumentRoot,
    MarkdownItem { key: u64 },
    BodyLine { number: u32 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BodyLine {
    pub number: u32,
    /// Includes the original line ending, when present.
    pub range: ByteSpan,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MarkdownItem {
    /// The full-source start byte: stable only inside this source version.
    pub key: u64,
    pub parent_key: Option<u64>,
    pub range: ByteSpan,
    /// All of this item's source except its direct children's entire spans.
    pub own_ranges: Vec<ByteSpan>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MarkdownLayout {
    pub version: u32,
    pub body_start: u64,
    pub source_len: u64,
    pub lines: Vec<BodyLine>,
    pub items: Vec<MarkdownItem>,
    /// Raw source ranges of ordinary text events. Code, HTML and link
    /// destinations are absent. Plugins still check their own escaping rules
    /// against the full source, never against decoded Markdown event text.
    pub text_ranges: Vec<ByteSpan>,
    item_ranges: Vec<(ByteSpan, u64)>,
}

impl MarkdownLayout {
    pub(crate) fn parse(source: &str, body_start: u64) -> Self {
        let body = &source[body_start as usize..];
        let options =
            Options::ENABLE_TABLES | Options::ENABLE_STRIKETHROUGH | Options::ENABLE_TASKLISTS;
        let mut items: Vec<MarkdownItem> = Vec::new();
        let mut stack: Vec<usize> = Vec::new();
        let mut text_ranges = Vec::new();
        let mut excluded = 0u32;
        for (event, range) in Parser::new_ext(body, options).into_offset_iter() {
            let span = ByteSpan {
                start: body_start + range.start as u64,
                end: body_start + range.end as u64,
            };
            match event {
                Event::Start(Tag::Item) => {
                    let parent_key = stack.last().map(|index| items[*index].key);
                    stack.push(items.len());
                    items.push(MarkdownItem {
                        key: span.start,
                        parent_key,
                        range: span,
                        own_ranges: Vec::new(),
                    });
                }
                Event::End(TagEnd::Item) => {
                    stack.pop();
                }
                Event::Start(Tag::CodeBlock(_) | Tag::HtmlBlock) => excluded += 1,
                Event::End(TagEnd::CodeBlock | TagEnd::HtmlBlock) => {
                    excluded = excluded.saturating_sub(1)
                }
                Event::Start(Tag::Link {
                    link_type: pulldown_cmark::LinkType::Autolink | pulldown_cmark::LinkType::Email,
                    ..
                }) => excluded += 1,
                Event::End(TagEnd::Link) if excluded > 0 => excluded -= 1,
                Event::Text(_) if excluded == 0 => text_ranges.push(span),
                _ => {}
            }
        }
        // Items arrive in source order. Direct children are disjoint, and
        // subtracting their full ranges excludes all descendants in one pass.
        let mut children: std::collections::BTreeMap<u64, Vec<ByteSpan>> =
            std::collections::BTreeMap::new();
        for item in &items {
            if let Some(parent) = item.parent_key {
                children.entry(parent).or_default().push(item.range);
            }
        }
        for item in &mut items {
            let mut at = item.range.start;
            for child in children.get(&item.key).into_iter().flatten() {
                if at < child.start {
                    item.own_ranges.push(ByteSpan {
                        start: at,
                        end: child.start,
                    });
                }
                at = child.end;
            }
            if at < item.range.end {
                item.own_ranges.push(ByteSpan {
                    start: at,
                    end: item.range.end,
                });
            }
        }
        let mut item_ranges: Vec<_> = items
            .iter()
            .flat_map(|item| item.own_ranges.iter().map(|span| (*span, item.key)))
            .collect();
        item_ranges.sort_by_key(|(span, _)| span.start);
        Self {
            item_ranges,
            version: MARKDOWN_MAP_VERSION,
            body_start,
            source_len: source.len() as u64,
            lines: physical_lines(source, body_start)
                .into_iter()
                .enumerate()
                .map(|(i, range)| BodyLine {
                    number: i as u32 + 1,
                    range,
                })
                .collect(),
            items,
            text_ranges,
        }
    }

    /// Deepest containing item, otherwise a physical body line. Metadata is
    /// document-root scope. The offset must already be a valid UTF-8 boundary.
    pub(crate) fn location_at(&self, source: &str, offset: u64) -> Result<Location, DocumentError> {
        ByteSpan {
            start: offset,
            end: offset,
        }
        .slice(source)?;
        if offset < self.body_start {
            return Ok(Location::DocumentRoot);
        }
        let at = self
            .item_ranges
            .partition_point(|(span, _)| span.start <= offset);
        if let Some((span, key)) = at
            .checked_sub(1)
            .and_then(|index| self.item_ranges.get(index))
        {
            if span.contains(offset) {
                return Ok(Location::MarkdownItem { key: *key });
            }
        }
        let index = self
            .lines
            .partition_point(|line| line.range.start <= offset)
            .saturating_sub(1);
        Ok(Location::BodyLine {
            number: self.lines[index].number,
        })
    }
}
