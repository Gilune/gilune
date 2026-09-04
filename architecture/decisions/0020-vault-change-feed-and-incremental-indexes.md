# ADR 0020: A Vault Change Feed, and a System Index Plugins Feed Without Knowing Each Other

Status: **Proposed** (drafted 2026-09-04 at the owner's direction; revised the
same day after the owner's review — § 10; build authorised on the owner's
word; flips to Accepted when § 8 is evidence)  
Validation: the acceptance gate in § 8, run against the production kernel.
Staged K1–K3 (kernel), P1–P2 (plugins), S (shells) in § 9.

This ADR adds a read contract to the durable record ADR 0004 keeps, amends
the reconciliation rule of ADR 0007, and gives ADR 0015's search seams the
storage and the join they were built without. It changes no other ADR's
status.

## 1. Context

The kernel knows what changed and tells nobody, and nothing in the app has an
index.

**What is recorded.** Every mutation of an object leaves a row in `events`
(`event_id` UUIDv7, `event_type`, `subject_id`, `payload`, `created_ms`,
`origin_device`): a capture writes `org.memory.source-ready`, a processor
output `org.memory.<type>-created`, `add_revision` — the path every external
edit takes through `ingest_external_edit` — `org.memory.revision-added`, and
a synced operation the event type it carried, stamped with its origin device.
The table is append-only. This is constitution rule 2 doing its job.

**What is not recorded.** Four mutations of the *projection* change the
`projections` and `imports` tables and raise nothing: a rename by id, a
tombstone withdrawal, an import of a file the kernel did not write, a
quarantine. No row anywhere carries a dense sequence a reader could resume
from, and there is no "changes since" on the FFI surface.

**What the shells do instead** (measured 2026-09-04, desktop 1.0.1):

- `GiluneState` raises one bare `vaultTick` on any save or reconcile and
  re-reads `recentSources(100)`. The tick carries no identity.
- **Search always scans.** `searchVault` walks the tree and reads every `.md`
  for every query, per keystroke in both search panels. There is no index
  anywhere in the app: the DSL and its plugin seams (`SearchOperators`,
  `SearchType`, `tasks.fields`) were built for correctness over files on
  vaults of a few dozen notes, and the agenda and every `filter` block reuse
  the same scan because it was the only thing there.
- `reconcile()` reads and SHA-256s **every projected file on every pass** to
  find external edits. No size or mtime short-cut (parked debt 12).
- A second process on the same vault — the Android widget's service, a second
  desktop instance, both supported use — cannot raise the first process's
  tick, so its writes are invisible until the next unrelated action.

At today's vault sizes each of these costs microseconds, which is why this is
the moment to fix the shape: an index built on a rescan inherits the
rescan's cost curve. The owner's words: "rescanning the vault, when we know
where the change happened is a bit ludicrous … plugins need to be able to be
smarter than that."

**The harder question the owner then asked.** If the tasks plugin and a
future people plugin each keep their own index, how does `type:task @alice`
ever run? Either one plugin learns about the other — "you'd need to build
knowledge of another plugin into another one" — or there is a model in which
they coexist without being explicit and the DSL queries across all of them.
Section 2.3 is that model.

**What openness forbids.** Constitution rule 13 makes third-party editing of
the vault supported use. Nothing in the kernel may *assume* it knows every
change; reconcile must still look. Looking, however, is not reading.

## 2. Decision

### 2.1 The vault change feed is a kernel contract

```
changes_since(cursor: Cursor, limit: u32) -> ChangePage | CursorUnknown
Cursor      { epoch: String, seq: u64 }
ChangePage  { changes: [VaultChange], next: Cursor, complete: bool }
VaultChange { seq, kind, rel_path, previous_rel_path?, object_id?,
              revision_id?, cause, created_ms }
kind  = Created | Modified | Moved | Removed | Imported | Quarantined
cause = Capture | Processor | LocalEdit | ExternalEdit | Sync | Reconcile
```

- **Scope: the projection.** The feed describes files in the vault. Objects
  that never project — attached captures — do not appear; object-level
  change remains the events table's, unchanged. A plugin that reasons about
  objects reads events; one that reasons about the vault reads this.
- **Dense, by construction.** `seq` is advanced by a counter in `meta`
  inside the same transaction as the change it numbers, the way `op_seq`
  works — **not** an `AUTOINCREMENT` column, which burns a number on every
  rolled-back transaction and would leave readers seeing gaps. SQLite's
  single writer makes the counter safe across processes.
- **An epoch, not a retention window.** The table is never trimmed; a change
  row is a couple of hundred bytes and a heavy vault's lifetime of them is
  megabytes. Consecutive `Modified` rows for one path may be compacted, which
  preserves density because compaction renumbers nothing — it deletes only
  rows older than every reader's cursor recorded in `meta`, or none. The
  `epoch` is a random id stamped when the table is created; a cursor whose
  epoch differs (a different vault's database swapped in) or whose `seq`
  the table does not hold is refused with `CursorUnknown`, and the consumer
  rebuilds from `Cursor { epoch, 0 }`. **That rebuild is the only sanctioned
  full scan.**
- **`cause` is best-effort.** It is exact where the kernel knows (reconcile
  knows an external edit; apply knows a synced operation); a change that
  reaches `project()` without its provenance plumbed is reported as
  `LocalEdit`. Consumers must not branch on it for correctness.

### 2.2 Every projection mutation writes the feed in its own transaction

A `vault_changes` table (schema v7) receives one row in the transaction that
commits the change: `project()` for created and modified files; reconcile for
external edits, renames by id (`Moved` with `previous_rel_path`),
withdrawals, imports and quarantines; synced operations through the
`project()` they already take. Events stay what they are — the durable
record processors subscribe to; the feed is a second *view*, not a second
event system, and no fake event kinds are added for projection mutations.

Stated residual: a file write happens outside the database transaction
(temp file, rename). A crash between the file landing and the row committing
leaves a file the next reconcile reports as `Modified / ExternalEdit` rather
than under its true cause. Nothing is lost; the cause is approximate, which
§ 2.1 already allows.

A new targeted entry point, `reconcile_path(rel_path)`, reconciles one file
in *O(1)*. The editor calls it after its own write, so the note it just saved
reaches the feed and the index in the same pass, before anything refreshes —
ADR 0015's write-on-action, without the shell holding an indexer of its own.

### 2.3 The system index: facts about anchors, stored once, contributed by many

**The unit is a fact, not an index.**

- An **anchor** is a place in the vault: a note (`rel_path`) or a line in a
  note (`rel_path`, line number). Every plugin that indexes the vault is
  describing anchors.
- A plugin registers an **indexer**: a pure function from a note's text to
  facts, each `(anchor, predicate, value)`. The tasks plugin, reading
  `- [ ] fix the wall @alice due:2026-09-10` on line 12, returns
  `(note#12, org.memory.tasks.state, open)` and
  `(note#12, org.memory.tasks.due, 2026-09-10)`. A future people plugin,
  reading the same line, returns `(note#12, org.memory.people.mention,
  alice)`. **Neither knows the other exists.** They agree on nothing except
  what a line is.
- The **kernel stores every fact in one table** — the system index — in a
  device-local **index database beside the vault**, never inside it and never
  synced, together with an **FTS5 full-text index** of note text in the same
  database. Predicates are namespaced by plugin id and opaque to the kernel:
  data, exactly as event types are. The kernel never interprets a predicate
  and never special-cases one. Values are stored as text with a sortable
  numeric or date shadow column so ranges work; a plugin says which of the
  two a predicate uses when it registers.
- **The kernel maintains the index from its own feed.** When a note changes,
  its facts are deleted and re-derived by every registered indexer, and its
  FTS entry replaced — that note only. The indexers run in the pass that
  recorded the change. Anchors are re-derived whole, so line numbers shifting
  on edit is not a problem the index has to solve.
- **Removing a plugin deletes its namespace.** The notes are untouched,
  which is the existing removal rule (ADR 0011). A plugin upgrade that
  changes its facts bumps a per-indexer version and its namespace is
  re-derived from cursor 0.
- **The whole index database is derived and disposable.** It has its own
  schema, outside the fixture chain; deleting it costs a rebuild and nothing
  else.
- **Indexers cross the boundary two ways.** Now: a uniffi foreign trait
  (`NoteIndexer`) the shell implements in Kotlin — the precedent is
  `KeyWrap` (ADR 0019). Later: `index-note(text) -> list<fact>` in the WIT
  processor contract (ADR 0003). The function is pure over text, so a
  sandboxed downloaded plugin can extend the index **without vault access**.
- **Private indexes remain allowed** for data that is not a fact — an
  embedding index later — under the same feed-and-cursor pattern. They are
  the exception; facts are the rule.

### 2.4 The DSL: plugins own the words, the kernel owns the join

- A plugin registers an operator together with how it **compiles to a
  constraint**: `due:week` → predicate `org.memory.tasks.due` between two
  dates; `@alice` → predicate `org.memory.people.mention` equals `alice`; a
  bare word or "phrase" → a full-text match; `[key:value]` → the
  front-matter predicates the vault plugin already contributes. Parsing,
  meaning and date phrases stay the plugin's, exactly where ADR 0015 put them
  (`SearchOperators`, `SearchType` become the registration route).
- The kernel's query engine takes a **conjunction of constraints** and
  answers with anchors: `query(constraints, scope, limit) -> [Anchor]`.
  `type:task due:week @alice` is a join on the anchor across three
  contributors, and nobody wrote the join.
- **Scope falls out of the anchor.** A line-scoped operator narrows to lines;
  a note-scoped fact applies to every line of its note. A query is answered
  at the finest scope any of its constraints names, and can be projected back
  to notes — "notes where a task is assigned to Alice" is the same query,
  projected.
- The agenda, `filter` blocks and both search panels become queries. The
  50-row and 8-row caps, the four agenda views and their horizon are
  unchanged: they are what the tasks plugin does with the anchors it gets.
- No `OR` yet; the DSL's whitespace-AND is a conjunction and this decision
  neither adds nor forbids disjunction.

### 2.5 Reconcile stops reading what has not changed

`projections` records file size and mtime beside `content_sha256`. A file
whose size and mtime both match its record is **not read**; a mismatch is
read and hashed as today. This is git's index rule, adopted **with git's
racy-file safeguard**: a file whose mtime is not older than the moment its
record was written is re-hashed regardless, because the write and the record
may have landed within the filesystem's timestamp resolution. Openness is
kept — a stat per file per pass — and the full hash of every file becomes an
explicit **Verify vault** action (the CLI's `verify`, surfaced in Settings),
not something every launch pays.

### 2.6 No file watcher as the mechanism

OS watchers miss network drives and editors' atomic saves and differ per
platform; they would be a second source of truth beside the feed. Reconcile
runs on the app's own cadence — after its own writes (`reconcile_path`), on
window focus and app resume, on sync. Whether the desktop should *also* take
a watcher as a hint, or a periodic stat-walk while focused, so that an edit
made in Obsidian beside Gilune shows up without a focus change, is **left
open by the owner (2026-09-04)** and recorded in § 10.

## 3. Consequences

- Cost model: *N* changes cost *O(N)* index work; a query costs a lookup
  regardless of vault size; a reconcile costs *O(files)* stats. Today: *O(files
  × size)* per query, per tick, per consumer.
- Vault database schema v7: `vault_changes`, the `meta` counter and epoch,
  `projections.size` / `projections.mtime`, reader cursors in `meta`. The
  fixture chain is re-pinned to `SCHEMA_VERSION`. The index database is
  separate and rebuildable.
- FFI grows `changes_since`, `reconcile_path`, `query`, `register_indexer`
  (foreign trait) and their records; bindings regenerate on both targets and
  must stay identical — remembering that a doc-comment edit on an exported
  item is a bindings change.
- Cross-process for free: feed and index live beside the vault, so a second
  process's writes reach every reader.
- Android: the same feed and index serve the agenda and search tabs and the
  widget's inbox count, today a directory read.
- What this does not do: no watcher, no `OR`, no cross-device feed
  (operations already are one, ADR 0005), no indexing of attachment
  contents, no embedding index (open question, and now with a substrate).

## 4. Alternatives considered

- **Private per-plugin indexes.** Rejected on the owner's point: a query
  across two plugins would require one to know the other, and the DSL could
  never be extended by a plugin the others had not heard of.
- **A kernel-owned tasks index.** Rejected: the kernel would have to know
  what a task is. Facts with opaque predicates give it storage and the join
  without the knowledge.
- **A shell-side search engine (Lucene on the desktop, something else on
  Android).** Rejected: two engines, a native dependency, and an index the
  kernel could not maintain from its own feed. SQLite with FTS5 is already
  shipped on both platforms.
- **OS file watchers as the primary mechanism.** Rejected: § 2.6.
- **The events table alone.** Rejected: renames, withdrawals and imports are
  the changes an index most needs and events do not carry them; and fake
  event kinds for them would pollute the record processors subscribe to.
- **Keep rescanning and make the scan faster.** Rejected: the wrong curve.

## 5. Prior art

One property store, many contributors, a query language over the union:
Obsidian's Dataview metadata cache, Logseq's Datascript database, Notion's
properties. The fact model is the triple store's; the index rule is git's.
None of this is novel, which is the point.

## 6. Relation to other decisions

ADR 0004 keeps the record this ADR reads. ADR 0007's reconciliation rule is
amended by § 2.5 (recorded there when the round lands). ADR 0011's removal
rule governs a plugin's namespace. ADR 0015's operator and type seams become
the registration route of § 2.4 and its `filter` block and agenda become
queries. ADR 0003's WIT contract gains `index-note` when the sandboxed route
is built. ADR 0017's tabs and widget take the feed. The S4 lesson (CLAUDE.md,
2026-09-03) applies: a synced change reaching the feed is tested with a third
replica pulling streams out of order.

## 7. Owner constraints recorded

- The kernel says *what* changed; plugins say *what it means*, as facts; the
  kernel stores facts and answers conjunctive queries; plugins own the DSL's
  words. No layer knows the layer above it.
- A rescan is a recovery path, never a refresh path.
- Every rule lands with its second-order test, shown red before and green
  after.

## 8. Acceptance gate (evidence, not argument)

1. **Every mutation path produces exactly its changes**, one test per path:
   voice capture, typed capture, processor output, editor save via
   `reconcile_path`, external rewrite, rename by id, delete that becomes a
   withdrawn tombstone, import, quarantine, synced operation through the
   three-replica harness with streams pulled out of order, and a write from a
   second `Store` on the same vault seen by the first reader.
2. **Cursor semantics**: dense and gap-free across all of the above
   including a rolled-back transaction between two changes; pages bounded
   with `complete` honest; `CursorUnknown` for a foreign epoch and for a seq
   the table does not hold; compaction never breaks a live cursor.
3. **Two indexers that share nothing join on an anchor**: two test indexers
   over the same fixture line, a conjunctive query returns exactly that
   line; removing one plugin's namespace removes its facts and nothing
   else; a versioned indexer re-derives its namespace alone.
4. **Incremental equals rebuilt**: a property test over randomised edit
   sequences holds `index after N changes == index rebuilt from cursor 0`,
   and `query results == the old full scan's results` for the DSL's existing
   operators.
5. **The agenda does no scan**: after *N* edits to one note the indexers ran
   over exactly one note (an observer counts).
6. **Reconcile reads nothing it need not**: an unchanged 1,000-note vault
   reads zero files (an instrumented read counter); a file edited within the
   timestamp resolution of its record is re-hashed by the racy rule; **Verify
   vault** hashes everything.
7. **The editor's own note is current before anything refreshes**: a save
   followed by `reconcile_path` lands in the index before the shell's cursor
   advances.
8. **Measurement, recorded**: a 1,000-note vault — one edit's agenda
   refresh, one query, one reconcile — before and after, on the owner's
   desktop, quiet.
9. **Nothing regresses**: cargo, desktop and instrumented suites green;
   bindings byte-identical; `gilune-cli` gains `changes` and `query` so a
   human can read both.

## 9. Staging

- **K1** feed: schema v7, `vault_changes`, counter and epoch, the four
  unrecorded mutations, `reconcile_path`, `changes_since` + FFI + CLI; gate
  items 1–2, 7.
- **K2** reconcile: size/mtime with the racy rule, Verify vault; gate item 6.
- **K3** system index: the index database, facts table, FTS5, `NoteIndexer`
  foreign trait, `query`, namespace removal and versioning; gate items 3–4.
- **P1** tasks plugin: its indexer; `type:task`, `due:`, `done:`, `is:`
  compile to constraints; agenda and `filter` blocks query; gate item 5.
- **P2** search: bare terms and phrases to FTS5; `file:`/`path:`/`content:`
  and `[key]` operators compile; both panels query.
- **S** shells: the tick becomes a cursor on desktop and Android; the widget's
  inbox count moves to the feed; gate items 8–9.

## 10. Ratification record

- 2026-09-04, first draft, from the owner's direction during the CPU review
  of the desktop app.
- 2026-09-04, revised after the owner's review of the draft's points of
  contention: dense by a `meta` counter, not `AUTOINCREMENT`; the racy rule
  instead of a full hash pass on every open; no retention window, an epoch
  instead; projection-only scope stated; `cause` best-effort; the crash
  window stated. The index half was rewritten on the owner's question about
  a future people plugin filtering tasks: private per-plugin indexes were
  replaced by the system index of § 2.3–2.4, the owner's own sketch — "the
  kernel doing something with plugin indexes to build out a system index to
  enable a pluggable DSL". The full-text index was pinned to SQLite FTS5.
- **Left open by the owner**: whether the desktop takes a file watcher as a
  hint, or a periodic stat-walk while focused, so an edit made beside Gilune
  shows without a focus change (§ 2.6). Also open, for the round to propose:
  the exact value typing of predicates, and anchor identity finer than a
  line number.
- Awaiting the owner's word before the build round starts.
