# Architecture Validation Spikes

Status: **Planned**

These are disposable, evidence-producing prototypes. Their purpose is to reject
bad foundational choices cheaply. Production-quality feature work is not part of
the spike unless it is necessary to test an invariant.

Each spike produces:

- runnable code and automated conformance/fault tests;
- recorded platforms, dependency versions, and build instructions;
- measurements and observed failure modes;
- a short result: **pass**, **pass with changes**, or **fail**;
- updates to the affected ADRs and open questions.

## Recommended order

1. S1 — Kernel boundary and cross-platform build
2. S2 — Durable capture, events, and recovery
3. S3 — Plugin contract, sandbox, and lifecycle
4. S4 — Offline sync, conflicts, and encrypted relay
5. S5 — Distribution-policy and threat-model review

S1 and the contract-only portion of S3 may overlap. S4 should reuse the data and
operation model proved by S2, not invent a parallel representation.

## Initial targets

Proposed starting budgets, to be adjusted with evidence at each spike kickoff
rather than left as “acceptable to the team”:

- capture commit (stop of input → durably committed and recoverable): under
  200 ms on a mid-range Android device;
- cold start to capture-ready: under 1.5 s on the same device;
- kernel contribution to release package size: under 15 MB per ABI;
- LAN sync of 100 small operations between two idle devices: under 5 s.

## S1 — Kernel boundary and cross-platform build

### Decisions tested

- ADR 0001: Compose Multiplatform shell and Rust kernel
- feasibility assumptions in ADR 0003

### Build

A minimal Compose application runs on a physical Android device and Windows. It
opens a Rust kernel, performs a transaction, streams a large file into and out of
the boundary, subscribes to a wake-up/event notification, and reopens the kernel
from a platform background worker.

The boundary includes representative records, optional values, typed errors,
async work, cancellation, streaming, and callback/event delivery. It does not
include domain UI beyond a diagnostic screen. The same kernel also runs in a
headless host on Windows to validate the sync-agent profile (ADR 0008).

### Faults to inject

- Rust panic and structured kernel error;
- cancelled Kotlin coroutine while Rust work is active;
- Android activity recreation and process death;
- background worker restart;
- missing or incompatible native library;
- a large stream with a deliberately slow consumer.

### Measure

- clean and incremental build time;
- debug/release package-size contribution;
- cold initialisation and first-call latency;
- stream throughput and peak memory;
- crash symbolication and diagnostic quality;
- binding-code complexity and unsafe-code surface;
- CI/cross-compilation and developer setup burden.

### Pass condition

The integration has predictable ownership/cancellation semantics, streams without
whole-file copies, yields useful cross-boundary diagnostics, and can be built and
debugged routinely. The recorded cost must be acceptable to the team, not merely
technically possible.

### Failure response

Prototype a Kotlin Multiplatform kernel implementing the same small contract.
Compare the actual complexity and supersede ADR 0001 if the Rust benefit does not
justify its operational cost.

## S2 — Durable capture, events, and recovery

### Decisions tested

- ADR 0002: SQLite plus external blob storage
- ADR 0004: transactional events and at-least-once jobs
- data and processing contracts

### Build

A single-action diagnostic capture writes an audio/file stream as a source. A
fake transcription processor consumes `source-ready`, emits a transcript, and a
fake task processor emits a proposal. A generic viewer and export command can
show all envelopes and provenance with both processors disabled.

All output IDs derive from documented idempotency inputs. The fake processors
are deterministic so recovery failures are easy to distinguish from provider
variation.

The committed capture, transcript, and proposal are projected into a Markdown
vault with front matter (ADR 0007), and the export command copies the vault
plus its integrity manifest.

### Termination matrix

Terminate or power-fail the process:

- while staging blob bytes;
- after bytes flush but before database commit;
- during the source/event/operation commit;
- after commit but before blob promotion;
- after event commit but before job claim;
- while a job lease is active;
- after external work but before output commit;
- during output/provenance commit;
- during vault projection writes and external-edit reconciliation;
- during migration and database checkpoint.

Run each point repeatedly with duplicate, delayed, and reordered job delivery.

### Pass condition

- Anything reported saved has recoverable original bytes and envelope metadata.
- Incomplete stages are recoverable or safely removed without hiding a committed
  source.
- Every committed event eventually has its jobs.
- Reprocessing produces the same logical outputs and no duplicate user-visible
  objects.
- Capture and export continue while processors are disabled or quarantined.
- Deleting the Markdown projection and rebuilding it produces an equivalent
  vault.
- External edit, rename, delete, and malformed front-matter cases reconcile
  without losing kernel history.
- Integrity failures are visible and do not trigger destructive “repair.”

### Required artefacts

- crash-state transition diagram;
- property/fault tests for every termination point;
- initial physical schema and migration fixture;
- vault layout and front-matter format draft;
- export example (a vault copy) and integrity manifest;
- decision on canonical JSON versus an immediate CBOR spike.

## S3 — Plugin contract, sandbox, and lifecycle

### Decisions tested

- ADR 0003: WIT and WebAssembly component execution
- ADR 0006: bundled mobile contract parity and declarative UI
- plugin and compatibility contracts

### Build

Implement one realistic sample plugin that:

- owns a `sample/task` schema;
- reads transcript objects;
- creates task proposals with provenance;
- requests a scoped fake network provider and opaque secret;
- subscribes through origin-device durable jobs;
- contributes a command, detail card, and settings form;
- migrates one payload schema version.

Run it as a sandboxed component on Windows and as a bundled implementation on
Android from the same contract fixtures.

### Adversarial cases

- deny every capability individually;
- ask for an undeclared type, event, secret, file, and network destination;
- return malformed, oversized, and unknown-version values;
- loop on its own events;
- exhaust CPU/fuel and memory;
- trap/crash during a call and during migration;
- deliver an event repeatedly;
- upgrade with a broader capability request;
- disable and uninstall with existing user-owned objects;
- reinstall an older compatible version.

### Pass condition

- Capability denials are enforced by the host and are understandable to users.
- Component termination cannot corrupt kernel state.
- Bundled and component implementations pass the same behavioural conformance
  suite.
- Unknown optional contract features negotiate cleanly.
- Existing data remains browsable, syncable, and exportable after uninstall.
- Declarative UI is sufficient for the sample domain without private shell APIs.
- Streaming and startup resource costs are measured and acceptable.

### Failure response

If WIT is insufficient, preserve the semantic contracts and compare a versioned
RPC/protobuf component boundary before choosing a language-specific ABI. If the
component host is the problem, evaluate another conforming runtime rather than
discarding capability isolation automatically.

## S4 — Offline sync, conflicts, and encrypted relay

### Decisions tested

- ADR 0005: operation-based sync
- sync, security, processing-location, deletion, and compatibility contracts

### Build

Create an in-memory or minimal opaque relay and three local replicas. The relay
can store and return authenticated ciphertext operations and chunked blobs but
cannot decode object payloads.

Use protocol test vectors independent of SQLite. One replica lacks the sample
plugin and must still preserve, forward, and export its objects. One replica
runs as the headless sync-agent profile, and the LAN peer-to-peer scenarios
run with the relay absent (ADR 0008).

### Scenario matrix

- offline source creation on two devices;
- LAN discovery, pairing, refusal of an unpaired device, and convergence with
  no relay present;
- concurrent edits to the same artifact;
- deterministic domain merge and no-plugin conflict preservation;
- duplicate, missing, replayed, corrupted, and reordered operations;
- interrupted/resumed multi-chunk audio transfer;
- origin-device processing with coordinator failover during a partition;
- tombstone, restoration, acknowledgement, and blob garbage-collection delay;
- device enrollment, retirement, and revocation/key-epoch advance;
- a device offline beyond the compatibility/compaction window;
- relay rollback or truncated history response.

### Pass condition

- Replicas converge to the same operation frontier and preserve all concurrent
  heads expected by policy.
- Unknown schemas round-trip byte-for-byte within their authenticated envelope.
- The relay never receives plaintext content keys or payloads.
- Duplicate processing does not create multiple logical transcript/task objects.
- A revoked device cannot decrypt future-epoch writes.
- Deletion is not physically collected before its stated safety conditions.
- Protocol parsing is bounded and rejects adversarial inputs safely.

### Required artefacts

- operation and frontier format draft;
- golden cross-platform test vectors;
- sequence/conflict diagrams for every scenario;
- relay-visible metadata inventory;
- draft key hierarchy and recovery questions;
- compaction and long-offline-device proposal.

Passing S4 does not approve production cryptography. A specialist review remains
mandatory before public hosted sync.

## S5 — Distribution policy and threat-model review

### Decisions tested

- ADR 0003 mobile/runtime assumptions
- ADR 0006 distribution and declarative UI profile

### Review

Build a dated distribution matrix for:

- Google Play Android build;
- open/direct Android build;
- Apple App Store build;
- direct desktop distribution;
- Microsoft and macOS store builds if pursued;
- self-hosted server execution.

For each, classify bundled native code, downloaded native code, WebAssembly,
interpreted scripts, schemas/data, declarative UI, model files, and remote
configuration. Record signing, review, update, permission, and disclosure rules.

Run a threat-model workshop over malicious plugins, publishers, devices, relay,
models/providers, migrations, exports, logs, and recovery flows. Map threats to
capabilities, sandbox limits, user confirmation, signatures, encryption, audit,
or explicit non-support.

### Pass condition

- No v0 product promise depends on a prohibited or review-ambiguous execution
  path.
- Platform-specific availability is explicit in manifests and marketplace UX.
- First-party bundled plugins still pass public contract conformance tests.
- The highest-risk unresolved threats have owners and pre-public-launch gates.
- Policy references and review date are recorded because store rules change.

## Foundation acceptance review

After all spikes, hold a single review against the constitution rather than
accepting ADRs independently. The result must include:

1. final disposition of every Proposed ADR;
2. revised contracts and removed contradictions;
3. a trace from every constitution rule to automated or manual evidence;
4. the remaining pre-hosted-sync and pre-third-party-plugin gates;
5. a go/no-go decision for the first production vertical slice.

