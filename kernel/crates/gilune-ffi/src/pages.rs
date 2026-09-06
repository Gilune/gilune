//! Typed-page host boundary. A host binds plugin identity before supplying a service.
use crate::{FfiResult, Gilune};
use gilune_kernel::pages as core;
#[derive(Debug, Clone, uniffi::Record)]
pub struct PageDefinition {
    pub type_id: String,
    pub label: String,
    pub schema: i64,
    pub default_folder: String,
    pub template: String,
    pub version: String,
    pub defaults_type_id: Option<String>,
    pub defaults_path: Option<String>,
    pub defaults_problem: Option<String>,
}
impl From<core::PageDefinition> for PageDefinition {
    fn from(d: core::PageDefinition) -> Self {
        Self {
            type_id: d.type_id,
            label: d.label,
            schema: d.schema,
            default_folder: d.default_folder,
            template: d.template,
            version: d.version,
            defaults_type_id: d.defaults_type_id,
            defaults_path: d.defaults_path,
            defaults_problem: d.defaults_problem,
        }
    }
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct CreatePageRequest {
    pub type_id: String,
    pub definition_version: String,
    pub request_id: String,
    pub rel_path: String,
    pub source: String,
}
#[derive(Debug, Clone, uniffi::Record)]
pub struct PageReceipt {
    pub object_id: String,
    pub revision_id: String,
    pub rel_path: String,
}
#[uniffi::export]
impl Gilune {
    pub fn initialize_pages(&self, plugin_id: String) -> FfiResult<Vec<PageInitialization>> {
        Ok(self.store().initialize_pages(&plugin_id)?.into_iter().map(|r| PageInitialization {
            initializer_id: r.initializer_id, status: r.status,
        }).collect())
    }
    pub fn page_definitions(&self, plugin_id: String) -> FfiResult<Vec<PageDefinition>> {
        Ok(self.store().page_definitions(&plugin_id)?.into_iter().map(Into::into).collect())
    }
    pub fn create_page(
        &self,
        plugin_id: String,
        request: CreatePageRequest,
    ) -> FfiResult<PageReceipt> {
        let receipt = self.store().create_page(
            &plugin_id,
            &core::CreatePage {
                type_id: request.type_id,
                definition_version: request.definition_version,
                request_id: request.request_id,
                rel_path: request.rel_path,
                source: request.source,
            },
        )?;
        Ok(PageReceipt {
            object_id: receipt.object_id,
            revision_id: receipt.revision_id,
            rel_path: receipt.rel_path,
        })
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PageInitialization {
    pub initializer_id: String,
    pub status: String,
}
