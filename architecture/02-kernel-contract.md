# Kernel Contract

Status: **Draft v0**

The kernel is a portable service boundary. Application shells and plugins call
versioned interfaces; they do not share kernel internals.

## Kernel responsibilities

### Durable state

- transactional object, relationship, provenance, and schema-envelope storage;
- immutable revision and operation journal;
- blob identity, reference tracking, staging, finalisation, and garbage
  collection eligibility;
- durable event, subscription-job, and processing-result records;
- a densely sequenced feed of what changed in the vault projection, and
  the stat facts that let reconciliation avoid reading what has not
  (ADR 0020);
- a derived, device-local **system index** beside the vault holding every
  plugin's facts about anchors and a full-text index of note text,
  maintained from that feed and rebuildable from it (ADR 0020 § 2.3);
- plugin-scoped state that is distinct from user-owned data.

### Identity and history

- object, relationship, event, transaction, operation, device, and processing
  run identifiers;
- causal metadata and per-device monotonic operation sequences;
- origin-device and creator information without treating wall-clock time as a
  conflict authority;
- tombstones and deletion acknowledgements.

### Contracts and access

- schema registration and type ownership;
- plugin lifecycle and compatibility checks;
- capability grants and enforcement;
- query and mutation APIs that apply type and provenance rules;
- complete indexed search: document/line matching, ranking, snippets,
  highlight coordinates, pagination, and freshness (ADR 0021);
- opaque secret handles backed by platform secure storage;
- configuration validation and plugin namespaces.

### Distributed invariants

- creation and validation of sync operations;
- idempotent application of local and remote operations;
- concurrent-head detection and default conflict preservation;
- stable export independent of plugin execution.

## Platform-shell responsibilities

Each Android, iOS, or desktop shell owns:

- application lifecycle, windows, navigation, and accessibility;
- microphone, camera, file picker, share sheet, notifications, and other OS UI;
- runtime permission prompts and display of plugin capability requests;
- platform background scheduling and wake-up integration;
- secure-key-store implementation and biometric gates;
- packaging, signing, update channel, and store-policy compliance;
- declarative rendering of plugin-contributed commands, cards, forms, and views.

The shell exposes these facilities through narrow kernel capability adapters. A
domain plugin asks to record audio or schedule work; it does not receive an
Android `Context`, iOS application object, or unrestricted desktop OS handle.

## Plugin responsibilities

Plugins own:

- capture workflows built over platform capture capabilities;
- transcription, language-model, embedding, and routing providers;
- domain schemas and processors for tasks, people, threads, and decisions;
- import, export-format, notification-policy, review, and indexing features;
- sync transports and blob transports;
- declarative UI contributions and commands;
- deterministic migrations for schemas they own.

## Explicit kernel exclusions

The kernel does not contain:

- domain object semantics or domain-specific merge policy;
- prompt templates, model selection, confidence thresholds, or AI escalation
  policy;
- provider credentials or network SDKs;
- a marketplace, billing, account UI, or visual design system;
- arbitrary plugin UI execution;
- an assumption that a server is present;
- direct use of a particular sync vendor or cloud database.

## Boundary rules

1. Only the kernel storage implementation opens the core database.
2. Every mutation is associated with a transaction, actor, plugin or platform
   producer, and capability decision.
3. User-owned data and plugin-private operational state use separate namespaces
   and lifecycle rules.
4. Events are emitted only as part of a successful transaction.
5. Platform calls that can outlive a process are represented by durable jobs
   before the shell schedules them.
6. Data returned across the boundary uses owned, versioned values rather than
   exposing database cursors, pointers, or mutable kernel collections.
7. Interfaces are asynchronous where I/O or platform execution may occur.

## Initial interface families

These names describe separate versioned contracts, not necessarily source-code
modules:

- `memory:objects` — create, read, revise, query, and tombstone objects;
- `memory:relations` — create, query, revise, and tombstone relationships;
- `memory:provenance` — attach sources, processing runs, confidence, and model
  metadata;
- `memory:blobs` — stage, commit, stream, verify, and release blobs;
- `memory:events` — subscribe, emit, acknowledge, retry, and inspect failures;
- `memory:schemas` — register schemas and negotiate supported versions;
- `memory:capabilities` — inspect and invoke granted host facilities;
- `memory:secrets` — request and use scoped secret handles;
- `memory:changes` — resume a cursor over what changed in the vault
  projection, and reconcile one path;
- `memory:index` — register an indexer for a namespace, advance or
  rebuild the index, and answer a conjunction of constraints with
  anchors;
- `memory:search` — execute plugin-compiled clauses against an index snapshot
  and return complete, ranked pages, counts, snippets, and freshness;
- `memory:sync` — enumerate, transmit, validate, and apply operations;
- `memory:export` — enumerate stable envelopes and stream standard-format blobs;
- `memory:diagnostics` — health and failure information with redaction.

## Kernel-change test

A change to a kernel interface must include:

- the invariant it enforces;
- why a plugin-level contract is insufficient;
- compatibility effects on existing data and plugins;
- sync and export consequences;
- at least one conformance test independent of a first-party feature.

