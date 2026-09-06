# Search model validation: document shapes and correctness

Date: 2026-09-06. Follow-up to the [DSL matrix](dsl-matrix-2026-09-06.md).
These additions are test fixtures and diagnostics, not production executor changes.

Subsequent work: [the complete update profile](update-path-profile-2026-09-06.md)
attributes the missing stages, compares batch ID resolution and delivers a People
callback improvement. Its before/after measurements supersede the unresolved
callback attribution below without changing these earlier measurements.

## Findings

Compact fact selection remains fast when the same task corpus is divided into
very different document sizes. The additional checks support that direction but
expose two independent costs: whole-document display projection and whole-document
reindexing. Neither disappears just by changing query selection.

The new fixture contains the same 10,000 tasks in 1,000, 100, 10 or one document.
The actual independent Tasks and People plugins produce the facts through the
packaged Windows release kernel. The native comparison consumes the real Kotlin
DSL compiler's existing exported requests:

```text
type:task is:open people.connection:assign:@alice due:after:2026-08-31 due:before:2026-10-01
```

Both default ordering and due-date ordering are tested, with page sizes 50 and
500. Every combination has 3,500 matches. Each checks existing-executor counts
and first-page identities, compact counts and first pages, and complete ordered
compact enumeration against independent expected results. Queries use indexed
data; the kernel's source-file read counter is asserted to remain zero.

The existing executor here runs against **schema 6**, with compact target
maintenance already present but the old selection algorithm. This isolates the
executor comparison for each layout. It is not a new schema-5-versus-6 comparison.

## Query timings

Initial run, medians of three consecutive samples after loading the fixture,
milliseconds. Due-date ordering,
first 50 matches:

| Tasks per document | Documents | Existing page including count | Compact selection | Separate display projection | Separate exact count |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 1,000 | 269.1 | 0.90 | 4.92 | 13.95 |
| 100 | 100 | 202.6 | 0.92 | 6.33 | 14.51 |
| 1,000 | 10 | 235.8 | 1.35 | 7.91 | 17.35 |
| 10,000 | 1 | 420.3 | 1.36 | 124.62 | 18.79 |

A separate verification run after all builds/tests finished also passes every
identity/count/read assertion. It records 0.74–0.79 ms for the due-ordered first-50
selection, 12.0–12.6 ms for counts, and 63.9 ms for the large-document projection.
Existing due-ordered first pages take 163.8–226.6 ms in that run. Both runs are
retained; the variation reinforces that these are diagnostic measurements, not
settled latency budgets. The original measurements above have not been replaced
with the faster repeat.

For 500 matches, compact due-ordered selection takes 6.1–11.6 ms and display
projection 65–110 ms. In default order, first-50 selection takes 0.52–0.73 ms;
display projection still reaches 102 ms for the one large document.

The projection helper deliberately uses the existing source maps, indexed text
and fact loading. It decodes the entire map and loads the entire document's facts
for each touched source. This explains why restricting selected targets alone
does not bound display preparation. The production integration needs selected-target
fact/range retrieval and bounded display construction, with identical Unicode,
nested-item and provenance semantics.

These columns measure separate phases; adding their medians is not a measured
application latency. Existing search includes full result construction; prototype
projection omits final SearchPage construction, highlights, serialization, FFI and
rendering. Full enumeration measures keys only. All caches are ordinary warm-process
caches; this is not an OS-cold or Android measurement. There is no separately
discarded warm-up iteration in this shape test.

## Reindexing and edit cost

The setup fixture measures actual initial reconcile/indexing and three edits of
one task's date, restoring the original document between edits. Reconciliation
already advances the index; the additional explicit advance is a settled no-op.

| Tasks per document | Initial indexing, seconds | Single-task edit median, ms |
| ---: | ---: | ---: |
| 10 | 14.77 | 105.6 |
| 100 | 8.11 | 95.8 |
| 1,000 | 7.37 | 797.0 |
| 10,000 | 6.78 | 8,098.0 |

A separate callback-instrumented repeat confirms the large-document issue:
the 10,000-task document takes 8.28–9.50 seconds to reconcile. In the middle
8.40-second sample, Tasks' callback takes 42 ms and People's takes 2,033 ms.
Roughly 6.33 seconds occurs outside those callback bodies. The settled advance
takes 0.21–0.27 ms. The instrumentation does not attribute the remaining time
between FFI transfer, source parsing, fact validation/writes, trigger/index
maintenance, filesystem reconciliation and durable commits; that requires a
further native profile. Callback time excludes argument/result transfer.

The initial setup and callback-instrumented repeat use the current transitional
production schema. They do not establish new compact-only write performance or
old-model write regressions. The earlier isolated target-map microbenchmark
cannot stand in for this complete workload. Full replacement currently scales
with the document's size, even though only one date changes.

## Follow-up: compact replacement cost for the same large document

At the owner's request, a further test isolates replacement of the complete
compact fact/target representation for the 10,000-task source. It takes **622 ms
median across seven consecutive samples (605–709 ms)**. This replaces **63,505
facts and 20,503 root/line/item targets**, writes the source map, maintains four
fact indexes (target/value, predicate/value, numeric and folded), checks foreign
keys and commits using WAL with FULL synchronization. It alternates one task's
due date while replacing all of the source's derived rows, assigning fresh target
IDs and advancing a test generation/revision token.

The sample at the median total breaks down as:

| Component | Milliseconds |
| --- | ---: |
| Delete old facts and targets | 208.8 |
| Replace target metadata and source map | 35.2 |
| Insert facts and maintain their indexes | 313.2 |
| Advance generation and commit | 64.8 |

This is **not the full new edit time and not a like-for-like speedup over the
8–9.5-second production reconcile measurement**. Inputs are already extracted
and typed. It excludes filesystem reconciliation, source parsing, plugin callbacks,
FFI, semantic validation, text/FTS updates and producer receipts. The production
compact-only maintenance API is not integrated. In particular, the approximately
two seconds observed in People's callback do not disappear through this database
layout change. No complete-edit estimate is derived by combining separate runs.

Every sample checks all fact values, duplicate row identities, numeric/typed
data and target metadata against the prepared input, plus generation and foreign
key integrity. Original vault/index files are read only; the experiment writes a
separate retained database. This expands beyond the earlier 100-task/three-predicate
write test and must not be confused with its 3.36 ms result.

Run `profile_compact_ten_thousand_task_replacement` with `--ignored --nocapture`
and `GILUNE_SHAPE_DIR` pointing at the existing fixture. The test and release kernel
test-target Clippy passed. [Results](search-compact-write-2026-09-06.json) and
[raw output](search-compact-write-2026-09-06.txt) retain every sample.

## Added correctness coverage

- Compact scalar facts: 128 combinations of present/missing/multiple values,
  exercised by 98 pairs of constraints and whole-clause negations. Exact counts
  and complete three-result-page traversal match an independent bitmask truth
  table. Page facts do not become item matches. Independent range constraints
  may use different fact values; combining them into one range requires an
  explicit cardinality or same-record guarantee.
- Structured-record prototype: numeric ordering/range boundaries, non-finite
  and wrong-type rejection, and different results for same-record correlation
  versus separate existential conditions over multiple samples. This is typed
  contract correctness, **not a numeric DSL performance comparison**.
- Structured-record prototype: two WAL connections, a writer committing while a
  reader holds a snapshot, coherent old rows/generation, fresh count after commit,
  expired continuation, and rollback preservation. Readiness remains a prototype
  stand-in; this does not validate future public worker integration.
- Structured-record prototype: deterministic interruption during a query, using
  a test-only callback gate and SQLite's interrupt handle. The error releases its
  read transaction; subsequent count and page calls work on the same connection.
- Production compact targets: an independent reader holds old source/fact/target
  bindings while reconciliation replaces the source and one producer fails.
  After the read transaction ends, it sees the new source, the successful producer's
  new bindings and the failed producer's preserved but unbound evidence. Rebuilding
  that producer recovers consistent bindings.

## Acceptance status

The architecture direction is supported; P3/P6 acceptance remains open. The
remaining production work is the shared bounded executor, selected-target result
construction, public page/count/readiness/cancellation integration, missing-value
ordering and empty-intersection handling, and text/path/root/grouped query paths.
Reindexing costs above are now an explicit performance issue. Numeric DSL
performance, OS-cold behavior, supported-device Android runtime/performance and
complete compact-only lifecycle/storage tests remain unmeasured.

Validation completed for this batch:

- Release native workspace: **553 passed, 0 failed, 17 ignored/opt-in**. The new
  scalar fixture was rerun after a Clippy-only array cleanup and passed.
- Release workspace/all-target Clippy: passed with warnings denied.
- Desktop regression: **631 passed, 0 failed, 4 opt-in benchmark methods skipped**.
  Both new shape setup/edit profiling methods were run separately and passed.
- Native shape benchmark: **16 query/page/layout combinations passed twice**,
  including the final zero-source-read assertion.
- Android application and instrumented-test APK build tasks: succeeded. `adb`
  reports no attached devices; instrumented runtime tests were not run.

Production source and native bindings did not change in this batch. Desktop tests
use the existing staged schema-6 Windows release kernel; Android builds use its
previously staged corresponding native libraries. No new shipping binary is
claimed for these test-only additions.

## Reproduction and evidence

Use a fresh `GILUNE_SHAPE_DIR` and run `SearchShapeBenchmarkTest.prepareShapesAndMeasureSingleTaskEdits`
through `:composeApp:desktopTest` with `'-Pgilune.kernel.profile=release'`.
For the instrumented repeat, point `GILUNE_SHAPE_PROFILE` at that existing directory
and run only `SearchShapeBenchmarkTest.profileExistingShapeEdits`. The setup method
intentionally refuses to overwrite an existing fixture.

Set `GILUNE_SHAPE_DIR` and `GILUNE_MATRIX_DIR` (the directory containing exported
`dsl-cases.json`) and run from `kernel`:

```powershell
cargo test -p gilune-kernel --release profile_document_shapes -- --ignored --nocapture
```

Each native run creates a fresh retained `query-*` folder with compact SQLite
stores and `results.json`. Correctness tests run through ordinary workspace tests;
performance fixtures are opt-in. Evidence:

- [Initial query results](search-shapes-2026-09-06.json) and [raw run](search-shapes-2026-09-06.txt).
- [Verification results](search-shapes-verification-2026-09-06.json) and [raw run](search-shapes-verification-2026-09-06.txt).
- [Initial indexing and callback edit timings](search-shapes-edits-2026-09-06.txt).

Full local regression logs are retained in `app/build/search-final-workspace-tests.log`,
`search-final-clippy.log`, `search-final-scalar-test.log` and
`search-final-platform-tests.log`.
