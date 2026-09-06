# SQLite lookup profile — 2026-09-06

The approximately 204 ms first-page lookup is dominated by SQLite execution,
not request translation. The query uses existing indexes, but its prefix tests
cannot seek by prefix, and ranking/sort preparation processes the whole root
population. This is avoidable work to address before accepting editor latency.

This investigation adds test-only instrumentation and an ignored reproducible
harness. It does not change production SQL, indexes or lookup semantics.

## Measurements and scope

Release build, isolated Windows run, SQLite 3.45.0, seven warmed samples; medians
below. The fixture contains 10,006 indexed roots with the same key/name, so all
match `al`. It deliberately bypasses filesystem/indexing throughput. The measured
entry point is Rust `Store::lookup`: neither Kotlin, FFI, UI nor DSL parsing is in
the measured interval. The UI provider also submits a structured lookup request,
rather than translating a textual search DSL for this operation.

| Stage | Median |
| --- | ---: |
| Complete direct lookup, first 30 rows | 198.511 ms |
| Main SQLite execution and collecting result rows | 178.617 ms |
| Namespace/source freshness SQL | 19.217 ms |
| SQLite statement preparation | 0.436 ms |
| Selected-root fact hydration | 0.166 ms |
| Generation, cursor and change-feed tip | 0.075 ms |
| SQL composition and parameter construction | 0.035 ms |
| Request validation, canonicalisation and fingerprint | 0.013 ms |

Per-stage medians need not sum to the total median. Approximately 90% is main
query execution and 10% freshness checks. Hydration of the 30 retained roots is
small. These timings reproduce the earlier 203.95 ms result; they are not a new
optimization. The previous 4.30-second enumeration uses 500-row pages: 21 separate
queries repeat matching, ranking, counts and freshness checks. That explains why
enumeration is expensive despite returning only indexed data.

## Actual SQL and index use

The [complete expanded SQL](lookup-baseline-2026-09-06.sql) contains the actual
synthetic bound values. The [raw evidence](lookup-profile-2026-09-06.txt) includes
all phase timings, indexes, EXPLAIN QUERY PLAN output and statement counters.

The main shape is:

```sql
-- allowed: all root facts in the requested namespaces
-- c1: base kind constraint; c2/c3/c4: exact key / key prefix / name prefix
-- matched: source-aligned base roots, with minimum matching alternative priority
-- sort_values: grouped minimum name/key for all allowed roots
-- ranked: matched LEFT JOIN sort_values
SELECT rel_path, source_version, priority, COUNT(*) OVER()
FROM ranked
WHERE priority < 4294967296
ORDER BY priority, s0 IS NULL, s0, s1 IS NULL, s1, rel_path
LIMIT 30 OFFSET 0;
```

1. **Indexes exist, and equality uses them.** The fact table has
   `(predicate,value)`, `(predicate,num)`, `(rel_path)` and `(namespace,rel_path)`
   indexes. Source maps and producer receipts have primary-key indexes. Exact
   comparisons use `facts_predicate_value (predicate=? AND value=?)`.
2. **Prefix matching is not an indexed range.** Its expression is
   `instr(gilune_fold(value), 'al') = 1`. Both prefix subqueries use
   `facts_predicate_num (predicate=?)`: SQLite narrows to that predicate, then
   evaluates the prefix expression over its values. There is no folded-value
   index in the shipped schema. The value part of `(predicate,value)` cannot
   provide a prefix seek for this expression either.
3. **The query constructs too much intermediate data.** It materializes base
   constraints, alternative matches, all allowed root facts, matched roots and
   grouped sort values. The plan includes `USE TEMP B-TREE FOR GROUP BY`, an
   `AUTOMATIC COVERING INDEX` joining sort values, and `USE TEMP B-TREE FOR ORDER
   BY`. The 30-row limit does not bound this upstream work. Exact total counts
   require all matches, while the current sort aggregation additionally covers
   roots that do not match the alternatives.
4. **Freshness is another population-wide query.** Per required producer,
   two-direction left joins compare `source_maps` and `fact_sources` versions.
   A fully aligned index must establish that no mismatch exists across the
   population. This check runs again on each lookup/page. It is necessary
   correctness work in the present design, but need not remain a full check on
   every keystroke if equivalent evidence is maintained during index updates.

The main statement measured separately takes **181.918 ms**, executes
**3,792,754 SQLite VM steps**, records **80,042 full-scan steps**, two sorts and
**10,005 automatic-index rows**. Full-scan steps include scans of intermediate
results; these are not source-file reads or evidence of 80,000 base-table scans.
The reported 37,136 statement bytes measure compiled statement memory, not peak
query memory. Zero Markdown reads does not mean zero SQLite database or temporary
file I/O; no OS I/O trace was collected here.

## Controlled experiments

These times measure only the main SQL statement, excluding the approximately
19 ms freshness phase. Each is a seven-sample warmed median. Experiments are
sequential, not randomized; small differences are directional evidence rather
than stable budgets. Temporary indexes/settings affect only the disposable
fixture. Equivalent-query experiments assert identical returned rows, priorities
and counts. Ablations deliberately change required behavior and are not fixes.

| Experiment | Main SQL median | Interpretation |
| --- | ---: | --- |
| Original query | 181.918 ms | Comparison baseline |
| Remove ranking alternatives | 118.202 ms | Significant alternative-selection cost; changes behavior |
| Remove declared sort fields | 102.847 ms | Significant sort preparation cost; changes behavior |
| Replace all MATERIALIZED hints with NOT MATERIALIZED | 196.719 ms | Slower; removing materialization wholesale is not a fix |
| Temporary storage in memory | 162.886 ms | Modest benefit with a memory tradeoff; does not establish actual disk I/O |
| Partial covering index for root facts | 169.411 ms | Modest benefit; adds about 3.36 MiB to a 10.70 MiB fixture database |
| Folded-value expression index, original prefix expression | 165.537 ms | Still does not bound the prefix range |
| Folded-value index plus ASCII range prototype | 161.865 ms | Plan confirms an expression range seek; full-population sorting remains |

The diagnostic ASCII range replaces the fixture's `al` prefix with `>= 'al' AND
< 'am'`. It is not a general Unicode implementation. Any production change must
preserve the existing folding and literal-prefix semantics, including punctuation
and boundary cases. Ablation differences are not additive component timings.

A second distribution uses distinct keys and names, with the original SQL and
original indexes restored:

| Fragment | Matching roots | Main SQL median |
| --- | ---: | ---: |
| `entry.00000` | 1 | 130.395 ms |
| `does-not-exist` | 0 | 64.632 ms |
| Empty fragment, no alternatives | 10,006 | 113.327 ms |

Even one match builds sort data and an automatic join index for 10,006 roots.
This makes query shape a stronger first target than simply adding more indexes.

## Recommended next implementation

1. Identify eligible matching roots before fetching/aggregating their sort facts.
   Preserve alternative priorities, negatives, source alignment, collisions,
   complete counts and deterministic pagination. Measure broad, selective and
   missing prefixes independently.
2. Compile literal folded-prefix constraints into a proven indexable operation;
   choose the smallest useful index from its actual query plan. Measure storage
   and index-maintenance cost as well as lookup time. Do not add every experimental
   index to the schema.
3. Maintain or reuse source-alignment evidence tied to the relevant index state,
   rather than rescanning it per lookup. Prove invalidation for producer changes,
   failures, partial indexing, source edits and rebuilds before replacing checks.
4. Profile complete enumeration again after reducing per-query work. Consider
   reuse of generation-bound results if repeated selection remains dominant;
   keyset paging alone does not eliminate the cost of repeated complete counts.

All of this belongs in generic kernel indexing/query execution. People continues
to declare domain constraints and sort fields; the shell renders bounded pages.
No People/Tasks-specific SQL or shell-side selection is needed.

## Reproduction

The harness is
[`lookup/tests/profile.rs`](../../kernel/crates/gilune-kernel/src/index/lookup/tests/profile.rs).
Timing calls are `cfg(test)` and absent from production builds. Ordinary tests
leave tracing disabled. Run without competing builds:

```powershell
$env:GILUNE_LOOKUP_PROFILE_OUT = "$PWD/app/build/lookup-profile"
cargo test -p gilune-kernel --release --offline --manifest-path kernel/Cargo.toml profile_lookup_stages_sql_and_indexes -- --ignored --nocapture --test-threads=1
```

The ignored release profile and its equivalence assertions passed. All four
ordinary native lookup tests and workspace/all-targets Clippy also passed after
adding instrumentation. This is a synthetic warm kernel profile, not a device/UI
latency benchmark or a completed performance acceptance gate.
