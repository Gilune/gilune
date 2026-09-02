# ADR 0001: Application Shell and Kernel Language

Status: **Accepted for v0** (owner decision 2026-08-30, provisional)  
Validation: Spike S1 — desktop half green on Windows; Android half green
on an x86_64 emulator (build pipeline, fault list, instrumented boundary
suite, symbolication, packaging). The acceptance gate's physical-device
items — capture-commit and cold-start budgets on mid-range hardware, and
first ARM64 execution — are **assumed, not measured**: the owner accepted
on the existing evidence and will acquire a device. If the device
measurements later fail the budgets, this acceptance is revisited per the
failure response in the spike plan.

## Context

The product is client-first rather than web-first. Android and Windows are both
required initial clients: the phone is central to low-friction capture, while the
Windows client is where substantial review and organisation work happens. macOS,
Linux, and later iOS remain targets rather than defining the initial delivery
sequence.

The kernel needs consistent storage, provenance, plugin, sync, and cryptographic
behaviour across all clients and potentially the server. Platform shells must
integrate naturally with microphone and future image capture, permissions,
background work, notifications, accessibility, and secure key storage.

The difficult trade-off is between sharing the invariant-heavy kernel and
keeping Android and Windows integration, packaging, and debugging simple.

## Decision

- Build the application shell and shared UI in Kotlin Multiplatform with Compose
  Multiplatform.
- Build the portable kernel as a Rust library.
- Generate narrow, versioned Kotlin and later Swift bindings. Do not expose Rust
  database or plugin-runtime implementation types across the boundary.
- Keep OS facilities behind Kotlin/Swift/desktop adapters that implement kernel
  capability contracts.
- Permit platform-specific UI or service code where shared Compose abstractions
  are insufficient.

Compose Multiplatform currently documents Android, iOS, and desktop UI targets as
Stable: <https://kotlinlang.org/docs/multiplatform/supported-platforms.html>.
UniFFI supports generated Kotlin and Swift bindings:
<https://mozilla.github.io/uniffi-rs/latest/bindings.html>.

## Why this is preferred

- Android remains native to the Jetpack Compose and Kotlin ecosystem.
- Windows receives a first-class installed client rather than a browser-based
  companion to the phone.
- Android, Windows, the other desktop targets, and later iOS can share UI and
  presentation logic without making a web application the architectural centre.
- Rust offers one memory-safe implementation for data invariants, sync parsing,
  encryption, and a WebAssembly host.
- The kernel can be reused by command-line tools and server-side processing.
- A narrow generated boundary makes it possible to replace the UI stack or the
  kernel implementation independently if necessary.

## Alternatives considered

### Kotlin Multiplatform kernel and UI

This gives the initial Android and Windows clients the simplest single-language
debugging and build pipeline. It remains the fallback if Rust integration
materially harms developer velocity, binary size, background execution, crash
diagnosis, or cross-platform packaging. Plugin sandbox hosting and server reuse
would need separate treatment.

### Flutter shell with Rust kernel

Flutter offers broad UI sharing but adds Dart and a second binding ecosystem
without a clear advantage for a client-first product whose co-primary initial
targets are Android and Windows.

### Tauri shell and Rust kernel

Tauri aligns well with a Rust kernel and small desktop binaries, and supports
mobile targets. Its system-WebView UI and JavaScript/native bridge are less
attractive for the P0 Android capture experience. It remains a credible desktop
shell alternative if Compose Desktop distribution proves problematic.

### Separate native clients

This gives maximum platform fit but duplicates presentation and coordination
logic early and increases the chance of divergent kernel behaviour if boundaries
are not perfectly maintained.

## Consequences

- The build uses Gradle/Kotlin and Cargo/Rust toolchains.
- Cross-boundary values must be deliberately small, owned, cancellable, and
  versioned.
- Panics, coroutine cancellation, callbacks/streams, large blob streaming, and
  threading must have explicit mappings.
- Rust does not call platform APIs directly except through registered capability
  adapters.
- The team pays an up-front FFI and packaging cost to reduce long-term invariant
  duplication.

## Acceptance gate

S1 must demonstrate on physical Android and Windows targets:

- repeatable debug and release builds;
- kernel initialisation and migration;
- streaming a large blob without a full-memory copy;
- asynchronous call, cancellation, callback/event wake-up, and error mapping;
- crash symbols and actionable diagnostics across the boundary;
- acceptable startup, package-size, and incremental-build impact;
- a minimal background worker reopening the kernel safely.

If these fail materially, supersede this ADR with a Kotlin Multiplatform kernel
decision before domain features are implemented.

## Amendments

**2026-09-02 (owner, via ADR 0018 acceptance):** the package-size budget gains a
companion. "Kernel contribution to release package size: under 15 MB per ABI"
(`architecture/spikes/README.md:36`) measures the kernel's own line, and native
weight can now leave it: ADR 0018 ships a transcription engine as an Android
library, so `gilune_ffi.so` does not grow while the user's download does. A
**companion app-package budget** therefore sits beside it — per ABI, measured on
the shipped artefact — and the two are reported together, so bytes moved across
the boundary cannot be counted as a saving. Its number is **set at R0** from
that spike's measurement rather than guessed here.
