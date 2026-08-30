//! Markdown vault projection and bidirectional reconciliation (ADR 0007).
//!
//! Projection is rebuildable and idempotent; reconciliation is never
//! destructive to kernel history. Files are matched by front-matter object ID,
//! never by filename, so renames are metadata changes.

use crate::crashpoints::crashpoint;
use crate::{capture, fsync_dir, ids, jobs, processors, store, Kernel};
use anyhow::Result;
use std::collections::HashMap;
use std::path::Path;

/// The reconciler is conceptually the vault plugin, so its proposal type
/// lives in the vault plugin's namespace — not the kernel's, and not some
/// unrelated domain's.
pub const TOMBSTONE_PROPOSAL_TYPE: &str = "org.memory.vault/tombstone-proposal";

struct Projectable {
    object_id: String,
    type_id: String,
    class: String,
    created_at: i64,
    heads: Vec<(String, String)>, // (revision_id, payload_json), sorted by revision_id
}

fn load_projectables(k: &Kernel) -> Result<Vec<Projectable>> {
    let mut stmt = k.conn.prepare(
        "SELECT o.object_id, o.type_id, o.class, o.display_created_at, h.revision_id, r.payload_json
         FROM objects o
         JOIN heads h ON h.object_id = o.object_id
         JOIN revisions r ON r.revision_id = h.revision_id
         WHERE o.tombstone_json IS NULL
         ORDER BY o.object_id, h.revision_id",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, i64>(3)?,
            r.get::<_, String>(4)?,
            r.get::<_, String>(5)?,
        ))
    })?;
    let mut out: Vec<Projectable> = Vec::new();
    for row in rows {
        let (object_id, type_id, class, created_at, revision_id, payload) = row?;
        match out.last_mut() {
            Some(last) if last.object_id == object_id => {
                last.heads.push((revision_id, payload));
            }
            _ => out.push(Projectable {
                object_id,
                type_id,
                class,
                created_at,
                heads: vec![(revision_id, payload)],
            }),
        }
    }
    Ok(out)
}

/// Path and body rendering: the vault plugin owns its own types (sources it
/// mirrors for capture, its tombstone proposals); everything else comes from
/// a type OWNER's registered renderer (processors::SAMPLE_RENDERERS stands in
/// for plugin contributions), with a generic preservable fallback for types
/// nothing has registered.
fn md_rel_path(p: &Projectable) -> String {
    // Trailing chars of the ID: UUIDv7 leads with a timestamp, so leading
    // chars collide for objects created close together.
    let id8 = &p.object_id[p.object_id.len() - 12..];
    match p.type_id.as_str() {
        capture::SOURCE_TYPE => format!("Captures/capture-{id8}.md"),
        TOMBSTONE_PROPOSAL_TYPE => format!("Proposals/tombstone-proposal-{id8}.md"),
        other => match processors::renderer_for(other) {
            Some(r) => format!("{}-{id8}.md", r.path_prefix),
            None => {
                // Unknown types stay preservable: generic location keyed by ID.
                let slug = other.replace(['/', ':'], "-");
                format!("Objects/{slug}-{id8}.md")
            }
        },
    }
}

fn render_body(k: &Kernel, p: &Projectable, payload: &serde_json::Value) -> Result<String> {
    Ok(match p.type_id.as_str() {
        capture::SOURCE_TYPE => {
            // A synced envelope can arrive before its bytes finish
            // transferring; the projection must not fail on it.
            let Ok((bytes, media_type)) = capture::read_source_bytes(k, &p.object_id) else {
                return Ok("(original bytes pending sync)".to_string());
            };
            if media_type.starts_with("text/") {
                String::from_utf8_lossy(&bytes).to_string()
            } else {
                let rel: String = k.conn.query_row(
                    "SELECT b.rel_path FROM blobs b JOIN blob_refs r ON r.blob_id = b.blob_id
                     WHERE r.object_id = ?1",
                    [&p.object_id],
                    |r| r.get(0),
                )?;
                let file = Path::new(&rel)
                    .file_name()
                    .map(|f| f.to_string_lossy().to_string())
                    .unwrap_or(rel);
                format!("![[{file}]]")
            }
        }
        TOMBSTONE_PROPOSAL_TYPE => format!(
            "Proposed deletion of `{}` (its vault file was removed externally). Accept or restore.",
            payload["target"].as_str().unwrap_or_default()
        ),
        other => match processors::renderer_for(other) {
            Some(r) => (r.render)(payload),
            None => payload.to_string(),
        },
    })
}

fn render_file(k: &Kernel, p: &Projectable) -> Result<String> {
    let (head_rev, head_payload) = &p.heads[0];
    let payload: serde_json::Value = serde_json::from_str(head_payload)?;
    let mut front = serde_json::json!({
        "object": p.object_id,
        "type": p.type_id,
        "schema": 1,
        "class": p.class,
        "revision": head_rev,
        "created": p.created_at,
    });
    if p.heads.len() > 1 {
        front["conflicted"] = serde_json::json!(true);
        front["heads"] = serde_json::json!(
            p.heads.iter().map(|(r, _)| r.clone()).collect::<Vec<_>>()
        );
    }
    let yaml = serde_yaml::to_string(&serde_json::json!({ "gilune": front }))?;
    let body = render_body(k, p, &payload)?;
    Ok(format!("---\n{yaml}---\n\n{body}\n"))
}

fn write_atomic(vault: &Path, rel: &str, content: &str) -> Result<String> {
    let target = vault.join(rel);
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
        let tmp = parent.join(format!(
            ".tmp-{}",
            target.file_name().unwrap().to_string_lossy()
        ));
        std::fs::write(&tmp, content)?;
        crate::fsync_file(&tmp)?;
        std::fs::rename(&tmp, &target)?;
        fsync_dir(parent)?;
    }
    Ok(ids::sha256_hex(content.as_bytes()))
}

/// Idempotent projection of committed state into Markdown. Objects whose
/// file was externally deleted (pending tombstone decision) are skipped so
/// the file is not resurrected while the proposal is open.
pub fn project(k: &mut Kernel) -> Result<usize> {
    let items = load_projectables(k)?;
    let mut written = 0;
    for p in items {
        let state: Option<(String, String, i64)> = k
            .conn
            .query_row(
                "SELECT rel_path, content_sha256, missing FROM vault_state WHERE object_id = ?1",
                [&p.object_id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                other => Err(other),
            })?;
        if let Some((_, _, missing)) = &state {
            if *missing == 1 {
                continue;
            }
        }
        let rel = state
            .as_ref()
            .map(|(rel, _, _)| rel.clone())
            .unwrap_or_else(|| md_rel_path(&p));
        let content = render_file(k, &p)?;
        let sha = ids::sha256_hex(content.as_bytes());
        let up_to_date = state
            .as_ref()
            .map(|(srel, ssha, _)| *srel == rel && *ssha == sha && k.vault.join(&rel).exists())
            .unwrap_or(false);
        if up_to_date {
            continue;
        }
        let written_sha = write_atomic(&k.vault, &rel, &content)?;
        let head_rev = p.heads[0].0.clone();
        k.conn.execute(
            "INSERT INTO vault_state (object_id, rel_path, projected_revision, content_sha256, missing)
             VALUES (?1, ?2, ?3, ?4, 0)
             ON CONFLICT(object_id) DO UPDATE SET rel_path = ?2, projected_revision = ?3,
                                                  content_sha256 = ?4, missing = 0",
            rusqlite::params![p.object_id, rel, head_rev, written_sha],
        )?;
        written += 1;
        crashpoint("vault.during_projection");
    }

    // Tombstoned objects leave the vault (deletion phase 2: cease use).
    // Kernel history keeps the tombstone; only the projection is removed.
    let dead: Vec<(String, String)> = {
        let mut stmt = k.conn.prepare(
            "SELECT v.object_id, v.rel_path FROM vault_state v
             JOIN objects o ON o.object_id = v.object_id
             WHERE o.tombstone_json IS NOT NULL",
        )?;
        let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
        rows.collect::<std::result::Result<_, _>>()?
    };
    for (object_id, rel_path) in dead {
        let path = k.vault.join(&rel_path);
        if path.exists() {
            std::fs::remove_file(&path)?;
        }
        k.conn.execute(
            "DELETE FROM vault_state WHERE object_id = ?1",
            [&object_id],
        )?;
    }
    Ok(written)
}

struct ScannedFile {
    rel_path: String,
    bytes: Vec<u8>,
    front: Option<serde_json::Value>, // gilune front-matter object, if parseable
    malformed: bool,
    body: String,
}

fn scan_vault(vault: &Path) -> Result<Vec<ScannedFile>> {
    let mut out = Vec::new();
    let mut stack = vec![vault.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir)? {
            let entry = entry?;
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            if path.is_dir() {
                if name == ".gilune" || name.starts_with('.') {
                    continue;
                }
                stack.push(path);
            } else if name.ends_with(".md") && !name.starts_with(".tmp-") {
                let rel = crate::portable_rel(path.strip_prefix(vault).unwrap());
                let bytes = std::fs::read(&path)?;
                let text = String::from_utf8_lossy(&bytes).to_string();
                let (front, malformed, body) = parse_front_matter(&text);
                out.push(ScannedFile {
                    rel_path: rel,
                    bytes,
                    front,
                    malformed,
                    body,
                });
            }
        }
    }
    out.sort_by(|a, b| a.rel_path.cmp(&b.rel_path));
    Ok(out)
}

fn parse_front_matter(text: &str) -> (Option<serde_json::Value>, bool, String) {
    let Some(rest) = text.strip_prefix("---\n") else {
        return (None, false, text.to_string());
    };
    let Some(end) = rest.find("\n---\n") else {
        return (None, true, text.to_string());
    };
    let yaml = &rest[..end + 1];
    let body = rest[end + 5..].trim_start_matches('\n').to_string();
    match serde_yaml::from_str::<serde_json::Value>(yaml) {
        Ok(v) => {
            let g = v.get("gilune").cloned();
            match g {
                Some(g) if g.get("object").and_then(|o| o.as_str()).is_some() => {
                    (Some(g), false, body)
                }
                Some(_) => (None, true, body),
                None => (None, false, body), // plain md with unrelated front matter
            }
        }
        Err(_) => (None, true, body),
    }
}

pub struct ReconcileReport {
    pub external_edits: usize,
    pub renames: usize,
    pub tombstone_proposals: usize,
    pub quarantined: usize,
    pub missing_blobs: usize,
}

/// Ingest external changes: edits become revisions, deletions become
/// proposals, unknown or malformed files are quarantined untouched.
pub fn reconcile(k: &mut Kernel) -> Result<ReconcileReport> {
    let mut report = ReconcileReport {
        external_edits: 0,
        renames: 0,
        tombstone_proposals: 0,
        quarantined: 0,
        missing_blobs: 0,
    };
    let files = scan_vault(&k.vault)?;
    let mut seen: HashMap<String, &ScannedFile> = HashMap::new();

    for f in &files {
        match (&f.front, f.malformed) {
            (_, true) => {
                quarantine(k, &f.rel_path, "malformed-front-matter")?;
                report.quarantined += 1;
            }
            (None, false) => {
                quarantine(k, &f.rel_path, "import-candidate")?;
                report.quarantined += 1;
            }
            (Some(front), false) => {
                let object_id = front["object"].as_str().unwrap_or_default().to_string();
                seen.insert(object_id, f);
            }
        }
    }

    // Files claiming an object this device has never seen (e.g. copied in
    // from another vault): preserve and flag, never ingest blindly.
    for (object_id, f) in &seen {
        let known: i64 = k.conn.query_row(
            "SELECT COUNT(*) FROM objects WHERE object_id = ?1",
            [object_id],
            |r| r.get(0),
        )?;
        if known == 0 {
            quarantine(k, &f.rel_path, "unknown-object")?;
            report.quarantined += 1;
        }
    }

    // Known objects: detect renames and edits against projection bookkeeping.
    let states: Vec<(String, String, String, String, i64)> = {
        let mut stmt = k.conn.prepare(
            "SELECT v.object_id, v.rel_path, v.content_sha256, v.projected_revision, v.missing,
                    o.class
             FROM vault_state v JOIN objects o ON o.object_id = v.object_id",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(5)?,
                r.get::<_, i64>(4)?,
            ))
        })?;
        let mut v: Vec<(String, String, String, String, i64)> =
            rows.collect::<std::result::Result<_, _>>()?;
        // note: tuple = (object_id, rel_path, content_sha, class, missing)
        v.sort();
        v
    };

    for (object_id, rel_path, content_sha, class, missing) in states {
        match seen.get(&object_id) {
            None => {
                if missing == 0 {
                    propose_tombstone(k, &object_id)?;
                    k.conn.execute(
                        "UPDATE vault_state SET missing = 1 WHERE object_id = ?1",
                        [&object_id],
                    )?;
                    report.tombstone_proposals += 1;
                }
            }
            Some(f) => {
                if missing == 1 {
                    // The file is back (restored externally): clear the flag.
                    k.conn.execute(
                        "UPDATE vault_state SET missing = 0 WHERE object_id = ?1",
                        [&object_id],
                    )?;
                }
                if f.rel_path != rel_path {
                    k.conn.execute(
                        "UPDATE vault_state SET rel_path = ?2 WHERE object_id = ?1",
                        rusqlite::params![object_id, f.rel_path],
                    )?;
                    report.renames += 1;
                }
                let file_sha = ids::sha256_hex(&f.bytes);
                if file_sha != content_sha {
                    if class == "source" {
                        // Sources are immutable: an edited original is
                        // preserved and flagged, never ingested as a source
                        // revision (constitution rule 2).
                        quarantine(k, &f.rel_path, "external-edit-to-source")?;
                        report.quarantined += 1;
                    } else {
                        // Only a type OWNER can map an edited body back into
                        // its payload; without one, preserve and flag.
                        let type_id: String = k.conn.query_row(
                            "SELECT type_id FROM objects WHERE object_id = ?1",
                            [&object_id],
                            |r| r.get(0),
                        )?;
                        match processors::renderer_for(&type_id).and_then(|r| r.apply_edit) {
                            Some(apply) => {
                                ingest_external_edit(
                                    k, &object_id, &f.body, &file_sha, &f.rel_path, apply,
                                )?;
                                report.external_edits += 1;
                            }
                            None => {
                                quarantine(k, &f.rel_path, "unowned-type-edit")?;
                                report.quarantined += 1;
                            }
                        }
                    }
                    crashpoint("reconcile.during");
                }
            }
        }
    }

    // Original blob bytes that vanished are integrity alerts, never silent.
    for (blob_id, rel, _sha) in capture::blob_paths(k)? {
        if !k.vault.join(&rel).exists() {
            store::raise_alert(
                &k.conn,
                "missing-blob-file",
                &blob_id,
                &format!("{} missing from vault", rel.display()),
            )?;
            report.missing_blobs += 1;
        }
    }
    Ok(report)
}

fn quarantine(k: &Kernel, rel_path: &str, reason: &str) -> Result<()> {
    k.conn.execute(
        "INSERT OR IGNORE INTO quarantine_files (rel_path, reason, seen_at) VALUES (?1, ?2, ?3)",
        rusqlite::params![rel_path, reason, Kernel::now_ms()],
    )?;
    Ok(())
}

fn ingest_external_edit(
    k: &mut Kernel,
    object_id: &str,
    body: &str,
    file_sha: &str,
    rel_path: &str,
    apply_edit: fn(&mut serde_json::Value, &str),
) -> Result<()> {
    let now = Kernel::now_ms();
    let device = k.device_id.clone();
    let tx = k.conn.transaction()?;
    {
        let (based_on, old_payload): (String, String) = tx.query_row(
            "SELECT v.projected_revision, r.payload_json
             FROM vault_state v
             JOIN revisions r ON r.revision_id = v.projected_revision
             WHERE v.object_id = ?1",
            [object_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        let mut payload: serde_json::Value = serde_json::from_str(&old_payload)?;
        apply_edit(&mut payload, body);
        let revision_id = ids::new_id();
        let op_payload = serde_json::json!({
            "object_id": object_id,
            "revision": { "revision_id": &revision_id, "based_on": [&based_on],
                          "payload": &payload },
            "supersedes": [&based_on],
            "event_type": "memory.kernel/object-revised",
        });
        let op_id =
            store::record_operation(&tx, &device, "object-revised", object_id, &op_payload, now)?;
        tx.execute(
            "INSERT INTO revisions (revision_id, object_id, based_on_json, payload_json, created_operation, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            rusqlite::params![
                revision_id,
                object_id,
                serde_json::to_string(&vec![based_on.clone()])?,
                payload.to_string(),
                op_id,
                now
            ],
        )?;
        // The new revision supersedes the head it was based on; any other
        // concurrent head is preserved.
        tx.execute(
            "DELETE FROM heads WHERE object_id = ?1 AND revision_id = ?2",
            rusqlite::params![object_id, based_on],
        )?;
        tx.execute(
            "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
            rusqlite::params![object_id, revision_id],
        )?;
        let event_id = ids::new_id();
        jobs::emit_event_in_tx(
            &tx,
            &event_id,
            "memory.kernel/object-revised",
            "kernel/vault-reconciler",
            object_id,
            &revision_id,
            None,
            &event_id,
            &device,
            &device,
            now,
        )?;
        tx.execute(
            "UPDATE vault_state SET projected_revision = ?2, content_sha256 = ?3, rel_path = ?4
             WHERE object_id = ?1",
            rusqlite::params![object_id, revision_id, file_sha, rel_path],
        )?;
    }
    tx.commit()?;
    Ok(())
}

fn propose_tombstone(k: &mut Kernel, target_object: &str) -> Result<()> {
    let now = Kernel::now_ms();
    let device = k.device_id.clone();
    let proposal_id = ids::deterministic_id("tombstone-proposal", target_object);
    let revision_id = ids::deterministic_id("tombstone-proposal-rev", target_object);
    let tx = k.conn.transaction()?;
    {
        let exists: i64 = tx.query_row(
            "SELECT COUNT(*) FROM objects WHERE object_id = ?1",
            [&proposal_id],
            |r| r.get(0),
        )?;
        if exists == 0 {
            let op_payload = serde_json::json!({
                "object": { "object_id": &proposal_id, "type_id": TOMBSTONE_PROPOSAL_TYPE,
                            "schema_version": 1, "class": "proposal",
                            "display_created_at": now },
                "revision": { "revision_id": &revision_id, "based_on": [],
                              "payload": { "target": target_object } },
            });
            let op_id = store::record_operation(
                &tx,
                &device,
                "object-created",
                &proposal_id,
                &op_payload,
                now,
            )?;
            tx.execute(
                "INSERT INTO objects (object_id, type_id, schema_version, class, origin_device, created_operation, display_created_at)
                 VALUES (?1, ?2, 1, 'proposal', ?3, ?4, ?5)",
                rusqlite::params![proposal_id, TOMBSTONE_PROPOSAL_TYPE, device, op_id, now],
            )?;
            tx.execute(
                "INSERT INTO revisions (revision_id, object_id, based_on_json, payload_json, created_operation, created_at)
                 VALUES (?1, ?2, '[]', ?3, ?4, ?5)",
                rusqlite::params![
                    revision_id,
                    proposal_id,
                    serde_json::json!({ "target": target_object }).to_string(),
                    op_id,
                    now
                ],
            )?;
            tx.execute(
                "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                rusqlite::params![proposal_id, revision_id],
            )?;
        }
    }
    tx.commit()?;
    Ok(())
}
