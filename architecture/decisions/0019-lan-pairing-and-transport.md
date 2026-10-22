# ADR 0019: LAN Pairing and Transport

Status: **Proposed** (design ratified and build authorised 2026-09-02;
flips to Accepted when § Acceptance gate is evidence)  
Validation: milestone M5, staged M5a–M5c in § 11. The acceptance gate in
§ 10 is the S4-extended scenario set ADR 0008 demands, run against the
production kernel rather than a spike.

This ADR decides the transport half of ADR 0005 and the first transport
of ADR 0008. It does not restate their semantics and it does not change
their status: **those flips are recorded in 0005 and 0008 themselves**,
when this gate passes.

## Context

The kernel writes operations and has never read one. `operations`
(`kernel/crates/gilune-kernel/src/store.rs:241-250`) carries device,
sequence, kind, target and payload; four kinds are recorded; nothing but
`COUNT(*)` reads them. There is no apply path, no frontier table, no
device table and no key material anywhere in `kernel/`. `op_seq` is a
single global counter, correct only while the kernel writes nobody's
operations but its own. `add_revision` deletes every head
(`revisions.rs:65`), so the production kernel has no representation for
the concurrent heads ADR 0005 requires. And `device_id` lives inside the
vault (`store.rs:47-50`), so copying a vault clones a device identity
and its sequence space.

The semantics, by contrast, are proven. Spike S4 demonstrated
convergence across two and three replicas, origin-device processing,
concurrent heads, idempotent re-application, safe halting on sequence
gaps and corrupt operations, interrupted blob transfer, tombstones and
unknown types — ten scenarios, green on Linux and Windows, over a
shared folder. Its `sync.rs` separates cleanly into transport-blind
semantics and relay-specific plumbing.

So M5 is not a semantics milestone. It is the milestone that carries
proven semantics onto a live socket, and everything genuinely new in it
is what a socket adds and a folder did not: a peer who must be
discovered, authenticated, authorised, bounded and revocable.

The owner's constraints for this round, fixed 2026-09-02 and not
reopened here: transport only, full applications on both ends — ADR
0008's headless agent profile is a later round; **full replica** on
every device; the phone syncs on open and foreground plus a periodic
Wi-Fi background job while the desktop is an always-available listener;
pairing offers a **6-digit short code and a QR path, both in v0**;
per-device long-lived identity keys; transport encryption from an
established Rust library; revocation from either device; a device
restored from backup re-enrols as a new identity.

The evidence for the decisions below is
`architecture/research/2026-09-02-lan-sync.md`, kept like a spike report,
with maintenance status and licence checked per crate and every
unverified figure marked.

The named risk this milestone owns is the one S5's threat model gave it:
**LAN impersonation and unpaired access**, whose acceptance tests are
the S4-extended scenarios.

## Decision

### 1. Discovery is DNS-SD, split by platform behind one trait

- The service is **`_gilune._tcp`**, advertised by every device that has
  sync switched on. The listener binds a fixed default port in the
  dynamic range, recorded in the wire contract; if that port is taken it
  takes an ephemeral one, and the actual port is always published in the
  service record, the QR and Settings.
- **The advertisement carries no personal data.** The DNS-SD instance
  name is derived from the device's static-key fingerprint, and the TXT
  record carries the protocol version and that fingerprint and nothing
  else. A paired peer recognises the fingerprint; an observer on a café
  network learns that a Gilune device is present — unavoidable for any
  advertised service — and not whose, nor what it is called. The human
  device name is exchanged inside the encrypted handshake.
- **`PeerDiscovery` is a capability trait** with two implementations:
  - **Rust `mdns-sd`** (0.21.1, `Apache-2.0 OR MIT`), used on desktop
    and by any future headless host. It has no async-runtime dependency,
    so it works from the kernel's synchronous world and from ADR 0008's
    UI-less agent, which has no JVM to borrow an implementation from.
  - **Android `NsdManager` through a Kotlin adapter.** The platform owns
    the multicast socket, so the app needs no `MulticastLock` and no
    `CHANGE_WIFI_MULTICAST_STATE`; battery behaviour is the platform's
    problem rather than ours. This is ADR 0001's rule applied literally
    — Rust does not call a platform API, an adapter implements the
    trait.
- **Discovery is never on the connect path when it can be avoided.** A
  paired peer's last-known address and port are recorded at pairing and
  refreshed after every successful session; a direct unicast connection
  is tried first, and discovery runs only when it fails. This is what
  makes the phone's background job cheap, and it is also the answer to
  a network that filters multicast but routes unicast.
- **The fallback chain is three-deep and the last step is words, not a
  spinner**: cached address, then manual `host:port` entry (the desktop
  shows its addresses and port in Settings, and the QR already carries
  them, so the QR path works where discovery does not), then a
  diagnostic that names AP client isolation as the likely cause.

### 2. The Windows firewall prompt is shaped, not dodged

Windows raises its Security Alert when a program binds a port to listen
for unsolicited inbound traffic; an administrator may allow it from the
dialog and a non-administrator is only told it was blocked. **mDNS does
not avoid this** — it is a second inbound bind, on UDP 5353, not an
escape from the first. ADR 0016 ships a per-user MSI that deliberately
requires no elevation (`0016-...md:83-84`), so there is no installer
moment in which to pre-create a rule.

Therefore:

- The listener and the responder **bind lazily, when the user turns sync
  on**, never at startup. A user who never enables sync never sees the
  prompt, and capture is never behind it.
- The shell explains the prompt immediately before causing it, and asks
  for the **Private profile only**.
- The non-administrator case is a supported, documented state with a
  `netsh advfirewall firewall add rule` line, not a mystery.

### 3. One pairing protocol, two ways to carry its secret

Pairing is a two-phase exchange over a plain TCP connection. It is the
same exchange for both paths; only the secret's entropy differs.

1. **SPAKE2** over the pairing secret, with role-distinguishing identity
   strings so a code cannot be reflected back at its sender. Output: a
   strong shared key `K`.
2. **Noise `XXpsk0`** with `psk = K`. `XX` exchanges both static
   identity keys; `psk0` mixes `K` in before the first message, so the
   handshake completes only if both sides derived the same `K` and no
   handshake message is intelligible without it.

On completion each side stores the peer's static public key, its
human-readable name and its address in the local roster, together with a
freshly derived **pair secret** used as the `psk` for all later sessions.

- **The short-code path**: the listening device generates a 6-digit code
  from a CSPRNG and displays it; the joining device's user types it.
- **The QR path**: the desktop displays a QR carrying protocol version,
  a 128-bit CSPRNG pairing secret, the device's static-key fingerprint
  and its address and port; the phone scans it and connects. Same two
  phases, same code path, a longer password.

**Why a PAKE and not the code as a Noise pre-shared key.** Six digits is
about twenty bits: as a raw PSK, one captured handshake is
brute-forceable offline in seconds. A PAKE makes each guess cost one
online attempt and leaks nothing to grind on. The QR secret is long
enough not to need this; running both paths over one protocol is
cheaper than maintaining two, and it means the QR path inherits the
code path's tests.

**Online guessing is bounded.** A pairing code is single-use, expires
after a short window, and the listener abandons it and stops
advertising a pairing window after a small number of failed attempts.
The user gets a new code, not an unbounded oracle.

**QR is desktop→phone only.** The desktop renders; the phone scans.
Desktop-to-desktop pairing uses the code, which is why the code path can
never be treated as the secondary one.

### 4. Device identity lives outside the vault

- Every device holds a long-lived **X25519 static keypair** — the Noise
  static identity — generated on first use.
- It is stored **device-local, outside the vault**: `%LOCALAPPDATA%\Gilune`
  on Windows, app-private `filesDir` on Android. This is not a
  preference. The vault is a portable artefact that ADR 0008 plans to
  put in Dropbox and on NAS shares and that users back up; an identity
  inside it is a **cloned identity with a shared `op_seq` space**, which
  is precisely the fork the threat model means when it says sequence
  reuse is never legitimate.
- The key file is wrapped by a **`KeyWrap` capability adapter** — DPAPI
  user scope on Windows (JNA is already a desktop dependency), a
  Keystore AES-GCM key on Android, since Keystore cannot export a raw
  X25519 private key. Written in Kotlin, per ADR 0001. Stated honestly:
  this protects the key at rest and from other users, **not from code
  running as the user**, which is what `06-sync-and-security.md` means
  by "platform-protected where available".
- **The roster is device-local too** — peer public key, name, status,
  last-known address. Pairing is pairwise; a roster is not vault state.

**Re-enrolment becomes mechanical rather than remembered.** The vault
gains `meta.identity_pub`, the public key of the device that owns
`meta.device_id`. On open:

| Local identity | `meta.identity_pub` | Action |
| --- | --- | --- |
| present, matches | present | same device, continue |
| present | absent | first M5 run: adopt `device_id`, write the marker |
| absent, or mismatched | present | **the vault came from elsewhere**: mint a new identity and a new `device_id`, start a fresh sequence space |

The restored-from-backup rule is then a consequence of where the key is
kept, not a check someone must remember to run. It also makes an
unsupported configuration visible instead of silently corrupting: two
machines sharing one live vault directory through a file syncer —
already excluded by constitution rule 8 — now re-mint identities at each
other rather than quietly forking one sequence space.

Pairing is bound to identity, not to `device_id`: a peer is recognised
by its static public key, and its `device_id` is a fact it asserts
inside the authenticated channel.

### 5. Transport is Noise over TCP, via `snow`

- **`Noise_XXpsk0_25519_ChaChaPoly_BLAKE2s` for every connection**,
  pairing and session alike. One pattern, one code path, one set of
  tests; the phase differs only in which `psk` is supplied — the PAKE
  output when pairing, the stored pair secret afterwards.
- After the handshake, the session verifies the peer's transmitted
  static key against the pinned roster entry. An unpaired or revoked
  peer cannot complete the handshake at all, because it does not hold
  the pair secret — **default-deny is a property of the handshake, not
  a check performed after one**. It also means an unpaired scanner
  cannot learn a paired device's static identity.
- `snow` 0.10.0, `Apache-2.0 OR MIT`, 26.3 M downloads, tracking Noise
  revision 34, pure-Rust resolver, no async runtime. It has **not been
  formally audited**, and neither has `spake2`; both facts are recorded
  here rather than discovered later. The mitigation is that neither
  construction is invented here: SPAKE2-then-Noise is what
  magic-wormhole ships for this exact problem.
- Messages are length-prefixed frames inside the Noise transport, with
  the protocol version agreed in the handshake prologue so a version
  mismatch fails before any operation is parsed.

### 6. Where the code lives

- **`gilune-kernel::operations`** owns the operation envelope, its
  versioned codec, `record` and **`apply`**, the per-device frontier,
  the gap and corrupt-operation halt policy, and the size bounds on
  apply. Apply writes heads, tombstones and idempotency records — these
  are kernel invariants, and the conventions put invariants in the
  kernel.
- **`gilune-sync`**, a new crate beside it, owns discovery, pairing,
  the Noise session, framing, the exchange loop and quota enforcement.
  It depends on `gilune-kernel`; nothing depends on it but the FFI.
- The seam between them is a **`Transport` trait** — `connect`, `listen`,
  `have_after(device, seq)`, `send_envelope`, `send_chunk` — which is
  what allows the whole S4 scenario suite to run over a **loopback
  socket pair against the production kernel before any radio exists**.
  This is M5a's entire point.
- **Platform adapters only where the OS demands one**: `PeerDiscovery`
  on Android (`NsdManager`), `KeyWrap` on both, background scheduling on
  Android. Everything else is one Rust implementation serving both
  shells, as whisper already is.
- The FFI grows a narrow surface: `sync_status`, `start_pairing`,
  `pairing_qr` (returning the module matrix, § 7), `pair_with_code`,
  `pair_from_qr`, `peers`, `revoke_peer`, `sync_now`, `set_sync_enabled`.
  Errors map onto the existing four flat `GiluneError` variants; the
  boundary gains variants, never reshaped ones.
- **Android bindings are regenerated from an NDK build**, per the
  standing rule and `kernel/scripts/build-android.ps1`.

### 7. QR is encoded in Rust and painted in Compose

The desktop encodes with the `qrcode` crate (0.14.1,
`MIT OR Apache-2.0`, no `image` dependency needed) and hands the module
matrix across the FFI as bits; the shell paints squares on a `Canvas`.
**No new Kotlin dependency, and the pairing secret never crosses the
boundary as a string a shell might log.**

Android scans with **CameraX plus `com.google.zxing:core`**
(Apache-2.0), decoding the analysis frame directly.

- **`CAMERA` is requested at the moment the user taps Scan**, never at
  install, and the 6-digit path is always available — so the camera is a
  convenience and never a condition of syncing.
- ML Kit's Google code scanner would need no camera permission at all,
  which is genuinely attractive; it is declined in § Alternatives
  because it is closed source and hard-requires Google Play services, in
  an app that already excluded OkHttp from a vendor AAR to keep a
  network fetch inside its consent gate.

### 8. Bounds on apply, and what a hostile peer costs

Every bound below **reuses the proven halt policy** rather than inventing
a second failure mode: raise a visible alert, stop that peer's stream,
apply nothing past the offending point, resume from the frontier when
the condition clears. The kernel gains the alert surface the spike
proved and production lacks — silent halting is the failure mode that
would make sync look like it works.

- **Envelope size** is capped in the low megabytes. An operation payload
  is a document, never bytes.
- **Blob length is never trusted as an allocation.** Chunks stream into
  the existing staging path, which already hashes incrementally, and the
  transfer aborts if the bytes exceed the declared length or the digest
  fails. This retires the spike's `Vec::with_capacity(byte_len)` from an
  attacker-supplied manifest (`sync.rs:432`), which was safe over a
  folder and is a one-packet OOM over a socket.
- **A declared length larger than free space is refused before a byte is
  written.**
- **Per-session caps** on operations and bytes end a session cleanly
  rather than truncating it; resumption costs nothing, because that is
  what a frontier is for.
- **Per-peer rate limits** on session establishment, with backoff.
- The numbers are configuration with documented defaults, not constants
  scattered through the exchange loop — one fact, one home.

### 9. Revocation, including mid-session

- Revocation is available in **either device's settings** and takes
  effect locally and immediately: the peer is marked revoked, **any live
  session with it is closed at once**, and subsequent handshakes fail
  because the pair secret is gone.
- **Mid-session revocation cannot half-apply anything.** Apply is one
  transaction per operation, so closing the session stops at the next
  operation boundary; what was applied is applied, what was not is
  fetched again from the frontier if the peer is ever re-paired.
- **Already-applied history is not retracted.** Revocation protects
  what comes next. `06-sync-and-security.md` says so, and the UI must
  say so too rather than implying an undo.
- **A revoked device's operations are refused on apply whoever delivers
  them**, by originating `device_id`. In a full-replica mesh a third
  paired device holds and would otherwise relay them. This is policy,
  not cryptography — see the limitation below — but it makes "a revoked
  device is excluded" testable with three replicas instead of true only
  by accident with two.
- **Re-pairing a revoked device is a new pairing**, and if that device
  was restored from a backup it is a new identity as well (§ 4).

**The v0 limitation, stated plainly.** Operations are authenticated by
the transport, not individually signed, so a paired device could forge
history attributed to another device. That is defensible while every
peer is the same user's own mutually-authenticated hardware and no relay
exists; it stops being defensible the moment an untrusted store holds
operations, which is exactly where the crypto ADR and its external
review already sit as a gate. The door is kept open mechanically: the
**raw received envelope bytes are stored**, not merely the parsed
columns, because a full replica must forward a third device's operations
byte-exactly and any signature added later must verify over the bytes
that were signed.

### 10. Phone scheduling, and the honest cadence

- **Foreground**: a sync runs on app open and on every resume. This is
  the prompt path and the only one the user should feel.
- **Background**: a `PeriodicWorkRequest` with `NetworkType.UNMETERED`
  and `setRequiresBatteryNotLow(true)`. **No charging requirement** —
  it would make the job mean "overnight only" for most users.
- **15 minutes is a floor, not a cadence.** WorkManager clamps anything
  shorter; Doze then batches deferred work into maintenance windows that
  lengthen the longer a device stays idle, and App Standby buckets gate
  it further. The honest statement, and the one the settings screen
  should make: near-15-minute cadence while the phone is in use,
  degrading toward hourly or worse in a pocket overnight.
- **Routine sync is not a foreground service.** Android 15 caps a
  `dataSync` FGS at six hours and Android 16 makes jobs started from an
  FGS obey their runtime quotas; the manifest's own comment already
  records that there is deliberately no boot receiver and no scheduler
  in it, and this decision keeps that posture. If a large first
  synchronisation ever needs to survive the app being backgrounded,
  the sanctioned route is a user-initiated data-transfer job, and it
  needs its own decision.
- **The desktop is the always-available listener** while the app is
  running. Making it survive the app closing is the headless agent
  profile, and that is ADR 0008's later round.

## Alternatives considered

### Custom UDP broadcast discovery

Rejected. It pays every cost DNS-SD pays — the same inbound firewall
bind, the same Android multicast suppression — and forfeits the one
thing that makes Android discovery free, since `NsdManager` speaks
DNS-SD and nothing else. It would also hand-roll conflict resolution,
service metadata and IPv6 behaviour that DNS-SD already specifies.

### One mDNS implementation everywhere (Rust `mdns-sd`, or JmDNS on both)

Rejected. An in-process multicast socket on Android requires
`CHANGE_WIFI_MULTICAST_STATE` and a held `WifiManager.MulticastLock`,
because Android suppresses multicast delivery by default to save
battery — so the "one implementation" saving is bought with a new
permission, a battery cost, and worst behaviour exactly when the screen
is off. Two implementations behind one trait is the smaller price, and
the Rust one is what ADR 0008's future JVM-less agent inherits.

### The 6-digit code as a Noise pre-shared key, with no PAKE

Rejected on arithmetic. Twenty bits of PSK is offline-brute-forceable
from a single captured handshake. The PAKE is the mechanism that makes
a human-transferable secret safe, and it is the reason the short-code
path is a real security boundary rather than a gesture.

### rustls with RFC 7250 raw public keys instead of Noise

The strongest rival, and it has the one advantage the chosen stack
lacks: rustls is audited. Declined because TLS 1.3's external-PSK path
is the awkward home for a PAKE-derived secret and is thinly exercised in
rustls; raw-public-key mutual authentication needs custom verifiers on
both sides, which is precisely where a hand-rolled mistake is silent;
and it pulls a substantially larger tree into a library with a 15 MB
per-ABI budget. Worth revisiting at the crypto ADR, where an external
review exists to make the audit advantage count.

### `iroh` (QUIC connections dialled by public key)

Genuinely mature — 1.1.0 as of 2026-08-25, `MIT OR Apache-2.0`, 2.17 M
downloads — and it solves connection establishment, NAT traversal and
public-key addressing in one dependency. Declined for v0 because it is
built around relay-assisted connectivity and a discovery story that
reaches beyond the LAN, which imports exactly the hosted-infrastructure
assumption ADR 0008 declined to take first; because it answers
addressing and not pairing, so SPAKE2 and the roster would still be
ours; and because its tree is large against the size budget. It is the
obvious candidate the day off-network sync is on the table.

### ML Kit's Google code scanner for QR

The permission-less scan is a real product advantage for an app that
justifies each of its six manifest permissions in a comment. Rejected
because it is closed source and hard-requires Google Play services,
which forfeits any non-Play distribution profile in the S5 matrix and
hands camera frames to a Google process — in a project that removed
OkHttp from a vendor AAR so a model fetch could not escape its consent
gate. ZXing under Apache-2.0 costs a permission prompt we can scope to
the moment of use.

### Identity keys in the vault, or in the kernel store

Rejected, and this is the sharpest rejection in the ADR. The vault is
plaintext by owner decision and portable by design — ADR 0008 plans a
shared-folder transport over third-party stores. An identity inside it
is a cloned identity with a shared sequence space the moment anyone
copies, restores or Dropbox-syncs a vault, which is the exact fork the
threat model prohibits. Device-local storage is the only placement that
makes the re-enrolment rule automatic rather than aspirational.

### Syncing the pairing roster as operations

Rejected for v0. It looks like it would make revocation propagate, but
without per-operation signatures a synced roster is an unauthenticated
assertion, and a revoked device restored from a backup would arrive
carrying its own pairing claims. Pairwise rosters plus refusal-on-apply
by originating device (§ 9) get the testable property without the
false assurance. A synced, signed device registry belongs with the
crypto ADR.

## Consequences

- **The production kernel gains its read side.** Schema v5 adds the
  per-device frontier, the raw-envelope column, `meta.identity_pub` and
  the integrity-alert surface; `op_seq` becomes per-device; and
  `add_revision` must stop deleting every head, because concurrent heads
  are ADR 0005's contract and the production kernel has never
  represented them. That last change is the largest single piece of M5a
  and it touches the most-exercised path in the kernel.
- **The operation envelope becomes a versioned public contract** with
  portable test vectors, which ADR 0005's gate requires and which
  nothing today provides. Because production operations have never been
  applied anywhere, M5a is free to reconcile the production kind names
  with the spike's — once, now, behind a migration, and never again.
- **The kernel takes its first network dependencies** — `mdns-sd`,
  `snow`, `spake2`, `qrcode` — where it previously had none, and its
  size budget must be re-measured per ABI rather than assumed. All four
  are `MIT OR Apache-2.0` or dual-equivalent and clear ADR 0009.
- **Two of those crates are unaudited** (`spake2`, `snow`). Recorded
  here, carried into the crypto ADR's external review, and not
  discovered by a reviewer later.
- **Android gains `CAMERA`** (requested at use, for QR only) and the
  desktop gains an inbound firewall rule the user must allow. Both are
  new consent surfaces and both belong in the S5 distribution matrix.
- **First run after M5 mints or adopts an identity**, and a vault opened
  on a machine with no matching local identity becomes a new device.
  That is the re-enrolment rule working, and the UI must say so plainly
  when it happens rather than letting a user wonder why their laptop
  appears twice.
- **The user docs gain a sync guide**, in user vocabulary — no kernel,
  no frontiers, no operations — and the pairing screenshots are shot
  through the probe tooling like every other guide.
- ADR 0008's shared-folder transport becomes cheap once the `Transport`
  trait exists: the spike's relay is already an implementation of it.

## Acceptance gate

M5 is not complete and this ADR is not Accepted until the following are
evidence, not assertions. Scenarios A run over a loopback socket; B over
two processes on one machine; C between a real Windows desktop and a
real Android device on a real network.

**A — semantics survive the move to a socket (M5a)**

1. All ten S4 scenarios pass against the **production** kernel over the
   loopback transport: two- and three-replica convergence, origin-device
   processing, concurrent heads preserved, idempotent re-application and
   late joiners, sequence gap halting and repairing, corrupt operation
   halting with nothing half-applied, interrupted blob transfer
   completing byte-identically, tombstone propagation, unknown-type
   round-trip.
2. Reordered and duplicated deliveries converge; a deleted-and-restored
   object converges; an offline-created object converges.
3. Portable operation test vectors exist and a decoder rejects every
   malformed and out-of-bounds vector in the set.
4. Every bound in § 8 is exercised: an oversized envelope, an oversized
   blob, a manifest declaring more than free space, and a session cap
   each halt that peer visibly, apply nothing past the fault, and resume
   correctly when the condition clears.

**B — pairing is a real boundary (M5b)**

5. Two devices pair by 6-digit code and by QR, and both paths produce
   the same roster state.
6. A wrong code fails, leaks nothing, and is exhausted after the attempt
   limit; a code is single-use and expires.
7. An unpaired device is refused — and cannot complete the handshake
   far enough to learn the listener's static identity.
8. A revoked device is refused; revoking mid-session closes the session
   at an operation boundary with nothing half-applied; a third replica
   refuses to apply a revoked device's operations relayed to it.
9. A vault copied to a machine with no local identity re-enrols as a new
   device with a fresh sequence space, and the original device is
   unaffected.
10. The identity key is wrapped by the platform adapter on both
    platforms and survives an application update (ADR 0016's gate
    already requires device-local state to survive; this joins it).

**C — it works on a real network (M5c)**

11. **The ADR 0008 six**: two devices discover each other, pair, sync and
    converge over LAN **with no relay present**; an unpaired device is
    refused; a revoked device is excluded; sync resumes correctly when
    devices rejoin the network.
12. Discovery failure degrades as designed: cached address, manual
    `host:port`, then a diagnostic naming AP isolation — demonstrated on
    a network with client isolation enabled.
13. The Windows firewall prompt appears when sync is switched on and not
    before; capture and startup are never behind it; the
    non-administrator path is documented and works.
14. The phone syncs on foreground within seconds, and a periodic
    background job is observed to run under `UNMETERED`; the measured
    real-world cadence is recorded honestly rather than quoted as
    fifteen minutes.
15. A capture made on the phone is transcribed on the phone, syncs to
    the desktop, and projects byte-identically — the P0 loop across two
    devices.
16. The standing budget is met: **"LAN sync of 100 small operations
    between two idle devices: under 5 s"**
    (`architecture/spikes/README.md:37`). *Idle* puts discovery and the
    handshake inside the five seconds, which is the measurement that
    decides whether the cached-address-first rule of § 1 is an
    optimisation or a requirement.

**What M5 does not close.** ADR 0005's gate also names a **key-epoch
change**, and v0 has no content-encryption keys at all (S5 threat model,
decision 4), so that scenario stays with the crypto ADR: **0005 cannot
flip to Accepted on M5 alone; M5 validates its LAN half.** ADR 0008's
gate additionally requires **S1 to demonstrate a headless kernel host on
Windows**, which the owner has scoped out of this round; 0008 flips when
that lands beside these scenarios. Neither status change is recorded
here.

## Staging

Each stage is shippable alone and in order.

- **M5a — operations become bidirectional.** No network. Schema v5;
  `gilune-kernel::operations` with apply, per-device sequences,
  per-device frontiers, the raw-envelope column, the versioned codec and
  its test vectors, the bounds of § 8 and the alert surface; concurrent
  heads in `add_revision`; the `Transport` trait and a loopback
  implementation; the whole S4 suite re-run against production. This is
  the largest stage and the only one that changes the most-exercised
  path in the kernel. Gate A.
- **M5b — identity, pairing, crypto.** The device-local identity store
  and `KeyWrap` adapters; `meta.identity_pub` and the re-enrolment
  table; SPAKE2 and the Noise session; the roster, revocation and the
  bounds on guessing; QR encode in Rust and scan on Android; the FFI
  surface and regenerated Android bindings. Two processes, one machine,
  no discovery. Gate B.
- **M5c — the live exchange.** `PeerDiscovery` on both platforms; the
  cached-address and manual fallbacks; the Windows firewall UX; desktop
  Settings ▸ Sync and the Android equivalent; foreground sync on resume
  and the periodic worker; the user docs sync guide. Gate C, on real
  hardware and a real network.

## Amendments

- **2026-09-03 (M5b, coordinator-accepted refinement).** § 4 says one
  identity per device; the implementation keys the identity file inside
  the device-local store **by the vault's `device_id`** — one identity
  per (machine, vault). Reasons: one machine can hold two unrelated
  vaults, and a literal one-per-device reading would re-mint on opening
  the second, silently dropping the first's pairings; and keying by
  `device_id` means *moving* a vault on the same machine is not
  mistaken for copying it. The recorded cost: a vault **copied** to a
  second directory on the same machine keeps its `device_id`, finds
  the same identity file, and is not detected as a fork — a
  configuration constitution rule 8 already excludes, now stated
  rather than discovered. Re-minting on a foreign machine additionally
  seeds the old device's frontier at what the vault holds, without
  which the first session with the original would halt on operations
  both sides already have.

- **2026-09-03 (post-delivery review, kernel).** A high-effort review of
  the M5 delivery confirmed defects in exactly the areas § Context named
  as new, and the fixes amend the decision as follows.
  - **Absent referents halt one stream, never the session.** Per-device
    sequences carry no causal parents, and pull orders streams by
    `device_id`, so an `object-revised`, a `tombstone`, or a `supersedes`
    name can arrive before the object or head it refers to. § 8's halt
    policy now covers it: the apply arm halts that stream with kind
    **`sync-awaiting-referent`** (subject = the missing id), frontier
    unmoved, nothing half-applied; the next pass — after the other
    device's stream has delivered the referent — resumes and clears it.
    Before this, the revision case killed the session with no alert
    (a foreign-key error, not a halt) and the other two applied silently
    and diverged replicas for good.
  - **`SyncHalted` carries `{kind, subject, reason}`.** Every refusal
    inside the apply transaction names its own alert kind; the outer
    handler republishes what it was given. Malformed envelopes and blob
    declarations, including a vault-escaping path, are
    `sync-corrupt-operation`; only size and free-space refusals are
    `sync-limit-exceeded`. Before this every in-transaction halt was
    relabelled as a size limit with its subject overwritten.
  - **A synced tombstone never deletes a locally edited file.** An
    external edit to a deleted object is the person keeping the note:
    the file stays, is offered as an import, and alert
    **`vault-deleted-note-edited`** says so; `withdraw_tombstoned`
    additionally removes only bytes equal to what the kernel would render
    now, so an ingested edit's hash can no longer authorise a deletion.
  - **Zero-length blobs complete cleanly** (an empty recording is a
    legitimate capture), and blob-chunk refusals take the one halt path
    with an alert, halting the transfer rather than the session.
  - **The `![[name]]` placeholder is only for content still crossing
    from another device** (`blobs.origin = 'sync'`, not yet ready); a
    local read failure holds the object back for that pass instead of
    projecting a body reconcile would later ingest.
- **2026-09-03 (post-delivery review, transport).**
  - **The listener times out an unfinished handshake.** § 8's bounds
    gain a pre-handshake read/write timeout on every accepted socket (the
    dialling side always had one), TCP keepalive on established
    sessions, and a rule that a handshake never finished counts as a
    failure for the caller's address under the per-peer rate limit.
    Before this, eight silent connections from any host on the LAN —
    unauthenticated, before the handshake — held the session cap until
    the process restarted, and a silent caller never registered with the
    rate limiter at all.
  - **The published address is one a peer can dial.** § 1's "actual port
    is always published" now includes the host: when bound to an
    unspecified address the service publishes a routable interface
    address (never unspecified, loopback or link-local while anything
    routable exists) to the pairing window, the QR and Settings; an
    explicit bind is published verbatim. Before this the QR carried
    `0.0.0.0:<port>`, so the QR and typed-address pairing legs failed on
    real hardware while only the discovery leg worked.
  - **The refusal set is durable vault state** (§ 9). Revocation adds to
    it, re-pairing is the only removal, and `configure` reconciles the
    roster's revoked ids *into* it rather than replacing it — so
    re-enrolment on a new machine, which mints a new identity and finds
    no roster, no longer silently re-admits every revoked device and
    clears the alerts that would have said so.
  - **Revocation and sync-off stop outbound sessions too.** The session
    this device dialled checks permission at every operation boundary,
    mirroring the listener's rule; before this only the inbound direction
    was gated and tested.
  - **The roster is edited under a file lock** with reload-merge-write,
    because the activity and the background worker each hold a
    `SyncService` over one roster file and a whole-file save from a stale
    snapshot could discard a pairing made moments earlier.
  - One browse per sync round, shared across unreachable peers; a
    responder whose registration was not confirmed is retried on the
    next round rather than left down.
- **2026-09-03 (post-delivery review, shells).**
  - **Device-local sync state is excluded from Android backup and
    device-to-device transfer** (`dataExtractionRules` on API 31+,
    `fullBackupContent` below). § 4 promised that a restored phone
    becomes a new device *mechanically*; on Android the default backup
    policy carried `filesDir/sync/` — the sealed identity and roster —
    to the new phone while the Keystore key that seals them cannot
    travel, so the identity was neither absent (re-mint) nor readable
    (continue): sync was permanently "not set up" with no recovery short
    of clearing app data. Excluding the directory makes the identity
    absent on the new phone, which is the re-enrolment row § 4 intended.
    For phones already in the stuck state the Sync screen offers "Reset
    sync on this phone" — deletes the device-local sync state, re-enrols
    as a new device, deletes no vault data, and says peers must re-pair.
  - **The integrity-alert wording lives in one shared table** for both
    shells, with the "is this damage" classification beside it, so a new
    kernel kind is worded once and an unknown kind cannot be announced
    as a stopped sync by one shell and a benign wait by the other.
  - The desktop Sync section loads alerts on entry (a halted stream no
    longer looks idle after a relaunch); the background worker consults
    the persisted sync-enabled state before dialling anyone; vault open
    no longer waits behind the responder's registration; a sync that
    applied nothing skips the full re-projection.
- **2026-09-03 (second review, kernel).** The first fix round was
  tested against each defect's own scenario and not the adjacent one;
  a second review found the regressions and these amendments are the
  corrections. Rule adopted for every later round: a fix ships with
  its second-order test or it is not done.
  - **A referent that can never arrive is not waited for.** Naming
    operations now carry the origin device of what they name
    (`object_origin_device`; `supersedes_origin` per superseded head) —
    additive canonical-JSON keys, codec version unchanged, two new
    vectors, the existing thirty-seven byte-identical. On apply an
    absent referent is one of three things: **awaited** (not yet
    received from a device this vault still exchanges with — halts the
    stream as `sync-awaiting-referent`, as before); **refused** (its
    origin is a revoked device — the naming operation is applied
    forward-only, frontier advanced, alert `sync-referent-refused`, not
    damage, withdrawn if that history is ever re-admitted); or
    **uninterpreted** (carried by a kind this build cannot read —
    forward-only under `sync-unknown-op-kind`'s existing rule). Before
    this, an innocent device's stream wedged permanently at the first
    operation naming a revoked device's object, while the shell called
    it "nothing is wrong".
  - **A tombstone names the heads it saw**, exactly as a revision does.
    § 9's withdrawal rule is now two facts, neither of which is a
    re-render: the bytes on disk are the bytes the kernel last wrote,
    and every local head is among the heads the deleter named. A local
    head the deleter never saw is a concurrent edit and the file is
    kept. Before this, the first fix round's "equal to what the kernel
    would render now" check orphaned every note that was revised and
    then deleted between two projections — a deletion made elsewhere
    silently never landed.
  - **A note whose own text file cannot be read is said**, as
    `vault-content-unreadable` (not damage; cleared when it reads
    again) — the placeholder link stays reserved for content still
    crossing from another device. `vault-deleted-note-edited` is
    withdrawn when the kept file is deleted or its words come back as
    something new; resolution is observed per reconcile pass rather
    than recorded, because nothing in the kernel yet turns an import
    into an object.
  - **A processor output may name the head it read.** `ProposedOutput`
    gains an optional expected head and the claim exposes it; a
    revise-subject output whose expected head has moved is refused, for
    whichever route submits it. The provider-local "never overwrite
    existing words" gate becomes a kernel compare-and-set.
  - **The kernel says which alerts are damage** (`IntegrityAlert.damage`)
    and how many jobs are parked (`GiluneStatus.jobs_parked`, the
    subset of pending whose subscription is off); an opt-in plugin can
    be registered disabled in one call; an identity file the platform
    key cannot open is a typed boundary error. Shells stop restating
    kernel facts as string tables and prose matches.
  - A session's `blobs_pending` is what that session left unfinished
    (the store-wide count is `blobs_awaiting` on status); `promote` is
    one function with one already-exists branch; every blob digest is
    streamed. The development stand-in processor is `external-interpret`
    — the kernel names no domain type.
- **2026-09-03 (second review, transport).**
  - **Every reachable address is published, ranked, and the joiner
    dials them in turn.** § 1 no longer publishes one guess: the
    pairing window, Settings and the QR carry every routable candidate
    (QR field five is `h1,h2,h3:port`, at most four hosts, one port; a
    single host is byte-identical to the old shape), enumerated once at
    bind, and the joiner tries each with a short timeout, recording the
    one that answered. The first fix round's "first routable address"
    picked the numerically lowest — on a machine with a Hyper-V, WSL or
    VPN adapter, the one no phone can dial.
  - **One address classifier for both sides** (`addressing.rs`): same
    LAN segment as one of this machine's own addresses, then routable,
    then link-local, then loopback; unusable addresses (unspecified,
    multicast, broadcast, documentation) are dropped rather than sorted
    last. Discovery's resolved-peer ranking uses it, so a multi-homed
    peer is dialled on the shared segment first and a bogus address in
    a responder's answer is never dialled.
  - **A device remembers the identities it used before**, and a
    pairing forgives them. Re-enrolment records the previous
    `device_id` (chaining when the same machine re-enrols twice, at
    most eight, newest first); the encrypted greeting carries them; a
    successful pairing withdraws the refusal of every name the peer
    answers to, and the roster retires any revoked row claiming any of
    them. Nothing ever *adds* a refusal from a greeting. Before this,
    re-pairing a device that had re-enrolled was undone at the next
    launch by the roster reconcile, and its earlier history stayed
    refused with a damage banner on every session.
  - **`sync_now` honours the persisted switch itself**: with sync
    switched off it dials nobody and reports nothing, whoever calls it.
    The outbound-permission flag is initialised from the persisted
    state at configure and is now a cache of that fact, not a second
    one every entry point had to remember.
  - One sealed-file envelope (`SealedFile`) for identity and roster,
    one field name, reading the two previous layouts so an existing
    installation keeps its key and its pairings; a roster the platform
    key cannot open surfaces as the same typed identity-unreadable
    error as a key. One handshake tail (`Handshaken::establish`) so no
    flow can forget the timeout swap. Test rig: the paired-device port
    band moved out of the OS ephemeral range, which had let a client
    socket squat a port a device was about to restart on.
- **2026-09-03 (third review; one surgical round, simplest-safe rules
  replacing the second round's inferences).**
  - **A claimed earlier identity is recorded, never believed.** The
    second round let a greeting's `previous_device_ids` un-refuse every
    name it listed — so any device the user paired with could lift the
    revocation on any other device by claiming to have been it. Now a
    pairing un-refuses only the peer's own current `device_id` (the
    user re-pairing *this* device is a decision about this device);
    each claimed earlier id raises `sync-previous-identity-claimed`
    (not damage) and is re-admitted only by an explicit action,
    `readmit_device`, which is durable vault state that a later
    revocation overrules. The roster likewise retires a revoked row
    only for the name the peer paired as. The honest re-enrolment case
    still works; it costs one deliberate tap by the person who knows
    whether that really was their old machine.
  - **A tombstone without named heads withdraws a file only when
    nothing could have moved**: the object has exactly one head, it is
    the revision the projection was rendered from, the bytes on disk are
    the kernel's, and that head is not a local edit. The second round's
    heuristic read the origin of a revision the projection had frozen on
    once the object was deleted, and could delete a file holding another
    device's already-synced edit. Keyless tombstones come from live code
    (the transcript merge, pre-v5 adoption), so the merge now builds its
    tombstone with the shared payload builder and names its heads.
  - **A referent is "carried by something this build cannot read" only
    when the unreadable operation comes from the device the naming
    operation is about** (its origin key, else the device that delivered
    it). An unrelated device's unknown-kind operation against the same
    id is not evidence; the referent is awaited. The second round's
    rule forwarded — and lost — a legitimate revision on that evidence.
  - **A processor output refused because its subject moved is an
    outcome, not an error**: the job completes with nothing to write,
    the pass continues, and the count is `skipped_moved`. A processor
    reads the head and the document from one row in one query, so the
    pair it hands back can never straddle a write.
  - Schema v6 indexes `operations(target_id)`; the processor WIT gains
    the optional `expected-head`; the joiner's rate limiter keys the
    whole attempt by the fingerprint the pairing names.

- **2026-10-22 (native Windows install).** § 2's premise — "there is no
  installer moment in which to pre-create a rule" — no longer holds. The
  per-user MSI it refers to was replaced by the per-machine, elevated MSI
  built on Linux (`.github/linux-windows-build.md`), and that installer
  now uses the moment: it adds an inbound Windows Firewall rule named
  `Gilune` for the installed `Gilune.exe`, **Private profile only**, as a
  deferred, non-impersonated `netsh` custom action. Install, repair and
  upgrade delete-then-add it (netsh would otherwise duplicate the rule),
  and first delete every rule Windows holds for the launcher or for the
  earlier launcher's `javaw.exe`, by program: a Block row left by a
  dismissed prompt on 1.1.0 beats any Allow, and did on the owner's
  notebook. The install owns every rule for its programs from here on;
  uninstall deletes it, and a product being replaced by a newer one leaves
  it alone so the order of `RemoveExistingProducts` cannot remove what the
  newer product just added. A netsh failure never fails the install; it
  only returns the user to the prompt. The prompt is therefore the
  fallback — it means the install did not add the rule — not the expected
  first-run event, and gate 13 is read accordingly: no prompt on an
  installed app with sync switched on, capture and startup still never
  behind the bind, and the non-administrator path needing no administrator
  at all; the `netsh` line stays in the guide for the fallback and names
  `C:\Program Files\Gilune\Gilune.exe`. Lazy binding is unchanged. The
  launcher also hosts the Java runtime in its own process now, so the
  socket's owner, the rule's program and any remaining prompt all name
  Gilune rather than `javaw.exe` ("OpenJDK Platform binary"). The per-user
  DEB is untouched; nothing on Linux adds a rule.

## Ratification record

Ratified by the owner on 2026-09-02, as proposed, with no
modifications — following the four scope decisions fixed earlier the
same day and embedded in § Context as constraints (transport only;
full replica; on-open plus background scheduling; both pairing paths
in v0 — the QR path being the owner's amendment to the coordinator's
recommendation to defer it). Ratification authorises the M5 build;
per this ADR's own gate, Status flips to Accepted only when § 10 is
evidence, and the status of ADRs 0005 and 0008 is recorded in those
files when their own gates pass. Gate C's real-device scenarios are
expected to wait on the owner acquiring an Android device, in the
same pattern as ADR 0001's assumed budgets and spike MS2.
