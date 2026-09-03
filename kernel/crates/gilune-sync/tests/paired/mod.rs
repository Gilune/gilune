//! Devices, not handles.
//!
//! Each `Device` is a real vault with its own kernel store, its own
//! device-local identity directory outside that vault, and its own
//! listener thread which opens a *second* kernel handle exactly as a
//! separate process would. Nothing in these tests reaches into another
//! device's database or roster: a peer is reached over a socket through
//! a Noise session or it is not reached at all, which is what makes a
//! passing scenario evidence about the boundary rather than about a
//! shared `Vec`.

// Every integration test binary compiles this module whole and uses the
// part it needs; unused-here is not unused.
#![allow(dead_code)]

use gilune_kernel::limits::Limits;
use gilune_kernel::store::Store;
use gilune_sync::rate::RateLimiter;
use gilune_sync::service::{PeerSummary, SyncService};
use gilune_sync::{ExchangeReport, Session};
use std::path::Path;

pub struct Device {
    pub name: String,
    pub vault_path: std::path::PathBuf,
    pub local_path: std::path::PathBuf,
    /// Kept only so the directories outlive the device when the device
    /// made them. A device reopened at existing paths owns neither, which
    /// is what lets "the process ended and another one started" be a
    /// scenario rather than a comment.
    _vault: Option<tempfile::TempDir>,
    _local: Option<tempfile::TempDir>,
    pub store: Store,
    pub service: SyncService,
}

impl Device {
    /// A device that is not yet listening. Turning sync on is a separate
    /// step because binding the socket is a separate step (§ 2).
    pub fn new(name: &str) -> Device {
        let vault = tempfile::tempdir().expect("vault");
        let local = tempfile::tempdir().expect("device-local");
        Device::at(name, vault, local)
    }

    pub fn at(name: &str, vault: tempfile::TempDir, local: tempfile::TempDir) -> Device {
        let mut device = Device::open(name, vault.path(), local.path());
        device._vault = Some(vault);
        device._local = Some(local);
        device
    }

    /// Open a device at directories somebody else is keeping alive.
    pub fn open(name: &str, vault: &Path, local: &Path) -> Device {
        let store = Store::open(vault).expect("open");
        store
            .register_plugin(&gilune_kernel::vault::plugin_descriptor())
            .expect("every host declares the projection");
        let service = SyncService::configure(&store, local, name, None, Limits::DEFAULT)
            .expect("configure sync");
        // Tests must never be at the mercy of a backoff they did not
        // ask for; the ones that are *about* backoff set their own.
        service.configure_rate_limits(RateLimiter::new(1_000_000, 0, 0, 1));
        Device {
            name: name.to_string(),
            vault_path: vault.to_path_buf(),
            local_path: local.to_path_buf(),
            _vault: None,
            _local: None,
            store,
            service,
        }
    }

    /// Subscribe the deterministic stand-in processor, as a device that
    /// has transcription installed would.
    pub fn with_processor(self) -> Device {
        self.store
            .register_plugin(&gilune_kernel::processor::fake_transcriber_descriptor())
            .expect("processor plugin");
        self
    }

    pub fn listening(self) -> Device {
        self.service.set_enabled(true).expect("bind");
        self
    }

    pub fn address(&self) -> String {
        self.service.listening_on().expect("a listening device")
    }

    pub fn identity_pub(&self) -> String {
        self.service.identity_public_hex()
    }

    pub fn device_id(&self) -> String {
        self.store.device_id().expect("device id")
    }

    /// Capture a thought here, as this device's own app would.
    pub fn capture(&self, text: &str) -> String {
        self.store
            .capture_bytes("txt", text.as_bytes())
            .expect("capture")
            .source_id
    }

    /// Run whatever work this device owns, as its own app would.
    pub fn work(&self) -> i64 {
        self.store
            .run_pending_jobs(
                &[&gilune_kernel::processor::FakeTranscriber],
                gilune_kernel::ids::now_ms(),
                30_000,
            )
            .expect("work")
            .outputs
    }

    pub fn peer(&self, other: &Device) -> Option<PeerSummary> {
        self.peer_by_key(&other.identity_pub())
    }

    /// The same, by static key — for scenarios where the device that key
    /// belongs to has been dropped and rebuilt.
    pub fn peer_by_key(&self, static_pub: &str) -> Option<PeerSummary> {
        self.service
            .peers()
            .into_iter()
            .find(|p| p.static_pub == static_pub)
    }

    /// Pair with a listening device by typing the code it is showing.
    pub fn pair_by_code(&self, listener: &Device) -> gilune_sync::Result<PeerSummary> {
        let offer = listener.service.start_pairing().expect("open a window");
        self.service
            .pair_with_code(&self.store, &offer.address, &offer.code)
    }

    /// Pair with a listening device by scanning what its QR encodes.
    pub fn pair_by_qr(&self, listener: &Device) -> gilune_sync::Result<PeerSummary> {
        listener.service.start_pairing().expect("open a window");
        // The matrix is what a shell paints; the payload is what a
        // scanner reads out of it. Asking for both proves the encoder
        // ran over the payload this then joins with.
        let matrix = listener.service.pairing_qr().expect("a matrix");
        assert!(matrix.width >= 21 && !matrix.modules.is_empty());
        let payload = listener
            .service
            .pairing_qr_payload()
            .expect("the payload behind it");
        self.service.pair_from_qr(&self.store, &payload)
    }

    /// One full round with every paired peer.
    pub fn sync(&self) -> ExchangeReport {
        self.service.sync_now(&self.store).expect("sync")
    }

    /// A session with one peer, for a test that wants to drive requests
    /// itself.
    pub fn dial(&self, peer: &Device) -> gilune_sync::Result<Box<dyn Session>> {
        self.service.dial_peer(&peer.identity_pub())
    }

    /// Everything two converged replicas must agree on — the same digest
    /// the M5a scenarios compare.
    pub fn digest(&self) -> String {
        self.store.convergence_digest().expect("digest")
    }

    /// How far this device has applied another's stream.
    pub fn frontier(&self, other: &Device) -> i64 {
        self.store
            .sync_frontier(&other.device_id())
            .expect("frontier")
    }

    /// How many operations this device holds that originated on `device`.
    pub fn holds_from(&self, device: &str) -> i64 {
        self.store
            .operation_devices()
            .expect("devices")
            .into_iter()
            .find(|(id, _)| id == device)
            .map(|(_, highest)| highest)
            .unwrap_or(0)
    }

    pub fn alerts_of(&self, kind: &str) -> Vec<gilune_kernel::alerts::Alert> {
        self.store.integrity_alerts_of(kind).expect("alerts")
    }
}

/// Copy a whole vault directory, the way a person copies a folder to a
/// memory stick. Used for the re-enrolment scenario, which is entirely
/// about what happens when a vault arrives somewhere its device did not.
pub fn copy_tree(from: &Path, to: &Path) {
    std::fs::create_dir_all(to).expect("create");
    for entry in std::fs::read_dir(from).expect("read dir") {
        let entry = entry.expect("entry");
        let target = to.join(entry.file_name());
        if entry.file_type().expect("file type").is_dir() {
            copy_tree(&entry.path(), &target);
        } else {
            std::fs::copy(entry.path(), &target).expect("copy");
        }
    }
}
