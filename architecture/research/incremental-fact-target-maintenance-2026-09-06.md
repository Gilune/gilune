# Incremental fact and target maintenance

Date: 2026-09-06

Status: Implemented and validated. Revision-binding work remains proportional
to the producer's facts; this is not yet maintenance of only changed tasks.

This phase retains the compact query model and changes database maintenance
underneath it. Plugins still supply complete, independently owned output; the
kernel gains no People or Tasks semantics.

## Implementation

Schema 10 updates an existing source-map row. Before a changed source becomes
current, all old fact bindings are detached. Target rows at matching kind/key
coordinates survive; only changed range metadata is updated, and added/removed
keys are inserted/deleted. Identical layouts skip target reconciliation entirely.
Only facts for the exact current source version rebind. Deleted target IDs are
not reallocated; a retained private coordinate slot is not stable task identity.

Each producer's new facts are compared with its stored rows in output order.
Unchanged rows need no fact write. When only provenance changes, the writer
updates location/target columns; changed values update the existing row. Extra
rows are inserted and surplus rows deleted. Location interning first checks for
an existing entry, avoiding attempted insertions for unchanged locations.

The comparison is positional within each producer's document output. It preserves
order and duplicate counts but is not a minimal edit script: adding or reordering
facts can change later slots. Matching private row slots does not assert domain
identity. Source revisions still require binding updates across retained values;
this phase does not make maintenance proportional only to changed task values.

Producer receipts commit with their own successful output. A failed producer's
old values and source locations remain intact and unbound, while another producer
can advance. Existing cursor/version checks, rollback, namespace removal, schema
rebuilds and WAL reader snapshots retain their roles. Query SQL and public FFI
signatures are unchanged; older derived indexes rebuild to install new triggers.

## Conformance

New fixtures verify that a same-length edit in a 1,000-item source writes no
target rows and preserves all target IDs. Changing one fact value retains all
fact rows, updates one value, and advances 999 other bindings. Repeating identical
output performs no fact-row writes. Additional fixtures compare ordered duplicate,
legacy, stale, typed, reordered, resized and empty batches with full replacement;
exercise failed producer rollback; and compare structural/position changes against
the complete parser layout in both directions.

Existing revision tests now explicitly exercise reused private IDs with detached
failed-producer evidence, independent WAL readers, retry, and text-only rebuild.
Schema 5–9 rebuild cases ensure source documents are preserved.

## Measurement method

The previous shipped DLL is preserved in an isolated benchmark directory. Both
versions run the same real Kotlin/FFI edit/restore fixture with People and Tasks,
at 100, 1,000 and 10,000 tasks per document. The new schema's initial rebuild is
completed before timed edits. Each version supplies three samples per size;
separate processes and warm-up behavior mean these are complete-path comparisons,
not a causal decomposition of every millisecond. Builds and other large workloads
are kept separate from timing runs. All samples are retained in the result data.

## Complete application results

Median milliseconds for one task edit, including reconciliation and both plugins:

| Tasks per document | Previous incremental-text implementation | This phase |
| --- | ---: | ---: |
| 100 | 64.51 | 36.74 |
| 1,000 | 201.59 | 156.78 |
| 10,000 | 1,732.57 | 1,651.54 |

The 10,000-task improvement is **4.7%** against the freshly measured baseline.
The preceding report measured that baseline at 2.100 seconds in a separate run;
using that older measurement would overstate this phase's gain. Small-fixture
results are particularly sensitive to warm-up, including the new schema rebuild
before timed work. These are three-sample observations, not latency guarantees.
See [all update samples](incremental-facts-updates-2026-09-06.json).

A separate instrumented 10,000-task run attributes the following median costs:

| Phase | Milliseconds |
| --- | ---: |
| Source/target maintenance, including old binding invalidation | 451.7 |
| Fact comparison and writes | 747.4 |
| Durable commits | 290.9 |
| Plugin adapters | 89.2 |
| Text/FTS maintenance | 10.6 |
| Snapshot parsing | 4.5 |

The fact span combines comparison and SQL work; it does not isolate those costs.
Medians need not sum to the total, and parent storage spans must not be added to
their children. The first plugin-adapter sample was cold (337.9 ms). See the
[profile samples](incremental-facts-profile-2026-09-06.json).

Retaining fact values has not removed per-revision location/target updates or
their secondary-index maintenance. The next investigation should examine how
already prepared producer outputs can be published coherently with fewer binding
writes and commits, while retaining independent producer failures and exact
source provenance. A different provenance representation would also need query
performance and stale-evidence conformance gates. Neither change is implemented
or established as the solution by this measurement.

## Validation and delivery

- **574 native tests passed**, with 22 ignored/opt-in tests. The initial workspace
  run failed a sync timing gate at 17.31 seconds while an Android build was active.
  The full sync suite subsequently passed in isolation; a separate timed repeat
  passed at 4.165 seconds against its five-second budget. Final totals replace
  the original sync results without double counting. The index suite was rerun
  after the last identical-layout and statement-caching optimizations.
- Release workspace/all-target Clippy passed with warnings denied.
- **633 desktop tests passed**, with five opt-in methods skipped. The complete
  application benchmarks ran separately against the respective packaged DLLs.
- Windows and both Android kernels were rebuilt with Whisper. Bindings generated
  independently from each target remained unchanged. Android app and test APK
  builds passed; no device was attached, so Android runtime checks remain unrun.
- The final duplicate-sensitive audit matched all **63,505 facts** against the
  frozen control. It found zero missing/additional facts, unbound or invalid
  current bindings, or unused locations. Current source provenance was checked
  separately because the frozen control uses synthetic source versions.

See [validation counts and binary hashes](incremental-facts-validation-2026-09-06.json)
and [fact equivalence](incremental-facts-fact-equivalence-2026-09-06.json).
The normal release DLL is restored and staged; profiling uses a separate binary.
Broader People and shared-refactoring acceptance remains open.
