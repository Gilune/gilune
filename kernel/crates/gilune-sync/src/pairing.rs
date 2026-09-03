//! Pairing secrets: the short code, the QR payload, and the window that
//! bounds guessing at both (ADR 0019 § 3).
//!
//! One exchange carries both paths — "it is the same exchange for both
//! paths; only the secret's entropy differs" — so everything here is
//! about *the secret*, and `handshake` is about what is done with it.
//!
//! Six digits is about twenty bits. As a raw pre-shared key one captured
//! handshake would brute-force it offline in seconds, which is why the
//! exchange runs a PAKE; but a PAKE only makes each guess cost one
//! *online* attempt, so the online attempts have to be counted. A code is
//! single-use, short-lived, CSPRNG-generated, and the window is abandoned
//! after a small number of failures: "the user gets a new code, not an
//! unbounded oracle".

use crate::{Result, SyncError};

/// Digits in a short code. Twenty bits, bounded by the window below.
pub const CODE_DIGITS: u32 = 6;
/// The QR's secret. Long enough that its entropy, not the attempt limit,
/// is what stops guessing.
pub const QR_SECRET_BYTES: usize = 16;
/// How long a pairing window stands. Long enough to walk to the other
/// device, short enough that a code left on screen is not a standing
/// invitation.
pub const DEFAULT_WINDOW_MS: i64 = 180_000;
/// Failed attempts before the window is abandoned.
pub const DEFAULT_MAX_ATTEMPTS: u32 = 5;

const QR_PREFIX: &str = "GILUNE-PAIR";
const QR_VERSION: i64 = 1;
/// A scanned payload is untrusted input; nothing longer than this is even
/// looked at.
const QR_MAX_CHARS: usize = 512;
/// How many addresses one QR offers.
///
/// **A machine cannot know which of its addresses a phone shares a network
/// with** (`addressing::local_addresses` says why), and the old payload
/// made it guess — one field, one host, and on a laptop with Hyper-V, WSL
/// and a VPN the guess was whichever address sorted lowest. A scan that
/// carries every candidate turns the guess into the joiner's dial, which
/// is the only place the question can actually be answered. Four is what a
/// laptop with a hypervisor, a VPN and a LAN plausibly offers, and it
/// keeps the field inside the address bound below.
pub const QR_MAX_HOSTS: usize = 4;
/// The whole address field, hosts and port together.
const QR_MAX_ADDRESS_CHARS: usize = 128;

/// Which secret a joiner is presenting. Declared in the clear, because
/// the listener has to know which password to run the PAKE over. It
/// discloses only that a pairing is happening, which the pairing screen
/// is already announcing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Credential {
    Code,
    Qr,
}

impl Credential {
    pub fn as_str(&self) -> &'static str {
        match self {
            Credential::Code => "code",
            Credential::Qr => "qr",
        }
    }

    pub fn parse(text: &str) -> Option<Credential> {
        match text {
            "code" => Some(Credential::Code),
            "qr" => Some(Credential::Qr),
            _ => None,
        }
    }
}

/// Random bytes from the OS CSPRNG. Every secret in this module comes
/// from here and from nowhere else.
pub fn random_bytes(into: &mut [u8]) -> Result<()> {
    getrandom::fill(into).map_err(|e| SyncError::Identity {
        detail: format!("the operating system would not supply randomness: {e}"),
    })
}

/// A six-digit code, uniform over 000000..=999999.
///
/// Rejection sampling rather than a modulo: `u32 % 1_000_000` is very
/// slightly biased toward low codes, and a biased code is a marginally
/// cheaper guess. It costs nothing to be exact here.
pub fn generate_code() -> Result<String> {
    let span: u32 = 1_000_000;
    let ceiling = u32::MAX - (u32::MAX % span);
    loop {
        let mut bytes = [0u8; 4];
        random_bytes(&mut bytes)?;
        let draw = u32::from_be_bytes(bytes);
        if draw < ceiling {
            return Ok(format!("{:0width$}", draw % span, width = CODE_DIGITS as usize));
        }
    }
}

/// A typed code, with the spaces and dashes people put in it taken back
/// out. Rejects anything that is not exactly six digits — a code with a
/// letter in it is a typing mistake, and spending an attempt on it would
/// punish the user for the wrong thing.
pub fn normalise_code(typed: &str) -> Result<String> {
    let digits: String = typed.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() != CODE_DIGITS as usize || typed.chars().count() > 32 {
        return Err(SyncError::Refused {
            detail: format!("a pairing code is {CODE_DIGITS} digits"),
        });
    }
    Ok(digits)
}

// ------------------------------------------------------------- the window

/// The listener's side of a pairing: the secrets on offer, until they are
/// used, expire, or are guessed at too often.
pub struct PairingWindow {
    code: String,
    qr_secret: [u8; QR_SECRET_BYTES],
    pub opened_ms: i64,
    pub expires_ms: i64,
    pub max_attempts: u32,
    attempts: u32,
    consumed: bool,
    /// Every address the joiner could connect at, best first — what the QR
    /// carries in full, and what a person typing one by hand is choosing
    /// from.
    pub addresses: Vec<String>,
    /// This device's own fingerprint, so a scan can check it saw the
    /// device it was pointed at.
    pub fingerprint: String,
}

/// Why a window would not serve a secret. Deliberately coarse on the
/// wire: a joiner is told it was refused, never which of these it was.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WindowClosed {
    Expired,
    Consumed,
    Abandoned,
}

impl WindowClosed {
    pub fn as_str(&self) -> &'static str {
        match self {
            WindowClosed::Expired => "the pairing code has expired",
            WindowClosed::Consumed => "the pairing code has already been used",
            WindowClosed::Abandoned => "too many wrong codes — ask for a new one",
        }
    }
}

impl PairingWindow {
    pub fn open(
        addresses: &[String],
        fingerprint: &str,
        now_ms: i64,
        window_ms: i64,
        max_attempts: u32,
    ) -> Result<PairingWindow> {
        let mut qr_secret = [0u8; QR_SECRET_BYTES];
        random_bytes(&mut qr_secret)?;
        Ok(PairingWindow {
            code: generate_code()?,
            qr_secret,
            opened_ms: now_ms,
            expires_ms: now_ms + window_ms,
            max_attempts,
            attempts: 0,
            consumed: false,
            addresses: addresses.to_vec(),
            fingerprint: fingerprint.to_string(),
        })
    }

    /// The one address to put in front of a person who is going to type
    /// it: the best-ranked. The rest are on the settings screen, and all
    /// of them are in the QR.
    pub fn address(&self) -> String {
        self.addresses.first().cloned().unwrap_or_default()
    }

    pub fn code(&self) -> &str {
        &self.code
    }

    pub fn attempts(&self) -> u32 {
        self.attempts
    }

    pub fn remaining_attempts(&self) -> u32 {
        self.max_attempts.saturating_sub(self.attempts)
    }

    pub fn abandoned(&self) -> bool {
        self.attempts >= self.max_attempts
    }

    pub fn consumed(&self) -> bool {
        self.consumed
    }

    /// The text a QR encodes. Version, secret, fingerprint, addresses —
    /// the four things § 7 names, in that order.
    pub fn qr_payload(&self) -> String {
        encode_qr_payload(&self.qr_secret, &self.fingerprint, &self.addresses)
    }

    pub fn state(&self, now_ms: i64) -> Option<WindowClosed> {
        if self.consumed {
            Some(WindowClosed::Consumed)
        } else if self.abandoned() {
            Some(WindowClosed::Abandoned)
        } else if now_ms >= self.expires_ms {
            Some(WindowClosed::Expired)
        } else {
            None
        }
    }

    /// The password for one credential kind, or the reason this window
    /// will not serve one.
    pub fn secret(&self, credential: Credential, now_ms: i64) -> Result<Vec<u8>> {
        if let Some(closed) = self.state(now_ms) {
            return Err(SyncError::Refused { detail: closed.as_str().to_string() });
        }
        Ok(match credential {
            Credential::Code => self.code.as_bytes().to_vec(),
            Credential::Qr => self.qr_secret.to_vec(),
        })
    }

    /// One online guess spent. At the limit the window abandons itself —
    /// the secrets are cleared, so nothing further can be tried against
    /// this window even by a caller that forgets to ask.
    pub fn fail(&mut self) {
        self.attempts += 1;
        if self.abandoned() {
            self.code.clear();
            self.qr_secret = [0u8; QR_SECRET_BYTES];
        }
    }

    /// Single use. A completed pairing closes the window even though the
    /// code was correct, because a correct code is exactly what an
    /// eavesdropper who watched somebody type it would also have.
    pub fn consume(&mut self) {
        self.consumed = true;
        self.code.clear();
        self.qr_secret = [0u8; QR_SECRET_BYTES];
    }
}

// ---------------------------------------------------------- the QR payload

/// What a scanned QR says.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QrPairing {
    pub version: i64,
    pub secret: Vec<u8>,
    /// The listener's fingerprint, compact. Checked against the identity
    /// the handshake actually transmits, so a QR photographed from a
    /// screen cannot redirect a phone to a different device without the
    /// mismatch being visible.
    pub fingerprint: String,
    /// Every address the listener offered, in the order it ranked them.
    /// The joiner dials them in turn; **the order is a hint and the device
    /// that answers is the decision**, because the machine that wrote the
    /// QR cannot know which of its interfaces the scanner shares.
    pub addresses: Vec<String>,
}

impl QrPairing {
    /// The first address, for a caller that only wants to say where this
    /// came from.
    pub fn address(&self) -> String {
        self.addresses.first().cloned().unwrap_or_default()
    }
}

/// The address field: every host, then the one port they share.
///
/// One listener has one port, so repeating it per host would be four
/// copies of one fact in a payload that has to stay small enough to scan.
/// Addresses that disagree with the first one's port are dropped rather
/// than silently re-pointed — a caller that mixed two listeners' addresses
/// has a bug, and inventing a combination is worse than losing one.
pub fn encode_addresses(addresses: &[String]) -> String {
    let mut port = String::new();
    let mut hosts: Vec<&str> = Vec::new();
    for address in addresses.iter().take(QR_MAX_HOSTS) {
        let Some((host, its_port)) = address.rsplit_once(':') else { continue };
        if port.is_empty() {
            port = its_port.to_string();
        }
        // A host carrying a colon is an IPv6 literal, which this field
        // cannot express — and the decoder refuses one, so emitting it
        // would let the encoder write a payload its own reader rejects.
        // (Nothing routable reaches here as v6; see `addressing`.)
        if its_port != port || host.is_empty() || host.contains(':') || hosts.contains(&host) {
            continue;
        }
        hosts.push(host);
    }
    if hosts.is_empty() {
        return String::new();
    }
    format!("{}:{port}", hosts.join(","))
}

/// And back: `h1,h2:port` into dialable `host:port` strings, in order.
///
/// Adversarial input like every other field here — a camera reads any QR
/// in the world — so the count, the length and every character are checked
/// before any of it is believed.
pub fn decode_addresses(field: &str) -> Result<Vec<String>> {
    let refused = || SyncError::Refused {
        detail: "that pairing code names no address".to_string(),
    };
    let field = field.trim();
    if field.is_empty()
        || field.chars().count() > QR_MAX_ADDRESS_CHARS
        || field.chars().any(|c| c.is_control() || c.is_whitespace())
    {
        return Err(refused());
    }
    let (hosts, port) = field.rsplit_once(':').ok_or_else(refused)?;
    // A host part carrying a colon is an IPv6 literal or a second port,
    // and this field format can express neither. Refusing beats dialling
    // something nobody meant.
    if hosts.contains(':') || port.is_empty() || port.parse::<u16>().is_err() {
        return Err(refused());
    }
    let mut addresses: Vec<String> = Vec::new();
    for host in hosts.split(',') {
        if host.is_empty() || host.chars().count() > 64 {
            return Err(refused());
        }
        let address = format!("{host}:{port}");
        if !addresses.contains(&address) {
            addresses.push(address);
        }
    }
    if addresses.is_empty() || addresses.len() > QR_MAX_HOSTS {
        return Err(refused());
    }
    Ok(addresses)
}

pub fn encode_qr_payload(secret: &[u8], fingerprint: &str, addresses: &[String]) -> String {
    format!(
        "{QR_PREFIX}|{QR_VERSION}|{}|{}|{}",
        hex::encode(secret),
        fingerprint.replace('-', "").to_lowercase(),
        encode_addresses(addresses)
    )
}

/// Parse a scanned payload. Adversarial by assumption: a camera will
/// happily read any QR in the world, so every field is checked for shape
/// and range before any of it is believed.
pub fn decode_qr_payload(text: &str) -> Result<QrPairing> {
    let refused = |detail: &str| SyncError::Refused { detail: detail.to_string() };
    let text = text.trim();
    if text.chars().count() > QR_MAX_CHARS {
        return Err(refused("that code is too long to be a Gilune pairing code"));
    }
    let fields: Vec<&str> = text.split('|').collect();
    if fields.len() != 5 || fields[0] != QR_PREFIX {
        return Err(refused("that is not a Gilune pairing code"));
    }
    let version: i64 = fields[1]
        .parse()
        .map_err(|_| refused("that pairing code has no version"))?;
    if version != QR_VERSION {
        return Err(SyncError::Refused {
            detail: format!(
                "that pairing code is version {version}; this device speaks {QR_VERSION}"
            ),
        });
    }
    let secret = hex::decode(fields[2]).map_err(|_| refused("that pairing code is damaged"))?;
    if secret.len() != QR_SECRET_BYTES {
        return Err(refused("that pairing code is damaged"));
    }
    let fingerprint = fields[3].to_ascii_lowercase();
    if fingerprint.len() != 16 || !fingerprint.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(refused("that pairing code names no device"));
    }
    let addresses = decode_addresses(fields[4])?;
    Ok(QrPairing { version, secret, fingerprint, addresses })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn window(now: i64) -> PairingWindow {
        PairingWindow::open(
            &["127.0.0.1:5599".to_string()],
            "0123456789abcdef",
            now,
            DEFAULT_WINDOW_MS,
            3,
        )
        .expect("open")
    }

    fn addresses(list: &[&str]) -> Vec<String> {
        list.iter().map(|a| a.to_string()).collect()
    }

    #[test]
    fn a_generated_code_is_always_six_digits() {
        for _ in 0..200 {
            let code = generate_code().expect("code");
            assert_eq!(code.len(), 6);
            assert!(code.chars().all(|c| c.is_ascii_digit()));
        }
    }

    #[test]
    fn generated_codes_are_not_all_the_same_one() {
        let mut seen = std::collections::BTreeSet::new();
        for _ in 0..50 {
            seen.insert(generate_code().expect("code"));
        }
        assert!(seen.len() > 40, "a code has to be unpredictable to be a secret");
    }

    #[test]
    fn a_typed_code_survives_the_spaces_and_dashes_people_add() {
        assert_eq!(normalise_code("123 456").expect("ok"), "123456");
        assert_eq!(normalise_code("123-456").expect("ok"), "123456");
        let padded = format!("{}123456", " ".repeat(40));
        for bad in ["12345", "1234567", "12345a", "", padded.as_str()] {
            assert!(normalise_code(bad).is_err(), "{bad:?} is not a code");
        }
    }

    #[test]
    fn a_live_window_serves_both_secrets_and_they_differ() {
        let window = window(1_000);
        let code = window.secret(Credential::Code, 1_000).expect("code");
        let qr = window.secret(Credential::Qr, 1_000).expect("qr");
        assert_eq!(code.len(), 6);
        assert_eq!(qr.len(), QR_SECRET_BYTES);
        assert_ne!(code, qr);
    }

    #[test]
    fn a_code_is_single_use() {
        let mut window = window(1_000);
        window.consume();
        assert_eq!(window.state(1_000), Some(WindowClosed::Consumed));
        assert!(window.secret(Credential::Code, 1_000).is_err());
        assert!(window.secret(Credential::Qr, 1_000).is_err());
    }

    #[test]
    fn a_code_expires() {
        let window = window(1_000);
        assert!(window.secret(Credential::Code, 1_000 + DEFAULT_WINDOW_MS - 1).is_ok());
        assert_eq!(
            window.state(1_000 + DEFAULT_WINDOW_MS),
            Some(WindowClosed::Expired)
        );
        assert!(window.secret(Credential::Code, 1_000 + DEFAULT_WINDOW_MS).is_err());
    }

    #[test]
    fn the_window_is_abandoned_after_its_attempt_limit_and_clears_its_secrets() {
        let mut window = window(1_000);
        let code = window.code().to_string();
        for _ in 0..3 {
            assert!(!window.abandoned());
            window.fail();
        }
        assert!(window.abandoned());
        assert_eq!(window.remaining_attempts(), 0);
        assert_eq!(window.state(1_000), Some(WindowClosed::Abandoned));
        assert!(window.secret(Credential::Code, 1_000).is_err());
        assert_ne!(window.code(), code, "the secret itself is gone, not merely flagged");
    }

    #[test]
    fn a_refusal_says_only_that_it_was_refused() {
        let mut window = window(1_000);
        window.fail();
        window.fail();
        window.fail();
        let Err(SyncError::Refused { detail }) = window.secret(Credential::Code, 1_000) else {
            panic!("refused");
        };
        assert!(
            !detail.contains(&window.attempts().to_string()) || detail.contains("new one"),
            "the wording tells the user what to do, not the attacker how close they are"
        );
    }

    #[test]
    fn a_qr_payload_round_trips_every_field() {
        let window = window(1_000);
        let decoded = decode_qr_payload(&window.qr_payload()).expect("decode");
        assert_eq!(decoded.version, QR_VERSION);
        assert_eq!(decoded.secret.len(), QR_SECRET_BYTES);
        assert_eq!(decoded.fingerprint, "0123456789abcdef");
        assert_eq!(decoded.addresses, vec!["127.0.0.1:5599".to_string()]);
        assert_eq!(decoded.address(), "127.0.0.1:5599");
        assert_eq!(
            decoded.secret,
            window.secret(Credential::Qr, 1_000).expect("qr secret")
        );
    }

    /// **Second-order test for publishing every candidate.** A laptop with
    /// a hypervisor bridge, a LAN and a VPN has three addresses and cannot
    /// know which one the phone reading its screen shares. The payload
    /// therefore carries all three, in the order the machine ranked them,
    /// and the joiner decides by dialling.
    #[test]
    fn a_qr_carries_every_address_the_device_could_be_reached_at() {
        let offered = addresses(&[
            "192.168.1.20:51837",
            "172.20.0.1:51837",
            "10.8.0.2:51837",
        ]);
        let payload = encode_qr_payload(&[5u8; 16], "0123456789abcdef", &offered);
        assert!(
            payload.ends_with("|192.168.1.20,172.20.0.1,10.8.0.2:51837"),
            "one port, every host, in order: {payload}"
        );
        let decoded = decode_qr_payload(&payload).expect("decode");
        assert_eq!(decoded.addresses, offered, "and every one comes back dialable");
        assert_eq!(
            decoded.address(),
            "192.168.1.20:51837",
            "the first is what a person is shown; the rest are what the joiner tries"
        );

        // The bound is real, and a payload with more hosts than a device
        // could sensibly offer is refused rather than truncated silently.
        let many = addresses(&["10.0.0.1:1", "10.0.0.2:1", "10.0.0.3:1", "10.0.0.4:1", "10.0.0.5:1"]);
        assert_eq!(
            decode_qr_payload(&encode_qr_payload(&[5u8; 16], "0123456789abcdef", &many))
                .expect("decode")
                .addresses
                .len(),
            QR_MAX_HOSTS,
            "the encoder stops at the cap"
        );
        assert!(
            decode_addresses("10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5:1").is_err(),
            "and a payload that got past the encoder is refused by the decoder"
        );
    }

    #[test]
    fn addresses_that_do_not_share_one_listeners_port_are_not_combined() {
        // One listener has one port. A caller that mixed two devices'
        // addresses gets the first device, never an invented pairing of
        // one machine's host with another's port.
        assert_eq!(
            encode_addresses(&addresses(&["10.0.0.1:51837", "10.0.0.2:9999"])),
            "10.0.0.1:51837"
        );
        assert_eq!(encode_addresses(&[]), "");
        assert_eq!(encode_addresses(&addresses(&["nonsense"])), "");
        assert_eq!(
            encode_addresses(&addresses(&["[::1]:51837"])),
            "",
            "the encoder never writes a payload its own decoder would refuse"
        );
        assert_eq!(
            encode_addresses(&addresses(&["10.0.0.1:1", "10.0.0.1:1"])),
            "10.0.0.1:1",
            "and one address said twice is one host"
        );
    }

    #[test]
    fn a_grouped_fingerprint_is_accepted_and_normalised() {
        let payload = encode_qr_payload(
            &[7u8; 16],
            "A1B2-C3D4-E5F6-7890",
            &addresses(&["10.0.0.5:5599"]),
        );
        assert_eq!(
            decode_qr_payload(&payload).expect("decode").fingerprint,
            "a1b2c3d4e5f67890"
        );
    }

    #[test]
    fn every_malformed_payload_is_refused_rather_than_guessed_at() {
        let here = addresses(&["127.0.0.1:5599"]);
        let good = encode_qr_payload(&[1u8; 16], "0123456789abcdef", &here);
        let bad = [
            "".to_string(),
            "https://example.com/definitely-a-qr".to_string(),
            good.replace(QR_PREFIX, "GILUNE-PAIRX"),
            good.replace("|1|", "|2|"),
            good.replace("|1|", "|x|"),
            // A truncated secret, a non-hex secret, and no secret.
            encode_qr_payload(&[1u8; 8], "0123456789abcdef", &here),
            good.replacen(&hex::encode([1u8; 16]), "zz", 1),
            // A fingerprint of the wrong length, and one that is not hex.
            encode_qr_payload(&[1u8; 16], "abc", &here),
            encode_qr_payload(&[1u8; 16], "zzzzzzzzzzzzzzzz", &here),
            // Addresses that are not addresses.
            encode_qr_payload(&[1u8; 16], "0123456789abcdef", &[]),
            encode_qr_payload(&[1u8; 16], "0123456789abcdef", &addresses(&["no-port-here"])),
            encode_qr_payload(&[1u8; 16], "0123456789abcdef", &addresses(&["a b:1"])),
            // Host lists that are not host lists: an empty entry, a port
            // that is not a number, a second colon, and one host too many.
            good.replace("127.0.0.1:5599", "127.0.0.1,:5599"),
            good.replace("127.0.0.1:5599", ",127.0.0.1:5599"),
            good.replace("127.0.0.1:5599", "127.0.0.1:no"),
            good.replace("127.0.0.1:5599", "127.0.0.1:5599:5599"),
            good.replace("127.0.0.1:5599", "a,b,c,d,e:1"),
            good.replace("127.0.0.1:5599", &format!("{}:1", "h".repeat(80))),
            format!("{good}|extra"),
            format!("{QR_PREFIX}|1|{}", hex::encode([1u8; 16])),
            format!("{QR_PREFIX}|1|{}|f|{}", hex::encode([1u8; 16]), "x".repeat(600)),
        ];
        for payload in bad {
            assert!(
                decode_qr_payload(&payload).is_err(),
                "{payload:?} must be refused"
            );
        }
        assert!(decode_qr_payload(&good).is_ok(), "and the good one still works");
    }
}
