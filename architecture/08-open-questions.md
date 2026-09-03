# Open Questions and Decision Gates

Status: **Active**

This list prevents unresolved choices from being hidden inside implementation.
An answer should become either a contract change or an ADR.

## Must answer before the vertical-slice foundation is accepted

| Question | Evidence | Resolution target |
| --- | --- | --- |
| Is the Rust/Kotlin boundary tolerable on Android and Windows? | S1 build, debug, streaming, crash, and background results | Accept or supersede ADR 0001 |
| Can SQLite and external blobs make “saved” truthful across every crash point? | S2 fault-injection matrix | Accept or supersede ADR 0002 |
| ~~Is canonical JSON sufficient for v0 payloads, or is canonical CBOR needed immediately?~~ | Answered 2026-09-03 (M5a): canonical JSON — compact, UTF-8, keys sorted at every level — is the operation codec, with 37 portable vectors in `kernel/vectors/operations-v1/` (valid ones re-encode byte-exact); CBOR is not needed for v0 | ADR 0019 § 6; `kernel/crates/gilune-kernel/src/operations.rs` |
| Can WIT express async jobs, streams, errors, and capability handles without awkward or leaky bindings? | S3 bundled/component conformance suite | Accept or supersede ADR 0003 |
| Can sandboxed component startup and streaming meet capture-adjacent latency and memory constraints? | S3 measurements | Runtime selection in ADR 0003 |
| How are origin-device jobs failed over without duplicate user-visible artifacts? | S4 partition and lease cases | Processing/sync contract update |
| ~~What is the minimum operation frontier representation that remains compact?~~ | Answered 2026-09-03 (M5a): one `(device_id, applied_seq)` row per peer device in `sync_frontiers` — deliberately not `MAX(op_seq)`, since a halted stream holds operations past its frontier; compaction of retired devices stays open under the hosted-sync gate | ADR 0019 § 6; schema v5 |
| Which metadata remains visible to the hosted relay? | S4 prototype and threat model | Encryption/key ADR |
| Can vault reconciliation ingest external edits, renames, deletions, and malformed front matter without losing kernel history? | S2 extended vault cases | Accept or supersede ADR 0007 |
| Do LAN discovery and pairing hold up on real home networks (AP isolation, sleep, firewalls) and within Android background limits? | S4 no-relay scenarios on physical devices | Accept or supersede ADR 0008 |
| ~~Where does device-local state live so file-syncing a vault is safe by default?~~ Are operations exported as immutable vault files? | First half answered 2026-09-02 (ADR 0019 § 4): device identity, the pairing roster and the sync-enabled flag live OUTSIDE the vault in a shell-nominated device-local root (`%LOCALAPPDATA%\Gilune\sync`, Android `filesDir/sync`), keyed by the vault's `device_id`; a vault opened where no matching identity exists re-enrols as a new device. The second half (immutable operation files for the shared-folder transport) stays open | ADR 0019 § 4 answered; shared-folder transport → ADR 0008 amendment |
| ~~What consent and disclosure gate a provider plugin sending capture content off-device, and is local-only the default?~~ | Answered 2026-08-30 (threat-model workshop): local-only IS the default; off-device requires explicit per-provider consent with plain disclosure | `05-plugin-system.md` § Provider plugins |
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

- Which publisher identities, signing roots, and transparency mechanism are used?
- What static and dynamic checks run before publication/installation?
- Which capability grants require per-use confirmation?
- How are network destination patterns reviewed and displayed?
- What are the default CPU, memory, output, recursion, and storage quotas?
- ~~Can a plugin migrate user data automatically?~~ Answered 2026-08-30
  (threat-model workshop): additive migrations run automatically;
  destructive/lossy require explicit approval plus a retained recovery
  copy — `07-compatibility.md` § Migration execution.
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

