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

- `architecture/` — constitution, contracts (01–08), engineering
  conventions (09), ADRs 0001–0010, spike plan, prior-art survey.
- `sources/` — product principles (the "why").
- `spikes/s1-kernel-boundary/` — Compose ↔ UniFFI ↔ Rust boundary spike:
  diagnostic kernel (`kernel/`, cargo test), Compose shell for desktop +
  Android (`shell/`, `gradlew :composeApp:desktopTest` and
  `:composeApp:connectedDebugAndroidTest`); bindings are generated per
  target from that target's own binary (see its README).
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

## Status (as of 2026-08-30)

Green on Linux AND Windows: ADR 0002/0004/0007 (S2 crash matrix incl.
migration against a real v1 fixture), ADR 0003's desktop half (S3: contract
parity, sandbox containment, ~1 ms calls), ADR 0005/0008 semantics over a
shared-folder relay (S4: convergence, gaps, corrupt ops, chunked blobs,
tombstones). The Windows runs (2026-08-30) found and fixed four
portability faults — Windows fsync semantics (backup-semantics dirs,
writable handles), rel-paths normalised to `/` as a portable contract,
fixture byte-exactness via `.gitattributes`, and S3 harness timing windows
that included the one-time guest build; each spike README's Windows
evidence section has the details. S5 policy matrix drafted from live
sources. Spike S1 (ADR 0001, the Compose/Kotlin ↔ Rust boundary) is
**closed provisionally** (2026-08-30): desktop green on Windows, Android
green on an x86_64 emulator (fault list, instrumented suite,
symbolication, packaging, dual-ABI build); ADR 0001 accepted for v0 with
the mid-range-device budgets and first ARM64 execution **assumed until
the owner acquires a device**. ADR 0009 (MIT OR Apache-2.0) and ADR 0010
(downloadable store plugins via the sandboxed runtime; test submission
gates promises) are ratified. **Next: application-level engineering — see
`architecture/09-engineering-conventions.md` and the vertical slice.**

## Next steps

1. **The foundation acceptance review sitting** (owner):
   `architecture/foundation-review.md` is prepared — ratify the ADR
   dispositions (§1) and the go/no-go (§5); the S5 threat-model workshop
   feeds its gates and needs the same sitting or its own hour.
2. **The vertical slice** (on "go"): first application code under
   `architecture/09-engineering-conventions.md` — capture → kernel →
   vault → LAN peer-to-peer sync between paired devices, end to end on
   Windows and Android (owner scope decision 2026-08-30; pairing,
   device keys, and encrypted transport are in scope — relay/E2EE stays
   gated; see review §5).
3. ~~Spike S1~~ — closed provisionally 2026-08-30 (see its README). When
   the owner's device arrives: run the instrumented suite on hardware,
   measure the capture-commit/cold-start budgets, first ARM64 execution.
   Machine notes: emulator AVD `gilune-s1` exists; stale `ANDROID_HOME=
   C:\Android\android-sdk` env var must be overridden to
   `%LOCALAPPDATA%\Android\Sdk` per process, and the old `C:\Android\
   adb.exe` fights the SDK's adb server — kill both, start the SDK's.
4. ~~Owner decisions~~ — all closed 2026-08-30: ADR 0009 ratified, the
   S5 threat-model workshop held
   (`spikes/s5-distribution-policy/threat-model.md`), and the Gilune
   name derivation recorded in `architecture/README.md` § The name.

## Conventions

- Application code follows `architecture/09-engineering-conventions.md`
  (Accepted 2026-08-30).
- Docs use British spelling and the existing terse, contract-like style.
- Commit messages: imperative subject; body says what changed and why;
  spike commits summarise the evidence they add.
- The spike crates are disposable prototypes — clarity over polish — but
  the boundary rules above hold even there.
