//! Read-side queries for shells and tools: bounded, envelope-level, no
//! raw SQL across the boundary (02-kernel-contract). Grows per screen
//! need, not speculatively.

use crate::store::Store;
use crate::Result;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceSummary {
    pub object_id: String,
    pub created_ms: i64,
    pub blob_rel_path: Option<String>,
    pub projection_rel_path: Option<String>,
    pub preview: String,
}

const PREVIEW_CHARS: usize = 160;

impl Store {
    /// Newest-first sources for a capture feed.
    pub fn recent_sources(&self, limit: u32) -> Result<Vec<SourceSummary>> {
        let mut stmt = self.conn().prepare(
            "SELECT o.object_id, o.created_ms, r.payload,
                    (SELECT p.rel_path FROM projections p WHERE p.object_id = o.object_id)
             FROM objects o
             JOIN heads h ON h.object_id = o.object_id
             JOIN revisions r ON r.revision_id = h.revision_id
             WHERE o.deleted = 0 AND o.type_id = 'org.memory.source'
             GROUP BY o.object_id
             HAVING r.created_ms = MAX(r.created_ms)
             ORDER BY o.created_ms DESC
             LIMIT ?1",
        )?;
        let rows = stmt.query_map([limit], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<String>>(3)?,
            ))
        })?;
        let mut out = Vec::new();
        for row in rows {
            let (object_id, created_ms, payload, projection_rel_path) = row?;
            let payload: serde_json::Value = serde_json::from_str(&payload)?;
            let blob_rel_path = payload
                .get("rel_path")
                .and_then(|v| v.as_str())
                .map(str::to_string);
            let preview = if let Some(text) = payload.get("text").and_then(|v| v.as_str()) {
                truncate(text)
            } else if let (Some(rel), Some("txt")) = (
                blob_rel_path.as_deref(),
                payload.get("media").and_then(|v| v.as_str()),
            ) {
                std::fs::read_to_string(self.vault().join(rel))
                    .map(|t| truncate(&t))
                    .unwrap_or_default()
            } else {
                payload
                    .get("media")
                    .and_then(|v| v.as_str())
                    .map(|m| format!("[{m} capture]"))
                    .unwrap_or_default()
            };
            out.push(SourceSummary {
                object_id,
                created_ms,
                blob_rel_path,
                projection_rel_path,
                preview,
            });
        }
        Ok(out)
    }
}

fn truncate(text: &str) -> String {
    let line = text.lines().next().unwrap_or("");
    line.chars().take(PREVIEW_CHARS).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recent_sources_are_newest_first_with_previews() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.capture_bytes("txt", b"first thought\nsecond line").expect("capture");
        store.capture_bytes("wav", &[0u8; 4]).expect("capture");
        store.project().expect("project");

        let sources = store.recent_sources(10).expect("query");
        assert_eq!(sources.len(), 2);
        assert_eq!(sources[0].preview, "[wav capture]");
        assert_eq!(sources[1].preview, "first thought");
        assert!(sources[1].projection_rel_path.is_some());
        assert_eq!(store.recent_sources(1).expect("limit").len(), 1);
    }

    #[test]
    fn an_external_edit_updates_the_preview() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.capture_bytes("txt", b"before edit").expect("capture");
        store.project().expect("project");
        let rel = store.recent_sources(1).expect("query")[0]
            .projection_rel_path
            .clone()
            .expect("projected");
        let edited = std::fs::read_to_string(store.vault().join(&rel))
            .expect("read")
            .replace("before edit", "after edit");
        std::fs::write(store.vault().join(&rel), edited).expect("edit");
        store.reconcile().expect("reconcile");
        assert_eq!(store.recent_sources(1).expect("query")[0].preview, "after edit");
    }
}
