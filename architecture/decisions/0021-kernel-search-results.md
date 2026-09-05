# ADR 0021: Complete Search Results at the Kernel Boundary

Status: **Implemented and validated, 2026-09-05. Build authorised by the owner.**
Amends ADR 0020's candidate-only query path. The existing anchor query remains
available for integrations; application search moves to the service below.

## Problem

The index selects file paths, then the Kotlin shell reads those files and
evaluates, extracts, highlights, and ranks them again. Document searches can
lose matches across lines; transient indexing reads can erase results;
candidate limits truncate before filtering; broad queries block composition.

## Decision

- Plugins compile domain operators to a generic search request. The kernel
  owns document/line matching, negation, ranking, snippets, and pagination.
- Search reads a consistent index snapshot, never Markdown files. Results
  include matching lines, UTF-16 highlight ranges, facts, totals, continuation,
  and index freshness. An incomplete index is distinguishable from no matches.
- Document matching allows terms on different lines. Line matching requires
  them on the same line, with note properties applying to all its lines.
- Uncompiled plugin predicates may run over indexed snapshots through a
  narrow pure callback. They cannot require the shell to enumerate files or
  finish result evaluation. Built-in and task operators compile declaratively.
- Ranking precedes pagination. Continuations are bound to the request and
  index generation; stale continuations must restart rather than mix snapshots.
- Temporary read failures retain prior entries and pending work. Only confirmed
  absence withdraws a file. Concurrent maintenance cannot overwrite a newer
  committed plan with stale rows or regress its cursor.
- Search, agenda, and embedded filters execute away from composition. Obsolete
  requests cannot publish over newer input; failures remain visible.

## Implementation plan and acceptance evidence

- [x] 1. Index recovery: retryable reads, coherent snapshots, concurrent-plan
  checks; regressions for transient locks and independent handles.
- [x] 2. Kernel search: explicit scope, complete filtering and ranking, Unicode
  matching, indexed snippets/facts, bounded pages, freshness and invalidation.
  Regressions include cross-line terms, negative-only queries beyond 10,000
  candidates, same-line task joins, and zero vault reads during search.
- [x] 3. Public boundary and plugins: versioned typed FFI surface; regenerate
  bindings from each target binary; remove the production file evaluator;
  retain the old evaluator only as an independent test oracle.
- [x] 4. Consumers: background search, agenda, and filter blocks, explicit
  loading/error/incomplete states, cancellation-safe publication, pagination.
- [x] 5. Validation: kernel/FFI suites, desktop search equivalence and UI
  regressions, Android build and available instrumented checks, representative
  broad/selective workloads, updated public contracts and evidence here.

## Compatibility and scope

Only the disposable index format changes; user files, operation sync, and the
kernel history fixture chain do not. Plugin domain vocabulary stays outside
the kernel. This work adds no new product domains, template system, hosted
service, or downloaded-plugin runtime.

## Validation results (2026-09-05)

- Rust workspace: **453 passed, zero failed**, two pre-existing ignored tests
  (fixture regeneration and an explicit Windows firewall probe). Multicast
  discovery timed out in the sandbox; the full run passed with network access.
- All **45 index tests** passed again after tightening read-failure cursor
  preservation: a refusal neither advances nor resets the acknowledged prefix.
- `cargo clippy --offline --workspace --all-targets -- -D warnings`: passed.
  Modified Rust sources are formatted; unrelated existing formatting was
  preserved.
- Full desktop suite: **576 passed, zero failed**. Includes the independent
  search oracle, task lines beyond the former 200-line cap, pagination,
  callback errors, and cancellation-safe UI publication. Eight boundary and query UI tests
  also passed against the final release native library.
- Both Android native libraries rebuilt with Whisper. Independently generated
  arm64 and x86_64 bindings compare identically. Desktop debug/release
  bindings also compare identically; the release DLL is staged for packaging.
- Android app and instrumentation APKs build successfully. Instrumentation
  waits were updated for asynchronous results. **Device execution was not
  available: `adb devices` listed no connected device.**
- Optimized kernel, 1,000 notes × 20 nonempty body lines: first page of 50
  broad-search hits **52.3 ms**, selective search **0.88 ms**, unfiltered
  listing **7.2 ms**. The file-read probe stayed at zero. These are local
  observations, not performance thresholds or phone measurements.

The new public contract is [documented here](../../contracts/search/README.md).
The old anchor API remains supported. Full-result traversal still repeats
selection/ranking per page; UI consumers use bounded pages. Native calls
already running finish on their worker even when publication is cancelled.
Only disposable index schema v2 changes; no user-file or history migration is
introduced.
