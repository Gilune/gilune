//! The request/answer protocol, over any `Channel`.
//!
//! This is M5a's loopback conversation with the socket taken out of it.
//! A paired session and a loopback session run the same `serve` and the
//! same `ChannelSession`; what differs is only whether the bytes between
//! them were encrypted, which is precisely the property ADR 0019 § 5 asks
//! for — the whole S4 suite therefore exercises the code path that
//! carries a real pairing.

use crate::transport::{DeviceOffer, Frame, Session};
use crate::wire::{
    read_frame, string_at, write_frame, Channel, TAG_ACK, TAG_CHUNK, TAG_CLOSE, TAG_DEVICES,
    TAG_END, TAG_ENVELOPE, TAG_PUSH_CHUNK, TAG_PUSH_ENVELOPE, TAG_REFUSED, TAG_WANT_AFTER,
    TAG_WANT_BLOB, TAG_WANT_DEVICES,
};
use crate::{Result, SyncError};
use gilune_kernel::limits::Limits;
use gilune_kernel::operations::BlobNeed;
use gilune_kernel::store::Store;

/// The asking half. Owns a channel and turns the `Session` verbs into
/// frames on it.
pub struct ChannelSession {
    channel: Box<dyn Channel + Send>,
    limits: Limits,
}

impl ChannelSession {
    pub fn new(channel: Box<dyn Channel + Send>, limits: Limits) -> ChannelSession {
        ChannelSession { channel, limits }
    }

    /// Send a request and gather the frames it answers with, up to the
    /// end marker. A refusal from the peer ends the call rather than the
    /// process: the peer is allowed to say no.
    fn ask(&mut self, tag: u8, header: serde_json::Value) -> Result<Vec<Frame>> {
        write_frame(self.channel.as_mut(), tag, &header, &[])?;
        let mut frames = Vec::new();
        loop {
            let Some(incoming) = read_frame(self.channel.as_mut(), &self.limits)? else {
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
        write_frame(self.channel.as_mut(), tag, &header, payload)?;
        let Some(incoming) = read_frame(self.channel.as_mut(), &self.limits)? else {
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

impl Session for ChannelSession {
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
        let _ = write_frame(self.channel.as_mut(), TAG_CLOSE, &serde_json::json!({}), &[]);
        self.channel.close();
        Ok(())
    }
}

/// Why an answering loop stopped.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Served {
    /// The peer said goodbye, or hung up.
    Ended,
    /// This device withdrew permission part-way through — a revocation
    /// while a session was live (ADR 0019 § 9). The loop stops between
    /// requests, so an operation is either applied whole or not at all:
    /// nothing is half-done, because apply is one transaction and the
    /// check sits on the boundary between two of them.
    Withdrawn,
}

/// Answer one peer's requests until it leaves, or until `permitted` says
/// this peer may no longer be served.
///
/// `permitted` is consulted **after a request has been read and before
/// anything is done about it**, which is the tightest boundary available:
/// a revocation that lands while this loop is blocked waiting still stops
/// the very next request rather than the one after it. It is never
/// consulted *inside* a request, and it does not need to be — apply is
/// one transaction per operation, so the boundary between two requests is
/// also a boundary between two whole operations. That is the whole of
/// "revoking mid-session cannot half-apply anything".
pub fn serve(
    store: &Store,
    channel: &mut dyn Channel,
    limits: &Limits,
    permitted: &dyn Fn() -> bool,
) -> Result<Served> {
    loop {
        let Some(request) = read_frame(channel, limits)? else { return Ok(Served::Ended) };
        if !permitted() {
            channel.close();
            return Ok(Served::Withdrawn);
        }
        match request.tag {
            TAG_CLOSE => return Ok(Served::Ended),
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
                    channel,
                    TAG_DEVICES,
                    &serde_json::json!({ "devices": devices }),
                    &[],
                )?;
                write_frame(channel, TAG_END, &serde_json::json!({}), &[])?;
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
                    write_frame(channel, TAG_ENVELOPE, &serde_json::json!({}), &envelope)?;
                }
                write_frame(channel, TAG_END, &serde_json::json!({}), &[])?;
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
                        channel,
                        TAG_CHUNK,
                        &serde_json::json!({ "blob_id": blob_id, "offset": offset }),
                        &bytes,
                    )?,
                    // No bytes, or none this peer holds: an empty answer
                    // is the whole vocabulary for "ask me again later".
                    _ => {}
                }
                write_frame(channel, TAG_END, &serde_json::json!({}), &[])?;
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
                    write_frame(channel, TAG_ACK, &ack, &[])?
                }
                Err(e) => write_frame(
                    channel,
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
                    Ok(_) => write_frame(channel, TAG_ACK, &serde_json::json!({}), &[])?,
                    Err(e) => write_frame(
                        channel,
                        TAG_REFUSED,
                        &serde_json::json!({ "detail": e.to_string() }),
                        &[],
                    )?,
                }
            }
            other => {
                write_frame(
                    channel,
                    TAG_REFUSED,
                    &serde_json::json!({ "detail": format!("unknown request {other}") }),
                    &[],
                )?;
            }
        }
    }
}
