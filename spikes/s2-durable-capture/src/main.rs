//! Spike S2 CLI. Every command opens the kernel, which runs the startup
//! recovery checks — so simply invoking any command after a crash exercises
//! recovery, which is exactly what the fault harness does.

use anyhow::{bail, Context, Result};
use spike_s2::{capture, export, jobs, vault, Kernel};
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
            jobs::seed_subscriptions(&k)?;
            println!("initialised device={}", k.device_id);
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
            "SELECT COUNT(*) FROM objects WHERE type_id='org.memory.review/tombstone-proposal'"
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

/// Internal invariants the harness asserts after every recovery:
/// nothing reported saved may be unrecoverable, and operational state must
/// be self-consistent.
fn verify(k: &Kernel) -> Result<Vec<String>> {
    let mut problems = Vec::new();

    // Every ready blob's bytes exist and hash correctly.
    for (blob_id, rel, sha) in capture::blob_paths(k)? {
        let path = k.vault.join(&rel);
        match std::fs::read(&path) {
            Ok(bytes) => {
                if spike_s2::ids::sha256_hex(&bytes) != sha {
                    problems.push(format!("blob {blob_id} content hash mismatch"));
                }
            }
            Err(_) => problems.push(format!("blob {blob_id} file missing ({})", rel.display())),
        }
    }

    // No blob may remain unpromoted after recovery unless an alert says why.
    let committed: i64 = count(k, "SELECT COUNT(*) FROM blobs WHERE state='committed'")?;
    let missing_alerts: i64 = count(
        k,
        "SELECT COUNT(*) FROM integrity_alerts WHERE kind='missing-blob-bytes'",
    )?;
    if committed > missing_alerts {
        problems.push(format!(
            "{committed} unpromoted blobs but only {missing_alerts} alerts"
        ));
    }

    // Every event has a job per enabled matching subscription.
    let missing_jobs: i64 = count(
        k,
        "SELECT COUNT(*) FROM events e JOIN subscriptions s
             ON s.enabled = 1 AND s.event_type = e.event_type
         WHERE NOT EXISTS (SELECT 1 FROM jobs j
                           WHERE j.event_id = e.event_id
                             AND j.subscription_id = s.subscription_id)",
    )?;
    if missing_jobs > 0 {
        problems.push(format!("{missing_jobs} committed events lack jobs"));
    }

    // Completed jobs reference an existing processing run.
    let dangling: i64 = count(
        k,
        "SELECT COUNT(*) FROM jobs j WHERE j.status='completed'
           AND (j.processing_run_id IS NULL
                OR NOT EXISTS (SELECT 1 FROM processing_runs r
                               WHERE r.run_id = j.processing_run_id))",
    )?;
    if dangling > 0 {
        problems.push(format!("{dangling} completed jobs without runs"));
    }

    // Idempotency: at most one transcript per source head.
    let dup_transcripts: i64 = count(
        k,
        "SELECT COALESCE(MAX(cnt), 0) FROM (
             SELECT COUNT(*) AS cnt FROM provenance p
             JOIN objects o ON o.object_id = p.artifact_object_id
             WHERE o.type_id = 'org.memory.transcription/transcript'
             GROUP BY p.inputs_json)",
    )?;
    if dup_transcripts > 1 {
        problems.push(format!(
            "duplicate transcripts for one input set (max {dup_transcripts})"
        ));
    }

    // Heads exist for every live object.
    let headless: i64 = count(
        k,
        "SELECT COUNT(*) FROM objects o WHERE o.tombstone_json IS NULL
           AND NOT EXISTS (SELECT 1 FROM heads h WHERE h.object_id = o.object_id)",
    )?;
    if headless > 0 {
        problems.push(format!("{headless} live objects without heads"));
    }

    Ok(problems)
}
