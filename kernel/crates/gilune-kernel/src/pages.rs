//! User-authored typed pages. Domain definitions remain plugin contributions;
//! the kernel owns creation authority, identity, revision and operation recording.
use crate::{ids, operations, plugins::PluginState, store::Store, KernelError, Result};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};

pub const TYPE_SLOT: &str = "pages.types";
pub const CREATE_CAPABILITY: &str = "pages.create";
pub const CREATED_EVENT: &str = "org.memory.page-created";
pub(crate) const PAYLOAD_KEY: &str = "_gilune_page";

#[derive(Debug, Clone, PartialEq, Eq)]
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
#[derive(Debug, Clone)]
pub struct CreatePage {
    pub type_id: String,
    pub definition_version: String,
    /// A fresh caller-generated UUID, reused only when retrying this request.
    pub request_id: String,
    pub rel_path: String,
    /// Full editable Markdown, without kernel-owned front matter.
    pub source: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PageReceipt {
    pub object_id: String,
    pub revision_id: String,
    pub rel_path: String,
}
fn invalid(detail: impl Into<String>) -> KernelError {
    KernelError::DocumentInvalid { detail: detail.into() }
}
fn unavailable(detail: impl Into<String>) -> KernelError {
    KernelError::DocumentUnavailable { detail: detail.into() }
}

impl Store {
    /// Resolves the supplied plugin's declarations and current defaults revisions. Namespace ownership
    /// is structural: `plugin-id/local-type`, with no slash in either component.
    pub fn page_definitions(&self, plugin_id: &str) -> Result<Vec<PageDefinition>> {
        let plugin =
            self.plugin(plugin_id)?.ok_or_else(|| unavailable("page plugin is unavailable"))?;
        if plugin.state != PluginState::Enabled {
            return Err(unavailable("page plugin is disabled"));
        }
        if plugin_id.is_empty()
            || !plugin_id.bytes().all(|c| c.is_ascii_alphanumeric() || b".-_".contains(&c))
        {
            return Err(invalid("invalid page plugin namespace"));
        }
        let mut result = Vec::new();
        for contribution in plugin.descriptor.contributions.iter().filter(|c| c.slot == TYPE_SLOT) {
            contribution
                .id
                .strip_prefix(&format!("{plugin_id}/"))
                .filter(|s| {
                    !s.is_empty()
                        && s.bytes().all(|c| c.is_ascii_alphanumeric() || b"-_".contains(&c))
                })
                .ok_or_else(|| {
                    invalid("a page type must belong to its declaring plugin namespace")
                })?;
            if result.iter().any(|d: &PageDefinition| d.type_id == contribution.id) {
                return Err(invalid("duplicate page type declaration"));
            }
            let config: Value =
                serde_json::from_str(&contribution.config).map_err(|e| invalid(e.to_string()))?;
            if config["version"].as_u64() != Some(1) {
                return Err(invalid("unsupported page definition version"));
            }
            let schema = config["schema"]
                .as_i64()
                .filter(|v| *v > 0)
                .ok_or_else(|| invalid("page schema must be positive"))?;
            let folder = config["default_folder"]
                .as_str()
                .ok_or_else(|| invalid("missing default folder"))?;
            portable_folder(folder)?;
            let template =
                config["template"].as_str().ok_or_else(|| invalid("missing creation template"))?;
            crate::vault::new_page_payload(template)?;
            result.push(PageDefinition {
                type_id: contribution.id.clone(),
                label: contribution.label.clone(),
                schema,
                default_folder: folder.into(),
                template: template.into(),
                version: ids::sha256_hex(
                    json!([contribution.id, contribution.label, config]).to_string().as_bytes(),
                ),
                defaults_type_id: None,
                defaults_path: None,
                defaults_problem: None,
            });
        }
        self.resolve_page_defaults(&plugin.descriptor.contributions, &mut result)?;
        Ok(result)
    }

    /// Commit a new page, without editing any existing object or file. The
    /// receipt means durable, not published: the normal projection pass publishes
    /// it, or reports a held-back path collision. Retrying returns the same receipt.
    pub fn create_page(&self, plugin_id: &str, request: &CreatePage) -> Result<PageReceipt> {
        self.write_txn(|| self.create_page_in_txn(plugin_id, request))
    }

    fn create_page_in_txn(&self, plugin_id: &str, request: &CreatePage) -> Result<PageReceipt> {
        let rel_path = portable_path(&request.rel_path)?;
        let request_id = uuid::Uuid::parse_str(&request.request_id)
            .map_err(|_| invalid("request id must be a UUID"))?
            .to_string();
        if request.source.len() > crate::limits::Limits::DEFAULT.max_envelope_bytes {
            return Err(invalid("page source exceeds the default sync envelope budget"));
        }
        let fingerprint = ids::sha256_hex(
            json!([
                plugin_id,
                request.type_id,
                request.definition_version,
                rel_path,
                request.source
            ])
            .to_string()
            .as_bytes(),
        );
        let device = self.device_id()?;
        let object_id =
            deterministic_id(&json!(["page", device, plugin_id, request_id]).to_string());
        let revision_id = deterministic_id(&format!("page-revision:{object_id}"));
        let receipt = PageReceipt {
            object_id: object_id.clone(),
            revision_id: revision_id.clone(),
            rel_path: rel_path.clone(),
        };
        {
            // Recheck lifecycle/grants/definition inside the same transaction.
            let plugin = self.plugin(plugin_id)?.ok_or_else(|| unavailable("page plugin is unavailable"))?;
            if plugin.state != PluginState::Enabled || !plugin.is_granted(CREATE_CAPABILITY)
                || !plugin.descriptor.capabilities.iter().any(|c| c == CREATE_CAPABILITY) {
                return Err(unavailable("enabled page plugin with pages.create grant required"));
            }
            let previous: Option<String> = self.conn().query_row("SELECT payload FROM revisions WHERE revision_id = ?1", [&revision_id], |r| r.get(0))
                .map(Some).or_else(|e| if matches!(e, rusqlite::Error::QueryReturnedNoRows) { Ok(None) } else { Err(e) })?;
            if let Some(previous) = previous {
                let previous: Value = serde_json::from_str(&previous)?;
                if previous[PAYLOAD_KEY]["request_fingerprint"].as_str() != Some(&fingerprint) {
                    return Err(invalid("request id was already used for different page content"));
                }
                return Ok(receipt.clone());
            }
            let definition = self.page_definitions(plugin_id)?.into_iter().find(|d| d.type_id == request.type_id)
                .ok_or_else(|| invalid("undeclared page type"))?;
            if definition.version != request.definition_version { return Err(KernelError::DocumentChanged); }
            if let Some(problem) = definition.defaults_problem { return Err(unavailable(problem)); }
            self.check_page_defaults_source(definition.defaults_path.as_deref())?;
            let contribution = plugin.descriptor.contributions.iter().find(|c| c.slot == TYPE_SLOT && c.id == request.type_id).unwrap();
            let config: Value = serde_json::from_str(&contribution.config)?;
            if config.get("defaults_for").is_some() {
                let count: i64 = self.conn().query_row("SELECT count(*) FROM objects WHERE type_id=?1 AND deleted=0", [&request.type_id], |r| r.get(0))?;
                if count != 0 { return Err(unavailable("defaults already exist; edit the existing defaults document")); }
                let snapshot = gilune_document::TextSnapshot::new(request.source.clone()).map_err(|e| invalid(e.to_string()))?;
                portable_folder(snapshot.metadata_string("folder").ok_or_else(|| invalid("defaults require a string folder property"))?)?;
                crate::vault::new_page_payload(snapshot.body())?;
            }
            let destination = checked_path(self.vault(), &rel_path)?;
            if destination.exists() { return Err(unavailable("page destination already exists")); }
            let reserved: i64 = self.conn().query_row(
                "SELECT COUNT(*) FROM objects o JOIN heads h ON h.object_id=o.object_id
                 JOIN revisions r ON r.revision_id=h.revision_id LEFT JOIN projections p ON p.object_id=o.object_id
                 WHERE o.deleted=0 AND lower(COALESCE(p.rel_path, json_extract(r.payload, '$._gilune_page.path'))) = lower(?1)",
                [&rel_path], |r| r.get(0))?;
            if reserved != 0 { return Err(unavailable("page destination is already reserved")); }
            let mut payload = crate::vault::new_page_payload(&request.source)?;
            payload[PAYLOAD_KEY] = json!({"version":1,"path":rel_path,"owner":plugin_id,"request_fingerprint":fingerprint});
            let payload = payload.to_string();
            let created_ms = ids::now_ms();
            self.conn().execute("INSERT INTO objects (object_id,type_id,type_schema,created_ms) VALUES (?1,?2,?3,?4)", rusqlite::params![object_id, definition.type_id, definition.schema, created_ms])?;
            self.conn().execute("INSERT INTO revisions (revision_id,object_id,payload,origin,created_ms) VALUES (?1,?2,?3,'user-page',?4)", rusqlite::params![revision_id,object_id,payload,created_ms])?;
            self.conn().execute("INSERT INTO heads (object_id,revision_id) VALUES (?1,?2)", [&object_id,&revision_id])?;
            let op_id = operations::record(self.conn(), operations::OBJECT_CREATED, &object_id, json!({
                "object":{"object_id":object_id,"type_id":definition.type_id,"type_schema":definition.schema,"created_ms":created_ms},
                "revision":{"revision_id":revision_id,"payload":payload,"origin":"user-page","created_ms":created_ms},
                "event_type":CREATED_EVENT,
            }),created_ms)?;
            let encoded_bytes: i64 = self.conn().query_row("SELECT length(envelope) FROM operations WHERE op_id=?1", [&op_id], |r| r.get(0))?;
            if encoded_bytes as usize > crate::limits::Limits::DEFAULT.max_envelope_bytes {
                return Err(invalid("encoded page exceeds the default sync envelope budget"));
            }
            self.conn().execute("INSERT INTO events (event_id,event_type,subject_id,payload,created_ms,origin_device) VALUES (?1,?2,?3,?4,?5,?6)",
                rusqlite::params![ids::new_id(),CREATED_EVENT,object_id,payload,created_ms,device])?;
            Ok(receipt.clone())
        }
    }
}

fn portable_folder(folder: &str) -> Result<()> {
    portable_path(&if folder.is_empty() { "fixture.md".into() } else { format!("{folder}/fixture.md") }).map(|_| ())
}

mod defaults;
mod initialization;
pub use initialization::PageInitialization;

fn deterministic_id(value: &str) -> String {
    let h = ids::sha256_hex(value.as_bytes());
    format!("{}-{}-{}-{}-{}", &h[..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

pub(crate) fn portable_path(supplied: &str) -> Result<String> {
    let path = supplied.replace('\\', "/");
    if !path.to_ascii_lowercase().ends_with(".md")
        || path.split('/').any(|s| {
            let stem = s.split('.').next().unwrap_or("").to_ascii_uppercase();
            s.is_empty()
                || s == "."
                || s == ".."
                || s.eq_ignore_ascii_case(".gilune")
                || s.ends_with([' ', '.'])
                || s.chars().any(|c| c.is_control() || ":<>\"|?*".contains(c))
                || matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
                || (stem.len() == 4
                    && (stem.starts_with("COM") || stem.starts_with("LPT"))
                    && stem.as_bytes()[3].is_ascii_digit())
        })
    {
        return Err(invalid("expected a portable Markdown path inside the vault"));
    }
    Ok(path)
}

pub(crate) fn checked_path(vault: &Path, path: &str) -> Result<PathBuf> {
    let portable = portable_path(path)?;
    let root = std::fs::canonicalize(vault)
        .map_err(|source| KernelError::Io { path: vault.into(), source })?;
    let mut walk = root.clone();
    for component in portable.split('/') {
        walk.push(component);
        match std::fs::symlink_metadata(&walk) {
            Ok(metadata) => {
                #[cfg(windows)]
                let reparse = {
                    use std::os::windows::fs::MetadataExt;
                    metadata.file_attributes() & 0x400 != 0
                };
                #[cfg(not(windows))]
                let reparse = false;
                if metadata.file_type().is_symlink() || reparse {
                    return Err(invalid("page path crosses a link or junction"));
                }
                let resolved = std::fs::canonicalize(&walk)
                    .map_err(|source| KernelError::Io { path: walk.clone(), source })?;
                if !resolved.starts_with(&root) {
                    return Err(invalid("page path leaves the vault"));
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(source) => return Err(KernelError::Io { path: walk, source }),
        }
    }
    Ok(walk)
}

pub(crate) fn projection_path(payload: &Value) -> Result<Option<String>> {
    let Some(page) = payload.get(PAYLOAD_KEY) else { return Ok(None) };
    if page["version"].as_u64() != Some(1) {
        return Err(invalid("unsupported page envelope version"));
    }
    let path = page["path"].as_str().ok_or_else(|| invalid("missing page destination"))?;
    Ok(Some(portable_path(path)?))
}

#[cfg(test)]
mod tests;
#[cfg(test)]
mod defaults_tests;
