# Production target executor: fact queries and bounded projection

Date: 2026-09-06

Fact-only item search now uses the generic source-qualified target/fact indexes
for matching, ordering and continuation. It loads only the selected snippets and
facts. People and Tasks contribute independent constraints through their existing
contracts; neither plugin nor the shell owns the execution strategy.

## Complete DSL matrix

All **53 actual Kotlin-exported DSL cases** return exactly the same first page as
the previous executor: hits, text, fact order/duplicates, source ranges, totals,
issues and continuation. **43 cases use the new route**. Ten text/relevance cases
retain the previous route and are explicitly marked in the results. Small native
conformance fixtures additionally compare every page through complete traversal,
all fact operators, numeric values, scopes, groups, missing/multiple sort values,
independent owners, empty snippets, and source-version/receipt failures.

The 50,000-task fixture has 500 documents and actual Tasks/People facts. A disposable
copy of the preserved complete index receives only the two projection indexes;
both executors read the same data and connection. The old route runs once per
case and the new route three times. These are Windows release measurements,
without concurrent build/test work, not OS-cold measurements or latency SLOs.

| DSL / presentation | Matches | Old route | New median |
| --- | ---: | ---: | ---: |
| `type:task` | 50,000 | 379 ms | 29 ms |
| `type:task is:open` | 42,500 | 418 ms | 64 ms |
| `type:task -is:done` | 42,500 | 18,877 ms | 85 ms |
| Alice, open, due this month | 17,500 | 1,849 ms | 198 ms |
| Same query, sorted by due date | 17,500 | 1,569 ms | 216 ms |
| `type:task -due:week` | 40,000 | 69,906 ms | 86 ms |
| Absent person | 0 | 86 ms | 1.6 ms |
| Alice grouped by document, due-date order | 500 documents | 1,437 ms | 296 ms |

The exact Alice DSL is:

```text
type:task is:open people.connection:assign:@alice due:after:2026-08-31 due:before:2026-10-01
```

Sorting is the existing separate `sort_predicate=org.memory.tasks.due` request
option; it does not change that DSL string. The matrix anchors relative dates to
2026-09-06. See the raw results for the exact queries and presentation options.

The shorter three-case control measured the same unsorted Alice query at 131 ms
(old route 1,180 ms), with about 125 ms counting, 0.7 ms selecting, and 2.6 ms
projecting 50 items. The broad matrix shows meaningful timing variability; it is
not appropriate to present the faster control as a guaranteed application time.
The architecture finding is consistent: synchronous exact counting dominates
the unsorted query once selection and projection are bounded.

## What changed

- Compile opaque fact constraints into probes on private target/predicate IDs.
  Reuse the established value-comparison implementation, including numeric/typed
  validity and Unicode prefix/contains behavior. Preserve separate witnesses for
  separate constraints on a multi-valued predicate.
- Apply whole-clause negation and explicit exact/root/inherited scope. Current
  source bindings and producer receipts constrain both positive and negative
  results. Missing predicates and capped sparse positive sets avoid needless work.
- Default ordering uses indexed path/item-key order with SQL continuation/limit.
  Constant relevance fields must not obscure that useful ordering.
- Value ordering walks the value index, verifies the independent minimum per
  target, deduplicates repeated facts, then visits missing values separately.
  It cannot assume one value per predicate or push a filter's lower bound into
  the minimum sort value: another value may witness that filter.
- Grouped queries aggregate matching targets and limit snippets per returned
  document. Grouping still requires aggregate selection work.
- Projection fetches selected target metadata and overlapping indexed physical
  lines, preserving own ranges, nested-item exclusion, Unicode, CRLF and existing
  displayed indentation/trailing-blank behavior. It does not decode a whole
  source map or load a whole document body to display an individual item.

The two-item projection fixture has 3,000 indexed items. Instrumentation confirms
only two items and two physical lines are reconstructed, with zero source-file
reads. Real 50-item pages can need more than 50 lines because items may continue
across lines. The existing `max_lines` item limit does not impose a byte limit on
one exceptionally large returned item.

## Actual application API and update check

The final Whisper-enabled Windows kernel was also exercised through the real
Kotlin DSL compiler, independent Tasks/People registrations, FFI and public Store
search. At 50,000 tasks, warm medians across five calls are:

| Request, 50 returned items | Complete application call |
| --- | ---: |
| All tasks | 50.7 ms |
| Open tasks | 71.6 ms |
| Open tasks assigned to Alice | 104.3 ms |
| Alice, open, due this month | **149.0 ms** |
| Alice or Bob, open, due this month | 176.0 ms |

The Alice query at 10,000 tasks takes 30.7 ms. DSL compilation at 50,000 tasks
takes 0.134 ms per query. These application and native-copy measurements are
separate runs; their differences do not isolate FFI overhead alone.

For Alice's 17,500 matches at 50,000 tasks, first-500 medians are 178 ms in default
order and 279 ms sorted by due date. Complete enumeration passes both identity
and ordering checks: 35 pages with no duplicates, in 6.52 s and 9.25 s respectively.
Every page still repeats the exact count; this cost is a reason to separate
count state from page selection, not a finished enumeration budget.

The controlled full-update repeat uses the previous 100/1,000/10,000-item warm-up
sequence. Medians are **67.7 ms, 288.6 ms and 2.724 s**, versus the prior schema-7
batch-writer run's 67.0 ms, 289.7 ms and 2.595 s. The large-source controlled range
is 2.711–2.748 s. It uses the normal packaged kernel; the earlier schema-7 timings
used the diagnostic build. This comparison does not isolate index maintenance
from all run/build differences.

An initial run measured 4.436, 4.347 and 2.674 s for the large source; those slower
samples are retained. A diagnostic large-only repeat measured 2.940, 2.748 and
2.680 s, with a read-only fact audit overlapping that repeat. Neither is substituted
for the controlled repeat above, which ran without another task workload. The
variation is not sufficient evidence to attribute a 4-second write regression
to the added indexes. After the final restore, all 63,505 facts match preserved
earlier data and current source bindings remain valid.

Repeated Gradle benchmarks must use `--rerun`: an environment-variable change
alone can retrieve cached test results. Only executions containing fresh measured
samples are used here. These tests verify the public kernel/application boundary;
they do not measure Compose rendering or device interaction latency.

## Storage and remaining work

Schema 8 adds `(source_id,kind,start_byte)` target lookup and
`(rel_path,field,line)` text lookup indexes. On the identical compacted fixture,
used index bytes increase from **153,006,080 to 156,073,984**: **2.93 MiB (2.0%)**.
This is additional access-path storage; no wide representation was removed.
Older derived indexes rebuild normally; authored documents, plugin callback
versions and public FFI signatures are unchanged.

The existing public page API still requires an exact total. Counting remains
synchronous in the same snapshot and Store lock; it is not included in the
sub-millisecond selection figure. Independent count state/read execution and
cancellation are the next boundary work. Sorted selection, grouped aggregation,
and count cache/read behavior still warrant optimization. Text/highlighting,
residual callbacks, document/line search and the separate location/lookup routes
have not all been moved to this executor. Wide storage remains until those
consumers migrate. This is a production step, not completion of P3/P6.

## Evidence and reproduction

- [All 53 DSL results](target-executor-matrix-2026-09-06.json).
- [Storage comparison](target-executor-storage-2026-09-06.json).
- [Application query measurements](target-executor-application-2026-09-06.json).
- [Controlled full-update samples](target-executor-updates-2026-09-06.json).
- [Post-update fact equivalence](target-executor-fact-equivalence-2026-09-06.json).
- Native opt-in test: `profile_production_target_executor`, with
  `GILUNE_MATRIX_RUN` pointing to the preserved full-index comparison,
  `GILUNE_MATRIX_DIR` pointing to the DSL export, and
  `GILUNE_PRODUCTION_CASES=*` selecting every case.
- Local measurements and build/test logs: `app/build/target-executor`.

The historical prototype adapters explicitly call the retained old executor so
their baseline labels do not silently change when the production dispatcher does.

Validation: **560 native tests passed** (20 ignored/opt-in), release workspace
all-target Clippy passed with warnings denied, and **632 desktop tests passed**
(5 opt-in methods skipped). The 53-case matrix, actual application benchmark and
update fixtures ran separately and passed. Android app and instrumented-test APK
builds passed. Windows and both Android ABIs rebuilt/staged with transcription;
bindings generated independently from each binary remain byte-identical to the
existing bindings. No Android device was attached for runtime testing.

The tested Windows DLL and packaged resource have SHA-256
`356DEB9344D0B2CD348B9BCEF9AAA7FDDE70F1F85D1C3868C6EC4BBD3C38A0EE`.
