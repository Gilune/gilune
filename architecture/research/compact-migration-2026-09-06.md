# Compact index migration

Date: 2026-09-06

The production index is now schema 9. There is one physical fact store, shared
by search, lookup, location queries and legacy anchor APIs. The migration does
not introduce domain-specific SQL or a dependency between People and Tasks.

## Delivered changes

- `fact_values` contains integer location/predicate/target IDs plus values and
  typed data. Locations and owned predicates are interned. The old wide `facts`
  table and its indexes are gone; `facts` is a relational compatibility view.
- The batch writer inserts compact rows directly. Source replacement preserves
  stale evidence but detaches target bindings. Producer replacement and removal
  collect unused locations. Existing receipts and transaction boundaries remain.
- Every item query uses compact targets, including text and residual predicates.
  Text reconstruction uses indexed own ranges, preserving nested-item exclusion,
  CRLF and Unicode. Display facts are fetched for retained results.
- Lookup intersects target IDs and performs bounded root probes. Document/line
  queries without relevance select pages in SQL and project selected snippets.
  Their legacy scope and unversioned-fact semantics remain available.
- Independent query handles provide pages and generation-bound exact counts on
  separate read-only connections. They release the Store lock before execution.
  Cancellation interrupts long SQL and prevents obsolete result publication.
- Both shells represent unknown totals explicitly, publish pages before counts,
  cancel replaced queries and prevent counts crossing generations. Enumeration
  avoids requesting a count for every page. Existing exact-total APIs remain.

Relevance and arbitrary pure callbacks can still require inspecting all matching
candidates. This is not a promise of constant-time matching or universal SQL-only
execution. Lookup retains its existing exact-total/offset-cursor contract. Full
document updates still replace text/FTS and producer output; they are not a
task-level incremental update algorithm.

## Validation

- Native workspace: **565 passed**, 21 opt-in/ignored. The final workspace run
  encountered a listener-port race in the sync `gate_b` restart test. The entire
  sync suite passed when rerun serially; the reported total substitutes those
  results without counting tests twice. See the [validation record](compact-migration-validation-2026-09-06.json).
- Release workspace/all-target Clippy: passed with warnings denied.
- Desktop: **633 passed**, five opt-in tests skipped.
- Android application and instrumented-test APK builds: passed.
- Windows and both Android kernels include Whisper. Bindings independently
  generated from all three target binaries have identical SHA-256:
  `76006804D81C752D0862B7598BC3602F55DB91EA4D09E49AD46CF277DAA6427C`.
- No Android device was attached; Android runtime validation remains unrun.

New conformance covers independent count/page concurrency during a writer
transaction, expired generations, cancellation during a blocked callback,
public Kotlin pending-count/error behavior, text/mixed-negation equivalence,
and grouped/sorted/zero-snippet document/line pages. Existing large-population,
source-revision, producer failure, rebuild, deletion, move, namespace ownership
and rollback tests continue to pass.

The selective 10,006-root lookup test checks fewer than 3,000 SQLite VM steps
and no automatic index for one match. It caught an expensive join choice during
normalization; the final target-based lookup passes the original bound.

## Controlled measurements

The [53-case matrix](compact-migration-matrix-2026-09-06.json) compares complete
first pages against the old executor on identical data. Forty-three fact cases
passed, then all ten text cases were rerun after the window/prefilter changes.
Every logical fact, including duplicate row identity, was compared in both
directions before the migration's temporary wide table was removed.

[Compacted storage](compact-migration-storage-2026-09-06.json), including FTS,
source maps and all production indexes: **148.84 MiB (schema 8) → 73.41 MiB
(schema 9), 50.7% smaller**. The fixture contains 320,000 facts and 50,500 distinct
locations across 50,000 tasks. The preserved conversion input predates the two
schema-8 projection indexes; its 145.92 MiB baseline and the separately measured
148.84 MiB schema-8 copy are both recorded explicitly.

Text comparison medians (milliseconds):

| Query shape | Old executor | Compact executor |
| --- | ---: | ---: |
| Broad body text, 50,000 matches | 419 | 293 |
| Short body substring, 50,000 matches | 446 | 283 |
| One matching task in the final document | 336 | 2.0 |
| Absent body term | 327 | 1.1 |
| Path selecting one document | 352 | 3.3 |
| Path plus cross-plugin facts | 837 | 12.6 |

The text window retains at most 256 indexed physical lines and never decodes a
whole source map. Positive single-line text uses an indexed document prefilter;
exact own-item and case-sensitive matching still occurs afterward. Multiline
text and negative clauses retain their complete semantics. A 1,200-line nested
fixture verifies window boundaries and revisiting child items.

There is observable timing variation in mixed old/new runs: early fact counts
sometimes took 0.5–0.9 seconds and one negative-text case took 710 ms. Separate
[SQL/statement-cache controls](compact-migration-count-plans-2026-09-06.json)
confirmed indexed plans and stable work counts: all tasks ~30 ms, open tasks
~75 ms and Alice/month ~138 ms. Clearing the statement cache did not explain
the difference. An isolated [text repeat](compact-migration-text-controls-2026-09-06.json)
measured the negative-text case at 218 ms versus 400 ms for the old executor.
These controls do not establish the cause of the mixed-run variance; the slower
samples remain in the matrix and are not silently discarded.

Builds and other large SQLite workloads are kept separate from timing runs.
Native comparisons use identical preserved source/fact data. Application tests
use real Kotlin DSL compilation, plugin callbacks and the packaged release DLL.
Update timings include reconciliation and complete producer/text-index updates.

### Real application queries

The [application measurements](compact-migration-application-2026-09-06.json)
exercise the actual Kotlin DSL, both independent plugins and the final packaged
release DLL. This query matches 17,500 of 50,000 tasks:

```text
type:task is:open people.connection:assign:@alice due:after:2026-08-31 due:before:2026-10-01
```

| Operation | Schema 8 | Schema 9 |
| --- | ---: | ---: |
| First 50, including synchronous exact total | 149.0 ms | 127.9 ms |
| First 50, independent page | Unavailable | 11.3 ms |
| Independent exact count | Unavailable | 132.2 ms |
| All 17,500 results, 35 pages, default order | 6.52 s | 1.67 s |
| All 17,500 results, 35 pages, due-date order | 9.25 s | 3.62 s |

Independent page and count measurements are separate calls; their sum is not a
single-query latency. The UI can deliver the page while counting proceeds.
Enumeration now avoids redundant counts. At 50,000 tasks the Alice/Bob membership
variant returns its first 50 in 11.4 ms, with an exact count of 25,000 taking
175.9 ms separately. DSL compilation takes 0.151 ms median. At 10,000 tasks the
Alice/month page and count take 7.0 ms and 28.9 ms respectively. First-after-setup
samples in the raw record are not OS-cold measurements.

### Complete document updates

The [update measurements](compact-migration-updates-2026-09-06.json) change one
task's date, reconcile and reindex the complete source with both plugins, then
restore the original source. Three trials ran per size, separately from builds
and other database workloads.

| Tasks in edited document | Schema 8 median | Schema 9 median | Schema 9 range |
| --- | ---: | ---: | ---: |
| 100 | 67.7 ms | 75.0 ms | 60.9–79.7 ms |
| 1,000 | 288.6 ms | 305.9 ms | 304.3–311.7 ms |
| 10,000 | 2.724 s | 3.097 s | 2.966–4.044 s |

The large-document median is 13.7% slower than the preceding schema-8 run.
Normalization saves storage but does not establish a write-latency improvement;
the complete replacement path remains a performance limitation. These are
separate three-sample runs, not proof of which storage operation caused the
difference. The earlier 8–9.5-second path has improved substantially over the
whole project, but that must not hide this latest regression. Parser/plugin
callbacks account for only about 51–57 ms of each large-document trial.

After the final restore, a [duplicate-sensitive audit](compact-migration-fact-equivalence-2026-09-06.json)
compared all **63,505 facts** with the preserved earlier wide-store fixture:
no missing or additional facts, no invalid or unbound current targets, no missing
dictionary/location records, and no unused locations. Synthetic source versions
in the frozen control differ from current source hashes; current version and
location bindings were checked separately against the live source maps.

## Delivery and remaining limits

The index/search migration is complete: production consumers share schema 9,
older derived indexes rebuild through the normal path, both shells use the new
query handles, and public compatibility methods remain available. Historical
comparison executors and opt-in experiments remain test-only; not every historical
benchmark was rerun. The broader People/refactoring delivery is still separate.

Windows and both Android native resources were rebuilt and staged. The tested
Windows DLL and packaged copy have SHA-256
`07C3222EF448D5EEF1DAEF7D399DC2BD32845AE1BD2CDFC93B94F30CCC1A5522`.
Android runtime checks, OS-cold budgets, large-document incremental updates and
the cause of mixed-run timing variance remain unproven. Broad text/residual
matching and sorted enumeration can still perform substantial candidate work.

The earlier production baseline is documented in the
[schema 8 report](target-executor-2026-09-06.md). Its timings are separate runs;
they are not an isolated attribution of every difference to physical storage.
