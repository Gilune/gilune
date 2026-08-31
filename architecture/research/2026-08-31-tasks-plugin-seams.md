# Gilune plugin framework — seams present and absent for a Tasks plugin

Research note for the design round. Facts and options; no recommendation.
Where the code contradicts an ADR or `CLAUDE.md`, the code is reported.
Read at commit `c89f79a`, branch `claude/gilune-docs-review-0db3kb`.

---

## 1. The framework today

### 1.1 The path a plugin walks

**Declare.** A plugin is a `PluginDescriptor`
(`kernel/crates/gilune-kernel/src/plugins.rs:54`) — `plugin_id`, `name`,
`class` (`Domain|Provider|Adapter|Projection|Index`), `profile`
(`Bundled|Sandboxed`), `required`, `processor_id: Option<String>`,
`subscribes: Option<String>`, `emits: Vec<String>`, `capabilities:
Vec<String>`, `contributions: Vec<Contribution>`. A `Contribution`
(`plugins.rs:44`) is exactly three strings: `slot`, `id`, `label`.
Across the FFI the mirror is `PluginDeclaration`
(`kernel/crates/gilune-ffi/src/lib.rs:152`), with class and profile as
strings validated in `TryFrom` (`lib.rs:197`).

**Register.** `Store::register_plugin` (`plugins.rs:109`) is idempotent
and called on every launch. An existing row has its *declaration*
refreshed but keeps `state`, `settings` and grants — so an update cannot
silently widen what a plugin may do — and returns the state the plugin
is already in. It then calls `apply_subscription` (`plugins.rs:390`),
which upserts one row into `subscriptions` keyed by `processor_id`, with
`enabled = (state == Enabled)`. That single statement is where job
delivery is gated.

**Who registers, and where the code lives.** Three different homes, no
common one:

| Plugin | Descriptor site | Implementation site |
|---|---|---|
| `org.memory.vault` (required projection) | `gilune-kernel/src/vault.rs:54` | the kernel itself |
| `org.memory.transcription` | `gilune-ffi/src/whisper.rs:16` | `gilune-ffi`, behind the `whisper` cargo feature |
| `org.memory.voice-capture` | `app/.../jvmShared/.../Plugins.kt:35` | the Compose shell |

The first two register from `register_builtins`
(`gilune-ffi/src/lib.rs:285`), called inside `Gilune::open`. The third
registers over the FFI from `GiluneState.openVault`
(`app/.../jvmShared/.../GiluneState.kt:43`).

**Consent policy — at the host, not the kernel.** `plugins.rs:9` states
it explicitly: the kernel grants nothing on its own. Two host policies
exist, and they agree:

- `register_builtins` (`lib.rs:295-300`) grants whisper's `blobs.read`
  and `objects.write` **once**, only when `fresh` (`store.plugin(..)?
  .is_none()`) and the returned state is `Enabled`. `network.request` is
  deliberately excluded.
- `GiluneState.openVault` (`GiluneState.kt:42-46`) grants
  `platform.audio-record` on the same `fresh && registered == "enabled"`
  condition.

`Store::grant_capability` (`plugins.rs:251`) refuses a capability the
descriptor does not declare (`KernelError::UndeclaredCapability`).
`Store::is_granted` (`plugins.rs:278`) answers false for a disabled or
removed plugin whatever its grants say, so callers ask one question.

**FFI surface** (all `#[uniffi::export] impl Gilune`, `lib.rs:305-551`):
`register_plugin`, `plugins`, `set_plugin_enabled`, `remove_plugin`,
`reinstall_plugin`, `grant_capability`, `revoke_capability`,
`is_granted`, `set_plugin_settings`, `backfill_jobs`. `PluginInfo`
(`lib.rs:123`) carries `granted`, `ungranted`, `contributions` and the
`settings` document. There is **no** `register_processor`:
`Gilune.processors` (`lib.rs:257`) is populated only by `enable_whisper`
(`lib.rs:324`), so a processor implementation must be Rust compiled into
`gilune-ffi`.

**App-side slots.** `Slots` (`app/.../jvmShared/.../Plugins.kt:12`)
names five ids: `capture.actions`, `note.actions`, `navigation`,
`command.palette`, `settings.form`. Two helpers read them back
(`Plugins.kt:63,68`): `contributionsTo(slot)` filters to
`state == "enabled"`; `contributes(slot, id)` is a boolean over it.

**Actual consumption — three call sites, one slot and a half:**

- `Main.kt:420` — `contributes(CAPTURE_ACTIONS, RECORD_VOICE_ACTION)`
  gates the top-bar microphone.
- `NoteEditor.kt:263` — `contributes(NOTE_ACTIONS, DICTATE_ACTION)`
  gates the editor's dictate microphone.
- `CaptureBar.kt:67` — the same capture-actions check on the Android
  capture bar.

`contributionsTo` is **never called in production code**. `navigation`,
`command.palette` and `settings.form` are **never read anywhere** —
verified by grepping both the literal strings and the constants across
all `.kt` and `.rs` files. The only other occurrence of any of them is
`whisper.rs:32`, which *declares* a `settings.form` contribution.

**Settings screen.** `SettingsPane.kt` renders fixed sections plus one
nav entry per installed plugin (`SettingsNav`, `:201-214`).
`PluginScreen` (`:285`) shows the enable switch, per-capability
Allow/Revoke rows (`:307-339`), events from `subscribes`/`emits`
(`:341-353`), contributions as read-only chips labelled "Adds to the
app" (`:355-365`), and Remove for non-required plugins (`:372-384`).

**Removability semantics.** `remove_plugin` (`plugins.rs:208`) refuses a
`required` plugin, then in one transaction deletes all
`plugin_grants` rows, sets `state = 'removed'` and clears `settings`,
then disables the subscription. `installed_plugins` filters
`state != 'removed'`, so the plugin vanishes from `plugins()` — and
therefore from the settings nav and from every `contributes(...)` check,
which is what makes its controls disappear. The tombstone row survives
so re-registering on the next launch returns `Removed` and does not
resurrect it (test `a_removed_plugin_is_not_resurrected_by_the_next_
launch`, `plugins.rs:635`). `reinstall_plugin` re-enables with **empty
grants**. Waiting jobs are parked, not destroyed; everything the plugin
created stays as ordinary user data (test
`removing_a_plugin_keeps_what_it_created_and_drops_what_it_was_given`,
`plugins.rs:618`). Device-local assets are the host's to delete — the
kernel does not know they exist.

### 1.2 What the framework does not carry

- `Contribution` has no payload, config, ordering, icon or keybinding
  field — three strings only.
- One subscription per plugin. `apply_subscription` keys `subscriptions`
  by `processor_id`; the doc comment at `plugins.rs:64` says "v0 models
  exactly one per processor; several handlers per plugin arrive with the
  sandboxed profile".
- Plugin settings are an opaque JSON string. `set_plugin_settings`
  (`plugins.rs:284`) validates only that it parses. There is no settings
  schema, and the shell reads whisper's language back with a **regex
  over the JSON** (`SettingsPane.kt:396`) rather than a parser.
- `ui.contribute` is listed among the capability families in
  `architecture/05-plugin-system.md:98`. **No descriptor declares it and
  nothing checks it.** Contributions render on enablement alone.
- No plugin SDK crate exists. `kernel/crates/` holds exactly
  `gilune-cli`, `gilune-ffi`, `gilune-kernel`.
  `05-plugin-system.md:154` requires bundled domain-plugin modules to
  depend on a generated plugin SDK "not on kernel storage implementation
  modules; the build must enforce this dependency direction" — nothing
  provides or enforces this.

---

## 2. The editor today

### 2.1 Buffer and write path

`EditorState` (`NoteEditor.kt:107`) holds the **body only**: `open()`
splits with `splitNoteRaw` (`Editing.kt:41`) and keeps the front-matter
block verbatim in `frontMatterRaw`; `document()` reassembles via
`assembleNote` (byte-lossless). `hashAtOpen` guards against external
writers; a dirty buffer raises `staleBuffer` instead of being
overwritten (`NoteEditor.kt:299-303`). Autosave fires 1200 ms after the
last change (`AUTOSAVE_IDLE_MS`, `:105`, effect at `:292`). One write
path, `write(force)` at `:275`.

### 2.2 Styled editing — one transformation, no registry

Edit mode composes a single `BasicTextField` (`NoteEditor.kt:489`) with
`visualTransformation = transformation`, where

```kotlin
val line = caretLine(editor.text.text, editor.text.selection.start)
val transformation = remember(palette, line) { MarkdownEditTransformation(palette, line) }
```
(`NoteEditor.kt:461-462`)

`MarkdownEditTransformation` (`MarkdownEditing.kt:41`) is a concrete
class, constructed directly. Its `filter` computes checkbox glyphs, and
when there are none returns `TransformedText(styleMarkdown(source,
palette), OffsetMapping.Identity)` — identity offsets, so styling can
never change bytes.

`styleMarkdown` (`MarkdownEditing.kt:193`) is a fixed pass over lines
with a front-matter/fence state machine and eight **private** regexes:
`HEADING`, `LIST_MARKER`, `CODE_SPAN`, `BOLD`, `ITALIC`, `STRIKE`,
`LINK`, `WIKI` (`:184-191`). There is no list, map or registry of
recognisers — adding one means editing this function.

### 2.3 The checkbox slice (E3), end to end

- **Recognition:** `TASK_MARKER = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")`
  (`MarkdownEditing.kt:100`), group 4 is the marker, `MARKER_LENGTH = 3`.
  Ordered lists are deliberately out of scope. `- []` without a space is
  not a marker (matches Obsidian).
- **Glyphs:** `☐` / `☑` (`:95-96`). `checkboxGlyphs(source, caretLine)`
  (`:104`) collects every qualifying marker **except on the caret's
  line**; `replaceMarkers` (`:118`) builds the transformed string.
- **Offset mapping:** `CheckboxOffsetMapping` (`:151`) — each glyph
  shortens by two, so offsets shift left by `2 ×` glyphs fully behind
  them; offsets inside a marker snap to the nearer edge to stay
  monotone. Both directions clamp, because Compose crashes otherwise.
- **Click:** `checkboxUnder(position)` (`NoteEditor.kt:470`) rebuilds a
  transformation, takes `layout.getOffsetForPosition(position)`, maps
  transformed→original, then tries `checkboxMarkerAt(text, original)`
  and `checkboxMarkerAt(text, original - 1)` (a hit on the glyph's right
  half maps just past the marker), rejecting anything on the caret line.
  The gesture is intercepted on `PointerEventPass.Initial` and consumed
  so the caret never moves (`:506-516`).
- **Toggle:** `toggleCheckbox(text, marker)` (`MarkdownEditing.kt:85`)
  flips `[ ]`↔`[x]`, byte-identical outside those three characters, and
  returns the input unchanged if the range no longer holds a marker.
- **Hover:** a second `pointerInput` sets `hoveringCheckbox`, and
  `pointerHoverIcon(..., overrideDescendants = true)` (`:535`) is
  required because the inner text field's I-beam otherwise wins.

### 2.4 Where task-line knowledge lives — plainly

**In core editor code, not plugin code.** `TASK_MARKER`, the glyph
characters, `checkboxGlyphs`, `replaceMarkers`, `CheckboxOffsetMapping`,
`checkboxMarkerAt` and `toggleCheckbox` all live in
`app/composeApp/src/jvmShared/kotlin/org/gilune/app/MarkdownEditing.kt`,
most of them `private`. The click and hover wiring lives inside
`OpenNote` in `NoteEditor.kt`. No plugin declares any of it, no plugin
id is consulted, and removing every removable plugin leaves checkbox
rendering and toggling fully working.

This is structurally the same fault ADR 0011 named for the microphone —
"the microphone and the transcription bootstrap are hardcoded shell
chrome" (`0011:§Context`) — repeated for task lines.

### 2.5 Read mode has no task rendering at all

`parseMarkdown` produces `MdBlock` (`Markdown.kt:45`), a **sealed
interface** with a closed set: `Heading`, `Paragraph`, `ListItem`,
`Quote`, `Code`, `Rule`, `Embed`. `MarkdownView` (`MarkdownView.kt:42`)
switches over it exhaustively and renders `ListItem` as a bullet plus
its spans (`:52-62`). So in Read mode `- [ ] milk` displays as
`• [ ] milk` — the literal brackets. Being sealed, `MdBlock` cannot be
extended from another module.

### 2.6 Popup / autocompletion machinery — what exists

**No inline suggestion, completion or caret-anchored popup exists
anywhere in the editor.** What is reusable, and its limits:

- `QuickOpenBox` (`Main.kt:591`) is the only "type → filtered dropdown"
  pattern: a `DropdownMenu` anchored to the enclosing `Box`, `expanded =
  matches.isNotEmpty()`, `PopupProperties(focusable = false)` so the
  field keeps focus, Enter opens the first match, Escape clears
  (`:629-643`). It is anchored to a **text field**, not to a caret, and
  its candidates come from `searchNotes` (prefix/contains over file
  names, `VaultTree.kt:150`).
- `PropertiesDialog` (`NoteEditor.kt:757`) uses `Popup` with a custom
  `PopupPositionProvider` — `WindowOverlay` (`:744`) — that returns
  `IntOffset.Zero`. There is **no caret-relative position provider**
  anywhere in the tree.
- `TextLayoutResult` is captured as `layout` (`NoteEditor.kt:463`,
  `onTextLayout`) and used only in the position→offset direction. The
  reverse (`getCursorRect`) is available on the type but unused, so
  anchoring a popup at the caret is reachable but unwritten.

### 2.7 `insertAtCaret`

`insertAtCaret(editor, words)` (`NoteEditor.kt:176`) replaces the current
selection, prefixes a single space when the character before is
non-whitespace, `words.trim()`s the insertion, places the caret after it
and sets `dirty`. It was written for dictation. Note the trim and the
auto-space: it is **not** a general "insert exactly these characters at
this offset" or "replace this range" primitive, which is what accepting
a completion needs.

---

## 3. The gaps

### (a) No editor extension seam — decorations, recognisers and inline-suggest are hard-coded

**Does not exist.** `NoteEditor` constructs one concrete
`MarkdownEditTransformation`; `styleMarkdown`'s regex table is private;
the checkbox recogniser, glyph, offset delta and click handler are all
compiled in; the plugin registry is never consulted while styling or
handling a click.

*Smallest honest seam:* give `MarkdownEditTransformation` a list of
decorators — each a recogniser (regex or line predicate) plus optional
replacement text, span style and click handler — and make the checkbox
slice the first registered decorator rather than a special case. The
analogous existing pattern is `SearchOperators(vararg extra)`
(`Search.kt:57`): a registry of built-ins plus extras, where current
behaviour becomes one registered entry. Offset arithmetic already
generalises: `CheckboxOffsetMapping` is table-driven over
`(start, replacementLength)` pairs and only assumes a constant
`MARKER_LENGTH`.

*Separate and additional:* nothing exists for caret-anchored
suggestions. Filling that needs a trigger→candidates→insert-range
contract plus `getCursorRect` anchoring; `QuickOpenBox` is the nearest
shape but is field-anchored, and `insertAtCaret` cannot express a
range replacement (§2.7).

### (b) No plugin-contributed rail panel — ADR 0011 reserved the space, the code did not

**Does not exist.** `RailPanel` is `enum class RailPanel { FILES, SEARCH }`
(`VaultTree.kt:173`) and `RailPanels` switches over exactly those two
(`:229-242`). ADR 0011 says "the rail's upper region is a declared
**navigation slot** that renders nothing while empty" — there is no
navigation slot in the code, and `Slots.NAVIGATION` is read by nothing.
**Code contradicts ADR 0011.**

*Smallest honest seam:* render `plugins.contributionsTo(Slots.NAVIGATION)`
as additional `RailTab`s beside Files and Search, each opening a
plugin-supplied panel composable, with `RailPanel` becoming a sealed
type or a plugin-id-keyed value. The analogous pattern is the mic:
`contributes(...)` decides whether a control is composed at all
(`Main.kt:420`), so disabling the plugin removes the tab.

### (c) `SearchOperators` exists but nothing registers from plugin declarations at runtime

The registry is real and tested — `SearchOperators(TagOperator)` in
`SearchTest.kt:269,284`, with `assertFalse("tag" in SearchOperators().names)`
at `:290` proving it is not a built-in. But **every production call site
uses the empty default**: `SearchPanel.kt:73` calls
`searchVault(state.vaultDir, query)`, and `searchVault`'s parameter
defaults to `SearchOperators()` (`Search.kt:141`). The file's own
comment (`Search.kt:50-52`) says the shell registers them in code until
the sandboxed runtime lands; no shell code does.

*Smallest honest seam:* build the `SearchOperators` instance in the
shell from enabled plugins' contributions to a search-operator slot,
and pass it down to `SearchPanel`. Everything below that line already
works.

### (d) Other gaps found

**`Slots.COMMAND_PALETTE` and `Slots.SETTINGS_FORM` are declared and
dead.** There is no command palette at all — Ctrl+P is quick-open by
file name only. Worse, `whisper.rs:32` **declares** a `settings.form`
contribution (`transcription-model` / "Model and language") that nothing
renders: `SettingsPane.kt:367` hardcodes
`if (plugin.pluginId == TRANSCRIPTION_PLUGIN) TranscriptionSettings(...)`.
Deleting the declared contribution would change nothing on screen. This
contradicts ADR 0011's "every control beyond the shell's own chrome is a
plugin contribution, rendered by the shell from the plugin registry".
*Smallest seam:* at minimum, gate the bundled form on
`contributes(Slots.SETTINGS_FORM, ...)` so removal is honest; fully, a
schema-driven form — but there is no settings schema (§1.2).

**Contributions cannot carry an argument.** `(slot, id, label)` only. A
tasks plugin declaring "a rail panel named Tasks with this icon" or "an
operator `due:` taking a date" has nowhere to put the payload.
*Smallest seam:* one optional `config: String` (canonical JSON) on
`Contribution` / `PluginContribution`, mirroring the plugin `settings`
column, which is already an opaque JSON string the kernel does not
interpret.

**One subscription per plugin.** `subscribes: Option<String>` and the
`processor_id`-keyed `subscriptions` upsert mean a plugin that wants to
react to both `revision-added` and source-ready cannot declare it
(`plugins.rs:64-67` says so). Relevant if tasks ever needs to observe
edits.

**No route for a non-Rust processor.** There is no `register_processor`
across the FFI; only `enable_whisper` pushes into `Gilune.processors`.
A tasks plugin needing background inference (parsing "next Friday") must
be Rust inside `gilune-ffi`, following whisper's cargo-feature
precedent. *Smallest seam:* nothing small — either accept Rust-in-FFI,
or keep v1 purely user-typed so no processor is needed.

**Bundled plugin code has no home and no boundary.** Three precedents,
three crates/source sets (§1.1 table), no plugin SDK, no build rule
enforcing that plugin code does not reach into shell or kernel
internals. `05-plugin-system.md:154` asks for exactly that enforcement.
A bundled tasks plugin written as ordinary Kotlin in
`org.gilune.app` would be indistinguishable from shell chrome except by
convention.

**Read mode cannot show a task.** `MdBlock` is sealed and
`MarkdownView`'s `when` is exhaustive (§2.5), so the decoration seam in
(a) covers Edit mode only unless read-mode blocks are opened too.

---

## 4. Constitutional constraints

**Rule 4 — the kernel must not learn `task`.** It currently has not: a
case-insensitive grep for `task` across `kernel/` matches only
`futures-task` in `Cargo.lock`. The kernel *may* carry a tasks plugin's
data through **generic mechanisms it already has**, all of which are
opaque strings to it: object type ids (`ProposedOutput.type_id`, where
"the last segment names the vault folder and the `<type>-ready` event",
`processor.rs:68-73`), namespaced event types, capability names, and
`(slot, id, label)` contributions. So `org.memory.tasks/task` is
expressible without the kernel naming it. What would breach rule 4: a
`due_ms` column, a tasks table, a task-aware query, or the projection
special-casing task notes. The bar the constitution sets (`rule 4`):
"what user-data invariant becomes impossible to enforce if this remains
outside the kernel?"

**Rule 13 + ADR 0012 — task metadata must be plain text in the body.**
The vault is a contract other tools read and edit; "no feature may
depend on the stored form being opaque". A due date therefore lives in
the note's own characters. A sidecar index, a private table as source of
truth, or zero-width/hidden markers would break both openness and
Obsidian round-tripping. Front matter is plain text but note-level; a
due date on *one* task line has to be on that line. The shipped
precedent is exact: styling never changes bytes (identity offsets,
`MarkdownEditing.kt:49`), and the only byte-changing operation is
`toggleCheckbox`, which is byte-identical outside three characters.

**Rule 2 — sources versus interpretations.** A due date the *user*
types is their own note text: an ordinary edit, reconciled as a revision
with origin external. A due date a *processor* infers is an
interpretation and must go the processor route, where ADR 0013 permits
`revise-subject` but the revision records origin `processor:{id}` and
the earlier document survives in history. Accepting an inline suggestion
is a user edit; a background parse is not. Do not launder the second
through the first — ADR 0013 rejected exactly that ("App-side merge …
launders processor output through the external-edit path, blurring
provenance").

**Rule 1 — capture-first.** Nothing about tasks may enter the capture
path: no task type, no date parse, no dependency on the tasks plugin
being installed or enabled. Capture must still succeed with every
removable plugin gone.

**Rule 6 + ADR 0011 — per-plugin consent and honest removal.** A tasks
plugin declares its capabilities and gets none until granted; the host
policy that auto-grants a bundled plugin's *local* capabilities on first
install (§1.1) is the precedent for anything local, and anything leaving
the device needs an explicit yes. Every control it adds must be gated on
`contributes(...)` so removal removes it, and removal must keep every
task note as ordinary Markdown.

**ADR 0011's slot list is fixed at five**, and new slots "require real
first-party pressure and an amendment here (ADR 0006)". ADR 0006 says
the same from the other side: "missing first-party needs drive
deliberate slot additions". Any new slot id is therefore an ADR 0011
amendment, not an implementation detail.

**ADR 0006 — mobile / declarative profile.** Third-party UI is
shell-rendered declarative slots, never arbitrary widgets; store builds
run bundled plugins only (ADR 0010 sets post-v0 direction but leaves
0006's bundled-only stance as v0). Placement follows: `MarkdownEditing.kt`
is in `jvmShared`, so an editor decoration seam there is available to a
mobile reader; `NoteEditor`, `EditorState` and the rail are desktop-only
and ADR 0014 forbids the shared set learning what a tab is.

**Rule 5 — first-party features exercise the public contracts.** The
hardcoded `TranscriptionSettings` is an existing breach of this in
spirit. A tasks plugin that reaches around the registry would repeat it
rather than prove the framework, which is the stated point of the
exercise.

---

## 5. Where the code would live

### 5.1 Descriptor — three precedents, one match

For a v1 whose behaviour is editor decoration, a rail panel and search
operators (no background inference), the matching precedent is
`voiceCaptureDeclaration()`: the implementation is shell code, so the
shell declares it.

- **Declaration:** `tasksDeclaration()` beside `voiceCaptureDeclaration()`
  in `app/composeApp/src/jvmShared/kotlin/org/gilune/app/Plugins.kt` —
  `class = "domain"`, `profile = "bundled"`, `required = false`,
  `processorId = null`, `subscribes = null`, `emits = emptyList()`.
- **Registration:** one more `g.registerPlugin(...)` in
  `GiluneState.openVault` (`GiluneState.kt:43`), with the same
  `fresh && registered == "enabled"` grant of any purely local
  capability.
- **Capabilities:** the plugin touches only the note text the user is
  editing, so `ui.contribute` is the honest declaration — which would be
  its **first** use anywhere (§1.2), and would need a host-side check to
  mean anything.

If v1 instead needs inference, it needs a `Processor`, which today means
Rust in `gilune-ffi` behind a cargo feature (whisper's precedent,
`gilune-ffi/src/whisper.rs`), plus a descriptor there and a subscription
event — a materially larger change.

### 5.2 Implementation placement

- Recognisers, date parsing and any pure logic: `jvmShared`, so mobile
  can reuse them and so they are testable without a screen — the
  precedent is `Search.kt` and `MarkdownEditing.kt`, both pure and both
  in `jvmShared`.
- Rail panel composable: `desktopMain` (the rail is desktop-only).
- A `plugins/` sub-package would signal the boundary, but nothing
  enforces it (§3d) — the build has no such rule today.

### 5.3 New slot ids the editor and rail would need to publish

Each requires an ADR 0011 amendment (§4). Listed as facts about what is
missing, not as a recommended set:

| Slot id | What it must carry | Nearest existing thing |
|---|---|---|
| `editor.decorations` | recogniser + style/glyph + offset delta | `MarkdownEditTransformation`'s hardcoded checkbox path |
| `editor.suggest` | trigger, candidate list, insert **range** | nothing; `QuickOpenBox` is field-anchored |
| `editor.line.actions` | click target on a decorated span | `checkboxUnder` + the consumed Initial-pass gesture |
| `navigation` (exists, dead) or `rail.panel` | tab icon/label + panel composable | `RailTab` / `RailPanel` enum |
| `search.operator` | keyword + predicate | `SearchOperators(vararg extra)`, test-proven only |

Only the first two are strictly required for "inline due dates on task
lines, entered while typing". The rail panel and the search operator are
what make it a Tasks *plugin* rather than a date highlighter — and they
are the two that exercise seams the framework already half-has.

### 5.4 Consequences to check against, whichever shape is chosen

- If the checkbox slice does **not** move behind the new seam, task-line
  knowledge stays in core editor code and the framework is not proven —
  it is only extended alongside.
- `Contribution` carrying no payload (§3d) constrains every slot above:
  each would otherwise need its data smuggled into `id` or `label`.
- Read mode still shows `[ ]` literally unless `MdBlock` is opened too.
- `insertAtCaret` trims and auto-spaces, so accepting a completion needs
  a new range-replacement primitive rather than that function.
