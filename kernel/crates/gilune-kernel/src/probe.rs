//! Test-only instrumentation. Nothing here exists in a shipped binary:
//! outside `cfg(test)` every function is empty and the compiler removes
//! the call.
//!
//! It exists because ADR 0020's gate asks for evidence rather than
//! argument — "an unchanged vault reads zero files" is a claim about the
//! read path, and only the read path can answer it. The counter is a
//! thread-local so that tests running in parallel in one binary each see
//! their own reads.

#[cfg(test)]
thread_local! {
    static FILE_READS: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

/// Called wherever the kernel reads a file out of the vault.
pub(crate) fn file_read() {
    #[cfg(test)]
    FILE_READS.with(|reads| reads.set(reads.get() + 1));
}

#[cfg(test)]
pub(crate) fn file_reads() -> u64 {
    FILE_READS.with(std::cell::Cell::get)
}

#[cfg(test)]
pub(crate) fn reset_file_reads() {
    FILE_READS.with(|reads| reads.set(0));
}
