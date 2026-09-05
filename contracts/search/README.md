# Search contract (v0)

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

## Results and bounds

Each hit contains its path and name, matching body lines, facts, highlight
ranges, complete match count, and the count of matching lines omitted from
its snippets. Line numbers are body-relative and 1-based. Ranges are UTF-16
offsets with an inclusive start and exclusive end, ready for JVM display.
Blank and trailing body lines are retained so those coordinates stay stable.

`total_hits` is computed after all predicates and before pagination. Pages
contain 1–500 hits and each hit quotes at most 0–200 lines. These bound result
cardinality, not the length of a source line. There is no 10,000-candidate
cutoff. Ranking scans the complete candidate set with a page-sized heap;
only one candidate document is hydrated at a time. Display facts are fetched
after ranking when they are not needed to sort.

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
not file handles. They must be pure and must not re-enter Gilune: the native
store is held during the call. Callback exceptions fail the query visibly;
the Kotlin adapter translates them to the declared UniFFI error type.

The shells run queries on IO workers. Cancellation prevents an obsolete
answer from publishing; a synchronous native call already running finishes
on its worker. Retry advances the index and refreshes consumers. Ordinary
read failures retain prior entries and pending feed work. Explicit rebuild
discards the old index, but a failed rebuild retains its retry position.

## Evidence

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
