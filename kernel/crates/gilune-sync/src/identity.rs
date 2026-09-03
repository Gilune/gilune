//! Device identity, and the re-enrolment rule (ADR 0019 § 4).
//!
//! An X25519 static keypair — the Noise static identity — minted on first
//! use and kept **device-local, outside the vault**. That placement is the
//! sharpest decision in the ADR and the reason is mechanical rather than
//! stylistic: the vault is a portable artefact that gets copied, backed up
//! and put on shared folders, and an identity inside it is a cloned
//! identity with a shared `op_seq` space — the exact fork the threat model
//! prohibits. Keeping the key outside makes "a restored device re-enrols"
//! a consequence of where the key lives rather than a check somebody has
//! to remember to run.
//!
//! **Scoping, stated plainly.** The shell hands in a device-local root
//! (`%LOCALAPPDATA%\Gilune` on Windows, `filesDir` on Android) and this
//! module keys the identity file inside it by the vault's `device_id`.
//! That is a refinement of the ADR, which speaks of one identity per
//! device: one machine can hold two unrelated vaults, and opening the
//! second must not re-mint the first's identity and silently drop its
//! pairings. Keying by `device_id` also means moving a vault's directory
//! — an ordinary thing to do — is not mistaken for copying it. What it
//! does *not* catch is a vault copied to a second directory on the **same**
//! machine, which keeps its `device_id` and so finds its own identity
//! file; that configuration is already excluded by constitution rule 8,
//! and it is recorded here rather than discovered later.

use crate::{Result, SyncError};
use gilune_kernel::store::Store;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

/// The version of the identity file's own format.
const FILE_VERSION: i64 = 1;

/// The capability adapter that protects the key at rest (ADR 0019 § 4).
/// DPAPI user scope on Windows, a Keystore AES-GCM key on Android — both
/// written in Kotlin, per ADR 0001's rule that Rust does not call a
/// platform API.
///
/// Stated honestly, as `06-sync-and-security.md` requires: this protects
/// the key from other users and at rest. It does **not** protect it from
/// code running as this user, which is what "platform-protected where
/// available" means here.
pub trait KeyWrap: Send + Sync {
    /// A short name recorded in the file, so a person can be told what
    /// actually protected their key rather than what was intended to.
    fn name(&self) -> String;
    fn wrap(&self, plaintext: &[u8]) -> std::result::Result<Vec<u8>, String>;
    fn unwrap(&self, ciphertext: &[u8]) -> std::result::Result<Vec<u8>, String>;
}

/// Recorded in the file when no platform adapter was registered. The key
/// is then stored unwrapped, and the reason travels with it so the
/// settings screen can say so instead of implying a protection that is
/// not there.
pub const UNWRAPPED: &str = "none";

/// Why a file holds its contents in the clear. ADR 0019 § 4 allows the
/// fallback and asks for the reason to be recorded rather than assumed
/// away — for the roster as much as for the key, since a roster holds a
/// pair secret for every peer.
const UNWRAPPED_REASON: &str =
    "no platform key wrapper was registered by this host, so this file is stored \
     unwrapped and protected only by the permissions of its directory";

/// The field the sealed half is written under.
const SEALED: &str = "sealed";

/// What earlier builds called it. The identity file used `secret` and the
/// roster used `payload` — two names for one thing, which is how the two
/// readers came to drift apart. New files carry `SEALED`; old ones are
/// still read, because the alternative is a user's pairings vanishing on
/// the update that tidied the field name.
const SEALED_WAS: [&str; 2] = ["payload", "secret"];

/// How many earlier device ids one identity file remembers. A device that
/// has been restored from a backup four times has four ancestors and a
/// bound is a bound; the oldest are dropped first, because it is the
/// recent ones a peer still holds unapplied history from.
pub const MAX_PREVIOUS_DEVICE_IDS: usize = 8;

// ------------------------------------------------------- the sealed file

/// A device-local file whose body is key material.
///
/// **One envelope, read and written in one place.** The identity and the
/// roster had a copy each — same version check, same `wrap` field, same
/// hex payload, same four error messages — and they had already diverged:
/// only one of them recorded why an unwrapped file was unwrapped, and they
/// spelled the payload field differently, so neither could ever read the
/// other's compat case. A file format that exists twice is a file format
/// that is checked once and a half.
pub(crate) struct SealedFile {
    /// The adapter that sealed it, or `UNWRAPPED`.
    pub protection: String,
    /// The whole envelope, so a caller can read the plain fields it wrote
    /// beside the sealed half.
    pub document: serde_json::Value,
    sealed: Vec<u8>,
}

impl SealedFile {
    /// The contents, unsealed. Fails rather than guessing when the
    /// adapter that sealed the file is not registered on this host —
    /// quietly returning nothing would look exactly like an empty file.
    pub fn open(&self, wrap: Option<&dyn KeyWrap>, what: &str) -> Result<Vec<u8>> {
        if self.protection == UNWRAPPED {
            return Ok(self.sealed.clone());
        }
        // The file is here and sealed, and this host cannot open it. Both
        // arms raise `IdentityUnreadable` rather than `Identity` so that
        // the boundary can tell it from "there is nothing here yet",
        // which is enrolment: minting over a sealed key would throw away
        // every pairing made with it (ADR 0019 § 4).
        let Some(wrap) = wrap else {
            return Err(SyncError::IdentityUnreadable {
                detail: format!(
                    "the {what} was wrapped by {}, and no key wrapper is registered on this \
                     host to open it",
                    self.protection
                ),
            });
        };
        wrap.unwrap(&self.sealed).map_err(|detail| SyncError::IdentityUnreadable {
            detail: format!("{} could not unwrap the {what}: {detail}", self.protection),
        })
    }

    pub fn strings(&self, field: &str) -> Vec<String> {
        self.document
            .get(field)
            .and_then(|v| v.as_array())
            .map(|rows| {
                rows.iter()
                    .filter_map(|v| v.as_str())
                    .map(str::to_string)
                    .collect()
            })
            .unwrap_or_default()
    }
}

/// Read one, or `None` when there is no file yet — which is a first run,
/// not a failure. The version is checked **here**, once, for both callers.
pub(crate) fn read_sealed(path: &Path, what: &str) -> Result<Option<SealedFile>> {
    let raw = match std::fs::read(path) {
        Ok(raw) => raw,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => {
            return Err(SyncError::Identity {
                detail: format!("reading {}: {e}", path.display()),
            })
        }
    };
    let document: serde_json::Value = serde_json::from_slice(&raw)
        .map_err(|e| SyncError::Identity { detail: format!("{what} file: {e}") })?;
    let version = document.get("version").and_then(|v| v.as_i64()).unwrap_or(0);
    if version != FILE_VERSION {
        return Err(SyncError::Identity {
            detail: format!("{what} file version {version} is not one this build reads"),
        });
    }
    let protection = document
        .get("wrap")
        .and_then(|v| v.as_str())
        .unwrap_or(UNWRAPPED)
        .to_string();
    let hex_text = std::iter::once(SEALED)
        .chain(SEALED_WAS)
        .find_map(|field| document.get(field).and_then(|v| v.as_str()))
        .ok_or_else(|| SyncError::Identity {
            detail: format!("the {what} file holds nothing"),
        })?;
    let sealed = hex::decode(hex_text)
        .map_err(|e| SyncError::Identity { detail: format!("{what} contents: {e}") })?;
    Ok(Some(SealedFile { protection, document, sealed }))
}

/// Write one: whole, atomically, sealed by the registered adapter if there
/// is one, and carrying the reason when there is not. `plain_fields` are
/// written beside the sealed half in the clear — the public key, a device
/// id, the ancestry — because they are not secrets and a file that cannot
/// be opened should still be able to say whose it is.
pub(crate) fn write_sealed(
    path: &Path,
    what: &str,
    wrap: Option<&dyn KeyWrap>,
    plain: &[u8],
    plain_fields: serde_json::Value,
) -> Result<()> {
    let (protection, sealed, reason) = match wrap {
        Some(wrap) => {
            let sealed = wrap.wrap(plain).map_err(|detail| SyncError::Identity {
                detail: format!("{} could not wrap the {what}: {detail}", wrap.name()),
            })?;
            (wrap.name(), sealed, None)
        }
        None => (UNWRAPPED.to_string(), plain.to_vec(), Some(UNWRAPPED_REASON)),
    };
    let mut document = serde_json::json!({
        "version": FILE_VERSION,
        "wrap": protection,
        SEALED: hex::encode(&sealed),
    });
    if let Some(reason) = reason {
        document["unwrapped_reason"] = serde_json::Value::String(reason.to_string());
    }
    if let Some(fields) = plain_fields.as_object() {
        for (key, value) in fields {
            document[key] = value.clone();
        }
    }
    write_private(path, document.to_string().as_bytes())
}

/// This device's static keypair.
#[derive(Clone)]
pub struct Identity {
    private: Vec<u8>,
    public: Vec<u8>,
}

impl std::fmt::Debug for Identity {
    /// The private half never reaches a log line, a panic message or a
    /// test failure by accident.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Identity")
            .field("public", &self.public_hex())
            .field("private", &"<withheld>")
            .finish()
    }
}

impl Identity {
    pub fn private(&self) -> &[u8] {
        &self.private
    }

    pub fn public(&self) -> &[u8] {
        &self.public
    }

    pub fn public_hex(&self) -> String {
        hex::encode(&self.public)
    }

    pub fn fingerprint(&self) -> String {
        fingerprint_of(&self.public)
    }
}

/// A short, human-comparable name for a static key. Read aloud down a
/// telephone or checked against a QR, it is what "is that really my
/// laptop" means when there is no certificate authority anywhere.
pub fn fingerprint_of(public: &[u8]) -> String {
    let digest = Sha256::new()
        .chain_update(b"gilune:fingerprint:v1")
        .chain_update(public)
        .finalize();
    let hex = hex::encode_upper(&digest[..8]);
    hex.as_bytes()
        .chunks(4)
        .map(|c| String::from_utf8_lossy(c).to_string())
        .collect::<Vec<_>>()
        .join("-")
}

/// The compact form the QR carries — the same eight bytes without the
/// grouping, so a scan can compare fingerprints without parsing dashes.
pub fn fingerprint_compact(public: &[u8]) -> String {
    fingerprint_of(public).replace('-', "").to_lowercase()
}

/// Fresh X25519 material from the OS CSPRNG, by way of `snow`'s own
/// resolver — the same generator that will use the key, so there is one
/// notion of what a valid keypair is.
pub fn mint_identity() -> Result<Identity> {
    let params: snow::params::NoiseParams = crate::NOISE_PARAMS
        .parse()
        .map_err(|e| SyncError::Identity { detail: format!("noise parameters: {e}") })?;
    let keypair = snow::Builder::new(params)
        .generate_keypair()
        .map_err(|e| SyncError::Identity { detail: format!("generating a keypair: {e}") })?;
    Ok(Identity {
        private: keypair.private,
        public: keypair.public,
    })
}

// --------------------------------------------------------------- the store

/// The device-local directory the shell nominates, and the files inside
/// it. Path-agnostic by design: the kernel never learns where a platform
/// keeps its per-user state (ADR 0001).
pub struct IdentityStore {
    root: PathBuf,
}

impl IdentityStore {
    pub fn new(root: impl Into<PathBuf>) -> IdentityStore {
        IdentityStore { root: root.into() }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn identity_path(&self, device_id: &str) -> PathBuf {
        self.root.join(format!("{}.identity.json", slug(device_id)))
    }

    pub fn roster_path(&self, device_id: &str) -> PathBuf {
        self.root.join(format!("{}.roster.json", slug(device_id)))
    }

    /// Where this device records whether the user has turned sync on.
    ///
    /// **Device-local, beside the identity and the roster, and for the
    /// same reason** (ADR 0019 § 4): whether *this machine* answers the
    /// network is a fact about this machine, not about the vault. Putting
    /// it in the vault would mean copying a vault to a second machine
    /// switched sync on there, which is the opposite of the re-enrolment
    /// rule. Putting it in a shell preference would mean the desktop and
    /// the phone each keeping their own answer to one question, and ADR
    /// 0008 asks for one configuration model serving both.
    pub fn settings_path(&self, device_id: &str) -> PathBuf {
        self.root.join(format!("{}.sync.json", slug(device_id)))
    }

    /// Whether sync was left switched on. Absent, unreadable or malformed
    /// all mean **off** — the safe direction: a device that cannot read
    /// its own intent must not open a port on the strength of a guess.
    pub fn sync_wanted(&self, device_id: &str) -> bool {
        std::fs::read(self.settings_path(device_id))
            .ok()
            .and_then(|raw| serde_json::from_slice::<serde_json::Value>(&raw).ok())
            .and_then(|v| v.get("enabled").and_then(|e| e.as_bool()))
            .unwrap_or(false)
    }

    /// Record what the user chose. Not wrapped by `KeyWrap`: it is a
    /// boolean about this machine's networking, not key material, and
    /// wrapping it would mean a device that lost its wrapper could no
    /// longer tell whether it was meant to be reachable.
    pub fn set_sync_wanted(&self, device_id: &str, enabled: bool) -> Result<()> {
        write_private(
            &self.settings_path(device_id),
            serde_json::json!({ "enabled": enabled }).to_string().as_bytes(),
        )
    }

    /// Read the identity held for `device_id`, or `None` if this machine
    /// holds none — which is the whole of "the vault came from
    /// elsewhere".
    pub fn load(&self, device_id: &str, wrap: Option<&dyn KeyWrap>) -> Result<Option<Identity>> {
        let Some(file) = read_sealed(&self.identity_path(device_id), "identity")? else {
            return Ok(None);
        };
        let public = hex_field(&file.document, "public")?;
        // A sealed file that will not open raises `IdentityUnreadable`
        // from `open` itself: "this machine holds it and cannot use it" is
        // a different answer from "there is nothing here yet", which is
        // enrolment, and minting over a sealed key would throw away the
        // identity every existing pairing is with.
        let private = file.open(wrap, "identity key")?;
        if private.len() != 32 || public.len() != 32 {
            return Err(SyncError::Identity {
                detail: "the identity file does not hold an X25519 keypair".to_string(),
            });
        }
        Ok(Some(Identity { private, public }))
    }

    /// What protected the stored key, for the settings screen to show.
    /// Lenient by design: a screen asking what guards a key must not fail
    /// to draw because the answer is "nothing this build understands".
    pub fn protection(&self, device_id: &str) -> String {
        read_sealed(&self.identity_path(device_id), "identity")
            .ok()
            .flatten()
            .map(|file| file.protection)
            .unwrap_or_else(|| UNWRAPPED.to_string())
    }

    /// The device ids this machine used for this vault **before** it
    /// re-enrolled, newest first (ADR 0019 § 4 meeting § 9).
    ///
    /// A re-enrolled device is the same machine with a new name for
    /// itself, and the history it wrote under the old name is still in the
    /// vault and still travelling between replicas. Without this a peer
    /// that revoked the old device and then re-paired the new one had no
    /// way to know they were the same device, so the old stream stayed
    /// refused for ever and a third replica relaying it raised an alert
    /// nobody could clear.
    ///
    /// Lenient like `protection`: a file that will not parse costs a peer
    /// one piece of ancestry, not an open.
    pub fn previous_device_ids(&self, device_id: &str) -> Vec<String> {
        read_sealed(&self.identity_path(device_id), "identity")
            .ok()
            .flatten()
            .map(|file| file.strings("previous_device_ids"))
            .unwrap_or_default()
    }

    /// Mint and store an identity for `device_id`, replacing anything
    /// already there. Written whole and renamed into place, so a crash
    /// leaves either the previous file or the new one and never half of
    /// either.
    pub fn mint(&self, device_id: &str, wrap: Option<&dyn KeyWrap>) -> Result<Identity> {
        let identity = mint_identity()?;
        self.write(device_id, &identity, wrap, &[])?;
        Ok(identity)
    }

    fn write(
        &self,
        device_id: &str,
        identity: &Identity,
        wrap: Option<&dyn KeyWrap>,
        previous_device_ids: &[String],
    ) -> Result<()> {
        write_sealed(
            &self.identity_path(device_id),
            "identity key",
            wrap,
            identity.private(),
            serde_json::json!({
                "device_id": device_id,
                "public": hex::encode(identity.public()),
                "created_ms": gilune_kernel::ids::now_ms(),
                "previous_device_ids": previous_device_ids,
            }),
        )
    }
}

/// The device ids one machine has used for one vault, newest first,
/// deduplicated and bounded.
fn ancestry(previous_device_id: &str, older: Vec<String>) -> Vec<String> {
    let mut chain: Vec<String> = Vec::new();
    for id in std::iter::once(previous_device_id.to_string()).chain(older) {
        if !id.is_empty() && !chain.contains(&id) {
            chain.push(id);
        }
    }
    chain.truncate(MAX_PREVIOUS_DEVICE_IDS);
    chain
}

fn hex_field(document: &serde_json::Value, field: &str) -> Result<Vec<u8>> {
    let text = document
        .get(field)
        .and_then(|v| v.as_str())
        .ok_or_else(|| SyncError::Identity {
            detail: format!("the identity file has no {field}"),
        })?;
    hex::decode(text).map_err(|e| SyncError::Identity {
        detail: format!("{field} is not hexadecimal: {e}"),
    })
}

/// A device id is a UUIDv7 today, but it is a string on the wire and this
/// builds a file name from it. Anything that is not plainly safe becomes
/// a hyphen, so no identifier can ever reach outside the directory.
fn slug(device_id: &str) -> String {
    let cleaned: String = device_id
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' { c } else { '-' })
        .take(64)
        .collect();
    if cleaned.is_empty() {
        "unnamed".to_string()
    } else {
        cleaned
    }
}

/// Write a file holding key material: whole, atomically, and as private
/// as the platform allows.
///
/// The permissions story, honestly. On Unix the file is created 0600 and
/// only this user can read it. **On Windows there is no mode set here**:
/// the protection is that `%LOCALAPPDATA%` is already ACL'd to this user
/// by the profile, and DPAPI user scope is what actually guards the
/// bytes. On Android `filesDir` is private to the application's UID by
/// the platform's own construction. None of the three protects against
/// code running as this user.
pub(crate) fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    let dir = path.parent().unwrap_or(Path::new("."));
    std::fs::create_dir_all(dir).map_err(|e| SyncError::Identity {
        detail: format!("creating {}: {e}", dir.display()),
    })?;
    let temp = path.with_extension("writing");
    {
        let mut options = std::fs::OpenOptions::new();
        options.write(true).create(true).truncate(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temp).map_err(|e| SyncError::Identity {
            detail: format!("opening {}: {e}", temp.display()),
        })?;
        use std::io::Write;
        file.write_all(bytes).map_err(|e| SyncError::Identity {
            detail: format!("writing {}: {e}", temp.display()),
        })?;
        file.sync_all().map_err(|e| SyncError::Identity {
            detail: format!("flushing {}: {e}", temp.display()),
        })?;
    }
    std::fs::rename(&temp, path).map_err(|e| SyncError::Identity {
        detail: format!("renaming into {}: {e}", path.display()),
    })?;
    let _ = gilune_kernel::fs::fsync_dir(dir);
    Ok(())
}

// ----------------------------------------------------------- re-enrolment

/// What opening this vault on this machine turned out to mean.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Enrolment {
    /// The local identity matches the one the vault records. Same device.
    Continued,
    /// The vault recorded no identity: the first M5 run adopts its
    /// `device_id` and writes the marker.
    Adopted,
    /// The vault came from elsewhere. A new identity, a new `device_id`
    /// and a fresh sequence space — and the user is told, because
    /// otherwise they wonder why their laptop appears twice.
    Reenrolled { previous_device_id: String },
}

impl Enrolment {
    pub fn as_str(&self) -> &'static str {
        match self {
            Enrolment::Continued => "continued",
            Enrolment::Adopted => "adopted",
            Enrolment::Reenrolled { .. } => "re-enrolled",
        }
    }

    /// The sentence the shells show, in their existing message idiom.
    /// `None` when nothing happened that anybody needs to hear about.
    pub fn notice(&self) -> Option<String> {
        match self {
            Enrolment::Continued => None,
            Enrolment::Adopted => None,
            Enrolment::Reenrolled { .. } => Some(
                "this vault came from elsewhere — this machine is now a new device, \
                 and it will need pairing again"
                    .to_string(),
            ),
        }
    }
}

/// The table in ADR 0019 § 4, run on open.
///
/// | local identity | `meta.identity_pub` | action |
/// | --- | --- | --- |
/// | present, matches | present | same device, continue |
/// | present | absent | first M5 run: adopt `device_id`, write the marker |
/// | absent, or mismatched | present | the vault came from elsewhere: mint a new identity and a new `device_id`, start a fresh sequence space |
pub fn enrol(
    store: &Store,
    identities: &IdentityStore,
    wrap: Option<&dyn KeyWrap>,
) -> Result<(Identity, Enrolment)> {
    let device_id = store.device_id()?;
    let recorded = store.identity_pub()?;
    let local = identities.load(&device_id, wrap)?;

    match (local, recorded) {
        // Row one: this machine holds the key the vault names.
        (Some(identity), Some(recorded)) if recorded == identity.public_hex() => {
            Ok((identity, Enrolment::Continued))
        }
        // Row two: a vault no M5 kernel has opened. Adopt it, whether the
        // key was already here or is minted now — either way this machine
        // is the device the vault already believed itself to belong to,
        // and nothing about its sequence space changes.
        (local, None) => {
            let identity = match local {
                Some(identity) => identity,
                None => identities.mint(&device_id, wrap)?,
            };
            store.set_identity_pub(&identity.public_hex())?;
            Ok((identity, Enrolment::Adopted))
        }
        // Row three: the vault names an identity this machine does not
        // hold. It was copied, restored or carried here.
        (_, Some(_)) => {
            let identity = mint_identity()?;
            let fresh = gilune_kernel::ids::new_id();
            // What this machine used to call itself, carried forward so a
            // peer can learn it at the next pairing. § 9 refuses by
            // originating device id; § 4 changes that id under a machine
            // that has not changed at all. Without the ancestry those two
            // rules together mean a re-enrolled device's older history
            // stays refused by a peer that has already welcomed the device
            // back — visibly, as a standing alert nobody can clear.
            let previous = ancestry(&device_id, identities.previous_device_ids(&device_id));
            // The key file is written **before** the vault is told about
            // it. The failure that order avoids is the expensive one: a
            // vault that has taken a new device id with no local identity
            // behind it would re-enrol again on the next open, and again
            // after that. This order can only leave an unused key file
            // for a device id nothing claims, which costs a few hundred
            // bytes and nothing else.
            identities.write(&fresh, &identity, wrap, &previous)?;
            let previous_device_id = store.reenrol_as_new_device(&fresh)?;
            store.set_identity_pub(&identity.public_hex())?;
            Ok((identity, Enrolment::Reenrolled { previous_device_id }))
        }
    }
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

    fn vault() -> (tempfile::TempDir, tempfile::TempDir, Store, IdentityStore) {
        let vault = tempfile::tempdir().expect("vault");
        let local = tempfile::tempdir().expect("device-local");
        let store = Store::open(vault.path()).expect("open");
        let identities = IdentityStore::new(local.path());
        (vault, local, store, identities)
    }

    #[test]
    fn a_minted_identity_is_thirty_two_bytes_each_way_and_never_repeats() {
        let a = mint_identity().expect("mint");
        let b = mint_identity().expect("mint");
        assert_eq!(a.private().len(), 32);
        assert_eq!(a.public().len(), 32);
        assert_ne!(a.public_hex(), b.public_hex());
    }

    #[test]
    fn the_private_half_is_withheld_from_debug_output() {
        let identity = mint_identity().expect("mint");
        let rendered = format!("{identity:?}");
        assert!(rendered.contains("withheld"));
        assert!(
            !rendered.contains(&hex::encode(identity.private())),
            "a key that reaches a log line is a key that has left the device"
        );
    }

    #[test]
    fn a_wrapped_key_round_trips_and_the_file_never_holds_the_plain_secret() {
        let (_vault, _local, store, identities) = vault();
        let device = store.device_id().expect("device");
        let wrap = Reversing;
        let minted = identities.mint(&device, Some(&wrap)).expect("mint");
        let raw = std::fs::read_to_string(identities.identity_path(&device)).expect("read");
        assert!(
            !raw.contains(&hex::encode(minted.private())),
            "the wrapped file must not contain the unwrapped key"
        );
        assert!(raw.contains("reversing-test-wrapper"));
        let loaded = identities
            .load(&device, Some(&wrap))
            .expect("load")
            .expect("an identity");
        assert_eq!(loaded.private(), minted.private());
        assert_eq!(loaded.public_hex(), minted.public_hex());
    }

    #[test]
    fn a_wrapped_key_cannot_be_read_without_the_wrapper_that_sealed_it() {
        let (_vault, _local, store, identities) = vault();
        let device = store.device_id().expect("device");
        identities.mint(&device, Some(&Reversing)).expect("mint");
        assert!(matches!(
            identities.load(&device, None),
            Err(SyncError::IdentityUnreadable { .. })
        ));
        // The second-order case, and the reason the variant is its own:
        // no identity file at all is *enrolment*, not a sealed key. If
        // both read the same, a host cannot tell "mint one" from "do not
        // dare mint one".
        assert!(matches!(identities.load("never-enrolled", None), Ok(None)));
    }

    #[test]
    fn an_absent_wrapper_records_why_the_key_is_unwrapped() {
        let (_vault, _local, store, identities) = vault();
        let device = store.device_id().expect("device");
        identities.mint(&device, None).expect("mint");
        let raw = std::fs::read_to_string(identities.identity_path(&device)).expect("read");
        assert!(raw.contains("unwrapped_reason"));
        assert_eq!(identities.protection(&device), UNWRAPPED);
    }

    /// **The shared envelope, both ways.** One writer, one reader, one
    /// version check — and `protection` reads through the same function
    /// rather than picking the `wrap` field out of the JSON on its own,
    /// which is how the two copies came to answer differently about what
    /// an unopenable file was.
    #[test]
    fn a_sealed_file_round_trips_through_the_shared_envelope() {
        let dir = tempfile::tempdir().expect("dir");
        let path = dir.path().join("thing.json");
        let wrap: Option<&dyn KeyWrap> = Some(&Reversing);

        assert!(
            read_sealed(&path, "thing").expect("absent is not an error").is_none(),
            "no file yet is a first run, not a failure"
        );

        write_sealed(
            &path,
            "thing",
            wrap,
            b"the body",
            serde_json::json!({ "who": "a device" }),
        )
        .expect("write");
        let file = read_sealed(&path, "thing").expect("read").expect("a file");
        assert_eq!(file.protection, "reversing-test-wrapper");
        assert_eq!(
            file.document.get("who").and_then(|v| v.as_str()),
            Some("a device"),
            "plain fields ride beside the seal"
        );
        assert_eq!(file.open(wrap, "thing").expect("open"), b"the body");
        assert!(
            file.open(None, "thing").is_err(),
            "and the seal is a seal: no wrapper, no contents"
        );

        // Unwrapped files say why, whatever kind of file they are.
        write_sealed(&path, "thing", None, b"the body", serde_json::json!({})).expect("write");
        let file = read_sealed(&path, "thing").expect("read").expect("a file");
        assert_eq!(file.protection, UNWRAPPED);
        assert!(file
            .document
            .get("unwrapped_reason")
            .and_then(|v| v.as_str())
            .is_some_and(|why| why.contains("unwrapped")));
        assert_eq!(file.open(None, "thing").expect("open"), b"the body");
    }

    /// **Second-order: the field rename must not cost anybody their key.**
    /// The identity file used to spell the sealed half `secret` and the
    /// roster spelled it `payload`. A build that read only the new name
    /// would meet every existing installation as a file it could not open
    /// — which for the roster means every pairing gone, silently, on the
    /// update that tidied a name.
    #[test]
    fn a_file_written_by_the_previous_layout_still_opens() {
        let dir = tempfile::tempdir().expect("dir");
        for old_name in ["secret", "payload"] {
            let path = dir.path().join(format!("{old_name}.json"));
            let document = serde_json::json!({
                "version": FILE_VERSION,
                "wrap": UNWRAPPED,
                old_name: hex::encode(b"what an older build wrote"),
                "public": hex::encode([7u8; 32]),
            });
            std::fs::write(&path, document.to_string()).expect("write");

            let file = read_sealed(&path, "thing").expect("read").expect("a file");
            assert_eq!(
                file.open(None, "thing").expect("open"),
                b"what an older build wrote",
                "{old_name} was the field name in the field"
            );
            assert_eq!(
                file.document.get("public").and_then(|v| v.as_str()),
                Some(hex::encode([7u8; 32]).as_str())
            );
        }
    }

    /// A re-enrolled machine remembers what it used to be called, so a
    /// peer can stop refusing the history it wrote then.
    #[test]
    fn re_enrolment_records_the_device_id_this_machine_used_before() {
        let (_vault, _local, store, identities) = vault();
        enrol(&store, &identities, None).expect("adopt");
        let first_device = store.device_id().expect("device");

        let elsewhere_dir = tempfile::tempdir().expect("elsewhere");
        let elsewhere = IdentityStore::new(elsewhere_dir.path());
        enrol(&store, &elsewhere, None).expect("re-enrol");
        let second_device = store.device_id().expect("device");
        assert_ne!(second_device, first_device);
        assert_eq!(
            elsewhere.previous_device_ids(&second_device),
            vec![first_device.clone()],
            "the new identity has to be able to say whose history it inherited"
        );

        // The chain extends when one machine re-enrols the same vault
        // twice — a backup restored, and then restored again: the vault is
        // overwritten by another copy of itself, so its marker stops
        // naming the key this machine holds for it.
        store
            .set_identity_pub(&mint_identity().expect("mint").public_hex())
            .expect("a copy from elsewhere lands on top");
        enrol(&store, &elsewhere, None).expect("re-enrol again");
        let third_device = store.device_id().expect("device");
        assert_eq!(
            elsewhere.previous_device_ids(&third_device),
            vec![second_device, first_device],
            "two ancestors, newest first"
        );

        // **What a machine cannot know, stated exactly.** The ancestry is
        // read from the identity file *this* machine holds, so a vault
        // that hops from machine to machine gives each of them one hop —
        // a third machine never held the second's key and cannot vouch
        // for what it inherited. A real limit, in the safe direction.
        let far_dir = tempfile::tempdir().expect("a third machine");
        let far = IdentityStore::new(far_dir.path());
        enrol(&store, &far, None).expect("re-enrol somewhere new");
        assert_eq!(
            far.previous_device_ids(&store.device_id().expect("device")),
            vec![third_device],
            "a machine names the device id it took over from, and no further"
        );
    }

    #[test]
    fn the_ancestry_is_bounded_and_says_each_device_once() {
        let older: Vec<String> = (0..20).map(|n| format!("device-{n}")).collect();
        let chain = ancestry("device-0", older);
        assert_eq!(chain.len(), MAX_PREVIOUS_DEVICE_IDS);
        assert_eq!(chain[0], "device-0");
        assert_eq!(chain[1], "device-1", "the duplicate of the head is not repeated");
        assert!(ancestry("", vec![String::new()]).is_empty());
    }

    #[test]
    fn a_fingerprint_is_stable_short_and_grouped_for_reading_aloud() {
        let identity = mint_identity().expect("mint");
        let printed = identity.fingerprint();
        assert_eq!(printed, fingerprint_of(identity.public()));
        assert_eq!(printed.len(), 19, "four groups of four, three dashes");
        assert_eq!(printed.matches('-').count(), 3);
        assert_eq!(fingerprint_compact(identity.public()).len(), 16);
    }

    #[test]
    fn first_open_adopts_the_vaults_device_id_and_writes_the_marker() {
        let (_vault, _local, store, identities) = vault();
        let before = store.device_id().expect("device");
        assert!(store.identity_pub().expect("marker").is_none());

        let (identity, outcome) = enrol(&store, &identities, None).expect("enrol");
        assert_eq!(outcome, Enrolment::Adopted);
        assert_eq!(store.device_id().expect("device"), before, "the same device");
        assert_eq!(
            store.identity_pub().expect("marker"),
            Some(identity.public_hex())
        );
        assert!(outcome.notice().is_none(), "nothing happened worth saying");
    }

    #[test]
    fn opening_again_on_the_same_machine_simply_continues() {
        let (_vault, _local, store, identities) = vault();
        let (first, _) = enrol(&store, &identities, None).expect("first");
        let (second, outcome) = enrol(&store, &identities, None).expect("second");
        assert_eq!(outcome, Enrolment::Continued);
        assert_eq!(first.public_hex(), second.public_hex());
    }

    #[test]
    fn a_vault_whose_identity_this_machine_does_not_hold_re_enrols_visibly() {
        let (_vault, _local, store, identities) = vault();
        let (original, _) = enrol(&store, &identities, None).expect("enrol");
        let original_device = store.device_id().expect("device");

        // A second machine: the same vault, a device-local store that has
        // never seen it.
        let elsewhere = tempfile::tempdir().expect("elsewhere");
        let elsewhere = IdentityStore::new(elsewhere.path());
        let (fresh, outcome) = enrol(&store, &elsewhere, None).expect("enrol elsewhere");

        assert!(matches!(outcome, Enrolment::Reenrolled { .. }));
        assert_ne!(fresh.public_hex(), original.public_hex(), "a new identity");
        assert_ne!(store.device_id().expect("device"), original_device);
        assert_eq!(
            store.identity_pub().expect("marker"),
            Some(fresh.public_hex()),
            "the vault now names the identity that actually holds it"
        );
        assert!(
            outcome.notice().expect("a notice").contains("new device"),
            "the UI must say so rather than letting a user wonder"
        );
    }
}
