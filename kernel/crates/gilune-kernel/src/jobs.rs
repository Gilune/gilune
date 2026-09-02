//! Durable jobs (constitution rule 7, ADR 0004): work created
//! transactionally with the event that caused it, claimed under leases,
//! retried with backoff, quarantined after repeated failure — never
//! lost, never blocking capture. Handlers run at-least-once and must be
//! idempotent; the kernel promises delivery, not exactly-once execution.
//! Callers supply `now_ms` so time-dependent behaviour is testable.

use crate::store::Store;
use crate::{KernelError, Result};

pub const MAX_ATTEMPTS: i64 = 5;
const BACKOFF_BASE_MS: i64 = 1_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Job {
    pub job_id: String,
    pub subscription_id: String,
    pub event_id: String,
    pub event_type: String,
    pub subject_id: String,
    pub payload: String,
    pub attempts: i64,
}

/// Which processors a claim may answer for.
enum JobFilter<'a> {
    Any,
    Only(&'a str),
    Excluding(&'a [String]),
}

impl Store {
    /// Register interest in an event type. Jobs are created only for
    /// events committed while a subscription exists, and claimed only
    /// while it is enabled — disabling parks waiting jobs rather than
    /// destroying them, so re-enabling resumes where work stopped. Events
    /// that passed while no subscription existed are not backfilled.
    pub fn subscribe(&self, subscription_id: &str, event_type: &str) -> Result<()> {
        self.conn().execute(
            "INSERT OR REPLACE INTO subscriptions (subscription_id, event_type, enabled)
             VALUES (?1, ?2, 1)",
            [subscription_id, event_type],
        )?;
        Ok(())
    }

    /// Queue work for events that no enabled subscription ever saw —
    /// captures made before a plugin was installed, or while it was off.
    /// Deliberate by design: switching a plugin on must not silently
    /// re-run the past, but a person may ask for the catch-up. Safe to
    /// repeat: processing runs stay idempotent, so an event that was
    /// already handled produces nothing new. Returns jobs queued.
    pub fn backfill_jobs(&self) -> Result<i64> {
        let queued = self.conn().execute(
            "INSERT OR IGNORE INTO jobs
             (job_id, subscription_id, event_id, state, attempts, not_before_ms)
             SELECT e.event_id || s.subscription_id, s.subscription_id, e.event_id,
                    'pending', 0, 0
             FROM events e
             JOIN subscriptions s ON s.event_type = e.event_type
             WHERE s.enabled = 1
               AND NOT EXISTS (
                   SELECT 1 FROM jobs j
                   WHERE j.subscription_id = s.subscription_id AND j.event_id = e.event_id
               )",
            [],
        )?;
        Ok(queued as i64)
    }

    /// Claim the next runnable job under a lease. A job whose lease has
    /// expired is claimable again — that is at-least-once, by design, and
    /// it is also the whole of abandoned-claim recovery: a claimant that
    /// dies holding a job blocks it for the rest of its lease and no
    /// longer, whether that claimant was a Rust processor or an external
    /// implementation across the FFI (ADR 0018 § 2).
    pub fn claim_next_job(&self, now_ms: i64, lease_ms: i64) -> Result<Option<Job>> {
        self.claim_where(JobFilter::Any, now_ms, lease_ms)
    }

    /// Claim the next runnable job for exactly one processor. The external
    /// route claims this way: a host answers for the ids it registered and
    /// for nothing else.
    pub fn claim_next_job_for(
        &self,
        processor_id: &str,
        now_ms: i64,
        lease_ms: i64,
    ) -> Result<Option<Job>> {
        self.claim_where(JobFilter::Only(processor_id), now_ms, lease_ms)
    }

    /// Claim the next runnable job that no listed processor owns.
    pub fn claim_next_job_excluding(
        &self,
        processor_ids: &[String],
        now_ms: i64,
        lease_ms: i64,
    ) -> Result<Option<Job>> {
        if processor_ids.is_empty() {
            return self.claim_next_job(now_ms, lease_ms);
        }
        self.claim_where(JobFilter::Excluding(processor_ids), now_ms, lease_ms)
    }

    fn claim_where(&self, filter: JobFilter<'_>, now_ms: i64, lease_ms: i64) -> Result<Option<Job>> {
        let conn = self.conn();
        // The subscription id is always a bound parameter, never text spliced
        // into the statement: an id is host-supplied data.
        let (clause, ids): (String, Vec<&str>) = match filter {
            JobFilter::Any => (String::new(), Vec::new()),
            JobFilter::Only(id) => (" AND j.subscription_id = ?2".to_string(), vec![id]),
            JobFilter::Excluding(ids) => {
                let holes: Vec<String> =
                    (0..ids.len()).map(|i| format!("?{}", i + 2)).collect();
                (
                    format!(" AND j.subscription_id NOT IN ({})", holes.join(", ")),
                    ids.iter().map(String::as_str).collect(),
                )
            }
        };
        let sql = format!(
            "SELECT j.job_id, j.subscription_id, j.event_id, j.attempts,
                    e.event_type, e.subject_id, e.payload
             FROM jobs j
             JOIN events e ON e.event_id = j.event_id
             JOIN subscriptions s ON s.subscription_id = j.subscription_id
             WHERE s.enabled = 1
               AND ((j.state = 'pending' AND j.not_before_ms <= ?1)
                    OR (j.state = 'leased' AND j.lease_until_ms <= ?1))
               {clause}
             ORDER BY e.created_ms
             LIMIT 1"
        );
        let mut params: Vec<&dyn rusqlite::ToSql> = vec![&now_ms];
        for id in &ids {
            params.push(id);
        }
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let claimed: Result<Option<Job>> = (|| {
            let row = conn
                .query_row(&sql, params.as_slice(), |row| {
                    Ok(Job {
                        job_id: row.get(0)?,
                        subscription_id: row.get(1)?,
                        event_id: row.get(2)?,
                        attempts: row.get::<_, i64>(3)? + 1,
                        event_type: row.get(4)?,
                        subject_id: row.get(5)?,
                        payload: row.get(6)?,
                    })
                })
                .map(Some)
                .or_else(|e| match e {
                    rusqlite::Error::QueryReturnedNoRows => Ok(None),
                    e => Err(e),
                })
                .map_err(KernelError::from)?;
            let Some(job) = row else { return Ok(None) };
            conn.execute(
                "UPDATE jobs SET state = 'leased', attempts = ?2, lease_until_ms = ?3
                 WHERE job_id = ?1",
                rusqlite::params![job.job_id, job.attempts, now_ms + lease_ms],
            )?;
            Ok(Some(job))
        })();
        match &claimed {
            Ok(_) => conn.execute_batch("COMMIT;")?,
            Err(_) => {
                let _ = conn.execute_batch("ROLLBACK;");
            }
        }
        claimed
    }

    pub fn complete_job(&self, job_id: &str) -> Result<()> {
        let changed = self.conn().execute(
            "UPDATE jobs SET state = 'done', lease_until_ms = NULL WHERE job_id = ?1",
            [job_id],
        )?;
        if changed == 0 {
            return Err(KernelError::NoSuchJob { job_id: job_id.to_string() });
        }
        Ok(())
    }

    /// Record a failure: retry with exponential backoff until
    /// `MAX_ATTEMPTS`, then quarantine. A quarantined job is inspectable
    /// and blocks nothing (rule 7).
    pub fn fail_job(&self, job_id: &str, now_ms: i64, error: &str) -> Result<()> {
        let attempts: i64 = self
            .conn()
            .query_row("SELECT attempts FROM jobs WHERE job_id = ?1", [job_id], |row| row.get(0))
            .map_err(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => {
                    KernelError::NoSuchJob { job_id: job_id.to_string() }
                }
                e => KernelError::Storage(e),
            })?;
        if attempts >= MAX_ATTEMPTS {
            self.conn().execute(
                "UPDATE jobs SET state = 'quarantined', last_error = ?2, lease_until_ms = NULL
                 WHERE job_id = ?1",
                rusqlite::params![job_id, error],
            )?;
        } else {
            let backoff = BACKOFF_BASE_MS << (attempts - 1).max(0);
            self.conn().execute(
                "UPDATE jobs SET state = 'pending', last_error = ?2, lease_until_ms = NULL,
                        not_before_ms = ?3
                 WHERE job_id = ?1",
                rusqlite::params![job_id, error, now_ms + backoff],
            )?;
        }
        Ok(())
    }

    /// Diagnostic lookup used by tests and tooling.
    pub fn job_state(&self, job_id: &str) -> Result<String> {
        let state = self
            .conn()
            .query_row("SELECT state FROM jobs WHERE job_id = ?1", [job_id], |row| row.get(0))
            .map_err(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => {
                    KernelError::NoSuchJob { job_id: job_id.to_string() }
                }
                e => KernelError::Storage(e),
            })?;
        Ok(state)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store_with_job() -> (tempfile::TempDir, Store, Job) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store
            .subscribe("transcribe", crate::capture::SOURCE_READY_EVENT)
            .expect("subscribe");
        store.capture_bytes("md", b"job fodder").expect("capture");
        let job = store.claim_next_job(1_000, 30_000).expect("claim").expect("job exists");
        (dir, store, job)
    }

    #[test]
    fn a_capture_creates_a_job_for_each_enabled_subscription() {
        let (_dir, store, job) = store_with_job();
        assert_eq!(job.subscription_id, "transcribe");
        assert_eq!(job.event_type, crate::capture::SOURCE_READY_EVENT);
        assert_eq!(job.attempts, 1);
        assert_eq!(store.status().expect("status").jobs_pending, 1); // leased counts as in-flight
    }

    #[test]
    fn a_leased_job_is_invisible_until_its_lease_expires() {
        let (_dir, store, job) = store_with_job();
        assert!(store.claim_next_job(2_000, 30_000).expect("claim").is_none());
        let reclaimed = store
            .claim_next_job(1_000 + 30_000, 30_000)
            .expect("claim")
            .expect("lease expired, job claimable again");
        assert_eq!(reclaimed.job_id, job.job_id);
        assert_eq!(reclaimed.attempts, 2);
    }

    #[test]
    fn completion_is_terminal() {
        let (_dir, store, job) = store_with_job();
        store.complete_job(&job.job_id).expect("complete");
        assert_eq!(store.job_state(&job.job_id).expect("state"), "done");
        assert!(store.claim_next_job(60_000, 30_000).expect("claim").is_none());
    }

    #[test]
    fn repeated_failure_backs_off_then_quarantines_without_blocking_capture() {
        let (_dir, store, first) = store_with_job();
        let mut job = first;
        let mut now = 1_000;
        for _ in 0..MAX_ATTEMPTS - 1 {
            store.fail_job(&job.job_id, now, "boom").expect("fail");
            assert_eq!(store.job_state(&job.job_id).expect("state"), "pending");
            now += 1_000_000; // past any backoff
            job = store.claim_next_job(now, 30_000).expect("claim").expect("retry");
        }
        store.fail_job(&job.job_id, now, "boom").expect("final fail");
        assert_eq!(store.job_state(&job.job_id).expect("state"), "quarantined");

        store.capture_bytes("md", b"capture still works").expect("capture unblocked");
        assert!(store.claim_next_job(now + 1, 30_000).expect("claim").is_some());
    }

    #[test]
    fn a_capture_made_before_its_plugin_existed_can_be_caught_up_deliberately() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        // Captured while nothing was listening: an event, but no job.
        store.capture_bytes("wav", &[3u8; 32]).expect("capture");
        assert!(store.claim_next_job(1_000, 30_000).expect("claim").is_none());

        store
            .register_plugin(&crate::processor::fake_transcriber_descriptor())
            .expect("install the plugin afterwards");
        assert_eq!(
            store.claim_next_job(1_000, 30_000).expect("claim"),
            None,
            "installing a plugin does not silently re-run the past"
        );

        assert_eq!(store.backfill_jobs().expect("backfill"), 1);
        let summary = store
            .run_pending_jobs(&[&crate::processor::FakeTranscriber], 2_000, 30_000)
            .expect("run");
        assert_eq!(summary.outputs, 1, "the stranded capture is finally processed");
        assert_eq!(store.backfill_jobs().expect("second backfill"), 0);
    }

    #[test]
    fn failing_an_unknown_job_is_an_error_not_a_shrug() {
        let (_dir, store, _job) = store_with_job();
        assert!(matches!(
            store.fail_job("missing", 0, "x"),
            Err(KernelError::NoSuchJob { .. })
        ));
    }
}
