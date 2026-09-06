//! Typed search requests, indexed snapshots, and complete result pages.

use crate::{Constraint, FfiResult, Gilune};
use gilune_kernel::search as core;
use std::sync::Arc;

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchClause {
    pub constraints: Vec<Constraint>,
    pub negated: bool,
    pub case_sensitive: bool,
    pub residual: Option<u32>,
    pub never: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchRequest {
    pub clauses: Vec<SearchClause>,
    pub line_mode: bool,
    pub extension_candidates: bool,
    pub group_by_note: bool,
    pub body_needles: Vec<String>,
    pub name_needles: Vec<String>,
    pub sort_predicate: String,
    pub required_namespaces: Vec<String>,
    pub extension_key: String,
    pub page_size: u32,
    pub max_lines: u32,
    pub cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchProperty {
    pub key: String,
    pub value: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchDocument {
    pub rel_path: String,
    pub name: String,
    pub body: String,
    pub properties: Vec<SearchProperty>,
}

/// A plugin's pure predicate over indexed content. Must not re-enter Gilune
/// or access files; execution takes place on the querying worker.
#[uniffi::export(with_foreign)]
pub trait SearchExtension: Send + Sync {
    fn matches(&self, key: u32, document: SearchDocument) -> FfiResult<bool>;
    fn candidates(&self, document: SearchDocument) -> FfiResult<Vec<u32>>;
}

struct ExtensionAdapter(Arc<dyn SearchExtension>);
impl core::SearchExtension for ExtensionAdapter {
    fn candidates(&self, document: &core::SearchDocument) -> Result<Vec<u32>, String> {
        self.0
            .candidates(SearchDocument {
                rel_path: document.rel_path.clone(),
                name: document.name.clone(),
                body: document.body.clone(),
                properties: document
                    .properties
                    .iter()
                    .cloned()
                    .map(Into::into)
                    .collect(),
            })
            .map_err(|e| e.to_string())
    }
    fn matches(&self, key: u32, document: &core::SearchDocument) -> Result<bool, String> {
        self.0
            .matches(
                key,
                SearchDocument {
                    rel_path: document.rel_path.clone(),
                    name: document.name.clone(),
                    body: document.body.clone(),
                    properties: document
                        .properties
                        .iter()
                        .cloned()
                        .map(Into::into)
                        .collect(),
                },
            )
            .map_err(|error| error.to_string())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchRange {
    pub start: u32,
    pub end: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchLine {
    pub number: u32,
    pub text: String,
    pub ranges: Vec<SearchRange>,
    pub facts: Vec<SearchProperty>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchHit {
    pub facts: Vec<SearchProperty>,
    pub rel_path: String,
    pub name: String,
    pub name_matched: bool,
    pub lines: Vec<SearchLine>,
    pub items: Vec<SearchItem>,
    pub more_matches: u64,
    pub match_count: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SearchItem {
    pub key: u64,
    pub source_version: String,
    pub map_version: u32,
    pub range: crate::SourceSpan,
    pub own_ranges: Vec<crate::SourceSpan>,
    pub first_line: u32,
    pub text: String,
    pub ranges: Vec<SearchRange>,
    pub facts: Vec<SearchProperty>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchPage {
    pub hits: Vec<SearchHit>,
    pub total_hits: u64,
    pub next_cursor: Option<String>,
    pub index_complete: bool,
    pub issues: Vec<String>,
    pub generation: String,
}

/// A page can arrive before its exact, generation-bound count.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchSlice {
    pub hits: Vec<SearchHit>,
    pub total_hits: Option<u64>,
    pub next_cursor: Option<String>,
    pub index_complete: bool,
    pub issues: Vec<String>,
    pub generation: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchCount {
    pub total_hits: u64,
    pub index_complete: bool,
    pub issues: Vec<String>,
    pub generation: String,
}

#[derive(uniffi::Object)]
pub struct SearchQuery {
    query: core::SearchQuery,
    extension: Option<ExtensionAdapter>,
}

#[uniffi::export]
impl SearchQuery {
    pub fn cancel(&self) { self.query.cancel(); }

    pub fn page(&self, cursor: Option<String>) -> FfiResult<SearchSlice> {
        let slice = self.query.page(cursor, self.extension.as_ref().map(|e| e as &dyn core::SearchExtension))?;
        let page: SearchPage = core::SearchPage { hits: slice.hits, total_hits: 0,
            next_cursor: slice.next_cursor, index_complete: slice.index_complete,
            issues: slice.issues, generation: slice.generation }.into();
        Ok(SearchSlice { hits: page.hits, total_hits: slice.total_hits, next_cursor: page.next_cursor,
            index_complete: page.index_complete, issues: page.issues, generation: page.generation })
    }

    pub fn count(&self, generation: String) -> FfiResult<SearchCount> {
        let count = self.query.count(&generation, self.extension.as_ref().map(|e| e as &dyn core::SearchExtension))?;
        Ok(SearchCount { total_hits: count.total_hits, index_complete: count.index_complete,
            issues: count.issues, generation: count.generation })
    }
}

impl From<SearchRequest> for core::SearchRequest {
    fn from(request: SearchRequest) -> Self {
        Self {
            clauses: request
                .clauses
                .into_iter()
                .map(|c| core::SearchClause {
                    constraints: c.constraints.into_iter().map(Into::into).collect(),
                    negated: c.negated,
                    case_sensitive: c.case_sensitive,
                    residual: c.residual,
                    never: c.never,
                })
                .collect(),
            line_mode: request.line_mode,
            extension_candidates: request.extension_candidates,
            group_by_note: request.group_by_note,
            body_needles: request.body_needles,
            name_needles: request.name_needles,
            sort_predicate: request.sort_predicate,
            required_namespaces: request.required_namespaces,
            extension_key: request.extension_key,
            page_size: request.page_size,
            max_lines: request.max_lines,
            cursor: request.cursor,
        }
    }
}
impl From<core::SearchProperty> for SearchProperty {
    fn from(p: core::SearchProperty) -> Self {
        Self {
            key: p.key,
            value: p.value,
        }
    }
}
impl From<core::SearchPage> for SearchPage {
    fn from(page: core::SearchPage) -> Self {
        Self {
            hits: page
                .hits
                .into_iter()
                .map(|h| SearchHit {
                    facts: h.facts.into_iter().map(Into::into).collect(),
                    rel_path: h.rel_path,
                    name: h.name,
                    name_matched: h.name_matched,
                    more_matches: h.more_matches,
                    match_count: h.match_count,
                    items: h.items.into_iter().map(|i| SearchItem {
                        key:i.key,source_version:i.source_version,map_version:i.map_version,
                        range:i.range.into(),own_ranges:i.own_ranges.into_iter().map(Into::into).collect(),
                        first_line:i.first_line,text:i.text,ranges:i.ranges.into_iter().map(|r| SearchRange { start:r.start,end:r.end }).collect(),
                        facts:i.facts.into_iter().map(Into::into).collect(),
                    }).collect(),
                    lines: h
                        .lines
                        .into_iter()
                        .map(|l| SearchLine {
                            number: l.number,
                            text: l.text,
                            ranges: l
                                .ranges
                                .into_iter()
                                .map(|r| SearchRange {
                                    start: r.start,
                                    end: r.end,
                                })
                                .collect(),
                            facts: l.facts.into_iter().map(Into::into).collect(),
                        })
                        .collect(),
                })
                .collect(),
            total_hits: page.total_hits,
            next_cursor: page.next_cursor,
            index_complete: page.index_complete,
            issues: page.issues,
            generation: page.generation,
        }
    }
}

#[uniffi::export]
impl Gilune {
    pub fn prepare_search(&self, request: SearchRequest, item_mode: bool, extension: Option<Arc<dyn SearchExtension>>) -> FfiResult<Arc<SearchQuery>> {
        Ok(Arc::new(SearchQuery { query: self.store().prepare_search(request.into(), item_mode)?, extension: extension.map(ExtensionAdapter) }))
    }
    pub fn search_items(&self, request: SearchRequest, extension: Option<Arc<dyn SearchExtension>>) -> FfiResult<SearchPage> {
        let adapter=extension.map(ExtensionAdapter);
        Ok(self.store().search_items(&request.into(),adapter.as_ref().map(|e| e as &dyn core::SearchExtension))?.into())
    }
    /// One bounded page from an index snapshot. Errors propagate; an index
    /// behind its feed returns explicit issues alongside any available results.
    pub fn search(
        &self,
        request: SearchRequest,
        extension: Option<Arc<dyn SearchExtension>>,
    ) -> FfiResult<SearchPage> {
        let adapter = extension.map(ExtensionAdapter);
        let page = self.store().search(
            &request.into(),
            adapter.as_ref().map(|e| e as &dyn core::SearchExtension),
        )?;
        Ok(page.into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn the_boundary_returns_cross_line_results_with_offsets_and_paging() {
        let dir = tempfile::tempdir().expect("temp");
        let g = Gilune::open(dir.path().to_string_lossy().into_owned()).expect("open");
        g.capture_text("alpha\nbeta".into()).expect("capture");
        g.project().expect("project");
        let request = SearchRequest {
            clauses: ["alpha", "beta"]
                .iter()
                .map(|q| SearchClause {
                    constraints: vec![Constraint::Text {
                        target: crate::TextTarget::Any,
                        query: q.to_string(),
                    }],
                    negated: false,
                    case_sensitive: false,
                    residual: None,
                    never: false,
                })
                .collect(),
            line_mode: false,
            extension_candidates: false,
            group_by_note: true,
            body_needles: vec!["beta".into()],
            name_needles: vec![],
            sort_predicate: String::new(),
            required_namespaces: vec![],
            extension_key: String::new(),
            page_size: 10,
            max_lines: 5,
            cursor: None,
        };
        let page = g.search(request, None).expect("search");
        assert_eq!(page.total_hits, 1);
        assert!(page.index_complete);
        assert_eq!(page.hits[0].lines[0].number, 2);
        assert_eq!(page.hits[0].lines[0].ranges[0].end, 4);
    }
    #[test]
    fn scoped_membership_survives_the_public_boundary_and_clause_negation() {
        let dir = tempfile::tempdir().expect("temp");
        let g = Gilune::open(dir.path().to_string_lossy().into_owned()).expect("open");
        std::fs::write(
            dir.path().join("fixture.md"),
            "---\ngilune-id: fixture\ncolour: red\n---\nfirst\nsecond",
        )
        .expect("write");
        g.reconcile().expect("reconcile");
        let mut request = SearchRequest {
            clauses: vec![SearchClause {
                constraints: vec![Constraint::FactIn {
                    predicate: "frontmatter.colour".into(),
                    values: vec!["red".into(), "blue".into()],
                    locality: crate::FactLocality::Exact,
                }],
                negated: false,
                case_sensitive: false,
                residual: None,
                never: false,
            }],
            line_mode: true,
            extension_candidates: false,
            group_by_note: false,
            body_needles: vec![],
            name_needles: vec![],
            sort_predicate: String::new(),
            required_namespaces: vec![],
            extension_key: String::new(),
            page_size: 10,
            max_lines: 5,
            cursor: None,
        };
        assert_eq!(
            g.search(request.clone(), None)
                .expect("exact location")
                .total_hits,
            0
        );
        request.clauses[0].constraints = vec![Constraint::FactIn {
            predicate: "frontmatter.colour".into(),
            values: vec!["red".into()],
            locality: crate::FactLocality::DocumentRoot,
        }];
        assert_eq!(
            g.search(request.clone(), None)
                .expect("root filter")
                .total_hits,
            2
        );
        request.clauses[0].negated = true;
        assert_eq!(
            g.search(request.clone(), None).expect("NOT IN").total_hits,
            0
        );
        request.clauses[0].constraints = vec![Constraint::FactAt {
            predicate: "frontmatter.colour".into(),
            op: crate::ConstraintOp::Eq,
            value: "blue".into(),
            locality: crate::FactLocality::DocumentRoot,
        }];
        assert_eq!(g.search(request, None).expect("NOT equality").total_hits, 2);
    }
}
