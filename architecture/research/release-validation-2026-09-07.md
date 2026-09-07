# Cross-platform release validation

Date: 2026-09-07. Follow-up to the incremental indexing delivery at
`4b3f96e8590b4d24d9373816085971cc9ebb3a31`.

The first remote build exposed portability failures that the local Windows
checks and Android compilation had not caught. This work fixes those failures
and adds Android execution to the existing release gate. It does not change
the index schema, query executor or plugin callback contracts. The follow-up
below also corrects detection of explicitly reported file edits.

## Failures and fixes

- Linux deleted temporary vaults because several tests discarded their
  `TempDir` guard immediately. Windows open-file semantics had masked this.
  Tests now retain the guard until their store and callbacks finish.
- Linux treated Windows drive paths as relative paths. Projected-path
  validation now rejects drive prefixes on every platform, with absolute,
  drive-relative and UNC regression cases.
- The Linux Android build script generated bindings into the old shell
  directory. It now writes into `kernelBindings`, matching module ownership
  and the Windows build script. These fixes landed in `3bde1f8`.
- Android app-private storage rejected hard links used to publish new vault
  pages. Captures remained preserved, but projections, typed-page creation
  and dependent UI flows failed. Linux and Android now publish the fully
  written temporary file using `renameat2` with `NOREPLACE`, via rustix's safe
  API. A competing destination is never overwritten. The parent directory is
  still synced; Windows retains its existing hard-link publication.

The filesystem helper reports collision separately from I/O failure. Tests
cover preserving an externally created destination and eight simultaneous
publishers producing exactly one complete file. Unsupported filesystem
operations fail visibly; there is no overwrite or partial-copy fallback.

## Local evidence

Android used the existing Pixel 6 API 36 Google APIs x86_64 AVD in a temporary
read-only session, with both shipped native ABIs rebuilt and staged. The
instrumented suite ran through Gradle against the release native kernel:

| Check | Result |
| --- | --- |
| First full Android runtime suite | 873 tests, 39 failures, 12 skipped |
| Full suite after publication fix | 873 tests, **0 failures**, 12 skipped; 861 passed |
| Fixed suite duration | 157.462 s test time; 2 min 55 s Gradle elapsed |
| Native filesystem, page and vault regressions | 85 passed |
| Earlier portable-path/search/scoped regressions | 46 passed |
| Release workspace Clippy, all targets, warnings denied | Passed |
| ARM64 and x86_64 Android native builds; application and test APK assembly | Passed |

The XML report is authoritative for the count; Gradle's console summary counts
skips twice in its final "Finished" line. All 39 original failures disappeared
without weakening assertions or excluding additional tests.

Local raw logs are under ignored `app/build/release-*`; the initial failure
details are preserved in `release-android-before.json`. CI retains the Android
XML and HTML reports as the `android-test-results` artifact, including failures.

## Continuous gate and limits

The existing Android build job now runs the full instrumented suite on an
API 36 Google APIs x86_64 emulator after staging the release native libraries.
It uses a pinned [Android Emulator Runner revision](https://github.com/ReactiveCircus/android-emulator-runner/tree/a421e43855164a8197daf9d8d40fe71c6996bb0d)
and enables KVM using the action's documented Linux setup. Its failure blocks
the job and the existing dependent release job. Compilation alone is no longer
accepted as Android runtime evidence.

This is not physical ARM64 execution, an installed MSI/DEB smoke test or speech
accuracy evidence. The 12 skipped Android tests require opt-in speech inputs.
The broader People editor/navigation and shared refactoring milestones remain
open; these release corrections do not mark them complete.

## Minimum-version follow-up

An additional Pixel 6 x86_64 emulator run on Android 10 (API 29, the declared
minimum) found eight failures. Two used Java's `Path.of`, absent on that OS;
the shared tests now use `Paths.get`. An agenda test waited for an already
visible neighbouring row rather than the asynchronous removal it asserted;
it now waits for that removal. The editor test drove a real InputConnection
while the system keyboard independently selected a composing word; it now
uses the existing exclusive input-session rule, retaining real IME protocol
calls and undo assertions.

Three failures were reproductions of a historical Android 16 LatinIME bug,
documented for that exact environment in [the MS1 report](../../spikes/ms1-ime/README.md).
Their requirement is now explicitly API 36. The portable composing/commit
invariants and platform EditText control still run on API 29. These three
diagnostics are filtered from API 29 discovery, not counted as passing there.

The remaining failure exposed a production bug: rapid equal-length saves
could keep the same mtime, so the reconciliation stat cache suppressed their
change events. Explicit one-file reconciliation now reads the named file;
unchanged content still emits nothing. The native fixture covers managed
projections and import candidates with deliberately identical size/mtime,
checking the resulting Modified event and a no-op repeat. Restoring the old
stat shortcut makes that fixture fail; restoring the fix makes it pass.
Background scans retain their existing stat cache and verification policy.

After these corrections, the full applicable API 29 suite reports **870 tests,
858 passed, 12 speech-input skips, zero failures**, in 143.576 seconds of test
time (2 min 57 s Gradle elapsed). Both native ABIs were rebuilt. The 54 native
vault tests and release workspace Clippy also pass. Local before/after logs
are `release-android-runtime-api29*.log` and `release-same-stamp-before/after.log`.

The first hosted emulator attempt in [run 34099027332](https://github.com/Gilune/gilune/actions/runs/34099027332)
failed while unpacking the system image because the runner had no disk space;
both desktop jobs passed. The Android job now removes its temporary cross-build
trees and cleans the host Cargo build after staging libraries and bindings,
before installing the emulator image. Later steps consume only staged outputs.
The required runtime gate remains enabled; its successful hosted execution is
required before this release-validation milestone is complete.

The next hosted attempt, [run 34101699315](https://github.com/Gilune/gilune/actions/runs/34101699315),
passed both desktop jobs (593 Linux native tests, plus Windows native and both
desktop suites) but exposed the second part of emulator capacity: after image
installation, 3.5 GB remained while its default data partition required 7.4 GB
of host space. The emulator exited before tests and its runner waited for boot.
CI now explicitly sizes guest data at 3 GB, removes the unused preinstalled
.NET SDK from that disposable Android runner, and bounds boot waiting at five
minutes. The local API 36 run uses about 1.7 GB of guest data during the suite;
the smaller partition retains headroom while avoiding the default's allocation.

With those capacity changes, [run 34104640030](https://github.com/Gilune/gilune/actions/runs/34104640030)
booted the emulator and completed 873 tests in 706.761 seconds: 858 passed,
12 speech-input skips and three failures confined to the historical MS1
keyboard-bug reproductions. There were no application-invariant failures.
API level alone was therefore insufficient to scope those measurements. They
now opt in using `gilune.ime.legacyDiagnostics=true`, retaining their original
assertions and permitting comparison on any OS/keyboard. The default gate
continues to run the portable IME protocol, modern editor/undo and platform
EditText control tests. See [the diagnostic command](../../spikes/ms1-ime/README.md).

The final application code also passed a fresh local API 36 run (861 passed,
12 skipped, zero failures; 175.273 seconds), and the rebuilt/staged Windows
release library passed 635 desktop tests with five skips. Source-query
contracts and shipped application behaviour are unchanged by diagnostic gating.

The scoped local verification runs the same two IME suites in both modes:
default mode reports 34 tests, 31 passed and three explicit diagnostic skips;
enabling the argument reports all 34 passed with no skips. The original
diagnostic assertions are therefore still executable and checked on their
measured local keyboard environment. Logs are `release-diagnostics-default.log`
and `release-diagnostics-enabled.log` under `app/build/`.
