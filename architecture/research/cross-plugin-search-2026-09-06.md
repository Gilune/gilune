# Tasks and People DSL query benchmark

Date: 2026-09-06. Diagnostic tests only; no production optimization in this change.

## What was measured

Earlier lookup measurements used direct `LookupRequest` values over synthetic
`fixture.kind`, `fixture.key` and `fixture.name` root facts. They did not run a
DSL query or combine Tasks and People. The ordered lookup prototype also targets
root lookup, not the `searchItems` path used by task queries.

This benchmark runs the actual DSL compiler, independently registered Tasks and
People plugins, their real indexers, UniFFI and the existing release kernel.
The main query asks for open tasks assigned to Alice due during September 2026:

```text
type:task is:open people.connection:assign:@alice due:after:2026-08-31 due:before:2026-10-01
```

`before` and `after` are exclusive. There is currently no `due:month` shorthand;
these bounds include September 1 and September 30. The multi-person query is:

```text
type:task is:open people.connection:assign IN ["alice", "bob"] due:after:2026-08-31 due:before:2026-10-01
```

Tasks supplies the type, status and date constraints. People supplies the paired
connection constraint. The shared compiler emits five exact-locality constraints:

| Predicate | Operation | Value |
| --- | --- | --- |
| `org.memory.tasks.state` | Exists | |
| `org.memory.tasks.state` | Equals | `open` |
| `org.memory.people.connection` | Equals | `["assign","alice"]` |
| `org.memory.tasks.due` | Greater than | `2026-08-31` |
| `org.memory.tasks.due` | Less than | `2026-10-01` |

The kernel combines facts at the same `(source path, source version, item key)`.
Neither domain plugin imports or calls the other. The benchmark asserts that no
residual plugin callback is present and both producer namespaces are required.
These are literal handle connections; the benchmark does not claim to exercise
person-page identity resolution.

## Fixture and method

The opt-in desktop test writes 100 tasks per Markdown document, first 10,000 tasks
in 100 documents, then grows the same vault to 50,000 tasks in 500 documents.
Reconciliation and the actual plugin callbacks build the index from these files;
no synthetic SQL facts are injected. Setup takes 4.28 seconds initially and
13.21 seconds for the additional 40,000 tasks, excluded from query timings.

Each cycle of 20 tasks contains seven qualifying Alice tasks and three qualifying
Bob tasks. Other cases include completed tasks, dates outside the month, missing
dates, missing assignees, an Alice owner, and Alice as reviewer of Bob's task.
One qualifying task uses continuation-line fields. Every page also has an Alice
assignment in frontmatter, which must not become assignment on its tasks.
Full enumeration asserts the exact task identities, uniqueness and boundaries;
the due-sort run additionally verifies ascending due dates across pages.

First-page measurements are medians of five warm requests. DSL compilation is
measured separately in seven batches of 200 compilations after warmup, with the
operator/type registry already constructed. Page timings include FFI and result
decoding, exact counts and index readiness checks, but exclude UI rendering and
DSL compilation. Enumeration is one traversal at 500 tasks per page and includes
its result validation. This is a repeatable synthetic workload, not a production
vault trace or device latency guarantee.

## Measured results

Default search ordering, individual task results (`groupByNote=false`):

| Indexed tasks | Matching Alice/month tasks | First 50 | First 500 | All matches at 500/page |
| ---: | ---: | ---: | ---: | ---: |
| 10,000 | 3,500 | 138.08 ms | 140.37 ms | 991.49 ms / 7 pages |
| 50,000 | 17,500 | 1,355.12 ms | 1,376.45 ms | 47,443.66 ms / 35 pages |

DSL compilation takes 0.117 ms at the first stage and 0.097 ms at the second.
It does not explain the execution latency.

With due-date ordering (the same DSL, with the public `sortPredicate` request
field set to the Tasks-provided due predicate):

| Indexed tasks | First 500, due ascending | All Alice/month matches |
| ---: | ---: | ---: |
| 10,000 | 262.66 ms | 1,563.87 ms |
| 50,000 | 1,670.39 ms | 56,181.26 ms |

The [raw desktop output](cross-plugin-search-2026-09-06.txt) includes every DSL
query, compiled request, sample range and fixture setup measurement. The desktop
benchmark passed all assertions against release DLL SHA-256
`27913BADAAEA0E7694B2315272BE29ED25DF1FA1906F4C98C32599732AA51FA2`.

Stepwise first-50 queries show the cost of adding constraints:

| DSL (month bounds as above) | Matches at 50k | First 50 at 10k | First 50 at 50k |
| --- | ---: | ---: | ---: |
| `type:task` | 50,000 | 73.60 ms | 371.12 ms |
| `type:task is:open` | 42,500 | 83.74 ms | 442.94 ms |
| `type:task is:open people.connection:assign:@alice` | 25,000 | 89.84 ms | 502.09 ms |
| Above plus month bounds | 17,500 | 138.08 ms | 1,355.12 ms |
| Open, Alice or Bob, month bounds | 25,000 | 152.55 ms | 1,040.48 ms |

More selective output is not necessarily faster: materialized fact sets and
their intersection have costs before page construction. The Alice/Bob result
must not be interpreted as a universal advantage for membership over equality.

## SQL execution and index structure

The [exact SQL, bound values and execution plan](cross-plugin-sql-profile-2026-09-06.txt)
come from the production item-query composer against the actual 50,000-task
fixture, opened read-only. A separate release-native diagnostic measured:

| Operation | Warm median, five samples |
| --- | ---: |
| Compose SQL and parameters | 0.016 ms |
| Execute selection SQL and consume all candidate keys | 755.72 ms |
| Native item service, including selection and result construction | 867.68 ms |

The last two are separate executions, not additive phases. This diagnostic
excludes the Store entry path and FFI and uses a separate read connection/process
after the desktop run. Its 868 ms must not replace the measured desktop 1,355 ms
or turn their difference into a claimed FFI cost; that difference has not been
isolated by same-process instrumentation. The native diagnostic asserts zero
Markdown source-file reads. SQLite reads and temporary-table work still occur.

The plan proves that the index is used:

- Status equality and People pair equality use `facts_predicate_value` with
  `(predicate=? AND value=?)`.
- Both date conditions use the same index with `(predicate=? AND value>?)` or
  `(predicate=? AND value<?)`. ISO dates are compared lexically; this query uses
  neither `instr` nor `gilune_fold`.
- Namespace/source receipts and source maps use their primary-key indexes.

However, the current SQL materializes five separate fact sets. The type's
Exists constraint seeds every task, then status, assignment and the two date
sets are intersected using the source-qualified item tuple. The two month
bounds are separate broad scans, not one bounded interval. The plan builds
temporary B-trees for DISTINCT and ORDER BY. Selection emits all 17,500 keys,
even when the caller only wants 50. The statement records 7,574,562 VM steps,
207,995 full-scan steps (including intermediate-set scans), and one sort.

After selection, `search/items.rs` loads matching documents' layouts and text
from SQLite, reconstructs matching task text, computes ranking and retains a
bounded heap for the requested page. Due ordering additionally loads those
documents' facts before page selection. Returned items then receive their facts.
Each subsequent page repeats selection, counting and ranking; the current cursor
filters ranks in Rust rather than seeking directly to a bounded SQL page.

This establishes two useful improvement areas without changing plugin ownership:

1. Improve the generic fact-query planner: combine compatible date bounds on
   the same predicate/locality, eliminate implied Exists constraints, compare
   selective candidate seeds, and measure covering indexes against their storage
   and write cost. Preserve same-item correlation and negation semantics.
2. Apply bounded ordered selection before text/fact construction, with generation-
   bound continuation and separately requested/reused counts. Extend the shared
   ordering work to source-qualified items and validate it with these actual DSL
   queries. Root-lookup results alone are not acceptance evidence for item search.

These are proposed next experiments, not implemented optimizations. Keep both
the fully selective query and broad type/status queries as performance fixtures;
also vary tasks per document, sparse matches, nesting, sorting and updates.

Validation: the desktop benchmark, read-only native diagnostic, four existing
item-search regression tests and kernel test-target Clippy passed. No production
API/schema/binary or plugin dependency was changed.

## Reproduction

From `app`, using the existing release kernel:

```powershell
$env:GILUNE_CROSS_PLUGIN_BENCHMARK = '1'
.\gradlew.bat :composeApp:desktopTest --tests 'org.gilune.app.CrossPluginSearchBenchmarkTest' '-Pgilune.kernel.profile=release' --offline
```

The test prints the temporary fixture path and retains it for read-only SQL
profiling. Ordinary test runs skip this benchmark unless explicitly opted in.

The companion ignored Rust diagnostic opens that index read-only, uses the exact
five clauses above, prints generated SQL and `EXPLAIN QUERY PLAN`, and separately
measures SQL composition, SQL selection and the native item service. It does not
re-index or mutate the fixture.

```powershell
# From kernel; substitute the path printed by the desktop benchmark.
$env:GILUNE_CROSS_PLUGIN_INDEX = '<fixture>/.gilune/local/index.db'
cargo test -p gilune-kernel --release profile_real_cross_plugin_item_query -- --ignored --nocapture
```
