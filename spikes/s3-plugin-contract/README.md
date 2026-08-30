# Spike S3 — Plugin Contract, Sandbox, and Lifecycle

Status: **In progress** — sandboxed-component half green on Linux and
Windows; bundled-Android parity, streaming, and lifecycle remain.

Evidence prototype for ADR 0003 (WIT contracts, WebAssembly component
execution) and the capability model in `architecture/05-plugin-system.md`.
It formalises the `ProcessorCtx` seam carved in Spike S2 as a real contract
and runs the same sample processors as an untrusted component.

## Layout

- `wit/processor.wit` — the contract: a `host-objects` import shaped like a
  scoped `objects.read` grant, and a `handler` export returning a
  `proposed-output`. Deliberately minimal but exercises records, results,
  options, lists, and strings.
- `guest/` — the S2 sample processors recompiled for `wasm32-wasip2` (Rust
  produces a component natively), plus adversarial handlers: `crash`,
  `spin`, `hog`, `huge-output`, `bad-json`, `undeclared-read`.
- `host/` — Wasmtime host: deny-by-default grants with an audit trail,
  per-run fresh store, fuel metering, memory limits via store limiter, and
  host-side output policy (size cap, JSON validity, bounded identifiers).

## Run it

```sh
rustup target add wasm32-wasip2
cd host && cargo test            # builds the guest component itself
```

## Evidence so far (Linux x86_64, 2026-08-29; 13 tests, repeated runs clean)

- **Contract parity** — `fake-transcribe` and `fake-task` produce
  byte-identical payloads to the bundled implementations in
  `spikes/s2-durable-capture/src/processors.rs`, for text and audio inputs.
  Same contract, two execution profiles: the core ADR 0003 claim, at
  desktop fidelity.
- **Deny-by-default capabilities** — a missing grant and an out-of-scope
  object are both refused as ordinary errors (never traps), audited, and
  leave the sandbox serving granted work.
- **Containment** — a panicking guest traps without harming the engine; an
  infinite loop is cut off by fuel within wall-clock bounds; unbounded
  allocation is stopped by the store memory limit (fuel demonstrably not
  the binding constraint).
- **Output policy** — a 32 MB payload and malformed JSON are rejected by
  host policy after a successful call, before anything would be committed.
- **Determinism** — duplicate delivery returns identical outputs, down to
  identical fuel consumption (13,335 units per transcribe call).
- **Costs** — component: 174 KB. One-time load+JIT: ~0.9 s (cacheable /
  precompilable via wasmtime). Instantiate + call: ~1 ms, comfortably
  inside the capture-adjacent latency budget in the spike plan.

**Finding worth recording:** a Rust-std guest implicitly imports WASI
interfaces (io, clocks, random, cli) even when its own world imports only
our contract. The host therefore grants a deny-by-default WASI context —
no filesystem, no network, no environment, discarded stdio — and anything
broader must become a declared, granted capability. Plugin authors'
"innocent" standard library use is itself a capability surface; the S5
policy review and the manifest schema must account for it.

## Evidence: Windows (Windows 11, MSVC, rustc 1.98, 2026-08-30)

Same 13 tests green, cold parallel and serial, with no changes to host,
guest, or contract — containment, capability denial, and output policy
behaved identically. Numbers: component load+JIT ~0.35 s (warm OS cache);
instantiate + call ~1 ms, matching Linux. Fuel per transcribe call observed
as 33,816 against the 13,335 recorded on Linux: fuel is a property of the
compiled guest, the lockfile is unchanged, and the cause (toolchain
codegen for `wasm32-wasip2`) was not chased — within-build determinism,
which is what the test asserts, holds.

One harness fault fixed: both timing tests started their clocks before the
one-time guest `cargo build` that `component_path()` triggers, so a cold
parallel run measured the build — and thirteen tests queueing on cargo's
target-dir lock — as "load" (~29 s observed) and failed the ceiling. The
Linux numbers were evidently taken warm. The guest build is now excluded
from the measured windows on every platform.

## Honest gaps (not yet evidence)

- No bundled-Android side yet: parity is proved against S2's native Rust
  processors on Linux, not a Compose/Android build (S1 scaffold first).
- Sync calls only: async jobs, streaming blob reads, and callback delivery
  through WIT remain untested — the hardest open question for ADR 0003.
- No manifest/signature pipeline, no upgrade/quiesce/uninstall lifecycle,
  no per-use confirmation UX: contract and sandbox only.
- Fuel/latency numbers are two desktop machines (Linux, Windows); JIT
  warm-cache untested on mobile.
- The component still links Rust std; a no_std or stripped profile may
  shrink the WASI surface and size further.
