# Windows artifacts built on Linux

Implemented as an explicit candidate workflow, `windows-cross.yml`, on 11 September
2026. The existing `build.yml` release route is retained until a disposable Windows
runner proves installation, upgrade from v1.0.3, downgrade rejection and uninstall.
No candidate is published as a release, and ordinary pushes do not run this workflow.

## Build boundary

Linux produces the whisper-enabled MSVC DLL, generates bindings from that exact
DLL, compiles the Windows JVM application and desktop tests, compiles all Windows
Rust unit/integration tests, builds the Windows launcher, and assembles the MSI.
Windows downloads the artifacts and runs the suites and installer lifecycle checks.
The Windows validation job does not compile the application or Rust tests.

Gradle's `gilune.desktop.target=windows-x64` selects the Windows Skiko runtime
regardless of the host. `gilune.desktop.bindings` selects the generated source for
that DLL. Packaging rejects inconsistent version, target or bindings hashes. The
payload manifest records the source commit and every shipped file's SHA-256.
Rust test executables carry a manifest with their crate working directories and
hashes; runtime overrides relocate the test CLI and fixture paths to Windows.

The standalone Rust launcher starts only `runtime/bin/javaw.exe`, with the existing
memory flags, explicit resources directory, application version and classpath.
It uses Windows Unicode arguments and waits for the JVM's exit status. LLVM embeds
the app icon and file version. It needs no additional runtime-detection dependency.
The MSI retains upgrade family `c077b799-5193-4ce3-b93a-411c67e29e37`, a per-machine
Program Files install and the Gilune Start menu group. Java's license files remain
in the bundled runtime.

## Toolchain and cache requirements

- Ubuntu 24.04, LLVM/Clang/lld 19, CMake, Ninja, wixl 0.103, msitools and python3-pefile.
- Rust 1.98.0 and target `x86_64-pc-windows-msvc`.
- cargo-xwin 0.23.1, downloaded with a pinned SHA-256.
- Microsoft CRT manifest ID `14.44.17.14` (header build `14.44.35220`), SDK `10.0.26100`.
- Windows Temurin JRE `17.0.20.1+1`, downloaded with a pinned SHA-256.

`setup-windows-cross.sh` installs user-space tools after the system prerequisites.
The SDK cache key includes the native build recipe. Compiled Rust dependencies
use a separate cross-target cache. The upstream Whisper build script does not
track all forwarded CMake environment settings, so a recipe change explicitly
invalidates that C++ dependency. The kernel links its CRT statically and disables
build-machine CPU specialization (`GGML_NATIVE=OFF`).

The tool's CRT selector takes the manifest ID, not the header version: a populated
cache hid an invalid initial selector. Validation therefore includes fetching the
pinned SDK/CRT into an empty cache. A warm local compile alone is insufficient.

## Efficiency recheck, 3 October 2026

The GitHub cache API returned no saved caches on this date. The first resumed run
must therefore be budgeted as cold. Cache entries from a review branch are not
available to subsequent release tags; entries from the default branch are shared.
This is a [GitHub cache scope restriction](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching#restrictions-for-accessing-a-cache),
so a successful candidate run is not evidence that a later release will be warm.

Re-reading [run 34386963569](https://github.com/Gilune/gilune/actions/runs/34386963569)
confirmed 22m 02s Windows, 22m 58s Linux and 40m 08s Android, plus seven seconds
for routing: **85m 15s summed job time**, not elapsed time or a billing figure.
Android failed in this reference run. Its native build took 12m 02s; Windows kernel
tests, including compilation, took 10m 51s. These remain substantial costs that
cannot be claimed away by moving compilation to Linux.

The local recheck corrected these avoidable costs before another hosted run:

- Desktop testing receives the same version as packaging. Previously a release
  changed generated `AppVersion.kt` after testing and recompiled the application.
- Desktop and Android release builds request only the FFI library, avoiding unused
  release binding-generator executables (including one for each Android ABI).
  The host binding generator and all runtime suites remain in the workflow.
- Android restores its exact native-output cache before Rust/libclang setup.
  A hit skips those tools and compilation. Its Cargo cache stores downloads only:
  temporary native build trees are removed to make room for the emulator, before
  the cache action saves. Android SDK/NDK packages remain available for APK tasks.
- The Windows candidate uses separate host and cross target directories. Its
  recipe stamp is cached beside the cross target, because
  [rust-cache cleanup](https://github.com/Swatinem/rust-cache/blob/v2/src/cleanup.ts)
  removes loose files inside target directories. Previously cleanup would remove
  the stamp and force Whisper's C++ rebuild after a warm restore.
- The main workflow has explicit job deadlines, and its path filters include the
  Windows launcher and standalone test project. Rust commands enforce the lockfile.

Validation: both workflows pass actionlint; both changed build scripts pass Bash
syntax checks; all 20 Python CI-script checks pass. The new regression exercises
cold compilation, simulated cache cleanup/restoration, changed recipes and failed
compilation without marking a failed recipe reusable. Windows process-control
checks required running outside the local sandbox. No hosted build was dispatched.

The next hosted validation should use the existing Windows-only candidate input
below. Its installer lifecycle result still gates replacing the normal Windows
release job. Compare summed job time on that cold run and a later necessary warm
run before assigning a savings percentage; this audit does not establish one.

## Installer upgrade correction, 3 October 2026

The first resumed [candidate run 37124376830](https://github.com/Gilune/gilune/actions/runs/37124376830)
at `0500323` built the Windows payload on Linux, passed all 605 active kernel tests
and 706 desktop cases (five skipped), and passed clean installation, repair, launch
and uninstall. Upgrade from v1.0.3 failed exact payload verification at `Gilune.exe`.

The upgrade log marked 68 components ineligible because a higher-versioned key file
already existed. The old product was then removed, leaving those files missing.
Reading the MSI database reproduced the metadata defect locally: `Gilune.exe`,
`javaw.exe` and `jvm.dll` had empty `File.Version` entries despite embedded versions.
The [wixl 0.103 implementation](https://github.com/GNOME/msitools/blob/v0.103/tools/wixl/builder.vala)
uses `DefaultVersion` directly; it does not extract PE file versions. Windows
Installer [prefers versioned files to unversioned files](https://learn.microsoft.com/en-us/windows/win32/msi/file-versioning-rules).

Packaging now reads each EXE/DLL's numeric fixed file version with `pefile` and
writes it into WiX `DefaultVersion`. It preserves the runtime's own version and
leaves genuinely unversioned files unversioned. Malformed binaries fail packaging.
No file-overwrite policy or installer assertion was relaxed.

Local Linux validation passed all 21 CI-script tests. The new regression compiles
small PE binaries and builds an MSI, then checks real `File` and `MsiFileHash`
rows, including a distinct product version, a versioned DLL and an unversioned DLL.
The candidate workflow requires that regression after installing its tools, so it
cannot silently skip on the packaging runner. Repacking the full existing payload
also verified all 366 MSI file rows, including 99 versioned binaries. Evidence is
under `app/build/installer-upgrade/`. A hosted upgrade of the corrected package
still supplies the final lifecycle gate.

## Local evidence

All application artifacts below were built in an isolated Ubuntu WSL environment
(`GiluneBuild`). Candidate version `1.0.7` is a test stamp; no tag was created.

| Check | Result |
| --- | --- |
| Full whisper-enabled Windows DLL built on Linux | Passed |
| Bindings generated on Linux from that DLL | Passed; matches existing Windows surface |
| Windows JVM application and desktop tests compiled on Linux | Passed |
| Exported desktop suite executed on Windows | 706 cases, 5 skipped, 0 failures/errors |
| All 28 exported Windows Rust test executables | 605 passed, 24 ignored, 0 failed |
| Packaged DLL's real Whisper inference | Existing local base-q5_1 model loaded; generated silence processed successfully |
| MSI generated by Linux wixl and administratively extracted on Windows | Passed; all 365 payload file hashes matched |
| Extracted app launched from a path with spaces and Unicode | Rendered its window, opened an isolated Unicode vault, closed normally |
| Windows launcher icon/version resource | File and product version 1.0.7 verified |
| Pinned SDK/CRT fetched into an empty cache | Passed |
| Workflow lint, 19 script tests and launcher Clippy | Passed |

Evidence is under ignored `app/build/linux-ci/`: `verified-desktop.log`,
`results/verified-kernel/results.json`, `verified-transcription.log`,
`verified-msi-extract.log` and `results/verified-launch/launcher.txt`.
The final local MSI is
`app/build/linux-ci/verified-candidate/package/Gilune-1.0.7.msi`.

The 28-executable suite preserves the current unit/integration coverage. Rust
documentation tests are not exported; the current workspace has no active doc
test cases. Revisit this boundary if Windows-specific documentation tests appear.

## Problems caught before CI

The current Microsoft STL requires Clang 19; Ubuntu's default Clang 18 failed.
Whisper's build script also applies some Windows flags based on the host, and
its CMake runtime selection must agree with Rust's static CRT selection.

Launch4j 3.50 built an executable, but quoted `JAVA_TOOL_OPTIONS` made its version
parser read a JVM setting as a version and crash inside the native launcher.
Its shipped `head_src/head.c` function `getVersionFromOutput` selects the first
quoted text from `java -version`, including Java's options announcement. The Rust
launcher passed the exact failing setup and removes that parser entirely.

## Remaining release gate

Administrative extraction proves the MSI payload, not Windows Installer's product
registration or upgrade transaction. `test-windows-installer.ps1` only runs on a
disposable Windows Actions runner and refuses a runner already containing Gilune.
It checks clean install, exact payload and shortcut, repair, application launch, uninstall,
upgrade from the existing v1.0.3 MSI, rejection of a downgrade, a second uninstall,
and preservation of the smoke-test vaults. The first hosted run reached the upgrade
payload failure described above; the corrected package must pass the whole sequence.

The candidate was pushed as `adf3bdf`. The explicit
[candidate run 34608408313](https://github.com/Gilune/gilune/actions/runs/34608408313)
selected the new route and skipped the normal Windows/Linux/Android/release jobs,
but GitHub refused to start its Linux job with the existing account
billing/spending-limit restriction. It ran zero build steps. The accompanying
[quick branch run](https://github.com/Gilune/gilune/actions/runs/34608389358) was
blocked for the same reason. No billing settings were changed.

Run **Windows build on Linux** explicitly at a candidate version greater than
1.0.3 when Actions allowance is available. Before this new workflow reaches the
default branch, the existing **Build** workflow can call it from the review branch:

```sh
gh workflow run build.yml --ref ci/reliability-review \
  -f windows_cross_only=true -f version=1.0.7
```

That input bypasses the normal platform/release jobs, so validating the candidate
does not also spend an Android/Linux/full-release run. Review both jobs' results and summed
runner seconds before replacing the old Windows job in `build.yml`. Keep release
gates and bindings parity dependent on the Windows validation result. No paid
runner or spending-limit change is required or proposed.

Moving compilation should reduce Windows minutes, but this candidate retains the
full Windows runtime suites. It does not claim the earlier six-minute Windows
target, and local timings are not hosted billing measurements. Measure a cold
build, a warm app-only build and a release before accepting a savings figure.
