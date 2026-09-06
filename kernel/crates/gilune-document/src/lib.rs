//! Exact source text, common Markdown locations and proposed text changes.
//! This contract has no filesystem, database, application or domain dependency.

mod markdown;
mod properties;
pub use markdown::{BodyLine, Location, MarkdownItem, MarkdownLayout, MARKDOWN_MAP_VERSION};
pub use properties::{PropertyChange, PropertyField, PropertySheet};

use sha2::{Digest, Sha256};

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DocumentError {
    #[error("front matter has no closing delimiter")]
    UnclosedHeader,
    #[error("invalid front matter: {detail}")]
    InvalidHeader { detail: String },
    #[error("invalid UTF-8 byte range {start}..{end}")]
    InvalidRange { start: u64, end: u64 },
    #[error("offset is inside a Unicode character")]
    CharacterBoundary,
    #[error("text edits overlap or have ambiguous ordering")]
    OverlappingEdits,
    #[error("text change would alter protected document metadata")]
    ProtectedMetadata,
    #[error("document content changed since this proposal was prepared")]
    VersionChanged,
}

/// Full-source UTF-8 bytes, start inclusive and end exclusive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ByteSpan {
    pub start: u64,
    pub end: u64,
}

impl ByteSpan {
    pub fn contains(self, offset: u64) -> bool {
        self.start <= offset && offset < self.end
    }

    pub fn slice(self, source: &str) -> Result<&str, DocumentError> {
        if self.start > self.end || self.end > source.len() as u64 {
            return Err(DocumentError::InvalidRange {
                start: self.start,
                end: self.end,
            });
        }
        source
            .get(self.start as usize..self.end as usize)
            .ok_or(DocumentError::CharacterBoundary)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TextEdit {
    pub range: ByteSpan,
    pub replacement: String,
}

/// Metadata entries retain their raw source representation. This is a source
/// map, not a YAML value interpreter; the owning plugin interprets its fields.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetadataEntry {
    pub key: String,
    pub range: ByteSpan,
    pub value_range: ByteSpan,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceHeader {
    pub range: ByteSpan,
    pub opening: ByteSpan,
    pub closing: ByteSpan,
    pub entries: Vec<MetadataEntry>,
}

/// Immutable content snapshot. A content fingerprint alone makes no claim
/// about filesystem freshness or managed object identity; the host adds those.
#[derive(Debug, Clone)]
pub struct TextSnapshot {
    text: String,
    version: String,
    header: Option<SourceHeader>,
    protected: Vec<ByteSpan>,
    layout: MarkdownLayout,
    metadata: serde_yaml_ng::Mapping,
}

/// An arena of parsed YAML values, root at index zero. Sequence children are
/// value indices; mapping children alternate key/value indices (keys retain
/// their original types). Tagged values have one child and the tag as value.
/// This read model never replaces the exact source used by editing/refactoring.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetadataNode {
    pub kind: String,
    pub value: Option<String>,
    pub children: Vec<u32>,
}

impl TextSnapshot {
    pub fn metadata_nodes(&self) -> Vec<MetadataNode> {
        use serde_yaml_ng::Value;
        fn append(value: &Value, nodes: &mut Vec<MetadataNode>) -> u32 {
            let id = nodes.len() as u32;
            nodes.push(MetadataNode { kind: String::new(), value: None, children: Vec::new() });
            let (kind, scalar, children) = match value {
                Value::Null => ("null", None, Vec::new()),
                Value::Bool(v) => ("boolean", Some(v.to_string()), Vec::new()),
                Value::Number(v) => ("number", Some(v.to_string()), Vec::new()),
                Value::String(v) => ("string", Some(v.clone()), Vec::new()),
                Value::Sequence(values) => ("sequence", None, values.iter().map(|v| append(v, nodes)).collect()),
                Value::Mapping(values) => ("mapping", None, values.iter().flat_map(|(k,v)| [append(k,nodes), append(v,nodes)]).collect()),
                Value::Tagged(v) => ("tagged", Some(v.tag.to_string()), vec![append(&v.value,nodes)]),
            };
            nodes[id as usize] = MetadataNode { kind: kind.into(), value: scalar, children };
            id
        }
        let mut nodes = vec![MetadataNode { kind: "mapping".into(), value: None, children: Vec::new() }];
        let children = self.metadata.iter().flat_map(|(k,v)| [append(k,&mut nodes),append(v,&mut nodes)]).collect();
        nodes[0].children = children;
        nodes
    }
    pub fn new(text: String) -> Result<Self, DocumentError> {
        let header = source_header(&text)?;
        let metadata = parse_metadata(&text, header.as_ref())?;
        let body_start = header.as_ref().map_or(0, |h| h.range.end);
        let mut protected = Vec::new();
        if let Some(header) = &header {
            protected.extend([header.opening, header.closing]);
            protected.extend(
                header
                    .entries
                    .iter()
                    .filter(|entry| is_reserved(&entry.key))
                    .map(|entry| entry.range),
            );
            if metadata
                .keys()
                .filter_map(|key| key.as_str())
                .any(|key| is_reserved(key) && !header.entries.iter().any(|entry| entry.key == key))
            {
                // Flow mappings and inherited/aliased keys have no independent
                // physical entry to protect. Body changes remain available.
                protected = vec![header.range];
            }
        }
        let layout = MarkdownLayout::parse(&text, body_start);
        Ok(Self {
            version: content_version(&text),
            text,
            header,
            protected,
            layout,
            metadata,
        })
    }

    pub fn text(&self) -> &str {
        &self.text
    }
    pub fn version(&self) -> &str {
        &self.version
    }
    pub fn header(&self) -> Option<&SourceHeader> {
        self.header.as_ref()
    }
    pub fn protected_ranges(&self) -> &[ByteSpan] {
        &self.protected
    }
    pub fn layout(&self) -> &MarkdownLayout {
        &self.layout
    }
    pub fn location_at(&self, offset: u64) -> Result<Location, DocumentError> {
        self.layout.location_at(&self.text, offset)
    }
    pub fn metadata_string(&self, key: &str) -> Option<&str> {
        self.metadata
            .get(serde_yaml_ng::Value::String(key.into()))
            .and_then(|value| value.as_str())
    }

    pub fn has_reserved_metadata(&self) -> bool {
        self.metadata
            .keys()
            .any(|key| key.as_str().is_some_and(is_reserved))
    }

    pub fn body(&self) -> &str {
        &self.text[self.layout.body_start as usize..]
    }

    /// Validate every range against this exact source, then prepare new text.
    /// No write occurs. Callers must still check the source again when applying.
    pub fn propose(
        &self,
        expected_version: &str,
        edits: &[TextEdit],
    ) -> Result<Self, DocumentError> {
        if expected_version != self.version {
            return Err(DocumentError::VersionChanged);
        }
        let mut ordered: Vec<&TextEdit> = edits.iter().collect();
        ordered.sort_by_key(|edit| (edit.range.start, edit.range.end));
        for edit in &ordered {
            edit.range.slice(&self.text)?;
            if self.protected.iter().any(|span| {
                if edit.range.start == edit.range.end {
                    span.start < edit.range.start && edit.range.start < span.end
                } else {
                    span.start < edit.range.end && edit.range.start < span.end
                }
            }) {
                return Err(DocumentError::ProtectedMetadata);
            }
        }
        if ordered.windows(2).any(|pair| {
            pair[1].range.start < pair[0].range.end || pair[1].range.start == pair[0].range.start
        }) {
            return Err(DocumentError::OverlappingEdits);
        }
        let mut after = self.text.clone();
        for edit in ordered.into_iter().rev() {
            after.replace_range(
                edit.range.start as usize..edit.range.end as usize,
                &edit.replacement,
            );
        }
        let after = Self::new(after)?;
        if let Some(before_header) = &self.header {
            let shifted = |span: ByteSpan| {
                let shift: i128 = edits
                    .iter()
                    .filter(|edit| edit.range.end <= span.start)
                    .map(|edit| {
                        edit.replacement.len() as i128 - (edit.range.end - edit.range.start) as i128
                    })
                    .sum();
                ByteSpan {
                    start: (span.start as i128 + shift) as u64,
                    end: (span.end as i128 + shift) as u64,
                }
            };
            if !after.header.as_ref().is_some_and(|header| {
                header.opening == shifted(before_header.opening)
                    && header.closing == shifted(before_header.closing)
            }) {
                return Err(DocumentError::ProtectedMetadata);
            }
        }
        // Range checks alone cannot prevent inserting a new reserved key or
        // changing YAML structure around an untouched identity line.
        if self.reserved_signature() != after.reserved_signature()
            || self.reserved_values() != after.reserved_values()
        {
            return Err(DocumentError::ProtectedMetadata);
        }
        Ok(after)
    }

    fn reserved_values(&self) -> serde_yaml_ng::Mapping {
        self.metadata
            .iter()
            .filter(|(key, _)| key.as_str().is_some_and(is_reserved))
            .map(|(key, value)| (key.clone(), value.clone()))
            .collect()
    }

    fn reserved_signature(&self) -> Vec<(&str, &str)> {
        self.header
            .iter()
            .flat_map(|h| &h.entries)
            .filter(|entry| is_reserved(&entry.key))
            .map(|entry| {
                let raw = &self.text[entry.range.start as usize..entry.range.end as usize];
                // A removed neighbouring property may leave its trailing comments
                // after this entry. Range checks still protect the original bytes;
                // exclude trailing standalone trivia from the identity signature.
                let mut offset = 0;
                let mut content_end = 0;
                for line in raw.split_inclusive('\n') {
                    offset += line.len();
                    if !line.trim().is_empty() && !line.starts_with('#') {
                        content_end = offset;
                    }
                }
                (entry.key.as_str(), &raw[..content_end])
            })
            .collect()
    }
}

pub fn content_version(text: &str) -> String {
    format!("sha256:{}", hex::encode(Sha256::digest(text.as_bytes())))
}

fn is_reserved(key: &str) -> bool {
    key.to_ascii_lowercase().starts_with("gilune-")
}

/// Locate the envelope without normalising any bytes, including CRLF or BOM.
/// Only unindented mapping entries are exposed; continuation bytes stay with
/// their entry. Unknown nested values are retained without flattening.
fn source_header(text: &str) -> Result<Option<SourceHeader>, DocumentError> {
    let lines = physical_lines(text, 0);
    let Some(first) = lines.first() else {
        return Ok(None);
    };
    let opening_text = &text[first.start as usize..first.end as usize];
    if opening_text.trim_start_matches('\u{feff}').trim_end() != "---" {
        return Ok(None);
    }
    let close = lines
        .iter()
        .enumerate()
        .skip(1)
        .find(|(_, range)| text[range.start as usize..range.end as usize].trim_end() == "---")
        .map(|(index, _)| index)
        .ok_or(DocumentError::UnclosedHeader)?;
    let mut entries: Vec<MetadataEntry> = Vec::new();
    for line in &lines[1..close] {
        let raw = &text[line.start as usize..line.end as usize];
        if raw.starts_with([' ', '\t']) || raw.trim().is_empty() || raw.starts_with('#') {
            continue;
        }
        if let Some(previous) = entries.last_mut() {
            previous.range.end = line.start;
            previous.value_range.end = line.start;
        }
        let Some((key, _)) = raw.split_once(':') else {
            continue;
        };
        // Expose only an independently recognised block key. A colon inside
        // a quoted/plain key or flow mapping must not invent a different key
        // or a guessed value range. Reserved keys without a mapped entry make
        // the whole header protected in TextSnapshot::new.
        let colon = raw.find(':').expect("split_once found a colon");
        let tail = &raw[colon + 1..];
        if !tail.is_empty() && !tail.starts_with(char::is_whitespace) {
            continue;
        }
        let Ok(key) = serde_yaml_ng::from_str::<String>(key.trim()) else {
            continue;
        };
        entries.push(MetadataEntry {
            key,
            range: ByteSpan {
                start: line.start,
                end: lines[close].start,
            },
            value_range: ByteSpan {
                start: line.start + colon as u64 + 1,
                end: lines[close].start,
            },
        });
    }
    Ok(Some(SourceHeader {
        range: ByteSpan {
            start: 0,
            end: lines[close].end,
        },
        opening: *first,
        closing: lines[close],
        entries,
    }))
}

pub(crate) fn physical_lines(text: &str, start: u64) -> Vec<ByteSpan> {
    let mut at = start;
    let mut lines = Vec::new();
    for line in text[start as usize..].split_inclusive('\n') {
        let end = at + line.len() as u64;
        lines.push(ByteSpan { start: at, end });
        at = end;
    }
    // Match the legacy body's final empty line after a newline.
    if at == start || text.ends_with('\n') {
        lines.push(ByteSpan { start: at, end: at });
    }
    lines
}

/// Convert source coordinates without rounding into a multibyte character.
pub fn byte_to_utf16(text: &str, byte_offset: u64) -> Result<u64, DocumentError> {
    let prefix = ByteSpan {
        start: 0,
        end: byte_offset,
    }
    .slice(text)?;
    Ok(prefix.encode_utf16().count() as u64)
}

pub fn utf16_to_byte(text: &str, utf16_offset: u64) -> Result<u64, DocumentError> {
    let mut units = 0;
    for (byte, char) in text.char_indices() {
        if units == utf16_offset {
            return Ok(byte as u64);
        }
        units += char.len_utf16() as u64;
        if units > utf16_offset {
            return Err(DocumentError::CharacterBoundary);
        }
    }
    if units == utf16_offset {
        Ok(text.len() as u64)
    } else {
        Err(DocumentError::InvalidRange {
            start: utf16_offset,
            end: utf16_offset,
        })
    }
}

#[cfg(test)]
mod tests;

/// Explicit, pinned full non-Turkic folding followed by canonical composition.
/// Unicode case folding 9.0.0; NFC tables from unicode-normalization 0.1.24.
/// Callers own whether this equivalence is appropriate for their vocabulary.
pub fn casefold_nfc(value: &str) -> String {
    use unicode_casefold::UnicodeCaseFold;
    use unicode_normalization::UnicodeNormalization;
    value.nfc().case_fold().nfc().collect()
}

fn parse_metadata(
    text: &str,
    header: Option<&SourceHeader>,
) -> Result<serde_yaml_ng::Mapping, DocumentError> {
    Ok(match header {
        None => serde_yaml_ng::Mapping::new(),
        Some(header) => {
            let yaml = &text[header.opening.end as usize..header.closing.start as usize];
            let mut value: serde_yaml_ng::Value =
                serde_yaml_ng::from_str(yaml).map_err(|error| DocumentError::InvalidHeader {
                    detail: error.to_string(),
                })?;
            value
                .apply_merge()
                .map_err(|error| DocumentError::InvalidHeader {
                    detail: error.to_string(),
                })?;
            match value {
                serde_yaml_ng::Value::Null => serde_yaml_ng::Mapping::new(),
                serde_yaml_ng::Value::Mapping(mapping) => mapping,
                _ => {
                    return Err(DocumentError::InvalidHeader {
                        detail: "expected a mapping".into(),
                    })
                }
            }
        }
    })
}

/// Pure scalar access without hashing the body or building Markdown locations.
/// Aliases and merges use the same interpretation as editable snapshots.
pub fn source_properties(
    text: &str,
    keys: &[String],
) -> Result<Vec<(String, Option<String>)>, DocumentError> {
    let header = source_header(text)?;
    let metadata = parse_metadata(text, header.as_ref())?;
    use serde_yaml_ng::Value;
    Ok(keys
        .iter()
        .map(|key| match metadata.get(Value::String(key.clone())) {
            None => ("missing".into(), None),
            Some(Value::String(value)) => ("string".into(), Some(value.clone())),
            Some(Value::Bool(value)) => ("boolean".into(), Some(value.to_string())),
            Some(Value::Number(value)) => ("number".into(), Some(value.to_string())),
            _ => ("other".into(), None),
        })
        .collect())
}
