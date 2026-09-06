# Lookup selection and prefix optimization — 2026-09-06

This implements the two changes agreed after the
[initial profile](lookup-performance-2026-09-06.md): select matching roots before
sort preparation, and make literal prefix matching indexable. All changes remain
in generic kernel query/index code. People, Tasks, the SDK and DSL retain their
existing contracts.

## Implementation

Alternative conjunctions now produce sets directly, using intersections and
exclusions as needed. Their union is grouped by source root with minimum priority.
Negative-only and empty alternatives start from the source universe. Duplicate
facts and overlapping alternatives cannot duplicate a result.

After candidate selection, base constraints probe each candidate's path and
source version. Those probes explicitly use the existing `facts_path` index:
EXPLAIN showed that SQLite otherwise chose a broad `(predicate,value)` access
path for repeated equal values, making per-root checks quadratic. Unbound fact
queries retain normal planner choice. Producer/source evidence is checked before
sort preparation. A left join reads sort facts only for matching roots, preserving
missing values and minimum-value sorting without a namespace-wide sort table or
temporary automatic join index.

The new `(predicate, gilune_fold(value))` expression index stores the existing
simple case-folded value during fact writes. Prefix bounds are computed once per
request. UTF-8 BINARY order preserves Unicode scalar order; the exclusive upper
bound increments the last incrementable scalar, skips surrogate code points and
carries past trailing maximum scalars. Empty/all-maximum prefixes have no upper
bound. No wildcard interpretation, locale collation or normalization change is
introduced. Equality and Contains retain their behavior.

This is disposable index schema **5**. Opening schema 4 rebuilds derived state
through the existing version mechanism. User documents and plugin schemas do not
change. A test simulates schema 4 and proves source preservation and rebuilt
prefix matches.

## Measured results

Same synthetic 10,006-root fixture, release build, Windows, SQLite 3.45.0. The
final profiling and scale runs were isolated from other tests/builds. The earlier
profile provides the before values. Timing differences between the separate
seven-sample instrumented and three-sample scale runs are retained explicitly.

| Measurement | Before | After |
| --- | ---: | ---: |
| Instrumented first 30, all roots match, median 7 | 198.51 ms | 156.41 ms |
| Main SQL, all roots match, separate median 7 | 181.92 ms | 132.12 ms |
| Main SQL, one matching root, median 7 | 130.40 ms | 0.061 ms |
| Main SQL, no matching roots, median 7 | 64.63 ms | 0.023 ms |
| Main SQL, empty fragment, median 7 | 113.33 ms | 95.02 ms |
| Scale first 30, median 3 | 203.95 ms | 142.80 ms |
| Complete enumeration, 500/page | 4.30 s | 3.12 s |

Complete direct calls for one/no matches take **20.50 / 22.59 ms**. Main query
execution is now a small fraction of those calls: source-alignment checks still
scan producer receipts. No freshness guarantee was removed to obtain the gains.

A separate isolated release measurement of **page 19 at 500/page** (offset 9,000)
took **146.34 ms median**, range **145.83–147.61 ms** over seven warmed samples.
Page 1 at the same size took **142.12 ms** in alternating paired measurements.
The preceding 18 pages were used only to obtain the real continuation cursor;
their execution is excluded from these timings. The initial page-19 call after
obtaining that cursor took 145.42 ms. Assertions verify results 9,001–9,500,
the full count and zero source reads. Reproduce with the ignored release test
`profile_nineteenth_lookup_page`; output is in `app/build/lookup-page19-profile.log`.

### Hydration versus matching population

The ignored `profile_lookup_page_size_and_hydration` test isolates the number
returned from the number matching. Seven warmed release samples per request,
using the same broad fixture and then a single unique key, measured:

| Matching roots | Page size | Main SQL/result collection | Display-fact hydration | Complete call |
| ---: | ---: | ---: | ---: | ---: |
| 10,006 | 1 | 123.719 ms | 0.034 ms | 141.981 ms |
| 10,006 | 30 | 123.900 ms | 0.149 ms | 142.318 ms |
| 10,006 | 500 | 123.973 ms | 2.027 ms | 144.168 ms |
| 1 | 1 | 0.195 ms | 0.008 ms | 18.424 ms |

Freshness checks take approximately 17.7–17.9 ms in this run. Per-stage medians
need not sum exactly to the complete-call median. The broad query costs almost
the same whether it returns 1 or 500 rows: matching, minimum priorities, sort-key
preparation, global ordering and exact counts still cover 10,006 matches.
Hydration reads requested display facts from SQLite only for the retained rows;
500 rows cost about 2 ms. This is distinct from the earlier 0.061 ms selective
main-statement measurement, which excludes freshness and hydration and has only
one matching root. These native timings exclude FFI and UI rendering. The test
asserts result counts and zero source reads; output is retained in
`app/build/lookup-hydration-profile.log`.

### 50,000 matching roots

The same isolated release comparison at exactly 50,000 roots, seven warmed
samples per request, measured:

| Matching roots | Page size | Main SQL/result collection | Freshness | Hydration | Complete call |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 50,000 | 1 | 627.333 ms | 100.014 ms | 0.050 ms | 727.732 ms |
| 50,000 | 30 | 622.352 ms | 99.360 ms | 0.166 ms | 721.316 ms |
| 50,000 | 500 | 626.557 ms | 99.672 ms | 2.101 ms | 729.074 ms |
| 1 (in a 50,000-root index) | 1 | 0.137 ms | 100.085 ms | 0.008 ms | 100.685 ms |

Broad queries take approximately five times the 10,006-root time, independently
of the small returned page size. Freshness itself grows to about 100 ms, including
when only one root matches. Hydration for 500 retained rows remains about 2 ms.
Counts, returned row counts and zero source reads are asserted. This measures
the first page, not full enumeration at this scale, and excludes FFI/UI rendering.

Reproduce with the ignored release test `profile_lookup_fifty_thousand_matches`.
The shared fixture builder freezes seed receipts once before cloning them, avoiding
a quadratic setup scan of the growing receipt table; the resulting indexed data
is unchanged. Setup is excluded from all timings. Only test instrumentation and
documentation changed for this measurement. The
[raw 50,000-root output](lookup-50000-profile-2026-09-06.txt) is retained alongside
this report.

The one-match statement executes **481 VM steps**, zero full-scan steps and zero
automatic-index rows, versus 2,681,871 steps previously. The no-match statement
uses 104 steps. A normal regression test requires one-match selection over 10,006
roots to stay below 3,000 VM steps and build no automatic index; it does not rely
on wall-clock timing. Broad results still need global ranking and exact counts.

The folded index occupies **209 4-KiB pages = 836 KiB**, about 7.6% of the original
10.70-MiB fixture database. Rebuilding that expression index over existing fixture
facts took **43.69 ms**. That is index construction, not end-to-end vault ingestion
or an isolated incremental-write overhead measurement. No wide covering index or
temporary-storage setting was added. The old profiling experiments remain useful:
adding a wide covering index to this query did not improve this final broad run.

The [final expanded SQL](lookup-optimized-2026-09-06.sql) and
[raw measurements/plans](lookup-optimized-profile-2026-09-06.txt) are retained.
Re-run the ignored profiling harness with `GILUNE_LOOKUP_PROFILE_OUT` set, as in
the original report. It now exercises the current compiler/index and measures
the folded index's size and rebuild time; the original artifacts remain unchanged.

## Correctness and remaining work

Coverage includes old-expression equivalence across case folding, combining
characters, supplementary characters, UTF-8 width boundaries, surrogate-gap
boundaries, maximum scalars, NULs, empty prefixes and literal punctuation. A
counted folding callback proves no value folding occurs during indexed prefix
reads; updates/deletes maintain the expression index. Lookup tests cover negative
and empty alternatives, conjunctions, repeated facts, multiple sort values,
collisions, freshness, source versions and complete pagination.

Freshness scans, repeated full selection/counts on later pages, representative
vault ingestion/write overhead and actual device/UI latency remain open. This
change does not claim a completed P6 performance gate or implement editor triggers.

## Validation

- **538 native workspace tests passed** in an isolated full run. Workspace and
  all-targets Clippy passed. The ignored release profile and scale regression
  checks passed separately; zero source-file reads are retained.
- **631 desktop tests passed**, zero failures/errors/skips, against the rebuilt
  Whisper-enabled release DLL. The staged app resource has the same SHA-256 as
  the tested DLL: `27913BADAAEA0E7694B2315272BE29ED25DF1FA1906F4C98C32599732AA51FA2`.
- Both Whisper-enabled Android kernels rebuilt: **8.66 MiB arm64 / 8.80 MiB
  x86_64**. Bindings independently generated from both Android binaries and the
  desktop DLL share SHA-256
  `2A8B580E9C260E91481E44B5F66C62EB966E346F7F81A0B3529ED838B90BE23E`.
  App and instrumentation APK builds passed. No Android device was attached;
  device/runtime tests remain unrun.
- Build/test logs are under `app/build/lookup-optimized-*`. Exact SQL and measured
  profile output are retained alongside this report rather than only in build
  output. No public binding/API shape changed.
