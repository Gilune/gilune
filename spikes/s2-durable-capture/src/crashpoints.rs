//! Named crash points for the fault harness.
//!
//! When the environment variable `GILUNE_CRASHPOINT` equals a point's name,
//! reaching that point aborts the process without unwinding — the closest
//! portable stand-in for sudden termination. The harness runs each scenario
//! once per point and then asserts the recovery invariants.

pub const ALL: &[&str] = &[
    "stage.after_write",     // bytes written, not yet fsynced
    "stage.after_fsync",     // staged durably, nothing committed
    "commit.inside",         // inside the capture transaction, before COMMIT
    "commit.after",          // committed, blob not yet promoted
    "promote.after",         // blob renamed into the vault, state not yet 'ready'
    "job.after_claim",       // lease held, no work done
    "job.after_work",        // external work done, outputs not committed
    "output.commit.inside",  // inside the output transaction, before COMMIT
    "vault.during_projection", // some projection files written, not all
    "reconcile.during",      // mid-way through ingesting external edits
    "migration.during",      // inside a schema migration step, before commit
    "sync.during_push",      // some operations published, not all
    "sync.during_pull",      // some remote operations applied, not all
];

pub fn crashpoint(name: &str) {
    if let Ok(armed) = std::env::var("GILUNE_CRASHPOINT") {
        if armed == name {
            eprintln!("CRASHPOINT {name}: aborting");
            std::process::abort();
        }
    }
}

/// Deterministic handler failure for retry/quarantine tests: set
/// `GILUNE_FAIL_HANDLER=<handler>` to make that handler return an error.
pub fn handler_should_fail(handler: &str) -> bool {
    std::env::var("GILUNE_FAIL_HANDLER").map(|v| v == handler).unwrap_or(false)
}
