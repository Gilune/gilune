# DSL query matrix and full-index storage comparison

Date: 2026-09-06. Diagnostic tests only; no production executor change.

Follow-up: [document-shape, edit-cost and correctness validation](search-validation-2026-09-06.md)
adds production snapshot and prototype semantics checks, plus full regression results.

## Outcome

The broader matrix strengthens the case for compact, source-qualified joins and
page selection before projection. It also finds serious old negation costs and
new prototype gaps that the earlier Alice/month example did not reveal.

**53 real DSL cases ran; 38 have a compact-facts comparison.** The other 15 remain
explicitly unsupported by this diagnostic adapter: 12 text/path/root-scope cases,
two grouped cases, and one `done` predicate absent from the adapter's loaded data.
They were measured through the existing native executor, not silently simplified
or labelled as new-executor results.

The full compact-storage experiment retains every one of the fixture's 320,000
facts, including duplicate row identity, typed values and source provenance.
After compacting both files it occupies **55.15 MiB versus 125.01 MiB**, a **55.9%**
reduction. This is a storage representation experiment, not an executable public
query/index-maintenance implementation. The current transitional schema is larger.

## Evidence and method

- [All 53 queries and measurements as CSV](dsl-matrix-2026-09-06.csv).
- [Complete structured results and storage breakdown](dsl-matrix-2026-09-06.json).
- [Actual Kotlin compiler output](dsl-matrix-requests-2026-09-06.json).
- [Native run, SQL, plans and counters](dsl-matrix-2026-09-06.txt).
- [Statistics controls](dsl-matrix-controls-2026-09-06.json) and
  [SQL/plans](dsl-matrix-controls-2026-09-06.txt).
- [Full-storage preservation and sizes](dsl-matrix-full-storage-2026-09-06.json).
- [Count access-path controls](dsl-matrix-count-controls-2026-09-06.json) and
  [cache controls](dsl-matrix-cache-controls-2026-09-06.json), with
  [fresh-build reproduction](dsl-matrix-cache-controls-2026-09-06.txt).
- [Complete old/current fact identity check](dsl-matrix-identity-2026-09-06.txt).

Input remains 50,000 tasks in 500 Markdown documents, 100 tasks per document,
from the existing real Tasks/People fixture. The date is fixed to September 6,
2026. `due:week` means September 6–12; negation also includes missing dates.
The source schema-5 database is opened read-only. A separate schema-6 vault is
rebuilt from byte-identical Markdown with the actual independently registered
plugins. Initial schema-6 reconcile/indexing takes 41.9 seconds in this run;
there is no paired old-build measurement here, so that is not a write-regression
comparison.

`DslMatrixBenchmarkTest` invokes the real DSL compiler and exports structured
requests. The Rust adapter reconstructs those requests without parsing the DSL
again. No production People/Tasks dependency is introduced. Compilation is timed
separately; the native timings exclude Kotlin/FFI, the Store entry lock and UI.

Existing first-page timings are warm medians of three complete native requests
after one initial request. They include exact count, producer-source checks and
full result construction. New page timings are medians of five page selections
plus existing text/fact projection, with exact counts requested separately.
The prototype uses a static readiness flag instead of the production maintenance
protocol; it excludes final SearchPage assembly, highlighting and serialization.
These are deliberately different page/count contracts, not a measured application
speedup after integration. Initial-request values are retained in JSON but are
not OS-cold-cache measurements.

The wider test adapter supports exact fact conjunctions, membership, whole-clause
negation, absence, default order, and ascending due order with missing values last.
It keeps distinct existential constraints distinct; it does not assume arbitrary
plugin facts are scalar. The due-order driver asserts this fixture has at most
one due value per target before applying range hints. This is a fixture assertion,
not a new SDK cardinality promise. Empty sort phases and contradictory common
predicates are intentionally left visible as planner weaknesses.

For every supported query, old counts/first-page identities are compared with an
independent evaluation of the imported facts. Every new result identity is then
enumerated and compared in order, including missing-value boundaries and ties.
Selected page-19 checks skip measuring the previous 18 pages. Queries perform
zero instrumented vault-source reads; they still read SQLite pages. No other
build or benchmark was started by this agent during the timed runs.

## Representative filters

All timings are milliseconds. Filters below follow `type:task`. `month` abbreviates
`due:after:2026-08-31 due:before:2026-10-01`; the CSV retains every complete DSL string.
Default order is used unless stated otherwise.

| Filter | Matches | Old page + count | New page + projection | New separate count |
| --- | ---: | ---: | ---: | ---: |
| All tasks | 50,000 | 359.95 | 0.92 | 256.59 |
| `is:open` | 42,500 | 427.44 | 0.91 | 34.23 |
| `-is:done` | 42,500 | 15,710.14 | 0.97 | 50.70 |
| `is:open people.connection:assign:@alice month` | 17,500 | 1,182.24 | 2.13 | 79.15 |
| Same Alice/month query, due order | 17,500 | 1,201.15 | 5.79 | 60.29 |
| `is:open -people.connection:assign:@bob month` | 25,000 | 20,199.59 | 1.15 | 68.05 |
| `people.connection:assign NOT IN ["alice", "bob"]` | 7,500 | 16,479.90 | 2.49 | 39.12 |
| `people.connection:owner:@alice` | 2,500 | 197.74 | 5.88 | 35.52 |
| Alice assignee AND Bob reviewer | 2,500 | 307.58 | 6.31 | 45.43 |
| Alice assignee AND Bob assignee | 0 | 244.27 | 43.86 | 43.68 |
| `due:none` | 2,500 | 6,030.91 | 5.81 | 29.78 |
| `due:none`, due order | 2,500 | 6,417.37 | 111.72 | 33.04 |
| `-due:week` | 40,000 | 54,694.41 | 0.91 | 47.34 |
| `is:open is:done` | 0 | 228.37 | 45.01 | 45.14 |
| `people.connection:assign:@absent` | 0 | 85.19 | 0.04 | 0.04 |
| `content:"Work 49999"` | 1 | 301.98 | Not implemented | — |
| `path:work-0499` | 100 | 302.76 | Not implemented | — |
| Alice/month, grouped by document | 500 | 870.63 | Not implemented | — |

The complete matrix also covers done tasks, multiple/duplicate/empty person sets,
Unicode/unknown handles, wrong role pairs, date equality and boundaries, relative
dates, invalid dates and their negation, redundant ranges, contradictory bounds,
present/missing properties, short text, broad/absent/negative text, relevance,
filename filtering and combined path/fact filtering. DSL OR/grouping syntax does
not exist; membership supplies the supported multi-value alternative.

## Pagination and projection

Page 19 below contains up to 500 results and starts after result 9,000. Timings
are individual requests, not the cumulative time to fetch preceding pages.

| Query | Old page 19 + count | New page 19 + projection |
| --- | ---: | ---: |
| All tasks, default order | 358.72 | 3.15 |
| Alice/month, due order | 1,164.67 | 62.44 |
| All tasks, due order including missing dates | 634.04 | 32.26 |

Default order keeps neighboring tasks in the same documents. Due order spreads
results across more documents, so loading their existing text/maps/facts costs
more even after selecting only the page. For Alice/month, selecting every target
takes 162 ms, and projecting all results separately takes 2.16 seconds. These are
separate passes, not a measured end-to-end traversal. The earlier full-traversal
benchmark remains linked from the original prototype report.

## What the plans show

The old negative paths materialize predicate sets and use tuple membership over
path/location/source-version fields, followed by complete candidate processing
and sorting. Their plans still show predicate indexes: this is not an absence of
SQL indexes. The expensive negation behavior survives statistics updates.

On an isolated, compacted old-index copy, `-is:done` takes 16.17 seconds before
`ANALYZE` and 15.90 seconds after it. The Alice/month due query changes from 1.56
seconds to about 1.15 seconds warm, remaining far slower than bounded selection.
`-due:week` still exceeds the separate statistics control's 30-second limit and is
recorded as interrupted, not assigned a fabricated completion time. That timeout
does not affect its fully completed 54.69-second baseline measurement.

Current schema 6, running the existing executor, also retains the bad paths:
the `-due:week` sample is 53.96 seconds and Alice/month due is 1.09 seconds.
Adding target tables alone does not fix the query executor.

Two new planner issues are clear. A missing-date query with due ordering first
searches the present-date phase even though the query excludes it. Also, two
individually common predicates can have an empty intersection, causing a scan
despite the bounded sparse-candidate probe. Both need generic planner treatment,
without teaching the kernel the meanings of assignment or task state.

Broad-count connection/cache controls are recorded separately; do not promote
the main run's 250–300 ms broad counts into a fixed latency budget without those
controls. The candidate probe itself is only a fraction of a millisecond.

The outlier reproduces after freshly building the query store: all-task counts
take 255–270 ms, while the same count through a reopened connection takes about
21.6 ms. Forcing a target-table scan barely changes the reopened result. Candidate
probing takes 0.03 ms, and both count plans execute the same 803,511 VM steps.
Increasing the fresh connection's cache to 32 MiB changes the complete count from
270 to 19.0 ms. Restoring the original 2 MiB setting and shrinking the cache leaves
it at 21.3 ms; checkpointing leaves it at 20.9 ms. This establishes connection/cache
state sensitivity, without proving that a permanently larger cache is necessary
or identifying the internal cause. Preserve this regression fixture and inspect
cache/read behavior during executor integration. These are controls, not silently
substituted numbers in the main table.

## Disk usage

MiB means 1,048,576 bytes. Both compacted figures below are measured files created
with SQLite `VACUUM INTO`, not estimates obtained by subtracting table sizes.

| Whole-index representation | As built/allocated | After compaction |
| --- | ---: | ---: |
| Existing schema 5 | 131.22 MiB | 125.01 MiB |
| Current transitional schema 6 | 152.89 MiB | 145.92 MiB |
| Full compact-fact storage experiment | Conversion file retains freed pages | 55.15 MiB |

The transitional index is **16.5% larger as built**, because it keeps wide facts,
their old indexes and source maps alongside numeric target/predicate bindings.
Its two new covering indexes occupy about 15.1 MiB; target rows and their unique
index occupy about 5.15 MiB. This is temporary duplication, not a delivered space
saving.

The full storage experiment replaces repeated path, namespace, predicate and
revision strings in fact rows with target/predicate IDs. It retains all facts and
duplicate row IDs, numeric/type data, exact/folded/numeric and target access indexes,
source maps, target metadata, source receipts, text and FTS. Reconstructing the
old wide fact fields through those IDs yields zero differences. It removes the old
fact table and maintenance triggers on a disposable copy; production APIs and
maintenance are not wired to this layout. There are no stale/unbound legacy facts
in this fixture, and their required preservation remains a production design gate.
The converted file does not shrink automatically when tables are dropped; the
55.15 MiB result requires compaction or a fresh replacement database.

For comparison with the earlier experiment, the three-predicate query-only stores
are **8.32 MiB for compact facts** and **6.45 MiB for structured records** as built
(7.50/5.80 MiB compacted). These exclude shared text, most predicates and maintenance
data. They must not be compared directly with whole-index sizes as a total saving.

## Required next checks and implementation priorities

1. Replace the current materialized tuple-membership/negation paths through the
   shared bounded executor, preserving whole-clause negation and exact scope.
2. Make missing-value ordering phase-aware and improve empty/common intersections.
   Measure and explain broad-count connection/cache behavior before setting budgets.
3. Integrate text/path/root-scope and grouped searches; the matrix exposes them as
   gaps, not implicit fallbacks with claimed new-model performance.
4. Wire full result construction, real per-producer readiness, generation-bound
   cursors, independent counts and cancellation through the public API and shells.
5. Benchmark different document shapes, multiple values/records per target, numeric
   predicates, mixed plugin workloads, write amplification and rebuild growth,
   live edits/lagging producers, OS-cold reads and supported Android devices.

No production schema, executor or plugin contracts were changed by this benchmark.
These results support the architecture direction while defining work still needed
before the performance gate can be accepted.

## Reproduction

The old schema-5 fixture must be retained; opening it through a current `Store`
would rebuild it. The diagnostic Rust reader uses read-only SQLite access instead.
Point `GILUNE_MATRIX_ORIGIN` at its vault and `GILUNE_MATRIX_OUTPUT` at a separate
workspace build directory. Run `DslMatrixBenchmarkTest` against the Windows release
kernel. It produces `dsl-cases.json` and `schema6-vault` without modifying the origin.

From `kernel`, set `GILUNE_MATRIX_DIR` to that output directory and
`GILUNE_CROSS_PLUGIN_INDEX` to the old `.gilune/local/index.db`, then run:

```powershell
cargo test -p gilune-kernel --release profile_exported_dsl_matrix -- --ignored --nocapture
```

The test prints its retained `run-*` directory. Set `GILUNE_MATRIX_RUN` to it for
`profile_dsl_matrix_statistics_controls`, `profile_full_compact_storage`,
`profile_dsl_matrix_count_access_paths` and `verify_matrix_complete_fact_identity`.
The storage tests create named files and should use a fresh run directory when
repeated. Optional `GILUNE_MATRIX_FILTER=all,empty_negative_membership` restricts
a new matrix run for the connection/cache controls; unset it for the full matrix.

Validation: the DSL export/rebuild test, 53-case native matrix, statistics controls,
full-storage test, count/cache controls and all-fact identity comparison passed.
The 30-second statistics-control interruption is an explicitly recorded measurement,
not a correctness-test pass for that interrupted result. All three existing
prototype conformance tests passed, and release kernel test-target Clippy passed
with warnings denied. No production binaries were changed by these diagnostics.
