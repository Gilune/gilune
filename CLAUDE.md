# Gilune — session onboarding

Gilune is a local-first, capture-first personal memory system: tap → speak →
done on the phone; an Obsidian-openable Markdown vault as the user-visible
store; a plugin-first microkernel underneath; devices sync by exchanging
immutable operations, LAN-first, servers optional. Product principles live in
`sources/README.md`; the architecture package in `architecture/` is the
authority — read its README first. The project was previously named Memory
Project, then Linoema; the permanent `memory`/`org.memory` contract
namespaces are deliberately project-neutral and must not chase the name.
(One deliberate exception, owner decision 2026-08-30: vault front-matter
*keys* are `gilune-*` — they are user-visible product surface — while the
identifiers in their *values* stay `org.memory.*`.)

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

1. **The vertical slice** (GO ratified 2026-08-30, review closed).
   Production code lives in `kernel/` (Rust workspace), `contracts/`
   (public contracts), and `app/` (Compose Multiplatform, from M3);
   spikes stay disposable reference. Milestones:
   - ~~M1 kernel core~~ — done 2026-08-30: schema v1, capture commit +
     recovery, at-least-once jobs, per-device operations; 6-point crash
     matrix green (`kernel/crates/gilune-cli/tests/crash_matrix.rs`).
   - ~~M2 vault~~ — done 2026-08-30: schema v2, deterministic projection,
     bidirectional reconciliation (edits→revisions+ops, deletes→proposals,
     renames-by-id, quarantine, imports); `gilune-*` front-matter keys.
   - ~~M3 boundary + shells~~ — done 2026-08-30: full FFI surface
     (streamed capture crosses chunk-by-chunk), `app/` Compose project,
     desktop + Android apps boot and pass the shared boundary suite
     (6 desktop + 6 instrumented). Packaged desktop app measures
     **~1.4 s cold / ~1.5 s warm to window** (double-click launch;
     earlier "~4 s" was gradle+probe overhead — and PowerShell
     `Start-Process` stdio wiring makes the jpackage launcher fail to
     show a window at all, so measure with `cmd /c start`). Mic adapters
     (javax.sound / AudioRecord → WAV) are code-complete and
     WAV-encoder-tested; microphone recording verified by the owner on
     Windows desktop (2026-08-30).
   - **Perf review (future)**: desktop startup baseline is JVM+Compose
     bound at ~1.4 s; if it ever needs to shrink, the avenues are
     AppCDS/AOT-cache and deferring kernel open off the first frame.
     Android cold start on real hardware rides with the ADR 0001 device
     budgets.
   - ~~M4 transcription~~ — done 2026-08-31. M4a: processor contract in
     `contracts/processor/` (WIT + Rust trait mirror), schema v3
     (processing_runs, provenance), kernel work loop with narrow
     ProcessorCtx, deterministic outputs (derived IDs), crash-tested
     (mid-output-commit abort → exactly-one on retry). M4b: local
     Whisper (whisper-rs 0.16, `whisper` cargo feature, desktop dll
     only; Android captures and lets a coordinator transcribe). Model
     auto-downloads to `%LOCALAPPDATA%\Gilune\models\ggml-tiny.bin`
     (~75 MiB, pre-seeded on this machine). End-to-end proven: TTS
     speech wav → capture → job → Whisper → transcript object →
     `Transcripts/*.md`. Build needs LLVM libclang
     (`scoop install llvm cmake`; LIBCLANG_PATH to scoop llvm bin).
   - **Review items (open)**: (1) standalone double-click launch of the
     packaged exe behaved inconsistently under automated probes while
     gradle-exec and the owner's manual launch worked — re-verify by
     hand on the current build; probes must not kill processes by name
     (the owner may be running the app). (2) Two app instances ran
     concurrently against one vault during testing: multi-process
     safety is untested (known ADR 0002 gap, now user-reachable) — add
     a single-instance guard or a vault lock. (3) whisper.cpp logs
     spam stderr; silence via log hooks.
   - **M5 LAN sync** — discovery, short-code pairing, device keys,
     encrypted transport, op exchange; acceptance = the S4-extended
     scenarios (pair, refuse unpaired, revoke, rejoin). Threat-model
     rules apply (re-enrollment on restore, per-device quotas).
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
