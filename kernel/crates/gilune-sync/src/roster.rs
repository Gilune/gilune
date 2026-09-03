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

use crate::identity::{read_sealed, write_sealed, KeyWrap};
use crate::{Result, SyncError};
use std::path::PathBuf;
use std::sync::Arc;

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
    /// The device ids this peer used **before** it re-enrolled, learned
    /// inside the encrypted channel like the current one (ADR 0019 § 4
    /// meeting § 9).
    ///
    /// A machine that re-enrols takes a new `device_id` and stays the same
    /// machine. Its older history is still in every replica and still
    /// arrives; without this, welcoming the device back would welcome only
    /// the operations it writes from now on, and everything it wrote
    /// before would stay refused for ever behind an alert nobody could
    /// clear.
    pub previous_device_ids: Vec<String>,
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

    /// Every device id this peer has ever originated operations under —
    /// what it calls itself now, and what it called itself before it
    /// re-enrolled. The unit § 9's refusal actually works in: refusing or
    /// welcoming *a device* means all of them or none.
    pub fn device_ids(&self) -> Vec<String> {
        std::iter::once(self.device_id.clone())
            .chain(self.previous_device_ids.iter().cloned())
            .filter(|id| !id.is_empty())
            .collect()
    }

    /// Whether this row is that device under any of its names.
    fn claims(&self, device_id: &str) -> bool {
        !device_id.is_empty() && self.device_ids().iter().any(|id| id == device_id)
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
        let Some(file) = read_sealed(&path, "roster")? else {
            return Ok(Roster { path, wrap, peers: Vec::new() });
        };
        let plain = file.open(wrap.as_deref(), "roster")?;
        let document: serde_json::Value = serde_json::from_slice(&plain)
            .map_err(|e| SyncError::Identity { detail: format!("roster contents: {e}") })?;
        let peers = document
            .get("peers")
            .and_then(|v| v.as_array())
            .map(|rows| rows.iter().filter_map(peer_from).collect())
            .unwrap_or_default();
        Ok(Roster { path, wrap, peers })
    }

    /// Read the file again into this handle, discarding what was held.
    ///
    /// A roster that has gone missing since it was opened is an empty
    /// one, not an error: that is a first write, and `open` already
    /// treats it that way.
    fn reload(&mut self) -> Result<()> {
        let fresh = Roster::open(self.path.clone(), self.wrap.clone())?;
        self.peers = fresh.peers;
        Ok(())
    }

    /// Apply one change to the roster **as it is on disk right now**, and
    /// write the result back, with nobody else in the file meanwhile.
    ///
    /// **The defect this closes.** Saving used to be a whole-file replace
    /// of whatever this handle happened to be holding. One file, two
    /// writers — the Android activity's service and the background
    /// worker's, or two desktop instances on one vault, which is
    /// sanctioned use since 2026-08-31 — and the second writer's save
    /// erased the first's change entirely: a pairing recorded by the
    /// worker vanished the next time the activity refreshed an address,
    /// and nothing anywhere said so.
    ///
    /// So a write is now read-modify-write under an advisory exclusive
    /// lock. The lock is on a sidecar rather than on the roster itself,
    /// because the write is an atomic replace of that path and a lock
    /// held on a file that is about to be replaced protects nothing.
    /// A machine that will not give a lock is not a machine that should
    /// stop pairing, so failing to take one is a reason to carry on
    /// unlocked — the reload still merges, which is the larger half.
    ///
    /// This does **not** make two processes' reads consistent, and does
    /// not claim to: a handle that has held a roster for an hour still
    /// answers `peers()` from what it read. What it fixes is the write —
    /// no change is silently thrown away by another writer's save.
    fn mutate<T>(&mut self, change: impl FnOnce(&mut Vec<Peer>) -> T) -> Result<T> {
        let _guard = FileGuard::acquire(&self.lock_path());
        self.reload()?;
        let outcome = change(&mut self.peers);
        self.save()?;
        Ok(outcome)
    }

    fn lock_path(&self) -> PathBuf {
        let mut name = self
            .path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "roster".to_string());
        name.push_str(".lock");
        self.path.with_file_name(name)
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
    ///
    /// **Never one a paired device answers to.** This list is folded into
    /// the vault's durable refusal set at every open, and the set only
    /// grows — so a single stale name in here is a refusal that comes back
    /// on the next launch and never leaves. That is what a re-pairing
    /// produced: revoking recorded a device id, the peer re-enrolled under
    /// a new one, re-pairing welcomed the new id, and the *old* row still
    /// named the old id — so the peer's earlier history, which every other
    /// replica still relays, was refused for ever by a device that had
    /// just deliberately welcomed it back.
    ///
    /// So a peer that is paired now, under any name it has ever used,
    /// silences every revoked row that names it. `pair` also retires those
    /// rows outright; this is the half that holds even for a roster
    /// written before that ran.
    pub fn revoked_device_ids(&self) -> Vec<String> {
        let welcomed: Vec<String> = self
            .peers
            .iter()
            .filter(|p| p.state == PeerState::Paired)
            .flat_map(|p| p.device_ids())
            .collect();
        let mut refused: Vec<String> = Vec::new();
        for peer in self.peers.iter().filter(|p| p.state == PeerState::Revoked) {
            for id in peer.device_ids() {
                if !welcomed.contains(&id) && !refused.contains(&id) {
                    refused.push(id);
                }
            }
        }
        refused
    }

    /// Record a completed pairing. Re-pairing a device that was revoked
    /// is a **new** pairing, not a restoration: the row is replaced
    /// whole, with the secret the new exchange produced.
    ///
    /// **Replaced by device, not merely by key.** A peer that re-enrols
    /// arrives with a new static key *and* a new device id, so replacing
    /// only the row with the same key left the revoked row behind — and a
    /// revoked row is a standing refusal. Any revoked row this peer
    /// answers to, under its current name or any earlier one, is retired
    /// by the pairing that welcomed it.
    pub fn pair(
        &mut self,
        static_pub: &str,
        device_id: &str,
        previous_device_ids: &[String],
        name: &str,
        address: &str,
        secret: [u8; 32],
    ) -> Result<Peer> {
        let now = gilune_kernel::ids::now_ms();
        let peer = Peer {
            static_pub: static_pub.to_string(),
            device_id: device_id.to_string(),
            previous_device_ids: previous_device_ids.to_vec(),
            name: name.to_string(),
            address: address.to_string(),
            state: PeerState::Paired,
            paired_ms: now,
            last_seen_ms: now,
            secret: secret.to_vec(),
        };
        let stored = peer.clone();
        self.mutate(move |peers| {
            let names = stored.device_ids();
            peers.retain(|p| {
                p.static_pub != stored.static_pub
                    && !(p.state == PeerState::Revoked && names.iter().any(|id| p.claims(id)))
            });
            peers.push(stored);
        })?;
        Ok(peer)
    }

    /// Revoke, locally and immediately. The secret goes; the row stays so
    /// the refusal survives a restart.
    pub fn revoke(&mut self, static_pub: &str) -> Result<bool> {
        // The lookup happens **inside** the reload, not against what this
        // handle was holding: another process may have paired this device
        // since, and revoking somebody who was only just added is exactly
        // the case that must not silently do nothing.
        self.mutate(|peers| {
            let Some(peer) = peers.iter_mut().find(|p| p.static_pub == static_pub) else {
                return false;
            };
            peer.state = PeerState::Revoked;
            peer.secret.clear();
            true
        })
    }

    /// Record where a peer can be reached, without claiming to have
    /// reached it. This is § 1's manual entry and its discovery step
    /// arriving at the same place: an address a person typed and an
    /// address a browse resolved are both "try this one first next time",
    /// which is what the cached-address rule means. Deliberately does
    /// **not** touch `last_seen_ms` — nothing has been seen.
    pub fn set_address(&mut self, static_pub: &str, address: &str) -> Result<bool> {
        self.mutate(|peers| {
            let Some(peer) = peers.iter_mut().find(|p| p.static_pub == static_pub) else {
                return false;
            };
            peer.address = address.to_string();
            true
        })
    }

    /// Refresh what a successful session taught us: the peer's device id,
    /// the address it was actually reachable at, and when.
    pub fn seen(&mut self, static_pub: &str, device_id: &str, address: &str) -> Result<()> {
        self.mutate(|peers| {
            let Some(peer) = peers.iter_mut().find(|p| p.static_pub == static_pub) else {
                return;
            };
            if !device_id.is_empty() {
                peer.device_id = device_id.to_string();
            }
            if !address.is_empty() {
                peer.address = address.to_string();
            }
            peer.last_seen_ms = gilune_kernel::ids::now_ms();
        })
    }

    fn save(&self) -> Result<()> {
        let document = serde_json::json!({
            "peers": self
                .peers
                .iter()
                .map(|p| serde_json::json!({
                    "static_pub": p.static_pub,
                    "device_id": p.device_id,
                    "previous_device_ids": p.previous_device_ids,
                    "name": p.name,
                    "address": p.address,
                    "state": p.state.as_str(),
                    "paired_ms": p.paired_ms,
                    "last_seen_ms": p.last_seen_ms,
                    "secret": hex::encode(&p.secret),
                }))
                .collect::<Vec<_>>(),
        });
        write_sealed(
            &self.path,
            "roster",
            self.wrap.as_deref(),
            document.to_string().as_bytes(),
            // Nothing rides outside the seal. A roster's plain fields
            // would be a list of who this device talks to, which is the
            // one thing an advertisement is careful not to say either.
            serde_json::Value::Null,
        )
    }
}

/// An advisory exclusive lock held for the length of one read-modify-write.
///
/// Held on a sidecar, never on the roster: the roster is replaced by an
/// atomic rename, and a lock on a file that is about to be replaced
/// guards a handle nobody will be holding afterwards.
///
/// **A lock that cannot be taken is not a reason to refuse to pair.** A
/// filesystem that will not lock — some network shares, some sandboxes —
/// still gets the reload-and-merge, which is the half that turns a lost
/// update into a rare interleaving rather than a routine one. So this
/// carries an `Option` and never an error.
/// Uses `std::fs::File`'s own `lock`/`unlock` — stable since Rust 1.89
/// and well inside this workspace's floor — so the whole of this costs no
/// dependency: `flock` on Unix, `LockFileEx` on Windows.
struct FileGuard(Option<std::fs::File>);

impl FileGuard {
    fn acquire(path: &std::path::Path) -> FileGuard {
        let opened = std::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(path);
        match opened {
            Ok(file) if file.lock().is_ok() => FileGuard(Some(file)),
            _ => FileGuard(None),
        }
    }
}

impl Drop for FileGuard {
    fn drop(&mut self) {
        if let Some(file) = self.0.take() {
            let _ = file.unlock();
        }
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
        previous_device_ids: row
            .get("previous_device_ids")
            .and_then(|v| v.as_array())
            .map(|rows| {
                rows.iter()
                    .filter_map(|v| v.as_str())
                    .filter(|id| !id.is_empty())
                    .map(str::to_string)
                    .collect()
            })
            .unwrap_or_default(),
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
            .pair("aa".repeat(32).as_str(), "device-b", &[], "Laptop", "127.0.0.1:9", [7u8; 32])
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
            .pair(&"bb".repeat(32), "device-b", &[], "Phone", "127.0.0.1:9", [9u8; 32])
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
            .pair(&key, "device-b", &[], "Phone", "127.0.0.1:9", [3u8; 32])
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
                .pair(&key, "device-b", &[], "Phone", "127.0.0.1:9", [3u8; 32])
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
        roster.pair(&key, "device-b", &[], "Phone", "a:1", [1u8; 32]).expect("pair");
        roster.revoke(&key).expect("revoke");
        roster.pair(&key, "device-b", &[], "Phone", "a:1", [2u8; 32]).expect("re-pair");

        assert_eq!(roster.peers().len(), 1, "one row, replaced whole");
        assert_eq!(roster.secret_for(&key), Some([2u8; 32]));
        assert!(roster.revoked_device_ids().is_empty());
    }

    /// **K2.** One roster file, two handles — the shape the Android
    /// activity's service and its background worker actually have, and
    /// two desktop instances on one vault (sanctioned use since
    /// 2026-08-31). A save used to be a whole-file replace of whatever
    /// the writing handle held, so whichever of these wrote last erased
    /// the other's work: a pairing made by the worker disappeared the
    /// next time the activity refreshed an address, silently.
    #[test]
    fn two_handles_over_one_file_do_not_overwrite_each_others_changes() {
        let dir = tempfile::tempdir().expect("dir");
        let first_key = "1a".repeat(32);
        let second_key = "2b".repeat(32);

        // Both opened before either wrote, which is what two processes
        // starting up looks like.
        let mut first = roster(&dir, None);
        let mut second = roster(&dir, None);

        first
            .pair(&first_key, "device-a", &[], "Laptop", "10.0.0.5:51837", [1u8; 32])
            .expect("the first handle pairs a device");
        second
            .pair(&second_key, "device-b", &[], "Phone", "10.0.0.6:51837", [2u8; 32])
            .expect("the second handle pairs another");
        // And now the first handle, which has never heard of the second's
        // peer, changes something about its own.
        assert!(first
            .set_address(&first_key, "10.0.0.9:51837")
            .expect("address"));

        let after = roster(&dir, None);
        assert_eq!(
            after.peers().len(),
            2,
            "one handle's write erased the other's pairing"
        );
        assert_eq!(
            after.find(&first_key).expect("the first peer").address,
            "10.0.0.9:51837",
            "and the change that came last is the one that stands"
        );
        assert_eq!(
            after.secret_for(&second_key),
            Some([2u8; 32]),
            "the other handle's pair secret survived intact"
        );
    }

    /// The same property for the change that matters most: a revocation
    /// must not be undone by an unrelated write from another handle.
    #[test]
    fn a_revocation_survives_another_handles_write() {
        let dir = tempfile::tempdir().expect("dir");
        let revoked = "3c".repeat(32);
        let other = "4d".repeat(32);
        let mut first = roster(&dir, None);
        first
            .pair(&revoked, "device-b", &[], "Phone", "a:1", [5u8; 32])
            .expect("pair");
        first.pair(&other, "device-c", &[], "Tablet", "b:2", [6u8; 32]).expect("pair");

        // A second handle reads the file, then the first revokes, then
        // the second writes something about a different peer entirely.
        let mut second = roster(&dir, None);
        assert!(first.revoke(&revoked).expect("revoke"));
        second.seen(&other, "device-c", "b:3").expect("seen");

        let after = roster(&dir, None);
        assert_eq!(
            after.revoked_device_ids(),
            vec!["device-b".to_string()],
            "an unrelated write brought a revoked device back"
        );
        assert_eq!(after.secret_for(&revoked), None);
        assert_eq!(after.find(&other).expect("the other peer").address, "b:3");
    }

    /// **Second-order: re-pairing a device that re-enrolled must not leave
    /// a refusal behind.**
    ///
    /// The revoked row is keyed by the *old* static key and names the
    /// *old* device id. Re-pairing writes a new row under a new key, and
    /// the old one used to survive — so `revoked_device_ids` still named a
    /// device the user had just welcomed back, and every open folded that
    /// name into the vault's durable refusal set again.
    #[test]
    fn re_pairing_a_re_enrolled_device_retires_the_row_that_revoked_it() {
        let dir = tempfile::tempdir().expect("dir");
        let mut writing = roster(&dir, None);
        let roster = &mut writing;
        let before = "1a".repeat(32);
        let after = "2b".repeat(32);

        roster
            .pair(&before, "device-old", &[], "Phone", "a:1", [1u8; 32])
            .expect("pair");
        assert!(roster.revoke(&before).expect("revoke"));
        assert_eq!(roster.revoked_device_ids(), vec!["device-old".to_string()]);

        // The phone's vault arrived on a machine holding no identity for
        // it: a new key, a new device id, and the old id as its ancestry.
        roster
            .pair(
                &after,
                "device-new",
                &["device-old".to_string()],
                "Phone",
                "a:1",
                [2u8; 32],
            )
            .expect("re-pair");

        assert!(
            roster.revoked_device_ids().is_empty(),
            "the device was welcomed back under both its names, so neither is refused"
        );
        assert_eq!(roster.peers().len(), 1, "and the revoked row is gone, not merely quiet");
        assert_eq!(
            roster.find(&after).expect("the peer").device_ids(),
            vec!["device-new".to_string(), "device-old".to_string()]
        );

        // It survives a reopen as one row with its ancestry intact.
        let reopened = Roster::open(dir.path().join("roster.json"), None).expect("reopen");
        assert!(reopened.revoked_device_ids().is_empty());
        assert_eq!(
            reopened.find(&after).expect("the peer").previous_device_ids,
            vec!["device-old".to_string()]
        );
    }

    /// And the same fact from the other side: a device that is *still*
    /// revoked stays refused when somebody else re-enrols. The fix must
    /// not be "stop refusing anybody".
    #[test]
    fn re_enrolling_one_peer_leaves_another_peers_revocation_standing() {
        let dir = tempfile::tempdir().expect("dir");
        let mut roster = roster(&dir, None);
        let banished = "3c".repeat(32);
        let before = "4d".repeat(32);
        let after = "5e".repeat(32);

        roster
            .pair(&banished, "device-gone", &[], "Old tablet", "a:1", [1u8; 32])
            .expect("pair");
        roster
            .pair(&before, "device-old", &[], "Phone", "b:2", [2u8; 32])
            .expect("pair");
        roster.revoke(&banished).expect("revoke");
        roster.revoke(&before).expect("revoke");

        roster
            .pair(
                &after,
                "device-new",
                &["device-old".to_string()],
                "Phone",
                "b:2",
                [3u8; 32],
            )
            .expect("re-pair");

        assert_eq!(
            roster.revoked_device_ids(),
            vec!["device-gone".to_string()],
            "welcoming one device back must not amnesty another"
        );
    }

    /// **Second-order for the shared envelope: nobody loses their
    /// pairings to a field rename.** A roster written by the build that
    /// spelled the sealed half `payload` is a roster on a real machine.
    #[test]
    fn a_roster_written_by_the_previous_layout_still_opens() {
        let dir = tempfile::tempdir().expect("dir");
        let path = dir.path().join("roster.json");
        let key = "6f".repeat(32);
        let body = serde_json::json!({
            "peers": [{
                "static_pub": key,
                "device_id": "device-b",
                "name": "Phone",
                "address": "10.0.0.6:51837",
                "state": "paired",
                "paired_ms": 17,
                "last_seen_ms": 18,
                "secret": hex::encode([4u8; 32]),
            }],
        });
        let envelope = serde_json::json!({
            "version": 1,
            "wrap": "none",
            "payload": hex::encode(body.to_string().as_bytes()),
        });
        std::fs::write(&path, envelope.to_string()).expect("write");

        let mut opened = Roster::open(path.clone(), None).expect("open the old layout");
        let peer = opened.find(&key).expect("the pairing survived the update");
        assert_eq!(peer.secret(), Some([4u8; 32]), "and so did its pair secret");
        assert_eq!(peer.name, "Phone");
        assert!(peer.previous_device_ids.is_empty(), "an old row has no ancestry");

        // And once this build writes it back, it is in the new shape and
        // still holds everything.
        opened.set_address(&key, "10.0.0.9:51837").expect("write it back");
        let raw = std::fs::read_to_string(&path).expect("read");
        assert!(raw.contains("\"sealed\""), "written under the one field name now");
        let again = Roster::open(path, None).expect("reopen");
        assert_eq!(again.find(&key).expect("the peer").secret(), Some([4u8; 32]));
    }

    #[test]
    fn a_roster_that_cannot_be_opened_is_an_error_rather_than_an_empty_list() {
        let dir = tempfile::tempdir().expect("dir");
        let wrap: Arc<dyn KeyWrap> = Arc::new(Reversing);
        let mut roster = roster(&dir, Some(wrap));
        roster.pair(&"ff".repeat(32), "b", &[], "Phone", "a:1", [1u8; 32]).expect("pair");
        // A `Roster` has no `Debug` and should not grow one — it holds
        // pair secrets — so the outcome is reduced to something a failure
        // message can print.
        //
        // `IdentityUnreadable` and not `Identity`: a sealed roster this
        // host cannot open is the same condition as a sealed key it cannot
        // open — the file is *here*, and the answer is neither "start
        // empty" nor "mint a new one". Both go through one envelope now,
        // so both say the same thing.
        let outcome = Roster::open(dir.path().join("roster.json"), None).map(|r| r.peers().len());
        assert!(
            matches!(outcome, Err(SyncError::IdentityUnreadable { .. })),
            "silently forgetting every pairing would look exactly like working: {outcome:?}"
        );
    }
}
