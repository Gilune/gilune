# CI reliability review — 9 September 2026

The strongest evidence points to uncontrolled UI timing, inconsistent test
resource ownership, and unresolved rendering/runtime failures. Hosted-runner
variation exposes those weaknesses. The evidence does not support attributing
all failures to GitHub infrastructure, or treating every failure as one bug.

This is an investigation report. No application, test or workflow fixes were
made during this review. The previous build-routing changes remain local and
unpublished.

## What failed most recently

The [v1.0.6 release run](https://github.com/Gilune/gilune/actions/runs/34240439739)
tested commit `09e3de34b3bc550d6741c25e62d1eadbd303eca0`.

| Platform | Evidence | Meaning |
| --- | --- | --- |
| Windows | Kernel suite, desktop suite and MSI packaging passed. | This platform passed at this commit. |
| Linux | Kernel suite and Clippy passed. Desktop: 702 tests, one failure, five skips. | A UI test failed; DEB packaging never ran. |
| Android | Native cross-compilation, app APK and test APK passed. Instrumentation reported 310/898 tests, one failure, then a process crash. | A runtime crash prevented the remaining 588 tests from executing. |

The [matching main run](https://github.com/Gilune/gilune/actions/runs/34240439637)
was cancelled about 102 seconds after creation. It supplies no completed
same-commit comparison. The duplicate-build change is not on GitHub yet.

### Linux: readiness did not remain true until the click

`PeopleEditorUiTest.theRealEditorKeepsFrontmatterContextAndTranslatesBodyInsertion`
failed in `EditorCompletionAwait.chooseFirstEditorOption`, at
`assertHasClickAction()`: **no node tagged `completion-0` existed**.

The helper had already returned from waiting for the labelled first option.
This is stronger evidence than simply "the runner was slow": the observed
menu became unavailable between the readiness check and the action. The new
failure also occurs with the unmerged-tree selector, so the previous diagnosis
of a merged-label lookup problem was incomplete.

There are concrete candidate transitions in the application:

- `NoteEditor` autosaves after 1,200 ms of coroutine time and calls `noteSaved`.
- Save reconciliation advances `vaultCursor`, a key of the completion query.
- `rememberQueryWork` replaces its result with loading state on a key change;
  `rememberEditorCompletions` then replaces the displayed offers.
- Losing editor focus also dismisses the menu; the desktop uses a native popup.

The UI test advances its frame clock while real IO runs independently. Thus a
short wall-clock test can still encounter a debounce/refresh transition. These
are hypotheses for the disappearing menu, not a demonstrated causal sequence:
the failed run did not record focus, query generation or dismissal reasons.
Repeating the click or increasing its timeout would not establish which path
is responsible, or whether a user can lose the same menu.

### Android: drawing crashed before the checkbox action

`MobileFilterBlockTest.aCheckboxCompletesTheSourceTaskWithoutLeavingTheOverview`
encountered `IllegalArgumentException` from `SnapshotStateObserver.observeReads`:
the observer detected another thread (`previousThreadId=60`) while the main
thread drew the Compose view.

The retained thread dump places the test at `MobileFilterBlockTest.kt:47`,
waiting for the **initial** "mend the gate" row. The checkbox click is line 48.
Consequently this run does not establish that checkbox saving caused the crash.
The dump shows the instrumentation thread waiting for a main-thread test-clock
advance, but does not map Java thread ID 60 to the earlier offending access.
We cannot yet assign responsibility to application code, the test framework or
a Compose implementation bug.

Both the Android slash-menu test and the People lookup test fixed for v1.0.6
passed before this crash. The instrumented action failed after roughly 11m37s,
well inside its 40-minute limit. Increasing that limit again would be irrelevant.

## Why the suite is fragile

### Synchronisation handles individual symptoms, not complete operations

The tests use real kernel queries and hard-coded IO dispatchers. There is no
registered application idling resource or shared controllable query scheduler
in `composeApp/src`. UI readiness checks are separate observations of a changing
tree. They do not establish that pending save/index/query work has settled, or
that the option still belongs to the same query when clicked.

Compose's [synchronisation guidance](https://developer.android.com/develop/ui/compose/testing/synchronization)
distinguishes its own clock/idleness from external asynchronous work and
provides idling resources for that work. We need explicit ownership and
observable completion of finite operations; registering an endless background
poller as busy would itself make the suite hang.

### Test resource ownership is inconsistent

`MobileFilterBlockTest.blockVault` constructs an `IndexedVault` and a
`CoroutineScope(Dispatchers.Default)` without retaining either for teardown.
`IndexedVault` opens a native `Gilune` handle and temporary directory but has no
close contract. Similar fixture patterns appear in mobile Agenda and desktop
filter tests. Cleanup therefore depends partly on garbage collection, and
application jobs have no explicit per-test cancellation boundary.

Other fixtures do cancel scopes and close handles; the lifecycle test uses a
main-thread scope. However, cancellation without awaiting running work also
deserves inspection before closing a native handle it may still use.

These are observed ownership weaknesses and credible sources of resource or
test-order sensitivity. No measured leak, out-of-memory event or causal link
from these fixtures to the latest crash has been established.

### The rendering findings remain open

Earlier evidence includes an Android `SlotWriter` failure during activity
destruction, and local desktop native drawing hangs in `_nDrawPicture` and
`_nDrawRect`. A hung drawing thread can prevent an in-test timeout from running.
The docking problem also affected real application use, although its relationship
to these test failures remains unknown.

The locally resolved Android test graph uses Compose UI/runtime/test **1.7.6**,
Espresso **3.7.0**, and test runner/core **1.7.0**. The latter are upgraded
transitively from the lower versions written in the build file. Desktop declares
Compose Multiplatform 1.7.3. There is no obvious Android Compose runtime/test
version mismatch in that graph. It is local dependency evidence, not a captured
dependency graph from the failed runner.

A [JetBrains issue with the same exception](https://github.com/JetBrains/compose-multiplatform/issues/4737)
involves desktop semantics access and a different version. It supports examining
the framework boundary, but neither proves our Android cause nor identifies a
verified upgrade fix. Any upgrade should be a controlled comparison.

### The pipeline amplifies the cost of uncertainty

The same release candidate has been built twice for main and tag. Environment
inputs include moving `*-latest` runners, Rust `stable`, Java patch updates and
Android emulator/system-image revisions. The earlier v1.0.4 timeout demonstrated
substantial execution-time variation; the latest failure does not demonstrate
an infrastructure outage or that duplicate jobs caused a threading violation.

The Android suite shares an instrumentation process: one crash ends the run and
removes coverage of later cases. Lengthy native compilation precedes discovering
UI failures. The workflow presently validates main and `ci/**` pushes rather
than automatically validating pull requests before merge.

My previous approach was too focused on the failing assertion, followed by local
passes and another release attempt. The v1.0.5 Android race reproduced locally
and was fixed, but the Linux disappearance shows the helper was not a complete
solution. One successful full local run is useful evidence, not a reliability
claim across different scheduling and platform conditions.

## Recommended order of work

1. **Instrument and reproduce the two current failures.** For Linux, record
   focus, popup dismissal, text/caret, query generation and save/index activity
   around the check and click. Deliberately delay queries and trigger refreshes.
   For Android, run the failing class alone and after its preceding classes;
   capture thread IDs, first exception and lifecycle transitions. Compare a
   fresh process with a long-lived process. Preserve failed attempts.
2. **Create one owned UI-test fixture.** Retain the vault, scope and jobs;
   dispose UI through the platform rule, cancel and await application work,
   close native handles, then remove temporary files. Make dispatcher/time
   dependencies injectable where needed. Audit UI-thread access rather than
   assuming every background Compose-state write is itself illegal.
3. **Test asynchronous behaviour deliberately.** Exercise stale results,
   cancellation, autosave refresh, focus changes and disposal in controlled
   tests; retain real UI/keyboard/pointer integration coverage. Assert both
   stable operation state and exact resulting document data. Do not turn a
   disappearing control into an automatic click retry.
4. **Isolate and diagnose runtime failures.** Add an external watchdog that
   captures desktop stacks before terminating a hung worker. Evaluate bounded
   Android shards or process isolation while retaining lifecycle/session tests.
   Compare a coherent supported Compose stack against the current versions
   with the same scenarios; do not claim an upgrade fixed a race after one pass.
5. **Improve the release gate.** Publish the prepared deduplication/path filters,
   record resolved tool/runtime versions and resource measurements, and use
   `ci/**` validation before another tag. Put fast deterministic checks ahead
   of expensive packaging while keeping cross-platform runtime checks mandatory.
   Use a defined set of repeat/stress runs as diagnostic evidence, with every
   failure reported; do not retry failed release gates into green.

Exit evidence should include a demonstrated failing sequence, a change that
prevents that sequence, complete Linux/Windows/Android results on the candidate,
and a declared repetition count with zero hidden retries. Failures that remain
unreproduced must remain explicitly open.

## Evidence retained locally

`app/build/ci-review-v106/` contains the failed-run console log, Linux XML/HTML
reports, Android XML/HTML and per-test logcat/thread dumps, and the offline
Android test dependency graph. These are ignored build outputs. Earlier run
links, reproduction results and unresolved findings are in
[ci-stability.md](ci-stability.md). This review executed dependency resolution,
not another application test run.
