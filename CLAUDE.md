# Gilune — session onboarding

Gilune is a local-first, capture-first personal memory system: tap → speak →
done on the phone; an Obsidian-openable Markdown vault as the user-visible
store; a plugin-first microkernel underneath; devices sync by exchanging
immutable operations, LAN-first, servers optional. Product principles live in
`sources/README.md`; the architecture package in `architecture/` is the
authority — read its README first. The project was previously named Memory
Project, then Linoema; the permanent `memory`/`org.memory` contract
namespaces are deliberately project-neutral and must not chase the name.

## Ground rules (distilled — the constitution is the law, read it in full)

- Capture is preservation-first: saving never requires network, account,
  model, or structure; failure favours preservation.
- Sources are immutable; interpretations are separate artifacts with
  provenance. Never ingest an edit to a source as source history.
- The kernel owns invariants, not features. Domain code (tasks,
  transcription, …) is plugin territory: in the spikes it is quarantined in
  `spikes/s2-durable-capture/src/processors.rs` behind a narrow
  `ProcessorCtx`. Kernel modules must never name a domain type.
- At-least-once processing with idempotent handlers; conflicts are preserved
  as multiple heads — never wall-clock last-write-wins.
- Openness is a goal (constitution rule 13): third-party tools reading and
  editing the vault is supported use.
- Decisions are ADRs (`architecture/decisions/`) validated by spikes
  (`architecture/spikes/README.md`). Spikes are disposable evidence; every
  spike README records evidence AND honest gaps — keep that honesty. When a
  question is answered, update `architecture/08-open-questions.md` and the
  decision map: an answer becomes a contract change or an ADR.

## Layout

- `architecture/` — constitution, contracts (01–08), ADRs 0001–0009, spike
  plan, prior-art survey.
- `sources/` — product principles (the "why").
- `spikes/s2-durable-capture/` — Rust kernel-core spike: durable capture,
  events/jobs, Markdown vault projection and reconciliation, schema
  migration, plus the sync module and S4 harness. `cargo test` runs the
  crash matrix and the sync convergence scenarios (~30 tests).
- `spikes/s3-plugin-contract/` — WIT processor contract, wasm component
  guest, capability-checked Wasmtime host. Needs
  `rustup target add wasm32-wasip2`; then `cd host && cargo test` (the
  tests build the guest themselves).
- `spikes/s4-sync/README.md`, `spikes/s5-distribution-policy/README.md` —
  evidence reports for sync and store policy.

## Status (as of 2026-08-29)

Green on Linux: ADR 0002/0004/0007 (S2 crash matrix incl. migration against
a real v1 fixture), ADR 0003's desktop half (S3: contract parity, sandbox
containment, ~1 ms calls), ADR 0005/0008 semantics over a shared-folder
relay (S4: convergence, gaps, corrupt ops, chunked blobs, tombstones).
S5 policy matrix drafted from live sources. **Not yet validated: ADR 0001 —
the Compose/Kotlin ↔ Rust boundary — which needs real Windows and Android
hardware. That is Spike S1, and it is the next task.**

## Next steps on a Windows machine

1. **Toolchain**: rustup (stable ≥ 1.98) with the MSVC toolchain and Visual
   Studio Build Tools. Then run the existing suites ON WINDOWS —
   `spikes/s2-durable-capture$ cargo test` and
   `spikes/s3-plugin-contract/host$ cargo test` (after
   `rustup target add wasm32-wasip2`) — and add a Windows line to each spike
   README's evidence section, with any fixes. Likely hazards: path
   separators, SQLite WAL file locking, renames over open handles. Fix
   forward; never weaken an invariant to make a platform pass.
2. **Spike S1** (`architecture/spikes/README.md` § S1; ADR 0001): scaffold
   Compose Multiplatform (JDK 17+, Gradle) with the Rust kernel behind
   UniFFI-generated bindings; a diagnostic screen only; run the S1 fault
   list and measurements against the budgets in the spike plan's "Initial
   targets". Create `spikes/s1-kernel-boundary/` with the code and a README
   in the house style (evidence + honest gaps). Android needs Android
   Studio + NDK and a physical device; Windows desktop runs via Compose
   Desktop (JVM).
3. **Owner decisions still open**: ratify or amend ADR 0009 (licensing) and
   add the LICENSE files its split requires; fill the Gilune name TODO in
   `architecture/README.md`; schedule the S5 threat-model workshop.

## Conventions

- Docs use British spelling and the existing terse, contract-like style.
- Commit messages: imperative subject; body says what changed and why;
  spike commits summarise the evidence they add.
- The spike crates are disposable prototypes — clarity over polish — but
  the boundary rules above hold even there.
