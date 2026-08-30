# Open Questions and Decision Gates

Status: **Active**

This list prevents unresolved choices from being hidden inside implementation.
An answer should become either a contract change or an ADR.

## Must answer before the vertical-slice foundation is accepted

| Question | Evidence | Resolution target |
| --- | --- | --- |
| Is the Rust/Kotlin boundary tolerable on Android and Windows? | S1 build, debug, streaming, crash, and background results | Accept or supersede ADR 0001 |
| Can SQLite and external blobs make “saved” truthful across every crash point? | S2 fault-injection matrix | Accept or supersede ADR 0002 |
| Is canonical JSON sufficient for v0 payloads, or is canonical CBOR needed immediately? | Size, parsing, schema, unknown-field, and cross-language measurements | Data-model encoding ADR |
| Can WIT express async jobs, streams, errors, and capability handles without awkward or leaky bindings? | S3 bundled/component conformance suite | Accept or supersede ADR 0003 |
| Can sandboxed component startup and streaming meet capture-adjacent latency and memory constraints? | S3 measurements | Runtime selection in ADR 0003 |
| How are origin-device jobs failed over without duplicate user-visible artifacts? | S4 partition and lease cases | Processing/sync contract update |
| What is the minimum operation frontier representation that remains compact? | S4 two/three-device history and compaction results | Sync encoding ADR |
| Which metadata remains visible to the hosted relay? | S4 prototype and threat model | Encryption/key ADR |
| Can vault reconciliation ingest external edits, renames, deletions, and malformed front matter without losing kernel history? | S2 extended vault cases | Accept or supersede ADR 0007 |
| Do LAN discovery and pairing hold up on real home networks (AP isolation, sleep, firewalls) and within Android background limits? | S4 no-relay scenarios on physical devices | Accept or supersede ADR 0008 |
| Where does device-local state live so file-syncing a vault is safe by default (marked directory inside .gilune/ versus outside the vault), and are operations exported as immutable vault files? | S2 vault layout draft; shared-folder transport prototype | ADR 0007/0008 amendment |
| What consent and disclosure gate a provider plugin sending capture content off-device, and is local-only the default? | Capability-grant UX draft and product decision | Constitution or capability-contract amendment |
| Does the application emit telemetry or crash reports at all, and under what consent? | Product decision | ADR or constitution amendment |

## Must answer before hosted sync is public

- Which audited cryptographic library and envelope construction are used?
- How does a user enrol a new device: existing-device approval, recovery key,
  account recovery, or a combination?
- What happens when every content key is lost?
- How are revoked devices excluded from future key epochs?
- What operation and blob metadata is padded, bucketed, or left visible?
- How are rollback, fork, truncation, and replay attacks detected?
- What snapshot/compaction process lets a long-offline device recover safely?
- What are hosted retention, backup, deletion, and abuse-response policies?

These require an external security review before production deployment.

## Must answer before third-party plugin installation is public

- Which store execution path do mobile builds take for downloadable
  plugins — bundled-only forever, a JS-in-system-engine tier, or the
  sandboxed-wasm interpreter argument? Research (2026-08-30, see
  `spikes/s5-distribution-policy/downloaded-plugins-research.md`) finds
  both stores' texts defensible for a capability-mediated interpreter and
  documents precedents, but no store has ruled on downloaded wasm; the
  answer needs a test submission and becomes an ADR superseding the
  bundled-only clause of ADR 0006.
- Which publisher identities, signing roots, and transparency mechanism are used?
- What static and dynamic checks run before publication/installation?
- Which capability grants require per-use confirmation?
- How are network destination patterns reviewed and displayed?
- What are the default CPU, memory, output, recursion, and storage quotas?
- Can a plugin migrate user data automatically, or must some migrations require
  explicit approval?
- How are vulnerability revocation and offline installation reconciled?
- What recovery copy is retained after a plugin upgrade, and for how long?
- Which desktop native integrations justify the trusted bridge profile?

## Can remain open through the first vertical slice

- Server framework, database, deployment platform, and billing model.
- Vector index implementation and embedding model.
- Hosted transcription and LLM providers.
- Marketplace discovery and ranking.
- Shared/multi-user memory semantics.
- A general workflow language.
- Arbitrary custom plugin views.
- CRDT selection for particular future collaborative types.

## Decision hygiene

For each resolved question:

1. link the evidence or spike report;
2. state whether the resolution is a baseline invariant, draft contract, or ADR;
3. record data, sync, security, and compatibility consequences;
4. update or remove the question rather than leaving contradictory guidance.

