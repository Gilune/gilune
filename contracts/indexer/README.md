# Indexer contract (org:memory, v0.1)

The third seam between the kernel and a plugin, beside the processor
contract and the sync transport: **a pure function from a note's text to
facts about anchors** (ADR 0020 § 2.3).

A processor interprets an object and writes an interpretation. An indexer
says nothing durable at all — it describes what is already in the vault,
and the kernel keeps the description up to date and throws it away when
the plugin goes.

```
index(rel_path, text) -> [fact]
fact   { line: option<u32>, predicate: string, value: string }
anchor = (rel_path, line)          // line absent = the note itself
```

## What the kernel promises

- **The text is the note's body**, front matter split off, and a fact's
  `line` is **1-based within that body** — the same line number the
  shells' search surfaces already carry, so an anchor addresses the
  buffer a person sees. `None` anchors a fact to the note, and a fact
  about a note satisfies every line only under the legacy/inherited constraint
  scope. The [search contract](../search/README.md) adds explicit `Exact` and
  `DocumentRoot` constraints without changing stored facts or indexer inputs.
- **Called once per changed note per pass**, in the pass that recorded
  the change, from the kernel's change feed (ADR 0020 § 2.1). Never on a
  note the feed did not name; never on the whole vault except a rebuild.
  An explicit `reconcile_path`/`reconcilePath` save notification reads the
  named file even when size and mtime match the cache: those values cannot
  distinguish rapid same-size edits on coarse clocks. Identical content
  emits no new change. Background discovery retains its stat shortcut;
  this does not introduce source-file reads into queries.
- **Predicates are opaque.** The kernel stores, orders and joins them and
  interprets none of them, exactly as it interprets no event type. No
  domain word appears in kernel code.
- **Facts are replaced whole per note**, so an indexer is a function and
  not an accumulator: line numbers shifting on an edit is not a problem
  it has to solve.
- **The namespace is the plugin's.** It is the plugin id or a name under
  it, and removing the plugin drops it (ADR 0011's removal rule). The
  notes are untouched.

## What an implementation promises

- **Purity and totality.** Same text, same facts. No vault access is
  offered and none is needed.
- **A declared kind per predicate** — text, number or date — at
  registration, so ranges over its values mean something. A value that
  will not take its kind is kept as it came and left out of ordered
  comparisons.
- **A version.** Change what the indexer means by its facts and change
  the version; that namespace, and only that namespace, is derived again
  from the beginning of the feed.
- **Failing is allowed.** An implementation that refuses a note stops its
  own namespace at that note — the pass completes, the refusal is on the
  record, every other namespace carries on — so refusing is a state, not
  a crash.

## Profiles

- **Bundled** — a Rust implementation of
  `gilune_kernel::index::NoteIndexer`.
- **Over the FFI** — `NoteIndexer` as a uniffi foreign trait, implemented
  in Kotlin by the shells, registered per handle and per process with
  `register_indexer` (the `KeyWrap` precedent, ADR 0019 § 4; the
  per-process rule is the external processor's, ADR 0018 § 2). It returns
  a result rather than a bare list, because a foreign implementation can
  throw and a panic must not cross the boundary.
- **Sandboxed** — `index-note(text) -> list<fact>` in the WIT processor
  world. **Deferred**: the WIT half is not written, and this README is
  the contract until it is. A pure function over text is precisely what a
  capability-checked component can do with no grants at all, which is why
  ADR 0020 § 2.3 named that route before there was a host for it.

## Querying what was contributed

Application search uses [`search(request)`](../search/README.md), which
returns complete pages. The anchor API below remains available, with its
original same-anchor join semantics; note scope there collapses matching
anchors and is not document-wide matching. ADR 0021 amends the former
candidate-then-file-evaluation application path.

`query(constraints, scope, limit) -> [anchor]` is a **conjunction**, and
it is the kernel's, not any plugin's: `type:task due:week @alice` is a
join on the anchor across three contributors and nobody wrote the join
(ADR 0020 § 2.4). A plugin owns the words and how they compile to
constraints; the kernel owns the storage and the join. The exact
semantics — the note-scope join rule, what `Ne` means, how a text term
matches — are recorded in that ADR's § 10 K3 entry, which is the
authority for anyone compiling operators.

## Conformance

The kernel's own suite (`gilune-kernel::index`) holds the rules: two
indexers sharing nothing joining on one line, a version bump re-deriving
one namespace alone, removal taking a namespace and nothing else, and a
property test over randomised edit sequences holding
`incremental == rebuilt`. `gilune-ffi`'s `index_surface` holds the
boundary's half, a throwing implementation included.

## Opt-in full-source callbacks

`SourceIndexer` / `register_source_indexer` and the SDK
`SourceIndexerRegistration` opt into exact full Markdown source, including front
matter and CRLF. Existing `NoteIndexer` registrations still receive the legacy
body text with its existing newline normalization. Core `index_source` defaults
to invoking that body callback; the FFI source adapter explicitly overrides it.
Facts retain the same root/body-line coordinate contract for both callback types.
Neither callback receives a kernel handle or filesystem authority.

The source is retained from the same maintenance read used to derive text and
front-matter facts. All registered namespaces share it within the existing bounded
maintenance batch; no per-plugin file read is introduced. The tradeoff is retaining
the raw source alongside the legacy normalized body for that batch. Search never
calls either callback. A regression registers both forms on a CRLF document and
checks exact source input, unchanged body-line matching, and one file read for the
whole rebuild pass.

The same namespace ownership, enabled/removal behaviour, failure handling and
version rules apply. Switching callback input form or normalization rules requires
a version bump and re-derivation. The SDK rejects registering both forms for one
bundled namespace. People used source indexer version 2 for page properties and
now uses scoped indexer version 3.

## Source-qualified locations and facts

`ScopedIndexer` / `register_scoped_indexer` and `ScopedIndexerRegistration` opt
into a supplied `SourceTextSnapshot`: exact source, content fingerprint, shared
Markdown locations and a generic parsed YAML value tree. The kernel parses the
snapshot once per changed source in a maintenance batch. Callbacks remain pure;
the SDK's `SourceView` navigates supplied byte offsets, locations and values
without accessing a kernel handle. One bundled namespace chooses exactly one of
the body, full-source or scoped callback forms.

`ScopedFact { location, predicate, value }` uses `DocumentRoot`,
`BodyLine { number }` or `MarkdownItem { key }`. The kernel validates that every
location exists in that snapshot. A list item's key is its full-source start byte,
valid only with that source fingerprint; it is not a durable object ID. Shared
Markdown parsing determines item boundaries and own ranges excluding nested
items. Domain producers independently select locations and interpret their own
syntax. No plugin-specific syntax is interpreted by the kernel.

Derived index schema 8 keeps these rows in the existing facts table. A compact
source map holds ranges, not another copy of the prose. Every scoped namespace
records which source it processed, including successful outputs containing no
facts. That evidence is necessary for negative filters. All data follows ordinary
maintenance, rebuild, move, deletion and plugin-removal rules. Invalid snapshots
named by the change feed record an indexer refusal; ordinary text indexing remains
available. A file refused by reconciliation before a feed event is still outside
that maintained snapshot; discovery must reconcile and report such files separately.

Schema 5 adds a compact expression index on `(predicate, gilune_fold(value))`.
The kernel's existing simple case fold is computed into that index on fact writes;
literal `Prefix` constraints use lower/exclusive-upper BINARY bounds over the
folded key. Empty prefixes and prefixes ending in maximum Unicode scalars use
the appropriate open upper bound. Wildcards remain literal; equality, contains,
domain normalization and plugin fact contracts are unchanged. Schema 4 derived
databases rebuild normally without rewriting source documents or plugin data.

`query_locations(LocationQuery) -> LocationPage` is a paged **fact conjunction**
over these locations. Callers declare the contributing scoped namespaces and
use explicit `FactAt`/`FactIn` locality. Only facts from those namespaces and the
same source version participate. All required producers must have processed that
version, even when a negative tests absence. `Exact` joins only the same location;
root inheritance requires `DocumentRoot` or `Inherited` explicitly. Results carry
path, fingerprint, map version, location, full range and own ranges. Multiple
matching values deduplicate to one location.

The request selects all/root/line/item locations, limits each page to 1–500, and
uses a query/generation-bound cursor. Ordering is path, location kind (root, line,
item), then line number/item key. There is no total-result cap. Changed indexes
expire cursors; incomplete namespaces or differing source versions are visible.
Queries use only the derived database. Returned fingerprints identify indexed
bytes, not current filesystem freshness or verified managed object identity.

This discovery API requires a positive fact generator. Complete application
search uses `search_items` for text/residual clauses, negative-only search,
ranking, grouped snippets and type/operator composition over these same locations.
Document-mode search can find contained item facts. Legacy line query/fact inspection APIs
exclude item facts instead of misreporting them as root facts. Legacy indexers
are never silently promoted from lines to items.

Schema 6 adds private source, target and namespace-qualified predicate IDs with
covering fact indexes. A target binding requires the current source version and
exact location; stale producer facts remain available as previous evidence but
cannot bind to a replacement revision. Legacy unversioned facts have no target
binding. Public callbacks and result identities are unchanged. `query_locations`
projects ranges from target metadata; schema 8 below extends target execution to
fact-only item search. Wide fact columns and source maps remain during integration,
so this stage adds temporary storage/write overhead. Older derived indexes rebuild
without changing authored documents. See [ADR 0025](../../architecture/decisions/0025-compact-index-targets.md).

Schema 7 resolves private predicate/target IDs in batches inside the existing
producer write transaction and inserts them with each fact. This removes repeated
per-fact trigger updates without changing callbacks, ownership, source-version
checks, receipts or query semantics. Unversioned/stale facts remain unbound;
source replacement still invalidates old fact bindings. Older disposable indexes
rebuild normally. The shared writer contains no plugin-domain interpretation.

Schema 8 adds line-coordinate and source-byte indexes for bounded item projection.
Fact-only `search_items` queries select/order/page current target IDs in SQL and
load only returned ranges and facts. Grouping, explicit scope, negative filters,
typed value comparisons, duplicate facts and multi-valued sort predicates retain
their existing semantics. Text/highlight/residual requests keep the previous
execution route. The current exact total remains synchronous in the page's read
snapshot; no asynchronous-count contract is implied. Wide compatibility storage
remains, and no callback or public result shape changes.

Schema 4 also records whether a namespace uses scoped input. Changing that mode
without increasing the producer version is rejected. Front-matter facts and
successful outputs are source-qualified by the kernel so explicit page-property
filters can join current item facts. This is a disposable index rebuild; stored
Markdown and domain objects are not migrated.

Conformance in `index::scoped` joins independent colour/shape producers, exercises
continuations, nested items, explicit root scope, membership, negative absence,
empty-output source versions, cursor expiry, lifecycle and more than 10,000
matches with zero query-time file reads. People exercises the same API across FFI.

## Current storage: schema 12

`fact_values` stores location/predicate/target IDs and opaque value/typed data.
`fact_frames` stores each path and optional source version once;
`fact_location_slots` stores anchors by frame ID. `fact_locations` projects their
existing relational shape without copying the path/version into every anchor;
`index_predicates` owns each namespace/predicate pair. The `facts` SQL view is a
relational compatibility projection, not a second table. Wide fact indexes and
per-fact repeated paths, source hashes, namespaces and predicates are removed.
Value, numeric, folded-prefix, target and location access have compact indexes.

Stale, unversioned and invalid/unavailable targets retain distinct location
records and unbound facts. Source replacement detaches bindings; only facts for
exactly the new source version can rebind. Successful producer replacement and
namespace removal collect unused locations and frames, without discarding failed producer
evidence. Batches intern locations and predicates inside their existing write
transaction. Source-map JSON remains parser/rebuild evidence; result projection
uses target ranges and indexed text. All public fact/search/lookup/location APIs
read this same store. An older disposable schema rebuilds from sources; it does
not migrate domain objects or add cross-plugin dependencies.

Text maintenance compares each newly produced document with its stored text
rows. Exact matches keep their private FTS row IDs; identical text moved within
the same field updates its line coordinate without rewriting FTS. Removed or
changed text uses the existing delete/insert triggers. Duplicate rows are matched
as a multiset, and no row identity is exposed as persistent document identity.
This needs no plugin edit stream and works with externally edited files. All
changes still commit under the existing version/cursor checks and rollback
boundary. Public APIs remain unchanged.

Source-map updates normally detach old fact bindings; only exact source-version
facts can bind. For an aligned, bounded edit to one path, the kernel can prepare
all available producer outputs before taking a write transaction. It verifies
all producer versions/cursors again inside that transaction. Only when every
owner of the old source-qualified locations has successful fresh output may it
advance shared locations and reuse target slots together with those outputs.
A missing or refusing producer prevents that reuse for the path. Its old facts
remain at their original source version and unbound; successful producers and
independent failure receipts can still advance. No plugin callback runs while
the write transaction is held. A database failure rolls back the whole publication.

Reused slots can move to new coordinates. Exact first-line text is a slot-matching
preference, never proof of unchanged domain meaning. Complete validated output
still determines every fact value. Within reused locations, facts are compared
as ordered slots; adding/removing facts in one location does not shift the
comparison across other locations. Duplicate multiplicity is preserved. Private
row order across locations and private target IDs are not public identities.
The general path retains producer-wide comparison and independent transactions.

Schema 12 normalises revision validity. A coherent publication advances one
frame instead of rewriting a source hash into every location. Removed anchors
retain their old frame until fresh producer output removes their facts. A held
destination revision uses conservative replacement rather than merging frames.
Frame changes, coordinate changes, facts and receipts share the transaction and
reader snapshot. Old disposable indexes rebuild; documents remain unchanged.
Schema 13 stores geometry relative to small position blocks and orders targets
with private sparse labels. Current fact locations and indexed body text follow
retained slots; stale evidence is detached and materialised at its original
coordinates before live geometry changes. Public line numbers and byte ranges
are reconstructed against the exact returned source revision. Private labels
and target IDs carry no domain identity. Geometry, source text, facts and receipts
publish together; all readers retain the existing transaction snapshot guarantees.
The query model and all public result/source identities remain unchanged.

## Optional local scoped input

`LocalScopedIndexer.index_locations(path, source, locations)` and
`register_local_scoped_indexer` add an opt-in contract. Existing scoped and legacy
registrations retain their complete-document behavior. Bundled SDK registrations
may provide `createLocal` alongside the original full-output factory.

The callback returns complete facts **only for the selected locations**, including
an empty result when a previously recognised construct no longer qualifies.
Returning a fact outside the selection is rejected. The source remains exact,
with the shared parser map and metadata available as context. The locality promise
is stricter than the complete-document contract:

- Root facts depend only on document metadata.
- Item facts depend on the item's own source ranges, excluding child items, plus
  metadata and the parser's ordinary-text eligibility within those ranges.
- Line facts depend on that line's source, metadata, ordinary-text eligibility
  and whether its text belongs to an item.
- Fact values must not depend on unrelated locations, absolute coordinates,
  parent/sibling counts or the source-version token. Fact locations still use
  the exact coordinates of the supplied snapshot.

The kernel fingerprints these inputs, reuses successful outputs for unchanged
inputs, and remaps their locations when content moves. Repeated identical inputs
are exchangeable under this contract; duplicate facts remain duplicates. Header
changes invalidate all dependent inputs. Parser-context changes, including fences,
can invalidate text beyond the edited line. Full Markdown parsing currently still
runs to establish that context; local input selection prevents it from forcing
full plugin recognition.

The cache is disposable and scoped to a registration instance: one document,
with a 32 MiB estimated payload limit. Restart, another document, registration
replacement, overflow or failed derivation can require complete recognition.
No cached result is evidence of filesystem freshness or an advanced index receipt.
The writer still validates the assembled facts and the namespace's commit state.
No persistent plugin SQL table or second authoritative fact store is added.

People and Tasks opt in independently. Native fixtures and `LocalIndexerTest`
compare local and complete results across edits, shifted locations, nesting,
metadata, Unicode and code exclusions. See the
[implementation report](../../architecture/research/local-input-publication-2026-09-06.md)
for measurements and remaining whole-document storage costs.

## Line history (ADR 0028)

A scoped indexer's snapshot carries, on each `SourceLine`, `since_ms`: when
that body line reached its current text, from the kernel's replay of the
note's revisions. Null for a line whose date is unknown — present when Gilune
adopted the note, or on disk but not yet recorded — and null everywhere a
snapshot is built outside indexing (`inspect_source_text`, editors). It is
derived, device-local and best-effort: a fact built on it is an
interpretation, never the record. Native implementations opt in through
`index_scoped_with_history` / `index_scoped_locations_with_history`; the
defaults drop it. The local-incremental wrapper includes a location's line
dates in its fingerprint, so a date change re-derives that location.
