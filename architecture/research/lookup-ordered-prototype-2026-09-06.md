# Registered ordering and deferred counts — prototype evidence

Date: 2026-09-06. **Test-only prototype; not enabled in the application.**

The owner requested a prototype after the existing kernel lookup took roughly
0.73 seconds with 50,000 matching roots. This experiment tests reusable ordering,
keyset continuation, maintained freshness evidence and counts off the page path.
It changes no production schema, API, DLL, Android library or shell behavior.

## Results

Same synthetic 50,000-root fixture and literal-prefix request as the
[previous profile](lookup-optimization-2026-09-06.md). Release build, SQLite 3.45.0,
isolated Windows run. First-page figures are seven warmed medians; the prototype
includes index-state checks, candidate probing, selection, lookahead and display-
fact hydration. It excludes exact counting, FFI and rendering. Setup is excluded.

| Request | Existing complete lookup | Prototype page, count deferred |
| --- | ---: | ---: |
| First 1, all 50,000 match | 727.73 ms | **0.413 ms** |
| First 30, all 50,000 match | 721.32 ms | **0.617 ms** |
| First 500, all 50,000 match | 729.07 ms | **4.244 ms** |
| First page, one matching root in 50,000 | 100.69 ms | **0.375 ms** |

Additional measurements:

- Page 19 at 500/page: **4.291 ms median**, excluding preceding pages.
- All 50,000 results at 500/page: **735.71 ms**, including last-page discovery
  and duplicate checks, with zero source-file reads. Full enumeration of 50,000
  on the existing implementation was not measured; do not invent a before value.
- First exact count, requested separately: **324.19 ms**. Reusing the one-entry
  count cache for the same query and index generation: **0.040 ms median**.
- Foreground 30-row pages while a count ran on another SQLite read connection:
  **0.666 ms median**; that concurrent count took **325.70 ms**.
- A negative-only condition with no matches, and no selective positive seed:
  **111.55 ms median**. The engine must still establish absence across the full
  universe in this case; the fast broad-match figures are not a universal bound.

The [raw timings and expanded query plans](lookup-ordered-prototype-2026-09-06.txt)
are retained. A 500-row first page plus lookahead performs 56,151 VM steps in
its tier selection statement and **zero sorts**. Page 19 uses
`prototype_order_key (layout=? AND (k0,k1,k2,k3,rel_path)>(?,?,?,?,?))` and also
performs zero sorts. It seeks from the last returned key rather than using OFFSET.

## What is reused and maintained

### Registered ordering

One generic ordering definition selects source namespaces and up to four sort
predicates. A derived row per source stores its source version and minimum sort
values. The value encoding preserves the existing binary text order, empty values
and missing-values-last behavior. An index over `(layout, keys..., path)` supplies
the requested order. The rows are reusable across query fragments; they are not
50,000 cached search results for every keystroke.

Priorities remain query-dependent. Alternatives are processed in priority tiers;
each tier excludes earlier matching tiers, so overlaps cannot duplicate rows.
A bounded probe of positive constraints identifies small candidate sets. Small
sets are evaluated and sorted directly; broad sets use the ordering index and
stop after enough results plus one lookahead row. The probe limit of 64 is an
experimental threshold, not a tuned production policy. The final negative-only
measurement demonstrates why a general fallback still matters.

Rows receive current-source and fact checks through the existing opaque constraint
compiler. Only retained page rows have display facts hydrated. A continuation
contains the priority, ordering keys and path, bound to query fingerprint and
index generation. Changed query or generation invalidates it.

### Freshness evidence

One mismatch count per required producer replaces a repeated two-direction source/
receipt scan. SQL triggers maintain the count as source maps and producer receipts
are inserted, updated, moved or deleted, within the writer's transaction. Missing
receipts and orphan receipts both contribute. Rollback restores the previous
summary. Ordinary namespace cursors, errors and availability are still checked.

Dirty markers stop reads of stale ordering data. The experiment explicitly
refreshes an affected source's ordering row during the simulated writer transaction
and advances index generation before commit. It does not connect this maintenance
to the real indexing pipeline yet. Dirty tracking is deliberately conservative:
every changed fact/source marks its root for the single installed layout.

### Counts and connection ownership

Exact count uses the existing kernel constraint semantics in a separate query,
omitting sort preparation. A bounded one-entry cache keys the answer by query and
index generation. A changed generation or request requires a new count; incomplete
index state cannot reuse a cached answer.

The current FFI holds `Mutex<Store>` for lookup. Merely launching the existing API
on a background thread would still block foreground calls. The experiment therefore
also executes counts on a separate read-only SQLite connection, with its own WAL
read snapshot. Foreground pages continue on the original connection. A conformance
test updates data after the count snapshot is established: the count correctly
describes its old generation, and that generation must be rejected for the new
page. Production needs this reader boundary and publication check, not just a
coroutine around the existing count call.

## Storage and write cost

- Registering/building this ordering and its freshness evidence over 50,000 roots:
  **630.33 ms** in one measured build.
- Additional SQLite allocation: **8,564,736 bytes (8.17 MiB)** for this ordering,
  indexes, counters and dirty tracking. More independent layouts would add cost;
  this is not a free or universal ordering cache.
- One simulated source edit updates source version, its derived facts and producer
  receipts, advances generation and commits. Median without the prototype:
  **1.394 ms**; with triggers and ordering refresh: **1.384 ms**. These sequential
  samples are effectively indistinguishable at this resolution, not evidence of
  zero maintenance overhead or a write speedup. They exclude Markdown parsing,
  source I/O, history/sync work and hashing; bulk ingestion remains unmeasured.

## Correctness and scope

The normal test compares complete prototype pages and counts with the existing
kernel for mixed exact/prefix priorities, collisions, multiple and missing sort
values, negative-only and empty alternatives. It checks stale cursor rejection,
ordering invalidation, count cache invalidation and a late count from an older WAL
snapshot. Maintained mismatch counts are compared with the full-scan oracle for
source changes/deletion/rename, missing/orphan receipts and namespace movement,
including rollback. The scale test checks expected page contents, the 19th-page
boundary, complete enumeration without duplicates and zero source reads.

This is one ordering layout in an isolated synthetic store. It is not a production
registration lifecycle, schema migration, general query cost planner, API update
or Android/UI implementation. It does not prove behavior under every combination
of predicates, skew, four populated sort keys, many layouts, plugin changes or
concurrent writers. Long-lived count snapshots, cancellation and WAL retention
still need bounded production behavior.

## Recommendation and production work

The measured improvement justifies developing this approach. Before promotion:

1. Define a bounded, owned registration for reusable ordering layouts through the
   plugin contract. Deduplicate equivalent layouts and specify eligibility,
   dependencies, lifecycle/removal and storage limits; avoid multiplying rows
   across every source for many unrelated plugin views.
2. Integrate alignment counters, dirty tracking and incremental order refresh with
   real kernel maintenance/rebuild transactions, including partial failures and
   schema replacement. Prove generation changes and metadata survive recovery.
3. Add a page contract with lookahead/continuation and a separate count result
   tagged by query and generation. Preserve or explicitly version the existing
   exact-count contract. Let directories publish counts later; suggestions need
   not request them.
4. Provide bounded, cancellable read workers outside the global Store mutex.
   Recheck lifecycle/generation before publishing a result; bound count caching
   and WAL snapshot lifetimes.
5. Keep selective-candidate execution and a fallback for costly absence/filter
   shapes. Test realistic mixed values, selective base constraints, ties, many
   priorities and negative queries before choosing a dispatch threshold.
6. Measure bulk indexing, multiple layouts, actual platform/FFI/UI latency and
   production pagination. The prototype supports the architectural decision;
   it does not complete the application's performance gate.

All indexing, selection, continuation and count execution remain kernel-owned.
Plugins declare opaque predicates/orderings and their domain presentation. No
People/Tasks dependency or domain dispatch is introduced.

## Reproduction

```powershell
$env:GILUNE_LOOKUP_PROFILE_OUT = "$PWD/app/build/lookup-ordered-prototype"
cargo test -p gilune-kernel --release --offline --manifest-path kernel/Cargo.toml profile_ordered_lookup_prototype_50000 -- --ignored --nocapture --test-threads=1
```

Implementation:
[`ordered_prototype.rs`](../../kernel/crates/gilune-kernel/src/index/lookup/tests/ordered_prototype.rs).
Only the ignored benchmark and normal conformance test construct these tables;
the shipped kernel does not contain this implementation. Workspace/all-targets
Clippy, all seven normal lookup tests and the isolated release benchmark passed.
Five manual lookup benchmarks are ignored by ordinary runs. Logs are under
`app/build/lookup-ordered-prototype*`.
