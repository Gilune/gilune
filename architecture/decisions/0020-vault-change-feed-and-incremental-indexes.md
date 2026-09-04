# ADR 0020: A Vault Change Feed, and a System Index Plugins Feed Without Knowing Each Other

Status: **Proposed** (drafted 2026-09-04 at the owner's direction; revised the
same day after the owner's review — § 10; **ratified and build authorised by
the owner 2026-09-04**; flips to Accepted when § 8 is evidence)  
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
- **2026-09-04, ratified by the owner ("ratified.") and the build
  authorised.** Staged as § 9, one implementer per stage, sequential:
  K1+K2, then K3, then P1+P2+S, each landing with its gate items.

- **2026-09-04, K1 and K2 delivered.** Schema v7 adds `vault_changes`
  (`seq` an explicit `INTEGER PRIMARY KEY`, `kind`, `rel_path`,
  `previous_rel_path`, `object_id`, `revision_id`, `cause`, `created_ms`)
  with an index on `rel_path`; `meta.vault_change_seq` (the counter) and
  `meta.vault_change_epoch` (`lower(hex(randomblob(16)))`, stamped by the
  migration); and `projections.size`, `projections.mtime_ns`,
  `projections.recorded_ns`. Gate items 1, 2, 6, 7 and 9 are evidence;
  3–5 and 8 belong to the stages that follow. What the round chose where
  this decision left room, and where it deviated:

  - **No compaction.** § 2.1 permits consecutive `Modified` rows for one
    path to be compacted; nothing does. The table is never trimmed, so
    every sequence from 1 to the counter is held, and reader cursors in
    `meta` (§ 3) were not needed and were not added. A compactor would
    need them and would have to leave every live reader's position
    intact; when one is wanted, `already_removed` shows the shape of the
    per-path query it would build on. Until then `CursorUnknown` for a
    held sequence can only mean a foreign epoch or a reader ahead of the
    vault.

  - **`cause`, path by path.** Exact: reconciliation's external edit
    (`ExternalEdit`), and **`Sync`**, which is decided by asking the
    operations table whose the projected head's revision was — a head
    carried here by another device's operation is a synced change and can
    be nothing else, whatever `origin` string the writer stamped on it.
    Read off `revisions.origin`, and therefore only as good as the
    writer's own stamp: `Capture`, `Processor`. Assigned by the site
    rather than derived: `Reconcile` for a rename by id, an import, a
    quarantine, and for a deletion decided here. `LocalEdit` is the
    fallback for an origin this build does not recognise. The question is
    asked only where a change is announced, so a settled vault never pays
    for it.

  - **`Created` means the vault did not have this file** — either no
    projection record, or a record whose file was missing. The narrower
    "no record" reading would report a rejected tombstone proposal
    putting a file back as `Modified`, which describes a change to
    something that was not there.

  - **`Removed` is said once per disappearance.** Reconciliation opening
    a tombstone proposal and a synced tombstone withdrawing the same
    projection are two passes noticing one event; the guard is that the
    feed's last word about that path is not already `removed`. Anything
    that puts a file back writes a row of its own, so a second genuine
    removal is never suppressed. A locally deleted file is announced when
    reconciliation notices it, not when a person answers the proposal:
    the file is not in the vault from the moment it goes.

  - **mtime and the racy moment are nanoseconds** since the Unix epoch on
    both platforms (`SystemTime::duration_since`), so the racy comparison
    is exact and unit-free of platform. `recorded_ns` — the moment the
    record was written — is the third column § 3 did not name; git
    compares against its index file's mtime, and a per-row moment is the
    same rule without a file to stand for it. A racy row that hashes
    clean is re-armed with a later moment, which is git's smudge in
    reverse and is what makes the entry settle instead of being hashed on
    every pass for ever.

  - **`verify` re-arms as well as reports.** § 2.5 makes the full hash an
    explicit action; a `verify` that only reported would leave the stat
    rule going on missing the same file. It reports only what the stat
    rule *would* have missed — a file whose record already disagrees is
    an ordinary external edit reconciliation has yet to take in, and
    calling that damage would make every unsaved edit look like one.

  - **`reconcile_path` refuses what it cannot be about.** A path outside
    the vault, or one that is not a `.md` file, is `VaultUnusable` rather
    than silently doing nothing: only Markdown files project, and a
    caller's bug should not be invisible.

  - **Stated, because it is true rather than tidy.** Two processes
    projecting the same object in the same instant both find no record,
    both write the file, and both announce it — the feed reports what
    happened, and the file really was written twice. The sequence stays
    dense and unique (proven across operating-system processes), and
    re-deriving from a path a consumer is told about is idempotent, so
    nothing is lost by it. Avoiding it would mean serialising projection's
    file writes across processes, which would cost multi-instance the
    property that makes it work.

  - **Not in scope, and worth the next stage knowing.** `project()`
    renders every live object on every pass, and rendering a text
    capture reads that capture's preserved bytes; K2's stat rule covers
    the *projected* files, so an idle `project()` still reads one blob
    per text note. Gate 6 is measured on reconciliation, which is what
    § 2.5 names. The same treatment for rendering wants the object's own
    stat facts and is a separate change.

- **2026-09-04, K3 delivered.** The system index: `.gilune/local/index.db`
  beside `kernel.db`, its own schema version 1, stamped with the feed's
  epoch, maintained from the feed in the pass that recorded the change.
  Gate items 3 and 4 are evidence; 5 is evidence in the small (an
  observer counts indexer calls) and belongs to P1 for the agenda; 8's
  measurement is recorded below.

  **The database.** `index_meta(key, value)` holds
  `index_schema_version` and `feed_epoch` — both **text**, because the
  column has no declared type and a number written there comes back a
  number, which made every handle silently discard the last one's work
  until a test caught it. `namespaces(namespace PK, version, cursor_seq,
  last_error, last_error_ms)` is the per-namespace cursor.
  `predicate_kinds(namespace, predicate, kind)` is the value typing.
  `facts(rel_path, line, namespace, predicate, value, num, typed)` with
  indexes on `(predicate, value)`, `(predicate, num)`, `(rel_path)` and
  `(namespace, rel_path)` — the last so a namespace's rows for one path
  can be replaced without a scan. `lines(id, rel_path, line, field,
  text)` holds the indexed text with an FTS5 external-content table over
  it (`content='lines'`, insert and delete triggers) so replacing one
  note's rows is an indexed delete rather than a scan of the whole
  full-text index.

  - **`typed`, beyond the columns § 2.3 named.** The decision asked for
    values "stored as text with a sortable numeric or date shadow"; a
    shadow alone cannot say that a value *failed* its declared kind, and
    `due: tomorow` would then sort after every real date. So a row
    records whether its value took its kind, and an ordered comparison
    requires it. `Number` fills `num` and orders on it; `Date` is
    normalised to `YYYY-MM-DD` where lexicographic order is
    chronological; `Text` orders as text. **Unparseable typed values are
    kept exactly as they arrived** — somebody looking for what they
    actually wrote finds it by equality — and take no part in an ordered
    comparison. A bound that parses as a number asks a numeric question
    and only `num` answers it; any other bound compares text and
    excludes numeric predicates, so `weight >= 10` never reads "10" as
    less than "9". Dates are day-granular: a time component is dropped,
    and a value that needs one declares itself `Text`.

  - **Lines are 1-based within the note's body**, front matter split off
    — the same number `MatchedLine.number` and `TaskHit.line` already
    carry, which is what a shell navigates by. (`MdBlock.line` is
    0-based and body-relative; the shells already differ there and the
    index follows the search surfaces, not the parser.) The text handed
    to an indexer is that body, so a plugin counts what the kernel
    counted. Front-matter facts are **not** in this stage: an indexer
    never sees the `---` block, so `[key:value]` needs either a
    kernel-owned front-matter namespace or P2 keeping its own reader —
    named here so it is a decision rather than a discovery.

  - **The tokenizer is `trigram`**, and it was chosen to preserve what
    the shipped language already means. `Search.kt` matches a bare term
    with `contains(..., ignoreCase = true)` — a case-insensitive
    substring, inside a word, with a quoted phrase matching spaces and
    all — and `unicode61` would have made those word matches with prefix
    queries, silently changing every existing query's meaning. Trigram
    is exact about it. **Cost:** ~5.8 MB of `index.db` for 1,000 notes
    of 20 lines (20,000 indexed lines), which is roughly three times the
    text. **Behavioural delta P2 must honour:** a trigram index holds no
    token shorter than three characters, so a one- or two-character term
    is answered by a `LIKE` scan of the indexed lines instead — the
    meaning is identical, the cost is not; and `LIKE`'s case folding is
    ASCII-only, so a two-character non-ASCII term is case-sensitive
    where a three-character one is not.

  - **`Constraint::Text` carries a target, and the note's name and path
    are indexed as note-level rows.** A bare term in the shipped
    language matches the body **or** the note's name, and a conjunction
    cannot express an `or` — so the `or` lives inside the one
    constraint: `Any` reads body lines and the name, `Body`, `Name` and
    `Path` read one apiece. A name row is note-level, so a term matching
    a note's name holds on every line of it, which is exactly what
    `searchVault` does today with `type:task`. `path:` is answerable
    without a post-filter for the same reason.

  - **Note-scope join.** At `Line` scope the answer is the lines
    satisfying every constraint **at the same anchor** — a note-level
    fact satisfies every line of its note, two line-level facts on two
    different lines do not join. At `Note` scope it is those lines'
    notes, plus notes whose note-level facts satisfy everything on their
    own. A query made only of note-level constraints therefore answers
    nothing at `Line` scope: it names no line, and answering it with
    every line of the note would return the vault. Ordering is
    `(rel_path, line)`, always.

  - **`Ne` is "no fact with that predicate and that value at this
    anchor"**, not "some fact with another value": a line carrying
    `colour=red colour=blue` is excluded by `Ne red`, because a line
    that says it is red is not a line that is not red whatever else it
    says. `Ne` and `NotExists` are **filters, not generators** — they
    narrow what other constraints found and name no anchors — so a query
    with nothing positive in it answers nothing rather than the vault,
    and at `Note` scope a negative is judged at the anchor, so a
    note-level anchor is not excluded by a line-level fact.

  - **Two operators beyond the brief's list, each with the caller that
    needs it.** `NotExists`, because `done:none` is "no `done:` token on
    this line" and nothing else in the list expresses it; `Contains`,
    because `[key:value]`, `file:`, `path:` and `content:` are all
    *contains* in the shipped language and `Prefix` is not.

  - **A line beyond the note's length is stored as the plugin gave it.**
    The kernel never interprets a fact, and a line number is the
    plugin's claim about the note it was handed; counting the note's
    lines to police that claim would be the kernel reading the plugin's
    mind, and dropping the fact would lose data silently. A consumer
    bounds-checks the file it opens, which it must do anyway.

  - **A move re-keys; a namespace with nothing at the old path
    derives.** A move changes where a note is, not what it says, so the
    facts are re-keyed and no indexer is asked again. That alone would
    break `incremental == rebuilt`: a rebuild replaying `Created(a)`
    then `Moved(a→b)` finds no file at `a`, so there is nothing to
    re-key. Deriving at the new path when the old path held nothing
    closes it, and the kernel's own text index derives either way
    because its name and path rows *are* the path. Re-keying nothing
    inside the transaction means another process moved it first, which
    is the rows already being where this one was about to put them.

  - **An indexer that throws stops its own namespace at that note.** The
    pass completes, everything before the note is written, the cursor is
    set to one short of the refusal, the message is recorded against the
    namespace and readable from `index_status`, and every other
    namespace finishes the feed. **Where a refusal is surfaced:** in the
    returned `IndexPass`, and durably in `namespaces.last_error`, which
    clears when the namespace catches up. Deliberately **not** an
    integrity alert: the alert kinds are a coupled surface both shells
    read, and a plugin failing to read a note is not damage to the
    vault. `project`, `reconcile` and `reconcile_path` swallow index
    failures entirely — the index is derived, and a disposable database
    must never be able to stop the vault being written.

  - **The whole database is discarded rather than migrated.** A foreign
    epoch, a schema version this build does not know, or a file SQLite
    cannot read a row out of: all three are answered by dropping
    everything and rebuilding from cursor 0. There is no migration path
    and there is deliberately not going to be one.

  - **A namespace belongs to the plugin whose id it is or is under**:
    `org.memory.tasks` owns `org.memory.tasks` and
    `org.memory.tasks.lines`, and not `org.memory.tasksextra`. Matched
    by a half-open range on the primary key rather than `LIKE`, which
    would have taken the third. `remove_plugin` drops them; the notes
    are untouched.

  - **Registration is per handle, per process** (the external-processor
    rule). A process with no indexer for a namespace never touches that
    namespace's facts — the CLI projecting a vault leaves the app's
    index alone — while the kernel's own `text` namespace is maintained
    by whichever process passes. Predicate kinds are rewritten on every
    registration; **the version is what asks for a re-derivation**, so a
    plugin that changes a predicate's kind without changing its version
    leaves the facts typed as they were.

  - **`NoteIndexer` returns a result**, where the brief's sketch
    returned a bare list. A uniffi foreign trait with an infallible
    signature turns a Kotlin exception into a panic crossing the
    boundary; `KeyWrap`'s shape does not, and a throwing indexer is a
    case the gate asks for by name.

  - **`TextTarget`, not `TextField`.** The generated Kotlin type would
    collide with the Compose composable in exactly the files P2 will
    write.

  - **Measurement (debug build, this machine, quiet).** 1,000 notes ×
    20 lines: built in 6.45 s, 20,000 indexed lines, 5.80 MB of
    `index.db`. One edit to one note: 140 ms for the reconcile and the
    index together, **one** indexer call, that note's 20 line rows
    replaced by 2, two file reads (reconciliation's and the index's).
    One query: 147 µs. A release build and § 8.8's before-and-after on
    the owner's desktop belong to stage S, which is where a shell is
    actually asking.

  - **Known gaps, stated.** An **import candidate is announced once**
    (`INSERT OR IGNORE` in `record_import`), so an edit to a file the
    kernel does not claim never reaches the feed and never reaches the
    index until the file is imported or the index is rebuilt; that is a
    K1 property, not an index one, and the property test avoids editing
    imports because of it. The index reads a changed note a second time,
    after reconciliation has already read it — threading the text
    through would couple the index into the projection's internals. A
    quarantined file's rows are deleted, so its text is unfindable while
    it waits for a person. There is no compaction, so nothing yet needs
    the reader cursors § 3 mentioned.
