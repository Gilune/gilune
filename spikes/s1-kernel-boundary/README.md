# Spike S1 — Kernel Boundary and Cross-Platform Build

Status: **In progress** — Windows desktop half green (Compose Desktop ↔
UniFFI ↔ Rust); Android half not started, blocked on NDK + a physical
device.

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

## Honest gaps (not yet evidence)

- **No Android half.** Activity recreation, process death, background
  worker restart, cold-start and package-size budgets on a mid-range
  device — the ADR 0001 gate names physical hardware, and the desktop
  worker analogue (headless reopen) is weaker evidence. Needs NDK +
  device.
- Panic **message** crosses; a Rust **backtrace** does not by default.
  Symbolication quality (panic hook, `RUST_BACKTRACE`, dump tooling) is
  unassessed and is an explicit gate item.
- Peak memory during streaming asserted by construction (chunk-sized
  buffers), not measured.
- Nothing is packaged: the dylib loads from the cargo target dir. The
  jpackage/MSIX packaging cost and the release package-size contribution
  of JVM + Compose are unmeasured.
- One boundary version only; no bindings-skew / versioning test.
- Cancellation drops the future; there is no test that a mid-transaction
  cancellation leaves the store consistent (S2 owns transactional
  invariants; this spike only proves the drop is observable).
- Gradle 8.6 (the machine's cached distribution) with deprecation
  warnings; not a considered toolchain choice.
