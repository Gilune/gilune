//! Lossless projection envelopes. Only kernel-owned scalar tokens are replaced.
//! Unknown YAML, comments, BOM and physical newlines remain source bytes.
use gilune_document::{ByteSpan, TextSnapshot};
use serde_yaml_ng::{Mapping, Value};

pub(super) const PAYLOAD_KEY: &str = "_gilune_front_matter";
const OWNED: &[&str] = &[
    "gilune-id",
    "gilune-type",
    "gilune-schema",
    "gilune-revision",
    "gilune-created-ms",
    "gilune-blob",
    "gilune-conflicted",
];

pub(super) struct Header {
    pub snapshot: TextSnapshot,
    values: Mapping,
    fields: Vec<Field>,
}
struct Field {
    key: String,
    token: ByteSpan,
    line: ByteSpan,
}

impl Header {
    pub fn parse(source: &str) -> Option<Self> {
        let snapshot = TextSnapshot::new(source.into()).ok()?;
        let header = snapshot.header()?;
        let yaml = &source[header.opening.end as usize..header.closing.start as usize];
        let values: Mapping = match serde_yaml_ng::from_str(yaml).ok()? {
            Value::Mapping(mapping) => mapping,
            Value::Null => Mapping::new(),
            _ => return None,
        };
        if values.contains_key(Value::String("<<".into())) {
            return None;
        }
        let mut fields = Vec::new();
        for key in OWNED {
            let Some(value) = values.get(Value::String((*key).into())) else {
                continue;
            };
            // A managed field must have an independent, single-line scalar.
            // Flow headers, merges, anchors, aliases and multiline managed
            // values are held back rather than normalised over unknown data.
            let entry = header.entries.iter().find(|entry| entry.key == *key)?;
            let rest = entry.value_range.slice(source).ok()?;
            let first = rest.split('\n').next()?.trim_end_matches('\r');
            let start = first.len() - first.trim_start().len();
            let scalar = &first[start..];
            if scalar.starts_with(['&', '*', '!', '|', '>', '{', '[']) {
                return None;
            }
            let end = scalar_end(scalar)?;
            let token_value: Value = serde_yaml_ng::from_str(&scalar[..end]).ok()?;
            if !matches!(
                token_value,
                Value::String(_) | Value::Number(_) | Value::Bool(_) | Value::Null
            ) || &token_value != value
                || rest.split('\n').skip(1).any(|line| {
                    let line = line.trim();
                    !line.is_empty() && !line.starts_with('#')
                })
            {
                return None;
            }
            let token_start = entry.value_range.start + start as u64;
            let line_end = source[entry.range.start as usize..]
                .find('\n')
                .map_or(entry.range.end, |n| entry.range.start + n as u64 + 1);
            fields.push(Field {
                key: (*key).into(),
                token: ByteSpan { start: token_start, end: token_start + end as u64 },
                line: ByteSpan { start: entry.range.start, end: line_end },
            });
        }
        // Inherited identity must never be mistaken for an absent identity.
        // The snapshot resolves YAML merges; our writable envelope doesn't.
        for key in OWNED {
            if snapshot.metadata_string(key).is_some()
                && !values.contains_key(Value::String((*key).into()))
            {
                return None;
            }
        }
        Some(Self { snapshot, values, fields })
    }

    pub fn from_payload(value: &serde_json::Value) -> Option<Self> {
        if value.get("version")?.as_u64()? != 1 {
            return None;
        }
        let source = value.get("source")?.as_str()?;
        let header = Self::parse(source)?;
        if !header.snapshot.body().is_empty() {
            return None;
        }
        Some(header)
    }

    pub fn payload(&self) -> serde_json::Value {
        serde_json::json!({ "version": 1, "source": self.raw() })
    }

    pub fn raw(&self) -> &str {
        self.snapshot.header().unwrap().range.slice(self.snapshot.text()).unwrap()
    }

    /// Overlay the authoritative fields from the legacy canonical envelope.
    /// It remains the renderer for old payloads without a retained envelope.
    pub fn render(&self, canonical: &str) -> Option<String> {
        let desired = Self::parse(canonical)?;
        let source = self.snapshot.text();
        let mut edits: Vec<(ByteSpan, String)> = Vec::new();
        for field in &self.fields {
            let key = Value::String(field.key.clone());
            match desired.values.get(&key) {
                Some(value) if self.values.get(&key) == Some(value) => {}
                Some(_) => {
                    let replacement = desired.fields.iter().find(|f| f.key == field.key)?;
                    edits.push((field.token, replacement.token.slice(canonical).ok()?.into()));
                }
                None => {
                    // Keep any comment after a removed optional kernel field.
                    let suffix = &source[field.token.end as usize..field.line.end as usize];
                    let replacement =
                        suffix.find('#').map_or(String::new(), |n| suffix[n..].into());
                    edits.push((field.line, replacement));
                }
            }
        }
        let newline = if self.snapshot.header()?.opening.slice(source).ok()?.ends_with("\r\n") {
            "\r\n"
        } else {
            "\n"
        };
        let mut append = String::new();
        for field in &desired.fields {
            if !self.fields.iter().any(|f| f.key == field.key) {
                append.push_str(&format!(
                    "{}: {}{}",
                    field.key,
                    field.token.slice(canonical).ok()?,
                    newline
                ));
            }
        }
        let at = self.snapshot.header()?.closing.start;
        edits.push((ByteSpan { start: at, end: at }, append));
        edits.sort_by_key(|(span, _)| span.start);
        let mut result = self.raw().to_string();
        for (span, replacement) in edits.into_iter().rev() {
            result.replace_range(span.start as usize..span.end as usize, &replacement);
        }
        // Check the complete YAML after updating tokens. No partial rendering.
        let rendered = Self::parse(&result)?;
        for key in OWNED {
            let key = Value::String((*key).into());
            if rendered.values.get(&key) != desired.values.get(&key) {
                return None;
            }
        }
        Some(result)
    }
}

/// End of a scalar before its trailing space/comment. Quoted strings may
/// contain # and YAML's doubled single quotes or escaped double quotes.
fn scalar_end(text: &str) -> Option<usize> {
    let mut quote = None;
    let mut chars = text.char_indices().peekable();
    while let Some((at, ch)) = chars.next() {
        match quote {
            Some('"') if ch == '\\' => {
                chars.next()?;
            }
            Some('\'') if ch == '\'' && chars.peek().is_some_and(|(_, c)| *c == '\'') => {
                chars.next();
            }
            Some(q) if ch == q => quote = None,
            Some(_) => {}
            None if at == 0 && matches!(ch, '\'' | '"') => quote = Some(ch),
            None if ch == '#' && (at == 0 || text[..at].ends_with(char::is_whitespace)) => {
                return Some(text[..at].trim_end().len())
            }
            None => {}
        }
    }
    if quote.is_some() {
        None
    } else {
        Some(text.trim_end().len())
    }
}
