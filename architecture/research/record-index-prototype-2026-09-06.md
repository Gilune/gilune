# Queryable targets and structured plugin records: architecture experiment

Date: 2026-09-06. **Test-only prototype. No application, public API or production
index schema change.**

## Recommendation from the experiment

Adopt compact source-qualified targets and a shared query executor that selects
ordered pages before constructing results. This is the strongest performance
finding, and it works with both structured records and scalar facts.

Develop structured records as an additional plugin contract for correlated,
typed fields. Keep a scalar-fact compatibility path. Do not require every plugin
to adopt records, or freeze table-per-record storage as the universal physical
model on the strength of this workload. The facts alternative is competitive;
structured records use less query-store space here but have slower exact counts
in some cases. Keep logical plugin declarations separate from physical storage.

This qualifies the earlier recommendation: the existing facts abstraction does
not inherently require slow cross-plugin queries. Its current storage and
execution strategy are the immediate problem. Richer records also address a
separate expressiveness problem, allowing fields of one connection to remain
queryable together without an opaque pair encoding.

## What was built

The prototype is under
[`search/tests/records_prototype`](../../kernel/crates/gilune-kernel/src/search/tests/records_prototype/mod.rs).
It is included only under `cfg(test)`.

The generic record registry accepts an owner, record name, typed fields,
nullability, one-per-target or set-of-records cardinality, and bounded index
declarations. It validates duplicate declarations, field types, ownership and
target foreign keys. SQL identifiers are generated from internal IDs; plugin
names and field names are not executable SQL. Current experimental limits are
eight fields, four indexes per record, eight related clauses and 64 conditions.

The fixture declarations are independent:

| Owner | Declared record | Cardinality |
| --- | --- | --- |
| Tasks | `state: text`, `due: date?` | One per task target |
| People | `keyword: text`, `person: text` | Many per target |
| Unrelated test plugin | `sensor: text`, `reading: number` | One per target |

The generic query representation supports typed conditions, same-record field
correlation, related-record existence/absence, set membership, ordering and
generation/query-bound continuation. The engine contains no Tasks or People
dispatch. Target identity is an internal integer referring to a source and its
revision-qualified location. It is not a durable task identity or a replacement
for visible text and source provenance.

Two physical implementations share that target foundation:

1. **Structured records:** generated tables keyed by target, an index over the
   task record's state/due/target fields, and indexes for connection fields.
2. **Improved facts:** interned target and predicate IDs, a target/predicate/value
   primary key, and a covering predicate/value/target index. It retains People's
   existing paired value. The benchmark supplies optimized SQL for the same
   query meaning; this is not yet a general replacement DSL-to-SQL compiler.

Both use coalesced month bounds, indexed existence probes, direct SQL LIMIT and
keyset continuation. Counts are separate requests, not repeated on every page.
A bounded positive-relation probe selects a small candidate set when there are
at most 64 distinct targets; broad matches use the ordered index. The threshold
is experimental. Bounded returned candidates are not a universal bound on probe
work for arbitrary distributions or undeclared indexes.

Page and count operations read readiness, generation, candidates and results
inside one short read transaction. A generation change expires continuation.
The prototype uses one readiness flag; real per-producer maintenance, lifecycle
and failure accounting have not been replaced or integrated.

## Actual plugin data and verification

Input is the real 50,000-task, 500-document index from the
[Tasks/People DSL benchmark](cross-plugin-search-2026-09-06.md). The source index
is opened read-only. The loader checks required source-version receipts and
retains roots separately from items: 50,000 item targets plus 500 document roots.

The task and connection adapters convert the actual plugin-produced facts into
their experimental records. This exercises genuine extracted data but **does
not yet add record-producing callbacks to the Kotlin SDK or run the DSL through
a new FFI contract**. The prototype query corresponds to:

```text
type:task is:open people.connection:assign:@alice due:after:2026-08-31 due:before:2026-10-01
```

The benchmark verifies the first 50 ordered target identities against the
existing native search service, and compares complete ordered enumeration
between both prototypes. Every enumerated Alice target is independently checked
against its imported state, paired connection, date and item scope. Expected
counts include 17,500 Alice matches, 25,000 Alice/Bob matches, 32,500 open tasks
due in the month, and 25,000 excluding Bob's assignments.

One extra connection to `needle` is inserted into each temporary query store to
exercise a single matching target. It is deliberately identified as an added
fixture fact, not a connection extracted from the original documents. An absent
handle returns zero. Neither addition modifies the original index or Markdown.

Normal tests exercise an unrelated numeric schema, field/owner validation,
duplicate keys and declarations, date validation, same-record correlation,
root/item separation, membership/negation, missing fields, tied ordering,
readiness errors, cursor invalidation, source deletion and rollback. Transactions
are verified to release on both successful and failed reads.

## Measurements

Two runs of the final unchanged release prototype are retained:
[first run](records-prototype-first-run-2026-09-06.txt) and
[repeat](records-prototype-repeat-2026-09-06.txt). They include SQL, parameters,
query plans and statement counters. No builds or other benchmarks were started
concurrently by this agent; the machine was not reserved exclusively for this
process. The variation between runs is reported rather than hidden.

First-page selection is a median of seven warm samples. Page-plus-projection and
counts use five; source replacement uses nine. Full enumeration is one traversal
per run. Projection reconstructs requested task text and reads its facts through
the same existing source-map/text/fact functions for both models. It does not
include FFI, UI rendering, highlighting, or final `SearchPage` serialization.

For 50,000 indexed tasks and 17,500 Alice/month matches, the repeat measured:

| Operation | Structured records | Improved facts |
| --- | ---: | ---: |
| Select first 50 targets, including readiness/probe/cursor work | 0.114 ms | 0.125 ms |
| First 50 plus text/fact projection | 6.19 ms | 6.59 ms |
| First 500 plus projection | 70.86 ms | 68.90 ms |
| Page 19 at 500/page, excluding preceding pages, plus projection | 69.23 ms | 68.12 ms |
| All 17,500, at 500/page, plus projection and verification | 2.44 s | 2.46 s |
| Exact Alice count, requested separately | 232.10 ms | 32.19 ms |

The first run measured 12.31/7.95 ms for first-50 projection, 3.04/2.63 seconds
for full enumeration and 279.57/30.60 ms for exact Alice counts. There is no clear
page-latency winner. In both runs, loading existing document text/maps/facts is
much more expensive than target selection. The remaining projection work should
be profiled separately before adding another stored copy of source content.

The existing native service measured 1.26 seconds for the first 50 due-sorted
results in the repeat and 2.15 seconds in the first run, **including its exact
count and full result construction**. That is not the same page contract as the
prototypes. The earlier desktop result is also not directly interchangeable with
this native measurement. These differences prohibit treating the ratio as a
measured application speedup after integration.

Main Alice page selection uses 1,190 VM steps for records and 2,450 for facts,
with no full-scan steps and no sort in either statement. These counters exclude
the separate candidate probe and projection. Unlike the current item service,
selection returns only the page plus one lookahead target.

Other repeat measurements, first 50 plus text/fact projection:

| Query | Matches | Records | Facts |
| --- | ---: | ---: | ---: |
| Alice or Bob, open, month | 25,000 | 7.23 ms | 7.08 ms |
| Sparse added assignee, open, month | 1 | 0.711 ms | 0.712 ms |
| Absent assignee, open, month | 0 | 0.043 ms | 0.014 ms |
| Open, month, any assignee | 32,500 | 6.89 ms | 6.94 ms |
| Open, month, exclude Bob | 25,000 | 7.21 ms | 6.99 ms |

The bounded probe matters: the earlier ordered-scan prototype took roughly
90 ms for the sparse record case and 29 ms for sparse facts before projection.
Simply adding an ordering index is not a sufficient query strategy.

Exact-count behavior remains mixed. The repeat's records/facts counts are
19.74/31.17 ms for Alice/Bob and 3.21/17.30 ms for open/month, but 232.10/32.19 ms
for Alice and 110.82/28.14 ms for excluding Bob. Explicitly using the target-first
record primary key did not remove the regressions. Plans use indexes and show
fewer VM steps for the record count; the remaining time difference has not been
isolated. Cache/page-access and count-plan experiments remain necessary. Do not
declare records universally faster from their lower statement step count.

## Storage and writes

| Operation | Records | Facts |
| --- | ---: | ---: |
| Query-store bytes | 6,766,592 (6.45 MiB) | 8,704,000 (8.30 MiB) |
| Build from already extracted data, repeat | 422 ms | 462 ms |
| Replace one source's 100 tasks, repeat median | 4.06 ms | 3.36 ms |

Structured records occupy approximately **22% less query-store space** in this
fixture. This is not 22% of the whole Gilune index. These stores include sources,
targets and only the compared task/connection data and indexes. Shared text,
source maps, unrelated facts, and the existing index used for projection are
excluded. No duplicate content is counted as removed from the production index.

Build time includes declarations, target insertion, conversion from existing
facts, field validation, indexes and commit; it excludes Markdown parsing and
the real plugin indexing pass. Both stores use WAL and FULL synchronous commits.
Statistics are collected after initial population, outside the reported build.

The update deletes/replaces one source's targets and contributions atomically,
changes its recorded source version, closes its task records, and increments the
generation. Both models then report exactly 17,465 Alice/month matches instead
of 17,500. An earlier cursor is invalid. This tests derived-store write cost and
content changes; it is not end-to-end filesystem reconciliation, sync or recovery.
The first run's update medians were 4.40/3.11 ms. Both stores grew by one 4 KiB
page over the replacement series.

## What should happen before promotion

1. Establish the compact target/source representation and a shared declarative
   query plan for root and item consumers. Preserve explicit scope, producer
   completeness, generation checks and source provenance. Legacy facts should
   remain a supported input, with no cross-plugin dependencies.
2. Integrate SQL page selection, bounded positive seeds and separate counts into
   that service. Define count state/generation, cancellation and independent read
   workers; the existing FFI Store mutex cannot be ignored. Avoid a different
   optimized executor for each feature.
3. Add the structured-record SDK contract with owned/versioned declarations,
   scoped source callbacks, cardinality and same-record query bindings. Keep
   schema interpretation in plugins and storage/DDL authority in the kernel.
   Treat physical layout and index selection as kernel implementation choices.
4. Compare projection and count strategies, and vary tasks per document, record
   cardinality, sparse/negative distributions, nullable values, updates and cold
   caches. Measure peak memory, write amplification and supported devices before
   committing performance budgets or a universal physical layout.

Prototype limits are explicit: no public binding/module migration, legacy API
adapter, schema-version rebuild lifecycle, plugin removal/re-enable workflow,
producer receipt maintenance, fault injection, sync or device tests. Ordering is
one ascending field on a single-record base plus target ID; nullable ordering is
rejected unless the query guarantees a present value. Root inheritance, arbitrary
relation traversal, text ranking, grouped pages, canonicalized wire cursors and
UI count behavior are not implemented in this experiment. Those are acceptance
requirements for integration, not reasons to infer that this test engine ships.

## Reproduction and checks

After the [desktop fixture](cross-plugin-search-2026-09-06.md) has run, from `kernel`:

```powershell
$env:GILUNE_CROSS_PLUGIN_INDEX = '<fixture>/.gilune/local/index.db'
cargo test -p gilune-kernel --release records_prototype -- --nocapture
cargo test -p gilune-kernel --release compare_structured_records_and_compact_facts -- --ignored --nocapture
```

Three normal prototype tests, both final benchmark runs, four existing item-search
regression tests and kernel test-target Clippy passed. The benchmark never writes
the source index; temporary query stores are separate. No platform binaries were
rebuilt because all executable additions are test-only.
