//! A `Transport` over a TCP socket on the loopback interface, and the
//! server that answers one from a vault.
//!
//! This is M5a's whole point (ADR 0019 § 6): the S4 scenarios were proven
//! over a shared folder, where every message was a file and nothing could
//! be truncated, reordered or oversized by a sender. A socket can do all
//! three. Running the same scenarios over one, against the production
//! kernel, is what separates semantics that work from semantics that
//! worked on a filesystem.
//!
//! It is deliberately not the LAN transport. There is no discovery, no
//! handshake and no encryption: a peer here is trusted because it is this
//! machine. M5b replaces the socket's establishment half and nothing
//! above this file changes.

use crate::transport::{DeviceOffer, Frame, Listener, Session, Transport};
use crate::{Result, SyncError};
use gilune_kernel::limits::Limits;
use gilune_kernel::operations::BlobNeed;
use gilune_kernel::store::Store;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

const TAG_WANT_DEVICES: u8 = 1;
const TAG_WANT_AFTER: u8 = 2;
const TAG_WANT_BLOB: u8 = 3;
const TAG_PUSH_ENVELOPE: u8 = 4;
const TAG_PUSH_CHUNK: u8 = 5;
const TAG_CLOSE: u8 = 6;

const TAG_DEVICES: u8 = 10;
const TAG_ENVELOPE: u8 = 11;
const TAG_CHUNK: u8 = 12;
const TAG_END: u8 = 13;
const TAG_ACK: u8 = 14;
const TAG_REFUSED: u8 = 15;

/// The largest frame this device will read. Bounded by the same numbers
/// the kernel bounds an envelope and a chunk by, so a peer cannot make
/// the transport allocate what the kernel would have refused anyway.
fn max_frame(limits: &Limits) -> usize {
    limits.max_envelope_bytes + limits.chunk_bytes + 1024
}

// ------------------------------------------------------------------ framing

fn write_frame(
    stream: &mut TcpStream,
    tag: u8,
    header: &serde_json::Value,
    payload: &[u8],
) -> Result<()> {
    let header = header.to_string();
    let header = header.as_bytes();
    let body_len = 1 + 4 + header.len() + payload.len();
    stream.write_all(&(body_len as u32).to_be_bytes())?;
    stream.write_all(&[tag])?;
    stream.write_all(&(header.len() as u32).to_be_bytes())?;
    stream.write_all(header)?;
    stream.write_all(payload)?;
    stream.flush()?;
    Ok(())
}

struct Incoming {
    tag: u8,
    header: serde_json::Value,
    payload: Vec<u8>,
}

/// Read one frame, refusing an oversized declaration **before** any
/// buffer is sized from it — the transport's own half of "a declared
/// length is never an allocation".
fn read_frame(stream: &mut TcpStream, limits: &Limits) -> Result<Option<Incoming>> {
    let mut length = [0u8; 4];
    match stream.read_exact(&mut length) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(SyncError::Io(e)),
    }
    let body_len = u32::from_be_bytes(length) as usize;
    let limit = max_frame(limits);
    if body_len > limit || body_len < 5 {
        return Err(SyncError::OversizeFrame { declared: body_len, limit });
    }
    let mut body = vec![0u8; body_len];
    stream.read_exact(&mut body)?;
    let tag = body[0];
    let header_len = u32::from_be_bytes([body[1], body[2], body[3], body[4]]) as usize;
    if header_len + 5 > body_len {
        return Err(SyncError::Protocol {
            detail: "frame header runs past the frame".to_string(),
        });
    }
    let header: serde_json::Value = serde_json::from_slice(&body[5..5 + header_len])
        .map_err(|e| SyncError::Protocol { detail: e.to_string() })?;
    Ok(Some(Incoming {
        tag,
        header,
        payload: body[5 + header_len..].to_vec(),
    }))
}

// ------------------------------------------------------------- the client

pub struct LoopbackTransport {
    limits: Limits,
}

impl LoopbackTransport {
    pub fn new(limits: Limits) -> LoopbackTransport {
        LoopbackTransport { limits }
    }
}

impl Default for LoopbackTransport {
    fn default() -> Self {
        LoopbackTransport::new(Limits::DEFAULT)
    }
}

impl Transport for LoopbackTransport {
    fn connect(&self, peer: &str) -> Result<Box<dyn Session>> {
        let stream = TcpStream::connect(peer)?;
        stream.set_nodelay(true)?;
        Ok(Box::new(LoopbackSession {
            stream,
            limits: self.limits,
        }))
    }

    fn listen(&self, bind: &str) -> Result<Box<dyn Listener>> {
        let listener = TcpListener::bind(bind)?;
        Ok(Box::new(LoopbackListener {
            listener,
            limits: self.limits,
        }))
    }
}

struct LoopbackListener {
    listener: TcpListener,
    limits: Limits,
}

impl Listener for LoopbackListener {
    fn address(&self) -> String {
        self.listener
            .local_addr()
            .map(|a| a.to_string())
            .unwrap_or_default()
    }

    fn accept(&self) -> Result<Box<dyn Session>> {
        let (stream, _) = self.listener.accept()?;
        stream.set_nodelay(true)?;
        Ok(Box::new(LoopbackSession {
            stream,
            limits: self.limits,
        }))
    }
}

pub struct LoopbackSession {
    stream: TcpStream,
    limits: Limits,
}

impl LoopbackSession {
    /// Send a request and gather the frames it answers with, up to the
    /// end marker. A refusal from the peer ends the call rather than the
    /// process: the peer is allowed to say no.
    fn ask(&mut self, tag: u8, header: serde_json::Value) -> Result<Vec<Frame>> {
        write_frame(&mut self.stream, tag, &header, &[])?;
        let mut frames = Vec::new();
        loop {
            let Some(incoming) = read_frame(&mut self.stream, &self.limits)? else {
                return Err(SyncError::Protocol {
                    detail: "the peer closed mid-answer".to_string(),
                });
            };
            match incoming.tag {
                TAG_END => return Ok(frames),
                TAG_ENVELOPE => frames.push(Frame::Envelope(incoming.payload)),
                TAG_CHUNK => frames.push(Frame::Chunk {
                    blob_id: string_at(&incoming.header, "blob_id"),
                    offset: incoming.header.get("offset").and_then(|v| v.as_u64()).unwrap_or(0),
                    bytes: incoming.payload,
                }),
                TAG_DEVICES => frames.push(Frame::Envelope(
                    incoming.header.to_string().into_bytes(),
                )),
                TAG_REFUSED => {
                    return Err(SyncError::Protocol {
                        detail: string_at(&incoming.header, "detail"),
                    })
                }
                other => {
                    return Err(SyncError::Protocol {
                        detail: format!("unexpected frame {other}"),
                    })
                }
            }
        }
    }

    fn tell(
        &mut self,
        tag: u8,
        header: serde_json::Value,
        payload: &[u8],
    ) -> Result<serde_json::Value> {
        write_frame(&mut self.stream, tag, &header, payload)?;
        let Some(incoming) = read_frame(&mut self.stream, &self.limits)? else {
            return Err(SyncError::Protocol {
                detail: "the peer closed without acknowledging".to_string(),
            });
        };
        match incoming.tag {
            TAG_ACK => Ok(incoming.header),
            TAG_REFUSED => Err(SyncError::Protocol {
                detail: string_at(&incoming.header, "detail"),
            }),
            other => Err(SyncError::Protocol {
                detail: format!("unexpected frame {other}"),
            }),
        }
    }
}

impl Session for LoopbackSession {
    fn devices(&mut self) -> Result<Vec<DeviceOffer>> {
        let frames = self.ask(TAG_WANT_DEVICES, serde_json::json!({}))?;
        let Some(Frame::Envelope(bytes)) = frames.into_iter().next() else {
            return Ok(Vec::new());
        };
        let answer: serde_json::Value = serde_json::from_slice(&bytes)
            .map_err(|e| SyncError::Protocol { detail: e.to_string() })?;
        Ok(answer
            .get("devices")
            .and_then(|v| v.as_array())
            .map(|rows| {
                rows.iter()
                    .map(|row| DeviceOffer {
                        device_id: string_at(row, "device_id"),
                        highest_seq: row.get("highest_seq").and_then(|v| v.as_i64()).unwrap_or(0),
                    })
                    .collect()
            })
            .unwrap_or_default())
    }

    fn have_after(&mut self, device: &str, seq: i64, max_ops: usize) -> Result<Vec<Frame>> {
        self.ask(
            TAG_WANT_AFTER,
            serde_json::json!({ "device": device, "seq": seq, "max_ops": max_ops }),
        )
    }

    fn have_blob(&mut self, blob_id: &str, offset: u64, max_bytes: usize) -> Result<Vec<Frame>> {
        self.ask(
            TAG_WANT_BLOB,
            serde_json::json!({ "blob_id": blob_id, "offset": offset, "max_bytes": max_bytes }),
        )
    }

    fn send_envelope(&mut self, bytes: &[u8]) -> Result<Option<BlobNeed>> {
        let ack = self.tell(TAG_PUSH_ENVELOPE, serde_json::json!({}), bytes)?;
        let Some(need) = ack.get("needs_blob").filter(|v| v.is_object()) else {
            return Ok(None);
        };
        Ok(Some(BlobNeed {
            blob_id: string_at(need, "blob_id"),
            sha256: string_at(need, "sha256"),
            bytes: need.get("bytes").and_then(|v| v.as_u64()).unwrap_or(0),
            received: need.get("received").and_then(|v| v.as_u64()).unwrap_or(0),
        }))
    }

    fn send_chunk(&mut self, blob_id: &str, offset: u64, bytes: &[u8]) -> Result<()> {
        self.tell(
            TAG_PUSH_CHUNK,
            serde_json::json!({ "blob_id": blob_id, "offset": offset }),
            bytes,
        )?;
        Ok(())
    }

    fn close(&mut self) -> Result<()> {
        let _ = write_frame(&mut self.stream, TAG_CLOSE, &serde_json::json!({}), &[]);
        let _ = self.stream.shutdown(std::net::Shutdown::Both);
        Ok(())
    }
}

fn string_at(value: &serde_json::Value, field: &str) -> String {
    value
        .get(field)
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string()
}

// ------------------------------------------------------------- the server

/// A vault answering loopback sessions. It opens its own kernel handle,
/// exactly as a second process would: multi-instance on one vault is
/// supported use (owner decision 2026-08-31), and a peer that is a
/// separate handle is a truer rehearsal of M5b than one that shares a
/// connection.
pub struct LoopbackServer {
    address: String,
    stopping: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl LoopbackServer {
    /// Start serving `vault` on an ephemeral loopback port.
    pub fn start(vault: impl Into<PathBuf>, limits: Limits) -> Result<LoopbackServer> {
        let vault = vault.into();
        let listener = TcpListener::bind("127.0.0.1:0")?;
        let address = listener.local_addr()?.to_string();
        let stopping = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&stopping);
        let thread = std::thread::spawn(move || {
            let Ok(store) = Store::open(&vault) else { return };
            for stream in listener.incoming() {
                if flag.load(Ordering::SeqCst) {
                    return;
                }
                let Ok(mut stream) = stream else { return };
                let _ = stream.set_nodelay(true);
                // One peer at a time: M5a proves semantics, and a
                // concurrent server is a bound M5c must argue for on its
                // own evidence rather than inherit here.
                let _ = serve(&store, &mut stream, &limits);
            }
        });
        Ok(LoopbackServer {
            address,
            stopping,
            thread: Some(thread),
        })
    }

    pub fn address(&self) -> &str {
        &self.address
    }
}

impl Drop for LoopbackServer {
    fn drop(&mut self) {
        self.stopping.store(true, Ordering::SeqCst);
        // Wake the blocked accept so the thread can see the flag.
        let _ = TcpStream::connect(&self.address);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn serve(store: &Store, stream: &mut TcpStream, limits: &Limits) -> Result<()> {
    loop {
        let Some(request) = read_frame(stream, limits)? else { return Ok(()) };
        match request.tag {
            TAG_CLOSE => return Ok(()),
            TAG_WANT_DEVICES => {
                let devices: Vec<serde_json::Value> = store
                    .operation_devices()?
                    .into_iter()
                    .map(|(device_id, highest_seq)| {
                        serde_json::json!({
                            "device_id": device_id,
                            "highest_seq": highest_seq,
                        })
                    })
                    .collect();
                write_frame(
                    stream,
                    TAG_DEVICES,
                    &serde_json::json!({ "devices": devices }),
                    &[],
                )?;
                write_frame(stream, TAG_END, &serde_json::json!({}), &[])?;
            }
            TAG_WANT_AFTER => {
                let device = string_at(&request.header, "device");
                let seq = request.header.get("seq").and_then(|v| v.as_i64()).unwrap_or(0);
                let max_ops = request
                    .header
                    .get("max_ops")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0)
                    .min(limits.max_ops_per_session as u64) as usize;
                for envelope in store.operations_after(&device, seq, max_ops)? {
                    write_frame(stream, TAG_ENVELOPE, &serde_json::json!({}), &envelope)?;
                }
                write_frame(stream, TAG_END, &serde_json::json!({}), &[])?;
            }
            TAG_WANT_BLOB => {
                let blob_id = string_at(&request.header, "blob_id");
                let offset = request.header.get("offset").and_then(|v| v.as_u64()).unwrap_or(0);
                let max_bytes = request
                    .header
                    .get("max_bytes")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0)
                    .min(limits.chunk_bytes as u64) as usize;
                match store.blob_chunk(&blob_id, offset, max_bytes) {
                    Ok(bytes) if !bytes.is_empty() => write_frame(
                        stream,
                        TAG_CHUNK,
                        &serde_json::json!({ "blob_id": blob_id, "offset": offset }),
                        &bytes,
                    )?,
                    // No bytes, or none this peer holds: an empty answer
                    // is the whole vocabulary for "ask me again later".
                    _ => {}
                }
                write_frame(stream, TAG_END, &serde_json::json!({}), &[])?;
            }
            TAG_PUSH_ENVELOPE => match store.apply_operation("push", &request.payload, *limits) {
                Ok(applied) => {
                    let mut ack = serde_json::json!({});
                    if let Some(need) = applied.blob_wanted {
                        ack["needs_blob"] = serde_json::json!({
                            "blob_id": need.blob_id,
                            "sha256": need.sha256,
                            "bytes": need.bytes,
                            "received": need.received,
                        });
                    }
                    write_frame(stream, TAG_ACK, &ack, &[])?
                }
                Err(e) => write_frame(
                    stream,
                    TAG_REFUSED,
                    &serde_json::json!({ "detail": e.to_string() }),
                    &[],
                )?,
            },
            TAG_PUSH_CHUNK => {
                let blob_id = string_at(&request.header, "blob_id");
                let offset = request.header.get("offset").and_then(|v| v.as_u64()).unwrap_or(0);
                let written = store
                    .write_blob_chunk(&blob_id, offset, &request.payload, *limits)
                    .and_then(|_| store.finish_blob(&blob_id));
                match written {
                    Ok(_) => write_frame(stream, TAG_ACK, &serde_json::json!({}), &[])?,
                    Err(e) => write_frame(
                        stream,
                        TAG_REFUSED,
                        &serde_json::json!({ "detail": e.to_string() }),
                        &[],
                    )?,
                }
            }
            other => {
                write_frame(
                    stream,
                    TAG_REFUSED,
                    &serde_json::json!({ "detail": format!("unknown request {other}") }),
                    &[],
                )?;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A connected pair of loopback sockets, so a frame can be written by
    /// hand and read the way a peer's would be.
    fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let address = listener.local_addr().expect("addr");
        let client = TcpStream::connect(address).expect("connect");
        let (server, _) = listener.accept().expect("accept");
        (client, server)
    }

    #[test]
    fn a_frame_declaring_more_than_the_cap_is_refused_before_a_buffer_is_sized_from_it() {
        let (mut client, mut server) = pair();
        // Four bytes claiming four gigabytes. Reading this as a length
        // and allocating for it is the one-packet exhaustion ADR 0019 § 8
        // retires; the cap has to be applied to the declaration itself.
        client.write_all(&u32::MAX.to_be_bytes()).expect("write");
        client.flush().expect("flush");
        let refused = read_frame(&mut server, &Limits::DEFAULT);
        assert!(matches!(
            refused,
            Err(SyncError::OversizeFrame { declared, .. }) if declared == u32::MAX as usize
        ));
    }

    #[test]
    fn a_frame_too_short_to_hold_a_header_is_refused() {
        let (mut client, mut server) = pair();
        client.write_all(&2u32.to_be_bytes()).expect("write");
        client.write_all(&[TAG_END, 0]).expect("write");
        client.flush().expect("flush");
        assert!(matches!(
            read_frame(&mut server, &Limits::DEFAULT),
            Err(SyncError::OversizeFrame { .. })
        ));
    }

    #[test]
    fn a_header_claiming_to_run_past_its_own_frame_is_refused() {
        let (mut client, mut server) = pair();
        let body_len = 5u32;
        client.write_all(&body_len.to_be_bytes()).expect("write");
        client.write_all(&[TAG_END]).expect("write");
        client.write_all(&999u32.to_be_bytes()).expect("write");
        client.flush().expect("flush");
        assert!(matches!(
            read_frame(&mut server, &Limits::DEFAULT),
            Err(SyncError::Protocol { .. })
        ));
    }

    #[test]
    fn a_peer_that_hangs_up_is_an_ending_rather_than_an_error() {
        let (client, mut server) = pair();
        drop(client);
        assert!(read_frame(&mut server, &Limits::DEFAULT).expect("read").is_none());
    }

    #[test]
    fn a_frame_round_trips_its_tag_header_and_payload() {
        let (mut client, mut server) = pair();
        write_frame(
            &mut client,
            TAG_ENVELOPE,
            &serde_json::json!({ "blob_id": "b", "offset": 42 }),
            b"the bytes themselves",
        )
        .expect("write");
        let frame = read_frame(&mut server, &Limits::DEFAULT).expect("read").expect("a frame");
        assert_eq!(frame.tag, TAG_ENVELOPE);
        assert_eq!(string_at(&frame.header, "blob_id"), "b");
        assert_eq!(frame.header["offset"], 42);
        assert_eq!(frame.payload, b"the bytes themselves");
    }
}
