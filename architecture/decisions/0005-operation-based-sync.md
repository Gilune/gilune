# ADR 0005: Operation-Based Sync

Status: **Proposed**  
Validation: Spike S4

## Context

Devices work offline and may modify the same objects concurrently. Plugins can
introduce schemas unknown to other devices. The hosted server should be cheap,
self-hostable, and unable to become the owner of the only intelligible copy.

Sync semantics affect IDs, revisions, deletion, processing ownership, encryption,
and every future schema, so they cannot be deferred to a transport plugin.

## Proposed decision

- The kernel emits immutable operations with device identity, monotonic sequence,
  causal parents/frontier, target, kind, and authenticated payload.
- Transports exchange operations and blobs after acknowledged frontiers.
- Application is idempotent and preserves concurrent heads by default.
- Domain plugins may add deterministic merge functions; unknown types remain
  opaque and syncable.
- The hosted relay stores opaque end-to-end encrypted operation payloads and blob
  chunks.
- Do not adopt a universal CRDT or wall-clock last-write-wins rule for v0.
- Keep the wire encoding and server implementation behind the stable semantic
  contract and test vectors.

## Alternatives considered

### Copy/synchronise SQLite files or WAL pages

This couples sync to local storage layout, cannot safely merge independent
offline writers, and makes unknown partial transfer and encryption brittle.

### Server-authoritative rows

Simplifies central conflict handling but weakens offline authority, self-hosting
portability, and end-to-end encryption.

### CRDT for every object

Promises automatic convergence but imposes interaction semantics before domain
types are understood and may retain unexpected history. Targeted CRDTs remain an
option for specific schemas.

### Git-like whole-document commits

Strong history and merging model, but large binary transfer and fine-grained
mobile state require additional protocols, and plugin-defined merge remains.

## Consequences

- Operation and snapshot formats require compatibility and adversarial parsers.
- Causal metadata and active-device frontiers must be compacted.
- Multi-device deletion is delayed until acknowledgement/retention permits
  physical erasure.
- Key enrollment, recovery, rotation, and revoked-device behaviour become product
  decisions before hosted sync launches.
- Server framework and database remain replaceable as long as they preserve the
  relay contract.

## Acceptance gate

S4 must demonstrate convergence with two and then three replicas under offline
creation, concurrent revision, deletion/restoration, reordered and duplicate
operations, an unknown schema, large interrupted blob transfer, a retired device,
and a key-epoch change. It must also produce portable protocol test vectors.

