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

    let usage = "usage: gilune-cli --vault <dir> <init|capture --text <text>|status|verify>";
    let (Some("--vault"), Some(vault)) = (args.next(), args.next()) else {
        return Err(usage.to_string());
    };
    let store = Store::open(vault).map_err(|e| e.to_string())?;

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
                .capture_bytes("md", text.as_bytes())
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
                })
            );
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
