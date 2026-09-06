//! Conservative scalar editing over exact source; no file access or domain schema.
use super::*;
use serde_yaml_ng::Value;
use std::collections::BTreeSet;

#[derive(Debug, Clone)]
pub struct PropertyField {
    pub key: String,
    pub value_yaml: String,
    pub editable: bool,
    pub string_value: Option<String>,
}
#[derive(Debug, Clone)]
pub struct PropertyChange {
    pub key: String,
    pub value_yaml: Option<String>,
}
#[derive(Debug, Clone)]
pub struct PropertySheet {
    pub version: String,
    pub fields: Vec<PropertyField>,
}

fn invalid(detail: &str) -> DocumentError {
    DocumentError::InvalidHeader {
        detail: detail.into(),
    }
}
fn scalar(raw: &str) -> Result<Value, DocumentError> {
    if raw.contains(['\r', '\n']) || raw.trim_start().starts_with(['&', '*', '!']) {
        return Err(invalid(
            "Use a single-line scalar value; anchors and tags require source editing",
        ));
    }
    let value: Value = serde_yaml_ng::from_str(raw).map_err(|e| invalid(&e.to_string()))?;
    if matches!(
        value,
        Value::Null | Value::Bool(_) | Value::Number(_) | Value::String(_)
    ) {
        Ok(value)
    } else {
        Err(invalid("Structured YAML requires source editing"))
    }
}
fn simple_key(key: &str) -> bool {
    let mut chars = key.chars();
    chars.next().is_some_and(|c| c.is_alphabetic() || c == '_')
        && chars.all(|c| c.is_alphanumeric() || matches!(c, '_' | '-' | '.'))
}

impl TextSnapshot {
    // Locate only a physical, independently editable scalar. Never flatten aliases,
    // flow mappings, nested structures, multiline strings or kernel-owned ranges.
    fn property_token(&self, key: &str) -> Option<(ByteSpan, ByteSpan)> {
        if is_reserved(key) {
            return None;
        }
        let entry = self
            .header
            .as_ref()?
            .entries
            .iter()
            .find(|e| e.key == key)?;
        let expected = self.metadata.get(Value::String(key.into()))?;
        let raw = entry.range.slice(&self.text).ok()?;
        let first = raw.split_inclusive('\n').next()?;
        let rest = &raw[first.len()..];
        if !rest
            .lines()
            .all(|line| line.trim().is_empty() || line.trim_start().starts_with('#'))
        {
            return None;
        }
        let line_end = entry.range.start + first.len() as u64;
        let tail = &self.text[entry.value_range.start as usize..line_end as usize];
        let without_newline = tail.trim_end_matches(['\r', '\n']);
        let start = without_newline.len() - without_newline.trim_start_matches([' ', '\t']).len();
        let value = &without_newline[start..];
        let mut ends: Vec<usize> = value
            .char_indices()
            .filter_map(|(i, c)| {
                (c == '#' && (i == 0 || value[..i].ends_with([' ', '\t']))).then_some(i)
            })
            .collect();
        ends.push(value.len());
        for end in ends {
            let token = value[..end].trim_end_matches([' ', '\t']);
            if scalar(token).ok().as_ref() != Some(expected) {
                continue;
            }
            let range = ByteSpan {
                start: entry.value_range.start + start as u64,
                end: entry.value_range.start + start as u64 + token.len() as u64,
            };
            if self
                .protected
                .iter()
                .any(|p| p.start < range.end && range.start < p.end)
            {
                return None;
            }
            return Some((
                range,
                ByteSpan {
                    start: entry.range.start,
                    end: line_end,
                },
            ));
        }
        None
    }

    pub fn property_sheet(&self) -> PropertySheet {
        let mut keys: Vec<String> = self
            .header
            .iter()
            .flat_map(|h| h.entries.iter().map(|e| e.key.clone()))
            .collect();
        for key in self.metadata.keys().filter_map(Value::as_str) {
            if !keys.iter().any(|held| held == key) {
                keys.push(key.into());
            }
        }
        let fields = keys
            .into_iter()
            .filter_map(|key| {
                let value = self.metadata.get(Value::String(key.clone()))?;
                let token = self.property_token(&key);
                let raw = match token {
                    Some((range, _)) => range.slice(&self.text).unwrap().into(),
                    None => serde_yaml_ng::to_string(value)
                        .unwrap_or_default()
                        .trim_end()
                        .into(),
                };
                Some(PropertyField {
                    key,
                    value_yaml: raw,
                    editable: token.is_some(),
                    string_value: value.as_str().map(String::from),
                })
            })
            .collect();
        PropertySheet {
            version: self.version.clone(),
            fields,
        }
    }

    /// Pure proposed buffer edit. Hosts still guard the open buffer and disk save.
    pub fn change_properties(
        &self,
        expected_version: &str,
        changes: &[PropertyChange],
    ) -> Result<Self, DocumentError> {
        if expected_version != self.version {
            return Err(DocumentError::VersionChanged);
        }
        let mut keys = BTreeSet::new();
        let mut edits = Vec::new();
        let mut additions = String::new();
        let newline = if self
            .header
            .as_ref()
            .is_some_and(|h| h.opening.slice(&self.text).unwrap().ends_with("\r\n"))
        {
            "\r\n"
        } else {
            "\n"
        };
        for change in changes {
            if !keys.insert(&change.key) {
                return Err(invalid("Duplicate property change"));
            }
            if is_reserved(&change.key) {
                return Err(DocumentError::ProtectedMetadata);
            }
            if let Some(value) = &change.value_yaml {
                scalar(value)?;
            }
            if self
                .metadata
                .contains_key(Value::String(change.key.clone()))
            {
                let (token, line) = self
                    .property_token(&change.key)
                    .ok_or_else(|| invalid("This property requires source editing"))?;
                match &change.value_yaml {
                    Some(value) if token.slice(&self.text)? == value => {}
                    Some(value) => {
                        let replacement = if token.start == token.end
                            && self.text[token.end as usize..].starts_with('#')
                        {
                            format!("{value} ")
                        } else {
                            value.clone()
                        };
                        edits.push(TextEdit {
                            range: token,
                            replacement,
                        });
                    }
                    None => edits.push(TextEdit {
                        range: line,
                        replacement: String::new(),
                    }),
                }
            } else if let Some(value) = &change.value_yaml {
                if !simple_key(&change.key) {
                    return Err(invalid("Use a property name starting with a letter or underscore, then letters, digits, dots, underscores or hyphens"));
                }
                additions.push_str(&format!("{}: {}{}", change.key, value, newline));
            } else {
                return Err(invalid("Property no longer exists"));
            }
        }
        if !additions.is_empty() {
            let (at, replacement) = match &self.header {
                Some(header) => (header.closing.start, additions),
                None => (
                    if self.text.starts_with('\u{feff}') {
                        3
                    } else {
                        0
                    },
                    format!("---{newline}{additions}---{newline}"),
                ),
            };
            edits.push(TextEdit {
                range: ByteSpan { start: at, end: at },
                replacement,
            });
        }
        let after = self.propose(expected_version, &edits)?;
        let body = if self.header.is_none() {
            self.body().strip_prefix('\u{feff}').unwrap_or(self.body())
        } else {
            self.body()
        };
        if after.body() != body && !edits.is_empty() {
            return Err(invalid("Property changes cannot alter the document body"));
        }
        for change in changes {
            let expected = change.value_yaml.as_deref().map(scalar).transpose()?;
            if after.metadata.get(Value::String(change.key.clone())) != expected.as_ref() {
                return Err(invalid(
                    "Property change would have a different YAML interpretation",
                ));
            }
        }
        Ok(after)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn change(key: &str, value: Option<&str>) -> PropertyChange {
        PropertyChange {
            key: key.into(),
            value_yaml: value.map(String::from),
        }
    }
    #[test]
    fn editing_scalar_preserves_comments_unknown_yaml_identity_and_body() {
        let raw = "\u{feff}---\r\n# intro\r\ngilune-id: abc # identity\r\nname: 'Alice # Smith'  # label\r\n# keep this\r\nnested:\r\n  list: [a, b]\r\nretired: false\r\n---\r\n😀 body\r\n";
        let before = TextSnapshot::new(raw.into()).unwrap();
        let fields = before.property_sheet().fields;
        assert!(!fields.iter().find(|f| f.key == "nested").unwrap().editable);
        assert!(
            !fields
                .iter()
                .find(|f| f.key == "gilune-id")
                .unwrap()
                .editable
        );
        let after = before
            .change_properties(
                before.version(),
                &[
                    change("name", Some("'Alice # Jones'")),
                    change("retired", Some("true")),
                ],
            )
            .unwrap();
        assert_eq!(
            after.text(),
            raw.replace("'Alice # Smith'", "'Alice # Jones'")
                .replace("retired: false", "retired: true")
        );
        let removed = after
            .change_properties(after.version(), &[change("name", None)])
            .unwrap();
        assert!(removed.text().contains("# keep this\r\nnested:"));
    }
    #[test]
    fn additions_empty_values_quotes_and_no_header_preserve_bytes() {
        for raw in [
            "body\r\n",
            "\u{feff}body\r\n",
            "---\ngilune-id: abc\n---\nbody\r\n",
        ] {
            let before = TextSnapshot::new(raw.into()).unwrap();
            let after = before
                .change_properties(
                    before.version(),
                    &[change("name", Some("'true'")), change("empty", Some(""))],
                )
                .unwrap();
            assert_eq!(after.metadata_string("name"), Some("true"));
            assert!(after.body().ends_with("body\r\n"));
            assert!(
                after
                    .property_sheet()
                    .fields
                    .iter()
                    .find(|f| f.key == "empty")
                    .unwrap()
                    .editable
            );
        }
    }
    #[test]
    fn stale_reserved_structural_and_alias_changes_are_refused() {
        let before = TextSnapshot::new("---\ngilune-id: abc\nbase: &base {name: Alice}\n<<: *base\nlist: [a,b]\nname2: Bob\n---\nbody".into()).unwrap();
        assert!(before.change_properties("stale", &[]).is_err());
        for c in [
            change("gilune-id", Some("forged")),
            change("Gilune-new", Some("x")),
            change("name", Some("Other")),
            change("base", None),
            change("list", Some("x")),
            change("name2", Some("x\n---\ninjected")),
            change("name2", Some("&anchor x")),
        ] {
            assert!(before.change_properties(before.version(), &[c]).is_err());
        }
        assert!(before
            .change_properties(
                before.version(),
                &[change("new", Some("a")), change("new", Some("b"))]
            )
            .is_err());
    }
    #[test]
    fn comments_inside_quoted_scalars_and_empty_values_are_not_comment_delimiters() {
        let before = TextSnapshot::new(
            "---\na: \"quoted # value\" # comment\nb: 'it''s # quoted'\nc: # blank\n---\n".into(),
        )
        .unwrap();
        let sheet = before.property_sheet();
        assert_eq!(sheet.fields[0].value_yaml, "\"quoted # value\"");
        assert_eq!(sheet.fields[1].value_yaml, "'it''s # quoted'");
        assert_eq!(sheet.fields[2].value_yaml, "");
        let after = before
            .change_properties(before.version(), &[change("c", Some("true"))])
            .unwrap();
        assert!(after.text().contains("c: true # blank"));
    }
}
