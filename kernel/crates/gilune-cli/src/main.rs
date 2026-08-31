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

    let usage = "usage: gilune-cli --vault <dir> <init|capture --text <text>|work|project|\
                 reconcile|status|verify|backfill|plugins [enable|disable|remove <id>]>";
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
                    "jobs_quarantined": s.jobs_quarantined,
                    "operations": s.operations,
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
                    "failed": s.failed,
                    "outputs": s.outputs,
                })
            );
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
