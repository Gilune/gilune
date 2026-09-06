//! Portable creation defaults are normal typed documents, explicitly declared by
//! their owner. This lookup reads current revisions, never a second file index.
use super::*;
use crate::plugins::Contribution;
use rusqlite::OptionalExtension;

impl Store {
    pub(super) fn resolve_page_defaults(
        &self,
        contributions: &[Contribution],
        definitions: &mut [PageDefinition],
    ) -> Result<()> {
        let mut links = Vec::new();
        for c in contributions.iter().filter(|c| c.slot == TYPE_SLOT) {
            let config: Value = serde_json::from_str(&c.config)?;
            if let Some(target) = config.get("defaults_for") {
                let target = target
                    .as_str()
                    .ok_or_else(|| invalid("defaults_for must be a page type"))?;
                if target == c.id
                    || !definitions.iter().any(|d| d.type_id == target)
                    || links.iter().any(|(_, t): &(String, String)| t == target)
                {
                    return Err(invalid(
                        "defaults must name one distinct page type owned by the same plugin",
                    ));
                }
                links.push((c.id.clone(), target.to_string()));
            }
        }
        if links
            .iter()
            .any(|(_, target)| links.iter().any(|(source, _)| source == target))
        {
            return Err(invalid(
                "defaults documents cannot themselves have defaults",
            ));
        }
        for (config_type, target) in links {
            let declaration_version = definitions
                .iter()
                .find(|d| d.type_id == config_type)
                .unwrap()
                .version
                .clone();
            let definition = definitions
                .iter_mut()
                .find(|d| d.type_id == target)
                .unwrap();
            definition.defaults_type_id = Some(config_type.clone());
            let mut query = self.conn().prepare(
                "SELECT o.object_id, h.revision_id, r.payload, p.rel_path,
                 EXISTS(SELECT 1 FROM proposals WHERE object_id=o.object_id AND state='open'),
                 EXISTS(SELECT 1 FROM quarantine WHERE rel_path=p.rel_path)
                 FROM objects o LEFT JOIN heads h ON h.object_id=o.object_id
                 LEFT JOIN revisions r ON r.revision_id=h.revision_id
                 LEFT JOIN projections p ON p.object_id=o.object_id
                 WHERE o.type_id=?1 AND o.deleted=0 ORDER BY o.object_id,h.revision_id LIMIT 2",
            )?;
            let rows = query
                .query_map([&config_type], |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, Option<String>>(1)?,
                        r.get::<_, Option<String>>(2)?,
                        r.get::<_, Option<String>>(3)?,
                        r.get::<_, bool>(4)?,
                        r.get::<_, bool>(5)?,
                    ))
                })?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            definition.version = ids::sha256_hex(
                json!([definition.version, declaration_version, rows])
                    .to_string()
                    .as_bytes(),
            );
            if rows.is_empty() {
                continue;
            }
            if rows.len() != 1 {
                let locations = rows
                    .iter()
                    .map(|(id, _, _, path, _, _)| path.as_deref().unwrap_or(id))
                    .collect::<Vec<_>>()
                    .join(", ");
                definition.defaults_problem = Some(format!("Multiple or conflicting defaults for {}. Resolve the {} documents before creating pages (candidates: {locations}).", definition.label, config_type));
                continue;
            }
            let (_, revision, payload, path, pending, quarantined) = &rows[0];
            definition.defaults_path = path.clone();
            let applied = (|| -> Result<()> {
                if *pending || *quarantined || revision.is_none() {
                    return Err(unavailable(
                        "defaults document needs conflict, deletion or quarantine resolution",
                    ));
                }
                let payload: Value = serde_json::from_str(
                    payload
                        .as_deref()
                        .ok_or_else(|| invalid("missing defaults revision"))?,
                )?;
                if definition.defaults_path.is_none() {
                    definition.defaults_path = crate::pages::projection_path(&payload)?;
                    return Err(unavailable("defaults page is saved but awaits publication"));
                }
                let source = crate::vault::preserved_page_source(&payload)?;
                let snapshot = gilune_document::TextSnapshot::new(source)
                    .map_err(|e| invalid(e.to_string()))?;
                let folder = snapshot.metadata_string("folder").ok_or_else(|| {
                    invalid(
                        "defaults require a string folder property; use \"\" for the vault root",
                    )
                })?;
                portable_folder(folder)?;
                crate::vault::new_page_payload(snapshot.body())?;
                definition.default_folder = folder.into();
                definition.template = snapshot.body().into();
                Ok(())
            })();
            if let Err(e) = applied {
                definition.defaults_problem =
                    Some(format!("Cannot use defaults for {}: {e}", definition.label));
            }
        }
        Ok(())
    }

    /// A creation is allowed to read its particular configuration source. Search
    /// does not call this. Refuse unreconciled external edits at the commit gate.
    pub(super) fn check_page_defaults_source(&self, path: Option<&str>) -> Result<()> {
        let Some(path) = path else { return Ok(()) };
        let expected: Option<String> = self
            .conn()
            .query_row(
                "SELECT content_sha256 FROM projections WHERE rel_path=?1",
                [path],
                |r| r.get(0),
            )
            .optional()?;
        crate::probe::file_read();
        let bytes = std::fs::read(checked_path(self.vault(), path)?)
            .map_err(|_| KernelError::DocumentChanged)?;
        if expected.as_deref() != Some(ids::sha256_hex(&bytes).as_str()) {
            return Err(KernelError::DocumentChanged);
        }
        Ok(())
    }
}
