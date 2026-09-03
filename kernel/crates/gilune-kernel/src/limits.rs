//! The bounds a hostile peer runs into (ADR 0019 § 8). Every number a
//! remote peer can push against lives here and nowhere else: one fact,
//! one home. They are configuration with documented defaults, not
//! constants scattered through an exchange loop, so a host that knows its
//! device can lower them without editing the semantics.

/// Bounds applied to everything arriving from another device. Defaults
/// are the shipped policy; a host may lower them, and lowering one never
/// changes what "halt" means — see `operations::apply`, which raises a
/// visible alert, applies nothing past the fault, and resumes from the
/// frontier when the condition clears.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// Largest envelope this device will decode. An operation payload is
    /// a document, never bytes, so this is capped in the low megabytes;
    /// blob content travels as chunks instead.
    pub max_envelope_bytes: usize,
    /// Largest blob this device will accept. Never used as an
    /// allocation — it is the refusal threshold, checked against the
    /// declared length before a byte is written.
    pub max_blob_bytes: u64,
    /// Free space that must remain after a blob lands. A declared length
    /// larger than what is actually available is refused before staging
    /// opens, so a manifest cannot fill the disk that holds the vault.
    pub free_space_margin_bytes: u64,
    /// Bytes per blob chunk on the wire. Matches the capture path's own
    /// streaming granularity, so a recording streams in and out alike.
    pub chunk_bytes: usize,
    /// Operations one session may apply before it ends cleanly. Ending is
    /// not truncating: the frontier makes the next session resume exactly
    /// where this one stopped, so the cap costs a round trip and nothing
    /// else.
    pub max_ops_per_session: usize,
    /// Bytes one session may transfer before it ends cleanly.
    pub max_bytes_per_session: u64,
}

impl Limits {
    pub const DEFAULT: Limits = Limits {
        max_envelope_bytes: 2 * 1024 * 1024,
        max_blob_bytes: 512 * 1024 * 1024,
        free_space_margin_bytes: 64 * 1024 * 1024,
        chunk_bytes: 64 * 1024,
        max_ops_per_session: 10_000,
        max_bytes_per_session: 256 * 1024 * 1024,
    };
}

impl Default for Limits {
    fn default() -> Self {
        Limits::DEFAULT
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_defaults_are_internally_consistent() {
        let l = Limits::DEFAULT;
        assert!(
            l.chunk_bytes < l.max_envelope_bytes,
            "a chunk must fit inside the framing an envelope already fits in"
        );
        assert!(
            l.max_bytes_per_session >= l.max_envelope_bytes as u64,
            "a session that cannot carry one envelope can never make progress"
        );
        assert!(l.max_blob_bytes > 0 && l.max_ops_per_session > 0);
    }
}
