# Sync and Security

Status: **Draft v0**

The target is single-user, multi-device, local-first sync. Sharing between users
is a future feature and must not be smuggled into v0 by overloading device sync.

## Trust model

- A user's enrolled devices are authorised replicas.
- A hosted relay is assumed to be curious or compromisable and should not need
  plaintext user content.
- Plugins are untrusted unless explicitly installed in a trusted execution
  profile.
- Network and blob providers can fail, reorder, duplicate, delay, or omit data.
- A lost or malicious device can retain anything it legitimately decrypted
  before revocation; revocation protects subsequent key epochs and operations.

## Sync unit

Devices exchange immutable, authenticated operation envelopes and blob chunks,
not database pages.

An operation conceptually includes:

```text
protocol version
user/account scope
device ID and monotonic device sequence
operation ID
causal parents or compact frontier
target object/relationship/blob metadata ID
operation kind and schema identifier
encrypted payload
integrity/authentication data
```

The exact encoding is an ADR-level decision. Its semantic meaning is owned by the
kernel and covered by cross-implementation test vectors.

## Sync cycle

1. The kernel commits local revisions and their operations atomically.
2. A transport requests encrypted operations after its acknowledged frontier.
3. The receiving side validates identity, sequence, integrity, protocol, and
   payload bounds before application.
4. Operations are applied idempotently, preserving unknown schemas and concurrent
   heads.
5. Local factual events describe newly applied remote state. Processing policy
   decides whether any subscriber runs on that device.
6. Acknowledged frontiers are recorded per device and used for compaction and
   deletion eligibility.

A transport can be HTTP polling, WebSocket, peer-to-peer, removable storage, or
another mechanism without changing operation semantics. The first supported
transport is LAN peer-to-peer between paired devices, optionally through a
headless desktop sync agent (ADR 0008). A shared-folder transport over
third-party file stores and a hosted relay are planned, optional successors.

## Conflict policy

The kernel does not apply general wall-clock last-write-wins.

- Immutable source creation naturally converges by ID.
- Additive relationships converge as independently identified records.
- Deletion is an explicit tombstone operation with defined precedence and
  possible restoration as a new revision.
- Concurrent object revisions remain multiple heads.
- A type owner may supply a deterministic merge that consumes all heads and
  emits a new revision.
- Without the type owner, all heads remain available for later merge or human
  selection.

CRDTs may be adopted for individual high-value types after their interaction
semantics are understood. “Use a CRDT everywhere” is not a v0 decision.

## Processing across devices

Events and job attempts are operational state and are not blindly replayed as
user data on every device. Syncable processing outputs are normal objects with
provenance and operations.

The default origin-device policy and deterministic processing idempotency key
prevent duplicate transcript/task generation. Hosted or elected-coordinator work
uses a claim/lease operation visible to participating devices. Concurrent work
may still occur during partitions, so identical logical processing targets the
same output identity and preserves conflicting heads rather than creating hidden
duplicates.

Local indexes and caches use `every-device` processing and do not sync their
derived storage.

## Encryption target

Hosted sync should be end-to-end encrypted by default:

- the service stores ciphertext operations and ciphertext blob chunks;
- device and account authentication is distinct from content-encryption keys;
- each user has a versioned root/key-encryption hierarchy;
- content uses per-object or per-blob data keys wrapped by the current user key;
- device enrollment grants access through an authenticated existing-device or
  recovery flow;
- revocation advances the key epoch for future writes;
- encryption includes type/length padding policy review because metadata can be
  sensitive even when payloads are encrypted.

The cryptographic construction and recovery UX are deliberately unresolved until
the sync spike and an external security review. Custom cryptographic primitives
are prohibited; the implementation uses established audited libraries and
documented constructions.

## Device identity

Every device has a hardware/platform-protected signing identity where available.
Enrollment binds its public identity to the user's account and current key epoch.

The kernel distinguishes:

- human-readable device name;
- stable device ID;
- signing/encryption key versions;
- last acknowledged operation frontier;
- status: pending, active, revoked, or retired.

Device clocks are display input only. Monotonic sequences and causal parents
drive operation validation and convergence.

## Deletion and garbage collection

Tombstones sync like other operations. Physical content removal is safe only
after the relevant active devices have acknowledged the tombstone or a published
retention/retirement rule has expired.

The hosted service must support deletion of ciphertext and account metadata, but
cannot guarantee erasure from a previously authorised offline device. The user
interface must describe this limitation honestly.

Blob garbage collection considers:

- live local and synced references;
- tombstone causal position;
- device acknowledgement frontiers;
- configured recovery/backup retention;
- active transfers and staged writes.

## Server responsibilities

A relay is an optional transport, not a v0 requirement (ADR 0008). When one is
offered, the minimum hosted relay owns:

- account authentication and rate limiting;
- device enrollment/revocation metadata;
- opaque operation and encrypted blob storage;
- frontier/availability queries and bounded retention;
- optional coordination leases for explicitly hosted processing;
- abuse prevention, audit events, and operational backup of ciphertext.

It does not own object schemas, plaintext search, domain merging, or the only
copy of user data.

## Security observability

Security-relevant actions produce redacted audit records: plugin installation,
capability grant/revocation, device enrollment/revocation, secret use category,
signature failure, corrupt operation, migration failure, and repeated sandbox
limit violations.

Audit records never contain source payloads, raw secrets, model prompts, or
decrypted blob bytes.

## Required threat-model follow-up

Before public hosted sync or third-party plugin installation, perform a formal
threat-model review covering:

- malicious plugin and compromised publisher;
- compromised relay and metadata correlation;
- malicious or rolled-back device;
- operation replay, truncation, fork, and resource-exhaustion attacks;
- secret exfiltration through allowed network capabilities;
- unsafe schema/migration payloads;
- export and backup leakage;
- recovery-key loss and social-engineering paths.

