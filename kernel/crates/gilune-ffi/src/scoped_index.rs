//! Source-qualified indexing and discovery shared by all plugin domains.
use crate::{
    Constraint, FfiResult, Gilune, PredicateKind, SourceLocation, SourceSpan, SourceTextSnapshot,
};
use gilune_kernel::index::{self, scoped as core};
use std::sync::Arc;

#[derive(Debug, Clone, uniffi::Record)]
pub struct ScopedFact {
    pub location: SourceLocation,
    pub predicate: String,
    pub value: String,
}

/// Pure callback over one immutable source and the shared Markdown/YAML maps.
/// No Gilune handle or filesystem capability is supplied.
#[uniffi::export(with_foreign)]
pub trait ScopedIndexer: Send + Sync {
    fn index(&self, rel_path: String, source: SourceTextSnapshot) -> FfiResult<Vec<ScopedFact>>;
}

/// Opt-in location-local derivation. Return only facts at selected locations.
/// Values may depend on their own source ranges, parser text eligibility and
/// document metadata; not on other locations, absolute offsets or revision IDs.
/// The kernel may reuse and relocate successful output when those inputs match.
/// Root facts depend on metadata only. Item facts use own ranges, excluding
/// children. Line facts may use that line's item membership and text eligibility.
#[uniffi::export(with_foreign)]
pub trait LocalScopedIndexer: Send + Sync {
    fn index_locations(
        &self,
        rel_path: String,
        source: SourceTextSnapshot,
        locations: Vec<SourceLocation>,
    ) -> FfiResult<Vec<ScopedFact>>;
}

struct LocalAdapter(Arc<dyn LocalScopedIndexer>);
impl index::NoteIndexer for LocalAdapter {
    fn index(&self, _: &str, _: &str) -> Result<Vec<index::Fact>, String> {
        Err("scoped source input required".into())
    }
    fn uses_scoped_input(&self) -> bool {
        true
    }
    fn index_scoped_locations(
        &self,
        path: &str,
        source: &gilune_kernel::documents::TextSnapshot,
        locations: &[gilune_kernel::documents::Location],
    ) -> Result<Vec<core::ScopedFact>, String> {
        self.index_scoped_locations_with_history(path, source, locations, &[])
    }
    fn index_scoped_locations_with_history(
        &self,
        path: &str,
        source: &gilune_kernel::documents::TextSnapshot,
        locations: &[gilune_kernel::documents::Location],
        history: &[Option<i64>],
    ) -> Result<Vec<core::ScopedFact>, String> {
        self.0
            .index_locations(
                path.into(),
                SourceTextSnapshot::with_history(source, history),
                locations.iter().cloned().map(Into::into).collect(),
            )
            .map(|facts| {
                facts
                    .into_iter()
                    .map(|f| core::ScopedFact {
                        location: f.location.into(),
                        predicate: f.predicate,
                        value: f.value,
                    })
                    .collect()
            })
            .map_err(|e| e.to_string())
    }
}

struct Adapter(Arc<dyn ScopedIndexer>);
impl index::NoteIndexer for Adapter {
    fn index(&self, _: &str, _: &str) -> Result<Vec<index::Fact>, String> {
        Err("scoped source input required".into())
    }
    fn uses_scoped_input(&self) -> bool {
        true
    }
    fn index_scoped(
        &self,
        rel_path: &str,
        source: &gilune_kernel::documents::TextSnapshot,
    ) -> Result<Vec<core::ScopedFact>, String> {
        self.index_scoped_with_history(rel_path, source, &[])
    }
    fn index_scoped_with_history(
        &self,
        rel_path: &str,
        source: &gilune_kernel::documents::TextSnapshot,
        history: &[Option<i64>],
    ) -> Result<Vec<core::ScopedFact>, String> {
        let wire = {
            let _profile = gilune_kernel::update_profile::span("snapshot_to_wire", "");
            SourceTextSnapshot::with_history(source, history)
        };
        let facts = {
            let _profile = gilune_kernel::update_profile::span("ffi_callback_roundtrip", "");
            self.0
                .index(rel_path.into(), wire)
                .map_err(|e| e.to_string())?
        };
        let _profile = gilune_kernel::update_profile::span("wire_facts_to_native", "");
        Ok(facts
            .into_iter()
            .map(|f| core::ScopedFact {
                location: f.location.into(),
                predicate: f.predicate,
                value: f.value,
            })
            .collect())
    }
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum LocationKind {
    Any,
    DocumentRoot,
    BodyLine,
    MarkdownItem,
}
impl From<LocationKind> for core::LocationKind {
    fn from(value: LocationKind) -> Self {
        match value {
            LocationKind::Any => Self::Any,
            LocationKind::DocumentRoot => Self::DocumentRoot,
            LocationKind::BodyLine => Self::BodyLine,
            LocationKind::MarkdownItem => Self::MarkdownItem,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LocationQuery {
    pub constraints: Vec<Constraint>,
    pub kind: LocationKind,
    pub required_namespaces: Vec<String>,
    pub page_size: u32,
    pub cursor: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LocatedMatch {
    pub rel_path: String,
    pub source_version: String,
    pub map_version: u32,
    pub location: SourceLocation,
    pub range: SourceSpan,
    pub own_ranges: Vec<SourceSpan>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LocationPage {
    pub matches: Vec<LocatedMatch>,
    pub next_cursor: Option<String>,
    pub index_complete: bool,
    pub issues: Vec<String>,
    pub generation: String,
}

#[uniffi::export]
impl Gilune {
    pub fn register_local_scoped_indexer(
        &self,
        namespace: String,
        version: i64,
        predicate_kinds: Vec<PredicateKind>,
        indexer: Arc<dyn LocalScopedIndexer>,
    ) -> FfiResult<()> {
        let kinds: Vec<_> = predicate_kinds.into_iter().map(Into::into).collect();
        Ok(self.store().register_indexer(
            &namespace,
            version,
            &kinds,
            index::incremental::local(Arc::new(LocalAdapter(indexer))),
        )?)
    }
    pub fn register_scoped_indexer(
        &self,
        namespace: String,
        version: i64,
        predicate_kinds: Vec<PredicateKind>,
        indexer: Arc<dyn ScopedIndexer>,
    ) -> FfiResult<()> {
        let kinds: Vec<_> = predicate_kinds.into_iter().map(Into::into).collect();
        Ok(self.store().register_indexer(
            &namespace,
            version,
            &kinds,
            Arc::new(Adapter(indexer)),
        )?)
    }

    pub fn query_locations(&self, request: LocationQuery) -> FfiResult<LocationPage> {
        let page = self.store().query_locations(&core::LocationQuery {
            constraints: request.constraints.into_iter().map(Into::into).collect(),
            kind: request.kind.into(),
            required_namespaces: request.required_namespaces,
            page_size: request.page_size,
            cursor: request.cursor,
        })?;
        Ok(LocationPage {
            matches: page
                .matches
                .into_iter()
                .map(|m| LocatedMatch {
                    rel_path: m.rel_path,
                    source_version: m.source_version,
                    map_version: m.map_version,
                    location: m.location.into(),
                    range: m.range.into(),
                    own_ranges: m.own_ranges.into_iter().map(Into::into).collect(),
                })
                .collect(),
            next_cursor: page.next_cursor,
            index_complete: page.index_complete,
            issues: page.issues,
            generation: page.generation,
        })
    }
}
