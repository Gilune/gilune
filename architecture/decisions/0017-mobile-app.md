# ADR 0017: The Mobile App

Status: **Accepted** (owner ratification 2026-09-01 — all five open
questions answered; see Ratification record)  
Validation: two spikes in § 8. The live-preview editor is gated on **MS1**, the
IME spike — composing regions and autocorrect over the shared offset mapping —
exactly as ADR 0012 gated E3. On-device transcription's *enablement* is gated
on **MS2**, whisper measured on ARM64 hardware. The shape decisions are product
decisions, ratified by the owner rather than spiked. ADR 0001's device budgets
remain assumed until hardware exists, and no Android figure in this repo has
ever come off ARM64.

## Context

The phone is where the product's P0 lives: tap → speak → done. The phone app
does not do it. It is two Kotlin files — a text box, a mic and a flat list
(`MainActivity.kt`, 59 lines) — and it has not compiled since M6a: the Android
UniFFI bindings are stale (21 exported functions against desktop's 33, twelve
methods and three types missing), and because `androidMain` compiles
`src/jvmShared/kotlin`, the shared set's own imports fail against them.

The evidence for this round is two research notes, kept like a spike report:
`architecture/research/2026-09-01-mobile-prior-art.md` (the survey) and
`architecture/research/2026-09-01-mobile-constraints.md` (the tree, with
file:line). Between them they settle the shape of this decision:

- Mobile inherits far more than the ADRs imply. Every shared file is already on
  the Android compile path, and the pure machines — search, task recognisers,
  date resolution, filter blocks, `replaceRange` — are Android-safe as written.
- It inherits nothing executable. The toolchain is unbuilt, the vault is
  app-private, dark mode is broken twice over, and there is no UI-test net.
- The incumbent has conceded the capture argument. Obsidian's own users report
  4–15 s cold starts and fall back to Keep; its 1.11 answer was to move capture
  out of the app into widgets and lock-screen actions (prior art § 1.7, § 1.8).
- The most-cited live-preview defect is not that syntax appears but that the
  layout moves when it does: "the lines shift and the cursor jumps" (prior art
  § 1.2). Under a thumb that is fatal.

The owner's direction, ratified: design the whole app, editor deepest; editing
is live preview plus a formatting toolbar; checkboxes just look like
checkboxes; the look inherits the desktop family.

## Decision

### 1. The app shape: the first frame is a capture

- **Home is capture.** The app opens on a capture surface: a composer pinned
  above the keyboard with the voice action beside it, the recent-capture feed
  above. This is ADR 0011's Android region honoured literally — its amendment
  pins the capture bar as the Android surface — and reshaped for touch.
  `CaptureBar.kt` has exactly one caller, `MainActivity.kt:49`, so reshaping it
  carries zero desktop risk. Multi-line replaces `singleLine = true`; the IME's
  action key inserts a newline; commit is a button, never Enter.
- **Nothing else is on the path to the first frame.** No vault scan, no search
  index, no plugin enumeration, no list. The feed loads after the composer is
  typable. Obsidian's diagnosis is that the *workspace* restore dominates
  startup, not file count (prior art § 1.7) — so the mobile shell restores no
  workspace at all.
- **Returning is governed by one number, and expiry commits.** Drafts' *New
  Draft After* is the mechanism worth stealing (prior art § 3.1): one idle timer
  decides whether returning resumes your half-written thought or starts a fresh
  one. Gilune inverts its ending — an expired composer holding text **commits
  it as a capture** and opens a fresh one. Nothing is ever discarded (rule 1),
  and `Captures/` is already the inbox ADR 0011 declined to model. Default five
  minutes; settable, including Never.
- **Navigation is a bottom bar of three shell destinations** — Capture, Notes,
  Search — plus **at most two plugin-contributed destinations** from the
  `navigation` slot; Agenda is the first, contributed by the tasks plugin, and
  it leaves the bar when that plugin does. Android's guidance is a bar for 3–5
  same-level destinations, a drawer only beyond that. Overflow renders nowhere:
  a bar does not scroll. Settings is a gear in the top bar.
- **The bottom is mode-swapped, never stacked** (prior art § 5.6, § 1.6): the
  nav bar while reading and listing, the editor toolbar while a note is in Edit
  mode. Leave a margin above the system gesture area
  (`navigationBarsPadding()`), and put no destructive control at the very edge.
- **The mic lives in the capture bar**, drawn only when a plugin contributes
  `capture.actions` — which the shared code already gates
  (`CaptureBar.kt:66-67`). The desktop's top-bar mic is not mirrored; that
  shape belongs to the desktop's capture-by-gesture amendment.
- **The capture path that skips the app is a home-screen widget** (§ 4), with
  launcher shortcuts for Record and New note.

### 2. The editor

Live preview, under one law.

#### The no-reflow law

**Revealing a marker may change its colour and never the layout.** No line
rewraps, no glyph moves and no caret jumps because the caret arrived. This is
iA Writer's discipline — markers are kept, dimmed and styled, so nothing ever
moves (prior art § 2.2) — applied to Obsidian's caret rule rather than instead
of it.

Concretely, every rendering the mobile editor performs is **caret-invariant in
geometry**:

- Emphasis, code and link markers (`**`, `*`, `_`, `` ` ``, `~~`, `[`, `]`,
  `(`, `)`) are **always present at their own width**, in the muted role. They
  are not deleted off the caret's line and re-inserted on it.
- Block markers (`#`, `-`, `*`, `+`, `>`) are likewise always present and
  dimmed. Heading lines are always at heading size, whether or not the caret is
  in them, so type size never changes under the thumb either.
- **Reveal is a contrast change.** Off the point of editing the markers sit at
  the muted role; on it they take full `onSurfaceVariant` contrast. Dimmed to
  bright, never absent to present.

The mechanical consequence is the reason to choose this: with one length-delta
decoration in the buffer (the checkbox, which never changes with the caret),
the offset mapping **does not change shape as the caret moves**. That churn is
precisely what an IME sees, so the no-reflow law is also the largest single
reduction in the risk MS1 exists to measure.

#### Always rendered, versus revealed at the caret

| Construct | Mobile behaviour |
| --- | --- |
| Task checkbox `[ ]` / `[x]` | **Always the glyph** ☐ / ☑, at the caret too |
| `due:` / `done:` tokens (ADR 0015) | Always styled; zero-delta, so nothing to reveal |
| Heading text, bold, italic, strike, code | Always styled |
| Their markers `#`, `**`, `_`, `` ` ``, `~~` | Always present, muted → revealed by contrast |
| List bullets, blockquote marks | Always present, muted → revealed by contrast |
| Link `[text](url)` | Always styled, both halves visible; never a tap target |
| Tables, images, embeds, `filter` blocks | Raw text in Edit; rendered in Read only |

The last row is a Compose fact, not a preference: no `BasicTextField` overload
accepts inline content (prior art § 7), so richness inside an editable buffer
is styling plus glyph substitution and nothing else.

**The checkbox is the owner's rule and it is also the fix for a known trap.**
The shared transformation filters the caret's line out before consulting any
registration (`MarkdownEditing.kt:288`), so today entering a task line reverts
its glyph to `[ ]` — and on touch, tapping the glyph *is* how a caret gets
there, so the target vanishes under the finger that aimed at it. Always-glyph
designs the trap out. The seam gains a per-registration flag beside
`firesInsideCodeFences` — the checkbox opts in, everything else stays as it is,
and desktop behaviour is unchanged until the owner says otherwise (open
question 3).

#### Reveal scope: line for v1, element as the aspiration

The shared machine is **line-scoped**: `decorate` skips `caretLine` entirely.
Element-scoped reveal — brightening only the `**bold**` span the caret sits
inside — is the better reading of "near the point of editing", and it is
reachable: `styleMarkdown` would take an optional caret *offset* rather than a
line index. **v1 ships line-scoped.** Line-scope costs one line's markers being
brighter than they need to be — a typographic quibble, not a defect, since
nothing moves either way — and element-scope is a refinement that can land
after the editor exists without touching the offset table.

#### The checkbox tap

A tap on an interactive glyph inside an editable buffer must answer two
questions, and getting the second wrong is what users report as a bug — "the
cursor jumps to the previous position in the note" (prior art § 1.3).

- **A tap inside the glyph's hit box toggles the checkbox. The caret does not
  move.** If the field was unfocused it stays unfocused and the keyboard does
  not appear: ticking a box is not editing.
- **A tap anywhere else places the caret and toggles nothing.**
- Confirmation is **haptic**, since there is no hover and no cursor to change
  (Obsidian added exactly this in 1.11).
- **Long-press and drag are the platform's**: selection passes through a
  checkbox, which never intercepts — the gesture-arbitration answer the
  research demands of every inline tap target (prior art § 5.4).
- The hit box is a **48dp square centred on the glyph**, arbitrated above the
  text field so the toggle consumes the tap first. Android's figure is 48dp
  with 8dp separation; a glyph at body size is far under it (constraints T4).
- **The checkbox glyph is the only inline tap target in the editor.** Links are
  styled, not tappable, in Edit: Obsidian's rendered links swallow single taps
  and are edited only through modifier chords a phone does not have (prior art
  § 1.4, § 8). Following a link is a Read-view tap and a long-press menu item.

#### Type, targets and insets

- **Body 16sp with 24sp line height**, labels 14sp, minimum touch target 48dp.
  The desktop's `Compact` typography (13–14sp body, 13sp labels) is **not**
  inherited: it is deliberate desktop density, and shipping it on a phone would
  be a legibility and touch-target regression.
- **The palette is inherited whole.** `Theme.kt`'s `Light` and `Dark` schemes
  are pure Material 3 and portable as they stand; they move to the shared set
  and mobile supplies its own `Typography`. That split is the honest form of
  "the desktop look-and-feel". The manifest's hard-coded
  `Theme.Material.Light.NoActionBar` goes, and dark mode follows the system.
- **Dynamic colour is declined**: it would repaint the accent from the user's
  wallpaper and dissolve the one thing making the phone app recognisably the
  same product (prior art § 5.8).
- **Rendered heading size is capped at about 1.4× body.** Android's magnifier
  refuses to appear when the font is too large — the loupe gives up exactly
  where a styled Markdown editor is hardest to aim at (prior art § 5.2).
- The manifest gains `windowSoftInputMode=adjustResize`, the window goes
  edge-to-edge (`setDecorFitsSystemWindows(false)`), and every bottom-anchored
  surface uses `Modifier.imePadding()` with `navigationBarsPadding()`.
  Without edge-to-edge the IME insets never arrive at all (constraints T8).

### 3. The toolbar

- **A slim accessory row anchored above the keyboard**, holding checkbox,
  bold, italic, list, `/` (the tasks field menu), undo, then **more** and
  **dismiss keyboard**. Actions show a glyph and act on the selection or the
  caret's line.
- **`more` expands into a panel that replaces the keyboard** — Bear's shape
  (prior art § 2.1) — holding everything else at full touch-target size:
  headings, quote, code, link, strikethrough, indent and outdent, move line up
  and down, redo. **No command is reachable only by scrolling the row.**
  Obsidian's single swipeable row hides commands users never discover, which is
  why three separate plugins exist to add rows to it (prior art § 1.5).
- **The toolbar outlives the keyboard.** While a note is in Edit mode the row
  stays anchored at the bottom with the IME dismissed. The top-voted Obsidian
  toolbar complaint is that there is no way to hide one without the other.
- **The `/` suggester is not caret-anchored on mobile.** `Suggest.kt` and
  `SuggestPopup.kt` are both portable, but the keyboard owns the bottom half of
  the screen and the caret sits just above it. Candidates render **directly
  above the accessory row**, ↑/↓ replaced by tapping. The pure session machine
  is shared and free; IME-inset placement is the real work.
- **Every formatting action is a composition over shared primitives** —
  `replaceRange` (`Suggest.kt:307`, already tested for caret placement and
  exactness at both ends), `insertAtCaret`, `toggleCheckbox`, `activateLine`.
  Nothing writes bytes except through those, so ADR 0012's invariant is held by
  the same functions the desktop holds it with.
- **Dictation is a note action, not a row slot.** The editor's mic sits in the
  note action row above the buffer, where the desktop puts it and where
  `note.actions` already gates it — so removing the transcription plugin
  removes the button rather than leaving a dead key in a fixed row. It records
  an attached capture and inserts the words at the caret (§ 4).
- The row's contents are **fixed in v1**. A user-configurable toolbar is
  Obsidian's answer to having too few slots; the panel removes the pressure.

### 4. Capture and transcription: local, on the phone too

- **Recording works offline, forever, with no account, model or network** —
  rule 1. Commit is the kernel's durable commit; the shipped budget is
  ADR 0001's < 200 ms.
- **Transcription runs on the device.** Whisper's desktop-only scoping is an
  M4b build artefact — a cargo feature carrying a Windows DLL — not a product
  decision, and this ADR does not inherit it as one. The processor runs
  on-device through **the same kernel job loop, plugin registration, consent
  and settings the desktop uses**: same narrow `ProcessorCtx`, same
  at-least-once delivery, same derived output IDs, same revise-subject landing
  in the capture note (ADR 0013). Not a second pipeline — the same one, on a
  smaller CPU.
- **The engineering facts.** whisper.cpp is ARM64/NEON-capable and the tiny
  model is the shipped default. Desktop measures **~4.2 s for a ~14 s clip** at
  eight threads with the language set; a mid-range phone is plausibly **2–4×
  slower**. Acceptable, because transcription is a background job and **never
  on the capture path**: the recording is committed and durable before any
  processor is asked anything, so rule 1 is untouched. Threads respect the
  device — `min(cores, 4–6)` — with tuning left to MS2.
- **Consent and the model are the plugin's.** The transcription plugin appears
  in mobile settings like any other (§ 7), and the **~75 MiB model download is
  gated by the same `network.request` consent** the desktop asks for. Nothing
  downloads because the app opened.
- **A capture made before consent, or before the model lands, is not lost.**
  Jobs queued while it downloads wait; captures made while the plugin was off
  queued nothing and are recovered by the deliberate **backfill** action — the
  stranded-capture story the desktop has already proved on a real vault.
- **The pending state is shown and is never written to the file.** The row and
  the note header render kernel state — *recorded · 0:42 · transcribing…* while
  the job runs, *recorded · 0:42 · needs the transcription model* before
  consent. Otter's honesty is the model: the user always knows which state they
  are in (prior art § 6.2). ADR 0013 puts the transcript in the body, so the
  body is empty until the job lands; **the UI fills that gap, the bytes do
  not**.
- **Audio plays in the app**: the recording is the preserved source and stays
  useful whatever the transcript says.
- **Dictation at the cursor returns.** With a local transcriber `object_text`
  is real, so ADR 0013's second verb works as designed: the editor mic takes an
  attached capture, the kernel transcribes it, the words land at the caret as
  an ordinary edit. The capture-bar mic keeps the other verb, a new note from
  speech.
- **A low-end device may still prefer the other route** — disable the plugin
  and let a synced desktop transcribe once M5 exists. Sync is the fallback,
  never the plan.
- **The widget is the capture path that skips the app, and it is the only
  legal one.** A home-screen widget tap is a verbatim exemption permitting a
  microphone foreground service to start; nothing Gilune can do from the
  background may open the mic (prior art § 6.1). The shape:
  - Widget cells: **Record**, **New note**, **Search**, and an inbox count.
  - Record fires a `PendingIntent` into a service with
    `android:foregroundServiceType="microphone"`, holding
    `FOREGROUND_SERVICE_MICROPHONE` and `RECORD_AUDIO`, passing
    `FOREGROUND_SERVICE_TYPE_MICROPHONE` to `startForeground()`, with the FGS
    type declared in Play Console.
  - Once running it survives lock and screen-off; its mandatory notification
    carries **Stop**, and tapping it opens the note.
  - The service streams PCM into the kernel chunk-by-chunk through the existing
    streamed-capture FFI, so a process kill loses only the tail.
  - **Refused, deliberately**: any scheduled, boot-started or otherwise
    background-initiated recording — not merely against policy, but a case
    where the permission check lies in the background, returning GRANTED where
    service creation will throw.
  - **A Quick Settings tile is not a documented exemption.** v1 ships a tile
    for *New note* only; a mic tile waits on a device test.

### 5. The vault on the phone

**v1 keeps the vault in app-private internal storage**, where
`MainActivity.kt:41` already puts it, with the consequences stated rather than
inherited by accident:

- Obsidian and every file manager cannot see it, and **uninstalling wipes it**.
- Until M5, sync is not a convenience: it is the only way the *files* leave the
  phone. So v1 ships **Export a copy** in Settings ▸ Vault — a zip written
  through the system file picker. A one-way export of bytes the shell already
  reads needs no kernel change, and nothing is ever trapped.
- First run says this plainly rather than letting the user infer the desktop
  promise.

The alternatives are below; the short reason is that the user-visible-storage
answer is **kernel work, not shell work** — the kernel opens vaults by absolute
path and does `File` I/O beneath it, while SAF hands out a `content://` URI.
That is its own ADR and its own boundary spike, and it is in no milestone.

### 6. What mobile does not get, and what it inherits

**Refused, with reasons.** Tabs and split panes — ADR 0014 § Mobile is
explicit, and the shared set must not learn what a tab is. The block model —
refused on mechanism, not taste: Compose text fields cannot host inline
composables, so a block editor means abandoning the single buffer that makes
ADR 0012 true, and Notion's own docs contradict themselves about whether a
selection may span blocks (prior art § 4). Globally hidden syntax — the calmest
reading surface in the survey and the least honest about structure; the owner
chose live preview. Hover affordances — there is no hover on touch, so
`pointerHoverIcon` and tooltips have no mobile equivalent. A command palette —
the Search destination replaces it.

**Inherited free**, all already on the Android compile path: `Search.kt` and
the `SearchOperators` registry with the whole DSL, `TaskSearch.kt`,
`TaskDates.kt`, `FilterBlock.kt` (read-only in Read view), `Suggest.kt`,
`MarkdownEditing.kt`, `Markdown.kt`/`MarkdownView.kt`, `Plugins.kt`,
`Editing.kt`, `Audio.kt`, `GiluneState.kt`. **Promoted alone**: `EditorState`,
which ADR 0014 § Mobile sanctions by name — 65 lines whose only desktop
coupling is a `Prefs`-seeded boolean. `Prefs` does not port
(`java.util.prefs` is unusable on Android), so device-local facts — vault path,
theme, Read/Edit default, the idle timer — go to DataStore.

### 7. Plugin surfaces on mobile

Settings mirrors the desktop shape the owner asked for: a manager list with
switches, one entry per plugin opening its own screen showing class and
profile, per-permission Allow/Revoke rows, events, contributions, and Remove —
rendered from the shared registry, so the plugin list is data on both platforms
and never a hard-coded set. The host consent policy is the desktop's: a bundled
plugin's local capabilities are granted once on first install,
`network.request` always waits for an explicit yes — which is what gates the
transcription model's download (§ 4) — and nothing re-grants after a revoke or
a removal. The mobile shell renders `capture.actions`, `note.actions`,
`navigation` (capped at two), `settings.form`, `editor.decorations`,
`editor.suggest`, `editor.blocks` (read-only) and `search.types`;
`command.palette` renders nowhere, which ADR 0011's "renders nothing while
empty" already permits. Third-party UI stays declarative slots the shell draws
(ADR 0006), and mobile-store builds execute only bundled plugins.

### 8. Spikes and staging

**MS1 — the IME spike, gating MB2.** The highest-risk inherited component is
`DecorationOffsetMapping` under a real IME: a composing span straddling a
`[ ]`→`☐` replacement is untested, and `VisualTransformation` has a documented
history of IME bugs (wrong caret after backspace; duplicate insertion). The
spike runs an **IME matrix on a physical device** — Gboard, Samsung Keyboard,
one aggressively autocorrecting IME, one with a composing region — and answers
three questions: does the mapping hold; should the editor move to
`TextFieldState` with `outputTransformation`, which drops manual offset mapping
and holds "styling never changes bytes" in the API itself; and where does undo
come from. Pass = no byte the user did not type, caret stable across composing
commits, backspace correct at a decoration boundary, no duplicate insertion.

**MS2 — whisper on ARM64, gating the transcription plugin's mobile
enablement.** Every Android figure in this repo came off an x86_64 emulator and
the arm64 `.so` has never executed, so phone throughput is a guess. The spike
measures it on the owner's physical device — which does not yet exist — over a
fixed clip set at the tiny model, sweeping thread counts, recording battery and
thermal behaviour beside the seconds. **It gates enablement, not capture**: a
failing number turns a plugin off, and the recording is still committed and
preserved with the processor never consulted (rule 1). ADR 0001's pattern
applies verbatim — the numbers are **assumed until the device arrives**, and
this ADR may be accepted on that basis and revisited if they fail.

**Staging**, each stage shippable alone and in order:

- **MB0 — toolchain.** `gilune-ffi` built for `aarch64` and `x86_64` Android,
  bindings regenerated **from the Android binary** (UniFFI checksums are
  target-dependent; `architecture/07-compatibility.md`), the incantation
  committed as a script rather than left in a spike README, `androidMain`
  compiling, the M3 instrumented boundary suite green again, and a Compose UI
  test dependency added — Android has no UI-test net today. **Plus whisper in
  the NDK build**: the C++ engine cross-compiled through cmake into the same
  `.so` for both ABIs, `whisper-rs`'s bindgen pointed at the NDK's libclang,
  the feature enabled deliberately for Android rather than left off by
  convention, and the model cache pathed to app-private storage. That is real
  toolchain cost and the stage's largest unknown — budget beyond the day the
  shell half needs, and nothing else can start.
- **MB1 — the capture-first shell.** Home composer and feed, bottom nav,
  inherited palette with mobile type, dark mode fixed at both ends, notes list,
  Read view with rendered Markdown and **checkbox toggle from Read** (no caret
  exists there, so ticking a box ships before the editor does), audio playback,
  settings with per-plugin screens, Export a copy — **and the Record widget**,
  because a capture path that skips the app is the product, not a garnish.
- **MB2 — the editor and local transcription**, behind MS1: live preview under
  § 2, accessory row and panel, `/` suggest, title rename, autosave and the
  stale-buffer guard — **and the transcription plugin with dictation at the
  caret**. The pair sits here rather than in MB1 because dictation is an editor
  verb with nowhere to land before there is an editor, and a 75 MiB consent
  prompt works against the one thing MB1 exists to protect. MB1's recordings
  are transcribed on arrival here; anything stranded meanwhile is what backfill
  is for (§ 4).
- **MB3 — agenda, search, and the rest of the widgets**: Search over the
  shared DSL, Agenda from `TaskSearch` with toggling through `activateLine`,
  `filter` blocks read-only, remaining widget cells, launcher shortcuts and the
  New-note tile.

**The cold-start budget is TTFD to a typable caret, not TTID to first frame.**
ADR 0001's "cold start to capture-ready < 1.5 s" is adopted with that
definition made explicit: **1.5 s from launcher tap to a caret that accepts a
keystroke** on mid-range hardware, and **1.5 s from widget tap to an open
microphone** without composing the app's UI at all. Android vitals calls 5 s
"excessive", but the Obsidian evidence is that 4–5 s already loses the user to
Keep. Both stay assumed until the owner's device exists.

## Alternatives considered

### Open into the last note, or into the notes list

What Obsidian does, and what every file-first app does. Rejected: it makes the
first frame depend on restoring state, which is the diagnosed cause of the
startup problem this app exists to beat, and it puts a navigation decision
between the user and a thought.

### Hide markers entirely off the caret's line

The literal reading of live preview, and what Obsidian ships. Rejected on the
research's single strongest finding: hiding and re-showing changes a line's
width, so lines shift and the caret jumps (prior art § 1.2). Under a mouse that
is an irritation; under a thumb aiming at a 6-inch column it is the failure mode
a phone editor can least afford.

### Reserve the width but paint the markers transparent

The strongest rival, put to the owner and declined (Ratification record, 2).
Markers occupy their width invisibly and are painted on reveal: layout still
never moves, and away from the caret the text looks fully rendered. Not chosen
because an invisible marker still costs its width, so `**bold**` reads as a
bold word with unexplained gaps around it — a pale marker reads as deliberate
where a gap reads as a bug. It is a colour-table change away.

### Show every marker at full contrast, always (iA Writer)

Nothing ever moves and the buffer is the file, character for character. Not
chosen because the owner chose live preview, and because the permanent tax of
`**`, `#` and `[]()` is heaviest on the narrow column mobile has. The chosen
rule takes iA's discipline without its full price.

### A user-configurable toolbar

Obsidian's model, plus the three plugins that exist to fix it. Rejected for v1:
configurability is what you need when there are too few slots, and the panel
means there are not.

### `getExternalFilesDir()` for the vault

The honest runner-up: no permission, visible over MTP and to a file manager,
zero kernel work. Rejected because it buys visibility while keeping every real
cost — still wiped on uninstall, still unopenable by Obsidian on Android 11+ —
and a half-met promise is worse than an unmet one that ships an export.

### SAF, or `MANAGE_EXTERNAL_STORAGE`

The real answers, and both out of scope here. SAF collides with the kernel at
the FFI: `Gilune.open` takes an absolute path and Rust does `File` I/O beneath
it, so bridging means fd-passing or a kernel storage abstraction — a boundary
change needing its own spike. `MANAGE_EXTERNAL_STORAGE` gives a real path and a
Play policy fight, which belongs with the S5 distribution reasoning.

### Transcribe only on a synced desktop

The shape this design carried until the owner corrected it, recorded because it
was reached by inference rather than by decision: whisper is scoped to desktop
in the build, so the phone was assumed to record and wait for a paired machine.
Rejected. That scoping is an M4b engineering artefact, and reading product
direction out of it would leave the phone the one device that cannot turn
speech into words — in a product whose P0 is tap → speak → done — while making
the capture loop wait on a sync milestone that is not built. It survives as the
**fallback** a low-end device may choose (§ 4).

## Consequences

- `androidMain` grows from two files to a shell. The work is real but almost
  none of it is logic: the machines are inherited and tested, and the new code
  is composables, insets and gesture arbitration.
- `MarkdownEditing.kt` gains one per-registration flag so a decoration can
  survive the caret's line, and `Theme.kt`'s colour schemes move to the shared
  set with typography left per-platform. Both are additive; desktop behaviour
  is unchanged — the owner ratified keeping it (Ratification record, 3).
- `EditorState` is promoted alone, as ADR 0014 permits, and a device-local
  preference store on DataStore arrives with it.
- The kernel is untouched by everything in this ADR except the vault-location
  question, which is deliberately deferred rather than answered. The kernel
  *build* is not: whisper.cpp joins the Android `.so`, which is MB0's largest
  unknown and the reason its budget is not a flat day.
- The mobile shell is the framework's **second host**. Slot reading is shared;
  slot rendering is per-shell and written here — the honest test of ADR 0011's
  plugin-owned UI, and it will surface wherever the desktop shell knows
  something it should have declared.
- A phone recording becomes words on the phone, so the P0 loop closes without a
  second device and without M5. The costs are named: the `.so` grows by the
  engine, so ADR 0001's **< 15 MB per ABI** budget — which the 2.96 MiB arm64
  library clears about fivefold today — is re-measured at MB0, not assumed; the
  ~75 MiB model is a consented download into app-private storage, wiped on
  uninstall like the vault (§ 5); and transcription now competes for battery
  and thermal headroom, which is what MS2 turns into a number.
- Play Console gains a declared microphone FGS type, which is a store-review
  surface and belongs in the S5 matrix.

## Ratification record

Owner review, 2026-09-01. The five open questions, answered:

| # | Question | Answer | Date |
|---|---|---|---|
| 1 | Composer idle timer | Five minutes, expiry **commits** the pending text as a capture, as recommended (§ 1) | 2026-09-01 |
| 2 | Unrevealed markers | **Quiet-dimmed**, as recommended (§ 2); the invisible-width-reserved rival declined | 2026-09-01 |
| 3 | Desktop checkbox rule | **Against the recommendation**: the desktop keeps raw-on-the-caret-line while typing — "as it is now is fine". The shells deliberately differ here, and that difference is a decision, not drift | 2026-09-01 |
| 4 | Phone vault | App-private for v1 with **Export a copy**; user-visible storage stays its own future ADR + kernel-boundary spike — "sounds reasonable to start" | 2026-09-01 |
| 5 | Transcription enablement | **Enabled on every device**. Same day's desktop benchmark made the model a plugin setting: base-q5_1 is the desktop default, tiny-q5_1 (31 MiB, the measured fastest) pencilled as the phone default pending MS2 | 2026-09-01 |

The ADR is Accepted. MB0 — the toolchain stage everything queues behind —
starts on the owner's word; MS1 (IME) and MS2 (ARM64 whisper) remain the
gates they were.

## Amendments

**2026-09-02 (owner, via ADR 0018 acceptance):** § 4 gains a **live caption** —
while a recording runs the shell renders streaming words in the capture surface
as **display only**, never bytes. The recording commits exactly as § 4 already
says, the note's words still arrive from the job loop, and the pending
vocabulary grows one state: *listening* is not *transcribing…*, and the caption
is cleared on stop rather than left standing as if it were the body. § 4 also
gains a **second transcription provider**, exactly one of which is enabled at a
time, so § 7's per-plugin switches become mutually exclusive between them.
**§ 2's no-reflow law is editor law** — it protects a caret and a thumb — and
does not bind a caption that has no caret in it; ADR 0018 § 1 says so
explicitly, so a later reader does not obey it there and lose the feature.
**MS2 widens**: it now measures both engines on the same device over the same
clips, and it sets the packaged mobile default.
