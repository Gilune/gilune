//! Exact source coordinates and proposed edits, shared by all plugin domains.
use crate::{FfiResult, Gilune, GiluneError};
use gilune_kernel::documents as core;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct SourceSpan {
    pub start: u64,
    pub end: u64,
}

impl From<core::ByteSpan> for SourceSpan {
    fn from(value: core::ByteSpan) -> Self {
        Self {
            start: value.start,
            end: value.end,
        }
    }
}
impl From<SourceSpan> for core::ByteSpan {
    fn from(value: SourceSpan) -> Self {
        Self {
            start: value.start,
            end: value.end,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SourceLocation {
    DocumentRoot,
    MarkdownItem { key: u64 },
    BodyLine { number: u32 },
}
impl From<core::Location> for SourceLocation {
    fn from(value: core::Location) -> Self {
        match value {
            core::Location::DocumentRoot => Self::DocumentRoot,
            core::Location::MarkdownItem { key } => Self::MarkdownItem { key },
            core::Location::BodyLine { number } => Self::BodyLine { number },
        }
    }
}
impl From<SourceLocation> for core::Location {
    fn from(value: SourceLocation) -> Self {
        match value {
            SourceLocation::DocumentRoot => Self::DocumentRoot,
            SourceLocation::MarkdownItem { key } => Self::MarkdownItem { key },
            SourceLocation::BodyLine { number } => Self::BodyLine { number },
        }
    }
}

/// Generic parsed frontmatter tree. Root is node zero; mapping children are
/// alternating key/value indices, sequences are value indices. Exact source
/// stays authoritative; this tree is solely for plugin interpretation.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceValueNode {
    pub kind: String,
    pub value: Option<String>,
    pub children: Vec<u32>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceLine {
    pub number: u32,
    pub range: SourceSpan,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceItem {
    pub key: u64,
    pub parent_key: Option<u64>,
    pub range: SourceSpan,
    pub own_ranges: Vec<SourceSpan>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceTextSnapshot {
    pub text: String,
    pub content_version: String,
    pub map_version: u32,
    pub body_start: u64,
    pub header_range: Option<SourceSpan>,
    pub protected_ranges: Vec<SourceSpan>,
    pub lines: Vec<SourceLine>,
    pub items: Vec<SourceItem>,
    pub text_ranges: Vec<SourceSpan>,
    pub metadata: Vec<SourceValueNode>,
}

impl From<&core::TextSnapshot> for SourceTextSnapshot {
    fn from(value: &core::TextSnapshot) -> Self {
        let layout = value.layout();
        Self {
            text: value.text().into(),
            content_version: value.version().into(),
            map_version: layout.version,
            body_start: layout.body_start,
            header_range: value.header().map(|header| header.range.into()),
            protected_ranges: value
                .protected_ranges()
                .iter()
                .copied()
                .map(Into::into)
                .collect(),
            lines: layout
                .lines
                .iter()
                .map(|line| SourceLine {
                    number: line.number,
                    range: line.range.into(),
                })
                .collect(),
            items: layout
                .items
                .iter()
                .map(|item| SourceItem {
                    key: item.key,
                    parent_key: item.parent_key,
                    range: item.range.into(),
                    own_ranges: item.own_ranges.iter().copied().map(Into::into).collect(),
                })
                .collect(),
            text_ranges: layout.text_ranges.iter().copied().map(Into::into).collect(),
            metadata: value.metadata_nodes().into_iter().map(|n| SourceValueNode { kind: n.kind, value: n.value, children: n.children }).collect(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DocumentState {
    Managed,
    Unmanaged,
    NeedsReconciliation,
    UnverifiedIdentity,
    Quarantined,
    Conflicted,
    Deleted,
    PendingDeletion,
}
impl From<core::DocumentState> for DocumentState {
    fn from(value: core::DocumentState) -> Self {
        match value {
            core::DocumentState::Managed => Self::Managed,
            core::DocumentState::Unmanaged => Self::Unmanaged,
            core::DocumentState::NeedsReconciliation => Self::NeedsReconciliation,
            core::DocumentState::UnverifiedIdentity => Self::UnverifiedIdentity,
            core::DocumentState::Quarantined => Self::Quarantined,
            core::DocumentState::Conflicted => Self::Conflicted,
            core::DocumentState::Deleted => Self::Deleted,
            core::DocumentState::PendingDeletion => Self::PendingDeletion,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DocumentSource {
    pub document_key: String,
    pub version: String,
    pub rel_path: String,
    pub object_id: Option<String>,
    pub revision_id: Option<String>,
    pub state: DocumentState,
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct DocumentSnapshot {
    pub source: DocumentSource,
    pub content: SourceTextSnapshot,
}
impl From<core::DocumentSnapshot> for DocumentSnapshot {
    fn from(value: core::DocumentSnapshot) -> Self {
        Self {
            content: (&value.content).into(),
            source: DocumentSource {
                document_key: value.source.document_key,
                version: value.source.version,
                rel_path: value.source.rel_path,
                object_id: value.source.object_id,
                revision_id: value.source.revision_id,
                state: value.source.state.into(),
            },
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceTextEdit {
    pub range: SourceSpan,
    pub replacement: String,
}
impl From<SourceTextEdit> for core::TextEdit {
    fn from(value: SourceTextEdit) -> Self {
        Self {
            range: value.range.into(),
            replacement: value.replacement,
        }
    }
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct PreparedDocumentChange {
    pub before: DocumentSnapshot,
    pub after: SourceTextSnapshot,
}

/// Pure buffer inspection: no store, domain registration or filesystem access.
#[uniffi::export]
pub fn inspect_source_text(text: String) -> FfiResult<SourceTextSnapshot> {
    Ok((&core::TextSnapshot::new(text).map_err(invalid)?).into())
}

/// Inspect a buffer and locate one caret in the same parse. Repeated per-token
/// lookup should use the returned disjoint item ranges rather than reparsing.
#[uniffi::export]
pub fn source_location_at(text: String, byte_offset: u64) -> FfiResult<SourceLocation> {
    Ok(core::TextSnapshot::new(text)
        .map_err(invalid)?
        .location_at(byte_offset)
        .map_err(invalid)?
        .into())
}

#[uniffi::export]
pub fn source_byte_to_utf16(text: String, byte_offset: u64) -> FfiResult<u64> {
    core::byte_to_utf16(&text, byte_offset).map_err(invalid)
}
#[uniffi::export]
pub fn source_utf16_to_byte(text: String, utf16_offset: u64) -> FfiResult<u64> {
    core::utf16_to_byte(&text, utf16_offset).map_err(invalid)
}

fn invalid(error: core::DocumentError) -> GiluneError {
    GiluneError::DocumentInvalid {
        detail: error.to_string(),
    }
}

#[uniffi::export]
impl Gilune {
    pub fn document_snapshot(&self, rel_path: String) -> FfiResult<DocumentSnapshot> {
        Ok(self.store().document_snapshot(&rel_path)?.into())
    }
    pub fn prepare_document_change(
        &self,
        rel_path: String,
        expected_source_version: String,
        edits: Vec<SourceTextEdit>,
    ) -> FfiResult<PreparedDocumentChange> {
        let edits: Vec<_> = edits.into_iter().map(Into::into).collect();
        let prepared =
            self.store()
                .prepare_document_change(&rel_path, &expected_source_version, &edits)?;
        Ok(PreparedDocumentChange {
            after: (&prepared.after).into(),
            before: prepared.before.into(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_contract_crosses_ffi_without_any_plugin() {
        let dir = tempfile::tempdir().unwrap();
        let raw = "- 😀 label=red\r\n  continuation\r\n  - child\r\n";
        std::fs::write(dir.path().join("fixture.md"), raw).unwrap();
        let g = Gilune::open(dir.path().to_string_lossy().into_owned()).unwrap();
        let snapshot = g.document_snapshot("fixture.md".into()).unwrap();
        assert_eq!(snapshot.source.state, DocumentState::Unmanaged);
        assert_eq!(snapshot.content.text, raw);
        assert_eq!(snapshot.content.items.len(), 2);
        let start = raw.find("red").unwrap() as u64;
        let edit = SourceTextEdit {
            range: SourceSpan {
                start,
                end: start + 3,
            },
            replacement: "blue".into(),
        };
        let prepared = g
            .prepare_document_change(
                "fixture.md".into(),
                snapshot.source.version.clone(),
                vec![edit.clone()],
            )
            .unwrap();
        assert_eq!(prepared.after.text, raw.replace("red", "blue"));
        assert_eq!(
            std::fs::read_to_string(dir.path().join("fixture.md")).unwrap(),
            raw
        );
        let units = source_byte_to_utf16(raw.into(), start).unwrap();
        assert_eq!(source_utf16_to_byte(raw.into(), units).unwrap(), start);
        assert_eq!(
            source_location_at(raw.into(), start).unwrap(),
            SourceLocation::MarkdownItem { key: 0 }
        );
        std::fs::write(dir.path().join("fixture.md"), "changed externally").unwrap();
        assert!(matches!(
            g.prepare_document_change("fixture.md".into(), snapshot.source.version, vec![edit]),
            Err(GiluneError::DocumentChanged)
        ));
    }
}

/// Scalar property access is pure over supplied source, including YAML aliases.
/// It never locks a Gilune handle and is safe inside a pure indexer callback.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceProperty {
    pub key: String,
    pub kind: String,
    pub value: Option<String>,
}

#[uniffi::export]
pub fn read_source_properties(text: String, keys: Vec<String>) -> FfiResult<Vec<SourceProperty>> {
    let properties = core::source_properties(&text, &keys).map_err(invalid)?;
    Ok(keys
        .into_iter()
        .zip(properties)
        .map(|(key, (kind, value))| SourceProperty { key, kind, value })
        .collect())
}

#[uniffi::export]
pub fn casefold_nfc(value: String) -> String {
    core::casefold_nfc(&value)
}

/// Standard property forms consume raw scalar spellings, never a flattened YAML map.
#[derive(Debug, Clone, uniffi::Record)]
pub struct EditableProperty {
    pub key: String,
    pub value_yaml: String,
    pub editable: bool,
    pub string_value: Option<String>,
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct PropertySheet {
    pub content_version: String,
    pub fields: Vec<EditableProperty>,
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct PropertyChange {
    pub key: String,
    pub value_yaml: Option<String>,
}

#[uniffi::export]
pub fn inspect_source_properties(text: String) -> FfiResult<PropertySheet> {
    let snapshot = core::TextSnapshot::new(text).map_err(invalid)?;
    let sheet = snapshot.property_sheet();
    Ok(PropertySheet {
        content_version: sheet.version,
        fields: sheet
            .fields
            .into_iter()
            .map(|field| EditableProperty {
                key: field.key,
                value_yaml: field.value_yaml,
                editable: field.editable,
                string_value: field.string_value,
            })
            .collect(),
    })
}

/// Pure proposed buffer change. This does not save a file or bypass edit authority.
#[uniffi::export]
pub fn change_source_properties(
    text: String,
    expected_version: String,
    changes: Vec<PropertyChange>,
) -> FfiResult<SourceTextSnapshot> {
    let snapshot = core::TextSnapshot::new(text).map_err(invalid)?;
    let changes: Vec<_> = changes
        .into_iter()
        .map(|change| core::PropertyChange {
            key: change.key,
            value_yaml: change.value_yaml,
        })
        .collect();
    let after = snapshot
        .change_properties(&expected_version, &changes)
        .map_err(invalid)?;
    Ok((&after).into())
}
