use crate::{Constraint, FfiResult, Gilune};
use gilune_kernel::index::lookup as core;

#[derive(Debug, Clone, uniffi::Record)]
pub struct LookupAlternative {
    pub constraints: Vec<Constraint>,
    pub priority: u32,
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct LookupRequest {
    pub constraints: Vec<Constraint>,
    pub alternatives: Vec<LookupAlternative>,
    pub sort_predicates: Vec<String>,
    pub display_predicates: Vec<String>,
    pub required_namespaces: Vec<String>,
    pub page_size: u32,
    pub cursor: Option<String>,
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct LookupFact {
    pub predicate: String,
    pub value: String,
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct LookupHit {
    pub rel_path: String,
    pub source_version: String,
    pub priority: u32,
    pub facts: Vec<LookupFact>,
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct LookupPage {
    pub hits: Vec<LookupHit>,
    pub total_hits: u64,
    pub next_cursor: Option<String>,
    pub generation: String,
    pub index_complete: bool,
    pub issues: Vec<String>,
}
#[uniffi::export]
impl Gilune {
    pub fn lookup(&self, request: LookupRequest) -> FfiResult<LookupPage> {
        let page = self.store().lookup(&core::LookupRequest {
            constraints: request.constraints.into_iter().map(Into::into).collect(),
            alternatives: request
                .alternatives
                .into_iter()
                .map(|a| core::LookupAlternative {
                    constraints: a.constraints.into_iter().map(Into::into).collect(),
                    priority: a.priority,
                })
                .collect(),
            sort_predicates: request.sort_predicates,
            display_predicates: request.display_predicates,
            required_namespaces: request.required_namespaces,
            page_size: request.page_size,
            cursor: request.cursor,
        })?;
        Ok(LookupPage {
            hits: page
                .hits
                .into_iter()
                .map(|h| LookupHit {
                    rel_path: h.rel_path,
                    source_version: h.source_version,
                    priority: h.priority,
                    facts: h
                        .facts
                        .into_iter()
                        .map(|f| LookupFact {
                            predicate: f.predicate,
                            value: f.value,
                        })
                        .collect(),
                })
                .collect(),
            total_hits: page.total_hits,
            next_cursor: page.next_cursor,
            generation: page.generation,
            index_complete: page.index_complete,
            issues: page.issues,
        })
    }
}
