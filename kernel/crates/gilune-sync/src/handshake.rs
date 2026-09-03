//! The handshake: SPAKE2 when pairing, Noise `XXpsk0` always
//! (ADR 0019 §§ 3 and 5).
//!
//! Two phases, one pattern. Pairing runs SPAKE2 over the short code or
//! the QR's longer secret to reach a strong key `K`, then runs
//! `Noise_XXpsk0_25519_ChaChaPoly_BLAKE2s` with `psk = K`. Every session
//! afterwards runs the *same* Noise pattern with the pair secret that
//! pairing derived. "One pattern, one code path, one set of tests; the
//! phase differs only in which `psk` is supplied."
//!
//! Three properties fall out of that shape rather than being checked
//! afterwards, and they are the ones gate B asks about:
//!
//! - **Default-deny is a property of the handshake.** `psk0` mixes the
//!   pre-shared key in before message one, so message one carries an
//!   authentication tag over a key an unpaired device does not have. A
//!   peer with no pair secret cannot get its first message *read*.
//! - **An unpaired scanner learns nothing.** The responder's static key
//!   travels in message two, encrypted; a peer that cannot produce a
//!   readable message one is answered with nothing at all, so it never
//!   sees the identity it came to fish for.
//! - **A wrong code leaks nothing to grind on.** SPAKE2's failure is the
//!   Noise handshake failing; there is no oracle that says "close".
//!
//! The listener never asks a caller to name itself in the clear. It tries
//! each pair secret it holds against the buffered first message, so an
//! observer on the wire cannot tell which two devices are talking.

use crate::identity::Identity;
use crate::pairing::Credential;
use crate::wire::{noise_fault, Channel, NoiseChannel, PlainChannel};
use crate::{Result, SyncError, NOISE_PARAMS, PROTOCOL};
use gilune_kernel::limits::Limits;
use sha2::{Digest, Sha256};
use spake2::{Ed25519Group, Password, Spake2};
use std::net::TcpStream;
use std::time::Duration;

/// SPAKE2's role-distinguishing identity strings (§ 3): "so a code cannot
/// be reflected back at its sender". The library's own A/B sides carry
/// half of that — a reflected message fails the side byte — and these
/// carry the other half, binding each side's role into the transcript.
const ID_LISTENER: &[u8] = b"gilune:pair:v1:listener";
const ID_JOINER: &[u8] = b"gilune:pair:v1:joiner";

/// How long either side will wait during establishment. A handshake that
/// stalls must not wedge the listener thread.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);
/// And once established. Long enough for a chunk of a large blob on a
/// slow link, short enough that a dead peer is eventually noticed.
const SESSION_TIMEOUT: Duration = Duration::from_secs(60);

const NOISE_BUFFER: usize = 65535;

/// What each device tells the other about itself, inside the encrypted
/// handshake. Never in the clear: § 1 is explicit that "the human device
/// name is exchanged inside the encrypted handshake".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Greeting {
    pub device_id: String,
    pub name: String,
    /// Where this device is listening, if it is. § 1 records a peer's
    /// address "at pairing and after every successful session", and this
    /// is how the *listening* side learns the joiner's — a dialled
    /// connection's source port is not a listening port. Empty when this
    /// device is not accepting connections.
    ///
    /// Only the port is ever believed: the host is taken from the socket
    /// the peer actually arrived on, so a peer cannot make this device
    /// record an address it chose.
    pub address: String,
}

impl Greeting {
    pub fn new(device_id: &str, name: &str, address: &str) -> Greeting {
        Greeting {
            device_id: device_id.to_string(),
            name: name.to_string(),
            address: address.to_string(),
        }
    }

    /// The port this peer says it listens on, if it said anything usable.
    pub fn advertised_port(&self) -> Option<u16> {
        self.address.rsplit_once(':').and_then(|(_, p)| p.parse().ok())
    }

    fn encode(&self) -> Vec<u8> {
        serde_json::json!({
            "device_id": self.device_id,
            "name": self.name,
            "address": self.address,
        })
        .to_string()
        .into_bytes()
    }

    /// A greeting is a claim made by a peer, so it is bounded on the way
    /// in like any other untrusted field.
    fn decode(bytes: &[u8]) -> Result<Greeting> {
        let value: serde_json::Value = serde_json::from_slice(bytes)
            .map_err(|e| SyncError::Protocol { detail: format!("greeting: {e}") })?;
        let field = |name: &str, max: usize| -> Result<String> {
            let text = value
                .get(name)
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_string();
            if text.chars().count() > max || text.chars().any(|c| c.is_control()) {
                return Err(SyncError::Protocol {
                    detail: format!("greeting field {name} is not usable"),
                });
            }
            Ok(text)
        };
        Ok(Greeting {
            device_id: field("device_id", 128)?,
            name: field("name", 64)?,
            address: field("address", 128)?,
        })
    }
}

/// A pairing is not finished until the listening side has written it down.
///
/// Without this the joiner would return the moment its own handshake
/// completed, believing itself paired while the far end was still
/// committing — or had failed to. Gate B asks that both paths "produce
/// the same roster state", and one side storing without the other is
/// exactly the state that would not be.
///
/// The remaining asymmetry is stated rather than hidden: if this
/// confirmation is lost after the listener has stored, the listener holds
/// a pairing the joiner does not. That is the safe direction — the joiner
/// simply pairs again, and the second pairing replaces the first row
/// whole.
pub fn confirm_paired(channel: &mut NoiseChannel) -> Result<()> {
    channel.send(serde_json::json!({ "paired": true }).to_string().as_bytes())
}

pub fn await_paired(channel: &mut NoiseChannel) -> Result<()> {
    let Some(bytes) = channel.recv()? else {
        return Err(SyncError::Refused {
            detail: "that device closed before the pairing was recorded".to_string(),
        });
    };
    let answer: serde_json::Value = serde_json::from_slice(&bytes)
        .map_err(|e| SyncError::Protocol { detail: format!("confirmation: {e}") })?;
    if answer.get("paired").and_then(|v| v.as_bool()).unwrap_or(false) {
        return Ok(());
    }
    Err(SyncError::Refused {
        detail: answer
            .get("detail")
            .and_then(|v| v.as_str())
            .unwrap_or("that device did not record the pairing")
            .to_string(),
    })
}

/// What a caller wants, declared before Noise because the responder must
/// choose a `psk` before it can read message one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Intent {
    Pair(Credential),
    Session,
}

/// A completed handshake.
pub struct Established {
    pub channel: NoiseChannel,
    /// The peer's static identity **as the handshake transmitted it** —
    /// not as the peer claimed it. This is what a roster entry is pinned
    /// against.
    pub remote_static: Vec<u8>,
    pub peer: Greeting,
    /// Derived from the handshake hash, so both sides compute it without
    /// either sending it. For a pairing this is the stored pair secret.
    pub derived_secret: [u8; 32],
}

impl Established {
    pub fn remote_static_hex(&self) -> String {
        hex::encode(&self.remote_static)
    }
}

fn prologue(intent: Intent) -> Vec<u8> {
    match intent {
        Intent::Pair(credential) => {
            // The credential kind is bound into the prologue so a
            // downgrade from the QR's long secret to a six-digit code
            // cannot be forced by rewriting one word of the preamble.
            format!("{PROTOCOL}:pair:{}", credential.as_str()).into_bytes()
        }
        Intent::Session => format!("{PROTOCOL}:session").into_bytes(),
    }
}

fn derive(label: &[u8], material: &[u8]) -> [u8; 32] {
    let digest = Sha256::new().chain_update(label).chain_update(material).finalize();
    let mut out = [0u8; 32];
    out.copy_from_slice(&digest);
    out
}

// ------------------------------------------------------------ the preamble

pub fn send_hello(channel: &mut PlainChannel, intent: Intent) -> Result<()> {
    let mut hello = serde_json::json!({ "proto": PROTOCOL });
    match intent {
        Intent::Pair(credential) => {
            hello["intent"] = serde_json::Value::String("pair".to_string());
            hello["credential"] = serde_json::Value::String(credential.as_str().to_string());
        }
        Intent::Session => hello["intent"] = serde_json::Value::String("session".to_string()),
    }
    channel.send(hello.to_string().as_bytes())
}

/// Read a caller's opening line. Answering is a separate step, because
/// what the answer should be depends on state this module does not own —
/// whether a pairing window is open, for instance.
pub fn read_hello(channel: &mut PlainChannel) -> Result<Intent> {
    let Some(bytes) = channel.recv()? else {
        return Err(SyncError::Protocol { detail: "the caller said nothing".to_string() });
    };
    let hello: serde_json::Value = serde_json::from_slice(&bytes)
        .map_err(|e| SyncError::Protocol { detail: format!("hello: {e}") })?;
    let proto = hello.get("proto").and_then(|v| v.as_str()).unwrap_or_default();
    if proto != PROTOCOL {
        return Err(SyncError::Protocol {
            detail: format!("this device speaks {PROTOCOL}, the caller speaks {proto}"),
        });
    }
    match hello.get("intent").and_then(|v| v.as_str()).unwrap_or_default() {
        "session" => Ok(Intent::Session),
        "pair" => {
            let credential = hello
                .get("credential")
                .and_then(|v| v.as_str())
                .and_then(Credential::parse)
                .ok_or(SyncError::Protocol {
                    detail: "the caller named no pairing credential".to_string(),
                })?;
            Ok(Intent::Pair(credential))
        }
        other => Err(SyncError::Protocol {
            detail: format!("unknown intent {other}"),
        }),
    }
}

pub fn answer_ready(channel: &mut PlainChannel) -> Result<()> {
    channel.send(serde_json::json!({ "ok": true }).to_string().as_bytes())
}

/// Refuse a caller. One shape for every reason a caller might be turned
/// away, so what reaches the far end is "no" and never "nearly".
pub fn answer_refused(channel: &mut PlainChannel, detail: &str) -> Result<()> {
    channel.send(
        serde_json::json!({ "ok": false, "detail": detail })
            .to_string()
            .as_bytes(),
    )
}

fn read_answer(channel: &mut PlainChannel) -> Result<()> {
    let Some(bytes) = channel.recv()? else {
        return Err(SyncError::Refused {
            detail: "that device did not answer".to_string(),
        });
    };
    let answer: serde_json::Value = serde_json::from_slice(&bytes)
        .map_err(|e| SyncError::Protocol { detail: format!("answer: {e}") })?;
    if answer.get("ok").and_then(|v| v.as_bool()).unwrap_or(false) {
        return Ok(());
    }
    Err(SyncError::Refused {
        detail: answer
            .get("detail")
            .and_then(|v| v.as_str())
            .unwrap_or("that device refused the connection")
            .to_string(),
    })
}

// --------------------------------------------------------------- SPAKE2

/// Run the PAKE and return the `psk` for the Noise handshake.
///
/// The output is hashed with a label rather than used raw: it pins the
/// length at the 32 bytes `psk0` requires whatever the group's transcript
/// hash produces, and it separates this use of `K` from any other.
fn pake(
    channel: &mut PlainChannel,
    secret: &[u8],
    listener: bool,
) -> Result<[u8; 32]> {
    let password = Password::new(secret);
    let id_listener = spake2::Identity::new(ID_LISTENER);
    let id_joiner = spake2::Identity::new(ID_JOINER);
    let (state, mine) = if listener {
        Spake2::<Ed25519Group>::start_a(&password, &id_listener, &id_joiner)
    } else {
        Spake2::<Ed25519Group>::start_b(&password, &id_listener, &id_joiner)
    };
    // The joiner dials, so it speaks first; the listener answers. Either
    // way both messages are on the wire before either side finishes.
    if listener {
        let Some(theirs) = channel.recv()? else {
            return Err(SyncError::Refused { detail: "the caller left".to_string() });
        };
        channel.send(&mine)?;
        finish_pake(state, &theirs)
    } else {
        channel.send(&mine)?;
        let Some(theirs) = channel.recv()? else {
            return Err(SyncError::Refused {
                detail: "that device left before pairing finished".to_string(),
            });
        };
        finish_pake(state, &theirs)
    }
}

fn finish_pake(state: Spake2<Ed25519Group>, theirs: &[u8]) -> Result<[u8; 32]> {
    let key = state.finish(theirs).map_err(|_| SyncError::Refused {
        detail: "that pairing code did not match".to_string(),
    })?;
    Ok(derive(b"gilune:pake:psk:v1", &key))
}

// ---------------------------------------------------------------- Noise

fn builder<'a>(
    identity: &'a Identity,
    psk: &'a [u8; 32],
    prologue: &'a [u8],
) -> Result<snow::Builder<'a>> {
    let params: snow::params::NoiseParams = NOISE_PARAMS
        .parse()
        .map_err(|e| SyncError::Identity { detail: format!("noise parameters: {e}") })?;
    snow::Builder::new(params)
        .local_private_key(identity.private())
        .and_then(|b| b.psk(0, psk))
        .and_then(|b| b.prologue(prologue))
        .map_err(noise_fault)
}

/// The dialling half of the Noise handshake. `me` travels in message
/// three, by which point both static keys have been exchanged.
fn noise_initiator(
    channel: &mut PlainChannel,
    identity: &Identity,
    psk: &[u8; 32],
    prologue: &[u8],
    me: &Greeting,
) -> Result<(snow::TransportState, Vec<u8>, Greeting, [u8; 32])> {
    let mut state = builder(identity, psk, prologue)?
        .build_initiator()
        .map_err(noise_fault)?;
    let mut buffer = vec![0u8; NOISE_BUFFER];

    let written = state.write_message(&[], &mut buffer).map_err(noise_fault)?;
    channel.send(&buffer[..written])?;

    let Some(second) = channel.recv()? else {
        // Nothing came back. On this path that is what being refused
        // looks like, and it is also all an unpaired caller ever sees.
        return Err(SyncError::Refused {
            detail: "that device did not accept the connection".to_string(),
        });
    };
    let read = state.read_message(&second, &mut buffer).map_err(noise_fault)?;
    let peer = Greeting::decode(&buffer[..read])?;

    let written = state
        .write_message(&me.encode(), &mut buffer)
        .map_err(noise_fault)?;
    channel.send(&buffer[..written])?;

    finish_noise(state, peer)
}

/// The answering half, trying each `psk` it holds against a buffered
/// first message. `candidates` is empty for a pairing, where the single
/// PAKE output is passed instead.
fn noise_responder(
    channel: &mut PlainChannel,
    identity: &Identity,
    candidates: &[(String, [u8; 32])],
    prologue: &[u8],
    me: &Greeting,
) -> Result<(snow::TransportState, Vec<u8>, Greeting, [u8; 32])> {
    let Some(first) = channel.recv()? else {
        return Err(SyncError::Protocol { detail: "the caller left".to_string() });
    };
    let mut buffer = vec![0u8; NOISE_BUFFER];

    let mut chosen: Option<snow::HandshakeState> = None;
    for (_, psk) in candidates {
        let mut state = builder(identity, psk, prologue)?
            .build_responder()
            .map_err(noise_fault)?;
        if state.read_message(&first, &mut buffer).is_ok() {
            chosen = Some(state);
            break;
        }
    }
    // Nothing is written back. An unpaired caller gets silence, not a
    // message it could learn a static key or a timing signal from.
    let mut state = chosen.ok_or(SyncError::Refused {
        detail: "this device is not paired with the caller".to_string(),
    })?;

    let written = state
        .write_message(&me.encode(), &mut buffer)
        .map_err(noise_fault)?;
    channel.send(&buffer[..written])?;

    let Some(third) = channel.recv()? else {
        return Err(SyncError::Protocol {
            detail: "the caller left part-way through the handshake".to_string(),
        });
    };
    let read = state.read_message(&third, &mut buffer).map_err(noise_fault)?;
    let peer = Greeting::decode(&buffer[..read])?;

    finish_noise(state, peer)
}

fn finish_noise(
    state: snow::HandshakeState,
    peer: Greeting,
) -> Result<(snow::TransportState, Vec<u8>, Greeting, [u8; 32])> {
    let remote = state
        .get_remote_static()
        .ok_or(SyncError::Protocol {
            detail: "the handshake completed without a peer identity".to_string(),
        })?
        .to_vec();
    // Taken before transport mode, which is the only place it is
    // reachable. Both sides compute the same value from the same
    // transcript, so the pair secret is agreed without being sent.
    let secret = derive(b"gilune:pair-secret:v1", state.get_handshake_hash());
    let transport = state.into_transport_mode().map_err(noise_fault)?;
    Ok((transport, remote, peer, secret))
}

// ------------------------------------------------------------ the two flows

fn timed(stream: &TcpStream, timeout: Duration) -> Result<()> {
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    Ok(())
}

/// Dial a device and pair with it. The joiner's side of § 3.
pub fn dial_pairing(
    stream: TcpStream,
    limits: Limits,
    identity: &Identity,
    me: &Greeting,
    credential: Credential,
    secret: &[u8],
) -> Result<Established> {
    stream.set_nodelay(true)?;
    timed(&stream, HANDSHAKE_TIMEOUT)?;
    let mut channel = PlainChannel::new(stream, limits);
    let intent = Intent::Pair(credential);
    send_hello(&mut channel, intent)?;
    read_answer(&mut channel)?;
    let psk = pake(&mut channel, secret, false)?;
    let (transport, remote, peer, derived) =
        noise_initiator(&mut channel, identity, &psk, &prologue(intent), me)?;
    let stream = channel.into_stream();
    timed(&stream, SESSION_TIMEOUT)?;
    Ok(Established {
        channel: NoiseChannel::new(stream, transport, limits),
        remote_static: remote,
        peer,
        derived_secret: derived,
    })
}

/// Answer a pairing, having already read the hello and decided to serve
/// it. The listener's side.
pub fn accept_pairing(
    mut channel: PlainChannel,
    limits: Limits,
    identity: &Identity,
    me: &Greeting,
    credential: Credential,
    secret: &[u8],
) -> Result<Established> {
    let intent = Intent::Pair(credential);
    answer_ready(&mut channel)?;
    let psk = pake(&mut channel, secret, true)?;
    let (transport, remote, peer, derived) = noise_responder(
        &mut channel,
        identity,
        &[(String::new(), psk)],
        &prologue(intent),
        me,
    )?;
    let stream = channel.into_stream();
    timed(&stream, SESSION_TIMEOUT)?;
    Ok(Established {
        channel: NoiseChannel::new(stream, transport, limits),
        remote_static: remote,
        peer,
        derived_secret: derived,
    })
}

/// Dial a peer this device is already paired with.
pub fn dial_session(
    stream: TcpStream,
    limits: Limits,
    identity: &Identity,
    me: &Greeting,
    expected_static: &str,
    psk: [u8; 32],
) -> Result<Established> {
    stream.set_nodelay(true)?;
    timed(&stream, HANDSHAKE_TIMEOUT)?;
    let mut channel = PlainChannel::new(stream, limits);
    send_hello(&mut channel, Intent::Session)?;
    read_answer(&mut channel)?;
    let (transport, remote, peer, derived) = noise_initiator(
        &mut channel,
        identity,
        &psk,
        &prologue(Intent::Session),
        me,
    )?;
    // Belt and braces over the pinning the `psk` already enforces: the
    // key the handshake transmitted must be the key the roster holds.
    if hex::encode(&remote) != expected_static {
        return Err(SyncError::Refused {
            detail: "that address answered with a different device".to_string(),
        });
    }
    let stream = channel.into_stream();
    timed(&stream, SESSION_TIMEOUT)?;
    Ok(Established {
        channel: NoiseChannel::new(stream, transport, limits),
        remote_static: remote,
        peer,
        derived_secret: derived,
    })
}

/// Answer a session from a paired peer, working out which one it is by
/// trying every pair secret this device holds.
pub fn accept_session(
    mut channel: PlainChannel,
    limits: Limits,
    identity: &Identity,
    me: &Greeting,
    candidates: &[(String, [u8; 32])],
) -> Result<Established> {
    if candidates.is_empty() {
        // Refuse without answering: a device with no peers should not
        // even confirm that it is listening for sessions.
        return Err(SyncError::Refused {
            detail: "this device is paired with nobody".to_string(),
        });
    }
    answer_ready(&mut channel)?;
    let (transport, remote, peer, derived) = noise_responder(
        &mut channel,
        identity,
        candidates,
        &prologue(Intent::Session),
        me,
    )?;
    let hex_remote = hex::encode(&remote);
    if !candidates.iter().any(|(pinned, _)| *pinned == hex_remote) {
        return Err(SyncError::Refused {
            detail: "the caller's identity is not the one its pair secret belongs to".to_string(),
        });
    }
    let stream = channel.into_stream();
    timed(&stream, SESSION_TIMEOUT)?;
    Ok(Established {
        channel: NoiseChannel::new(stream, transport, limits),
        remote_static: remote,
        peer,
        derived_secret: derived,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::mint_identity;
    use std::net::TcpListener;

    fn pair_of_sockets() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let address = listener.local_addr().expect("addr");
        let client = TcpStream::connect(address).expect("connect");
        let (server, _) = listener.accept().expect("accept");
        (client, server)
    }

    fn greeting(name: &str) -> Greeting {
        Greeting::new(&format!("device-{name}"), name, "127.0.0.1:9")
    }

    /// Run a pairing across two threads and hand back both outcomes.
    fn pair_with(
        joiner_secret: &[u8],
        listener_secret: &[u8],
    ) -> (Result<Established>, Result<Established>) {
        let (client, server) = pair_of_sockets();
        let listener_identity = mint_identity().expect("mint");
        let joiner_identity = mint_identity().expect("mint");
        let listener_secret = listener_secret.to_vec();
        let listener = std::thread::spawn(move || {
            let mut channel = PlainChannel::new(server, Limits::DEFAULT);
            let intent = read_hello(&mut channel)?;
            let Intent::Pair(credential) = intent else {
                return Err(SyncError::Protocol { detail: "expected a pairing".into() });
            };
            accept_pairing(
                channel,
                Limits::DEFAULT,
                &listener_identity,
                &greeting("listener"),
                credential,
                &listener_secret,
            )
        });
        let joined = dial_pairing(
            client,
            Limits::DEFAULT,
            &joiner_identity,
            &greeting("joiner"),
            Credential::Code,
            joiner_secret,
        );
        let served = listener.join().expect("listener thread");
        (joined, served)
    }

    #[test]
    fn a_matching_code_pairs_both_sides_on_one_secret() {
        let (joined, served) = pair_with(b"481902", b"481902");
        let joined = joined.expect("the joiner pairs");
        let served = served.expect("the listener pairs");

        assert_eq!(
            joined.derived_secret, served.derived_secret,
            "both sides derive the same pair secret without either sending it"
        );
        assert_eq!(joined.peer.name, "listener");
        assert_eq!(served.peer.name, "joiner");
        assert_eq!(joined.peer.device_id, "device-listener");
        assert_ne!(joined.remote_static_hex(), served.remote_static_hex());
        assert_eq!(joined.remote_static.len(), 32);
    }

    #[test]
    fn a_wrong_code_fails_both_sides_and_says_nothing_useful() {
        let (joined, served) = pair_with(b"000000", b"481902");
        let Err(joined) = joined else { panic!("a wrong code must not pair") };
        assert!(served.is_err(), "and the listener must not believe it did");
        let said = joined.to_string();
        assert!(
            !said.contains("481902") && !said.contains("000000"),
            "a failure that quotes the secret is an oracle: {said}"
        );
    }

    #[test]
    fn a_paired_secret_opens_a_session_and_carries_bytes_both_ways() {
        let (joined, served) = pair_with(b"481902", b"481902");
        let joined = joined.expect("pair");
        let served = served.expect("pair");
        let secret = joined.derived_secret;
        let joiner_static = joined.remote_static_hex();
        drop(joined);
        drop(served);

        // A second connection, now as an ordinary session.
        let (client, server) = pair_of_sockets();
        let listener_identity = mint_identity().expect("mint");
        let dialer_identity = mint_identity().expect("mint");
        let listener_static = hex::encode(listener_identity.public());
        let dialer_static = hex::encode(dialer_identity.public());
        let _ = joiner_static;

        let candidates = vec![(dialer_static, secret)];
        let listener = std::thread::spawn(move || {
            let mut channel = PlainChannel::new(server, Limits::DEFAULT);
            let intent = read_hello(&mut channel)?;
            assert_eq!(intent, Intent::Session);
            let mut established = accept_session(
                channel,
                Limits::DEFAULT,
                &listener_identity,
                &greeting("listener"),
                &candidates,
            )?;
            let heard = established.channel.recv()?.expect("a frame");
            established.channel.send(b"and back")?;
            Ok::<Vec<u8>, SyncError>(heard)
        });

        let mut established = dial_session(
            client,
            Limits::DEFAULT,
            &dialer_identity,
            &greeting("dialer"),
            &listener_static,
            secret,
        )
        .expect("session");
        established.channel.send(b"over the wire").expect("send");
        let answer = established.channel.recv().expect("recv").expect("a frame");

        assert_eq!(listener.join().expect("thread").expect("served"), b"over the wire");
        assert_eq!(answer, b"and back");
    }

    #[test]
    fn an_unpaired_device_is_refused_and_never_learns_the_listeners_identity() {
        let (client, server) = pair_of_sockets();
        let listener_identity = mint_identity().expect("mint");
        let listener_static = hex::encode(listener_identity.public());
        let stranger = mint_identity().expect("mint");
        // A listener that holds exactly one pairing, with somebody else.
        let somebody_else = hex::encode(mint_identity().expect("mint").public());
        let candidates = vec![(somebody_else, [42u8; 32])];

        let listener = std::thread::spawn(move || {
            let mut channel = PlainChannel::new(server, Limits::DEFAULT);
            let _ = read_hello(&mut channel)?;
            accept_session(
                channel,
                Limits::DEFAULT,
                &listener_identity,
                &greeting("listener"),
                &candidates,
            )
        });

        let refused = dial_session(
            client,
            Limits::DEFAULT,
            &stranger,
            &greeting("stranger"),
            &listener_static,
            [1u8; 32],
        );

        let said = match refused {
            Ok(_) => panic!("an unpaired device must be refused"),
            Err(e) => e.to_string(),
        };
        assert!(listener.join().expect("thread").is_err());
        assert!(
            !said.contains(&listener_static),
            "the refusal must not hand over the identity it was fishing for: {said}"
        );
    }

    #[test]
    fn a_listener_with_no_peers_refuses_a_session_without_answering() {
        let (client, server) = pair_of_sockets();
        let listener_identity = mint_identity().expect("mint");
        let listener = std::thread::spawn(move || {
            let mut channel = PlainChannel::new(server, Limits::DEFAULT);
            let _ = read_hello(&mut channel)?;
            accept_session(
                channel,
                Limits::DEFAULT,
                &listener_identity,
                &greeting("listener"),
                &[],
            )
        });
        let stranger = mint_identity().expect("mint");
        let refused = dial_session(
            client,
            Limits::DEFAULT,
            &stranger,
            &greeting("stranger"),
            &hex::encode(stranger.public()),
            [1u8; 32],
        );
        assert!(refused.is_err());
        assert!(listener.join().expect("thread").is_err());
    }

    #[test]
    fn the_qr_path_and_the_code_path_are_the_same_exchange() {
        let long = [9u8; 16];
        let (client, server) = pair_of_sockets();
        let listener_identity = mint_identity().expect("mint");
        let joiner_identity = mint_identity().expect("mint");
        let listener = std::thread::spawn(move || {
            let mut channel = PlainChannel::new(server, Limits::DEFAULT);
            let Intent::Pair(credential) = read_hello(&mut channel)? else {
                return Err(SyncError::Protocol { detail: "expected a pairing".into() });
            };
            assert_eq!(credential, Credential::Qr, "the kind survives the preamble");
            accept_pairing(
                channel,
                Limits::DEFAULT,
                &listener_identity,
                &greeting("listener"),
                credential,
                &long,
            )
        });
        let joined = dial_pairing(
            client,
            Limits::DEFAULT,
            &joiner_identity,
            &greeting("joiner"),
            Credential::Qr,
            &long,
        )
        .expect("pair over the QR secret");
        let served = listener.join().expect("thread").expect("pair");
        assert_eq!(joined.derived_secret, served.derived_secret);
    }

    #[test]
    fn a_credential_kind_cannot_be_downgraded_between_the_preamble_and_the_handshake() {
        // The same secret, but the two sides bind different kinds into
        // the prologue: the handshake must not complete.
        let secret = [3u8; 16];
        let (client, server) = pair_of_sockets();
        let listener_identity = mint_identity().expect("mint");
        let joiner_identity = mint_identity().expect("mint");
        let listener = std::thread::spawn(move || {
            let mut channel = PlainChannel::new(server, Limits::DEFAULT);
            let _ = read_hello(&mut channel)?;
            accept_pairing(
                channel,
                Limits::DEFAULT,
                &listener_identity,
                &greeting("listener"),
                Credential::Code,
                &secret,
            )
        });
        let joined = dial_pairing(
            client,
            Limits::DEFAULT,
            &joiner_identity,
            &greeting("joiner"),
            Credential::Qr,
            &secret,
        );
        assert!(joined.is_err(), "the prologue binds the credential kind");
        assert!(listener.join().expect("thread").is_err());
    }

    /// A pair of sockets with everything that crosses them recorded, so a
    /// test can look at what actually went over the wire rather than at
    /// what was handed to the channel.
    fn tapped_pair() -> (TcpStream, TcpStream, std::sync::Arc<std::sync::Mutex<Vec<u8>>>) {
        use std::io::{Read, Write};
        let seen = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let inner = TcpListener::bind("127.0.0.1:0").expect("bind");
        let inner_addr = inner.local_addr().expect("addr");
        let outer = TcpListener::bind("127.0.0.1:0").expect("bind");
        let outer_addr = outer.local_addr().expect("addr");

        let recorded = std::sync::Arc::clone(&seen);
        std::thread::spawn(move || {
            let (client_side, _) = outer.accept().expect("accept");
            let server_side = TcpStream::connect(inner_addr).expect("connect");
            let pipe = |mut from: TcpStream, mut to: TcpStream, log: Option<std::sync::Arc<std::sync::Mutex<Vec<u8>>>>| {
                std::thread::spawn(move || {
                    let mut buffer = [0u8; 8192];
                    loop {
                        match from.read(&mut buffer) {
                            Ok(0) | Err(_) => break,
                            Ok(read) => {
                                if let Some(log) = &log {
                                    log.lock().expect("log").extend_from_slice(&buffer[..read]);
                                }
                                if to.write_all(&buffer[..read]).is_err() {
                                    break;
                                }
                                let _ = to.flush();
                            }
                        }
                    }
                })
            };
            let a = client_side.try_clone().expect("clone");
            let b = server_side.try_clone().expect("clone");
            pipe(client_side, server_side, Some(recorded));
            pipe(b, a, None);
        });

        let client = TcpStream::connect(outer_addr).expect("connect");
        let (server, _) = inner.accept().expect("accept");
        (client, server, seen)
    }

    #[test]
    fn what_a_paired_session_writes_is_not_what_travels_on_the_wire() {
        let secret = [11u8; 32];
        let (client, server, seen) = tapped_pair();
        let listener_identity = mint_identity().expect("mint");
        let dialer_identity = mint_identity().expect("mint");
        let listener_static = hex::encode(listener_identity.public());
        let listener_key = listener_identity.public().to_vec();
        let candidates = vec![(hex::encode(dialer_identity.public()), secret)];
        let confidential = b"an appointment nobody else's business";

        let listening = std::thread::spawn(move || {
            let mut channel = PlainChannel::new(server, Limits::DEFAULT);
            let _ = read_hello(&mut channel)?;
            let mut established = accept_session(
                channel,
                Limits::DEFAULT,
                &listener_identity,
                &greeting("listener"),
                &candidates,
            )?;
            established.channel.recv()
        });

        let mut established = dial_session(
            client,
            Limits::DEFAULT,
            &dialer_identity,
            &greeting("dialer"),
            &listener_static,
            secret,
        )
        .expect("session");
        established.channel.send(confidential).expect("send");
        let heard = listening.join().expect("thread").expect("recv").expect("a frame");
        assert_eq!(heard, confidential, "the far end reads what was written");

        let wire = seen.lock().expect("log").clone();
        assert!(!wire.is_empty(), "the tap saw the conversation");
        assert!(
            !wire.windows(confidential.len()).any(|w| w == confidential),
            "the content must not be readable by anything sitting on the wire"
        );
        // And the identities are not on the wire either: `XXpsk0` sends
        // both static keys encrypted, which is what stops a scanner
        // learning who is talking to whom.
        assert!(
            !wire.windows(32).any(|w| w == listener_key.as_slice()),
            "the responder's static key travels encrypted"
        );
    }

    #[test]
    fn a_frame_larger_than_one_noise_message_crosses_whole() {
        let secret = [12u8; 32];
        let (client, server, _) = tapped_pair();
        let listener_identity = mint_identity().expect("mint");
        let dialer_identity = mint_identity().expect("mint");
        let listener_static = hex::encode(listener_identity.public());
        let candidates = vec![(hex::encode(dialer_identity.public()), secret)];
        // Bigger than one Noise message and bigger than one segment, so
        // this is the path a 64 KiB blob chunk takes.
        let big: Vec<u8> = (0..200_000u32).map(|n| (n % 251) as u8).collect();
        let expected = big.clone();

        let listening = std::thread::spawn(move || {
            let mut channel = PlainChannel::new(server, Limits::DEFAULT);
            let _ = read_hello(&mut channel)?;
            let mut established = accept_session(
                channel,
                Limits::DEFAULT,
                &listener_identity,
                &greeting("listener"),
                &candidates,
            )?;
            let heard = established.channel.recv()?;
            established.channel.send(b"received")?;
            Ok::<Option<Vec<u8>>, SyncError>(heard)
        });

        let mut established = dial_session(
            client,
            Limits::DEFAULT,
            &dialer_identity,
            &greeting("dialer"),
            &listener_static,
            secret,
        )
        .expect("session");
        established.channel.send(&big).expect("send");
        let answer = established.channel.recv().expect("recv").expect("a frame");
        assert_eq!(answer, b"received");
        assert_eq!(
            listening.join().expect("thread").expect("served").expect("a frame"),
            expected,
            "a frame spanning several noise messages arrives byte-exact"
        );
    }

    #[test]
    fn a_greeting_is_bounded_like_any_other_untrusted_field() {
        let long = serde_json::json!({ "device_id": "d", "name": "x".repeat(65) });
        assert!(Greeting::decode(long.to_string().as_bytes()).is_err());
        let control = serde_json::json!({ "device_id": "a\u{0}b", "name": "n" });
        assert!(Greeting::decode(control.to_string().as_bytes()).is_err());
        assert!(Greeting::decode(b"not json at all").is_err());
        let fine = serde_json::json!({ "device_id": "d", "name": "Laptop" });
        assert_eq!(
            Greeting::decode(fine.to_string().as_bytes()).expect("ok").name,
            "Laptop"
        );
    }
}
