//! The pairing roster: who this device has agreed to talk to.
//!
//! Device-local, beside the identity and for the same reason (ADR 0019
//! § 4): "pairing is pairwise; a roster is not vault state". It holds the
//! pair secret for each peer, so it is key material and it is wrapped by
//! the same `KeyWrap` adapter the identity is.
//!
//! A peer is keyed by its **static public key**, never by its `device_id`
//! — § 4 again: "a peer is recognised by its static public key, and its
//! `device_id` is a fact it asserts inside the authenticated channel".
//! The device id is recorded because revocation needs it (§ 9 refuses a
//! revoked device's operations by originating device), but it is never
//! what authenticates anybody.

use crate::identity::{write_private, KeyWrap, UNWRAPPED};
use crate::{Result, SyncError};
use std::path::PathBuf;
use std::sync::Arc;

const FILE_VERSION: i64 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PeerState {
    Paired,
    /// Revoked here. The row stays: it is what makes this device's
    /// refusal of that device's operations survive a restart, and what
    /// lets the settings screen show a device that was deliberately
    /// removed rather than one that silently vanished.
    Revoked,
}

impl PeerState {
    pub fn as_str(&self) -> &'static str {
        match self {
            PeerState::Paired => "paired",
            PeerState::Revoked => "revoked",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Peer {
    /// Hex X25519 static public key. The identity, and the primary key.
    pub static_pub: String,
    /// What the peer says its `device_id` is, learned inside the
    /// encrypted channel.
    pub device_id: String,
    pub name: String,
    /// Last known `host:port`. Refreshed after every successful session,
    /// which is what M5c's "try the cached address first" will read.
    pub address: String,
    pub state: PeerState,
    pub paired_ms: i64,
    pub last_seen_ms: i64,
    /// The `psk` for every later session. Empty once revoked — "and
    /// subsequent handshakes fail because the pair secret is gone" is
    /// implemented literally rather than by a flag someone must check.
    secret: Vec<u8>,
}

impl Peer {
    pub fn fingerprint(&self) -> String {
        hex::decode(&self.static_pub)
            .map(|bytes| crate::identity::fingerprint_of(&bytes))
            .unwrap_or_default()
    }

    /// The same identity in the form an advertisement carries (§ 1). A
    /// DNS-SD instance name has no room for the readable spelling's
    /// dashes and case, so matching a browse result to a roster entry has
    /// to happen in this form — and doing it through one function is what
    /// stops the two spellings being compared to each other by accident.
    pub fn fingerprint_compact(&self) -> String {
        hex::decode(&self.static_pub)
            .map(|bytes| crate::identity::fingerprint_compact(&bytes))
            .unwrap_or_default()
    }

    pub fn secret(&self) -> Option<[u8; 32]> {
        if self.state != PeerState::Paired || self.secret.len() != 32 {
            return None;
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&self.secret);
        Some(key)
    }
}

pub struct Roster {
    path: PathBuf,
    wrap: Option<Arc<dyn KeyWrap>>,
    peers: Vec<Peer>,
}

impl Roster {
    /// Read the roster at `path`, or start an empty one. A roster that
    /// cannot be decrypted is an error rather than an empty list: quietly
    /// forgetting every pairing would look exactly like working.
    pub fn open(path: PathBuf, wrap: Option<Arc<dyn KeyWrap>>) -> Result<Roster> {
        let raw = match std::fs::read(&path) {
            Ok(raw) => raw,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Roster { path, wrap, peers: Vec::new() })
            }
            Err(e) => {
                return Err(SyncError::Identity {
                    detail: format!("reading {}: {e}", path.display()),
                })
            }
        };
        let envelope: serde_json::Value = serde_json::from_slice(&raw)
            .map_err(|e| SyncError::Identity { detail: format!("roster file: {e}") })?;
        let version = envelope.get("version").and_then(|v| v.as_i64()).unwrap_or(0);
        if version != FILE_VERSION {
            return Err(SyncError::Identity {
                detail: format!("roster file version {version} is not one this build reads"),
            });
        }
        let protection = envelope
            .get("wrap")
            .and_then(|v| v.as_str())
            .unwrap_or(UNWRAPPED)
            .to_string();
        let sealed = envelope
            .get("payload")
            .and_then(|v| v.as_str())
            .ok_or_else(|| SyncError::Identity { detail: "roster file has no payload".into() })?;
        let sealed = hex::decode(sealed)
            .map_err(|e| SyncError::Identity { detail: format!("roster payload: {e}") })?;
        let plain = if protection == UNWRAPPED {
            sealed
        } else {
            let Some(wrapper) = wrap.as_ref() else {
                return Err(SyncError::Identity {
                    detail: format!(
                        "the roster was wrapped by {protection}, and no key wrapper is \
                         registered on this host to open it"
                    ),
                });
            };
            wrapper.unwrap(&sealed).map_err(|detail| SyncError::Identity {
                detail: format!("{protection} could not unwrap the roster: {detail}"),
            })?
        };
        let document: serde_json::Value = serde_json::from_slice(&plain)
            .map_err(|e| SyncError::Identity { detail: format!("roster contents: {e}") })?;
        let peers = document
            .get("peers")
            .and_then(|v| v.as_array())
            .map(|rows| rows.iter().filter_map(peer_from).collect())
            .unwrap_or_default();
        Ok(Roster { path, wrap, peers })
    }

    pub fn peers(&self) -> &[Peer] {
        &self.peers
    }

    pub fn find(&self, static_pub: &str) -> Option<&Peer> {
        self.peers.iter().find(|p| p.static_pub == static_pub)
    }

    /// Every pair secret this device could be greeted with, so a listener
    /// can work out which peer is calling **without the caller naming
    /// itself in the clear**. An initiator that holds none of them cannot
    /// get a first handshake message read at all.
    pub fn candidate_secrets(&self) -> Vec<(String, [u8; 32])> {
        self.peers
            .iter()
            .filter_map(|p| p.secret().map(|s| (p.static_pub.clone(), s)))
            .collect()
    }

    pub fn secret_for(&self, static_pub: &str) -> Option<[u8; 32]> {
        self.find(static_pub).and_then(|p| p.secret())
    }

    /// Device ids whose operations this device refuses on apply (§ 9).
    pub fn revoked_device_ids(&self) -> Vec<String> {
        self.peers
            .iter()
            .filter(|p| p.state == PeerState::Revoked && !p.device_id.is_empty())
            .map(|p| p.device_id.clone())
            .collect()
    }

    /// Record a completed pairing. Re-pairing a device that was revoked
    /// is a **new** pairing, not a restoration: the row is replaced
    /// whole, with the secret the new exchange produced.
    pub fn pair(
        &mut self,
        static_pub: &str,
        device_id: &str,
        name: &str,
        address: &str,
        secret: [u8; 32],
    ) -> Result<Peer> {
        let now = gilune_kernel::ids::now_ms();
        let peer = Peer {
            static_pub: static_pub.to_string(),
            device_id: device_id.to_string(),
            name: name.to_string(),
            address: address.to_string(),
            state: PeerState::Paired,
            paired_ms: now,
            last_seen_ms: now,
            secret: secret.to_vec(),
        };
        self.peers.retain(|p| p.static_pub != static_pub);
        self.peers.push(peer.clone());
        self.save()?;
        Ok(peer)
    }

    /// Revoke, locally and immediately. The secret goes; the row stays so
    /// the refusal survives a restart.
    pub fn revoke(&mut self, static_pub: &str) -> Result<bool> {
        let Some(peer) = self.peers.iter_mut().find(|p| p.static_pub == static_pub) else {
            return Ok(false);
        };
        peer.state = PeerState::Revoked;
        peer.secret.clear();
        self.save()?;
        Ok(true)
    }

    /// Record where a peer can be reached, without claiming to have
    /// reached it. This is § 1's manual entry and its discovery step
    /// arriving at the same place: an address a person typed and an
    /// address a browse resolved are both "try this one first next time",
    /// which is what the cached-address rule means. Deliberately does
    /// **not** touch `last_seen_ms` — nothing has been seen.
    pub fn set_address(&mut self, static_pub: &str, address: &str) -> Result<bool> {
        let Some(peer) = self.peers.iter_mut().find(|p| p.static_pub == static_pub) else {
            return Ok(false);
        };
        if peer.address == address {
            return Ok(true);
        }
        peer.address = address.to_string();
        self.save()?;
        Ok(true)
    }

    /// Refresh what a successful session taught us: the peer's device id,
    /// the address it was actually reachable at, and when.
    pub fn seen(&mut self, static_pub: &str, device_id: &str, address: &str) -> Result<()> {
        let Some(peer) = self.peers.iter_mut().find(|p| p.static_pub == static_pub) else {
            return Ok(());
        };
        if !device_id.is_empty() {
            peer.device_id = device_id.to_string();
        }
        if !address.is_empty() {
            peer.address = address.to_string();
        }
        peer.last_seen_ms = gilune_kernel::ids::now_ms();
        self.save()
    }

    fn save(&self) -> Result<()> {
        let document = serde_json::json!({
            "peers": self
                .peers
                .iter()
                .map(|p| serde_json::json!({
                    "static_pub": p.static_pub,
                    "device_id": p.device_id,
                    "name": p.name,
                    "address": p.address,
                    "state": p.state.as_str(),
                    "paired_ms": p.paired_ms,
                    "last_seen_ms": p.last_seen_ms,
                    "secret": hex::encode(&p.secret),
                }))
                .collect::<Vec<_>>(),
        });
        let plain = document.to_string();
        let (protection, sealed) = match self.wrap.as_ref() {
            Some(wrap) => {
                let sealed = wrap.wrap(plain.as_bytes()).map_err(|detail| {
                    SyncError::Identity {
                        detail: format!("{} could not wrap the roster: {detail}", wrap.name()),
                    }
                })?;
                (wrap.name(), sealed)
            }
            None => (UNWRAPPED.to_string(), plain.into_bytes()),
        };
        let envelope = serde_json::json!({
            "version": FILE_VERSION,
            "wrap": protection,
            "payload": hex::encode(&sealed),
        });
        write_private(&self.path, envelope.to_string().as_bytes())
    }
}

fn peer_from(row: &serde_json::Value) -> Option<Peer> {
    let text = |field: &str| {
        row.get(field)
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string()
    };
    let static_pub = text("static_pub");
    if static_pub.is_empty() {
        return None;
    }
    Some(Peer {
        static_pub,
        device_id: text("device_id"),
        name: text("name"),
        address: text("address"),
        state: match text("state").as_str() {
            "revoked" => PeerState::Revoked,
            _ => PeerState::Paired,
        },
        paired_ms: row.get("paired_ms").and_then(|v| v.as_i64()).unwrap_or(0),
        last_seen_ms: row.get("last_seen_ms").and_then(|v| v.as_i64()).unwrap_or(0),
        secret: hex::decode(text("secret")).unwrap_or_default(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Reversing;

    impl KeyWrap for Reversing {
        fn name(&self) -> String {
            "reversing-test-wrapper".to_string()
        }
        fn wrap(&self, plaintext: &[u8]) -> std::result::Result<Vec<u8>, String> {
            Ok(plaintext.iter().rev().copied().collect())
        }
        fn unwrap(&self, ciphertext: &[u8]) -> std::result::Result<Vec<u8>, String> {
            Ok(ciphertext.iter().rev().copied().collect())
        }
    }

    fn roster(dir: &tempfile::TempDir, wrap: Option<Arc<dyn KeyWrap>>) -> Roster {
        Roster::open(dir.path().join("roster.json"), wrap).expect("open")
    }

    #[test]
    fn an_absent_roster_opens_empty_rather_than_failing() {
        let dir = tempfile::tempdir().expect("dir");
        assert!(roster(&dir, None).peers().is_empty());
    }

    #[test]
    fn a_pairing_survives_a_reopen_with_its_secret() {
        let dir = tempfile::tempdir().expect("dir");
        let wrap: Arc<dyn KeyWrap> = Arc::new(Reversing);
        let mut first = roster(&dir, Some(wrap.clone()));
        first
            .pair("aa".repeat(32).as_str(), "device-b", "Laptop", "127.0.0.1:9", [7u8; 32])
            .expect("pair");

        let second = roster(&dir, Some(wrap));
        let peer = second.find(&"aa".repeat(32)).expect("the peer");
        assert_eq!(peer.name, "Laptop");
        assert_eq!(peer.device_id, "device-b");
        assert_eq!(peer.secret(), Some([7u8; 32]));
        assert_eq!(peer.state, PeerState::Paired);
    }

    #[test]
    fn a_wrapped_roster_does_not_hold_its_secrets_in_the_clear() {
        let dir = tempfile::tempdir().expect("dir");
        let wrap: Arc<dyn KeyWrap> = Arc::new(Reversing);
        let mut roster = roster(&dir, Some(wrap));
        roster
            .pair(&"bb".repeat(32), "device-b", "Phone", "127.0.0.1:9", [9u8; 32])
            .expect("pair");
        let raw = std::fs::read_to_string(dir.path().join("roster.json")).expect("read");
        assert!(!raw.contains(&hex::encode([9u8; 32])));
        assert!(!raw.contains("Phone"), "names are inside the wrapper too");
    }

    #[test]
    fn revoking_takes_the_secret_away_and_keeps_the_refusal() {
        let dir = tempfile::tempdir().expect("dir");
        let mut roster = roster(&dir, None);
        let key = "cc".repeat(32);
        roster
            .pair(&key, "device-b", "Phone", "127.0.0.1:9", [3u8; 32])
            .expect("pair");
        assert!(roster.revoke(&key).expect("revoke"));

        let peer = roster.find(&key).expect("the row stays");
        assert_eq!(peer.state, PeerState::Revoked);
        assert_eq!(
            peer.secret(),
            None,
            "a later handshake has nothing to be built from"
        );
        assert_eq!(roster.revoked_device_ids(), vec!["device-b".to_string()]);
        assert!(roster.candidate_secrets().is_empty());
    }

    #[test]
    fn revoking_survives_a_reopen() {
        let dir = tempfile::tempdir().expect("dir");
        let key = "dd".repeat(32);
        {
            let mut roster = roster(&dir, None);
            roster
                .pair(&key, "device-b", "Phone", "127.0.0.1:9", [3u8; 32])
                .expect("pair");
            roster.revoke(&key).expect("revoke");
        }
        let reopened = roster(&dir, None);
        assert_eq!(reopened.revoked_device_ids(), vec!["device-b".to_string()]);
        assert_eq!(reopened.secret_for(&key), None);
    }

    #[test]
    fn re_pairing_a_revoked_device_is_a_new_pairing_with_a_new_secret() {
        let dir = tempfile::tempdir().expect("dir");
        let mut roster = roster(&dir, None);
        let key = "ee".repeat(32);
        roster.pair(&key, "device-b", "Phone", "a:1", [1u8; 32]).expect("pair");
        roster.revoke(&key).expect("revoke");
        roster.pair(&key, "device-b", "Phone", "a:1", [2u8; 32]).expect("re-pair");

        assert_eq!(roster.peers().len(), 1, "one row, replaced whole");
        assert_eq!(roster.secret_for(&key), Some([2u8; 32]));
        assert!(roster.revoked_device_ids().is_empty());
    }

    #[test]
    fn a_roster_that_cannot_be_opened_is_an_error_rather_than_an_empty_list() {
        let dir = tempfile::tempdir().expect("dir");
        let wrap: Arc<dyn KeyWrap> = Arc::new(Reversing);
        let mut roster = roster(&dir, Some(wrap));
        roster.pair(&"ff".repeat(32), "b", "Phone", "a:1", [1u8; 32]).expect("pair");
        assert!(
            matches!(
                Roster::open(dir.path().join("roster.json"), None),
                Err(SyncError::Identity { .. })
            ),
            "silently forgetting every pairing would look exactly like working"
        );
    }
}
