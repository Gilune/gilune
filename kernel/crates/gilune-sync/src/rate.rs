//! Per-peer limits on session establishment, with backoff (ADR 0019 § 8).
//!
//! § 8 lists this among the bounds and ADR 0019's § 11 leaves it to the
//! stage where peer identity exists, which is this one. The shape follows
//! the rest of § 8: a bound is configuration with a documented default,
//! and exceeding it ends the attempt cleanly rather than inventing a
//! second failure mode.
//!
//! Pure over an injected clock, so "an attacker gets one guess every
//! fifteen seconds by the fourth try" is a test rather than a claim.

use crate::{Result, SyncError};
use std::collections::BTreeMap;

/// Failures allowed before backoff begins. Generous enough that a person
/// mistyping a code twice notices nothing.
pub const DEFAULT_BURST: u32 = 3;
/// The first backoff, doubling from there.
pub const DEFAULT_BASE_BACKOFF_MS: i64 = 2_000;
/// The ceiling. Long enough to make a scripted guesser pointless, short
/// enough that a person who walks away and comes back is not locked out.
pub const DEFAULT_MAX_BACKOFF_MS: i64 = 5 * 60_000;
/// How long a quiet peer is remembered before its record is forgotten.
pub const DEFAULT_FORGET_MS: i64 = 60 * 60_000;

#[derive(Debug, Clone, Copy)]
struct Record {
    failures: u32,
    next_allowed_ms: i64,
    last_ms: i64,
}

/// Keyed by whatever names the caller — a peer's static key when it is
/// known, the remote address when it is not. Both are used: a stranger
/// cannot escape the limit by not identifying itself, and a paired peer
/// cannot be locked out by a stranger sharing a NAT with it.
pub struct RateLimiter {
    burst: u32,
    base_backoff_ms: i64,
    max_backoff_ms: i64,
    forget_ms: i64,
    records: BTreeMap<String, Record>,
}

impl Default for RateLimiter {
    fn default() -> Self {
        RateLimiter::new(
            DEFAULT_BURST,
            DEFAULT_BASE_BACKOFF_MS,
            DEFAULT_MAX_BACKOFF_MS,
            DEFAULT_FORGET_MS,
        )
    }
}

impl RateLimiter {
    pub fn new(burst: u32, base_backoff_ms: i64, max_backoff_ms: i64, forget_ms: i64) -> RateLimiter {
        RateLimiter {
            burst,
            base_backoff_ms,
            max_backoff_ms,
            forget_ms,
            records: BTreeMap::new(),
        }
    }

    /// May this caller try now? The refusal names a wait rather than a
    /// count, because a count is a progress bar for a guesser.
    pub fn check(&mut self, key: &str, now_ms: i64) -> Result<()> {
        self.forget(now_ms);
        let Some(record) = self.records.get(key) else { return Ok(()) };
        if now_ms >= record.next_allowed_ms {
            return Ok(());
        }
        let wait = (record.next_allowed_ms - now_ms + 999) / 1000;
        Err(SyncError::Refused {
            detail: format!("too many attempts from that device — try again in {wait}s"),
        })
    }

    /// A completed establishment clears the record: a peer that can
    /// actually connect is not a peer being guessed at.
    pub fn succeeded(&mut self, key: &str) {
        self.records.remove(key);
    }

    pub fn failed(&mut self, key: &str, now_ms: i64) {
        let record = self.records.entry(key.to_string()).or_insert(Record {
            failures: 0,
            next_allowed_ms: 0,
            last_ms: now_ms,
        });
        record.failures += 1;
        record.last_ms = now_ms;
        record.next_allowed_ms = if record.failures <= self.burst {
            now_ms
        } else {
            let steps = (record.failures - self.burst - 1).min(16);
            let delay = self
                .base_backoff_ms
                .saturating_mul(1i64 << steps)
                .min(self.max_backoff_ms);
            now_ms + delay
        };
    }

    /// How long this caller must wait, for a test or a diagnostic.
    pub fn wait_ms(&self, key: &str, now_ms: i64) -> i64 {
        self.records
            .get(key)
            .map(|r| (r.next_allowed_ms - now_ms).max(0))
            .unwrap_or(0)
    }

    fn forget(&mut self, now_ms: i64) {
        let forget_ms = self.forget_ms;
        self.records
            .retain(|_, r| now_ms - r.last_ms < forget_ms || now_ms < r.next_allowed_ms);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn limiter() -> RateLimiter {
        RateLimiter::new(3, 2_000, 60_000, 3_600_000)
    }

    #[test]
    fn a_caller_with_no_history_is_never_held_up() {
        let mut limiter = limiter();
        assert!(limiter.check("peer", 0).is_ok());
        assert!(limiter.check("peer", 1_000_000).is_ok());
    }

    #[test]
    fn the_first_few_failures_cost_nothing_so_a_typo_is_not_a_punishment() {
        let mut limiter = limiter();
        for attempt in 0..3 {
            limiter.failed("peer", attempt * 100);
            assert!(limiter.check("peer", attempt * 100).is_ok());
        }
    }

    #[test]
    fn beyond_the_burst_the_wait_doubles_up_to_its_ceiling() {
        let mut limiter = limiter();
        for _ in 0..3 {
            limiter.failed("peer", 0);
        }
        limiter.failed("peer", 0);
        assert_eq!(limiter.wait_ms("peer", 0), 2_000);
        limiter.failed("peer", 0);
        assert_eq!(limiter.wait_ms("peer", 0), 4_000);
        limiter.failed("peer", 0);
        assert_eq!(limiter.wait_ms("peer", 0), 8_000);
        for _ in 0..20 {
            limiter.failed("peer", 0);
        }
        assert_eq!(
            limiter.wait_ms("peer", 0),
            60_000,
            "the backoff has a ceiling, so a peer is slowed and never locked out forever"
        );
    }

    #[test]
    fn a_held_up_caller_is_refused_and_told_a_wait_rather_than_a_count() {
        let mut limiter = limiter();
        for _ in 0..5 {
            limiter.failed("peer", 0);
        }
        let Err(SyncError::Refused { detail }) = limiter.check("peer", 0) else {
            panic!("refused");
        };
        assert!(detail.contains("try again in"));
        assert!(!detail.contains('5'), "a count is a progress bar: {detail}");
        assert!(limiter.check("peer", 60_000).is_ok(), "and the wait does end");
    }

    #[test]
    fn one_callers_failures_never_hold_up_another() {
        let mut limiter = limiter();
        for _ in 0..10 {
            limiter.failed("noisy", 0);
        }
        assert!(limiter.check("noisy", 0).is_err());
        assert!(limiter.check("quiet", 0).is_ok());
    }

    #[test]
    fn a_successful_establishment_clears_the_record() {
        let mut limiter = limiter();
        for _ in 0..5 {
            limiter.failed("peer", 0);
        }
        limiter.succeeded("peer");
        assert!(limiter.check("peer", 0).is_ok());
        assert_eq!(limiter.wait_ms("peer", 0), 0);
    }

    #[test]
    fn a_quiet_caller_is_eventually_forgotten_so_the_table_cannot_grow_forever() {
        let mut limiter = limiter();
        limiter.failed("peer", 0);
        assert!(limiter.check("peer", 3_600_001).is_ok());
        assert_eq!(limiter.wait_ms("peer", 3_600_001), 0, "the record is gone");
    }
}
