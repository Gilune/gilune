# Prior Art Survey

Status: **Working notes**  
Last updated: 2026-08-29

These are study pointers, not citations: each entry names the mechanism worth
taking (and occasionally the mistake worth avoiding), mapped to our contracts
and ADRs. Versions and behaviours change, so verify any claim against the
tool's current documentation during the spike that draws on it.

## Vault, index, and reconciliation (ADR 0007)

- **Obsidian** — the files are the only truth; the metadata cache (links,
  tags, headings, front matter) is per-device, keyed by path plus
  mtime/size, never synced, and rebuilt by rescan whenever stale or corrupt.
  Device-specific state is segregated in `.obsidian/` and excluded from sync
  by default. Third-party file-sync conflicts surface as conflicted-copy
  notes: ugly but safe. Also published JSON Canvas as an open format spec.
  Take: the disposable-cache discipline; publishing our vault format the way
  they published Canvas.
- **Jujutsu (jj)** — snapshots the working copy automatically on every
  command, so manual edits made outside the tool become first-class commits
  without a "save" step; conflicts are stored *inside* commits as data and
  operations continue around them. Take: the reconciler model for external
  vault edits; conflict-carrying revisions matching our multi-head design.
  Its operation log powering global undo is also worth study.
- **Fossil** — canonical truth is an append-only log of immutable artifacts;
  the SQLite tables are a cache rebuilt on demand (`fossil rebuild`), and
  "shunning" propagates explicit content removal through sync. Take: the
  database-as-materialisation pattern and a working precedent for
  tombstone-plus-physical-erasure.
- **Logseq** — cautionary: Markdown as truth with a semi-authoritative graph
  index drifted into inconsistency and data-loss bugs, driving their rewrite
  around a database core. Reject: dual truth.
- **org-mode** — decades of proof that typed data (properties, states,
  timestamps) can live in hand-editable plain text without tooling lock-in.
  Take: conventions for keeping front matter human-tolerant.
- **Perkeep (Camlistore)** — a personal data store built entirely from
  content-addressed immutable blobs: "permanodes" give stable identity,
  signed "claims" mutate attributes append-only, and the indexer is fully
  rebuildable. Philosophically the closest ancestor of our envelope,
  operation, and projection model in fully file-shaped form. Take: the
  permanode/claim decomposition when drafting operations-as-vault-files.

## Operation log, sync, and conflicts (ADR 0005, ADR 0008)

- **CouchDB / PouchDB** — per-document revision trees with conflicts kept as
  siblings the application must resolve, and checkpointed incremental
  replication. The oldest production embodiment of "preserve concurrent
  heads, replicate idempotently after a frontier". Take: revision-tree and
  checkpoint semantics.
- **Automerge / automerge-repo** — changes carry actor ID, sequence number,
  and causal dependencies (our operation envelope in miniature); the sync
  protocol uses Bloom filters to discover missing changes efficiently; the
  repo layer cleanly separates storage adapters from network adapters,
  mirroring our kernel/transport split. Take: frontier-exchange design and
  compact change encoding, even where we don't adopt CRDT semantics.
- **git** — immutable content-addressed objects, loose objects compacted
  into indexed packfiles, a disposable index/working-tree layer, `fsck`
  integrity checking, and shallow clone with later deepening. Take: packfile
  compaction for a many-small-operation-files log; shallow bootstrap for the
  long-offline-device problem; fsck as a model for our recovery checks.
- **Syncthing** — device identity is the hash of a self-generated
  certificate, pairing is QR/ID exchange with mutual approval, discovery
  layers local multicast under global discovery servers and relays, ignore
  patterns exclude device-local state, and conflicts produce
  `.sync-conflict` files. Take: the whole pairing and discovery UX for
  LAN-first sync.
- **Tailscale** — direct peer-to-peer where the network allows, transparent
  fallback to DERP relays where it doesn't, identical encrypted payload
  either way. Take: the transport shape of ADR 0008 — LAN-first with an
  optional relay carrying the same bytes — plus their pairing/admin UX bar.
- **Kafka and event-sourcing practice** — consumer offsets are our
  acknowledgement frontiers; log compaction is the vocabulary for operation
  retention. Take: concepts and failure cases, not the infrastructure.
- **restic / borg** — content-defined chunking for deduplicated, resumable
  transfer of large binaries over dumb storage. Take: blob chunking for
  audio sync and the shared-folder transport.

## Capture and processing (P0, ADR 0004)

- **Google Recorder (Pixel)** — fully on-device live transcription with
  speaker labels and search, robust across interruptions. Take: the
  reliability and latency bar for tap → speak → done, and proof that
  on-device transcription is product-grade.
- **Apple Voice Memos** — the recording pipeline survives calls, crashes,
  and termination. Take: the capture-durability bar S2 must meet.
- **whisper.cpp / faster-whisper** — the obvious first local transcription
  providers for validating the provider-plugin contract on desktop and
  Android.
- **Temporal** — durable execution's working vocabulary: retries with
  backoff, heartbeats, timeouts, idempotency, durable timers, and
  deterministic replay. Take: semantics for the job system; not the server.
- **maildir** — atomic single-file commit via write-temp, fsync, rename.
  Take: the commit protocol for operations-as-files.

## Plugins and capabilities (ADR 0003, ADR 0006)

- **VS Code** — declarative contribution points (commands, views, menus)
  plus activation events so an extension does not run until something it
  contributed is invoked; extensions execute in a separate host process.
  Take: activation events for battery and startup cost; contribution-point
  design for our declarative UI slots.
- **Figma plugins** — after abandoning JS realm tricks, plugin logic runs in
  a JavaScript interpreter (QuickJS) compiled to WebAssembly, with plugin UI
  isolated in an iframe communicating by messages. Take: independent
  validation of wasm sandboxing for consumer plugins and of splitting plugin
  logic from rendered UI.
- **Zed** — a shipped consumer application whose extensions are
  WIT-defined WebAssembly components. Take: real-world component-model
  ergonomics, versioning pains, and API surface for ADR 0003.
- **Extism** — a universal wasm plugin framework with host SDKs and plugin
  PDKs across languages. Take: packaging and developer-experience ideas for
  our plugin SDK.
- **Browser extensions (Manifest V3)** — install-time versus optional
  runtime-requested permissions, host-permission scoping, store review.
  Take: runtime-requested optional grants and the permission-prompt
  language users already understand.
- **Obsidian plugins** — unsandboxed JavaScript with community review only:
  the counterexample on safety, but also proof that low friction breeds a
  huge ecosystem, with an API surface grown from real plugin pressure.
  Take: grow slots from demonstrated need. Reject: unsandboxed-by-default.

## Encryption, devices, and recovery (ADR 0005 open questions)

- **Matrix** — cross-signed device trees, per-device verification states,
  and encrypted server-side key backup unlocked by a recovery key: the most
  complete open blueprint for our enrollment, revocation, and
  lost-key questions.
- **Signal** — QR-based linked-device provisioning. Take: the pairing flow
  users already know.
- **1Password** — the Secret Key (a second, device-held factor the server
  never sees) and the printable Emergency Kit. Take: honest, usable answers
  to "what if every key is lost".
- **Standard Notes** — a published, versioned encryption protocol over dumb
  storage with documented upgrade paths. Take: publish and version our
  envelope crypto the same way.
- **Joplin** — end-to-end encryption layered over arbitrary dumb sync
  targets (Dropbox, WebDAV, S3, a directory) with per-item files and a
  conflicts notebook. Take: the closest production precedent for our
  shared-folder transport, encryption included.

## Object models and openness

- **Anytype** — local-first objects, types, and relations with CRDT sync
  (any-sync) and self-hosting: the closest product to our data model. But
  its on-disk format is effectively opaque to other tools. Take: the
  object/type/relation UX. Reject: the opacity — our vault is the
  differentiator.
- **Solid / remoteStorage** — user-chosen storage as a protocol-level
  principle. Take: framing for "any store" as a product promise.

## Router and intent layer

- **Apple App Intents** — applications expose typed intents with
  parameters; the system routes natural language onto them, including via
  on-device models. Take: the typed capability/intent registry our router
  contract describes, designed independently of any model.
- **Android AICore / Gemini Nano** — the platform's on-device inference
  service and its constraints. Take: what a bundled tiny-router provider
  must live within on Android.

## How to use this document

1. Each spike report should cite the entries it drew from or contradicted.
2. When an entry's mechanism is adopted, record it in the relevant ADR and
   keep the pointer here.
3. Claims are study pointers; verify against current upstream documentation
   before relying on details.
