//! Spike S2 CLI. Every command opens the kernel, which runs the startup
//! recovery checks — so simply invoking any command after a crash exercises
//! recovery, which is exactly what the fault harness does.

use anyhow::{bail, Context, Result};
use spike_s2::{capture, export, jobs, processors, vault, Kernel};
use std::path::PathBuf;

fn usage() -> ! {
    eprintln!(
        "usage: spike-s2 --vault <dir> <command>\n\
         commands:\n\
           init\n\
           capture (--text <s> | --file <path> --media-type <mt>)\n\
           work\n\
           project\n\
           reconcile\n\
           redeliver-latest <event-type>\n\
           retry-quarantined\n\
           disable-processors | enable-processors\n\
           dev-add-object <type-id> <payload-json>\n\
           dev-tombstone <object-id>\n\
           sync-push <relay-dir> | sync-pull <relay-dir>\n\
           export <dest>\n\
           verify-export <dest>\n\
           status\n\
           verify"
    );
    std::process::exit(2);
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut it = args.iter();
    let mut vault_dir: Option<PathBuf> = None;
    let mut rest: Vec<String> = Vec::new();
    while let Some(a) = it.next() {
        if a == "--vault" {
            vault_dir = Some(PathBuf::from(it.next().map(String::as_str).unwrap_or("")));
        } else {
            rest.push(a.clone());
            rest.extend(it.cloned());
            break;
        }
    }
    let Some(vault_dir) = vault_dir else { usage() };
    if rest.is_empty() {
        usage()
    }
    let cmd = rest[0].as_str();
    let mut k = Kernel::open(&vault_dir)?;

    match cmd {
        "init" => {
            processors::seed_subscriptions(&k)?;
            println!("initialised device={}", k.device_id);
        }
        "disable-processors" => {
            let n = jobs::set_subscriptions_enabled(&k, false)?;
            println!("disabled {n} subscription(s)");
        }
        "enable-processors" => {
            let n = jobs::set_subscriptions_enabled(&k, true)?;
            println!("enabled {n} subscription(s)");
        }
        "dev-add-object" => {
            let type_id = rest.get(1).cloned().unwrap_or_else(|| usage());
            let payload = rest.get(2).cloned().unwrap_or_else(|| usage());
            let value: serde_json::Value =
                serde_json::from_str(&payload).context("payload must be JSON")?;
            let object_id = spike_s2::store::dev_add_object(&mut k, &type_id, &value)?;
            println!("added {object_id}");
        }
        "dev-tombstone" => {
            let object_id = rest.get(1).cloned().unwrap_or_else(|| usage());
            spike_s2::store::tombstone_object(&mut k, &object_id)?;
            println!("tombstoned {object_id}");
        }
        "sync-push" => {
            let relay = rest.get(1).map(PathBuf::from).unwrap_or_else(|| usage());
            let r = spike_s2::sync::push(&mut k, &relay)?;
            println!("pushed ops={} blobs={}", r.ops_written, r.blobs_written);
        }
        "sync-pull" => {
            let relay = rest.get(1).map(PathBuf::from).unwrap_or_else(|| usage());
            let r = spike_s2::sync::pull(&mut k, &relay)?;
            println!(
                "applied={} blobs_completed={} pending_blobs={} gaps={} corrupt={}",
                r.ops_applied, r.blobs_completed, r.pending_blobs, r.gaps, r.corrupt
            );
        }
        "capture" => {
            let (bytes, media_type) = parse_capture_args(&rest[1..])?;
            let out = capture::capture(&mut k, &bytes, &media_type)?;
            // "saved" is printed only once the commit is recoverable.
            println!("saved {}", out.object_id);
            println!("blob {}", out.blob_rel_path);
        }
        "work" => {
            let n = jobs::work(&mut k)?;
            println!("completed {n}");
        }
        "project" => {
            let n = vault::project(&mut k)?;
            println!("projected {n}");
        }
        "reconcile" => {
            let r = vault::reconcile(&mut k)?;
            println!(
                "edits={} renames={} tombstone_proposals={} quarantined={} missing_blobs={}",
                r.external_edits, r.renames, r.tombstone_proposals, r.quarantined, r.missing_blobs
            );
        }
        "redeliver-latest" => {
            let event_type = rest.get(1).map(String::as_str).unwrap_or_else(|| usage());
            let event_id: String = k
                .conn
                .query_row(
                    "SELECT event_id FROM events WHERE event_type = ?1
                     ORDER BY committed_at DESC, event_id DESC LIMIT 1",
                    [event_type],
                    |r| r.get(0),
                )
                .context("no event of that type")?;
            let n = jobs::redeliver_event(&k, &event_id)?;
            println!("redelivered {n} job(s) for {event_id}");
        }
        "retry-quarantined" => {
            let n = jobs::retry_quarantined(&k)?;
            println!("retried {n}");
        }
        "export" => {
            let dest = rest.get(1).map(PathBuf::from).unwrap_or_else(|| usage());
            let m = export::export(&k, &dest)?;
            println!("exported files={} alerts={}", m.files.len(), m.integrity_alerts);
        }
        "verify-export" => {
            let dest = rest.get(1).map(PathBuf::from).unwrap_or_else(|| usage());
            let problems = export::verify_export(&dest)?;
            if problems.is_empty() {
                println!("export-ok");
            } else {
                for p in &problems {
                    println!("export-problem {p}");
                }
                std::process::exit(1);
            }
        }
        "status" => print_status(&k)?,
        "verify" => {
            let problems = verify(&k)?;
            if problems.is_empty() {
                println!("verify-ok");
            } else {
                for p in &problems {
                    println!("verify-problem {p}");
                }
                std::process::exit(1);
            }
        }
        _ => usage(),
    }
    Ok(())
}

fn parse_capture_args(args: &[String]) -> Result<(Vec<u8>, String)> {
    let mut text: Option<String> = None;
    let mut file: Option<PathBuf> = None;
    let mut media_type: Option<String> = None;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--text" => text = it.next().cloned(),
            "--file" => file = it.next().map(PathBuf::from),
            "--media-type" => media_type = it.next().cloned(),
            other => bail!("unknown capture arg {other}"),
        }
    }
    match (text, file) {
        (Some(t), None) => Ok((t.into_bytes(), "text/plain".into())),
        (None, Some(f)) => {
            let bytes = std::fs::read(&f)?;
            Ok((bytes, media_type.unwrap_or_else(|| "audio/wav".into())))
        }
        _ => bail!("capture needs exactly one of --text or --file"),
    }
}

fn count(k: &Kernel, sql: &str) -> Result<i64> {
    Ok(k.conn.query_row(sql, [], |r| r.get(0))?)
}

fn print_status(k: &Kernel) -> Result<()> {
    println!("objects={}", count(k, "SELECT COUNT(*) FROM objects")?);
    println!(
        "sources={}",
        count(k, "SELECT COUNT(*) FROM objects WHERE class='source'")?
    );
    println!(
        "transcripts={}",
        count(
            k,
            "SELECT COUNT(*) FROM objects WHERE type_id='org.memory.transcription/transcript'"
        )?
    );
    println!(
        "tasks={}",
        count(
            k,
            "SELECT COUNT(*) FROM objects WHERE type_id='org.memory.tasks/task'"
        )?
    );
    println!(
        "tombstone_proposals={}",
        count(
            k,
            "SELECT COUNT(*) FROM objects WHERE type_id='org.memory.vault/tombstone-proposal'"
        )?
    );
    println!("events={}", count(k, "SELECT COUNT(*) FROM events")?);
    println!(
        "pending_jobs={}",
        count(k, "SELECT COUNT(*) FROM jobs WHERE status='pending'")?
    );
    println!(
        "leased_jobs={}",
        count(k, "SELECT COUNT(*) FROM jobs WHERE status='leased'")?
    );
    println!(
        "completed_jobs={}",
        count(k, "SELECT COUNT(*) FROM jobs WHERE status='completed'")?
    );
    println!(
        "quarantined_jobs={}",
        count(k, "SELECT COUNT(*) FROM jobs WHERE status='quarantined'")?
    );
    println!("runs={}", count(k, "SELECT COUNT(*) FROM processing_runs")?);
    println!(
        "alerts={}",
        count(k, "SELECT COUNT(*) FROM integrity_alerts")?
    );
    println!(
        "quarantine_files={}",
        count(k, "SELECT COUNT(*) FROM quarantine_files")?
    );
    Ok(())
}

fn verify(k: &Kernel) -> Result<Vec<String>> {
    spike_s2::store::verify_invariants(k)
}
