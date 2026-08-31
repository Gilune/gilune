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
  conventions (09), ADRs 0001–0012, spike plan, prior-art survey.
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
     (the owner may be running the app). (2) ~~multi-process safety~~ —
     **multi-instance on one vault is supported use by owner decision
     (2026-08-31)**, torture-tested (20 concurrent capture processes
     racing project/reconcile/work; 20 clean runs). Fixes: SQLite
     busy_timeout, unique projection temp files, both-constraint
     upsert, age-gated stage sweeping (recovery can't steal a live
     capture; `force_stage_sweep` for tools), idempotent promotion,
     bounded rename retry — and `ids::short()` now uses the UUIDv7
     random tail, since the timestamp prefix collided filenames for
     same-millisecond captures. (3) whisper.cpp logs spam stderr;
     silence via log hooks.
   - **M5 LAN sync** — discovery, short-code pairing, device keys,
     encrypted transport, op exchange; acceptance = the S4-extended
     scenarios (pair, refuse unpaired, revoke, rejoin). Threat-model
     rules apply (re-enrollment on restore, per-device quotas).
   - **M6 desktop shell** — design session held 2026-08-31; ADR 0011
     (shell regions, fixed five-slot list, plugin-owned UI, everything
     but the vault projection removable) and ADR 0012 (the Markdown
     buffer is always the document; rich presentation renders it) are
     ratified. Order: ~~(a) kernel plugin registry~~ — done
     2026-08-31: schema v4 (`plugins`, `plugin_grants`), declarations
     live beside their implementations
     (`vault::plugin_descriptor`, `whisper::descriptor`), job delivery
     gated on plugin state, removal keeps a tombstone so bundling is
     not consent, grants never inherited across an update or a
     removal, FFI surface (`plugins`, `register_plugin`,
     `set_plugin_enabled`, `remove_plugin`, `grant_capability`, …)
     and `gilune-cli plugins`. Off means nothing is queued and waiting
     jobs are parked; switching on resumes them but never backfills.
     ~~(b) shell restructure~~ and ~~(c) editor E1~~ — done
     2026-08-31: rail with a collapsing tree, creation, rename, and
     delete-that-answers-its-own-tombstone-proposal; document pane
     with Rich/Raw, properties strip, autosave and the stale-buffer
     guard; capture bar across the foot with the mic drawn only when
     a plugin contributes `capture.actions`; settings surface (vault
     with a folder picker, plugins with permissions/events/
     contributions and enable/remove/allow, appearance, about);
     desktop palette and density instead of stock Material purple.
     Voice capture is its own shell-declared adapter, so removing
     transcription leaves recording intact. Markdown rendering uses
     `org.jetbrains:markdown` in shared code.
   - **M6 second pass (2026-08-31, Fable, screenshot-validated)** —
     the first shell build shipped unvalidated and missed the design;
     rebuilt against the wireframe and verified by launching probe
     instances and reading screenshots. Editor stage **E2 is done**
     (owner: "I expect to be able to edit the markdown in the app"):
     `MarkdownEditTransformation` styles the live buffer via
     VisualTransformation with identity offsets — styling can never
     change bytes (tested). Segmented Edit|Read; notes open editing.
     Top bar gained working jump-to search (Ctrl+P) and shortcuts
     (Ctrl+Shift+C focuses capture, Esc closes settings). Tree: inline
     naming (no modals), icons, Captures inbox count, context menu
     incl. Reveal in Explorer. Out-of-app deletions surface as a
     banner (restore/delete). Settings follows the Obsidian shape the
     owner asked for: manager section with switches plus one nav entry
     per plugin opening its own screen (per-permission Allow/Revoke
     rows, events, contributions, Remove). Consent policy at the host:
     a bundled plugin's LOCAL capabilities are granted once on first
     install (FFI test `builtin_grants.rs`); `network.request` always
     waits for an explicit yes; nothing re-grants after revoke or
     removal. **Next in M6**: E3 only after a spike; command-palette
     slot; Android shell parity (bindings are STALE — regenerate from
     an NDK build before compiling androidMain). M5 remains the last
     milestone of the vertical slice.
   - **M6 third pass (2026-08-31, agent team: four Opus implementers,
     Fable managing/validating; screenshot-validated)** — ADR 0013
     ratified and implemented: processor outputs may **revise their
     subject**; transcription lands in the capture note (body = text
     only, audio linked via `gilune-blob`); `Transcripts/` is gone and
     old vaults merge once on open (`transcript_merge`, flag
     `transcripts_merged_v1`; proven on a copy of the owner's vault).
     **Attached captures** (`begin_attached_capture`) commit like any
     capture but never project — the dictation substrate. App: editor
     mic **dictates at the cursor** (attached capture → whisper →
     `object_text` → insert at caret); capture-bar mic = new note from
     speech, opening the note it makes. Checkbox E3 slice: `- [ ]`
     renders as a glyph off the caret's line, raw on it, click toggles
     (offset-mapped transformation, exhaustively tested). Editor buffer
     is body-only (`splitNoteRaw`/`assembleNote`, byte-lossless,
     tested) with `TextFieldValue`; click-anywhere-to-edit with a UI
     regression test (compose uiTestJUnit4); Properties window (⋯ menu;
     `gilune-*` locked, custom pairs editable); tree nests attachments
     under their notes hidden until expanded, orphan non-md hidden;
     inline rename (co-renames attachments by stem); neutral
     surfaceContainer colours (dialogs no longer purple). Kernel bug
     fixed with test: `ingest_external_edit` used to REPLACE the
     document, stripping `rel_path` (a voice note lost its audio link
     on first edit) — now merges text into the head document. Owner
     added the logo (`gilune-logo.png` window icon, `gilune.ico`
     packaged). Suite: ~60 kernel tests + 22 desktop (incl. 1 UI).
     **Open after this pass**: dictation not yet voice-tested end to
     end by a person; imports (front-matter-less files) recorded but
     surfaced nowhere; `- []` without a space is not a task marker
     (matches Obsidian); deleting a note leaves its wav on disk
     unlisted (correct — preserved source); Android bindings STILL
     stale.
   - **Dev-run gotcha (machine note)**: `gradlew :composeApp:run` sets
     `compose.application.resources.dir`, and Main.kt prepends it to
     `jna.library.path` — so dev runs load
     `app/composeApp/resources/windows-x64/gilune_ffi.dll` (the staged
     RELEASE dll), not `kernel/target/debug`. After kernel changes,
     restage that dll or dev runs silently execute stale kernel code
     (cost an hour on 2026-08-31). UI probes: `-Pgilune.vault=<dir>`
     and `-Pgilune.probe=note:<rel>|read:<rel>|settings[:section|
     :plugin:<id>]` open a screen for screenshots; probe windows are
     titled "Gilune probe" — automation must only ever kill PIDs of
     windows with that exact title, never anything named Gilune.
   - **Bug found and fixed 2026-08-31** (owner's vault held two
     recordings but one transcript): `enable_whisper` used to create
     the `transcribe` subscription, so a capture made before the model
     finished loading produced an event with **no job** and was never
     transcribed. Registering the plugin during `Gilune::open` now
     subscribes it before any capture is possible. Captures already
     stranded are recovered deliberately with `backfill_jobs`
     (`gilune-cli backfill`, or Settings ▸ Plugins ▸ Catch up missed
     captures) — proven on a copy of the owner's vault: 1 queued,
     1 output, verify clean.
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
