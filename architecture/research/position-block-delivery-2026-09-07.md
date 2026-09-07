# Relative position blocks: production delivery

Date: 2026-09-07

Schema 13 completes the position-storage migration investigated in the
[binary prototype](revision-position-storage-2026-09-06.md) and begun by
[shared revision frames](shared-revision-frames-2026-09-07.md). This completes
that storage phase, not the broader People/refactoring product plan or a claim
that the whole update pipeline is proportional to one changed line.

## What ships

The implementation uses small relational position blocks, rather than the
prototype's binary document map. Compact predicates and fact values still join
directly to private target IDs. Filtering, sorting and counting do not decode
positions. Returned results resolve indexed block bases and relative ranges.

Each initial block holds up to 64 line or item targets. Edits retain matching
slots, split runs above 128 members, and coalesce adjacent small runs. Sparse
private order labels handle insertions without renumbering every later item.
Exhausted gaps redistribute a neighbourhood; arbitrary reordering may require
more work. These labels are not public identity. Cursors remain generation-bound.

Current fact locations and indexed text refer to retained slots. A shifted
prefix changes roughly one base per block, plus the affected block's members,
instead of rewriting every later fact/text coordinate. This is O(block count)
base maintenance, not a claim of constant-time updates regardless of document
size. Unchanged text retains its FTS row ID.

Fresh evidence from every affected owner is required for coherent publication.
Missing/failed owners retain their original source-qualified coordinates;
fallback materialises their locations before geometry moves. Geometry, source
replacement, values, receipts and preparation markers share the write transaction.
Rollback and independent WAL snapshots preserve the previous state. Schema 13
rebuilds disposable older indexes without rewriting source documents. People
and Tasks remain independent consumers of generic indexing contracts.

Legacy line/property readers, root lookup, target lookup, snippets, pagination
and text-search projection all use the new representation. Search performs no
source-file reads. The historical binary read experiment remains test-only;
superseded manual frame/coordinate benchmark entry points were retired in favour
of the frozen schema-12 versus production schema-13 delivery comparison.

## Query comparison

[Raw samples, DSL and stage timings](position-block-results-2026-09-07.json)
contain 118 comparisons: 53 DSL cases on each of two document distributions,
then four cases at page 19, first 500 and first 1. Each comparison runs five
alternating old/new samples with one live reader, explicit warmup, setup excluded
and no competing benchmark/build. The old control includes the same public
requirements validation as production. Its schema is normalised to shared-frame
schema 12 before disk comparison.

Assertions compare complete results, totals, source coordinates, text and facts.
Each model advances with its own opaque cursor; continuation presence and
returned content must agree. Every measured query asserts zero source-file reads.
The cases include conjunctions, negatives, missing facts, sets, paired roles,
dates, grouping, text/path/file filters, sorting, empty results and broad results.

Median page-only time, milliseconds:

| Population / query | Schema 12 | Schema 13 |
| --- | ---: | ---: |
| 10,000 tasks in one document, all / first 50 | 2.572 | 1.448 |
| 10,000, Alice open due this month / first 50 | 2.889 | 1.832 |
| 50,000 tasks across 500 documents, all / first 50 | 4.121 | 3.082 |
| 50,000, Alice open due this month / first 50 | 5.110 | 4.081 |
| 50,000, Alice due month sorted by due / first 50 | 71.647 | 71.434 |
| 50,000, Alice due month / page 19 | 5.533 | 4.406 |
| 50,000, Alice due month / first 500 | 28.858 | 18.520 |
| 50,000, Alice due month / first 1 | 2.535 | 2.548 |

The actual exported DSL for the Alice case is:

```text
type:task is:open people.connection:assign:@alice due:after:2026-08-31 due:before:2026-10-01
```

The sorted case adds the `org.memory.tasks.due` sort predicate. It matches 3,500
items in the 10,000 fixture and 17,500 in the 50,000 fixture. Exact counts and
broad sorted/text evaluation still traverse qualifying candidates. Small timing
differences are noise, not universal speed guarantees; these measurements do
not establish OS-cold latency or a new product latency budget.

To repeat the delivery comparison, export `dsl-cases.json` with the application
DSL benchmark and retain a pre-schema-13 fixture. From `kernel`, set
`GILUNE_MATRIX_DIR` to the export folder and `GILUNE_COMPACT_DB` to the old
fixture's SQLite file, then run:

```powershell
cargo test -p gilune-kernel --release compare_position_delivery -- --ignored --nocapture --test-threads=1
```

Optional `GILUNE_POSITION_CASES`, `GILUNE_POSITION_PAGE_SIZE` and
`GILUNE_POSITION_PAGE_NUMBER` select the cases and page. The test copies the
fixture, never mutates the input, and prints the output directory. Run alone.
The normal application edit harness is
`SearchShapeBenchmarkTest.profileExistingShapeEdits`, selected with
`GILUNE_SHAPE_PROFILE`, `GILUNE_SHAPE_PROFILE_SIZES=10000` and
`GILUNE_SHAPE_EDIT_KIND=value|longer|prefix|delete` (one kind per run).

Vacuumed SQLite sizes, including the extra positional indexes:

| Fixture | Schema 12 bytes | Schema 13 bytes | Increase |
| --- | ---: | ---: | ---: |
| 10,000 tasks | 13,410,304 | 13,934,592 | 3.9% |
| 50,000 tasks | 68,509,696 | 72,941,568 | 6.5% |

## Complete application edits

[Application samples and restored-data audit](position-block-application-2026-09-07.json)
use cloned copies of the same 10,000-task document, saved normal Whisper-enabled
schema-12 DLL, then the rebuilt normal schema-13 DLL. Each edit is applied and
restored three times through the real application and both plugins. Warm medians
use samples 1 and 2; sample 0 is retained separately because registration and
cache state affect it. Reconciliation includes index maintenance.

| Edit | Schema 12 warm ms | Schema 13 warm ms |
| --- | ---: | ---: |
| Same-length value | 360.299 | 310.427 |
| Longer first task, including emoji | 620.607 | 448.842 |
| Insert a prefix paragraph | 720.241 | 506.126 |
| Delete the first task | 703.215 | 441.055 |

After the trials, all 63,505 facts, public target geometry and indexed text match
the original duplicate-sensitively. Source document bytes are restored; there
are no unbound/invalid facts, preparation markers or temporary source IDs, and
SQLite integrity passes.

The focused 10,000-target storage test changes 127–130 target rows and 312–313
block bases for length/prefix/insertion/deletion examples. Length and prefix
shifts change no fact-location or text rows; the deletion case changes four
location rows and two text rows. Full-rebuild equivalence is asserted after each
edit. This removes the mass coordinate writes but still assembles and compares
whole-document geometry and producer output. Parsing context, input matching,
fact comparison and durable commits remain real costs.

A separate [instrumented longer-line run](position-block-profile-2026-09-07.json)
measures 451 ms warm total: about 153 ms fact comparison, 121 ms coordinate
preparation, 64 ms plugin adapters, 16 ms durable commits and 5 ms snapshot
parsing. Parent spans overlap their children: publication (348 ms), shared
location advancement (136 ms) and coordinate preparation must not be summed.
The normal DLL is restored after profiling; diagnostic timers are absent from
normal builds. This attributes remaining work without calling it SQLite write
time or claiming recognition reparses every task.

## Verification and remaining limits

Final platform results and binary fingerprints are recorded in
[validation evidence](position-block-validation-2026-09-07.json).
The full native workspace passed 591 tests; the desktop suite passed 635, with
five opt-in benchmarks skipped. Release Clippy passed with warnings denied. Both
Android ABIs (with Whisper), the application APK and the instrumented-test APK
built successfully. No Android device was attached, so runtime/device checks
remain unrun. Bindings were generated from each platform/ABI binary and verified;
the normal Windows DLL is staged after the separate diagnostic run.

Native coverage includes rebuilds, stale/unavailable producers, equal-layout
fallback followed by movement, rollback, concurrent reader snapshots, Unicode,
CRLF, nesting, deletion, repeated reordering, block fragmentation and order-gap
exhaustion. Publication tests compare facts, geometry and indexed text with full
replacement, rather than only checking the implementation's internal counters.

The 10,006-root lookup fixture now populates copied roots through the production
batch writer. The compatibility-view copying setup had taken about 191 seconds;
the production-writer fixture completes in about 13 seconds with the same
results. First 30 lookup results remain about 80 ms and full enumeration about
1.83 seconds in that fixture. Setup improvement is not reported as query speedup.

Further work to make complete edits local must address the remaining assembled
geometry/fact processing and transaction costs without weakening source evidence.
The current delivery preserves conservative fallback for unavailable producers
and parser-context changes; those cases can legitimately perform broader work.
