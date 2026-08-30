//! Identity and integrity primitives. IDs are UUIDv7 (time-ordered, no
//! coordination); digests are SHA-256 hex. Both appear in the vault and
//! in operations, so their formats are public contract.

pub fn new_id() -> String {
    uuid::Uuid::now_v7().to_string()
}

/// Filename-friendly fragment of an ID for projected file names. Files
/// are matched by front-matter ID, never by name (ADR 0007), so this is
/// legibility, not identity.
pub fn short(id: &str) -> String {
    id.chars().filter(|c| *c != '-').take(12).collect()
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::Digest;
    hex::encode(sha2::Sha256::digest(bytes))
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("clock before epoch")
        .as_millis() as i64
}
