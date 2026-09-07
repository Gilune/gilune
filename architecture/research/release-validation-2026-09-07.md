# Cross-platform release validation

Date: 2026-09-07. Follow-up to the incremental indexing delivery at
`4b3f96e8590b4d24d9373816085971cc9ebb3a31`.

The first remote build exposed portability failures that the local Windows
checks and Android compilation had not caught. This work fixes those failures
and adds Android execution to the existing release gate. It does not change
the index schema, query executor, plugin contracts or performance results.

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
