# Spike S1 — Kernel Boundary and Cross-Platform Build

Status: **In progress** — Windows desktop half green (Compose Desktop ↔
UniFFI ↔ Rust); Android half green **on an x86_64 emulator** (build
pipeline, full fault list, instrumented boundary suite). The performance
budgets and ARM64 remain gated on physical hardware.

Evidence prototype for ADR 0001 (Compose Multiplatform shell, Rust kernel,
UniFFI-generated bindings) and the headless sync-agent profile assumption
in ADR 0008. No domain code: the kernel is a deliberately small diagnostic
surface, one mechanism per acceptance-gate item.

## Layout

- `kernel/` — Rust crate (`cdylib` + `rlib`), UniFFI proc-macros, no UDL:
  records with optionals, a sealed error enum, SQLite open + two-step
  migration, one-transaction writes, chunked blob streaming (64 KiB chunks;
  no whole-blob copy crosses the FFI), tokio async with a Drop guard that
  makes cancellation observable, Rust→Kotlin event callbacks, and a
  deliberate panic. `src/bin/headless.rs` is the ADR 0008 profile: same
  kernel, no JVM. `src/bin/uniffi-bindgen.rs` drives binding generation
  (no global install).
- `shell/` — Compose Multiplatform project, desktop (JVM) target only so
  far. `Main.kt` is the diagnostic screen (one button per mechanism, a log
  pane, timings). `BoundaryTest.kt` is the machine-checked evidence: each
  test is one acceptance-gate item exercised from Kotlin.

## Run it

```sh
cd kernel
cargo test && cargo build                     # 5 Rust-side tests + dylib
cargo run --bin uniffi-bindgen -- generate \
  --library target/debug/spike_s1_kernel.dll \
  --language kotlin --out-dir ../shell/composeApp/src/desktopMain/generated
cd ../shell
./gradlew :composeApp:desktopTest             # 7 boundary tests
./gradlew :composeApp:run                     # diagnostic screen
```

Android (emulator or device; needs the NDK, and bindings must come from
the Android binary — checksums are target-dependent, see evidence):

```sh
cd kernel
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=$NDK/bin/x86_64-linux-android29-clang.cmd
export CC_x86_64_linux_android=$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER
export AR_x86_64_linux_android=$NDK/bin/llvm-ar.exe
cargo build --release --target x86_64-linux-android --lib
cp target/x86_64-linux-android/release/libspike_s1_kernel.so \
   ../shell/composeApp/src/androidMain/jniLibs/x86_64/
cargo run --bin uniffi-bindgen -- generate \
  --library target/x86_64-linux-android/release/libspike_s1_kernel.so \
  --language kotlin --out-dir ../shell/composeApp/src/androidMain/generated
cd ../shell
./gradlew :composeApp:connectedDebugAndroidTest   # 8 tests on the device/AVD
```

Release-profile numbers: `cargo build --release` and pass
`-Ps1.kernel.profile=release` to gradle. The dylib is loaded from the cargo
target directory via `jna.library.path`; nothing is packaged yet.

## Evidence: Windows desktop (Windows 11, rustc 1.98 MSVC, JDK 17,
Kotlin 2.1.20, Compose MP 1.7.3, Gradle 8.6; 2026-08-30)

7/7 Kotlin boundary tests and 5/5 Rust-side tests green.

- **Contract shape crosses** — records, `Option` → nullable, `u64`/`u32` →
  `ULong`/`UInt`, `Vec<u8>` → `ByteArray`; the Rust error enum arrives as a
  sealed `KernelException.{Storage,NotFound,Invalid,Cancelled}` hierarchy
  and is caught per-variant.
- **Panic containment** — a Rust panic surfaces as `InternalException`
  carrying the panic message; the kernel object keeps serving calls
  afterwards.
- **Cancellation crosses** — cancelling the Kotlin coroutine drops the
  Rust future: proven by a Rust-side `Drop` guard emitting `async-dropped`,
  which the Kotlin test observes. Completion emits `async-completed`.
- **Events cross the other way** — Rust→Kotlin callbacks fire on commit,
  blob promotion, and async milestones; a Kotlin listener object is held
  and invoked from Rust threads.
- **Streaming discipline holds** — 64 KiB chunks, staged then promoted by
  rename; SHA-256 computed independently in Kotlin and Rust agrees on
  64 MiB; a deliberately slow consumer (4 KiB chunks + sleep) is a valid
  reader, not an error.
- **Missing native library** — a clean, catchable `UnsatisfiedLinkError`
  at first kernel touch, naming the library and every searched path; the
  process survives and can present the failure.
- **Headless profile (ADR 0008)** — the same kernel with no JVM: open
  18 ms cold / 1.4 ms reopen; a second OS process reopening the same vault
  sees the first's committed records (WAL).

### Measurements (against the spike plan's initial targets)

- Cold open incl. two migration steps: 16–19 ms; first call ~2 ms;
  `put_record` transaction 1.2–1.9 ms. (The 200 ms capture-commit budget
  is for mid-range Android, still unmeasured; desktop clears it by ~100×.)
- Streaming across the FFI, release dylib: **write ~176 MiB/s, read
  ~324 MiB/s**; pure-Rust headless baseline 616 / 1671 MiB/s — a 3–5×
  boundary cost, far above any capture workload. The debug dylib collapses
  to ~14 MiB/s — that is unoptimised `sha2`, not FFI; only release numbers
  are meaningful.
- Size: release dylib **2.14 MiB** (budget: kernel ≤ 15 MB per ABI);
  debug 2.97 MiB; headless.exe 1.89 MiB.
- Builds: kernel clean release 1 m 34 s, incremental rebuild 2.1 s; shell
  first build 1 m 19 s (warm Gradle caches), test cycle 10–25 s.
- Binding-code surface: one generated Kotlin file (~3 k lines) from
  proc-macro annotations alone; no hand-written unsafe on either side —
  the unsafe surface is confined to the generated layer and JNA.

## Evidence: Android emulator (API 36.1 google_apis x86_64 AVD, WHPX,
NDK r30, AGP 8.10.1, Gradle 8.11.1; 2026-08-30)

The same kernel source cross-compiled for `x86_64-linux-android` (NDK
clang as linker and as CC for bundled SQLite; first-try build, 55 s;
`libspike_s1_kernel.so` **2.77 MiB**), packaged via `jniLibs`, driven by
the same shared Kotlin through the same UniFFI surface.

- **8/8 instrumented tests** on the emulator: the 7 boundary tests
  verbatim (same source file as desktop) plus a WorkManager test — a real
  background worker opens the kernel, commits, closes; a second worker
  instance reopens the same vault and sees the first's record.
- **Lifecycle fault list, zero crashes** (adb-scripted, kernel auto-opened
  from first frame): activity recreation via forced configuration change
  → reopen 11 ms; process death (`am force-stop`) + relaunch → reopen
  1.4 s cold; fresh install first-ever open 859 ms (JNA class-load +
  migration included).
- **Finding: UniFFI API checksums are target-dependent.** Bindings
  generated from the Windows dylib pass on desktop but throw
  "API checksum mismatch" against the Android .so built from identical
  source (debug and release Windows builds agree with each other; the
  Android build does not). Bindings are therefore generated per target
  from that target's own binary — and any future packaging pipeline must
  treat bindings + binary as one artefact, which is the versioned-boundary
  discipline ADR 0001 asks for anyway.
- Emulator-only numbers, **not budget evidence**: cold-open 114 ms,
  first call 25 ms, put-record 15 ms, FFI streaming ~10 MiB/s; debug APK
  25.45 MiB; debug cold app start ~13 s (unoptimised debug Compose on an
  emulator — indicative of nothing but itself). Every figure needs
  re-measuring on mid-range hardware, release build.

## Evidence: symbolication, packaging, ARM64 (2026-08-30)

- **Crash reports cross the boundary.** A panic hook captures message +
  `Backtrace::force_capture` into `last_panic_report()`, asserted from
  Kotlin in the shared suite on both platforms. Panic **site**
  (file:line + message) always crosses via the exception as well.
- **Symbol quality is host-dependent, and the surprise is inverted**:
  - pure-Rust host on Windows: full symbols with file:line;
  - **JVM host on Windows: frames captured, zero symbols resolved**
    (dbghelp inside the JVM process — the one genuine diagnostics gap);
  - Android/ART host: full symbol names from the release `.so`'s ELF
    symtab (`<spike_s1_kernel::Kernel>::trigger_panic` and the uniffi
    frames, no file:line).
- **Desktop is packaged.** `createReleaseDistributable` (ProGuard off)
  produces a self-contained app image of **122.4 MiB**, of which the
  kernel dll + pdb are 4.1 MiB (~3%; dll alone 2.14 MiB) shipped via
  Compose app resources; the packaged exe was launched and opened the
  kernel from its own resources dir in 231 ms (cold JVM + first JNA
  load). JVM + Compose runtime, not the kernel, is the size story.
- **ARM64 builds through the same pipeline**: `aarch64-linux-android`
  release `.so` 2.96 MiB; the dual-ABI debug APK (x86_64 + arm64-v8a)
  is 31.08 MiB and passes the instrumented suite on the x86_64 emulator.

## Honest gaps (not yet evidence)

- **No physical device.** The capture-commit and cold-start budgets are
  defined on mid-range hardware; emulator numbers above are explicitly
  out of scope for them, and the ADR 0001 gate names physical hardware.
  The ARM64 `.so` builds and packages but has **never executed**.
- JVM-host Windows backtraces resolve no symbols, and unresolved frames
  print without addresses, so offline symbolication from the report
  alone is impossible. Before ADR 0001 acceptance the crash path needs
  module+offset output (backtrace crate) or minidump tooling.
- Peak memory during streaming asserted by construction (chunk-sized
  buffers), not measured.
- Only the app-image format is measured: MSI needs WiX (untested), and
  ProGuard is disabled, so minified size is unknown.
- No deliberate bindings-skew test — though the checksum finding shows
  the runtime check works: mismatched bindings fail closed at first
  touch, loudly, on every call path.
- Cancellation drops the future; there is no test that a mid-transaction
  cancellation leaves the store consistent (S2 owns transactional
  invariants; this spike only proves the drop is observable).
