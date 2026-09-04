//! Developer CLI over the kernel. Every command opens the store fresh —
//! running startup recovery — so the crash-matrix harness can kill a
//! command at any persistence boundary and assert the next command
//! recovers. Output is JSON on stdout; failures exit non-zero.

use gilune_kernel::store::Store;
use std::process::ExitCode;

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut args = args.iter().map(String::as_str);

    let usage = "usage: gilune-cli --vault <dir> <init|capture --text <text>|work|\
                 external [--at <ms>]|apply --file <path>|project|reconcile|\
                 reconcile-path <rel>|changes [--since <epoch:seq>] [--limit <n>]|status|\
                 verify|backfill|alerts|plugins [enable|disable|remove <id>]>";
    let (Some("--vault"), Some(vault)) = (args.next(), args.next()) else {
        return Err(usage.to_string());
    };
    let store = Store::open(vault).map_err(|e| e.to_string())?;
    // Every host of this kernel carries the vault projection, so every
    // host declares it — the app, this tool and the agent then agree on
    // what is installed (ADR 0011).
    store
        .register_plugin(&gilune_kernel::vault::plugin_descriptor())
        .map_err(|e| e.to_string())?;

    match args.next() {
        Some("init") => {
            println!(
                "{}",
                serde_json::json!({
                    "vault": store.vault().display().to_string(),
                    "schema_version": store.schema_version().map_err(|e| e.to_string())?,
                    "device_id": store.device_id().map_err(|e| e.to_string())?,
                })
            );
            Ok(())
        }
        Some("capture") => {
            let (Some("--text"), Some(text)) = (args.next(), args.next()) else {
                return Err(usage.to_string());
            };
            let receipt = store
                .capture_bytes("txt", text.as_bytes())
                .map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "saved": true,
                    "source_id": receipt.source_id,
                    "rel_path": receipt.rel_path,
                    "sha256": receipt.sha256,
                    "bytes": receipt.bytes,
                })
            );
            Ok(())
        }
        Some("status") => {
            let s = store.status().map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "sources": s.sources,
                    "blobs_ready": s.blobs_ready,
                    "blobs_committed": s.blobs_committed,
                    "events": s.events,
                    "jobs_pending": s.jobs_pending,
                    // A subset of jobs_pending: work whose plugin is
                    // switched off. Waiting because nothing may take it,
                    // as against waiting for its turn.
                    "jobs_parked": s.jobs_parked,
                    "jobs_quarantined": s.jobs_quarantined,
                    "operations": s.operations,
                    "blobs_awaiting": s.blobs_awaiting,
                    "alerts": s.alerts,
                    "projections": s.projections,
                    "proposals_open": s.proposals_open,
                    "quarantined_files": s.quarantined_files,
                    "import_candidates": s.import_candidates,
                })
            );
            Ok(())
        }
        Some("work") => {
            // Dev tool: runs the deterministic stand-in transcriber, the
            // same one the crash harness aborts mid-commit. Registering it
            // is what subscribes it, so disabling it here behaves exactly
            // as disabling a real provider in the app does.
            store
                .register_plugin(&gilune_kernel::processor::fake_transcriber_descriptor())
                .map_err(|e| e.to_string())?;
            let s = store
                .run_pending_jobs(
                    &[&gilune_kernel::processor::FakeTranscriber],
                    gilune_kernel::ids::now_ms(),
                    30_000,
                )
                .map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "completed": s.completed,
                    "skipped_duplicate": s.skipped_duplicate,
                    "skipped_moved": s.skipped_moved,
                    "failed": s.failed,
                    "outputs": s.outputs,
                })
            );
            Ok(())
        }
        Some("external") => {
            // Dev tool: drives the FFI processor route's kernel half
            // (ADR 0018 § 2) as a host application drives it — claim,
            // stream the subject, submit. The crash harness aborts this
            // command between those steps and asserts the vault converges
            // on exactly one output or a cleanly re-claimable job.
            store
                .register_plugin(&gilune_kernel::external::fake_external_descriptor())
                .map_err(|e| e.to_string())?;
            // `--at <ms>` lets the harness step past a dead claimant's
            // lease without reaching into SQL, the same way the kernel's
            // own tests supply `now_ms` (jobs.rs).
            let now_ms = match (args.next(), args.next()) {
                (None, _) => gilune_kernel::ids::now_ms(),
                (Some("--at"), Some(ms)) => ms.parse().map_err(|_| usage.to_string())?,
                _ => return Err(usage.to_string()),
            };
            let delivered =
                gilune_kernel::external::run_external_once(&store, now_ms, 30_000)
                    .map_err(|e| e.to_string())?;
            println!(
                "{}",
                match delivered {
                    Some(d) => serde_json::json!({
                        "claimed": true,
                        "skipped_duplicate": d.skipped_duplicate,
                        "outputs": d.outputs,
                    }),
                    None => serde_json::json!({ "claimed": false }),
                }
            );
            Ok(())
        }
        Some("apply") => {
            // Dev tool: applies one operation envelope from a file, the
            // way a transport hands one over. The crash harness aborts
            // this command at apply's commit boundaries and asserts the
            // next command finds either nothing or exactly one.
            let (Some("--file"), Some(path)) = (args.next(), args.next()) else {
                return Err(usage.to_string());
            };
            let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
            let applied = store
                .apply_operation("cli", &bytes, gilune_kernel::limits::Limits::DEFAULT)
                .map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "op_id": applied.op_id,
                    "device_id": applied.device_id,
                    "op_seq": applied.op_seq,
                    "duplicate": applied.duplicate,
                    "uninterpreted": applied.uninterpreted,
                    "blob_wanted": applied.blob_wanted.map(|b| b.blob_id),
                })
            );
            Ok(())
        }
        Some("alerts") => {
            let listed: Vec<_> = store
                .integrity_alerts()
                .map_err(|e| e.to_string())?
                .into_iter()
                .map(|a| {
                    serde_json::json!({
                        "kind": a.kind,
                        "subject": a.subject,
                        "detail": a.detail,
                        "raised_ms": a.raised_ms,
                        // The kernel says which conditions are damage, so
                        // no reader keeps its own copy of the kind set.
                        "damage": a.damage,
                    })
                })
                .collect();
            println!("{}", serde_json::json!({ "alerts": listed }));
            Ok(())
        }
        Some("project") => {
            let p = store.project().map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "written": p.written,
                    "unchanged": p.unchanged,
                    "held_back": p.held_back,
                })
            );
            Ok(())
        }
        Some("reconcile") => {
            let r = store.reconcile().map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "edits": r.edits,
                    "renames": r.renames,
                    "tombstone_proposals": r.tombstone_proposals,
                    "imports": r.imports,
                    "quarantined": r.quarantined,
                })
            );
            Ok(())
        }
        Some("reconcile-path") => {
            let Some(rel) = args.next() else { return Err(usage.to_string()) };
            let r = store.reconcile_path(rel).map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "edits": r.edits,
                    "renames": r.renames,
                    "tombstone_proposals": r.tombstone_proposals,
                    "imports": r.imports,
                    "quarantined": r.quarantined,
                })
            );
            Ok(())
        }
        Some("changes") => {
            // The vault change feed, for a person (ADR 0020 § 8.9).
            // `--since` takes a cursor as this command prints it —
            // `<epoch>:<seq>` — and defaults to the beginning, which is
            // the sanctioned full read.
            let mut cursor = store.change_origin().map_err(|e| e.to_string())?;
            let mut limit = 100u32;
            loop {
                match (args.next(), args.next()) {
                    (None, _) => break,
                    (Some("--since"), Some(value)) => {
                        let (epoch, seq) = value.rsplit_once(':').ok_or_else(|| usage.to_string())?;
                        cursor = gilune_kernel::changes::Cursor {
                            epoch: epoch.to_string(),
                            seq: seq.parse().map_err(|_| usage.to_string())?,
                        };
                    }
                    (Some("--limit"), Some(value)) => {
                        limit = value.parse().map_err(|_| usage.to_string())?
                    }
                    _ => return Err(usage.to_string()),
                }
            }
            let page = store.changes_since(&cursor, limit).map_err(|e| e.to_string())?;
            let listed: Vec<_> = page
                .changes
                .iter()
                .map(|c| {
                    serde_json::json!({
                        "seq": c.seq,
                        "kind": c.kind.as_str(),
                        "rel_path": c.rel_path,
                        "previous_rel_path": c.previous_rel_path,
                        "object_id": c.object_id,
                        "revision_id": c.revision_id,
                        // Best-effort by contract: exact where the
                        // kernel knows, never something to branch on.
                        "cause": c.cause.as_str(),
                        "created_ms": c.created_ms,
                    })
                })
                .collect();
            println!(
                "{}",
                serde_json::json!({
                    "changes": listed,
                    "next": format!("{}:{}", page.next.epoch, page.next.seq),
                    "complete": page.complete,
                })
            );
            Ok(())
        }
        Some("backfill") => {
            let queued = store.backfill_jobs().map_err(|e| e.to_string())?;
            println!("{}", serde_json::json!({ "queued": queued }));
            Ok(())
        }
        Some("plugins") => {
            match (args.next(), args.next()) {
                (None, _) => {}
                (Some("enable"), Some(id)) => {
                    store.set_plugin_enabled(id, true).map_err(|e| e.to_string())?
                }
                (Some("disable"), Some(id)) => {
                    store.set_plugin_enabled(id, false).map_err(|e| e.to_string())?
                }
                (Some("remove"), Some(id)) => {
                    store.remove_plugin(id).map_err(|e| e.to_string())?
                }
                _ => return Err(usage.to_string()),
            }
            let listed: Vec<_> = store
                .installed_plugins()
                .map_err(|e| e.to_string())?
                .into_iter()
                .map(|p| {
                    serde_json::json!({
                        "plugin_id": p.descriptor.plugin_id,
                        "name": p.descriptor.name,
                        "class": p.descriptor.class.as_str(),
                        "profile": p.descriptor.profile.as_str(),
                        "state": p.state.as_str(),
                        "required": p.descriptor.required,
                        "subscribes": p.descriptor.subscribes,
                        "granted": p.granted,
                        "ungranted": p.ungranted(),
                    })
                })
                .collect();
            println!("{}", serde_json::json!({ "plugins": listed }));
            Ok(())
        }
        Some("verify") => {
            let problems = store.verify().map_err(|e| e.to_string())?;
            for problem in &problems {
                eprintln!("verify-problem: {problem}");
            }
            if problems.is_empty() {
                println!("{}", serde_json::json!({ "ok": true }));
                Ok(())
            } else {
                Err(format!("{} integrity problem(s)", problems.len()))
            }
        }
        _ => Err(usage.to_string()),
    }
}
