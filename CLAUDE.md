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
the owner acquires a device**. ADR 0009 (licensing; MIT-only since
2026-09-01) and ADR 0010
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
   - **M5 LAN sync — ADR 0019 ratified 2026-09-02 (build authorised;
     Accepted only at its 16-point gate), staged M5a/M5b/M5c.** Owner
     constraints: transport only (0008's headless agent = later
     round); FULL replica; phone syncs on open + periodic unmetered
     background; pairing = 6-digit SPAKE2 code AND desktop-QR both in
     v0. Design: DNS-SD `_gilune._tcp` (Rust mdns-sd desktop /
     NsdManager adapter Android; cached-address-first, manual
     host:port fallback), SPAKE2→Noise XXpsk0 via snow (both crates
     UNAUDITED — recorded), identity keys OUTSIDE the vault
     (DPAPI/Keystore wrap; vault meta.identity_pub makes re-enrolment
     mechanical), gilune-kernel::operations + gilune-sync crate split
     behind a Transport trait, raw envelope bytes stored for future
     signatures, revocation mid-session at op boundary + third-replica
     refusal by origin device. Gate C needs the owner's real device
     (like MS2). **M5a DONE (2026-09-03, one Opus agent)**: schema v5
     (sync_frontiers, integrity_alerts UNIQUE(kind,subject),
     operations.envelope raw BLOB, events.origin_device,
     blobs.origin, meta.op_seq deleted — MAX(op_seq) per device IS
     the counter), real-v4-binary fixture migration test (worktree
     build; adopted history applies into a fresh replica to the same
     digest); canonical-JSON versioned codec, kinds reconciled ONCE
     (create-source/object→object-created, create-revision→
     object-revised; unrebuildable rows→legacy-operation keeping the
     sequence DENSE); 37 portable vectors in kernel/vectors/
     (valid ones re-encode byte-exact; generator #[ignore]d — format
     changes must be an act); apply = one txn/op, halt kinds
     sync-gap/-corrupt-operation/-unsupported-version/-limit-exceeded
     (alerts raised OUTSIDE the txn; successful apply clears them;
     unknown kind applies-and-forwards, no halt); **add_revision now
     supersedes only the heads it saw and NAMES them in the op**
     (readers take one rule: newest_head_first created_ms then
     revision_id; recent_sources rewritten deterministic; projection
     keeps gilune-conflicted); limits.rs one home (env 2MiB, blob
     512MiB, free-space margin 64MiB via new dep fs4, 10k ops +
     256MiB/session; spike's attacker-sized Vec::with_capacity
     retired — staged length IS the resume point); gilune-sync:
     Transport/Session split (+devices()/have_blob() for the pull
     side; send_envelope returns BlobNeed so the crate never parses
     payloads), loopback = real TCP on a second handle. Gate A green:
     all ten S4 scenarios vs PRODUCTION kernel + reorder/dup/
     restore/offline + vectors + every bound (visible halt, nothing
     past fault, clean resume) + 4 apply crash tests. Suites: cargo
     150/0, desktop 436/0, instrumented 610/0/1(a03); dll restaged,
     both ABIs rebuilt, bindings byte-identical (SyncHalted maps to
     existing Invalid — shells learn of halts from alerts, M5c).
     **M5b DONE (2026-09-03, one Opus agent + two shell sub-agents,
     agent-coordinated)**: identity = X25519 minted on first use,
     device-local {device_id}.identity.json in a shell-nominated root
     (%LOCALAPPDATA%\Gilune\sync / filesDir/sync; Debug withholds the
     private half) — KEYED BY VAULT device_id, an accepted refinement
     recorded in 0019's Amendments (one identity per machine+vault;
     same-machine copy not detected = rule-8-excluded, stated);
     re-minting seeds the old device's frontier or the first session
     with the original halts. KeyWrap = uniffi foreign trait (no
     adapter ⇒ unwrapped WITH the reason in the file + keyProtection
     "none" surfaced): desktop DPAPI user-scope via direct Crypt32
     JNA (UI_FORBIDDEN, LocalFree, round-trip self-test before
     offering; 6 real-adapter tests), Android Keystore AES-256-GCM
     seal (no on-device round-trip test — gap). §4 re-enrolment table
     exact + visible notice. Pairing: SPAKE2 (role identity strings
     listener/joiner so reflection fails) → Noise XXpsk0
     (25519/ChaChaPoly/BLAKE2s; snow trimmed to named ciphers); pair
     secret from the handshake hash (never transmitted); credential
     kind bound into the prologue (QR→code downgrade fails); QR
     payload GILUNE-PAIR|1|secret|fingerprint|host:port (15 malformed
     shapes rejected). Default-deny = psk0 property: listener
     trial-decrypts msg1 against held pair secrets, unpaired callers
     get SILENCE (wire-tap tests: no payload, no static key on the
     wire). M5a framing refactored behind Channel — one serve/one
     exchange loop for loopback AND encrypted. Revocation deletes the
     pair secret; mid-session closes at op boundary; third-replica
     refusal is a kernel apply property via a roster-projected
     refusal list. Rate limits: per-peer doubling backoff; windows
     single-use/3-min/5-attempts-then-abandoned-with-secrets-cleared.
     FFI: the nine 0019 §6 methods + configure_sync (shell owns the
     identity path; syncSetup wired BEFORE openVault in both shells);
     bindings byte-identical AGAIN; dll restaged; ABIs 5.24/5.36 MB.
     Five bugs found by gates/GUI, fixed in code: joiner-paired-
     before-listener-committed (confirmation frame), revocation check
     before the blocking read, source-port-recorded-as-address
     (listening port travels in the encrypted greeting), GUI cleared
     the code before the IO thread read it, peer list never
     refreshed. Gate B 19/19 ×3 runs + LIVE two-OS-process pairing
     (note captured on A projected as Markdown on B) + GUI pair/
     revoke/re-pair + emulator Sync screen with live camera preview.
     Suites: cargo 217/0, desktop 442/0 (+6 DPAPI — the shell had
     shipped untested), instrumented 610/0/1 fresh-boot. Gaps:
     Sync-screen instrumented tests absent; CAMERA dialog never
     watched (installed -g); listener serves one peer; restarted
     device takes a new port (no discovery till M5c); Ctrl+N notes
     don't sync (pre-existing M2 import gap — surface in M5c?).
     Gaps: GiluneStatus lacks blobs_awaiting/alerts (deliberate, no
     FFI change — `gilune-cli alerts` meanwhile, M5c wires shells);
     per-peer establishment rate limits → M5b (no peer identity yet);
     malformed blob declaration files under sync-limit-exceeded
     (imprecise); no duplicate-JSON-key vector; loopback serves one
     connection; per-device sequences only, no causal parents (S4's
     inherited gap). **Next: M5b** (identity, SPAKE2/Noise pairing,
     KeyWrap adapters, roster, revocation, QR, FFI surface) then M5c
     (discovery, firewall UX, live exchange, docs).
     RIG NOTE (2026-09-03): MS1 determinism holds on FRESH boots
     only — after ~90 min of accumulated emulator load
     Ms1ImeSpikeTest degrades (2000 ms teardown assertion, activity
     lifecycle timeout); class alone 4/4, full suite clean after
     emulator restart. Restart the emulator before judging MS1.
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
   - **M6 fourth pass (2026-08-31, agent team round two)** — ADR 0011
     amended (owner): desktop capture bar REMOVED; capture-first is
     Ctrl+N (Untitled note, title focused/selected, Obsidian naming
     via `untitledName`/`createUntitledNote`) + top-bar mic
     (new-note-from-speech; still plugin-gated). Rail has Files/Search
     panels (VSCode shape); **Ctrl+F** = vault search, Ctrl+P =
     quick-open. `Search.kt` (jvmShared): DSL — bare terms AND,
     "phrases", -negation, file:/path:/content:, [key:value]/[key]
     metadata; `SearchOperators` registry is the plugin-extensibility
     seam (proven by a registered tag: operator test); no OR yet.
     Editor: Obsidian-style editable title (rename-on-commit via
     `renameNote`, co-renames attachments; body field carries
     testTag("note-body") so the title keeps real semantics);
     properties dialog restyled to the settings idiom (field borders
     full `outline` per owner feedback); checkbox hover shows a hand
     cursor (`pointerHoverIcon` needs overrideDescendants=true — the
     inner text field's I-beam wins otherwise). Whisper speed
     (measured, release, ~14s clip): default 4 threads → 8 =
     12.2s→7.1s; explicit language skips the detect pass = 7.1s→4.2s;
     n_threads always set, language via transcription plugin settings
     ("Spoken language" field on its plugin screen; default auto,
     takes effect next launch). **ADR 0014 (tabs + split panes)
     ratified as proposed** — stage M (tabs in one pane) is the next
     shell milestone; then M+1 explicit split + layout persistence,
     M+2 drag. Probes gained search:<q> and props:<rel>.
   - **M6 fifth pass (2026-08-31, agent team round three: model →
     two parallel UI agents, Fable coordinating/validating;
     screenshot-validated incl. a persistence relaunch)** — ADR 0014
     stages M AND M+1 shipped; M+2 (drag) stays deferred. Model:
     `Tabs.kt` (desktopMain; leaf type is `TabPane` — `Pane` was taken
     by SettingsPane's nav) holds the pane tree, `TabsState` (open
     dedupe, openToSide MOVES the tab, close refusal, dissolution,
     `label()` stem disambiguation, serialise/restore), and
     `flushEditor` — the guarded write extracted from NoteEditor so
     deactivating a tab flushes through the same path; 23 tests.
     Split-right on a single-tab pane is a no-op with a message (one
     buffer per note makes split a move). UI: `Panes.kt` PaneHost +
     tab strip (dot↔× in one fixed slot, error colour when stale,
     middle-click close, context menu, overflow chevron); the active
     PANE is named by a primary top rule on its front tab, drawn via
     drawBehind because inside horizontalScroll fillMaxWidth is a
     no-op (infinite constraints — cost one screenshot round). Shell:
     Ctrl+Tab/Shift+Tab, Ctrl+W, Ctrl+1…9, Ctrl+\; status line moved
     to the window scaffold; per-vault layout in Prefs
     (`tabs-<pathhash>`, save skipped over 7500 chars); window close
     and vault switch flush every tab then save. Tree: Open to the
     side, rename/delete notify tabs (folder rename walks contained
     notes so their tabs follow). Alignment seam fixed:
     `PANE_HEADER_HEIGHT` (36.dp, Tabs.kt) pins the rail header AND
     each strip so the divider runs unbroken (owner screenshot).
     Probes: `note:` now takes a ;-list, `split:<a>;<b>`, and any
     unknown probe value (e.g. `restore`) opens a probe-titled window
     showing only the persisted layout. **Probe runs persist layout —
     never point one at the owner's vault.** Known consequences:
     "Save now" on a clean buffer writes nothing (single write path);
     search results have no Open to the side yet; suite ~119 desktop
     tests (2 UI) + 23 model.
   - **M+2 drag (2026-08-31, same day: one Opus implementer, Fable
     validating with real scripted mouse drags + screenshots)** — the
     owner waived the ADR's Compose-spike gate and added within-strip
     reorder (both recorded in ADR 0014's Amendments). Model:
     `moveTab(tab, from, to, index)` (same-pane reorder never flushes;
     cross-pane activates the moved tab and flushes the displaced one
     through select's path) and `splitWith(tab, from, target)`
     (splitRight's surgery extracted to `splitOff`; identity drop
     returns false). Gesture in Panes.kt: press still selects, ~6.dp
     slop turns the held press into a drag inside the SAME TabChip
     pointer loop (no competing pointerInput); `TabDrag` holds a
     bounds registry (panes/strips/chips via boundsInRoot) and the
     live session; drop targets in order: strip gap (2dp caret) then
     a pane's outer right quarter (12% primary tint) then nothing.
     Drag image, caret and tint all render in the PaneHost root
     overlay — never laid out inside the horizontalScroll strip
     (infinite constraints). Edge auto-scroll on overflowing strips
     (read-verified only: a test would deadlock waitForIdle). Suite
     137 desktop tests; live validation: cross-pane move, reorder,
     and edge-split each performed with a real mouse drag via
     scratchpad drag.ps1 (SendInput on the "Gilune probe" window
     only) and screenshot-confirmed, incl. mid-gesture affordances.
     Tear-out and the four-edge grid remain out of scope.
   - **Divider resize + collapsible rail (2026-08-31, one Opus
     implementer, Fable validating live)** — ADR 0014 amendment (pane
     weights drag) and ADR 0011 amendment (rail resize/collapse).
     Model: `TabsState.resize(split, divider, delta)` clamps to
     MIN_PANE_WEIGHT 0.15 with partial moves; weights already in the
     layout codec so resizes persist for free. UI: shared
     `DragDivider` in Panes.kt — 1.dp hairline, 9.dp grab band via
     requiredWidth + **zIndex(1f)** (without it PaneView's
     Initial-pass pointerInput eats the overlap), AWT E_RESIZE
     cursor, live resize with a 160.dp px floor. Rail: width
     170..480dp persisted (`Prefs.railWidth`), collapse via top-bar
     Menu toggle / **Ctrl+B** / dragging shut (raw travel <120dp on
     release; reopens live crossing 120dp outward, so the mouse alone
     recovers it); collapse remembers the arranged width; Ctrl+F
     un-collapses then waits two frames before focusing (field
     doesn't exist until the rail composes). Suite 144 desktop tests;
     validated live with scripted drags + keybd_event (scratchpad
     key.ps1): pane resize, rail resize, toggle collapse, relaunch
     persistence (collapsed + weights + width), Ctrl+F reopen with
     typed query landing focused, drag-shut. Gap: stacked splits
     would need a vertical DragDivider twin (no renderer builds them
     yet).
   - **Tasks plugin T1 (2026-08-31, agent team: two researchers → design
     synthesis + mockups → ADR 0015 ratified in stages → four
     implementers; Fable coordinating, live-validating with scripted
     typing)** — ADR 0015 **Accepted**: `due:`/`done:` plain ISO keys on
     task lines only, `/` two-stage caret menu (natural language → ISO
     at insertion, never stored), global filter (default empty = every
     checkbox), generic ` ```filter ` blocks behind a required `type:`
     (T4), `search.types` + type-scoped operators (T2), `tasks.fields`
     as the first plugin-published slot; ADR 0011 amended five→nine
     slots; research notes in `architecture/research/`. T1 SHIPPED:
     kernel `Contribution.config` (manifest JSON — no migration;
     uniffi default "" keeps Kotlin call sites compiling; dll
     restaged); `editor.decorations` seam in MarkdownEditing.kt
     (checkbox = the shell's own first registration, per-match offset
     table, fence gating per-registration with the checkbox opting
     in, activation chain, `decorationAt` replaces checkboxUnder);
     suggest machinery (TaskDates/SuggestSession immutable state
     machine/`replaceRange` — insertAtCaret trims so cannot express
     completions/SuggestPopup non-focusable caret popup); tasks
     plugin wired (declaration `org.memory.tasks`, both-enabled
     tasks.fields gate, filter live without restart, settings screen
     with contribution chips). Suite 242 desktop + 65 cargo. Live
     proof: typed `/due tom` in a probe → `due:2026-09-01` on disk
     via autosave; prose `due:` stays plain; plugin off → tokens
     plain text, bytes unchanged, checkboxes still draw. Known gaps:
     decorations `config` JSON mirrors Kotlin recognisers untested
     (can drift); `done:` styled but stamped only in T2; no
     mouse-click acceptance in the popup; popup highlight not
     scrolled into view; Android bindings pre-existing stale
     (Plugins.kt uncompilable there since M6a). **T2 SHIPPED
     (2026-08-31, two parallel agents + coordinator alignment)**:
     done-stamping rides the activation chain via a registration
     matching the TASK MARKER itself (a line about to be ticked has
     no done: token to host the handler); render-losing registrations
     still activate (pinned by test, no seam change needed);
     `type:`/SearchType registry in Search.kt (line-level =
     narrowed-note evaluation per §4), TaskSearch.kt carries
     `type:task` + due:/done: operators (date/today/overdue/week/
     none/before:/after:, malformed ⇒ empty not prose) + the T4 seam
     (`TaskHit`/`taskRows`, `searchVault(maxLines=)`); panel gate =
     declared `search.types` contribution (coordinator aligned the
     slot constant + declaration after both agents landed). Suite
     ~284. Live-proven: toggle stamped done:2026-08-31 to disk;
     `type:task due:week` in Ctrl+F returned exactly the task lines.
     Gaps: a checkbox quoted in a code fence still stamps (activateLine
     carries no fence context); `today` resolves when the decoration
     registry builds (session over midnight stamps the open day);
     suggest's trailing space + stamp's leading space = double space
     before done:; validation lesson — scratch-vault PLUGIN STATE
     persists (a disabled test plugin stays disabled next probe).
     **T3 SHIPPED (2026-08-31, one agent)**: `RailPanel` is a
     plugin-keyed value (FILES/SEARCH companions); the `navigation`
     slot is real — `navigationPanels(plugins)` reads contributions
     whose config names a panel (nameless ones dropped), the tasks tab
     gated additionally on `tasksPanelEnabled()` (settings key
     "panel", absent=true; one JSON document with the filter,
     read-modify-write both keys); TasksPanel = one
     `searchVault("type:task", maxLines=200)` per vaultTick →
     `taskRows` → pure `agenda()` partition: checked excluded,
     overdue < today, today, today+1..+7 (deliberately NOT the
     due:week window so views never overlap), undated collapsed;
     50-row cap per group with +N more; rows NAVIGATE only. Probe
     `panel:<key>`. Suite 308. Live-proven: four groups with correct
     colours; done task in no view. Gaps: panel staleness = search's
     (vaultTick; buffer edits arrive at next tick, ADR's
     settle-refresh not implemented); rows open the note, not the
     line; tasks due past today+7 appear in no view (bounded by
     design — search answers them); `navigationPanels` knows the
     tasks panel by id in shell code (debt 10). **T4 SHIPPED
     (2026-08-31, one agent) — ADR 0015 delivery complete**:
     FilterBlock.kt (pure: lines joined = the DSL's whitespace-AND;
     classify Empty/NoType/UnknownType/Runnable BEFORE evaluating —
     the hint row never guesses); MarkdownView gains
     `BlockRenderers` (a table keyed by fence info string —
     `filter` is the shell's own and only registration into
     `Slots.EDITOR_BLOCKS`; unknown info strings fall to the code
     box, which is also the removal story); TaskHitRow extracted
     from the panel and shared; Read-mode rows navigate via
     TabsState.open. **DEVIATION, owner-visible: Edit mode ships
     the raw fence, not the ADR's caret-outside overlay** — rows
     don't sit on the text line grid, the composed offset mapping
     threads back through checkbox/hover/popup anchors, and a
     covered block can't be clicked into; pinned by test
     `editModeShowsTheFenceItselfRatherThanTheList`; no evaluation
     runs in Edit mode at all. Suite 341. Live-proven:
     `type:task due:week done:none` rendered exactly its two rows
     in Read mode, the type-less block its hint, Edit mode the raw
     fences with in-fence tokens correctly un-decorated. Gaps: each
     block = one vault pass; rows open the note not the line;
     EDITOR_BLOCKS has one producer (the settings.form lesson).
     **Post-delivery (2026-08-31, owner ask "due before today / not
     done")**: `before:`/`after:` values now take anything
     `resolveDatePhrase` resolves (today/tomorrow/weekday-next),
     resolved at evaluation; new `is:open`/`is:done` operator on
     `type:task` reads the checkbox marker itself (what "not done"
     means; `done:none` stays the date-token question); hint rows
     updated; recorded in 0015's post-delivery amendments alongside
     the T4 edit-mode deviation (pending owner word: accept as final
     or schedule the overlay round). Suite 351. Live-proven:
     `type:task due:before:today is:open` returned exactly the one
     open overdue task. **Owner-reported fix (2026-08-31)**: a
     just-opened note rendered its FIRST task line raw (caret parked
     at offset 0 with no visible caret ⇒ line 0 was "the caret's
     line") and the first click couldn't toggle it. Now
     `caretLine == null` means "no caret — decorate every line" and
     NoteEditor passes null while the body field is unfocused; the
     raw-line exception follows a caret someone can see. Coordinator
     fix; two seam tests rewritten to the new contract; live-proven
     (fresh open shows all glyphs, first click stamps done:).
     **Second owner report same evening**: ticking tasks with the
     agenda open didn't update the panel — vaultTick existed and the
     panel listened, but the editor's own writes never raised it
     (autosave wrote silently; recorded T3 gap). Fix: `write()` calls
     `state.noteSaved()` (vaultTick++) after any successful save, and
     checkbox activation now calls `write()` directly (§ 1
     write-on-action — no 1.2 s autosave wait). Panel, search and
     filter blocks all refresh off the same tick; kernel ingestion
     still waits for sync (display fact, not history). Remaining
     edge: a dirty buffer flushed by tab DEACTIVATION (flushEditor in
     Tabs.kt has no state handle) is still silent until the next
     tick. Live-proven: ticked a task from the open panel — its
     "today" group vanished without reload.
   - **User docs site + README (2026-09-01, one agent + coordinator
     validation via headless Chrome)** — `docs/` is a hand-authored
     static site (no build step; `.nojekyll`; relative links only —
     it serves from https://gilune.github.io/gilune/ once the owner
     flips Pages to main/docs): landing + six guides (notes, capture,
     tasks, search — the canonical user DSL reference, workspace,
     plugins), app-palette styling, 8 cropped probe screenshots +
     512px logo in docs/assets/. README.md rewritten product-first
     (centred logo/name, honest status, verified build-from-source
     block incl. the uniffi-bindgen step, licence preserved). RULE:
     user docs never use internal vocabulary (kernel/slots/ADR/
     seams); when features change, update the relevant guide page
     and reshoot its screenshot via the probe tooling.
   - **Whisper model benchmark + model setting (2026-09-01, owner
     ratified "sounds good")** — measured on this machine (21.5 s TTS
     clip, 8 threads, language set, greedy, throwaway harness in
     %TEMP%\wb): tiny 632 ms / tiny-q5_1 553 ms / base-q5_1 1019 ms /
     base 1190 ms. Quantised ≈ full siblings' accuracy and slightly
     FASTER; base-class fixes ring-main/£/plasterer-class errors but
     regressed "surveyor" on this clip; "Gilune" defeats every model.
     Shipped: model is a transcription-plugin setting (radio column
     beside Spoken language, one settings JSON `{language, model}`,
     kernel reads only `language` so `model` is inert to it);
     catalogue in Plugins.kt (`WHISPER_MODELS`, fastest first),
     **default now base-q5_1** (57 MiB < tiny's 75); per-model files
     in %LOCALAPPDATA%\Gilune\models so switching back is instant;
     absent/unknown key falls back to default. tiny-q5_1 (31 MiB) is
     the pencilled phone default pending MS2. MACHINE NOTES for any
     fresh whisper-rs build (MB0 will hit all of these): scoop CMake
     4.x needs `CMAKE_POLICY_VERSION_MINIMUM=3.5`; its VS-generator
     detection fails against the 2019 Build Tools → import
     vcvars64.bat env + `CMAKE_GENERATOR="NMake Makefiles"`; and
     NMake try-compiles die (U1052 build.make not found) beyond
     ~MAX_PATH — build from a SHORT path, never the scratchpad.
   - **Mobile design ratified + filter blocks render in Edit
     (2026-09-01)** — ADR 0017 (mobile app) **Accepted**: capture-first
     shell, live preview under a no-reflow law, always-glyph
     checkboxes, LOCAL on-device transcription (the coordinator story
     was an M4b build artefact, corrected pre-ratification), staged
     MB0–MB3 behind MS1 (IME) and MS2 (ARM64 whisper) spikes; five
     answers in its Ratification record — note #3: desktop KEEPS
     raw-on-the-caret-line, the shells deliberately differ. Research
     notes in architecture/research/2026-09-01-*. Separately, the
     owner overturned ADR 0015's T4 edit-mode fallback: a `filter`
     block now "behaves like a checkbox — renders until the caret is
     placed within it". Seam grew `EditorBlockDecoration` (block-
     scoped claims collapse a closed fence to reserved lines through
     the one offset table; overlay anchored from getLineTop/Bottom so
     height IS the reserved lines); caret on any fence line reveals
     the whole block; non-row clicks fall through to place the caret
     inside (reveal); rows navigate; edit cap 8 rows + more (Read
     keeps 50); no evaluation while the caret is inside; unterminated
     fences never collapse. Suite **381**. Screen-validated (render +
     click-through reveal). Coordinator confession: broke the
     no-PowerShell-regex rule on decisions/README, mojibaked an
     em-dash, caught it in the diff pre-commit — the rule stands.
     **MB0 DONE (2026-09-01, one agent)**: gilune-ffi cross-compiled
     for arm64-v8a + x86_64 WITH whisper (5.70/5.73 MB per ABI vs the
     15 MB budget; +libc++_shared.so — whisper links the NDK STL);
     incantation is `kernel/scripts/build-android.ps1` (auto-discovers
     SDK/NDK/make/libclang, overrides the stale ANDROID_HOME,
     -NoWhisper/-Abi flags, short build cache C:\gilune-android; the
     ABI bridge toolchain file MUST stay named android.toolchain.cmake
     or cmake-rs stops recognising the cross-build). Four encoded
     traps: no VS/ninja → Unix Makefiles + msys2 make; ANDROID_ABI
     must be set (CMAKE_ANDROID_ARCH_ABI does NOT substitute);
     whisper-rs-sys 0.15 applies HOST-Windows flags to Android
     (/utf-8 suppressed via CMAKE_*_FLAGS, -ladvapi32 stubbed);
     stale CMakeCache pins the wrong ABI (script wipes on mismatch).
     Bindings regenerated from the Android binary came out
     BYTE-IDENTICAL to desktop's — 07-compatibility's target-dependent
     checksum rationale did not hold (rule kept; owner may revisit).
     androidMain compiles with ZERO source changes; instrumented suite
     280/0 on gilune-s1 (jvmSharedTest rides along); desktop 381
     unregressed. Gaps: whisper built+loaded but never EXECUTED on
     Android (arm64 never run at all — MS2); model-cache pathing
     waits for a caller (MB1/MB2); AGP strip warning (no ndkVersion)
     cosmetic. **MB1 DONE (2026-09-01, one agent + coordinator
     emulator validation)**: MainActivity is the ADR shell —
     edge-to-edge, adjustResize, DayNight, composer-first home (IME
     action = newline, commit ✓), feed after first frame, voice rows
     with §4 pending wording ("enabled ≠ able" bug caught by eye),
     idle timer (pure-tested; expiry COMMITS), Capture/Notes nav
     (mode-swapped under IME), notes list + Read view with 48dp
     tap-to-tick (parser now carries source lines — MdBlock.line +
     ListItem.task; desktop Read shows glyphs too, inert), settings
     (vault truth + Export-a-copy zip via SAF + plugins/consent +
     appearance), DataStore prefs, Theme schemes moved to
     jvmShared/Palette.kt (ThemeChoice moved from Prefs.kt;
     secondaryContainer family added — stock-purple nav leak;
     CaptureBar.kt DELETED, one caller), espresso-core 3.7 pinned
     (3.6 reflects on an API-36-removed method). Suites: instrumented
     330/0, desktop 385/0. Coordinator live proof on emulator:
     first-run vault dialog, typed capture → "captured" → feed row.
     ADB lesson: `adb exec-out > file` in PowerShell mangles binary
     — screencap to /sdcard + pull. Gaps: navigation slot unread
     (no Agenda tab yet — MB3), filter fences = code box in mobile
     Read (MB3), transcribing-row over-claim window until
     enableWhisper wires (MB2), tertiary palette family undefined,
     timer lifecycle untested by a real cycle, still x86_64-only.
     **Record widget DONE (closes MB1)**: RemoteViews 4-cell widget
     (Record/New note/Search→Notes-until-MB3/inbox count = Captures
     *.md dir read, refreshed on vaultTick + service broadcast);
     RecordService = mic FGS (manifest+startForeground MICROPHONE
     type; own second Gilune.open — multi-instance is the sanctioned
     shape; recorder thread → 64KiB ChunkedSink → beginCapture,
     header first with 0xFFFFFFFF sizes — a stream can't know its
     length, content-addressed blobs can't be patched; whisper.rs
     reads fixed offsets so safe; device test proved a chunk crossed
     BEFORE stop); notification Stop → "Recorded m:ss · tap to
     open"; New-note QS tile only (mic tile withheld per ADR). API-36
     trap encoded: a FGS that declines via stopSelf() still crashes
     (ForegroundServiceDidNotStartInTime) — refuse() goes foreground
     for the instant it takes to withdraw. Suites: desktop 394/0,
     instrumented 350/0. Gaps: emulator mic = silence (no speech
     through the path; transcription unwired until MB2 — whisper IS
     in the .so, nothing calls enable_whisper yet); lock-survival
     asserted not exercised; Play Console FGS declaration = owner
     action; revoke-between-draw-and-tap window unhandled;
     MediaPlayer playback of 0xFFFFFFFF-size wavs untested; widget
     never placed on a real launcher.
   - **MS1 IME SPIKE DONE (2026-09-01): PASS-WITH-CONDITIONS**
     (spikes/ms1-ime/README.md; 39 tests stay as regression; real
     InputConnection via onCreateInputConnection, NOT
     performTextInput). Mapping held everywhere (straddling regions,
     autocorrect, CJK, surrogates, both caret rules); but Compose's
     LEGACY BasicTextField(TextFieldValue) corrupts unbatched
     composing after a commit (prefixes accumulate; 3 controls put
     the fault in the field, not our decoration; restartInput no
     cure). CONDITIONS: MB2 uses TextFieldState+OutputTransformation
     (retires manual mapping; ADR 0012's invariant becomes a
     type-system fact; undoState free; the IME sees DECORATED text so
     glyphs stay delimited); no length-delta decoration inside a
     word; physical-device matrix ASSUMED. Latent: the DESKTOP editor
     sits on the same legacy field — AWT/CJK composing untested
     there.
   - **MB2 DONE (2026-09-01, one agent + coordinator on-device
     validation)**: Android live-preview editor on
     TextFieldState+OutputTransformation per MS1. KEY CLASSPATH
     FINDING: Foundation 1.7.6's TextFieldBuffer has NO styling API —
     so the transformation changes ONLY the checkbox bytes (caret
     never consulted; offset table shape-stable) and styling is a
     sibling BasicText painting METRIC-NEUTRAL spans over a
     transparent-text field (colour/underline/strike only; layout
     agreement asserted by test at every line top and wrap point).
     COST RECORDED: heading size / bold weight / italic slant
     unrendered in Edit until a toolkit bump (Read renders all
     three; headings take the accent colour). Checkbox tap = 48dp
     boxes from the field's own layout, consumed on Initial pass
     only inside a box, selection restored, haptic, immediate write.
     mobileEditorDecorations() enforces MS1's whitespace-delimited
     rule over the whole registry (TASK_MARKER alone would let
     `- [ ]milk` glyph inside a word). Toolbar row + 232dp panel via
     pure BufferEdits through state.edit{} (whole-buffer rewrites
     would collapse undo + restart composing); / drives the shared
     SuggestSession by typing the trigger; undo = undoState, proven
     against a real InputConnection commit. Suites: instrumented
     425/0, desktop 394/0. Coordinator drove it live: capture →
     note → Read glyphs → Edit with styled tokens + toolbar. Gaps:
     glyph long-press eats platform selection start; title rename
     loses on back-without-commit (desktop parity); row scrolls
     under ~384dp width; perf unmeasured on long notes.
   - **Mobile transcription + dictation DONE (2026-09-01)** —
     **WHISPER'S FIRST ANDROID EXECUTION**: 2 s silence → transcript
     in 5.34–5.57 s (x86_64 emulator, tiny-q5_1, 3 runs, outputs=1
     failed=0; NOT a phone number — MS2 stands). Phone default
     tiny-q5_1 implemented at the READ SITE (`transcriptionModelKey()`
     returns the unresolved key — the only shared change, pinned from
     both sides so editing the shared default fails tests). One
     enable/download call site (LaunchedEffect keyed
     state|granted|settings; consent = POSITIVE grant — empty lists
     fail toward not fetching; .part rename; a second racing call
     site in openVault was removed). Pending states:
     `TranscriptionPhase`, only Ready earns "transcribing…"; third
     wording "downloading the transcription model…" added beyond §4's
     two (an in-flight download must not read as "needs"). Dictation
     = note.actions row gated contribution AND Ready; minimal
     BufferEdit at the caret; blank transcripts write nothing.
     WIDGET GAP FOUND: RecordService never ran processing — now
     enableTranscriptionForService + runProcessing + re-project on
     its own handle (enable_whisper is PER-HANDLE, kernel-confirmed;
     service never downloads — a home-screen tap is no place for
     consent; idempotent jobs make double-processing safe); ON_START
     refresh so widget notes stop reading "transcribing…" forever.
     INTERNET added to the manifest (S5/Play surface, like the mic
     FGS type). Suites: instrumented 454/0/0-skipped (the whisper
     test genuinely ran), desktop 394/0. Gaps: download flow never
     exercised end-to-end (model seeded via adb); **[BLANK_AUDIO]
     wart — whisper's silence marker lands as a real note body
     (desktop shares this; owner decision pending)**; runtime not
     persisted across process death; widget pays a model load per
     recording.
   - **MB3 DONE (2026-09-01)** — the ratified staging is complete.
     Search tab (panel DSL, live operators/types, empty-query hint);
     Agenda via the NAVIGATION SLOT as data (shell three + take(2)
     contributed; appears/leaves with the tasks plugin + its setting;
     vanished selection resolves to the shell tab as a VALUE, no
     write-back; M3 nav merges icon descriptions when labelled —
     tests address by label); agenda rows TOGGLE on mobile (§8; the
     desktop panel deliberately doesn't — both sides recorded in
     AgendaScreen) via toggleTaskLine guarded on exact line text;
     agenda() moved to jvmShared/Agenda.kt (pure, zero desktop
     change — TasksPanelTest untouched green); filter fences render
     in mobile Read; widget Search cell routed; launcher shortcuts
     (static XML, widget drawables not adaptive icons). Suites:
     desktop 394/0; instrumented 482/1 — the 1 is an MS1 CONTROL
     (no transformation) flaking on cold `-no-snapshot` AVD boots
     (3 runs failed 6/3/1 different members; the spike's 5 green
     runs were warm-snapshot). RIG NOTE: boot the AVD from snapshot
     for MS1 runs. Found in passing: READ-VIEW TICKING doesn't stamp
     done: (builtInDecorations only, contradicting VaultFiles' own
     comment) — folded into the Enter-continuation round. Search is
     per-keystroke unindexed on mobile (inherited); no OR
     (inherited).
   - **Enter continues lists (both editors) + Read-stamp fix
     (2026-09-01, owner rule)** — shared pure
     `listContinuation(text, caretAfterNewline)` in MarkdownEditing
     (reads the buffer AFTER the newline so mid-item Return falls
     out: split text lands behind the fresh marker, Obsidian-style;
     "empty item" judged over marker remainder + carried text;
     checked→unchecked; indentation preserved; fences and a live
     suggest session excluded; empty item UNWRAPS incl. the typed
     newline — CM6 behaviour, pinned). Desktop hooks onValueChange
     (IME-safe; suggest wins structurally since preview-Enter
     starves the field); mobile is an InputTransformation editing
     the incoming change (soft Return is a committed newline, not a
     key; MS1's no-whole-rewrite honoured; ONE undo removes
     newline+marker, device-proven). Read-view tick now stamps done:
     (call-site bug — ReadScreen took the default chain). Suites:
     desktop 420/0; instrumented 508/3 — the 3 are MS1 rig flakes,
     now known non-deterministic EVEN FROM SNAPSHOT (different
     members per run; rig-stabilisation spawned as a background
     task). Not continued: ordered lists, blockquotes,
     outdent-before-clear on indented empties; a batched
     autocorrect+Return single commit won't continue (safe default,
     un-tested against real Gboard).
   - **Moonshine arc R0–R3 DONE (2026-09-02, four sequential Opus
     agents, Fable coordinating; ADR 0018 Accepted with six
     consequence amendments; research
     architecture/research/2026-09-02-moonshine.md)** — realtime
     transcription per the owner's ask ("realtime on the phone will
     just feel better"). **R0 spike PASS-WITH-CONDITIONS**
     (spikes/r0-moonshine/): AAR ai.moonshine:moonshine-voice:0.1.5,
     natives 16.70 MiB/arm64, model 45.23 MB / 8 files crc32c from
     download.moonshine.ai, TTFT 649 ms at transcription_interval
     0.25 s (condition), partials rewrite MID-PHRASE, silence emits
     ZERO events (no [BLANK_AUDIO] analogue — so clean_transcript is
     deliberately NOT ported to the provider); transitive-dep
     exclusions are a DEFECT FIX (androidx.startup
     InitializationProvider crashes isolated process starts).
     **R1 (caption, 57d0959)**: org.memory.moonshine declared in
     Plugins.kt, registered by the ANDROID shell only;
     network.request-consented download is OURS (okhttp excluded —
     vendor AssetDownloader unreachable; manifest checksums are
     BASE64 crc32c decoded big-endian; java.util.zip.CRC32C is API 34
     vs minSdk 29 so the CRC is hand-rolled; .part→verify→rename,
     .ready last; Wi-Fi-only default); caption = fixed 68 dp block
     above the composer reserved by the record tap, bottom-anchored,
     settled lines onSurface + ENTIRE revisable region muted, fed by
     a throw-guarded tee on the recorder loop (capture writes first)
     into a bounded-queue session thread; display-only tested (the
     recording commits identically with the caption on/off/crashing);
     abiFilters arm64-v8a+x86_64 (the AAR ships armeabi-v7a);
     first words 1.3–1.9 s (model loads while recording runs —
     recording never waits). **R2 (FFI route, 67023e1; discharges
     ADR 0015 debt 7)**: register_external_processor / claim_job /
     submit_output / fail_job on the uniffi surface; ClaimedJob is a
     kernel-OWNED object (unconstructable by callers — not a write
     door); whisper's run-record→commit_outputs→complete_job became
     the shared Store::deliver_outputs + one public idempotency_key;
     blob crosses streamed 64 KiB, digest-verified twice (whole file
     before the first chunk, running hash on the same handle);
     NO schema migration (lease/processing_runs/blob facts suffice;
     registration is per-handle per-process, deliberately not a row);
     run loop EXCLUDES registered external ids (else it burns their
     leases — pinned both directions); stale claimant's late
     fail cannot resurrect a landed job; crash points as real process
     aborts in gilune-cli/tests/external_route.rs; contracts/processor
     records the third transport. cargo 92, bindings byte-identical
     across targets again. **R3 (provider)**: declaration grew
     processorId moonshine-transcribe (distinct from whisper's
     `transcribe` — shared id = coupled disable + unanswerable
     provenance) + blobs.read/objects.write; MoonshineProvider.kt
     claims→streams→fresh-decodes the COMMITTED blob→submits
     whisper's document shape, on BOTH handles (MainActivity and
     RecordService — the widget gets words now); the
     GiluneState.externalProcessing seam runs after every
     runProcessing call site, null on desktop (unchanged by
     construction); ONE ACTIVE TRANSCRIBER is host policy in
     Plugins.kt (TRANSCRIPTION_PROVIDERS precedence: whisper
     incumbent; providerSwitch disables the displaced side and says
     so in words, providerSwitchWarning says it BEFORE the flip;
     both-enabled via CLI ⇒ whisper wins, moonshine claims nothing);
     catch-up: queue stays blanket, the WRITING is wordless-scoped in
     the provider (worded subject ⇒ complete with no outputs) —
     consequence: those runs are recorded so moonshine can never
     later re-decode them; grown-declaration consent =
     newlyDeclaredLocalCapabilities (never offered ⇒ granted; never
     re-grants after revoke, pinned). Real-engine proof on emulator:
     committed wav → all three sentences in the note. Suites: desktop
     436, instrumented 610 (2 = the known MS1 rig flakes, 1 skip =
     R0's a03). Open after the arc: hybrid caption+whisper-words is
     UNREACHABLE (exclusivity; matches §2's one-engine rule — owner
     eye flagged); packaged mobile default stays whisper until MS2
     (moonshine opt-in); two models briefly co-resident (~57 MB each,
     unmeasured); dictation-mic has no caption (no-reflow reasoning
     fails over a live buffer); docs site has no mobile/caption
     guide; MS2 owns ARM64/battery/thermals/"Gilune"-vocabulary.
   - **MS1 rig stabilised (2026-09-02, one Opus agent, owner-started
     chip; coordinator decision (a) ratified in-flight)** — the flakes
     were never establishment timing: the emulator's bound LatinIME
     calls finishComposingText UNPROMPTED 150–860 ms after every edit
     on the LEGACY Compose field (a platform EditText under the
     identical rig takes zero interjections), and waitForIdle only
     waits for our process — every composing assertion raced another
     process, and the spike's original 5 green runs were luck. Fix:
     `Ms1InputSessionRule` disables device IMEs per row and restores
     in finally + a JVM shutdown hook (chained OUTSIDE the Compose
     rule — bare @Rule ordering is undefined); rows whose subject IS
     the keyboard opt back in via @WithSystemKeyboard and await the
     teardown as an event (bounded poll, no blind sleeps). The two
     old defect rows were CONTRADICTORY under any deterministic rig
     (region asserted open AND absent either side of the same ~260 ms
     teardown) and were rewritten to pin the REAL mechanism — field
     opens the region, keyboard tears it down, next span appends =
     bytes the user did not type; falsification proven (annotation
     stripped ⇒ all 3 mechanism rows fail). MS1 verdict + all four
     conditions UNCHANGED (condition 1 reinforced; TextFieldState
     unflaky 8/8 in diagnosis). Re-proof: 39/39 ×6 cold-no-snapshot
     + ×6 snapshot; full suite 610 = 609/0/1(a03 skip) from BOTH boot
     types — zero flakes anywhere. README keeps the superseded
     account verbatim. New honest gaps: one keyboard measured
     (Gboard); 36 rows now run keyboard-UNBOUND; whether
     TextFieldState suffers the same teardown is unasserted (wants
     its own @WithSystemKeyboard row).
   - **Team-coordination lesson (2026-08-31)**: agents verify against
     the whole tree — a coordinator edit that breaks compilation
     (Main.kt File-vs-String) cost one agent ~40 minutes of retry
     loops. Keep the shared tree compiling at every moment agents are
     live, or gate coordinator edits until they finish.
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
