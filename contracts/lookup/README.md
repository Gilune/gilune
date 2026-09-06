# Indexed lookup

Pre-1.0 bundled/native contract, introduced 2026-09-06. No WIT/component execution
contract is claimed. The kernel owns selection, alternatives, ranking, counts,
pagination and freshness. Plugins supply opaque predicates and presentation.

## Query and result

`lookup(LookupRequest) -> LookupPage` selects source-qualified **document roots**
from the existing index. It never walks files, parses Markdown, advances indexing
or invokes plugins. It complements complete item/document search; it does not
change that query language or add a second prose index.

The request supplies:

- `constraints`: a conjunction of `FactAt`/`FactIn`, explicitly `DocumentRoot`.
  Existing equality, prefix, ranges and absence operators retain their semantics.
- `alternatives`: an OR of conjunctions, each with an unsigned priority. A root
  matching several alternatives appears once with its lowest priority. Empty
  alternatives mean no additional restriction; an empty conjunction matches all.
- `sort_predicates`: ascending minimum fact values, after priority, with missing
  values last. Final tie-break is the source path. Normalised sort keys belong to
  the producing plugin. Sorting uses binary indexed values, not locale guesses.
- `display_predicates`: the root facts returned on selected results.
- `required_namespaces`, `page_size` and optional `cursor`.

Bounds: 1–500 results per page, at most 16 alternatives, 128 total constraints,
4 sort predicates, 32 display predicates and 32 required namespaces. Existing
membership-set bounds also apply. Scope is deliberately limited to roots; callers
cannot accidentally combine a page definition with a contained item's facts.

Results include complete `total_hits`, source path/fingerprint, matched priority,
requested display facts, generation and continuation. Rows from distinct source
paths remain distinct even when identifiers collide. Source fingerprints describe
indexed bytes, not current filesystem freshness or verified object identity.
Presentation never resolves such a collision by choosing the first result.

One index read transaction covers each page. Cursors bind an offset to the request
and index generation; changed data, query or rebuild requires a restart. There is
no total-result cap or host-side first-page filtering. Totals are computed in the
same selection pass as ordinary result pages. Alternatives produce candidate
sets, which are deduplicated with their minimum priority. Base constraints and
source evidence are checked at those roots before their sort fields are read.
Only matching roots participate in sort aggregation; only retained page rows
have display facts hydrated. Later pages repeat selection.

## Freshness and lifecycle

All required producers must have processed the indexed source version, including
successful empty output for absence queries. Scoped namespaces and the kernel's
source-qualified front-matter namespace are supported. Legacy body/full-source
producers are reported incomplete rather than having their facts silently promoted.
Pending feed work, missing producers, refusal and source misalignment are explicit
`index_complete=false`/`issues` results. Unreconciled external edits do not change
lookup results until normal maintenance observes them.

Derived index schema 5 adds `(predicate, gilune_fold(value))` for shared literal
prefix matching. SQLite maintains folded keys when facts change; queries fold
their prefix once and seek a BINARY range, preserving existing Unicode semantics.
The old disposable index is rebuilt through the normal schema-version mechanism;
source documents, plugin schemas and public request/response contracts do not
change. Native binaries and their generated bindings remain paired per target.

## Plugin and host seam

`IndexedLookupRegistration` is owned by an exact declared `lookup.indexed` entry.
Factories receive settings. `IndexedLookupProvider.query(fragment)` returns a
query; `present(hit)` returns a label, detail and optional exact insertion string.
Neither callback receives a kernel client, database or filesystem walker. Fact
interpretation and normalisation remain in the plugin. The host controls page size
and cursor and never reorders or filters returned rows.

The host checks the live owner, contribution and settings before/after execution.
Disabled/removed/unavailable providers are inert. Query/presentation callbacks run
outside the native store lock, on a worker; this service must not be invoked from
a native index/search callback. The SDK remains trusted bundled Kotlin, not a
sandbox for arbitrary code.

`LookupContext` identifies request, buffer revision, UTF-16 caret, provider and
fragment. Responses echo it. `rememberIndexedLookup` keys publication by that
context, current plugin state, index refresh key and page cursor. Changed keys
clear old results; cancellation discards late results even when a synchronous
native call finishes on its worker. No derived lookup cache survives those keys.
Incomplete pages surface as an error/retry state rather than selectable suggestions.

`PageCollectionRegistration.lookupProviderId` connects an owned provider to the
standard directory's Find field. Both shells render the same paged list and open
the selected source path. This is the first user-facing consumer. Editor trigger
detection, `@` insertion, `/` connection actions, create-from-lookup and reference
resolution remain separate pending work; returning insertion text is not proof
that those editor interactions have shipped.

## Evidence and limits

Native `index::lookup::tests` covers alternatives, prioritisation/deduplication,
missing sort values, root scope, literal prefix punctuation, negative absence,
namespace/source freshness, cursor invalidation and paging over 10,006 roots with
zero source reads. The scale fixture clones a consistent derived snapshot; it
measures query execution, not filesystem ingestion or a real user's vault.

`IndexedLookupTest` exercises People through FFI, Unicode normalisation, matching
beyond the first page, incomplete definitions, collisions, rebuild and lifecycle.
The compiled Labels fixture uses the same SDK without People or Tasks.
`IndexedLookupUiTest` blocks a provider on a worker while a newer buffer request
finishes, verifies late-result rejection and removes the provider. Directory UI
tests cover find, navigation and loading beyond 30 results.

After candidate-first selection and prefix range indexing, the release scale
fixture returned the first 30 rows in 142.80 ms (median of three warmed runs) and
enumerated all roots at 500/page in 3.12 seconds, versus 203.95 ms / 4.30 seconds.
The separately instrumented seven-sample total median was 156.41 ms. On a distinct-
key distribution, main SQL fell from 130.40 ms to 0.061 ms for one match and from
64.63 ms to 0.023 ms for none. Complete selective calls still take 20–23 ms due
mainly to unchanged freshness checks. The index adds 836 KiB in this fixture.
These are native synthetic measurements, not UI typing latency or a passed
performance budget. See the [optimization evidence](../../architecture/research/lookup-optimization-2026-09-06.md).

## Compact storage integration (schema 9)

Lookup alternatives intersect/exclude private target IDs. Remaining predicates
probe the retained roots, and sort fields read compact target/value indexes.
Required namespaces still constrain predicates and source receipts. Namespace,
source, alternative priority, lexical minima, duplicate elimination, completeness
and offset-cursor semantics are unchanged. This API still supplies its exact total
with each page; the independent count object is part of the search API. The
selective lookup regression checks SQLite VM work over 10,006 roots and requires
no automatic index or population-wide scan for a single matching root.
