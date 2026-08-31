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
     tasks panel by id in shell code (debt 10). **Remaining: T4
     (filter-block rendering in notes).**
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
