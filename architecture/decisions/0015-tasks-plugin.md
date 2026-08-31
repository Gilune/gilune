# ADR 0015: Tasks Plugin — Inline Dates and the Editor Seams They Need

Status: **Proposed** (design round 2026-08-31; owner ratification pending)  
Validation: owner ratification for the format and the scope line; the seams are
validated by delivery, not a spike — T1 is not done until the shipped checkbox
slice runs through the same registry a plugin uses, and until removing the
plugin removes every control it added and changes no byte in the vault.
Recognisers, date resolution and insert ranges are pure and tested without a
screen.

## Context

The ask is a Tasks plugin inspired by Obsidian Tasks whose headline is adding a
due date **inline while typing**, chosen deliberately to prove the plugin
framework can add functionality inside the editor. The evidence is two research
notes from this round, kept in `architecture/research/`:
`2026-08-31-tasks-prior-art.md` (Obsidian Tasks and the wider survey) and
`2026-08-31-tasks-plugin-seams.md` (the framework as the code has it, with
file:line evidence). Research notes are evidence like spike reports: they
record what was true when read, and the ADR, not the note, is the decision.

The prior art reduces to one line: **copy the entry affordance, refuse the
storage format.** Obsidian Tasks has the best inline date entry in the survey
and the only format a stranger cannot read; the two are independent axes, and
its emoji format then forced a second serialisation that reads neither format
well — the clearest available warning about shipping two.

The framework's state is the harder half. One slot is wired
(`capture.actions`/`note.actions`); `navigation`, `command.palette` and
`settings.form` are declared and read by nobody; there is no editor extension
point and no caret-anchored popup anywhere; `SearchOperators` is a registry
nothing registers into. Task-line knowledge — marker regex, glyphs, offset
mapping, toggle — is compiled into `MarkdownEditing.kt` and `NoteEditor.kt`,
structurally the fault ADR 0011 named for the microphone. Most of this work is
therefore shell infrastructure, and the plugin is what proves it. That is the
point, not a side effect.

## Decision

### 1. Storage: `due:YYYY-MM-DD`, one format, on the task line

```markdown
- [ ] renew the passport due:2026-09-05
- [x] file the return due:2026-09-01 done:2026-08-30
```

- **One format**, now and later. The research's most instructive failure is a
  parser that acquired a second one.
- **`key:ISO`, whitespace-separated, position-independent** — recognised
  anywhere on the line, never parsed from the end backwards.
- **Only on a task line** (the existing `TASK_MARKER`). That is the whole
  false-positive answer: the pattern is `\sdue:\d{4}-\d{2}-\d{2}\b` on a
  checklist line, so `due:` in prose, and `due:tomorrow` anywhere, are text the
  plugin does not see.
- **Done dates use the same convention**: `done:YYYY-MM-DD`, not Dataview's
  `completion`.
- **Toggling writes it, at the toggle.** `[ ]`→`[x]` appends ` done:<today>`;
  `[x]`→`[ ]` removes it. Write-on-action; no save-time rewrite pass, no
  background process editing an open document.
- **Every GFM checklist item is a task.** No global-filter opt-in: a marker
  meaning "this checkbox is really a task" is the rule a stranger cannot infer.
- **No index, no sidecar.** Recognition is per-line over the text, so a due date
  typed in vim is indistinguishable from one typed here (rule 13).

### 2. Entry: `/` on a task line, two stages, ISO at insertion

- **Trigger:** `/` at a word boundary on a task line. Explicit and dismissible;
  always-on is where every complaint in the research lands — blocked suggesters,
  stuck menus, firing on non-tasks, and a minimum-match-length setting whose
  purpose is to get the menu out of the way.
- **Two stages:** stage one offers the *fields* registered providers contribute
  (v1: `due`), stage two that field's *values*. With one field, stage one is a
  single highlighted entry Enter passes through, so the path is `/`, Enter,
  `tom`, Enter. The structure stays because stage one is where a second plugin's
  field appears for free.
- **Natural language is UI, never storage.** Stage two offers `today`,
  `tomorrow`, each weekday, `next week`, `in a week`; typing narrows; a typed ISO
  date is accepted verbatim. The phrase resolves to an absolute local date **at
  the instant of insertion** and never reaches the file. Nothing unresolvable is
  inserted.
- **Keyboard:** ↑/↓ move, Enter accepts, Escape dismisses, typing narrows, an
  empty candidate set dismisses. Dismissal leaves the buffer exactly as typed —
  the `/` and what follows are the user's bytes. The menu exists only after `/`,
  so Enter is never ambiguous and no `⏎`-in-the-menu escape hatch is needed.
- **Rendering:** a caret-anchored popup, `focusable = false` so the field keeps
  focus (the `QuickOpenBox` precedent), positioned by a new caret-relative
  `PopupPositionProvider` over `TextLayoutResult.getCursorRect`, flipping above
  the caret near the window foot. None of this exists today; it is new shell
  infrastructure, published as a seam rather than built for this plugin.

### 3. The seams

Three new slot ids, plus the one ADR 0011 reserved and the code never built.
**Ratifying this ADR amends 0011's fixed five-slot list to eight**; that
amendment is written into 0011 on acceptance, not before. `Contribution` gains
an optional **`config: String`** — canonical JSON, opaque to the kernel,
mirroring the `settings` column. Three strings cannot express "a decoration
matching this regex", and smuggling payload into `id` or `label` is not a seam.

**`editor.decorations`** — a registration is a line recogniser, a per-match
replacement (or none) and span role, and an optional activation handler taking
the matched line and returning a replacement line. The shell owns composition,
so a plugin cannot break ADR 0012:

- It filters the caret's line out before asking anyone, so E3's raw-on-the-caret-
  line rule is not a registration's to honour.
- It folds all matches into one `(start, sourceLength, replacementLength)` table
  and one `OffsetMapping`. `CheckboxOffsetMapping` is that table with a constant
  length; generalising it is arithmetic, not a redesign.
- Overlaps: first registration wins, in order, built-ins first.
- Rendering may never change bytes; only an activation the user performs may.
- **Activation chains.** Activation goes to every matching registration in
  order; each returns the line, possibly rewritten; the shell folds left and
  writes once, as one undo step. Core flips the marker, tasks then stamps or
  strips `done:`. Remove the plugin and the toggle still toggles.

**`editor.suggest`** — a trigger character plus an applicability predicate, a
stage-one field list, a stage-two value list, and on accept **the exact range to
replace and the exact text to write**. A registration never touches the buffer.
Needs one new primitive, `replaceRange(editor, IntRange, String)`, beside
`insertAtCaret`, which trims and auto-spaces and cannot express this.

**`search.operator`** — the shell builds its `SearchOperators` from enabled
plugins' contributions and passes it to `SearchPanel`, which today takes the
empty default; everything below that line already works and is tested. Tasks
registers `due:` and `done:`, taking a date or `today`/`overdue`/`week`/`none`.

**`navigation`** — the dead slot becomes real: `contributionsTo(NAVIGATION)`
renders as rail tabs beside Files and Search, `RailPanel` becomes a plugin-keyed
value rather than a two-case enum. **Staged to T3, not v1.**

**The checkbox slice stays core, and moves behind the seam.** Rendering `- [ ]`
as a glyph is Markdown presentation of a GFM element, the same class as bolding
`**bold**`, and ADR 0012 makes presentation of the buffer the shell's job; a
vault whose checkboxes reverted to raw brackets on removal would punish the user
for exercising removability. The constitution's "domain code is plugin territory"
binds the *kernel*, which learns nothing here either way. But the slice is
**re-expressed as the shell's own first registration**, exactly as `Search.kt`
holds `BUILT_INS + extra`, so the seam demonstrably carries two producers and the
plugin is not a special case bolted beside a hard-coded one.

**Removal** takes away the `/` menu, the token styling, the done-date stamp, the
two keywords and the rail tab, and changes no byte:
`- [x] file the return due:2026-09-01 done:2026-08-30` still renders as a
checkbox, still reads in Notepad, still greps. The plugin creates no kernel
object, so removal has nothing to retain or orphan.

### 4. Scope

- **T1 — inline due date, end to end.** `Contribution.config`;
  `editor.decorations` with the checkbox re-registered and the date token as the
  second producer; `editor.suggest` with the caret popup and `replaceRange`; the
  descriptor, registration, settings entry and removal. Nothing in the rail,
  nothing in search.
- **T2 — done date on toggle** through the activation chain, and the
  `search.operator` wiring that makes `due:`/`done:` live keywords.
- **T3 — the agenda panel**: a contributed navigation destination with four
  bounded views — overdue, today, next seven days, undated. Capped by
  construction: the measured finding is that matching is cheap (~17,000 tasks in
  ~80 ms) and *rendering* results is what freezes an editor, a footgun the
  maintainers are now fixing with a breaking default limit and we can decline to
  build.
- **Recurrence is deferred**, by name. It is the one advanced feature the
  research says earns its keep, and also where scope crept — a natural-language
  grammar, line-spawning on completion, a reference-date rule, `when done`
  changing the calculation base. It gets its own decision after T1–T3.

**Refused for v0**, each because meaning must survive reading the file, or
because the research shows the cost: **custom statuses** whose semantics live in
a CSS snippet (`- [/]` is opaque anywhere else); **JavaScript in queries**,
refused permanently — Tasks shipped it in 7.21.0 and disabled it by default in
8.0.0 because query text arrives inside synced Markdown, which is exactly our
threat model (ADR 0005); **an embedded query DSL**, since bounded views are what
the canonical agenda actually is; **priorities** and with them **urgency
scoring** — an ordering rule nobody infers plus an invisible score the
maintainers call arbitrary (if wanted later, `priority:high`, same convention);
**dependencies and on-completion actions** (`⛔ dcf64c` cannot be resolved by
reading); **scheduled, start, created and cancelled dates** — two types, not six;
and **filename-implied dates**, despite the research liking them, because the
inheritance rule lives nowhere in the vault.

### 5. Plumbing

A **bundled Kotlin shell plugin** on the voice-capture precedent:
`tasksDeclaration()` beside `voiceCaptureDeclaration()` in `Plugins.kt`,
registered from `GiluneState.openVault`, id `org.memory.tasks`,
`class = "domain"`, `profile = "bundled"`, `required = false`, no processor, no
subscription, no emitted events. Enable, disable, remove, tombstone and the
per-plugin settings screen apply with no new machinery.

**No capabilities.** The whole surface is contributions to shell-rendered slots
and nothing gates that today; declaring `ui.contribute` would be its first use
anywhere and would need a host check changing rendering policy for every
existing plugin — a framework decision listed as a debt, not absorbed here.
Consent is the enable switch.

**The kernel learns no task type**: plugin id, slot ids, contribution ids and
`config` are opaque strings; no column, no table, no task-aware query, no
projection special case (rule 4), and no object is created so no `type_id` is
minted. **Capture is untouched** (rule 1). **Rule 2 holds trivially**: a due
date is the user's typed bytes, an ordinary edit reconciled as a revision with
origin external, and there is no inference anywhere in T1–T3. If a later stage
parses dates out of speech, that is a processor output under ADR 0013 and must
not be laundered through the external-edit path.

**Mobile (ADR 0006).** Recognisers, token format and date resolution are pure and
live in `jvmShared`, so a mobile reader styles the same tokens from the same
code. The popup, the rail panel and search are desktop-only — `NoteEditor`,
`EditorState` and the rail sit in the desktop source set, and ADR 0014 forbids
the shared set learning about panes. Mobile v0 therefore reads and toggles task
lines; inline suggest waits for a mobile editor. Nothing is an arbitrary widget:
every contribution is data the shell renders, as 0006's declarative profile
requires.

**Wasm trajectory (ADR 0003, 0010).** The seams are ids plus JSON `config`
precisely so a sandboxed plugin can declare them later — a decoration is a regex,
a replacement template and a span role; an operator is a keyword; a destination is
a label and an icon. Honestly, in v1 the *behaviour* behind each id is Kotlin in
the shell: the candidate generator, the date resolver, the panel composable.
Three things must change for a wasm plugin to declare the same surface — the
recogniser becomes fully declarative rather than a Kotlin predicate;
`editor.suggest` needs a `suggest(line, fragment) -> candidates` export the WIT
contract has no shape for; the panel needs a declarative description. Two
existing limits would also bite: one subscription per plugin, and no
`register_processor` across the FFI.

## Alternatives considered

### `[due:: 2026-09-05]` — the Dataview-compatible form

The strongest rival, and why the format is an open question below: self-
describing, what Obsidian Tasks writes in its alternative format, and read
natively by Dataview, so an Obsidian user gets queryable fields free. Rejected as
default on three counts — roughly double the characters on a phone-width line,
which the research flags as a real cost for a capture-first product; two bracket
pairs meaning different things on one line (`- [ ] milk [due:: 2026-09-05]`); and
`::` is one ecosystem's convention, bought at the price of the leanest form that
needs no ecosystem at all.

### The other signifiers — `📅`, `>`, `@due(...)`

`📅 2026-09-05` is compact and language-neutral but the only form in the survey a
stranger cannot read; the plugin's own community answered it with an
emoji-replacement project the maintainers adopted. `>2026-09-05` (NotePlan)
scores best on readability-plus-entry and loses narrowly: `>` is Markdown's
blockquote character, our styler already keys on it at line start, and "goes to"
is guessed rather than read. `@due(...)` is rejected outright — the research
checked and corrected the assumption that NotePlan uses it, so it has no lineage,
more punctuation, and no ecosystem.

### Trigger-less suggest on every recognised task line

What Obsidian does, and mildly favoured by the research. Deferred, not refused:
the better end state, and also where the plugin's complaints live. It becomes a
setting once the popup has been lived with, never the default without an off
switch.

### Moving the checkbox slice into the plugin, or leaving it hard-coded

The first is the purer reading of "domain code is plugin territory" and would
prove the framework loudest; rejected because removability would then cost the
user their checkboxes. The second is the smallest diff; rejected because the
framework would be extended alongside rather than proven, and the seam would have
one producer — which is how the `settings.form` declaration ended up decorative.

## Consequences

- `MarkdownEditTransformation` takes a list of registrations instead of a
  hard-coded checkbox path; `TASK_MARKER`, the glyphs and the toggle become the
  shell's first registration, and the offset mapping generalises from a constant
  marker length to a per-match table.
- The shell gains its first caret-anchored popup and a range-replacement
  primitive.
- `Contribution`/`PluginContribution` gain `config` — a kernel schema migration
  and an FFI change, the only kernel work here, teaching it nothing about tasks.
- ADR 0011's slot list grows to eight; the navigation slot stops being a claim
  the code contradicts (T3) and `SearchOperators` stops being a registry nothing
  registers into (T2).
- Read mode still shows `• [ ] milk` and a due date as plain text, because
  `MdBlock` is sealed and `MarkdownView`'s `when` exhaustive. Cosmetic, not
  incorrect — the raw form was chosen to be legible.
- Four bounded views over a no-index search reading each note once is a
  deliberate ceiling; when it stops being honest the answer is an index plugin
  (`05-plugin-system.md`), not a query language.
- Nothing touches capture, sync or projection, so the crash matrix, convergence
  harness and boundary suite are unaffected.

## Debts this design leans on

Surfaced, not fixed, and none absorbed into this plugin's scope:

1. `whisper.rs:32` declares a `settings.form` contribution nothing renders;
   `SettingsPane.kt:367` hard-codes `TranscriptionSettings` by plugin id.
   Deleting the declaration would change nothing on screen.
2. `Slots.COMMAND_PALETTE` is declared and dead — there is no command palette at
   all — and `contributionsTo` is called nowhere in production.
3. ADR 0011 states the rail's upper region is a declared navigation slot;
   `RailPanel` is a two-value enum. The code contradicts the ADR until T3.
4. `ui.contribute` is listed in `05-plugin-system.md:98`, declared by no
   descriptor and checked by nothing. Whether contributing UI is a granted
   capability is a framework decision this ADR deliberately does not take.
5. No plugin SDK crate and no build rule enforcing the dependency direction
   `05-plugin-system.md:154` requires: a bundled Kotlin plugin in `org.gilune.app`
   is distinguishable from shell chrome by convention only — precisely the
   boundary this feature is meant to demonstrate.
6. Plugin settings are an opaque JSON string with no schema, read back in one
   place by a regex over the JSON (`SettingsPane.kt:396`).
7. One subscription per plugin, and no route for a non-Rust processor across the
   FFI. Neither blocks T1–T3; both block the wasm version of this plugin.

## Open questions for the owner

1. **Format** — `due:2026-09-05` *(recommended)*, or `[due:: 2026-09-05]` to buy
   Obsidian-ecosystem interop at double the width and a second bracket pair?
2. **Agenda panel** — T3 as staged *(recommended)*, or pulled into T1 so the
   first ship is visibly a Tasks plugin rather than a date affordance?
3. **Done date on toggle** — automatic in T2 *(recommended)*, or off by default
   behind a plugin setting?
4. **Is every checkbox a task** — yes, no opt-in marker *(recommended)*, or a
   global filter for people who use checklists for non-tasks?
5. **Trigger** — `/` at a word boundary on task lines *(recommended)*, or
   trigger-less on every recognised task line as Obsidian does?
