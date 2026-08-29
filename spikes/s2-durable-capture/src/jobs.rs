//! Events, subscriptions, and durable jobs — the KERNEL half of processing.
//! At-least-once delivery with leases; idempotent output commit keyed exactly
//! as 04-processing-model.md specifies (sorted input revision set + plugin +
//! processor + version). This module knows no domain types: what a handler
//! does lives in `processors.rs` (the plugin stand-in) behind a narrow
//! read-only context.

use crate::crashpoints::{crashpoint, handler_should_fail};
use crate::{ids, processors, store, Kernel};
use anyhow::{anyhow, Result};
use rusqlite::Transaction;

const PROCESSOR_VERSION: &str = "1";

const MAX_ATTEMPTS: i64 = 5;
const LEASE_MS: i64 = 30_000;

/// Kernel-side registration primitive. Which subscriptions exist is declared
/// by the domain side (processors::seed_subscriptions), as a manifest would.
pub fn register_subscription(k: &Kernel, id: &str, event_type: &str, handler: &str) -> Result<()> {
    k.conn.execute(
        "INSERT OR IGNORE INTO subscriptions (subscription_id, event_type, handler)
         VALUES (?1, ?2, ?3)",
        rusqlite::params![id, event_type, handler],
    )?;
    Ok(())
}

/// Disable/enable all subscriptions: the S2-fidelity version of disabling
/// the domain plugins. Disabled subscriptions get no jobs for new events;
/// re-enabling lets startup recovery backfill jobs for committed events.
pub fn set_subscriptions_enabled(k: &Kernel, enabled: bool) -> Result<usize> {
    Ok(k
        .conn
        .execute("UPDATE subscriptions SET enabled = ?1", [enabled as i64])?)
}

/// Emit an event and create its subscription jobs inside the caller's
/// transaction — an event cannot exist without its jobs and vice versa.
#[allow(clippy::too_many_arguments)]
pub fn emit_event_in_tx(
    tx: &Transaction,
    event_id: &str,
    event_type: &str,
    producer: &str,
    subject_object: &str,
    subject_revision: &str,
    causation: Option<&str>,
    correlation: &str,
    now: i64,
) -> Result<()> {
    tx.execute(
        "INSERT INTO events (event_id, event_type, schema_version, transaction_id, producer,
                             subject_object_id, subject_revision_id, causation_event_id,
                             correlation_id, committed_at)
         VALUES (?1, ?2, 1, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        rusqlite::params![
            event_id,
            event_type,
            ids::new_id(),
            producer,
            subject_object,
            subject_revision,
            causation,
            correlation,
            now
        ],
    )?;
    create_jobs_for_event_in_tx(tx, event_id, event_type, now)?;
    Ok(())
}

fn create_jobs_for_event_in_tx(
    tx: &Transaction,
    event_id: &str,
    event_type: &str,
    now: i64,
) -> Result<()> {
    let mut stmt = tx.prepare(
        "SELECT subscription_id, handler FROM subscriptions WHERE enabled = 1 AND event_type = ?1",
    )?;
    let subs: Vec<(String, String)> = stmt
        .query_map([event_type], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<std::result::Result<_, _>>()?;
    for (sub_id, handler) in subs {
        tx.execute(
            "INSERT OR IGNORE INTO jobs (job_id, event_id, subscription_id, handler, status, next_eligible_at)
             VALUES (?1, ?2, ?3, ?4, 'pending', ?5)",
            rusqlite::params![ids::new_id(), event_id, sub_id, handler, now],
        )?;
    }
    Ok(())
}

/// Recovery: any committed event must have jobs for currently enabled
/// subscriptions (covers a crash between historic schema changes or a
/// subscription enabled after the event committed).
pub fn ensure_jobs_for_committed_events(k: &mut Kernel) -> Result<()> {
    let now = Kernel::now_ms();
    let tx = k.conn.transaction()?;
    {
        let mut stmt = tx.prepare(
            "SELECT e.event_id, e.event_type FROM events e",
        )?;
        let events: Vec<(String, String)> = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<std::result::Result<_, _>>()?;
        for (event_id, event_type) in events {
            create_jobs_for_event_in_tx(&tx, &event_id, &event_type, now)?;
        }
    }
    tx.commit()?;
    Ok(())
}

/// Recovery: a lease whose deadline passed goes back to pending.
pub fn expire_stale_leases(k: &Kernel) -> Result<()> {
    k.conn.execute(
        "UPDATE jobs SET status = 'pending', lease_owner = NULL, lease_expires_at = NULL
         WHERE status = 'leased' AND lease_expires_at < ?1",
        [Kernel::now_ms()],
    )?;
    Ok(())
}

/// Force redelivery of completed jobs for an event (duplicate-delivery test
/// hook; the harness uses it to prove idempotency).
pub fn redeliver_event(k: &Kernel, event_id: &str) -> Result<usize> {
    let n = k.conn.execute(
        "UPDATE jobs SET status = 'pending', lease_owner = NULL, lease_expires_at = NULL,
                         next_eligible_at = ?2
         WHERE event_id = ?1",
        rusqlite::params![event_id, Kernel::now_ms()],
    )?;
    Ok(n)
}

pub fn retry_quarantined(k: &Kernel) -> Result<usize> {
    let n = k.conn.execute(
        "UPDATE jobs SET status = 'pending', attempts = 0, last_error = NULL,
                         next_eligible_at = ?1
         WHERE status = 'quarantined'",
        [Kernel::now_ms()],
    )?;
    Ok(n)
}

/// Claim and run eligible jobs until none remain. Returns jobs completed.
pub fn work(k: &mut Kernel) -> Result<usize> {
    let mut done = 0;
    loop {
        let claimed = claim_next(k)?;
        let Some((job_id, event_id, handler)) = claimed else {
            return Ok(done);
        };
        crashpoint("job.after_claim");
        match run_handler(k, &event_id, &handler) {
            Ok(outcome) => {
                commit_outputs(k, &job_id, &handler, outcome)?;
                done += 1;
            }
            Err(e) => record_failure(k, &job_id, &e.to_string())?,
        }
    }
}

fn claim_next(k: &mut Kernel) -> Result<Option<(String, String, String)>> {
    let now = Kernel::now_ms();
    let owner = format!("pid-{}", std::process::id());
    let tx = k.conn.transaction()?;
    let row: Option<(String, String, String)> = tx
        .query_row(
            "SELECT job_id, event_id, handler FROM jobs
             WHERE status = 'pending' AND next_eligible_at <= ?1
             ORDER BY next_eligible_at LIMIT 1",
            [now],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .map(Some)
        .or_else(|e| match e {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(other),
        })?;
    if let Some((job_id, _, _)) = &row {
        tx.execute(
            "UPDATE jobs SET status = 'leased', lease_owner = ?2, lease_expires_at = ?3,
                             attempts = attempts + 1
             WHERE job_id = ?1",
            rusqlite::params![job_id, owner, now + LEASE_MS],
        )?;
    }
    tx.commit()?;
    Ok(row)
}

/// What a handler produced: the domain half (a proposed output) plus the
/// causal metadata the kernel attaches, or nothing (no output warranted).
pub struct HandlerOutcome {
    pub idempotency_key: String,
    pub input_revisions: Vec<String>,
    pub output: Option<PlannedOutput>,
}

pub struct PlannedOutput {
    pub proposed: processors::ProposedOutput,
    pub causation_event: String,
    pub correlation: String,
}

/// External work happens OUTSIDE any database transaction, and the handler
/// sees only the narrow ProcessorCtx — never the kernel connection.
fn run_handler(k: &Kernel, event_id: &str, handler: &str) -> Result<HandlerOutcome> {
    if handler_should_fail(handler) {
        return Err(anyhow!("injected failure for {handler}"));
    }
    let (subject_object, subject_revision, correlation): (String, String, String) =
        k.conn.query_row(
            "SELECT subject_object_id, subject_revision_id, correlation_id
             FROM events WHERE event_id = ?1",
            [event_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )?;
    let mut input_revisions = vec![subject_revision.clone()];
    input_revisions.sort();
    let idempotency_key = format!(
        "{}|org.memory.spike|{}|{}",
        input_revisions.join(","),
        handler,
        PROCESSOR_VERSION
    );

    let ctx = processors::ProcessorCtx::new(k);
    let output = processors::dispatch(&ctx, handler, &subject_object, &subject_revision)?.map(
        |proposed| PlannedOutput {
            proposed,
            causation_event: event_id.to_string(),
            correlation: correlation.clone(),
        },
    );
    crashpoint("job.after_work");
    Ok(HandlerOutcome {
        idempotency_key,
        input_revisions,
        output,
    })
}

/// Commit run + outputs + provenance + follow-on event + job completion in
/// one transaction. A repeated delivery finds the recorded run and completes
/// the job against it without new outputs.
fn commit_outputs(k: &mut Kernel, job_id: &str, handler: &str, o: HandlerOutcome) -> Result<()> {
    let now = Kernel::now_ms();
    let device = k.device_id.clone();
    let tx = k.conn.transaction()?;
    {
        let existing: Option<String> = tx
            .query_row(
                "SELECT run_id FROM processing_runs WHERE idempotency_key = ?1",
                [&o.idempotency_key],
                |r| r.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                other => Err(other),
            })?;
        let run_id = match existing {
            Some(run_id) => run_id,
            None => {
                let run_id = ids::deterministic_id("run", &o.idempotency_key);
                let mut output_ids = Vec::new();
                if let Some(out) = &o.output {
                    let object_id = ids::deterministic_id("object", &o.idempotency_key);
                    let revision_id = ids::deterministic_id("revision", &o.idempotency_key);
                    let op_id = ids::new_id();
                    let seq = store::next_device_seq(&tx, &device)?;
                    tx.execute(
                        "INSERT INTO operations (operation_id, device_id, device_seq, kind, target_id, committed_at)
                         VALUES (?1, ?2, ?3, 'object-created', ?4, ?5)",
                        rusqlite::params![op_id, device, seq, object_id, now],
                    )?;
                    tx.execute(
                        "INSERT INTO objects (object_id, type_id, schema_version, class, origin_device, created_operation, display_created_at)
                         VALUES (?1, ?2, 1, ?3, ?4, ?5, ?6)",
                        rusqlite::params![object_id, out.proposed.type_id, out.proposed.class, device, op_id, now],
                    )?;
                    tx.execute(
                        "INSERT INTO revisions (revision_id, object_id, based_on_json, payload_json, created_operation, created_at)
                         VALUES (?1, ?2, '[]', ?3, ?4, ?5)",
                        rusqlite::params![revision_id, object_id, out.proposed.payload.to_string(), op_id, now],
                    )?;
                    tx.execute(
                        "INSERT INTO heads (object_id, revision_id) VALUES (?1, ?2)",
                        rusqlite::params![object_id, revision_id],
                    )?;
                    tx.execute(
                        "INSERT INTO provenance (provenance_id, artifact_object_id, run_id, inputs_json)
                         VALUES (?1, ?2, ?3, ?4)",
                        rusqlite::params![
                            ids::new_id(),
                            object_id,
                            run_id,
                            serde_json::to_string(&o.input_revisions)?
                        ],
                    )?;
                    let event_id = ids::deterministic_id("event", &o.idempotency_key);
                    emit_event_in_tx(
                        &tx,
                        &event_id,
                        out.proposed.event_type,
                        "org.memory.spike",
                        &object_id,
                        &revision_id,
                        Some(out.causation_event.as_str()),
                        &out.correlation,
                        now,
                    )?;
                    output_ids.push(object_id);
                }
                tx.execute(
                    "INSERT INTO processing_runs (run_id, idempotency_key, processor, processor_version,
                                                  input_revisions_json, output_ids_json, status, finished_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'completed', ?7)",
                    rusqlite::params![
                        run_id,
                        o.idempotency_key,
                        handler,
                        PROCESSOR_VERSION,
                        serde_json::to_string(&o.input_revisions)?,
                        serde_json::to_string(&output_ids)?,
                        now
                    ],
                )?;
                run_id
            }
        };
        tx.execute(
            "UPDATE jobs SET status = 'completed', processing_run_id = ?2,
                             lease_owner = NULL, lease_expires_at = NULL
             WHERE job_id = ?1",
            rusqlite::params![job_id, run_id],
        )?;
        crashpoint("output.commit.inside");
    }
    tx.commit()?;
    Ok(())
}

fn record_failure(k: &Kernel, job_id: &str, error: &str) -> Result<()> {
    let attempts: i64 = k.conn.query_row(
        "SELECT attempts FROM jobs WHERE job_id = ?1",
        [job_id],
        |r| r.get(0),
    )?;
    if attempts >= MAX_ATTEMPTS {
        k.conn.execute(
            "UPDATE jobs SET status = 'quarantined', last_error = ?2,
                             lease_owner = NULL, lease_expires_at = NULL
             WHERE job_id = ?1",
            rusqlite::params![job_id, error],
        )?;
    } else {
        let base: i64 = std::env::var("GILUNE_BACKOFF_BASE_MS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(100);
        let backoff = base * (1 << attempts.min(10));
        k.conn.execute(
            "UPDATE jobs SET status = 'pending', last_error = ?2,
                             lease_owner = NULL, lease_expires_at = NULL,
                             next_eligible_at = ?3
             WHERE job_id = ?1",
            rusqlite::params![job_id, error, Kernel::now_ms() + backoff],
        )?;
    }
    Ok(())
}
