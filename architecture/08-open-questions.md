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
- ~~How do plugins and shells learn what changed without rescanning the
  vault, and how does the search DSL query across plugins that do not know
  each other?~~ Proposed answer 2026-09-04: ADR 0020 — a dense change feed
  read from the durable record; a kernel-hosted system index of
  plugin-contributed facts about anchors plus FTS5, with operators compiling
  to constraints the kernel joins; a stat-before-read reconcile. Closes when
  its gate passes.
- Should the desktop take a file watcher as a hint, or a periodic stat-walk
  while focused, so an edit made beside Gilune shows without a focus change?
  (ADR 0020 § 2.6; owner deferred 2026-09-04.)
- Vector index implementation and embedding model (a private index under
  ADR 0020's feed-and-cursor pattern; which model and where stays open).
- Hosted transcription and LLM providers.
- Marketplace discovery and ranking.
- Shared/multi-user memory semantics.
- A general workflow language.
- Arbitrary custom plugin views.
- CRDT selection for particular future collaborative types.

## Product idea: page templates and default vault structure

Recorded 2026-09-05 from the product discussion. **Exploration, not an
accepted contract or an implementation commitment.** The owner asked to
retain the template idea for further design.

**Context.** The initial opinionated product being discussed centres on
notes, tasks, people, projects, and reminders. The owner wants default
page and folder structures for People and Projects, changeable through
plugins. Templates could provide useful starting pages without requiring
users to design their own structure first.

**Suggested shape to explore:**

- Ship lightweight default templates for person and project pages, with
  optional properties and freeform Markdown sections. Examples: a person's
  name, handle, birthday, and notes; a project's purpose, status, and notes.
- Separate folder/naming settings, templates for initial page content,
  and plugin-provided fields, relationships, and behaviour. Simple
  customisation should not require writing a plugin.
- Apply a template when creating a page. Later template changes affect new
  pages; they do not automatically rewrite existing user-authored content.
- Keep collected views (mentions, assigned tasks, related notes) distinct
  from initial template content. Explore using the existing filter-block
  and plugin contribution mechanisms instead of copying matching content.
- Let page identity and type survive moves and renames; default folders
  organise pages but should not be their sole type declaration.
- Preserve frictionless capture: choosing a template, type, or folder
  must never be required to save an ordinary thought.

**Open design questions:** where templates live and whether they sync;
how users override plugin defaults; how template versions and plugin
removal behave; which properties and sections ship by default; and how
dynamic views coexist with ordinary, externally editable Markdown.

Next step when this idea is taken forward: design one person template and
one project template, then use their actual needs to define any shared
page-creation contract. No general template language has been selected.

Planning update, 2026-09-05: the
[People and shared document-refactoring plan](plans/people-and-document-refactoring.md)
takes the person template and shared creation contract forward first. Project
templates remain future consumers. Template storage/round-trip choices are
explicit P0 work; no template language or implementation is accepted yet.

## People connections and plain-text refactoring

Owner direction, 2026-09-05: People owns user-defined connection keywords such
as `assign` and `owner`; Gilune does not enforce their personal meanings.
Renames should update visible text across existing documents rather than
preserve a hidden alternative meaning through aliases. Build a shared public
document-refactoring capability alongside People, outside the plugin, and test
it with People plus an unrelated consumer.

The [delivery plan](plans/people-and-document-refactoring.md) records scope,
contract gaps, milestones and acceptance gates. Remaining design choices
include lexical rules, portable definition format, source-versioned edits,
partial-rename configuration and guarded undo. The owner accepted whole-list-item
connection scope (nested items separate), preserving unknown person references
with optional explicit page creation, and renaming all vault Markdown without
adding metadata or requiring adoption. These are requirements, not open choices;
unmanaged-file recovery/sync mechanics remain design work. Implementation now
includes scoped membership queries, independent filter registrations, exact
source snapshots, shared Markdown locations, validated edit preparation and
the separately compiled Kotlin SDK/Tasks boundary.
Persisting item locations in the index, People and shared document refactoring
remain open; the plan and document contract record the implemented boundary.

The owner also requested shared set-membership queries in this delivery, using
`assign IN ["alice", "bob"]` with a task type as the motivating example. People
supplies connection values; membership, negation, scope and complete pagination
belong to the framework. The delivery plan now includes the public constraint,
parser/filter integration and independent-plugin acceptance tests; final query
syntax and list limits remain P0 choices.

## Decision hygiene

For each resolved question:

1. link the evidence or spike report;
2. state whether the resolution is a baseline invariant, draft contract, or ADR;
3. record data, sync, security, and compatibility consequences;
4. update or remove the question rather than leaving contradictory guidance.

