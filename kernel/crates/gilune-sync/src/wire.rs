//! Framing, over a plain socket and over Noise alike.
//!
//! M5a put the framing inside the loopback transport, because there was
//! one transport. M5b has two — a plain socket for pairing's cleartext
//! preamble and an encrypted session for everything else — and ADR 0019
//! § 5 is explicit that they are *one code path*: "one pattern, one code
//! path, one set of tests; the phase differs only in which `psk` is
//! supplied". So the framing moves behind a `Channel`, the protocol above
//! it does not know which kind it is holding, and the bytes a
//! `PlainChannel` puts on the wire are byte-for-byte what M5a's loopback
//! put there.
//!
//! Both implementations refuse an oversized declaration **before** any
//! buffer is sized from it. That is the transport's half of § 8's "a
//! declared length is never an allocation"; the kernel's half is in
//! `operations::decode`.

use crate::{Result, SyncError};
use gilune_kernel::limits::Limits;
use std::io::{Read, Write};
use std::net::TcpStream;

// Requests.
pub const TAG_WANT_DEVICES: u8 = 1;
pub const TAG_WANT_AFTER: u8 = 2;
pub const TAG_WANT_BLOB: u8 = 3;
pub const TAG_PUSH_ENVELOPE: u8 = 4;
pub const TAG_PUSH_CHUNK: u8 = 5;
pub const TAG_CLOSE: u8 = 6;

// Answers.
pub const TAG_DEVICES: u8 = 10;
pub const TAG_ENVELOPE: u8 = 11;
pub const TAG_CHUNK: u8 = 12;
pub const TAG_END: u8 = 13;
pub const TAG_ACK: u8 = 14;
pub const TAG_REFUSED: u8 = 15;

/// The largest frame this device will read. Bounded by the same numbers
/// the kernel bounds an envelope and a chunk by, so a peer cannot make
/// the transport allocate what the kernel would have refused anyway.
pub fn max_frame(limits: &Limits) -> usize {
    limits.max_envelope_bytes + limits.chunk_bytes + 1024
}

/// One logical message in each direction. Deliberately narrow: the
/// protocol above never learns whether its bytes were encrypted, which is
/// what lets one `serve` answer a loopback peer and a paired one.
pub trait Channel {
    /// Send one frame body.
    fn send(&mut self, body: &[u8]) -> Result<()>;
    /// Receive one frame body. `None` is the peer hanging up cleanly,
    /// which is an ending rather than an error.
    fn recv(&mut self) -> Result<Option<Vec<u8>>>;
    /// Stop. Idempotent, and never an error worth propagating: a session
    /// that has already failed still gets closed.
    fn close(&mut self);
}

// ------------------------------------------------------------ plain frames

/// Length-prefixed frames on a bare socket: `[u32 body_len][body]`.
/// Exactly M5a's bytes.
pub struct PlainChannel {
    stream: TcpStream,
    limits: Limits,
}

impl PlainChannel {
    pub fn new(stream: TcpStream, limits: Limits) -> PlainChannel {
        PlainChannel { stream, limits }
    }

    pub fn into_stream(self) -> TcpStream {
        self.stream
    }

    pub fn stream_mut(&mut self) -> &mut TcpStream {
        &mut self.stream
    }
}

impl Channel for PlainChannel {
    fn send(&mut self, body: &[u8]) -> Result<()> {
        self.stream.write_all(&(body.len() as u32).to_be_bytes())?;
        self.stream.write_all(body)?;
        self.stream.flush()?;
        Ok(())
    }

    fn recv(&mut self) -> Result<Option<Vec<u8>>> {
        let mut length = [0u8; 4];
        match self.stream.read_exact(&mut length) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(e) => return Err(SyncError::Io(e)),
        }
        let declared = u32::from_be_bytes(length) as usize;
        let limit = max_frame(&self.limits);
        if declared > limit {
            return Err(SyncError::OversizeFrame { declared, limit });
        }
        let mut body = vec![0u8; declared];
        self.stream.read_exact(&mut body)?;
        Ok(Some(body))
    }

    fn close(&mut self) {
        let _ = self.stream.shutdown(std::net::Shutdown::Both);
    }
}

// ------------------------------------------------------------ noise frames

/// A Noise transport message caps at 65535 bytes including its tag; a
/// frame does not, because a blob chunk plus its header is larger than
/// that on the default limits. So one logical frame becomes a run of
/// Noise messages, and the plaintext stream carries its own total length
/// so the far side knows when the run ends.
const NOISE_MESSAGE_MAX: usize = 65535;
const NOISE_TAG: usize = 16;
/// Plaintext per Noise message. Comfortably under the ciphertext cap
/// with the tag, and a round number so a 64 KiB chunk is two messages.
const SEGMENT: usize = 32 * 1024;

pub struct NoiseChannel {
    stream: TcpStream,
    noise: snow::TransportState,
    limits: Limits,
}

impl NoiseChannel {
    pub fn new(stream: TcpStream, noise: snow::TransportState, limits: Limits) -> NoiseChannel {
        NoiseChannel { stream, noise, limits }
    }

    /// The peer's static identity, as the completed handshake transmitted
    /// it. The pinned roster entry is checked against this and not
    /// against anything the peer merely claimed.
    pub fn remote_static(&self) -> Option<Vec<u8>> {
        self.noise.get_remote_static().map(|k| k.to_vec())
    }

    fn read_segment(&mut self) -> Result<Option<Vec<u8>>> {
        let mut length = [0u8; 2];
        match self.stream.read_exact(&mut length) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => return Ok(None),
            Err(e) => return Err(SyncError::Io(e)),
        }
        let declared = u16::from_be_bytes(length) as usize;
        if declared <= NOISE_TAG || declared > NOISE_MESSAGE_MAX {
            return Err(SyncError::Protocol {
                detail: format!("a noise segment of {declared} bytes is not a message"),
            });
        }
        let mut cipher = vec![0u8; declared];
        self.stream.read_exact(&mut cipher)?;
        let mut plain = vec![0u8; NOISE_MESSAGE_MAX];
        let read = self
            .noise
            .read_message(&cipher, &mut plain)
            .map_err(noise_fault)?;
        plain.truncate(read);
        Ok(Some(plain))
    }
}

impl Channel for NoiseChannel {
    fn send(&mut self, body: &[u8]) -> Result<()> {
        let limit = max_frame(&self.limits);
        if body.len() > limit {
            return Err(SyncError::OversizeFrame { declared: body.len(), limit });
        }
        let mut plain = Vec::with_capacity(4 + body.len());
        plain.extend_from_slice(&(body.len() as u32).to_be_bytes());
        plain.extend_from_slice(body);
        let mut cipher = vec![0u8; SEGMENT + NOISE_TAG + 64];
        for piece in plain.chunks(SEGMENT) {
            let written = self
                .noise
                .write_message(piece, &mut cipher)
                .map_err(noise_fault)?;
            self.stream.write_all(&(written as u16).to_be_bytes())?;
            self.stream.write_all(&cipher[..written])?;
        }
        self.stream.flush()?;
        Ok(())
    }

    fn recv(&mut self) -> Result<Option<Vec<u8>>> {
        let mut plain: Vec<u8> = Vec::new();
        let mut total: Option<usize> = None;
        loop {
            let Some(segment) = self.read_segment()? else {
                return if plain.is_empty() {
                    Ok(None)
                } else {
                    Err(SyncError::Protocol {
                        detail: "the peer closed part-way through a frame".to_string(),
                    })
                };
            };
            plain.extend_from_slice(&segment);
            if total.is_none() && plain.len() >= 4 {
                let declared =
                    u32::from_be_bytes([plain[0], plain[1], plain[2], plain[3]]) as usize;
                let limit = max_frame(&self.limits);
                // The declaration is checked here, before the accumulator
                // is allowed to grow to meet it.
                if declared > limit {
                    return Err(SyncError::OversizeFrame { declared, limit });
                }
                total = Some(declared);
            }
            if let Some(total) = total {
                if plain.len() >= 4 + total {
                    return Ok(Some(plain[4..4 + total].to_vec()));
                }
            }
        }
    }

    fn close(&mut self) {
        let _ = self.stream.shutdown(std::net::Shutdown::Both);
    }
}

/// A `snow` failure on an established channel is the peer's fault, not
/// this machine's: a forged or replayed message fails its tag here.
pub fn noise_fault(e: snow::Error) -> SyncError {
    SyncError::Protocol { detail: format!("noise: {e}") }
}

// ------------------------------------------------------------------ frames

pub struct Incoming {
    pub tag: u8,
    pub header: serde_json::Value,
    pub payload: Vec<u8>,
}

/// `[tag][u32 header_len][header json][payload]` inside whatever the
/// channel carries.
pub fn write_frame(
    channel: &mut dyn Channel,
    tag: u8,
    header: &serde_json::Value,
    payload: &[u8],
) -> Result<()> {
    let header = header.to_string();
    let header = header.as_bytes();
    let mut body = Vec::with_capacity(1 + 4 + header.len() + payload.len());
    body.push(tag);
    body.extend_from_slice(&(header.len() as u32).to_be_bytes());
    body.extend_from_slice(header);
    body.extend_from_slice(payload);
    channel.send(&body)
}

pub fn read_frame(channel: &mut dyn Channel, limits: &Limits) -> Result<Option<Incoming>> {
    let Some(body) = channel.recv()? else { return Ok(None) };
    if body.len() < 5 {
        return Err(SyncError::OversizeFrame {
            declared: body.len(),
            limit: max_frame(limits),
        });
    }
    let tag = body[0];
    let header_len = u32::from_be_bytes([body[1], body[2], body[3], body[4]]) as usize;
    if header_len + 5 > body.len() {
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

pub fn string_at(value: &serde_json::Value, field: &str) -> String {
    value
        .get(field)
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    /// A connected pair of loopback sockets, so a frame can be written by
    /// hand and read the way a peer's would be.
    pub fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let address = listener.local_addr().expect("addr");
        let client = TcpStream::connect(address).expect("connect");
        let (server, _) = listener.accept().expect("accept");
        (client, server)
    }

    fn plain() -> (PlainChannel, PlainChannel) {
        let (a, b) = pair();
        (
            PlainChannel::new(a, Limits::DEFAULT),
            PlainChannel::new(b, Limits::DEFAULT),
        )
    }

    #[test]
    fn a_frame_declaring_more_than_the_cap_is_refused_before_a_buffer_is_sized_from_it() {
        let (mut client, mut server) = plain();
        // Four bytes claiming four gigabytes. Reading this as a length
        // and allocating for it is the one-packet exhaustion ADR 0019 § 8
        // retires; the cap has to be applied to the declaration itself.
        client.stream_mut().write_all(&u32::MAX.to_be_bytes()).expect("write");
        client.stream_mut().flush().expect("flush");
        assert!(matches!(
            read_frame(&mut server, &Limits::DEFAULT),
            Err(SyncError::OversizeFrame { declared, .. }) if declared == u32::MAX as usize
        ));
    }

    #[test]
    fn a_frame_too_short_to_hold_a_header_is_refused() {
        let (mut client, mut server) = plain();
        client.send(&[TAG_END, 0]).expect("write");
        assert!(matches!(
            read_frame(&mut server, &Limits::DEFAULT),
            Err(SyncError::OversizeFrame { .. })
        ));
    }

    #[test]
    fn a_header_claiming_to_run_past_its_own_frame_is_refused() {
        let (mut client, mut server) = plain();
        let mut body = vec![TAG_END];
        body.extend_from_slice(&999u32.to_be_bytes());
        client.send(&body).expect("write");
        assert!(matches!(
            read_frame(&mut server, &Limits::DEFAULT),
            Err(SyncError::Protocol { .. })
        ));
    }

    #[test]
    fn a_peer_that_hangs_up_is_an_ending_rather_than_an_error() {
        let (client, mut server) = plain();
        drop(client);
        assert!(read_frame(&mut server, &Limits::DEFAULT).expect("read").is_none());
    }

    #[test]
    fn a_frame_round_trips_its_tag_header_and_payload() {
        let (mut client, mut server) = plain();
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
