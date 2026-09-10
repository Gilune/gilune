# Intermittent build failures — 7 September 2026

## CI allowance follow-up (10 September 2026)

The organisation is on GitHub Free; September's reported Actions net amount was
zero. The account error also occurs when the free allowance is exhausted without
a payment method. Paying is not the intended remedy. Run 34386963569 consumed
about 22 Windows, 23 Linux and 40 Android runner minutes (85 total), even though
the jobs overlapped. Windows kernel tests took 11 minutes; Android native
cross-compilation took 12 minutes and instrumented testing/setup took 19.5.

Ordinary pushes now run Linux kernel tests, Clippy and desktop tests only.
Windows, Android, packaging and bindings parity remain mandatory for tags and
manual full-validation runs. Atomic branch/tag routing and path filters remain.
Based on that prior run, excluding the other platforms and DEB packaging would
reduce routine usage to approximately 20 runner minutes (about 75% less). This
is an estimate, not a measured run of the new workflow. Platform-only regressions
are caught by full validation, not by the reduced branch checks.

Android caches the finished libraries for both ABIs together with their generated
bindings. Exact source/toolchain keys exclude `kernel/target`, and no prefix
fallback is allowed. The cache is saved before app tests, avoiding recompilation
after unrelated failures while still running the suites afresh. On a hit, the
temporary cross-build directories do not exist, so disk cleanup handles that
case. GitHub's branch scope still applies: distinct release tags cannot directly
share caches; manual full validation on main can seed a default-branch cache.
No extra full runs are scheduled merely to populate caches.

Local verification: all 15 workflow-script tests passed, including real Git
branch/tag/manual routing and the watchdog process ownership test; actionlint
passed. The native-output shell guard accepted complete fixtures and rejected
each of five missing and each of five empty outputs. The initial sandboxed
watchdog exercise timed out; with permission to inspect/stop its own child
processes the unchanged test passed. No application code changed in this update.
Hosted quick/full execution and cold/warm cache timings remain unverified while
the allowance restriction prevents jobs from starting. A green quick branch run
will not satisfy the outstanding full-validation requirement below.

## Current validation status

The implemented candidate is `280fca85b5029fb9d62d116c0fe30d09c04d6383` on
`ci/reliability-review`, in [draft PR #13](https://github.com/Gilune/gilune/pull/13).
Its full local Android suite passed 905 cases with zero failures/errors and 15
existing skips. The preceding candidate passed complete Windows and Linux suites
(706 cases each, five existing skips), Clippy, kernel tests, MSI and DEB packaging.
The follow-up changes only Android instrumentation sources and documentation.
Three final independent repetitions of the affected Android scenarios passed
46 cases each, zero failures/errors, with two existing skips per run (132
executed checks across the three runs). Each attempt retained its own XML and
log under `standard-repeat-*`; all invoked instrumentation, and the loop stopped
on any failure. This includes another 90 notice activity destruction cycles.

[Run 34394193212](https://github.com/Gilune/gilune/actions/runs/34394193212)
**did not start any build step**. GitHub's Build plan annotation reports that
recent account payments failed or the spending limit needs to be increased, and
directs the owner to Billing & plans. This is an account restriction; it is not
a test failure and the workflow cannot fix it. The annotation is preserved in
`app/build/ci-reliability/280-job-start-annotations.json`.

Keep the PR in draft until the account restriction is resolved and a full hosted
run validates this candidate, including Android and bindings parity. Do not mark
the incomplete prior Android run as passing, merge this as a verified release,
or publish another release tag based solely on the local pass. The new scheduling
probe establishes a fixed thread violation; it does not prove the captured
SlotWriter or historical native rendering failures have been eliminated.

## Android effect scheduling follow-up (9 September 2026)

[Candidate 8fa5cc0](https://github.com/Gilune/gilune/actions/runs/34386963569)
passed both desktop jobs: 706 cases each, zero failures/errors and five existing
skips, with fresh execution and successful MSI/DEB packaging. Both Ubuntu jobs
passed the restricted package setup. Android shard 1 crashed while destroying
the activity after `ProviderNoticeUiTest.aStandingNoticeIsDrawnAndGoesAwayWhenItIsDismissed`:
`SlotWriter.moveSlotGapTo` threw `ArrayIndexOutOfBoundsException` (length 1280,
index -596). Shards 0 and 2 passed 294 and 316 cases; shard 1 reported only 167
of its expected 293. This is a failed, incomplete Android gate, not 903 passing
tests. Reports and Java thread stacks are under `8fa-android-results`.

The unchanged notice class passed locally, as did a new 30-cycle notice dismissal
and activity destruction test. The full corresponding local shard (294 cases,
including the new lifecycle test) also passed. These attempts did not reproduce
the hosted slot-table crash. Published runtime 1.8.3 has identical `SlotTable.kt`
and `Composition.kt` to 1.8.2; there is no basis for claiming that patch fixes it.

Source inspection found another concrete test-environment boundary: the old
Compose test factories default to `UnconfinedTestDispatcher`. A background
completion can resume a composition effect immediately on the producer thread.
`EffectThreadTest` reproduced this: expected Android UI thread 2, actual worker
102 (101 in a second unchanged-factory diagnostic attempt). The test uses a
background-completed deferred value; no sleeps or probabilistic click retries.
Its failing XML is retained in `effect-thread-baseline-results`.

Android UI tests now share factories supplying `StandardTestDispatcher` to the
existing supported `effectContext` argument. Compose's Android main clock pumps
that scheduler on the UI thread. The same effect-thread probe passes, as do
the live semantics probe and the 30-cycle notice lifecycle scenario. This follows
Google's [test dispatcher guidance](https://developer.android.com/develop/ui/compose/testing/migrate-v2?hl=en)
and its [standard-dispatcher lint rationale](https://android.googlesource.com/superprojects/androidx/+/c396d28dff194e399f1b9703b9aeb02a244ffbd6).
It does not require another production framework upgrade.

The first queued-dispatcher check exposed a notice-readiness assertion: the test
checked the banner before DataStore's asynchronous result arrived. It now waits
for that actual banner before asserting/clicking. Its scope and temporary vault
also use owned cleanup. The initial five-case result (four pass, one fail) is
preserved in `standard-dispatcher-initial-results`. The demonstrated effect-thread
violation is fixed; the causal link to the historical slot-table and drawing
crashes remains a hypothesis requiring further evidence.

The first full migration run exposed five more early assertions (filter rows,
loaded shell content, and laid-out caption text), then stalled in Sync's
`performScrollTo`. It was stopped after capturing a SIGQUIT thread dump and
bugreport; its partial XML reports 687 cases, six failures including that deliberate
process termination, and 14 skips. It is not a complete suite result. Files are
under `standard-dispatcher-full-initial-results` and `standard-dispatcher-stall*`.
The stack is in `ActionsKt.performScrollTo`; this version's helper repeatedly
checks its target while the queued `ScrollBy` work has not run. The test now
issues one semantic scroll, advances the main clock, waits for visibility and
clicks once. Exact assertions remain. Shell and Sync fixtures now own cleanup.

The next focused run passed those checks but exposed one early click on the
composer's dismiss button before its focus-driven visibility update (46 cases,
one failure, two existing skips). The button is now awaited before one click.
Reports are retained in `standard-dispatcher-focused-initial-results`. Caption
visibility and loaded note/feed checks await the result they assert, rather than
assuming a single idle call finishes real IO and every layout transition.

The corrected full local Android suite then passed **905 unique tests**, zero
failures/errors and 15 existing skips, in 4m 8s including Gradle/deployment.
This includes the thread probe, both 30-recreation scenarios and the formerly
stalled Sync case. XML and console output are retained under
`standard-dispatcher-final-full*`. Hosted validation is still required; a local
pass does not establish that every historical rendering crash is eliminated.

## Unrelated runner package repository failure (9 September 2026)

[Validation run 34385921107](https://github.com/Gilune/gilune/actions/runs/34385921107)
failed before compilation on both Ubuntu jobs: Google's Chrome package index
had a `Hash Sum mismatch`. Both downloaded identical inconsistent metadata,
on two different revisions of the Ubuntu 24.04 runner image. This was a shared
external package setup failure, not a failing app test. The preceding run had
completed all 903 Android tests and Windows packaging successfully.

The Ubuntu dependency steps now select only the runner's `ubuntu.sources`
for both update and install, using APT's documented `Dir::Etc::SourceList` and
`Dir::Etc::SourceParts` options. Package signatures and hashes remain enforced;
`APT::Update::Error-Mode=any` makes a required source failure fatal rather than
continuing with old indexes. No retry or ignored error was added. The source
file must exist, so a changed runner layout fails explicitly. See the
[Ubuntu APT documentation](https://manpages.ubuntu.com/manpages/noble/man8/apt-get.8.html).

## Reliability work following v1.0.6 (9 September 2026)

See [the review](ci-fragility-review.md) for the v1.0.6 Linux missing-row failure
and Android `SnapshotStateObserver` process crash. The latter occurred before
the checkbox click, and remains an unresolved cause unless reproduced.

A controlled `CompletionRefreshUiTest` now holds query work pending while a
refresh starts. On the previous implementation, the test failed because
`completion-0` disappeared. The fixed implementation keeps the previous rows
only for the same context/kernel/plugin set while replacement results load.
Changed text clears them, and a callback captured before an edit cannot insert
into the new context. Native acceptance validation remains mandatory. This
establishes and fixes one disappearing-menu sequence, not proof that every
historical Linux failure took that sequence.

A follow-up geometry assertion exposed another part of that sequence: adding
the loading notice above retained rows moved the first option down 20 pixels
(top 6 to 26). Loading text is now shown only when there are no retained options,
so the same-query refresh preserves both the row and its pointer position.
The failed bounds assertion is retained under `refresh-position-baseline*`.

Affected People, editor, filter and Agenda UI fixtures now use an outer owned
resource rule. It waits for application jobs to finish cancellation before
closing native vault handles, after Compose disposes its UI. Tests cover that
order and preservation of original failures when cleanup also fails.

Android diagnostics now record Java thread IDs, the current test and exception
stacks. Desktop CI captures owned JVM stacks before terminating a timed-out
test process. The watchdog was exercised against a disposable real JVM and
captured `WatchdogProbe.main` before returning deadline failure; its tests also
check preservation of exit codes and isolation from an unrelated process.

Android CI uses three distinct instrumentation shards, with no retries and
per-shard reports. A failing shard still fails the run. A script test verifies
that later shards execute and earlier failure reports survive. This limits
the coverage lost to a process crash; it is not a fix for a rendering defect.

Local evidence is retained in `app/build/ci-reliability/`: the unchanged People
desktop class passed, and the unchanged Android editor/filter sequence passed
19 tests. After the changes, 18 focused desktop and 43 focused Android tests
passed, including 30 activity recreations within the lifecycle test. The full
Windows suite passed (701 passed test events; the existing five skips), and
Android's full unsharded XML reported 900 tests, zero failures/errors and 15
skips in 286.447 seconds. An initial five-run People/completion loop produced
passing reports, but inspection found runs 2–5 restored `desktopTest FROM-CACHE`.
Those are not independent executions and the proposed 35/35 repetition count
is withdrawn. A first upgrade repetition loop had the same problem after its
first run. Replacement repetitions disable the build cache, and CI JVM test
tasks now disable result caching and up-to-date reuse while retaining cached
compilation. Full-suite logs were checked separately: those tasks executed.
Three replacement upgrade repetitions then executed 21/21 tests successfully
(`uncached-upgrade-repeat-1` through `-3`, no cached or up-to-date test tasks).
A further seven-case run without cleaning or disabling the global cache
confirmed the CI policy: Gradle logged `CI must execute its runtime tests`,
`Task.upToDateWhen is false` and a new test worker, then passed all seven.

### A concrete framework threading difference

Comparing Google's published [1.7.6 UI-test sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test/1.7.6/ui-test-1.7.6-sources.jar)
and [1.8.2 UI-test sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test/1.8.2/ui-test-1.8.2-sources.jar)
identified a relevant change in `TestOwner` and `SemanticsNodeInteraction`:
1.7.6 collects nodes on the UI thread, then applies selectors on the calling
test thread. In 1.8.2, the selection runs inside the UI-thread operation too.
Live node properties can participate in Compose observation, so this is a
concrete cross-thread boundary to remove, rather than a general claim that
newer libraries must fix rendering.

`SemanticsThreadTest.selectorsInspectLiveNodesOnTheUiThread` fails on the existing
1.7.6 stack: expected UI thread **2**, actual selector thread **58**. Its baseline
log and XML are retained under `semantics-baseline*`. This demonstrates the
threading behaviour, not the original process crash itself.

The comparison candidate aligns Compose Multiplatform and Android UI tests at
1.8.2, whose [published dependency mapping](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.8.2)
uses Jetpack UI/runtime/foundation 1.8.2. Kotlin remains 2.1.20. The same thread
probe, affected UI scenarios and complete suites are used to evaluate it.

The upgraded thread probe and 43 affected Android cases passed. The first full
upgrade checks exposed two other concrete problems, with failed XML retained:

- Desktop undo's test polled `readText()` while the native refactoring protocol
  temporarily moved the old file before publishing its replacement. It threw
  `FileNotFoundException`, rather than observing operation completion. Both
  platforms now wait for the dialog's completed-operation control before the
  unchanged exact-file assertion. The corrected full Windows suite passed
  706 tests with zero failures and five existing skips.
- Android's prototype sent source offset 16 to `InputConnection.setSelection`.
  Foundation 1.8.2's `ImeEditCommandScope.setSelection` maps displayed offsets
  back to the source; 16 now selects inside `milk`. The test now sends displayed
  offset 12, asserts source caret 16, then keeps the original assertion that
  backspace removes only `]`. This is a deterministic API behaviour change,
  not a flaky test to retry. The corrected prototype and People classes passed
  all nine tests. The first full Android upgrade run finished 901 cases with
  this single failure and 15 skips, without a process crash.

The corrected full Android run then completed all 901 unique tests with zero
failures/errors and the existing 15 skips (5m 36s including Gradle/deployment).
Its console and XML are retained under `compose-upgrade-android-final*`.
Clippy also passed locally with warnings denied. These results verify the
changes on the local Windows/API 36 environments; they do not establish that
the unreproduced historical `SnapshotStateObserver` crash or Skia hangs have
been eliminated on every hosted runner.

The initial hosted candidate `b9f43c5`
([run 34377064269](https://github.com/Gilune/gilune/actions/runs/34377064269))
also caught a configuration omission introduced by this work: Linux's pinned
minimal Rust installation lacked Clippy. The Linux setup now explicitly
installs and checks Clippy before compilation. Rust dependency caches are saved
even when a later test fails, and owned `ci/**` branches can save Gradle caches;
neither cache changes test failure outcomes.

The completed baseline's Windows suite passed 706 tests (five skips) and built
the MSI. Android's three shards covered all 900 unique cases (293/291/316),
with one failure and 15 skips; the later two shards passed without hiding the
first shard's failure. The slash-menu test timed out waiting for **Due date**.
The new trace shows source caret 34 before the toolbar click, then caret 24
after it, with two offers published. With this fixture's 23-character frontmatter,
the slash had been inserted at body offset zero instead of 11.

`EditorToolbar` captured text and selection during composition. A callback
invoked after a buffer edit but before its next frame could apply the previous
snapshot. `ToolbarFreshStateTest` reproduces this even on Compose 1.8.2: a
captured slash callback inserts at the old caret, and a captured panel callback
overwrites the new text with the old text. Both tests failed before the fix.
Toolbar and panel commands now calculate edits from the live buffer when the
action fires. This is an application correction, not an increased timeout or
an assertion that waits for the wrong state to disappear.
The two regressions plus the real mobile editor class passed all 15 tests.
The final menu-position change also passed the full 706-test Windows suite
(five existing skips) and 24 focused Android cases. The expanded 903-case
Android suite and the replacement hosted candidate provide the final gate.
The final full local Android run completed all **903 unique tests**, with zero
failures/errors and 15 existing skips, in 5m 11s including Gradle/deployment
(`toolbar-android-full*`).
After the final production changes, three uncached desktop People/completion
runs passed **21/21 executions**, including the bounds assertion, and three
Android toolbar/editor runs passed **45/45 executions**. Each attempt has its
own log and XML (`final-desktop-repeat-*`, `android-toolbar-repeat-*`); both
loops were configured to stop on the first failure. These are the final-code
repetition counts, separate from the earlier upgrade comparison.

The baseline device log also records system-service ANRs during shard 0,
including System UI, the phone service, Google Play services and the keyboard.
At 16:48:44, System UI reported load 20.67 and CPU pressure `some avg10=85.94`;
this is direct evidence of emulator resource contention, not proof that
contention caused the historical Compose threading exception. It can expose
timing windows, while the stale toolbar callback remains independently
reproducible on the local emulator. Keep the complete device logs when judging
future timeouts rather than describing every failed run as the same flake.

### Follow-up from the upgraded hosted candidate

[Run 34381419268](https://github.com/Gilune/gilune/actions/runs/34381419268)
at `d625d37` passed Windows tests and MSI packaging. Linux passed Clippy and
completed 706 desktop cases with one failure and five skips. The failure was
`TasksPanelTest.thePluginPutsAThirdTabInTheRailAndItOpensTheAgenda`: it asserted
`today (1)` immediately after `waitForIdle()`, while the Agenda query runs on IO.

Holding that query with `LocalQueryDispatcher` reproduced the exact missing-node
failure locally. The test now asserts the loading state, releases the query,
waits for its result and then checks the heading. Agenda and task-editor fixtures
also use owned resources. An audit of the other desktop `waitForIdle` usages
found negative suggestion checks making the same assumption; they now wait for
the query's loading notice to disappear before asserting that no menu is offered.
Existing async save/sync checks already wait for their operation's result.
The complete local desktop suite passed after both corrections (706 cases,
zero failures, five existing skips). Failed and corrected logs are under
`agenda-held-*`, `agenda-desktop-full*` and `query-audit-desktop-full*`.
Three uncached repetitions of Agenda, task-editor and completion tests then
passed **93/93 executions** (`query-audit-repeat-1` through `-3`).
The toolbar regression's action capture also reads the live semantics
configuration on the UI thread; both toolbar cases and the thread probe passed
after that test-only adjustment (`toolbar-thread-fixed.log`).

The upgraded hosted Android job subsequently passed and produced its APK.
Its three preserved shards reported **294/293/316 tests**, totalling **903 unique
cases**, zero failures/errors and 15 existing skips. The original slash-menu
failure and the new toolbar/thread regressions passed in that full hosted run.
Reports are retained under `d625-android-results`; Windows reports are under
`d625-windows-results`. Only the reproduced desktop Agenda assertion prevented
that candidate's overall build from succeeding.

## v1.0.5: Android suggestion test was not waiting for its option

[Main 34231892380](https://github.com/Gilune/gilune/actions/runs/34231892380)
and [v1.0.5 34231892162](https://github.com/Gilune/gilune/actions/runs/34231892162)
both passed Windows and Linux and completed Android's 898-test suite with one
failure and 15 skips. Both failed
`MobileEditorUiTest.theSlashMenuOffersCandidatesAboveTheRowAndWritesARealDate`
at the attempt to tap **Due date**, with no matching text node. These were test
failures, not another timeout (792.249 and 968.450 seconds of test execution).

The test waited only for `editor-completions`, which is rendered during
**Finding suggestions…** before IO publishes an option. This was the Android
counterpart of the readiness pattern already fixed on desktop. The previous
local validation compiled the Android APKs but did not execute this flow;
compilation could not catch the runtime race.

The unchanged test reproduced the exact CI failure on the first local API 36
emulator run (10.876 seconds). After changing the wait, it passed on the same
emulator (5.732 seconds). Logs: `app/build/ci-v105-android-baseline.log` and
`app/build/ci-v105-android-fixed.log` (local build outputs).

`EditorCompletionAwait` now lives in `jvmSharedTest`, shared by desktop and
Android. Both platforms wait for the labelled first option and the absence of
the loading notice, then tap the tagged interactive row. Android Tasks and
People completion tests use this same helper. Exact token, frontmatter and
saved-file assertions remain; there are no retry-on-failure or skip changes.

Validation after the fix: 17 related desktop tests passed. The full local
`:composeApp:connectedDebugAndroidTest` run on API 36 passed all 898 tests
with zero failures/errors and the existing 15 skips (287.670 seconds of test
execution, 5m 11s including Gradle/deployment). The corrected slash-menu test
therefore passed both alone and in the full suite. Reports are under
`app/composeApp/build/outputs/androidTest-results/connected/debug`; the console
log is `app/build/ci-v105-android-full.log`. This verifies the specific test fix;
the previously documented native rendering failures are separate open findings.

## v1.0.4: two different failures at the same commit

Both runs used `0c00051856f67e08d0f99a9d5a9bd58c8049631e`:

- [Main 34220708320](https://github.com/Gilune/gilune/actions/runs/34220708320):
  Windows and Android passed. Linux completed 702 tests with one failure and
  five skips. `PeopleEditorUiTest.theRealEditorKeepsFrontmatterContextAndTranslatesBodyInsertion`
  found Alice Smith while waiting, then failed to locate that label in merged
  semantics when injecting the click. The failure explicitly reports the label
  still present in the unmerged tree. This was an assertion failure, not the
  previously observed native render hang.
- [Release 34220707390](https://github.com/Gilune/gilune/actions/runs/34220707390):
  Windows and Linux passed. The APK compiled successfully. The Android test
  action expired at its 20-minute limit, with its last progress update reporting
  476/898 tests completed, six skipped and zero failed. Per-test logcat continued
  to show tests starting and finishing until cancellation; it was not stuck on
  one test. The final recorded test even finished during cancellation cleanup.

The release Android action began at 11:43:49 UTC and tests began at 11:49:55,
so setup/preparation consumed about six minutes. The corresponding successful
main action took 19 minutes overall, with all 898 tests completing in 811.694
seconds (15 skipped). Across 440 matched completed tests with start/finish logs,
release execution totalled 642.254 seconds versus 515.454 on main, about 25%
longer. This supports an insufficient step budget under variable runner load;
it does not identify the host-level cause of the speed difference.

The People completion tests now wait for the labelled first suggestion to be
ready, then click its tagged interactive row in the unmerged tree. Exact token
and frontmatter-preservation assertions remain. Android compiles its test APK
before starting the emulator and allows 40 minutes for the full instrumented
step, retaining its five-minute boot limit and always-uploaded diagnostics.
No tests are skipped, assertions weakened, or automatic retries introduced.

Local validation: all 17 People/Tasks/list-continuation desktop tests passed,
followed by three independent runs of all five People-editor tests (15/15),
and both Android APKs build with the revised preparation command. Hosted Linux
and the longer Android budget still require verification in the next CI run.

## Desktop: completion tests raced background work

[Main run 34156277391](https://github.com/Gilune/gilune/actions/runs/34156277391)
failed two Linux UI tests at commit `347b85f`, while the
[v1.0.3 run](https://github.com/Gilune/gilune/actions/runs/34156277429)
passed at the same commit:

- `ListContinuationUiTest.theSuggestMenuKeepsItsEnterAndWritesADateRatherThanACheckbox`
- `TasksEditorUiTest.enterTwiceWritesAnIsoDateTheUserNeverSpelledOut`

`waitForIdle()` only established Compose idleness. Suggestions and acceptance
validation run on `Dispatchers.IO`, and a popup can exist while loading its next
stage. The tests now wait for the first option at each stage and for acceptance
to change the buffer before asserting its exact contents. They retain the
original assertions; there are no retries or fixed sleeps.

The workflow now retains XML and HTML desktop test reports on failure as well
as success, and Gradle prints full exception details.

## Android: activity destruction crash remains unresolved

[v1.0.2 run 34143245722](https://github.com/Gilune/gilune/actions/runs/34143245722)
built its APK, then crashed during instrumentation. Its
[main counterpart](https://github.com/Gilune/gilune/actions/runs/34126173220)
passed at the same commit, `e66b9fe`.

The failed test was
`MobileAgendaTest.anAgendaWhoseTasksAreAllDoneOrFarOffSaysSoInstead`.
The recorded exception was `ArrayIndexOutOfBoundsException: length=320; index=-71`,
from Compose's `SlotWriter.moveSlotGapTo`, reached through
`CompositionImpl.dispose` while Android destroyed the activity. The process
crash stopped the suite after 257 of 893 tests. This is not an APK compilation
failure, and the stack alone does not establish its underlying cause.

`AgendaLifecycleTest` exercises 30 activity recreations around the loading and
empty Agenda states. It has passed on the existing application code; it is
additional coverage, not a reproducer or proof of a fix. `MobileAgendaTest`
also logs test-body failures before activity cleanup so a destruction failure
cannot hide the earlier exception from the retained CI logcat. Look for the
`GiluneTest` tag if it recurs. Production Compose code and versions are unchanged.

## Local validation

- Windows desktop, release kernel: 672 tests, zero failures, five skipped.
- Both affected desktop test classes: ten repeat runs with software rendering,
  all successful.
- Isolated Android API 36 emulator, release native library: 894 tests including
  the new lifecycle test; zero failures, 15 skipped.
- Final Agenda diagnostics and lifecycle check: all 20 tests passed.

Local success does not establish that the Android crash has been eliminated.
The next occurrence needs its first exception and activity-destruction stack
examined together; do not automatically retry the suite to make it green.

## Desktop renderer hang observed during issue validation (2026-09-08)

`PeopleEditorUiTest.theRealEditorKeepsFrontmatterContextAndTranslatesBodyInsertion`
can hang after choosing a completion. The test worker waits in
`SkikoComposeUiTest.waitUntil` for the AWT thread, which spins in native
`CanvasKt._nDrawPicture`, reached through `RenderNodeLayer.drawLayer`.
Because drawing itself never returns, the test's ten-second wait timeout
cannot fire. This is different from an assertion running before asynchronous
completion insertion finishes.

The same hang occurred with the committed `NoteEditor.kt` restored: two
isolated runs passed, then the third hung with the same stack. Both layouts
also passed other runs. Restoring toolbar controls or giving the header a
minimum height did not establish a fix; those experiments were removed.
The renderer and test remain unchanged, and this failure is unresolved.
Local evidence is in `app/build/issues-focused-threads.txt` and
`app/build/issues-people-baseline-threads.txt` (untracked build outputs).

## Docking and undocking: content stops filling its window (2026-09-08)

The owner reports that docking or undocking can leave Gilune's contents
apparently resized inside the outer window, with unused space. This is a
user-observed rendering problem, not just a test failure. Its relationship to
the native drawing hang above is unknown: those tests did not exercise a
display change, so their results cannot validate docking behaviour.

The desktop uses Compose Multiplatform 1.7.3 and Skiko 0.8.18. `Main.kt`
sets an initial window size but the root Surface and DesktopApp use
`fillMaxSize()`. No application-level density override was found. A missed
display-scale or native rendering-surface update is a hypothesis, not an
established cause.

Reproduce by docking and undocking with Gilune open, including maximised
windows and displays with different Windows scale settings. Record the scale
and resolution on both displays, which transition fails, whether clicks align
with the visible controls, and whether resizing/restoring the window repairs
the content. Compare the native client bounds, renderer bounds and Compose
density before and after the transition. Ordinary offscreen UI tests cannot
substitute for that check. Microsoft's [DPI testing guidance](https://learn.microsoft.com/en-us/windows/apps/develop/win2d/dpi-and-dips)
also recommends changing scale while an app runs and moving between displays
with different DPI settings.

The owner subsequently confirmed that **any manual window resize repairs the
content**, including restoring/maximising. A Windows-only mitigation now watches
the window's display configuration after move, resize, activation and display
change events. On a changed monitor/scale/resolution, it revalidates existing
components, invokes Skiko's public scale/layout/redraw path and repeats the
refresh once after settlement. Normal window movements do not trigger additional
rendering, and closing the window removes the listeners and cancels pending work.
The outer window and editor composition are not recreated or resized.

This follows the [pinned Skiko 0.8.18 implementation](https://github.com/JetBrains/skiko/blob/v0.8.18/skiko/src/awtMain/kotlin/org/jetbrains/skiko/SkiaLayer.awt.kt):
`checkContentScale()` notifies density changes, while `doLayout()` updates the
child canvas and synchronises native renderer bounds. It is a targeted recovery
path, not proof of the original dock-event ordering or a fix for the test hang.

`DisplayChangesTest` injects display-signature changes into the watcher and uses
a real ComposeWindow with a deliberately undersized native canvas, including a
late child-canvas size change. It checks restored canvas bounds, unchanged outer
window bounds, retained draft/cursor state, no composition recreation, no extra
refresh for ordinary moves, and cancellation on disposal. The test does not
physically change Windows DPI or connect/disconnect a dock; confirmation on the
owner's hardware remains outstanding.

During issue #12 validation, an initial test that immediately cancelled a still
loading person form observed the dialog title while moving to another menu.
A diagnostic run waiting for that title to disappear then hung in native
`CanvasKt._nDrawRect`, drawing a Compose scene-layer background. The AWT thread
used 63 CPU seconds in 64 elapsed seconds; the test worker waited in
`SkikoComposeUiTest.waitUntil` at `renderNextFrame`. The local dump is
`app/build/issue-12-threads.txt`. Only that test worker was stopped. This is
additional evidence of native rendering failure, not proof that its cause is
identical to the earlier `_nDrawPicture` hang. Menu-switch checks now dismiss
popups with an outside pointer click; cancellation is separately checked after
the form's asynchronous defaults preparation completes. No renderer change or
automatic retry was introduced for this issue.
