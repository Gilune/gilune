# Search contract (v0.3)

[ADR 0021](../../architecture/decisions/0021-kernel-search-results.md) puts
complete search results at the kernel boundary. Plugins own vocabulary and
predicate meaning. Consumers render results and request another page.

`Store::search(request, extension?) -> SearchPage` is exposed through the
typed UniFFI `Gilune.search` method. The packaged bindings and native library
must come from the same target build. The CLI exposes the same service with
`search`; its older `query` command continues to return anchors.

## Requests and matching

- A request ANDs clauses. A clause ANDs generic text/fact constraints;
  `negated` complements the whole clause, including a range with two bounds.
- `line_mode=false` matches documents: different terms may match different
  lines. `line_mode=true` matches individual body lines, with note-level
  name/path/properties applying to each line. `group_by_note` groups matching
  lines into one document result; false returns individually pageable lines.
- Text means a literal substring with Unicode simple case folding, shared
  by the trigram index, short-term matching, and highlighting. A clause may
  explicitly request case-sensitive text. Multiline document substrings are
  evaluated against ordered indexed body lines.
- Plugins compile complete meanings declaratively. A clause with `residual`
  instead names a pure callback key. Conservative prefilters belong in
  separate positive clauses; negating a prefilter is not generally sound.
  A non-indexed line type may provide candidate body-line numbers through
  `extension_candidates` and the extension's `candidates` method.
- `required_namespaces` names the indexes needed by the query, in addition
  to the kernel's text and frontmatter namespaces. `extension_key` identifies
  callback configuration and contributes to continuation identity.
- `body_needles` and `name_needles` describe presentation/ranking; they do
  not replace matching constraints. Default order is name match first,
  descending highlighted-span count, path, then line number. A nonempty
  `sort_predicate` chooses ascending lexical fact value, absent last, then
  path and line. Normalised ISO dates use this order; it is not numeric sort.

## Scoped facts and set membership

`Constraint.FactAt(predicate, op, value, locality)` makes fact scope explicit.
`Constraint.FactIn(predicate, values, locality)` tests whether any stored value
is exactly equal to any supplied value. Values are opaque strings, using the
same equality as `Fact(Eq)`; the kernel does not decode plugin pair encodings.

| Locality | Line search | Document search |
| --- | --- | --- |
| `Exact` | Only facts on that body line; roots do not qualify it | Root or contained matches, retaining document-search semantics |
| `DocumentRoot` | Only root facts, explicitly qualifying the containing document | Only root facts |
| `Inherited` | Line facts plus root facts | Root or contained matches |

The existing `Fact` variant retains `Inherited` behaviour. The legacy anchor
query API also honours these scopes, but retains its existing anchor generation
and same-line conjunction rules. It is not interchangeable with document search.
Markdown-item search uses the explicit source-qualified targets described below.

Negate the enclosing clause for `NOT IN`. A missing predicate passes `NOT IN`;
a multivalued location fails if **any** value belongs to the excluded set.
`IN []` is false and `NOT IN []` is true. The kernel sorts and deduplicates sets
for continuation identity; reordering/repeating members does not expire a
cursor. Bounds apply before deduplication: at most **256 values and 65,536
aggregate UTF-8 bytes** per set. Both native search and anchor query reject
oversized sets. SQL binds the list as one JSON parameter, keeping parameter
usage independent of the number of members; values are never SQL fragments.

The shared query compiler accepts:

```text
type:some-type
example.field:binding IN ["one", "two"]
```

`NOT IN` or a leading minus complements the entire set clause. Combining both
negation forms is rejected. `IN`/`NOT IN` are uppercase tokens separated by
whitespace. Lists use strict JSON string-array literals, including JSON escapes
and valid Unicode surrogate pairs; numeric/boolean/null/nested values are not
accepted. A plugin validates domain-specific values and supplies their canonical
string encoding. List parsing, bounds, negation and kernel execution remain
shared. Scalar/date/number domains can expose their own string-literal binding.

A registered operator supplies `setBinding(argument)` with a predicate,
locality and pure single-value encoder. For the example, `example.field` is the
operator name and `binding` is its argument. There is no selected-type argument,
filesystem handle or separate query per member. Operators declare their required
index namespaces; the compiler combines these with the type's dependencies.

Malformed, unsupported and unavailable membership fields fail visibly. Unknown
qualified scalar fields such as `example.field:value` also report unavailability.
Ordinary unknown unqualified scalar keywords retain their legacy text meaning,
as do ordinary prose and quoted filter examples. Duplicate operator names,
including type/global and built-in collisions, now fail instead of overriding
by registration order. These deliberate pre-1.0 grammar changes are covered by
`SearchMembershipTest`; stored documents and index rows need no migration.
The changed UniFFI enum requires regenerating bindings from each target binary.

## Results and bounds

Each hit contains its path and name, matching body lines, facts, highlight
ranges, complete match count, and the count of matching lines omitted from
its snippets. Line numbers are body-relative and 1-based. Ranges are UTF-16
offsets with an inclusive start and exclusive end, ready for JVM display.
Blank and trailing body lines are retained so those coordinates stay stable.

`total_hits` is computed after all predicates and before pagination. Pages
contain 1–500 hits and each hit quotes at most 0–200 lines. These bound result
cardinality, not the length of a source line. There is no 10,000-candidate
cutoff. Declarative queries without relevance select bounded pages in SQL. Relevance
and residual callbacks inspect candidates with a page-sized heap; Display facts are fetched
after ranking when they are not needed to sort, once per surviving document
even when many of its lines occupy the page.

`next_cursor` is opaque, versioned, and bound to the complete request and
index generation. A changed request, index update, or rebuild expires it with
`SearchCursorExpired`. Consumers restart; they never append different index
generations. Each page uses one consistent read transaction. Reading later
pages repeats selection and ranking; this deliberately avoids keeping a
transaction or an unbounded result cache alive between UI interactions.

## Freshness and failure

Search reads only the index. It does not open Markdown files, reconcile, or
run indexers. `index_complete=false` and `issues` distinguish pending,
unavailable, or failed required indexes from an authoritative empty answer.
Freshness is relative to the committed change feed, not unobserved edits on
disk. Maintenance remains a separate operation.

Callbacks receive indexed document snapshots (or narrowed indexed lines),
not file handles. They must be pure and must not re-enter Gilune. Compatibility
methods hold the native Store; independent query handles use their own read
connection. Callback exceptions fail the query visibly;
the Kotlin adapter translates them to the declared UniFFI error type.

The shells run queries on IO workers. Independent query handles below support
native cancellation as well as rejecting obsolete publication. A synchronous
compatibility call already running finishes on its worker. Retry advances the index and refreshes consumers. Ordinary
read failures retain prior entries and pending feed work. Explicit rebuild
discards the old index, but a failed rebuild retains its retry position.

## Evidence

- `gilune-kernel::search::tests::membership`: opaque values, correlated value
  encodings, scopes, negation, empty/bounded sets, cursor equivalence, and full
  paging over 10,051 matching lines with the source file removed after indexing.
- `SearchMembershipTest`: two independent plugin registrations, exact field
  ownership, compilation through FFI, strict list parsing, Unicode, old prose,
  collisions, required namespaces and continuation across equivalent queries.
- `gilune-kernel::search::tests`: document/line scope, Unicode and UTF-16,
  zero vault reads, complete ranking, continuation invalidation, plugin
  failures, missing namespaces, and more than 10,000 candidates.
- `gilune-kernel::index::tests`: read failures, Windows exclusive locks,
  independent-handle races, fixed feed boundaries, indexer-version ownership,
  and incremental/rebuild equivalence.
- CLI `system_index` and the FFI search test exercise the public boundaries.
- Kotlin `SearchEquivalenceTest` retains the independent scan oracle.
  `KernelSearchTest` covers late task lines, caps, facts, metadata, callbacks,
  and pagination. `QueryStateTest` covers background execution, obsolete
  requests, paging, and visible failure/retry states.

## Document-root display facts

`SearchHit.facts` carries indexed document-root properties independently of its
line previews. In particular, `max_lines=0` still returns root facts, including for
pages whose body is empty. Line facts are not promoted into that field. Both
sorted and unsorted result paths hydrate only selected hits from the index; the
public kernel regression checks root/line separation after changing the on-disk
file without reconciliation. This additive record change requires regenerating
FFI bindings from each target's binary.

## Markdown-item search

`search_items` uses the same clauses, ranking, grouping and pagination as `search`,
over shared Markdown items. It requires `line_mode=true` and rejects legacy
extractor candidates. The SDK's indexed `SearchType.itemScoped` selects this API;
type and operator registrations remain independent. The kernel has no knowledge
of the domains whose facts select an item.

`SearchHit.items` contains each item's own indexed text, first body-relative line,
UTF-16 highlights and exact item facts. Source evidence includes the fingerprint,
map version, full-source UTF-8 range and own ranges excluding nested items. These
are snapshot-local locations, not persistent object IDs. Root facts remain in
`SearchHit.facts`. The existing `max_lines` limit bounds returned items in this
mode; an item's text can span multiple physical lines. Ungrouped results count
items; grouped results count notes and report omitted items in `more_matches`.

Text and residual predicates inspect the whole item's own content. Fact clauses
join exact item locations; root inheritance must be requested explicitly.
Whole-clause negation, negative-only queries and membership operate on the same
item universe. Required producer receipts must match the indexed source, including
zero-fact outputs. Cursor identity includes the search mode.

All matching, counting, ordering and result construction stay in the kernel.
Indexed lines and compact source maps supply the text: no query-time source reads,
Markdown parsing or second prose index. Document-mode search can match contained
item facts, while explicit document-root constraints exclude contained facts.
Physical-line APIs continue to exclude item facts.

With derived schema 9, all fact consumers read one compact store. Item queries
use private target IDs; lookup uses root target IDs; legacy anchor/document APIs
project their original coordinates through joins, without wide fact copies.
Fact-only items and declarative document/line queries without relevance select
bounded pages in SQL. Item text/relevance and residual predicates inspect compact
targets and reconstruct own text from indexed lines. Relevance and arbitrary pure
callbacks can require inspecting every candidate; bounded output does not promise
bounded matching work. Only retained item facts are hydrated for display.

Value sorting respects the independent minimum across all facts at the target;
matching and sorting may use different values. Missing values sort last and
multiple facts do not duplicate targets. Grouping preserves snippet limits and
complete omitted-match counts. Text/Unicode/CRLF and legacy unversioned-anchor
semantics remain unchanged.

## Independent page and count execution

`prepare_search(request, item_mode, extension?) -> SearchQuery` creates an
immutable query handle. Each `page(cursor?) -> SearchSlice` and
`count(generation) -> SearchCount` uses a separate read-only SQLite connection.
No connection or read transaction survives a call. Preparation initializes the
index under the Store lock; query/count execution does not hold that lock.
Counts can run concurrently with page reads and normal WAL maintenance.

`SearchSlice.total_hits` is nullable: `None` means not counted, never zero. SQL
page routes omit the count; routes that already inspect the full candidate set
for relevance/callback matching can return their known total. `SearchCount`
contains an exact total, generation, completeness and issues. It rejects a
changed generation with `SearchCursorExpired`, including index rebuilds. The
immutable handle binds count and page requests to the same query and callback
configuration. Consumers must not publish a count under another generation.

`cancel()` permanently cancels the handle and all its in-flight work. SQLite
progress handlers interrupt long SQL execution; cancellation is also checked
before execution and before publishing the result. A currently running foreign
callback must return before native execution can finish; it is not preempted.
Both shells cancel replaced searches, display pages before counts, represent
pending/unavailable totals explicitly, and prevent old counts from replacing a
new query's state. Full enumeration does not request an unused total each page.

The existing `search`/`search_items` methods and `SearchPage` retain their exact,
synchronous-total contract for existing callers. The new FFI object/records
require bindings regenerated from every packaged target binary.

`search::tests::items` covers source coordinates, nested scope, root filters,
negation, callbacks, grouping, paging and cursor expiry. `ItemSearchTest` combines
independently registered types and People operators through FFI, including an
unrelated outline type without Tasks installed and embedded filter evaluation.
