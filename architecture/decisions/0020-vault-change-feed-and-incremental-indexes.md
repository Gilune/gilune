# ADR 0020: A Vault Change Feed at the Kernel Boundary, and Indexes That Follow It

Status: **Proposed** (drafted 2026-09-04 at the owner's direction; build
authorised on the owner's word; flips to Accepted when § 7 is evidence)  
Validation: the acceptance gate in § 7, run against the production kernel.
Staged K1–K2 (kernel), P1–P2 (plugins), S (shells) in § 8.

This ADR adds a read contract to the durable record ADR 0004 already keeps,
and amends the reconciliation rule of ADR 0007. It changes neither's status.

## 1. Context

The kernel knows what changed and tells nobody.

**What is recorded.** Every mutation of an object leaves a row in `events`
(`event_id` UUIDv7, `event_type`, `subject_id`, `payload`, `created_ms`,
`origin_device`): a capture writes `org.memory.source-ready`, a processor
output writes `org.memory.<type>-created`, `add_revision` writes
`org.memory.revision-added` — the path every external edit takes through
`ingest_external_edit` — and a synced operation writes the event type it
carried, stamped with its origin device. The table is append-only; nothing
deletes from it. This is constitution rule 2 doing its job.

**What is not recorded.** Four mutations of the *projection* change the
`projections` and `imports` tables and raise no event at all: a rename by id,
a tombstone withdrawal, an import of a file the kernel did not write, and a
quarantine. And no row anywhere carries a dense sequence a reader could
resume from: `event_id` is time-ordered but not dense, and there is no
"changes since" query on the FFI surface.

**What the shells do instead** (measured 2026-09-04, desktop 1.0.1):

- `GiluneState` raises one bare `vaultTick` on any save or reconcile and
  re-reads `recentSources(100)`. The tick carries no identity: *something*
  changed.
- `searchVault` walks the whole tree and reads every `.md` for every query.
  Four callers run it — the agenda on every tick, each `filter` block on
  every settle, and both search panels per keystroke.
- `reconcile()` reads and SHA-256s **every projected file on every pass** to
  find external edits. There is no size or mtime short-cut (parked debt 12).
- A second process — the Android widget's service, or a second desktop
  instance on one vault, both supported use — cannot raise the first
  process's tick at all, so its writes are invisible until the next
  unrelated action.

At today's vault sizes every one of these costs microseconds, which is why
this is the moment to fix the shape: an index built on top of a rescan
inherits the rescan's cost curve, and the owner has said the design is
wrong in principle — "rescanning the vault, when we know where the change
happened is a bit ludicrous … plugins need to be able to be smarter than
that."

**What openness forbids.** Constitution rule 13 makes third-party editing of
the vault supported use. Obsidian or a script can change any file at any
time, so nothing in the kernel may *assume* it knows every change. Reconcile
must still look. Looking, however, is not reading.

## 2. Decision

### 2.1 The vault change feed is a kernel contract

The kernel exposes a dense, resumable log of projection-level changes:

```
changes_since(cursor: u64, limit: u32) -> ChangePage
ChangePage  { changes: [VaultChange], next_cursor: u64, complete: bool }
VaultChange { seq: u64, kind, rel_path, previous_rel_path?, object_id?,
              revision_id?, cause, created_ms }
kind  = Created | Modified | Moved | Removed | Imported | Quarantined
cause = Capture | Processor | LocalEdit | ExternalEdit | Sync | Reconcile
```

The record is **path-centric** because its consumers index files; the object
and revision ids ride along for consumers that reason about objects. `seq`
is dense, monotonic and never reused. Cursor `0` means everything. A cursor
the kernel no longer holds — a vault restored from backup, a table past its
retention — is refused with `CursorUnknown`, and the consumer rebuilds from a
full read. That rebuild is the **only sanctioned full scan**.

### 2.2 Every projection mutation writes to the feed in its own transaction

The row that describes a change commits with the change: `project()` for
created and modified files, reconcile for external edits (`Modified` /
`ExternalEdit`), renames by id (`Moved` with `previous_rel_path`),
withdrawals (`Removed`), imports and quarantines, and synced operations
(cause `Sync`, through the same `project()` they already take). A processor
output reaches the feed through the projection it produces. There is no
second event system: the feed is a **read view of the durable record**, with
the four missing projection mutations added to it.

The build round chooses the table — a `vault_changes` table with an
`AUTOINCREMENT` sequence, or new event kinds plus a sequence column on
`events` — under three requirements: dense, transactional with the change it
describes, append-only. Retention is bounded (the round proposes the
numbers; whichever it picks, a cursor past the boundary is `CursorUnknown`,
never a silent gap).

### 2.3 Indexes belong to their consumers and are incremental

The kernel owns invariants, not features (constitution rule 5; ADR 0011;
ADR 0015). So:

- **The tasks plugin owns a task-row index**: one full read at open (cursor
  `0`), then only the notes the feed names. The agenda, `filter` blocks and
  `type:task` search read the index and never walk the vault.
- **Search owns a per-note line cache** keyed by `(rel_path, content sha)`,
  fed by the feed; a query evaluates over the cache and re-reads only a
  note whose sha moved. A full-text inverted index stays an open question
  (08 § "Can remain open"); this decision makes it possible, not
  necessary.
- **The editor updates its own note's entry synchronously on write**
  (ADR 0015's write-on-action), so a tick never waits on anything.
- `vaultTick` is replaced by a cursor. `noteSaved()` becomes "advance from
  the feed". The desktop's tab-deactivation flush, silent under the tick,
  becomes visible for free.

### 2.4 Reconcile stops reading what has not changed

`projections` records file size and mtime beside `content_sha256`. A file
whose size and mtime match its record is **not read**; a mismatch is read
and hashed exactly as today. Openness is kept — a stat per file per pass —
and the residual is stated: an edit that preserves both size and mtime to
the filesystem's resolution is missed until the next full hash pass, which
runs on every vault open. This is git's index rule, and it is the rule
because it is well understood.

### 2.5 No file watcher

OS watchers (`WatchService`, `FileObserver`) are not the mechanism: they
miss network drives and editors' atomic saves, behave differently per
platform, and would be a second source of truth beside the feed. Reconcile
runs on the app's own cadence — after its own writes, on window focus and
app resume, on sync — and finds external edits by stat. A watcher may later
*hint* reconcile toward a path; it will never replace it.

## 3. Consequences

- Cost model: *N* changes cost *O(N)* consumer work plus *O(files)* stats per
  reconcile. Today it is *O(files × size)* per tick per consumer.
- FFI grows `changes_since` and two records; bindings regenerate on both
  targets and must stay identical.
- Schema v7: the feed table (or sequence column) and `projections.size` /
  `projections.mtime`. The fixture chain is re-pinned to `SCHEMA_VERSION`.
- Cross-process for free: the feed lives in the vault database, so a
  second process's writes reach every reader's cursor.
- Android: the same feed serves the agenda and search tabs and the widget's
  inbox count, which today is a directory read.
- What this does not do: no watcher, no inverted index, no cross-device
  feed — operations already are one (ADR 0005).

## 4. Alternatives considered

- **OS file watchers as the primary mechanism.** Rejected: § 2.5.
- **Kernel-owned task and search indexes.** Rejected: tasks are a plugin and
  the search DSL is shell territory; the kernel would have to name domain
  types, which it must never do.
- **Keep rescanning and make the scan faster** (memory-mapped reads,
  parallel walk). Rejected: it optimises the wrong curve.
- **The events table alone, without projection changes.** Rejected: renames,
  withdrawals and imports are the changes an index most needs and the ones
  events do not carry.

## 5. Relation to other decisions

ADR 0004 keeps the durable record this ADR reads. ADR 0007's reconciliation
rule is amended by § 2.4 (the amendment is recorded there when the round
lands). ADR 0011 and 0015 place the indexes with the plugins that own the
features. ADR 0017's agenda and search tabs and its widget take the feed.
The S4 lesson (CLAUDE.md, 2026-09-03) applies: a synced change reaching the
feed must be tested with a third replica pulling streams out of order.

## 6. Owner constraints recorded

- The kernel says *what* changed; plugins decide *what to do about it*.
- A rescan is a recovery path, never a refresh path.
- The change lands with the same falsification standard as every other
  round: each rule with its second-order test, shown red before and green
  after.

## 7. Acceptance gate (evidence, not argument)

1. **Every mutation path produces exactly its changes**, one test per path:
   voice capture, typed capture, processor output, editor save, external
   rewrite of a file, rename by id, delete that becomes a withdrawn
   tombstone, import, quarantine, synced operation — the last through the
   three-replica harness with streams pulled out of order — and a write
   from a second `Store` on the same vault seen by the first reader.
2. **Cursor semantics**: dense and gap-free across all of the above; pages
   bounded by `limit` with `complete` honest; `CursorUnknown` on a vault
   restored from the fixture and on a cursor past retention.
3. **The agenda does no scan**: after *N* edits to one note the tasks index
   touched exactly one note (an observer counts), and a property test over
   randomised edit sequences holds `index == full scan`.
4. **Reconcile reads nothing it need not**: an unchanged 1,000-note vault
   reads zero files (an instrumented read counter), and an edit that changes
   bytes while preserving size and mtime is caught by the first reconcile
   after open.
5. **The editor's own note is current before the tick**: a save updates its
   index entry synchronously; no test waits.
6. **Measurement, recorded**: a 1,000-note vault, one edit — agenda refresh
   and reconcile wall time before and after, on the owner's desktop, quiet.
7. **Nothing regresses**: cargo, desktop and instrumented suites green;
   bindings byte-identical on both targets; `gilune-cli` gains a `changes`
   command for the feed so a human can read it.

## 8. Staging

- **K1** kernel feed: schema v7, the four missing mutations recorded,
  `changes_since` + FFI + CLI, gate items 1–2.
- **K2** reconcile pre-check: `projections.size/mtime`, gate item 4.
- **P1** tasks index: agenda, `filter` blocks, `type:task`; gate items 3, 5.
- **P2** search cache; both shells swap the tick for cursors; the widget's
  inbox count moves to the feed; gate items 6–7.

## 9. Ratification record

Drafted 2026-09-04 from the owner's direction during the CPU review of the
desktop app, after the memory round was already in flight. Awaiting the
owner's word before the build round starts.
