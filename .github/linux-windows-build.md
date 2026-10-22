# Windows artifacts built on Linux

## Native Windows install, 22 October 2026

The owner, testing 1.1.0 on Windows 11, reported three things a native app does
not do: Start search showed no logo and the context menu no "Run as
administrator"; switching sync on raised the firewall prompt for "OpenJDK
Platform binary" (Eclipse Adoptium); and Task Manager listed Gilune twice. All
three had one cause each.

- **Advertised shortcut.** wixl 0.103 writes the owning feature as the Shortcut
  target when none is given; the installed `.lnk` resolved through
  `C:\WINDOWS\Installer\{ProductCode}\GiluneIcon`. The shortcut now carries
  `Target="[#<file id>]"`, and the Icon id is `GiluneIcon.ico` so the file the
  Installer extracts has an extension the shell reads. (`Advertise="no"` is the
  WiX default; wixl only logs "unimplemented" for the attribute, so it is omitted.)
- **Firewall rule at install time.** The installer runs as the system, so the
  rule the user used to be prompted for is added by it: `netsh advfirewall
  firewall add rule name="Gilune" dir=in action=allow enable=yes profile=private
  program="[INSTALLDIR]Gilune.exe"`. wixl builds EXE custom actions from a
  property (type 50) but not from a directory, so an immediate type 51 action sets
  `GILUNE_NETSH` to `[System64Folder]netsh.exe` before `InstallInitialize`; the
  deferred, non-impersonated delete/rollback/add actions follow `InstallFiles`.
  Delete-then-add on every install, repair and upgrade (netsh duplicates by
  name); the deletes also clear every rule for `[INSTALLDIR]Gilune.exe` and
  `[INSTALLDIR]runtime\bin\javaw.exe` by program (`delete rule name=all
  program=...`), because the owner's notebook held Block rows for javaw.exe from
  a dismissed 1.1.0 prompt and a Block beats any Allow — the install owns every
  rule for its programs from here on; delete on uninstall; a product being replaced leaves the rule alone
  (`NOT UPGRADINGPRODUCTCODE`) so the order of `RemoveExistingProducts` cannot
  remove what the newer product added; `Return="ignore"` throughout, so a
  firewall that will not take a rule returns the user to the prompt rather than
  failing the install. Private profile only, per ADR 0019 § 2 (amended).
  **First hosted attempt (run 39764223871) failed with error 2613,
  "RemoveExistingProducts action sequenced incorrectly"**: wixl gives that action
  no sequence number of its own and places it after whatever its dependency sort
  put last, which had been `InstallFiles` (4001) and became the new custom
  actions (4006). The package now schedules it explicitly
  `After="InstallInitialize" Before="ProcessComponents"` (1501), a documented
  slot inside the transaction, so a failed upgrade rolls the old product back;
  both bounds are needed because wixl's sort only moves an action where a
  dependency forces it. The real-MSI regression asserts the position.
- **One process.** `Gilune.exe` loads `runtime\bin\server\jvm.dll` and creates
  the VM in-process instead of spawning `javaw.exe`: `runtime\bin` goes on the
  DLL search path (`SetDllDirectoryW`) and on `PATH` (the runtime derives
  `java.library.path` from it, where `jawt` and friends are found by name);
  options are the ones the old command line carried, plus `java.class.path`
  listing `lib\*.jar` in name order because that property takes no wildcard;
  `org.gilune.app.MainKt.main` runs on a 1 MiB thread as the Java launcher
  arranges it; an uncaught exception exits 1; the `MessageBoxW` path remains for
  everything before the VM is up. Raw JNI declarations checked against JDK 17's
  `jni.h`; no new dependencies. The updater's install script used to wait for the
  launcher's parent as well; it now waits for a parent only when that parent is a
  `Gilune.exe`.

Local evidence on the owner's machine (Windows 11, Rust 1.98, VS 2019 Build
Tools): all 25 CI-script tests pass in the `GiluneBuild` WSL distro, including the
real-MSI regression, which now reads the Shortcut, Icon, CustomAction and
InstallExecuteSequence rows (types 51 / 3186 / 3442; order InstallInitialize <
InstallFiles < remove < rollback < add < InstallFinalize; conditions as above).
The launcher crate is Clippy-clean under `-D warnings`. The new launcher, built
natively and dropped into a copy of the installed 1.1.0 at
`C:\Program Files\Gilune`, passed `test-windows-launcher.ps1`: window rendered,
isolated Unicode vault opened, normal close with exit 0, no `java`/`javaw`
process beneath it, and the window owned by `Gilune.exe` itself, in about 2 s.
Desktop tests `UpdatesTest` and `DesktopLaunchArgumentsTest` pass against
bindings regenerated from the current kernel.

What this machine cannot prove: the installer lifecycle refuses to run anywhere
but a disposable Windows Actions runner, Windows Sandbox is not enabled here, and
the owner's own 1.1.0 install must not be replaced by a test. So
`windows-cross.yml` gained an opt-in `validate_installer` dispatch input (default
false; never set by `workflow_call`, so pushes and releases start no Windows
runner) that runs `test-windows-installer.ps1` on `windows-2025` against the
freshly built MSI and the published v1.0.3. The lifecycle now also asserts the
`.lnk`'s `TargetPath` is `C:\Program Files\Gilune\Gilune.exe` with an `.ico`
icon, exactly one `Gilune` firewall rule after install and after repair
(Inbound/Allow/Private/enabled, program = the launcher) with no other rule for
any program under the install directory — Block rows for the lower-cased
javaw.exe path are seeded with netsh before the clean install and again before
the upgrade, as a dismissed prompt leaves them — none after uninstall,
and — by starting the installed app a second time with sync switched on, the
device id read from the vault's `kernel.db` and `<id>.sync.json` written beside
the identity — that the sync listener is owned by `Gilune.exe`. Run it with:

```sh
gh workflow run windows-cross.yml --ref <branch> -f version=1.1.1 -f validate_installer=true
```

The run's `windows-installer-results` artifact holds `lifecycle.txt`,
`listener.txt` and every msiexec log. Three hosted runs, all from this branch
with version stamp 1.1.1 (no release published):

| Run | Linux build | Windows lifecycle | Outcome |
| --- | --- | --- | --- |
| [39764223871](https://github.com/Gilune/gilune/actions/runs/39764223871) | 33m 25s (cold caches) | 39s | Clean install failed 1603: error 2613, RemoveExistingProducts sequenced after the custom actions (fixed above) |
| [39769146019](https://github.com/Gilune/gilune/actions/runs/39769146019) | 17m 09s | 1m 00s | Clean install over seeded Block rules, repair and first launch passed; the test then looked up the port's owner after closing the app (test bug, fixed) |
| [39771626630](https://github.com/Gilune/gilune/actions/runs/39771626630) | 4m 10s (warm) | 1m 26s | **Passed** the whole lifecycle |

`lifecycle.txt` from the passing run: clean install over seeded Block rules, plain
shortcut, one firewall rule and no Block left, repair without a duplicate rule,
one-process launch owning the sync port, upgrade from v1.0.3 over seeded Block
rules, downgrade rejection, uninstall removing every rule, preserved vaults. Both
`listener.txt` files name `C:\Program Files\Gilune\Gilune.exe` (process name
`Gilune`) as the owner of port 51837. Since the seeded Block rows carried the
lower-cased javaw.exe path and none survived, netsh's `program=` match is
case-insensitive, as expected. The Linux build durations are runner time, not
billing; the Windows job is billed at twice its minutes. Code signing is
unchanged: any remaining "Publisher: Unknown" needs a certificate and is out of
scope. What only the owner's eye can confirm: the Gilune logo in Start search and
"Run as administrator" in the shortcut's context menu, after installing the
candidate MSI from a run's `gilune-windows-msi` artifact.

## Current pipeline: Linux runners only

As of 3 October 2026, every hosted job in `build.yml` and `windows-cross.yml` runs
on Ubuntu. The normal release pipeline calls `windows-cross.yml` to produce its
Windows MSI; the original Windows build job and the candidate's Windows validation
job have both been removed. This replaces the earlier migration plan that kept
Windows runtime validation mandatory.

Linux produces separate packages for the currently supported targets: a Windows
x64 MSI, a Linux x64 DEB, and Android APKs containing arm64-v8a and x86_64 libraries.
These are platform-specific packages built on one host OS, not a single universal
installer. macOS packaging is not configured by these workflows.

The Linux kernel/desktop suites, Clippy and Android emulator suite remain release
gates. Windows packaging verifies native versions using a real MSI, enforces the
application/native bindings hash and emits the same `gilune-windows-msi` and
`bindings-desktop-windows` artifacts consumed by the existing release and bindings
parity jobs. Manual full builds without a version use `1.0.0` consistently across
all packages; tagged releases use their tag version. Ordinary branch pushes still
run Linux checks without building all installers.

Windows-specific execution, GUI and installer lifecycle checks are no longer
automatic. The successful `c17cec5` run below is evidence for that exact package,
not a guarantee for future changes. Future Windows runtime validation must be
requested separately. The diagnostic scripts remain available; native Windows
test export is explicitly enabled with `GILUNE_WINDOWS_BUILD_TESTS=1`. Production
builds omit those unused test executables, test JARs and the large validation bundle.

Local validation: all 23 CI-script tests pass, both workflows pass actionlint, and
the regression suite checks that every hosted runner is Linux, exercises the actual
version-selection shell step and verifies that Windows test compilation is opt-in.
[Run 37144108109](https://github.com/Gilune/gilune/actions/runs/37144108109) at
`94d08f7` passed. GitHub reports exactly one executed job, labelled `ubuntu-24.04`,
and the expected `gilune-windows-msi` and `bindings-desktop-windows` artifacts.
It consumed **22m 56s of Linux runner time and zero Windows runner time**. The
native/tool cache keys changed with the build recipe and missed; Gradle restored
its cache and application compilation took 21s. These are first-run measurements
for the new route, not a measured warm-build or full-release timing. The focused
run did not repeat Linux/Android runtime suites or publish a release. This change
is on `ci/reliability-review`; the default branch adopts it when that work is merged.

## Earlier Windows validation evidence

The following records describe the migration and verification runs before the
Windows jobs were removed. Their Windows durations are historical costs, not jobs
that remain in the current pipeline.

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
checks required running outside the local sandbox. This local audit preceded the
hosted runs recorded below.

Hosted validation used the existing Windows-only candidate input below, avoiding
a duplicate full-platform run. The comparisons below separate cold compilation,
warm compilation and runtime execution; they do not establish release billing savings.

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
under `app/build/installer-upgrade/`.

[Run 37131500573](https://github.com/Gilune/gilune/actions/runs/37131500573), at
`4d9a1e6`, confirmed that clean installation, repair, upgrade and both application
launches now pass. Kernel and desktop results remained 605 passed/24 ignored and
706 cases/five skipped, with no test failures. It then caught a separate failure:
the published v1.0.3 MSI permits downgrades and removed the candidate successfully.
Its log identifies the incoming product as `{AAE0D767-8197-38BD-8519-19579879B9FE}`.

The candidate now refuses removal when Windows Installer's
[`UPGRADINGPRODUCTCODE`](https://learn.microsoft.com/en-us/windows/win32/msi/upgradingproductcode)
identifies that legacy MSI. This uses an early launch condition in the installed
package; the incoming v1.0.3 package's downgrade policy cannot be retroactively
changed. Normal uninstall has no such property, and future products have different
codes. This compatibility guard covers the listed published product, while
`MajorUpgrade` continues to reject installing the new package over newer versions.
The real-MSI regression verifies that the guard is present and runs before
`InstallInitialize`, including during removal. Installer checks now precede the
long runtime suites so a packaging failure stops earlier without weakening the
successful candidate's test gates.

The second run restored Rust, cross-tool and Gradle caches. Compilation/packaging
fell from 19m 25s to **4m 39s**: native compilation from 14m 42s to 1m 31s, and
application/test compilation from 2m 34s to 29s. Windows validation took 18m 04s,
including 13m 54s of kernel execution; the two jobs totalled **22m 43s**. These
compare a cold run with a warm packaging-only revision, not an app-source change
or a complete release. They are runner durations, not billing figures, and both
runs still failed a lifecycle gate.

[Run 37136906844](https://github.com/Gilune/gilune/actions/runs/37136906844), at
`c17cec5`, passed both jobs. The complete hosted installer lifecycle passed in 56s,
including rejection of the original v1.0.3 MSI's downgrade attempt, exact payload
verification afterward, final uninstall and preserved vaults. The downgrade log
confirms that the new launch condition stopped removal before any files changed.
All 28 Rust executables completed: 605 tests passed, 24 ignored, none failed.
Desktop reports contain 706 cases, five skipped, zero failures and zero errors.

| Summed job duration | Cold `0500323` | Warm `4d9a1e6` | Warm `c17cec5` |
| --- | --- | --- | --- |
| Linux compilation/packaging | 19m 25s | 4m 39s | 4m 28s |
| Windows runtime/installer checks | 14m 47s | 18m 04s | 24m 02s |
| Both jobs | 34m 12s | 22m 43s | 28m 30s |
| Outcome | Upgrade failed | Downgrade failed | Passed |

The final kernel execution took 16m 25s and desktop execution 4m 48s. Runtime
variation between these runs is substantial; caches reduce compilation but do
not remove those tests. The earlier failures also stopped installer validation
before its final steps, so these totals are not equivalent successful release
benchmarks. The current Linux-only integration preserves the artifact/bindings
gates, but these historical measurements do not establish its full release cost.
Downloaded evidence is under ignored `app/build/installer-final-validation/`,
with the job metadata and complete log in `app/build/installer-final-run.*`.

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

## Hosted installer gate

Administrative extraction proves the MSI payload, not Windows Installer's product
registration or upgrade transaction. `test-windows-installer.ps1` only runs on a
disposable Windows Actions runner and refuses a runner already containing Gilune.
It checks clean install, exact payload and shortcut, repair, application launch, uninstall,
upgrade from the existing v1.0.3 MSI, rejection of a downgrade, a second uninstall,
and preservation of the smoke-test vaults. After correcting the version metadata
and legacy downgrade behavior, `c17cec5` passed the entire sequence.

The candidate was pushed as `adf3bdf`. The explicit
[candidate run 34608408313](https://github.com/Gilune/gilune/actions/runs/34608408313)
selected the new route and skipped the normal Windows/Linux/Android/release jobs,
but GitHub refused to start its Linux job with the existing account
billing/spending-limit restriction. It ran zero build steps. The accompanying
[quick branch run](https://github.com/Gilune/gilune/actions/runs/34608389358) was
blocked for the same reason. No billing settings were changed.

Run **Windows build on Linux** explicitly with a numeric MSI version when
validating packaging changes. Before this new workflow reaches the
default branch, the existing **Build** workflow can call it from the review branch:

```sh
gh workflow run build.yml --ref ci/reliability-review \
  -f windows_cross_only=true -f version=1.0.7
```

That input now runs only Linux compilation, packaging and MSI metadata checks.
It bypasses the normal Linux/Android runtime and release jobs, so it is a focused
packaging check rather than a full release validation. No Windows runner starts,
and no release is published. The normal full pipeline retains its Linux/Android
test gates, bindings parity and required-artifact checks.

The current workflow consumes zero hosted Windows minutes. Measure its complete
Linux release path before assigning a total savings percentage; the historical
cold and warm candidate timings above included Windows jobs and different coverage.
