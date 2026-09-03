//! Two or three replicas on one machine, each a real vault with its own
//! kernel handle, talking over a real loopback socket. The point of the
//! shape: nothing in these tests reaches into another replica's database,
//! so a scenario that passes passed over a transport.

// Every integration test binary compiles this module whole and uses the
// part it needs; unused-here is not unused.
#![allow(dead_code)]

use gilune_kernel::limits::Limits;
use gilune_kernel::operations::BlobNeed;
use gilune_kernel::store::Store;
use gilune_sync::loopback::{LoopbackServer, LoopbackTransport};
use gilune_sync::transport::{DeviceOffer, Frame, Session, Transport};
use gilune_sync::{exchange, ExchangeReport};

pub struct Replica {
    pub dir: tempfile::TempDir,
    pub store: Store,
    server: LoopbackServer,
    limits: Limits,
}

impl Replica {
    pub fn new() -> Replica {
        Replica::with_limits(Limits::DEFAULT)
    }

    pub fn with_limits(limits: Limits) -> Replica {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store
            .register_plugin(&gilune_kernel::vault::plugin_descriptor())
            .expect("every host declares the projection");
        let server = LoopbackServer::start(dir.path(), limits).expect("serve");
        Replica { dir, store, server, limits }
    }

    /// Subscribe the deterministic stand-in processor, as a device that
    /// has transcription installed would.
    pub fn with_processor(self) -> Replica {
        self.store
            .register_plugin(&gilune_kernel::processor::fake_transcriber_descriptor())
            .expect("processor plugin");
        self
    }

    pub fn address(&self) -> &str {
        self.server.address()
    }

    pub fn limits(&self) -> Limits {
        self.limits
    }

    /// Change the bounds this replica applies to what arrives. Bounds are
    /// configuration (ADR 0019 § 8), and "resumes when the condition
    /// clears" is only testable if the condition can be cleared.
    pub fn relimit(&mut self, limits: Limits) {
        self.limits = limits;
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

    /// Take everything this replica lacks from `peer`, in one session.
    pub fn pull_from(&self, peer: &Replica) -> ExchangeReport {
        let mut session = self.dial(peer);
        let report = exchange::pull(&self.store, session.as_mut(), self.limits).expect("pull");
        let _ = session.close();
        report
    }

    /// Give and take, in one session.
    pub fn sync_with(&self, peer: &Replica) -> ExchangeReport {
        let mut session = self.dial(peer);
        let report = exchange::sync(&self.store, session.as_mut(), self.limits).expect("sync");
        let _ = session.close();
        report
    }

    /// Pull through a session that misbehaves the way a socket can.
    pub fn pull_through(&self, peer: &Replica, fault: Fault) -> ExchangeReport {
        let inner = self.dial(peer);
        let mut session = FaultySession { inner, fault };
        let report = exchange::pull(&self.store, &mut session, self.limits).expect("pull");
        let _ = session.close();
        report
    }

    pub fn dial(&self, peer: &Replica) -> Box<dyn Session> {
        LoopbackTransport::new(self.limits)
            .connect(peer.address())
            .expect("connect")
    }

    pub fn digest(&self) -> String {
        self.store.convergence_digest().expect("digest")
    }

    pub fn path(&self, rel: &str) -> std::path::PathBuf {
        self.dir.path().join(rel)
    }

    /// The projected Markdown path of an object, once projection has run.
    pub fn projection_of(&self, object_id: &str) -> Option<String> {
        self.store
            .recent_sources(50)
            .expect("sources")
            .into_iter()
            .find(|s| s.object_id == object_id)
            .and_then(|s| s.projection_rel_path)
    }

    pub fn alert_kinds(&self) -> Vec<String> {
        let mut kinds: Vec<String> = self
            .store
            .integrity_alerts()
            .expect("alerts")
            .into_iter()
            .map(|a| a.kind)
            .collect();
        kinds.sort();
        kinds
    }
}

/// What a hostile or broken transport does to the frames it carries.
/// Every one of these is something a socket permits and a shared folder
/// did not, which is why M5a runs the scenarios again over one.
#[derive(Debug, Clone)]
pub enum Fault {
    /// Silently withhold the operation with this sequence number, so the
    /// receiver sees a hole in the stream.
    WithholdSeq(i64),
    /// Damage the bytes of the first operation delivered.
    CorruptFirst,
    /// Pad the first operation past any sane envelope size.
    InflateFirst(usize),
    /// Deliver in reverse order.
    Reverse,
    /// Deliver everything twice.
    Duplicate,
    /// Claim a chunk of content belongs somewhere it does not, so the
    /// receiver is offered bytes that would leave a hole.
    MisplaceChunk,
    /// Do not offer this device's stream at all, so the peer's own
    /// operations arrive while the history they refer to does not. Not a
    /// hostile transport: a full replica offers several devices' streams
    /// and a pull takes them one at a time, so one arriving before
    /// another is the ordinary case — this only makes it deterministic.
    WithholdDevice(String),
}

pub struct FaultySession {
    inner: Box<dyn Session>,
    fault: Fault,
}

impl Session for FaultySession {
    fn devices(&mut self) -> gilune_sync::Result<Vec<DeviceOffer>> {
        let offers = self.inner.devices()?;
        Ok(match &self.fault {
            Fault::WithholdDevice(device) => offers
                .into_iter()
                .filter(|offer| &offer.device_id != device)
                .collect(),
            _ => offers,
        })
    }

    fn have_after(
        &mut self,
        device: &str,
        seq: i64,
        max_ops: usize,
    ) -> gilune_sync::Result<Vec<Frame>> {
        let frames = self.inner.have_after(device, seq, max_ops)?;
        Ok(match &self.fault {
            Fault::WithholdSeq(withheld) => frames
                .into_iter()
                .filter(|frame| match frame {
                    Frame::Envelope(bytes) => {
                        gilune_kernel::operations::decode(bytes, &Limits::DEFAULT)
                            .map(|e| e.op_seq != *withheld)
                            .unwrap_or(true)
                    }
                    _ => true,
                })
                .collect(),
            Fault::CorruptFirst => damage_first(frames, |mut bytes| {
                bytes.splice(0..1, b"{not-json".iter().copied());
                bytes
            }),
            Fault::InflateFirst(to) => damage_first(frames, |bytes| {
                let mut value: serde_json::Value =
                    serde_json::from_slice(&bytes).expect("an envelope is json");
                value["payload"]["padding"] = serde_json::Value::String("x".repeat(*to));
                value.to_string().into_bytes()
            }),
            Fault::Reverse => frames.into_iter().rev().collect(),
            Fault::Duplicate => frames
                .iter()
                .flat_map(|f| [f.clone(), f.clone()])
                .collect(),
            // These two damage content, not operations.
            Fault::MisplaceChunk | Fault::WithholdDevice(_) => frames,
        })
    }

    fn have_blob(
        &mut self,
        blob_id: &str,
        offset: u64,
        max_bytes: usize,
    ) -> gilune_sync::Result<Vec<Frame>> {
        let frames = self.inner.have_blob(blob_id, offset, max_bytes)?;
        let Fault::MisplaceChunk = &self.fault else { return Ok(frames) };
        Ok(frames
            .into_iter()
            .map(|frame| match frame {
                Frame::Chunk { blob_id, offset, bytes } => Frame::Chunk {
                    blob_id,
                    offset: offset + 1,
                    bytes,
                },
                other => other,
            })
            .collect())
    }

    fn send_envelope(&mut self, bytes: &[u8]) -> gilune_sync::Result<Option<BlobNeed>> {
        self.inner.send_envelope(bytes)
    }

    fn send_chunk(&mut self, blob_id: &str, offset: u64, bytes: &[u8]) -> gilune_sync::Result<()> {
        self.inner.send_chunk(blob_id, offset, bytes)
    }

    fn close(&mut self) -> gilune_sync::Result<()> {
        self.inner.close()
    }
}

fn damage_first(frames: Vec<Frame>, damage: impl Fn(Vec<u8>) -> Vec<u8>) -> Vec<Frame> {
    let mut done = false;
    frames
        .into_iter()
        .map(|frame| match frame {
            Frame::Envelope(bytes) if !done => {
                done = true;
                Frame::Envelope(damage(bytes))
            }
            other => other,
        })
        .collect()
}
