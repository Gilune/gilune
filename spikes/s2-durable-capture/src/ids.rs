//! Identifiers. Opaque 32-hex-char strings (128 bits).
//!
//! Fresh IDs use UUIDv7 per the data model's proposal. Derived output IDs are
//! deterministic functions of the idempotency key so that duplicate delivery
//! converges on the same logical objects instead of creating hidden copies.

use sha2::{Digest, Sha256};

pub fn new_id() -> String {
    uuid::Uuid::now_v7().simple().to_string()
}

pub fn deterministic_id(tag: &str, key: &str) -> String {
    let mut h = Sha256::new();
    h.update(tag.as_bytes());
    h.update(b":");
    h.update(key.as_bytes());
    let digest = h.finalize();
    hex::encode(&digest[..16])
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(bytes);
    hex::encode(h.finalize())
}
