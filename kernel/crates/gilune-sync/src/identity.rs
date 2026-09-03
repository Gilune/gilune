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

    /// Read the identity held for `device_id`, or `None` if this machine
    /// holds none — which is the whole of "the vault came from
    /// elsewhere".
    pub fn load(&self, device_id: &str, wrap: Option<&dyn KeyWrap>) -> Result<Option<Identity>> {
        let path = self.identity_path(device_id);
        let raw = match std::fs::read(&path) {
            Ok(raw) => raw,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => {
                return Err(SyncError::Identity {
                    detail: format!("reading {}: {e}", path.display()),
                })
            }
        };
        let document: serde_json::Value = serde_json::from_slice(&raw)
            .map_err(|e| SyncError::Identity { detail: format!("identity file: {e}") })?;
        let version = document.get("version").and_then(|v| v.as_i64()).unwrap_or(0);
        if version != FILE_VERSION {
            return Err(SyncError::Identity {
                detail: format!("identity file version {version} is not one this build reads"),
            });
        }
        let public = hex_field(&document, "public")?;
        let sealed = hex_field(&document, "secret")?;
        let protection = document
            .get("wrap")
            .and_then(|v| v.as_str())
            .unwrap_or(UNWRAPPED)
            .to_string();
        let private = if protection == UNWRAPPED {
            sealed
        } else {
            let Some(wrap) = wrap else {
                return Err(SyncError::Identity {
                    detail: format!(
                        "this key was wrapped by {protection}, and no key wrapper is \
                         registered on this host to open it"
                    ),
                });
            };
            wrap.unwrap(&sealed).map_err(|detail| SyncError::Identity {
                detail: format!("{protection} could not unwrap the identity key: {detail}"),
            })?
        };
        if private.len() != 32 || public.len() != 32 {
            return Err(SyncError::Identity {
                detail: "the identity file does not hold an X25519 keypair".to_string(),
            });
        }
        Ok(Some(Identity { private, public }))
    }

    /// What protected the stored key, for the settings screen to show.
    pub fn protection(&self, device_id: &str) -> String {
        let Ok(raw) = std::fs::read(self.identity_path(device_id)) else {
            return UNWRAPPED.to_string();
        };
        serde_json::from_slice::<serde_json::Value>(&raw)
            .ok()
            .and_then(|d| d.get("wrap").and_then(|v| v.as_str()).map(str::to_string))
            .unwrap_or_else(|| UNWRAPPED.to_string())
    }

    /// Mint and store an identity for `device_id`, replacing anything
    /// already there. Written whole and renamed into place, so a crash
    /// leaves either the previous file or the new one and never half of
    /// either.
    pub fn mint(&self, device_id: &str, wrap: Option<&dyn KeyWrap>) -> Result<Identity> {
        let identity = mint_identity()?;
        self.write(device_id, &identity, wrap)?;
        Ok(identity)
    }

    fn write(&self, device_id: &str, identity: &Identity, wrap: Option<&dyn KeyWrap>) -> Result<()> {
        std::fs::create_dir_all(&self.root).map_err(|e| SyncError::Identity {
            detail: format!("creating {}: {e}", self.root.display()),
        })?;
        let (protection, sealed, reason) = match wrap {
            Some(wrap) => {
                let sealed = wrap.wrap(identity.private()).map_err(|detail| {
                    SyncError::Identity {
                        detail: format!("{} could not wrap the identity key: {detail}", wrap.name()),
                    }
                })?;
                (wrap.name(), sealed, None)
            }
            // ADR 0019 § 4 allows the fallback and asks for the reason to
            // be recorded rather than assumed away.
            None => (
                UNWRAPPED.to_string(),
                identity.private().to_vec(),
                Some(
                    "no platform key wrapper was registered by this host, so the key is \
                     stored unwrapped and protected only by the permissions of its \
                     directory"
                        .to_string(),
                ),
            ),
        };
        let mut document = serde_json::json!({
            "version": FILE_VERSION,
            "device_id": device_id,
            "public": hex::encode(identity.public()),
            "wrap": protection,
            "secret": hex::encode(&sealed),
            "created_ms": gilune_kernel::ids::now_ms(),
        });
        if let Some(reason) = reason {
            document["unwrapped_reason"] = serde_json::Value::String(reason);
        }
        write_private(&self.identity_path(device_id), document.to_string().as_bytes())
    }
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
            // The key file is written **before** the vault is told about
            // it. The failure that order avoids is the expensive one: a
            // vault that has taken a new device id with no local identity
            // behind it would re-enrol again on the next open, and again
            // after that. This order can only leave an unused key file
            // for a device id nothing claims, which costs a few hundred
            // bytes and nothing else.
            identities.write(&fresh, &identity, wrap)?;
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
            Err(SyncError::Identity { .. })
        ));
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
