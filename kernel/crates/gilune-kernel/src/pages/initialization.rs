//! Once-only, revision-backed initial page sets. The declaration supplies every
//! domain value. A normal visible receipt and retained object history prevent
//! reinitialization after edits, retirement, deletion, restart or sync.
use super::*;
use serde::Deserialize;
use std::collections::{BTreeMap, BTreeSet};

pub const INITIALIZERS_SLOT: &str = "pages.initializers";

#[derive(Debug, Clone)]
pub struct PageInitialization {
    pub initializer_id: String,
    /// recorded | initialized | preserved-existing
    pub status: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Initializer {
    version: u32,
    receipt_type: String,
    receipt_file: String,
    pages: Vec<Seed>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Seed {
    key: String,
    type_id: String,
    relative_file: String,
    properties: BTreeMap<String, String>,
}

impl Store {
    pub fn initialize_pages(&self, plugin_id: &str) -> Result<Vec<PageInitialization>> {
        let plugin = self
            .plugin(plugin_id)?
            .ok_or_else(|| unavailable("page plugin is unavailable"))?;
        if plugin.state != PluginState::Enabled
            || !plugin.is_granted(CREATE_CAPABILITY)
            || !plugin
                .descriptor
                .capabilities
                .iter()
                .any(|c| c == CREATE_CAPABILITY)
        {
            return Err(unavailable(
                "enabled page plugin with pages.create grant required",
            ));
        }
        let definitions = self.page_definitions(plugin_id)?;
        let mut initializers = Vec::new();
        let mut ids = BTreeSet::new();
        let mut receipt_types = BTreeSet::new();
        for contribution in plugin
            .descriptor
            .contributions
            .iter()
            .filter(|c| c.slot == INITIALIZERS_SLOT)
        {
            let config: Initializer =
                serde_json::from_str(&contribution.config).map_err(|e| invalid(e.to_string()))?;
            let owned = |id: &str| definitions.iter().any(|d| d.type_id == id);
            if !contribution.id.starts_with(&format!("{plugin_id}/"))
                || !ids.insert(contribution.id.clone())
                || config.version != 1
                || !owned(&config.receipt_type)
                || !receipt_types.insert(config.receipt_type.clone())
                || config.pages.is_empty()
                || config.pages.len() > 32
            {
                return Err(invalid("invalid or duplicate page initializer declaration"));
            }
            portable_path(&config.receipt_file)?;
            let mut keys = BTreeSet::new();
            for page in &config.pages {
                if !owned(&page.type_id)
                    || page.type_id == config.receipt_type
                    || page.key.is_empty()
                    || !keys.insert(&page.key)
                    || page.properties.len() > 64
                {
                    return Err(invalid("invalid initializer page ownership or key"));
                }
                portable_path(&page.relative_file)?;
            }
            initializers.push((contribution.id.clone(), contribution.config.clone(), config));
        }
        if initializers
            .iter()
            .any(|(_, _, c)| c.pages.iter().any(|p| receipt_types.contains(&p.type_id)))
        {
            return Err(invalid(
                "initializer receipts cannot be seeded by another initializer",
            ));
        }
        let mut result = Vec::new();
        for (id, declaration, config) in initializers {
            // Cheap no-read path for every subsequent invocation, even if the
            // receipt was edited/deleted. Old object history is intentional.
            if self.has_page_type_history(&config.receipt_type)? {
                result.push(PageInitialization {
                    initializer_id: id,
                    status: "recorded".into(),
                });
                continue;
            }
            // Initial discovery only. Reconciliation preserves unmanaged files.
            // Inspect known imports/quarantine through exact snapshots: legacy
            // frontmatter facts retain raw YAML spelling, not scalar semantics.
            let types: BTreeSet<_> = config
                .pages
                .iter()
                .map(|p| p.type_id.clone())
                .chain(std::iter::once(config.receipt_type.clone()))
                .collect();
            let mut existing_history = false;
            for type_id in &types { existing_history |= self.has_page_type_history(type_id)?; }
            let imported = if existing_history {
                false // History already proves this is not a fresh collection.
            } else {
                self.reconcile()?;
                self.has_imported_page_types(&types)?
            };
            let status = self.write_txn(|| {
                let live = self.plugin(plugin_id)?.ok_or_else(|| unavailable("page plugin is unavailable"))?;
                if live.state != PluginState::Enabled || !live.is_granted(CREATE_CAPABILITY)
                    || !live.descriptor.contributions.iter().any(|c| c.slot == INITIALIZERS_SLOT && c.id == id && c.config == declaration) {
                    return Err(KernelError::DocumentChanged);
                }
                if self.has_page_type_history(&config.receipt_type)? { return Ok("recorded".to_string()); }
                let mut existing = imported;
                for type_id in &types { existing |= self.has_page_type_history(type_id)?; }
                let definitions = self.page_definitions(plugin_id)?;
                let status = if existing { "preserved-existing" } else { "initialized" };
                let mut evidence = format!("# Page initialization\n\nInitializer: `{id}`\n\nResult: {status}\n\nThis receipt records a one-time setup. Editing or deleting it does not reset initialization.\n");
                if !existing {
                    for page in &config.pages {
                        let d = definitions.iter().find(|d| d.type_id == page.type_id).ok_or_else(|| invalid("initializer type disappeared"))?;
                        let snapshot = gilune_document::TextSnapshot::new(d.template.clone()).map_err(|e| invalid(e.to_string()))?;
                        let changes: Vec<_> = page.properties.iter().map(|(key, value)| gilune_document::PropertyChange { key: key.clone(), value_yaml: Some(value.clone()) }).collect();
                        let source = snapshot.change_properties(snapshot.version(), &changes).map_err(|e| invalid(e.to_string()))?.text().to_string();
                        let receipt = self.create_page_in_txn(plugin_id, &CreatePage {
                            type_id: page.type_id.clone(), definition_version: d.version.clone(),
                            request_id: super::deterministic_request_id(&json!([plugin_id, id, page.key]).to_string()),
                            rel_path: in_folder(&d.default_folder, &page.relative_file), source,
                        })?;
                        evidence.push_str(&format!("\n- {}: `{}` (object `{}`)\n", page.key, receipt.rel_path, receipt.object_id));
                    }
                }
                let d = definitions.iter().find(|d| d.type_id == config.receipt_type).ok_or_else(|| invalid("initializer receipt type disappeared"))?;
                self.create_page_in_txn(plugin_id, &CreatePage {
                    type_id: d.type_id.clone(), definition_version: d.version.clone(),
                    request_id: super::deterministic_request_id(
                        &json!([plugin_id, id, "receipt", self.device_id()?]).to_string(),
                    ),
                    rel_path: in_folder(&d.default_folder, &config.receipt_file), source: evidence,
                })?;
                Ok(status.to_string())
            })?;
            result.push(PageInitialization {
                initializer_id: id,
                status,
            });
        }
        Ok(result)
    }

    fn has_page_type_history(&self, type_id: &str) -> Result<bool> {
        Ok(self.conn().query_row(
            "SELECT EXISTS(SELECT 1 FROM objects WHERE type_id=?1)",
            [type_id],
            |r| r.get(0),
        )?)
    }

    fn has_imported_page_types(&self, types: &BTreeSet<String>) -> Result<bool> {
        let mut after = String::new();
        loop {
            let paths = self.conn().prepare("SELECT rel_path FROM (SELECT rel_path FROM imports UNION SELECT rel_path FROM quarantine) WHERE rel_path > ?1 ORDER BY rel_path LIMIT 128")?
                .query_map([&after], |r| r.get::<_, String>(0))?.collect::<std::result::Result<Vec<_>, _>>()?;
            if paths.is_empty() {
                return Ok(false);
            }
            for path in &paths {
                match self.document_snapshot(path) {
                    Ok(snapshot) => {
                        if snapshot
                            .content
                            .metadata_string("gilune-type")
                            .is_some_and(|t| types.contains(t))
                        {
                            return Ok(true);
                        }
                    }
                    Err(KernelError::Io { source, .. })
                        if source.kind() == std::io::ErrorKind::NotFound => {}
                    Err(e) => {
                        return Err(unavailable(format!(
                            "Cannot determine initial page scope from {path}: {e}"
                        )))
                    }
                }
            }
            after = paths.last().unwrap().clone();
        }
    }
}

fn in_folder(folder: &str, file: &str) -> String {
    if folder.is_empty() {
        file.into()
    } else {
        format!("{folder}/{file}")
    }
}

#[cfg(test)]
mod tests;
