//! Deliberate process aborts at named persistence boundaries, the
//! portable stand-in for sudden termination. Production cost is one
//! cached environment read; the crash-matrix harness sets
//! `GILUNE_CRASHPOINT=<point>` on a subprocess and asserts recovery.

use std::sync::OnceLock;

static ARMED: OnceLock<Option<String>> = OnceLock::new();

/// Abort the process here iff this point is armed via the environment.
pub fn crashpoint(point: &str) {
    let armed = ARMED.get_or_init(|| std::env::var("GILUNE_CRASHPOINT").ok());
    if armed.as_deref() == Some(point) {
        // abort, not panic: no unwinding, no destructors — a power cut.
        eprintln!("crashpoint hit: {point}");
        std::process::abort();
    }
}
