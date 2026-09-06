# Complete update-path profile and migration decision

Date: 2026-09-06. Same 10,000-task source used in the earlier
[document-shape tests](search-validation-2026-09-06.md).

## Decision

Proceed with integrating the common compact writer and bounded query executor.
The evidence now covers both reads and writes. The main update cost is the
current fact-write representation and per-fact binding work, rather than Markdown
parsing or filesystem reconciliation. Keep plugin inputs and domain ownership
independent; this does not require People to depend on Tasks.

The new writer is not yet a complete production maintenance path. Keep the
acceptance gates explicit while migrating consumers; the existing wide table
cannot simply be dropped before legacy/stale facts and all query paths are covered.

## Complete edit measurements

Three edited-date samples per document size, with original contents restored
between samples, real Kotlin plugins, native SQLite maintenance, and final commit.
The sample at the median 10,000-task baseline total is:

| Component | Milliseconds |
| --- | ---: |
| Reconciliation before indexing | 12.4 |
| Read/split source | 0.6 |
| Shared Markdown/source parsing | 4.6 |
| People callback body | 2,034.9 |
| Tasks callback body | 31.9 |
| Adapter/data-transfer remainder around both callbacks | 37.2 |
| Scoped-fact validation | 15.1 |
| All storage transactions | 6,075.3 |
| **Complete reconcile, including indexing** | **8,224.8** |

The rows above identify major phases; small preparation/logging/dispatch costs
are not assigned to a named leaf. The callback remainder is the native adapter
duration minus the two independently measured Kotlin callback bodies. It includes
conversion, transfer and wrapper work, not just transport. It is not a pure FFI
microbenchmark. Parsing occurs once per changed source, shared across producers.

Inside those storage transactions:

| Component | Milliseconds |
| --- | ---: |
| Insert facts/receipts and maintain their indexes | 4,119.9 |
| Insert indexed text and maintain FTS | 773.6 |
| Delete previous per-namespace rows | 562.0 |
| Durable commits | 492.3 |
| Insert source map and compact targets | 125.7 |

The delete total includes 155.6 ms for source-map/target withdrawal and 38.6 ms for
text deletion; these are nested components, not additional costs. Parent timers
must not be summed with children. The text/map maintenance transaction is about
1.3 seconds overall. The four namespace transactions commit independently.

Current fact insertion writes wide rows, then the binding trigger looks up private
predicate/target IDs and updates that row again. Predicate names and source-qualified
target locations are repeatedly resolved for the same source and namespace.

## Controlled write comparison

Two disposable copies of the same complete production index are updated with the
same already-extracted source map, text lines and facts. Both retain all wide and
compact indexes, text/FTS maintenance, namespace replacement and WAL/FULL commits.
The alternative resolves the predicate dictionary and target locations once per
namespace and inserts the IDs directly; only its per-fact binding triggers are
removed. The source-map replacement triggers remain active.

| Writer on the existing schema | Median total storage time |
| --- | ---: |
| Current triggers | 6,024 ms |
| IDs resolved once per batch | 2,288 ms |

One warm-up is excluded, followed by three samples per mode. Current measured
totals range from 5,847–6,053 ms; batch-resolved totals range from 2,243–2,355 ms.
Every trial checks all fact values, duplicate multiplicity/order, typed values,
source versions and current target/predicate binding integrity.

In the batch-resolved run at its median total, Tasks inserts fall to 161 ms and
People inserts to 409 ms. This removes roughly 3.7 seconds of storage work without
changing plugin extraction or discarding existing indexes. Text/map work remains
about 1.18 seconds in that sample. The batch-resolved control still loads an entire
source's target map for each namespace, including the tiny frontmatter namespace;
the production design should share that map or resolve only requested locations.

This is an isolated storage control, **not** a complete edit measurement and **not**
an already-integrated production optimization. The [622 ms compact-only write
experiment](search-compact-write-2026-09-06.json) excludes text/FTS and several
other stages. These experiments cannot be added together or presented as the
final end-to-end latency of the replacement implementation.

## People callback improvement delivered

JVM sampling pointed at JNA/native-buffer allocation and cleanup around Unicode
normalization. Source inspection showed repeated normalization of the same handles
and keywords, including values already canonicalized during recognition. Source
location lookup already uses binary search; it was not the problematic linear scan
suggested as an early hypothesis.

People now memoizes the **existing native Unicode normalizer** within one
recognition/indexing call. The cache holds at most 512 entries and is discarded
with that call. Additional distinct values still use the same normalizer. Both
mention recognition and connection fact construction share it during indexing.
No persistent aliases, person resolution, new casefold algorithm or kernel domain
dispatch is introduced. Public connection/query encoding is unchanged.

| Complete update, same 10,000-task fixture | Before | After |
| --- | ---: | ---: |
| People callback, three samples | 2,035–2,152 ms | 18–26 ms |
| Median full reconcile/index | 8,225 ms | 6,202 ms |

The updated full samples are 6,203, 6,202 and 6,092 ms. Storage remains about
6 seconds; the delivered callback improvement is distinct from the experimental
batch writer. The regression fixture compares exact facts for repeated Unicode
spellings, normalization expansion and over 512 distinct handles, then repeats
the indexing call to verify it does not depend on prior cache state. Existing
Markdown/range/collision/query tests remain applicable.

## Production migration order

1. Introduce the common batch writer using one source snapshot, generic target
   IDs and owned predicate IDs. Resolve IDs once; do not rebuild the same lookup
   map for every fact or silently bypass source/ownership validation. Avoid a
   parallel People-specific writer.
2. Preserve producer-version checks, readiness receipts, failures, stale/unbound
   evidence, retries, plugin lifecycle and generation-bound snapshots. Batch
   successful prepared contributions where consistent with those rules. Keep
   callbacks outside write transactions. Separate commits cost around 0.5 seconds
   here, but that is an opportunity to test rather than a promised saving.
3. Route the existing public query surfaces through bounded selection and
   selected-target projection. Complete text/path/root/grouped/negative/missing
   paths and cursor/count semantics before removing compatibility storage.
4. Remove redundant wide representation/index work after equivalence and
   lifecycle gates pass. Measure the integrated complete edit and query latencies,
   not sums of prototype components. Then address remaining source-wide text/FTS
   replacement and whether narrower maintenance units are warranted.

The present update contract replaces a source's contributions even when one task
changes. Compact keys reduce the cost of that work; they do not by themselves
provide incremental plugin parsing or task-level edits. An interactive UI should
not rely on this extreme source shape becoming an immediate synchronous operation
through the storage switch alone. Read-worker and freshness contracts remain part
of the existing migration plan.

## Instrumentation and reproduction

Native spans require both the `index-profile` Cargo feature and the
`GILUNE_PROFILE_INDEX` environment variable. Normal builds have no timers,
environment checks or logging. Spans report phase/namespace/duration only, never
source paths, contents or values. No FFI signatures or generated bindings change.
The diagnostic DLL was copied to `kernel/target/update-path-profile`; the previous
normal release DLL was preserved and restored after creating that copy.

Set `GILUNE_SHAPE_PROFILE` to the existing shape fixture, optionally set
`GILUNE_SHAPE_PROFILE_SIZES=100,1000,10000`, and run
`SearchShapeBenchmarkTest.profileExistingShapeEdits` with
`'-Pgilune.kernel.profile=update-path-profile'`. Set `GILUNE_UPDATE_JFR` to a local
recording path and run `profilePeopleCallbackCpu` for the separate CPU recording.
CPU-recording times are not the full update timings above.

From `kernel`, run the native control with `GILUNE_SHAPE_DIR` set:

```powershell
cargo test -p gilune-kernel --release profile_real_update_write_path -- --ignored --nocapture
```

`kernel/scripts/summarize-update-profile.ps1` extracts ordered native reconcile
groups and pairs them with independently measured Kotlin durations, rejecting a
pairing difference over 2 ms. This avoids incorrectly associating Gradle-buffered
stdout BEGIN markers with stderr timings. Raw overlapping spans are retained.

Evidence: [baseline summary](update-path-baseline-2026-09-06.json),
[optimized callback summary](update-path-cached-2026-09-06.json),
[native writer comparison](update-path-writes-2026-09-06.json), and
[raw timings](update-path-2026-09-06.txt). Local JVM recording and samples remain
under `app/build/update-path-profile`.

## Validation

- Normal release native workspace: **553 passed, 0 failed, 19 ignored/opt-in**.
  The new native write control ran separately and passed all eight trials.
- Release workspace/all-target Clippy and FFI all-target Clippy with
  `index-profile` enabled: passed with warnings denied.
- Desktop: **632 passed, 0 failed, 5 opt-in profiling/benchmark methods skipped**.
  Baseline and optimized full-path profiling, CPU recording, and focused People
  conformance ran separately and passed.
- [All 63,505 current facts match the pre-cache data](update-path-fact-equivalence-2026-09-06.json)
  in both directions, including typed values, duplicate multiplicity and logical
  source locations. The native control's synthetic revision tokens are excluded
  from that comparison; all current fact/target/source revision bindings are
  checked separately and valid.
- Android app and instrumented-test APK builds passed. No attached Android
  device was available for runtime/performance tests.
- Kotlin bindings generated from the normal Windows release DLL remain identical
  to both checked-in platform bindings (SHA-256
  `2A8B580E9C260E91481E44B5F66C62EB966E346F7F81A0B3529ED838B90BE23E`).

The normal default-feature DLL was rebuilt by workspace verification after the
diagnostic copy had been created; packaged Whisper-enabled resources were not
replaced. No new production writer or query route was enabled by these diagnostics.
The functional application change delivered here is the bounded People token
normalization cache. Existing normalized fact meanings and indexer version remain
unchanged.
