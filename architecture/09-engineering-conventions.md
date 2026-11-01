# Engineering Conventions

Status: **Accepted** (owner ratification 2026-08-30)

The constitution says what the system must guarantee; the contracts say
where the boundaries are; the ADRs say which technologies. This document
says what the code itself looks like. It binds product code; spike code
keeps its own rule (clarity over polish, boundaries still respected).

## Structure follows the boundaries

- The dependency direction is law: **contracts ← kernel ← everything
  else** (platform adapters, shell, plugins, tools). The kernel names no
  domain type; domain behaviour is plugin territory. Nothing imports
  "upward".
- Extension happens at named seams — a new processor behind the contract,
  a new renderer registration, a new capability adapter — never by
  widening the kernel. A feature that requires editing kernel modules is
  a design smell that must justify itself in review.
- One module, one reason to change. Kernel modules own invariants, not
  features; if a module's doc header needs the word "and", it is usually
  two modules.

## Self-documenting code, sparse comments

- Names carry the meaning. A comment is owed only for what code cannot
  say: an invariant, a platform constraint, a deliberate
  why-this-not-that. Never narrate the next line, never leave
  changelog or authorship comments, never commit commented-out code —
  delete it, git remembers.
- Doc comments (`///`, KDoc) state **contracts** on everything public
  across a boundary: effects, failure modes, idempotency, cancellation
  behaviour. A module starts with 2–5 lines saying why it exists (the
  established house style).
- Test names are specifications:
  `external_edit_becomes_revision_and_projection_canonicalises`, never
  `test_edit_2`. If the name cannot state the behaviour, the test is
  testing the wrong thing.
- `TODO` only with an owner: a linked open question, ADR, or issue.
  British spelling throughout, code identifiers included.

## DRY applies to knowledge, not keystrokes

- Deduplicate **facts**, not shapes. Every invariant has exactly one
  home: the schema, the idempotency inputs, the portable rel-path rule,
  the chunk size. One fact in two places is a corruption bug waiting for
  a divergent edit.
- Two similar-looking functions may be honest coincidence. Abstraction
  must earn its keep: rule of three, and prefer visible duplication over
  the wrong abstraction — a copied block is deletable, a premature
  interface propagates.

## SOLID, translated for this codebase

Applied as boundary discipline, not object-orientation ritual:

- **Single responsibility** → the module rule above.
- **Open/closed** → extend at seams; the kernel changes for invariants,
  not features.
- **Liskov substitution** → contract fidelity: an implementation honours
  its contract's documented *semantics*, not merely its signature —
  handlers are idempotent and at-least-once tolerant, boundary calls are
  cancellation-safe, whichever side of the FFI or WIT they live on.
- **Interface segregation** → narrow seams: hand a component the least
  capability that works. `ProcessorCtx` is the precedent — a scoped read
  grant, never a database connection.
- **Dependency inversion** → the kernel depends on capability traits;
  platform adapters implement them. Rust never calls a platform API
  directly (ADR 0001).

## Rust (kernel, components, tools)

- Typed errors at every public boundary (error enums with meaning per
  variant); `anyhow` only inside binaries and spikes. No
  `unwrap`/`expect` on fallible paths in kernel code: a kernel panic is a
  bug, and the panic path stays diagnosable (hook + report, per S1).
- `unsafe` budget: none outside generated bindings. Any exception
  carries a `// SAFETY:` comment and survives review on its own merits.
- Platform differences live in dedicated helpers or modules
  (`fsync_dir` precedent), never inline `cfg` scattered through logic.
- `rustfmt` defaults; clippy clean with warnings denied in CI.

## Kotlin (shell)

- The shell renders and forwards; it holds no invariants and makes no
  domain decisions. If the shell must *know* something for correctness,
  that knowledge belongs in the kernel or a contract.
- Composables are render + event dispatch; state hoisted; long work in
  structured coroutines whose cancellation is safe across the boundary
  (S1 proves the drop crosses — code may rely on it).
- ktlint defaults.

## Keyboard shortcuts

Owner rule, 2026-10-31: **every keyboard shortcut is reassignable**, unless
a decision explicitly says otherwise for that key. A new key ships with a
default and a setting (a plugin's keys on its settings screen, through the
shared keystroke control; `Keystrokes.kt` is the one spelling for recording
and matching), and the shortcuts window shows the binding as it stands, not
the default. The shell's own keys that predate this rule are not yet
reassignable; they need a shell keymap and are tracked as debt, not as an
exception.

## Desktop footprint

An idle app is measured, not assumed. Gilune's desktop process sat at
504 MB private / 360 MB working set / 101 threads on an empty vault
(2026-09-04), and almost none of it was the app's own data: 80 MB of Java
heap holding 13, **75 MB of G1 bookkeeping** sized from a 16 GB default
maximum, and 59 MB of speech model loaded at vault open for a person who
had not spoken. The rules that follow from it:

- The packaged launcher is told what an installed app needs, and nothing
  a build machine needed. Compose's `application { jvmArgs }` feeds the
  `run` task **and** the jpackage cfg, so a dev path put there ships: the
  1.0.1 MSI carried the CI runner's `kernel/target/debug`. Dev arguments
  go on the `run` task.
- The footprint flags in the distributable, each with its measurement:
  `-XX:+UseSerialGC` (a single-user desktop app has no pause budget that
  needs a concurrent collector, and G1's tables cost more than the heap),
  `-Xms32m -Xmx768m` (the default maximum is a quarter of RAM and the
  collector sizes itself from it), `-XX:CICompilerCount=2` (the minimum
  for tiered compilation, in a process that measured 101 threads).
  Together: **504/360/101 → 376/310/63**, native-memory-tracking
  committed 216 → 91 MB.
- **A model is resident for the work, not for the session.** Anything
  costing tens of megabytes loads on the first job that needs it and is
  dropped when none has for a while (ADR 0018, 2026-09-04). "Available"
  is a question about the file, and answering it must stay cheap.
- **The window draws on the CPU by default** (owner decision 2026-11-01,
  overturning 2026-09-04's "the renderer is not a footprint lever").
  `Rendering.kt` sets `skiko.renderApi=SOFTWARE_FAST` in `main` unless a
  renderer was named at launch; **Settings ▸ Appearance ▸ Draw with the
  graphics card** (device pref `gpuDrawing`, read at start) gives Skiko
  its own choice back. Measured against every alternative
  Skiko 0.9.4 offers (2026-11-01, probe copies of the installed 1.3.2, a
  1,500-line note scrolled by 300 wheel notches in a window the size of
  the owner's 5120×1440 screen): Direct3D sustained 75–83 fps at 413–417
  MB idle; OpenGL 45–49 fps at 417–420 MB; `SOFTWARE_FAST` 39–43 fps at
  292 MB. Software drew with *less* CPU (122% of a core against 135–147%)
  — the cost is Compose's text layout, not rasterising — but half the
  frames on a wide screen. Vulkan falls back to Direct3D on Windows;
  `skiko.buffering` and `skiko.vsync.enabled` changed nothing measurable;
  this Skiko exposes no limit on Skia's GPU cache. In a small window
  OpenGL looked cheaper (306–320 MB against 372–375); that is window
  size, not a saving. Memory won: ~120 MB for half the frames on the
  widest screen, and the processor path used less CPU.
- **Java2D's Direct3D pipeline is off** (`-Dsun.java2d.d3d=false`, in
  the Gradle `jvmArgs` and the Windows launcher, held together by
  `DesktopLaunchArgumentsTest`). Compose draws through Skia's own
  device and never through Java2D's, which cost 35 MB private and 16
  threads on an idle window (2026-11-01). The only Swing left is the two
  `JFileChooser` dialogs, which Java2D then draws with GDI.
- **An open note costs memory in proportion to its length.** The editor
  lays out the whole buffer through Skia's paragraph engine, not the
  visible lines: a 1,500-line note held ~90 MB more than an empty
  window (Java heap +12 MB, the rest native Skia layout objects that
  survive a full GC — live, not leaked). An editor that lays out only
  what is on screen is the lever, and a large one. A background tab
  costs its text and nothing more: three more tabs of a 64 KB note moved
  the live Java heap by −137 to +380 KB after a full GC and kept no Skia
  layout (only each pane's front tab is composed).
  Split further (same 64 KB, processor drawing): plain text in the
  editor +22 MB; the same bytes as styled Markdown +70 MB — ~6,300
  style runs at ~7.5 KB native each; **Read mode +136 MB**, because
  `MarkdownBody` is a plain `Column` that composes and lays out every
  block, on screen or not (38,919 live Skia objects). **Fixed by ADR 0032
  stage 1**: presentation mode and the phone's Read view draw through
  `LazyMarkdownBody`, which composes only the blocks near the screen.
  Re-measured with the installed 1.3.2 against a copy of it carrying the
  new jar (two runs each, full GC, private bytes): empty window 138–140
  MB both; the 64 KB note presented 280 MB before, 153–155 MB after —
  **+141 MB → +13–15 MB**. `MarkdownBody` stays for short fixed previews
  only; a whole note never goes through it.
- **The launcher runs on the Segment Heap** (ADR 0032 stage 2;
  `.github/windows-launcher/gilune.manifest`, the launcher's only
  manifest). Measured 2026-11-01 against a launcher identical but for the
  manifest, four runs each. At rest it held 182 MB against 193–217 MB. It
  did not cure typing's cost: after typing into the long note, both heaps
  kept +115–237 MB through a full GC and 3 minutes. That memory is held,
  not merely unreturned.
- **Typing in a long note churns native memory.** Every keystroke lays
  the whole buffer out again as one Skia paragraph, and the old one's
  native memory waits for the collector to run its cleaner — which a
  small, mostly idle heap rarely does. With the app's own flags, 150
  characters typed into that note took the process 175 → 372 MB, and
  270 MB after a full GC (the allocator keeps its high-water mark).
  Skia's paragraph cache is not it (13 entries throughout, 36 MB).
  Forcing a GC every 10 keystrokes did not help (peak 401 MB): cleaners
  run asynchronously and the allocator does not shrink. The levers are
  smaller layouts — fewer style runs, or an editor that lays out only
  what is on screen.
- **Download size is measured per part, not guessed** (2026-11-01, the
  1.3.1 MSI unpacked: 139 MB compressed, 222 MB installed). Jars barely
  compress further in an installer, so a jar's size is roughly what it
  adds to the download. Two rules came out of it:
  - **Icons: the core set plus copies, never the extended set.** The
    extended Material icons are a 36 MB jar of ~2,000 icons; the app drew
    34 of them. Those are copied into `jvmShared/.../app/icons`, whose
    README is the procedure for adding one, and `IconSourcesTest` keeps
    the dependency out and every copy licensed and in use.
  - **The MSI drops runtime files the app never reads** —
    `UNUSED_RUNTIME_FILES` in `.github/scripts/package-windows-linux.py`,
    each with its reason. Today that is `classes_nocoops.jsa` (the
    class cache for heaps above 32 GB, unreachable under `-Xmx768m`;
    2.7 MB).
  - Not done, measured: trimming the runtime with jlink saves ~7 MB of
    the MSI if every language is kept (the owner's requirement — Gilune
    will be translated), against the risk of a module that is only
    loaded at run time going missing. ProGuard is off for the same kind
    of risk (JNA, the bindings and sherpa-onnx find code by name).

## Tests

- Test the invariant, not the implementation: crash matrices, duplicate
  and reordered delivery, convergence, quarantine — the spike style is
  the product style.
- Every bug fix lands with the test that would have caught it.
- Coverage of invariants beats coverage of lines. No test theatre:
  asserting that a mock was called proves nothing a contract cares
  about.

Android Compose UI tests use `createDeviceComposeRule` or
`createDeviceAndroidComposeRule`, which supply a `StandardTestDispatcher` to the
test environment. The unconfined default can resume a UI effect on a real IO
producer's thread, as `EffectThreadTest` demonstrates. Use Compose's idle/clock
APIs to pump this scheduler and await actual asynchronous results before asserting.
Do not change back to immediate dispatch to make an early assertion pass.

## Continuous builds and releases

`.github/workflows/build.yml` is the only pipeline. Pushes to `main` and
`ci/**` build when they change `app/`, `kernel/`, `contracts/`, CI workflows
or scripts, `.gitattributes`, or `LICENSE`. README-only changes and changes
limited to architecture, documentation, source research or disposable spikes
do not start a build. Markdown vault fixtures still count as test inputs.
Update the workflow's path list when adding build inputs outside these roots.

Ordinary branch pushes run Linux kernel tests, Clippy and desktop tests, without
packaging installers or starting Windows/Android jobs. This is development
feedback, not release validation. Android-only and Windows-specific regressions
require full validation; a green branch run does not cover those platforms.

Valid `v*` release tags and every `workflow_dispatch` run perform full validation:
native and desktop suites on Windows/Linux, all Android instrumentation shards,
all three packages and bindings parity. Tags also publish a GitHub Release.
Manual runs on branches do not publish a Release; `dry_run_release` checks the
collected packages. Tag pushes bypass branch path filters, and manual runs
force full validation even for tagged commits or documentation-only changes.

Android caches both shipped ABIs and their matching generated Kotlin bindings
as one exact entry keyed by native sources, contracts, build scripts/workflow,
app build configuration, Rust/NDK versions and host platform. Build outputs are
excluded from the source hash. There are no fallback keys. Missing entries
compile normally; both ABIs and bindings must exist before saving, and saving
happens before app compilation/testing so failures do not discard valid native
compilation. Tests still execute on every full run. Large cross-build trees are
deleted before the emulator starts to preserve disk space.

GitHub caches are branch scoped: manual validation on `main` can seed a cache
that later tags read; repeated manual runs on a CI branch can reuse that branch's
cache. A cache created only on one tag is not available to another tag. Cold
builds remain supported, and no additional full run is scheduled just to warm
a cache. See [cache scope](https://docs.github.com/en/actions/using-workflows/caching-dependencies-to-speed-up-workflows#restrictions-for-accessing-a-cache).

Push a release commit and its tag **together**:

```text
git push --atomic origin main refs/tags/v<MAJOR>.<MINOR>.<PATCH>
```

GitHub creates separate branch and tag workflow runs. The small **Build plan**
job checks tags on the event commit; a valid release tag suppresses the branch
run's expensive jobs, leaving the tag run to build, test and publish once.
Both annotated and lightweight tags work. A tag pushed later cannot prevent
work that the branch run has already started. An invalid release tag fails
version validation and does not suppress the branch build.

New qualifying pushes cancel older builds on the same branch. Release tags
and manual runs have separate concurrency groups, so branch pushes cannot
cancel them. See GitHub's [path-filter and concurrency rules](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax).

Validate routing locally with the standard-library tests (no app build needed
for a change confined to workflow routing):

```text
python -B -m unittest discover -s .github/scripts -p test_build_route.py -v
```

The runner OS families are explicit (`windows-2025`, `ubuntu-24.04`) and Rust
is pinned in `GILUNE_RUST_VERSION`. Runner image patches and Android system
images can still change: retain tool versions, device fingerprint and the
resolved Android test dependency graph with the run.

Desktop tests run through `.github/scripts/run_checked.py` with a 20-minute
wall-clock deadline and `--no-daemon`, making their JVMs descendants of the
watched command. On expiry the watchdog captures owned JVM thread dumps before
stopping that process tree, and returns failure. Reports and diagnostics upload
even on failure. Validate the watchdog with `test_run_checked.py`.

With `GILUNE_CI=1`, JVM test tasks cannot reuse cached test results or be
skipped as up to date. Dependency and compilation caches remain enabled.
For local repetition evidence use `--no-build-cache` and clean the test task's
outputs before each run; inspect the log for actual execution. `FROM-CACHE`
is not another passing runtime observation.

Android builds its APKs once, then `.github/scripts/test-android.sh` executes
three disjoint instrumentation shards in separate processes. It retains each
shard's results and device log, continues to collect coverage from the other
shards after a failure, and returns failure if any shard fails. No shard is
retried. The activity-recreation tests remain in the suite. A full unsharded
local run is also required when investigating lifecycle or test-order failures.
`test_android_shards.py` verifies report preservation and failure propagation.

UI test resources belong to the outer `UiTestResources` rule: the inner Compose
rule disposes the UI first, then application jobs are cancelled and joined,
native handles close, and temporary vaults are removed. Do not close a kernel
while a job may still be using it. The original test error must survive cleanup
errors. Use `LocalQueryDispatcher` to hold query work deliberately in timing
tests rather than adding sleeps. The Android runner records Java thread IDs
and the active test on a process exception; desktop completion diagnostics are
enabled by `GILUNE_CI=1` and omit document contents.

- **Windows** (`windows-2025`) → **MSI**. Kernel built
  `--release --features whisper`, `cargo test --workspace`, then the
  desktop suite against that same release binary
  (`-Pgilune.kernel.profile=release`) so the tests load what the
  installer will carry, then `packageReleaseMsi`. The Compose plugin
  fetches its own WiX 3, so nothing is assumed of the image.
- **Linux** (`ubuntu-24.04`) → **DEB**. The same, plus
  `cargo clippy --workspace --all-targets -- -D warnings` — once, on one
  platform, because it is the same source either side. The Compose UI
  tests run under `xvfb-run` with `SKIKO_RENDER_API=SOFTWARE`: a display
  and no GPU.
- **Android** (`ubuntu-24.04`) → **APK**. `gilune-ffi` cross-compiled
  with whisper for `arm64-v8a` and `x86_64` by
  `.github/scripts/build-android.sh`, which is the Linux counterpart of
  `kernel/scripts/build-android.ps1` and does not replace it. The NDK is
  pinned by version, not taken as whichever the image ships: an NDK is a
  compiler. The instrumented suite then runs on an API 36 Google APIs
  x86_64 emulator against the staged release kernel; its results are
  uploaded even when tests fail. A failing runtime suite blocks the
  Android job and therefore release publication. Historical MS1 measurements
  that assert a particular keyboard's bug must occur are opt-in diagnostics;
  portable composing/commit/undo invariants and the platform control remain
  mandatory. See `spikes/ms1-ime/README.md` for the diagnostic command.
- **Bindings parity**. Generating bindings per target from that target's
  own binary stays the rule (07-compatibility). The three generated
  surfaces are then diffed, because they have been byte-identical since
  MB0 and the moment that stops being true is worth knowing.

`contents: write` is granted to the release job and to nothing else.

### Local release checks

Before tagging an editor or shared-UI change, execute the corresponding desktop
**and Android instrumented tests**, then the full Android suite on the candidate
sources. Building an APK or compiling instrumentation is not device-test evidence.
Use an API 36 x86_64 emulator and the staged release kernel, matching CI:

```text
./gradlew :composeApp:desktopTest -Pgilune.kernel.profile=release
./gradlew :composeApp:connectedDebugAndroidTest -Pgilune.kernel.profile=release
```

Run these from `app` (`gradlew.bat` on Windows). Confirm the intended emulator
is connected; retain the test reports and report which platforms actually ran.
For a reported failure, first run the unchanged failing test locally when
possible, then verify the fix in that same environment. When changing shared
UI test synchronisation, inspect the equivalent flow on both targets and share
the helper in `jvmSharedTest` to prevent separate implementations drifting.
Local success does not replace Linux CI or prove an intermittent renderer fault
resolved. This check follows the v1.0.5 Android failure that reproduced locally
on the first attempt after an APK-only pre-release check missed it.

### The version rule

jpackage on Windows accepts only `MAJOR.MINOR.BUILD` with MAJOR greater
than 0, so a `v0.x` tag cannot drive an MSI. A release tag therefore reads
`v<MAJOR>.<MINOR>.<PATCH>` with MAJOR at least 1 and no suffix — `v1.0.0`,
never `v0.9.0` and never `v1.0.0-rc1`. Anything else fails the run with
that sentence rather than producing a mis-versioned installer.

The tag drives `-Pgilune.version` (the desktop `packageVersion` and the
Android `versionName`) and the run number drives `-Pgilune.versionCode`.
Both are additive: a local build passes neither and keeps the values
written in `build.gradle.kts`.

### Signing

The debug-signed APK is always produced and always installable. A
release-signed APK is produced as well when the repository holds
`GILUNE_KEYSTORE_BASE64`, `GILUNE_KEYSTORE_PASSWORD`, `GILUNE_KEY_ALIAS`
and `GILUNE_KEY_PASSWORD`; without them there is no release signing
configuration at all and the step is skipped rather than failed, because
an unsigned APK looks installable and is not. The MSI and the DEB are
unsigned.

### What this does not cover

- **No physical Android device.** The emulator exercises x86_64 runtime
  behaviour; ARM64 execution, microphone quality and device performance
  still need hardware. Model/clip-dependent speech tests remain opt-in
  and report skips when their inputs are absent.
- **No macOS**, and no Linux format but the DEB.
- **Building is not running.** A green full-validation pipeline says the three packages
  build and the suites pass, not that a package installs and starts.

### In-app updates read the release

The installed Windows app updates itself from GitHub Releases
(`app/composeApp/src/desktopMain/kotlin/org/gilune/app/Updates.kt`), so the
release job's output is a contract, not just a download:

- **Tags** stay `v<MAJOR>.<MINOR>.<PATCH>`, and the release they publish is
  neither a draft nor a pre-release — the app asks `releases/latest`, which
  answers only such a release, and compares versions numerically.
- **The installer** stays named `Gilune-<version>.msi` and is uploaded to this
  repository's release. The app offers only an asset with exactly that name,
  downloaded from `https://github.com/Gilune/gilune/releases/download/`, and
  installs it only if it matches the SHA-256 GitHub publishes for the asset.
  Renaming the MSI, or publishing it elsewhere, silently stops every
  installed copy from updating.
- **The MSI** keeps its fixed UpgradeCode and per-machine scope, so
  `msiexec /i` of a newer one replaces the installed one in place (the
  installer lifecycle test proves the upgrade). Per-machine means Windows
  asks for permission on each update; moving to a per-user install would
  remove the prompt but cannot upgrade an existing per-machine install.
- **The launcher** stays `Gilune.exe` beside `resources/` and starts the
  runtime as its child: the app finds the executable to restart from
  `compose.application.resources.dir`, and waits for itself and that
  parent to exit before the installer runs.

The app looks only after the person has said yes once (asked in a strip
under the top bar; changeable in Settings ▸ About, where Check now is a yes
for one look). A dev build or a build that is not an installed Windows
release is never asked and never installs; it is offered the release page.
The packages are unsigned, so the check is integrity against what GitHub
holds, not authenticity of who built it.
