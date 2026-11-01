# ADR 0015: Tasks Plugin — Inline Dates and the Editor Seams They Need

Status: **Accepted** (owner ratification 2026-08-31 — the five answers, then the
query and extension-point sections including the generic `filter` form with a
required `type:`; see Ratification record)  
Validation: owner ratification for the format and the scope line, given
2026-08-31; the seams are
validated by delivery, not a spike — T1 is not done until the shipped checkbox
slice runs through the same registry a plugin uses, and until removing the
plugin removes every control it added and changes no byte in the vault.
Recognisers, date resolution, query evaluation and insert ranges are pure and
tested without a screen. T4 is not done until an unregistered fence still
renders as an ordinary code block.

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

This revision folds in the owner's answers to the draft's five questions, plus
two requirements taken on 2026-08-31: **task queries, in the search panel and
embedded live in a note**, and **an extension point on the plugin itself**, so a
later People plugin can add an `assignee` field to the tasks plugin's own menu.

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
- **Not inside a fenced code block.** The scan tracks fences, as `styleMarkdown`
  already does — still pure over the text — so a documented example, including
  the `filter` blocks of § 4, never becomes somebody's agenda. Obsidian excludes
  code blocks for the same reason.
- **Done dates use the same convention**: `done:YYYY-MM-DD`, not Dataview's
  `completion`.
- **Toggling writes it, at the toggle.** `[ ]`→`[x]` appends ` done:<today>`;
  `[x]`→`[ ]` removes it. Write-on-action; no save-time rewrite pass, no
  background process editing an open document.
- **No index, no sidecar.** Recognition is per-line over the text, so a due date
  typed in vim is indistinguishable from one typed here (rule 13).

**Which checkboxes are tasks: a global filter, default empty.** A plugin setting
holds a filter string. Empty — the shipped default — means every GFM checklist
item is a task. Set to `#task`, only checkbox lines carrying that string are,
for people whose checklists mostly are not. Recognisers, the `/` menu, the panel
and queries read the one setting, so the vault never disagrees with itself; the
line renders as written, with no filter-stripping display mode; a change takes
effect on the next recompute, rewriting nothing.

*The default-empty reading is the coordinator's, pending no owner objection.*
The owner ratified that a filter is a good idea without naming a default, and a
non-empty one would make "this checkbox is really a task" a rule nobody can
infer from the vault — the unreadable rule this section exists to reject.

### 2. Entry: `/` on a task line, two stages, ISO at insertion

- **Trigger:** `/` at a word boundary on a task line. Explicit and dismissible;
  always-on is where every complaint in the research lands — blocked suggesters,
  stuck menus, firing on non-tasks, and a minimum-match-length setting whose
  purpose is to get the menu out of the way.
- **Two stages:** stage one offers the *fields* registered in `tasks.fields`
  (§ 3; v1: `due`), stage two that field's *values*. With one field, stage one is
  a single highlighted entry Enter passes through, so the path is `/`, Enter,
  `tom`, Enter. The structure stays because stage one is where another plugin's
  field appears.
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

### 3. Fields are a registry, and `tasks.fields` is a slot this plugin publishes

The owner's requirement is that a future People plugin adds an `assignee` field
to tasks and its option appears in the *same* `/` menu. The framework advance,
stated plainly: **slot ids stop being shell-only. A plugin may publish a slot
other plugins contribute to.** The tasks plugin publishes `tasks.fields`.

**Kernel work: none** beyond the `config` string § 5 already adds — slot ids are
opaque, and the kernel neither knows who publishes one nor cares. **The gating
rule** is the shell's: a contribution into a plugin-published slot is live only
while **both** the publishing and the contributing plugin are enabled.
`contributionsTo` already drops a disabled contributor; the publisher check is
the addition. Disable or remove either side and the field leaves the menu and
its tokens stop being styled, **changing no bytes** — `assignee:anna` then reads
as plain text, exactly as `due:2026-09-05` does when tasks itself is removed.

A contribution declares itself in `config`:

```json
{ "key": "assignee", "label": "Assignee", "values": "org.people.contacts",
  "pattern": "[A-Za-z0-9._-]+", "operator": true }
```

— the field key, its stage-one label, its stage-two value source (a Kotlin
provider id while plugins are bundled; § 7 carries what a declarative one would
need), an optional pattern for token recognition, and whether to publish the
matching search keyword.

**`due` is the first registration into this slot**: the plugin's own field list
*is* that registry, one entry long — the two-producers-or-it-rots discipline the
checkbox re-registration follows. Honestly, at T2 the second producer is a
test-only fixture, the `SearchOperators(TagOperator)` precedent; a shipped one
waits for a real People plugin, and a fixture is the weaker evidence.

**Storage generalises unchanged**: a contributed field is a `key:value` token on
the task line under § 1's rules. Values are **unspaced** in v1 (`assignee:anna`
is a handle, not a name); spaces and quoting are deferred, not designed.

**Search follows for free.** `"operator": true` registers the key as an operator
scoped to `type:task`, meaning "this line carries `key:value`", so
`assignee:anna` works in the panel and inside a `filter` block with no further
machinery, by the rules in § 4.

### 4. Filters: one grammar, one engine, in the panel and in the page

The owner's requirement is querying as an extension of our search, and the same
query embeddable in a note as a live view. This overturns the draft's refusal of
an embedded query DSL, and the owner then generalised it: **the embedded block
is not a tasks feature.** It is the search language, in a file, for anything the
shell can enumerate. **JavaScript in queries stays refused permanently.**

**One grammar.** The language *is* `Search.kt`'s: bare terms ANDed by
whitespace, `"quoted phrases"`, `-negation`, `file:`, `path:`, `content:`,
`[key:value]` front matter, plus whatever operators enabled plugins registered.
No second parser, no sort/group/layout vocabulary, no boolean algebra beyond the
existing conjunction-with-negation, no templating.

**`type:` says what a hit is, and is required in a block.** A small registry
holds two kinds of type:

- **Note-level** — `type:note` is the shell's own: a hit is a note, which is the
  search panel's present semantics unchanged.
- **Line-level** — `type:task`, contributed by this plugin: a hit is a task line,
  honouring § 1's global filter. Evaluation is the narrowed-note rule: the
  evaluator is handed, per candidate line, a `SearchedNote` whose `body` is that
  one line and whose name, path and front matter are the containing note's. Every
  existing operator then behaves correctly with no new interface — terms about
  text (bare terms, `content:`, the task operators) bind to the **line**, terms
  about the note (`file:`, `path:`, `[key:value]`) bind to the **note**, and a
  bare term still also matches the note's name, as in the panel.

Scanning either way is `searchVault`'s: whole vault, each file read once, no
index.

**Operators are registered scoped to a type**, so `due:` is a keyword under
`task` and prose under `note`. An operator used beneath a type that does not know
it **degrades to a bare term**, exactly as the DSL already treats an unregistered
keyword — a typo, or a plugin switched off, must not silently empty a result set.

**No `type:`, no guess.** A block without one renders a single inline hint row
naming the registered types. A `type:` nobody has registered is treated the same
way, which is precisely what a disabled or removed contributor leaves behind.

**The panel shares the one engine.** `type:` works there too; absent, it defaults
to `note`, because interactive typing is a person narrowing by hand and not a
stored file that must say what it means to a stranger.

**What plugins contribute is the type, not the fence.** A `search.types`
contribution declares an extractor (what a hit is), a row renderer, and the
operators scoped to it. The `editor.blocks` registration for the `filter` fence
belongs to the shell's own search machinery, so blocks keep working with every
removable plugin gone. The People case falls out cheaply: `type:person` is most
naturally note-level, keyed off `[gilune-type:person]` front matter, and needs
almost nothing new.

**Task operators** (registered under `task` at T2). `due:` and `done:` take
`YYYY-MM-DD`, or `today`, `overdue`, `week` (today through the next seven days),
`none`. The one extension to value forms is `before:<date>` and `after:<date>`,
because a project note's horizon is otherwise unsayable and the alternative is
people typing rotting dates into prose. Obsidian's five-operator × six-field
matrix and its `YYYY-Www`/quarter ranges are refused; `-due:none` already means
"has a due date", so no `has`/`no` keywords are added. Relative words resolve
**at evaluation**, the inverse of § 2 and for the same reason: `due:today` is the
query's meaning, not a date that rots.

**Syntax in the file** — a fenced block whose info string is `filter`:

````markdown
```filter
type:task
due:week
-done:none
path:Work
```
````

One filter per line, lines ANDed, whitespace ANDing within a line — the search
DSL's own semantics applied twice over. Blank lines are ignored; there is no
comment syntax, because `#` opens a plausible term. **An empty block matches
nothing** and says so: the empty query is precisely the freeze the Tasks
maintainers propose a breaking change to fix, and `searchVault` already returns
nothing for no terms.

**Degradation.** A stranger, Notepad, GitHub and Obsidian all see a legible
fenced code block labelled `filter` holding readable filters, one of which says
what it is looking for; nothing is hidden and nothing executes. No surveyed tool
claims `filter` — Obsidian Tasks uses `tasks` — so the generalisation also walked
us out of a namespace collision.

**What renders is the type's row.** For `type:task` that is T3's agenda row: the
checkbox glyph, the line's text with its tokens styled, its dates, and the note
it came from. Rows **navigate, they do not edit** — a click opens the note at
that line. Toggling from a result would write to a file the user is not looking
at, needing the stale-buffer story, and the prior art's own bug (a checkbox in a
callout failing to write its done date) lives exactly there.

**Bounds by construction.** A type carries one order — tasks': due ascending,
undated last, then path, then line number — because it is the only order an
agenda needs and there are then no sort or group instructions to get wrong.
**50 rows, then a `+N more` line.** Matching is cheap (~17,000 tasks in ~80 ms)
and *rendering* results is what freezes an editor, so the cap is on rendering and
is not optional.

**Refresh** is on the vault tick (`GiluneState.vaultTick`) and on opening a
note, plus buffer settle in Edit mode, reusing `AUTOSAVE_IDLE_MS`. Never per
keystroke.

### 5. The seams

Four new slot ids — `editor.decorations`, `editor.suggest`, `editor.blocks`,
`search.types` — plus the one ADR 0011 reserved and the code never built.
**Accepting this ADR takes 0011's fixed five-slot list to nine and adds the rule
that a plugin may publish a slot of its own** (§ 3); both are written into 0011
on acceptance (done 2026-08-31). `Contribution` gains an optional
**`config: String`** — canonical JSON, opaque to the kernel, mirroring the
`settings` column. Three strings cannot express "a decoration matching this
regex", and smuggling payload into `id` or `label` is not a seam.

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

**`editor.blocks`** — a fence recogniser (the info string) plus a renderer, on
the same shell-owned terms as `editor.decorations`. The `filter` fence is the
search machinery's own registration, not this plugin's (§ 4). It is the
block-level analogue of E3's caret rule:

- **Read mode needs no new block type.** `parseMarkdown` already models a fenced
  block as `MdBlock.Code(text, language)`, carrying the info string, so the seam
  is a renderer table consulted in `MarkdownBody`'s `Code` branch and an info
  string nobody registered falls through to today's monospace box. `MdBlock`
  stays sealed and the `when` stays exhaustive — the resolution the draft's
  Consequences left open.
- **Edit mode.** Caret outside: the fence span is replaced, through the same
  offset table `editor.decorations` builds, by a placeholder of blank lines sized
  to the result, reserving the vertical box; the rows compose in an overlay
  aligned to it via `TextLayoutResult.getBoundingBox`, the layout facility the
  caret popup needs anyway. Caret inside: the registration is filtered out for
  that block, exactly as the caret's line is filtered today, and the raw fence
  shows. Bytes never change in either state.
- Honestly, the row count must be known before the transformation is built, so
  the query is evaluated ahead of layout — tolerable only because results
  recompute on tick, not per keystroke (§ 4). It is the least elegant part of
  this design, which is why it is staged last.

**`search.types`** — a type name, an extractor, a row renderer and the operators
scoped to it (§ 4). The shell builds its `SearchOperators` from enabled plugins'
registrations and passes it to `SearchPanel`, which today takes the empty
default; everything below that line already works and is tested. Operators arrive
*through* a type rather than loose, so switching a plugin off cannot leave a
keyword behind meaning something nobody can answer.

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
`task` type and its keywords, and the rail tab, and changes no byte:
`- [x] file the return due:2026-09-01 done:2026-08-30` still renders as a
checkbox, still reads in Notepad, still greps. `filter` blocks keep rendering,
because the fence is the shell's; a block that asked for `type:task` shows the
hint row of § 4, which is the honest answer — the type it wanted is gone. No
kernel object is created, so removal has nothing to orphan.

### 6. Scope

- **T1 — inline due date, end to end.** `Contribution.config`;
  `editor.decorations` with the checkbox re-registered and the date token as the
  second producer; `editor.suggest` with the caret popup and `replaceRange`; the
  `tasks.fields` registry with `due` as its only entry, read by the suggest's
  stage one; the global-filter setting; the descriptor, registration, settings
  entry and removal. Nothing in the rail, nothing in search.
- **T2 — done date on toggle** through the activation chain; **`search.types`**
  with the shell's `note`, this plugin's `task`, and `due:`/`done:` scoped to it
  with § 4's value forms, so `type:` works in the search panel before any block
  exists; and **`tasks.fields` published as a cross-plugin slot** — other
  plugins' contributions read, both-enabled gating enforced, paired operator
  registered, proven by a fixture. The hook ships here so the owner's
  requirement is real rather than notional, whenever a second plugin arrives.
- **T3 — the agenda panel**: a contributed navigation destination with four
  bounded views — overdue, today, next seven days, undated — each one of § 4's
  queries. **A plugin setting turns the panel off**; while it is off the shell
  does not compose the tab, the same shape as every other `contributes(…)` gate,
  one condition further in.
- **T4 — embedded `filter` blocks**: the `editor.blocks` seam, the shell's
  registration of the `filter` fence, the Read-mode renderer table, the Edit-mode
  overlay, the hint row and the 50-row cap. Last because it reuses T3's rows and
  T2's types wholesale and adds only rendering — and note that half of it is
  shell work this plugin merely proves.
- **Recurrence is deferred**, by name. It is the one advanced feature the
  research says earns its keep, and also where scope crept — a natural-language
  grammar, line-spawning on completion, a reference-date rule, `when done`
  changing the calculation base. It gets its own decision after T1–T4.

**Refused for v0**, each because meaning must survive reading the file, or
because the research shows the cost: **custom statuses** whose semantics live in
a CSS snippet (`- [/]` is opaque anywhere else); **JavaScript in queries**,
refused permanently — Tasks shipped it in 7.21.0 and disabled it by default in
8.0.0 because query text arrives inside synced Markdown, which is exactly our
threat model (ADR 0005); **priorities** and with them **urgency scoring** — an
ordering rule nobody infers plus an invisible score the maintainers call
arbitrary (if wanted later, `priority:high`, same convention); **dependencies
and on-completion actions** (`⛔ dcf64c` cannot be resolved by reading);
**scheduled, start, created and cancelled dates** — two types, not six; and
**filename-implied dates**, despite the research liking them, because the
inheritance rule lives nowhere in the vault.

**Overturned from the draft:** *an embedded query DSL* was refused here on the
grounds that bounded views are what an agenda actually is. The owner ruled on
2026-08-31 that querying must extend into notes; § 4 is the constrained form,
and the constraint is that it is not a second language.

### 7. Plumbing

A **bundled Kotlin shell plugin** on the voice-capture precedent:
`tasksDeclaration()` beside `voiceCaptureDeclaration()` in `Plugins.kt`,
registered from `GiluneState.openVault`, id `org.memory.tasks`,
`class = "domain"`, `profile = "bundled"`, `required = false`, no processor, no
subscription, no emitted events. Enable, disable, remove, tombstone and the
per-plugin settings screen apply with no new machinery; the global filter, the
panel switch and the field registry all sit in the settings document it already
has.

**No capabilities.** The whole surface is contributions to shell-rendered slots
and nothing gates that today; declaring `ui.contribute` would be its first use
anywhere and would need a host check changing rendering policy for every
existing plugin — a framework decision listed as a debt, not absorbed here.
Consent is the enable switch.

**The kernel learns no task type**: plugin id, slot ids, contribution ids and
`config` are opaque strings; no column, no table, no task-aware query, no
projection special case (rule 4), and no object is created so no `type_id` is
minted. Cross-plugin gating is likewise a shell rule over strings the kernel
cannot interpret. **Capture is untouched** (rule 1). **Rule 2 holds trivially**:
a due date is the user's typed bytes, an ordinary edit reconciled as a revision
with origin external, and there is no inference anywhere in T1–T4. If a later
stage parses dates out of speech, that is a processor output under ADR 0013 and
must not be laundered through the external-edit path.

**Mobile (ADR 0006).** Recognisers, token format, date resolution and query
evaluation are pure and live in `jvmShared`, so a mobile reader styles the same
tokens and could render the same block from the same code. The popup, the rail
panel, the search panel and the Edit-mode overlay are desktop-only, since
`NoteEditor`, `EditorState` and the rail sit in the desktop source set. Mobile
v0 therefore reads and toggles task lines; inline suggest waits for a mobile
editor. Nothing is an arbitrary widget: every contribution is data the shell
renders, as 0006's declarative profile requires.

**Wasm trajectory (ADR 0003, 0010).** The seams are ids plus JSON `config`
precisely so a sandboxed plugin can declare them later — a decoration is a regex,
a replacement template and a span role; an operator is a keyword; a destination is
a label and an icon; a field is the object in § 3. Honestly, in v1 the
*behaviour* behind each id is Kotlin in the shell, and four things must change
for a wasm plugin to declare the same surface: the recogniser becomes fully
declarative rather than a Kotlin predicate; `editor.suggest` needs a
`suggest(line, fragment) -> candidates` export the WIT contract has no shape
for; a `tasks.fields` value source needs that same export rather than a provider
id; and the panel and the block renderer need declarative descriptions.
Two existing limits would also bite: one subscription per plugin, and no
`register_processor` across the FFI.

## Alternatives considered

### `[due:: 2026-09-05]` — the Dataview-compatible form

The strongest rival, and the one the owner ruled against: self-describing, what
Obsidian Tasks writes in its alternative format, and read natively by Dataview,
so an Obsidian user gets queryable fields free. Rejected on three counts —
roughly double the characters on a phone-width line, which the research flags as
a real cost for a capture-first product; two bracket pairs meaning different
things on one line (`- [ ] milk [due:: 2026-09-05]`); and `::` is one
ecosystem's convention, bought at the price of the leanest form that needs no
ecosystem at all.

### The other signifiers — `📅`, `>`, `@due(...)`

`📅 2026-09-05` is compact and language-neutral but the only form in the survey a
stranger cannot read; the plugin's own community answered it with an
emoji-replacement project the maintainers adopted. `>2026-09-05` (NotePlan)
scores best on readability-plus-entry and loses narrowly: `>` is Markdown's
blockquote character, our styler already keys on it at line start, and "goes to"
is guessed rather than read. `@due(...)` is rejected outright — the research
checked and corrected the assumption that NotePlan uses it, so it has no lineage,
more punctuation, and no ecosystem.

### An embedded query DSL — refused in the draft, ratified by the owner

The draft refused it: bounded views are what the canonical agenda is, and the
prior art's query block acquired filters, sorts, groups, layouts, presets,
comments, Mustache placeholders and finally JavaScript. The owner overruled it
on 2026-08-31, and the overturn is recorded rather than quietly absorbed. What
makes § 4 a different object from the thing refused: it adds **no language** —
the block holds the query the search panel already takes, with no sort, group or
layout vocabulary — the result set is capped where the measured cost is, and the
escape hatch that ended in a security retreat is refused permanently.

### A `tasks` fence owned by the tasks plugin

This ADR's own previous revision, and what the prior art does. Replaced by the
owner's generalisation on 2026-08-31: a plugin-owned fence means every future
queryable thing brings its own info string and its own block registration, and
the file and the search panel drift into two languages saying the same thing. One
`filter` fence with a required `type:` keeps the count at one and makes the
question the block must answer — what am I looking at — explicit in the text
rather than implied by the fence's name.

### A contributing plugin registers with `editor.suggest` directly

A People plugin could declare its own `editor.suggest` registration and offer
`assignee` itself, needing no new slot. Rejected: it would have to re-derive what
a task line is, read tasks' global-filter setting to agree with it, style its own
token, and keep out of the way of tasks' own menu — a second trigger on the same
lines, precisely Obsidian's "suggesters block each other" complaint. **The tasks
plugin owns task-line semantics, so fields must compose *through* it, not around
it.** The slot costs one gating rule; the alternative costs two plugins holding
the same fragile agreement.

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
  hard-coded checkbox path; the offset mapping generalises from a constant marker
  length to a per-match table.
- The shell gains its first caret-anchored popup, a range-replacement primitive,
  and its first overlay aligned to a span of the text buffer.
- `Contribution`/`PluginContribution` gain `config` — a kernel schema migration
  and an FFI change, the only kernel work here, teaching it nothing about tasks.
- ADR 0011's slot list grows to nine and gains plugin-published slots (amended
  2026-08-31); the navigation slot stops being a claim the code contradicts (T3)
  and `SearchOperators` stops being a registry nothing registers into (T2).
- The search panel gains `type:` and keeps `note` as its default, so nothing a
  person has already typed changes meaning.
- `MarkdownBody`'s `Code` branch consults a renderer table; `MdBlock` stays
  sealed and its `when` exhaustive. The draft's open consequence is resolved
  without unsealing anything, because a fenced block is already parsed and
  already carries its info string.
- Read mode still shows `• [ ] milk` and a due date as plain text. Cosmetic, not
  incorrect — the raw form was chosen to be legible.
- Search reads every note once per query, and an embedded block is a query, so a
  note carrying several pays several passes. The tick-not-keystroke rule and the
  row cap keep that honest; an index plugin (`05-plugin-system.md`) is where the
  answer goes when they stop doing.
- The shell takes a fence info string, `filter`, in a namespace nobody owns.
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
   place by a regex over the JSON (`SettingsPane.kt:396`). The global filter, the
   panel switch and the field registry all land in that same string.
7. One subscription per plugin, and no route for a non-Rust processor across the
   FFI. Neither blocks T1–T4; both block the wasm version of this plugin.
   **(2026-09-02, via ADR 0018 acceptance: the second half is taken on — 0018's
   R2 builds `register_external_processor` / `claim_job` / `submit_output` /
   `fail_job` with the processor contract's semantics intact. It stays a debt
   until R2 ships, and it discharges nothing for the *wasm* profile: an external
   processor is in-process trusted host code, not a sandboxed one. One
   subscription per plugin stands.)**
8. `editor.blocks` is renderer-only: a plugin may render a **fenced** block, never
   add block syntax. Anything that is not a fence stays shell-parsed.
9. Fence info strings are a namespace nobody owns. `filter` collides with nothing
   in the survey — the generalisation away from `tasks` avoided the one known
   clash — but a generic word is a generic word, and a future tool may claim it.
10. Plugin-published slots have no registry of publishers: the shell knows
    `tasks.fields` belongs to `org.memory.tasks` because it is written in shell
    code. The both-enabled gate is therefore a shell rule, and a second host
    would have to re-implement it.

## Ratification record

Owner review round, 2026-08-31. The draft's five open questions, answered:

| # | Question | Answer | Date |
|---|---|---|---|
| 1 | Format | `due:YYYY-MM-DD`, as recommended (§ 1) | 2026-08-31 |
| 2 | Agenda panel | T3 as staged, **and** switchable off in the plugin's settings; off means the rail tab is not contributed (§ 6) | 2026-08-31 |
| 3 | Done date on toggle | Automatic in T2, as recommended (§ 1, § 6) | 2026-08-31 |
| 4 | Is every checkbox a task | A **global filter** setting. Default-empty — every GFM checkbox is a task — is the coordinator's reading of an answer that did not name a default, recorded in § 1 | 2026-08-31 |
| 5 | Trigger | `/` at a word boundary on task lines, as recommended (§ 2) | 2026-08-31 |

Two requirements were added in the same round and designed in above: **task
queries, in search and embedded in notes** (§ 4, `editor.blocks` in § 5, T4),
which overturns the draft's refusal of an embedded query DSL; and **an extension
point on the tasks plugin** (§ 3, T2), which makes publishing a slot a plugin's
privilege and not only the shell's. Both were then ratified:

| Section | Ratified | Date |
|---|---|---|
| Queries and the extension point, as revised | Accepted | 2026-08-31 |
| **Generalised** to the `filter` fence with a **required `type:`**, a two-kind type registry, operators scoped to a type, and `search.types` as what plugins contribute — the block language is the search DSL for everything, not a tasks feature (owner direction, then explicit agreement with the coordinator's recommendations) | Accepted | 2026-08-31 |

The ADR is Accepted and moves to build. ADR 0011 was amended the same day with
the four new slot ids and the plugin-published-slot rule.

Post-delivery amendments (owner, 2026-08-31, after all four stages shipped):

- § 4's `before:`/`after:` values widen from ISO-only to **anything the one
  date resolver resolves** — `today`, `tomorrow`, a weekday's next occurrence —
  resolved at evaluation as ever; a value the resolver rejects stays an empty
  result. Multi-word phrases cannot reach an operator value and are not
  contorted for.
- A **`is:` operator scoped to `type:task`**: `is:open` and `is:done` read the
  checkbox marker itself off the line's bytes — the one fact about a task that
  is not a date, and what "not done" actually means. `done:none` remains the
  date-token question.
- **2026-09-05, ADR 0020 stage P: the seams become the registration
  route, and the scan is gone.** § 4 said "Scanning either way is
  `searchVault`'s: whole vault, each file read once, no index", and
  Consequences said an index plugin "is where the answer goes when they
  stop doing". They stopped doing, and the answer went to the kernel
  rather than to a plugin: the system index of ADR 0020 § 2.3.
  - `SearchOperator` gains `compile(argument, negated)` and `SearchType`
    gains `constraints`. That is the whole of "a plugin registers an
    operator together with how it compiles to a constraint" (0020 § 2.4).
    **The default says nothing**, so a registration written for this ADR
    — the `tag:` fixture, and the `"operator": true` fields § 3 promises
    a People plugin — goes on working unchanged and costs the reads it
    always cost. Compilation narrows; the operator's own `matches` still
    decides, over the note it always read.
  - The tasks plugin registers a `NoteIndexer` under `org.memory.tasks`:
    one fact per task line for the checkbox and each of § 1's two dates,
    read by the same recognisers the operators use. **The global filter
    is not applied when the facts are derived** — it is a setting, and
    facts derived under it would be thrown away whenever it changed —
    so it narrows at query time and `taskLines` decides exactly.
  - The **agenda and every `filter` block are queries**. § 4's caps, the
    four views, the horizon, the one order and the tick-not-keystroke
    rule are all unchanged; what they were protecting is now much
    cheaper (measured: an agenda refresh over a thousand notes, 255 ms
    to 21 ms).
  - **Removal is unchanged in what it means and stronger in what it
    does**: `remove_plugin` drops the namespace, so the facts go with the
    words, and the shell registers the indexer only while the plugin is
    listed at all.
  - Debt 7's last sentence stands, and debt 6 is untouched. The `filter`
    fence is still the shell's own registration, so a block keeps
    rendering — as the hint row naming the type that has gone — with the
    plugin removed.

- T4's Edit-mode rendering first shipped as the raw fence. **The owner
  declined that fallback (2026-09-01)**: a block "behaves like a checkbox —
  renders until the caret is placed within it". § 5's caret rule is
  therefore **block-scoped**: the caret on any line of the fence, delimiters
  included, shows the whole block raw; anywhere else it collapses, through
  the same offset table, to blank lines an overlay draws the rows into —
  anchored and sized from the layout's own line boxes, so nothing below it
  mis-sits. Rows still navigate; a click that is not on one is **not
  consumed**, so the field lands the caret inside and that reveals the
  fence — which is how a covered block is edited. Edit mode caps at
  **8 rows** then `+N more` (Read mode keeps 50), and evaluates on the tick
  and on buffer settle, never per keystroke and never while the caret is
  inside the block.

### Overview checkbox amendment — 2026-09-08

The owner requested completing tasks directly from an overview note. Filter
results therefore offer a separate checkbox on desktop (Read and collapsed
Edit blocks) and Android. This supersedes the earlier read-only restriction
for filter task rows: row text still opens the source note, while the checkbox
changes its source task without navigating. Completed rows leave `is:open`
results after the save is reconciled and indexed.

The action uses the editor's decoration chain, including the tasks plugin's
completion-date stamp. It requires the exact displayed checkbox line at the
same body-relative position, serialises with autosave through the document
edit gate, and refuses a source with unsaved editor text. A stale result is
refreshed; it never overwrites newer source text. The overview's filter remains
ordinary Markdown. The desktop note header also exposes a labelled pencil
button that enters Edit mode and focuses the body.

### Tasks page amendment — 2026-10-19

Owner: tasks are built inside other pages, so a Tasks *folder* makes little
sense; a Tasks *page* of example queries does. The plugin now declares one
page type and a one-time page initializer that writes `Tasks.md` at the
vault root: live `filter` blocks for overdue, due today, this week, not
dated yet and done this week, plus a few lines on writing your own. The
initializer records a receipt (`Settings/Initialization/tasks-page.md`), so
the page is written once per vault on every device, editing it is the point,
and deleting it keeps it deleted.

This revises § 7's "no capabilities — consent is the enable switch": the
plugin now declares `pages.create`, used for that one page. A vault that
already had the plugin is granted it once, because the grown declaration
asks for something never offered before (ADR 0018 § 5's rule, applied by the
host to bundled page creation); a revoke is never undone. The plugin declares
no `vault.folders` entry (ADR 0011 amendment of the same date).

### Task shortcut amendment — 2026-10-20

A user asked for a key to add a task in a document. The desktop editor now
has one: **Ctrl+L** by default, Obsidian's "toggle checkbox status", acting
on the caret's line. A plain line becomes `- [ ] ` plus its text behind its
own indentation; a bullet (`-`, `*`, `+`) gains a marker and keeps its
bullet; an empty line takes `- [ ] ` with the caret after it; a task ticks
or unticks through `activateLine` with the editor's own registrations, so
`done:` is stamped or taken away exactly as on click, and — being an action
like the click — the note is written at once. The caret keeps its place in
the text. Nothing happens inside a fence (delimiters included, as list
continuation counts them), on an ordered-list item (out of the checkbox's
scope, and `- [ ] 1. milk` would wreck the numbering), or while the `/`
menu is up.

The keystroke is the plugin's, as go to definition is People's (ADR 0011
amendment of 2026-10-19): it is stored as `taskShortcut` in the plugin's
settings document beside `filter` and `panel`, changed or reset on its
settings screen, absent means the default (so the default is never pinned
by a write), and every write carries all three keys. The key does nothing
while the plugin is switched off. The rule is a pure function in shared
code (`taskLineShortcut`) so the phone can bind it later; only the desktop
wires it today. The global filter is not typed onto a new task: a filtered
vault's new checkbox is an ordinary one until its line carries the filter.

### Filter rows open, and lead to their line — 2026-10-21

Owner: `+N more` should expand the list, and a task clicked in a list
should open its note *at the task*. § 4's cap stays — rendering is what
freezes an editor — but it becomes the reader's to move: `+N more` shows the
next page (fifty rows, up to the kernel's 500-row page bound) and `Show
fewer` folds it back, in Edit mode and presentation mode alike. In Edit mode
the block reserves one more line for its footer once opened, so the
reservation and the drawn rows still agree. Clicking a task row — in a block,
the agenda panel, or a search result's quoted line — now opens the note with
the caret at the end of that line (met by its text if the note has moved on
since it was indexed), switching a presented note back to writing. The
agenda still never edits; rows navigate as before, just more precisely.

Same date, owner: subtasks are written as ordinary nested list items, so the
editor learns to nest. Tab on list lines indents them one level (two spaces,
which nests under a `- ` bullet in CommonMark and reads the same in
Obsidian); Shift+Tab lifts them; elsewhere Tab types two spaces instead of
the field's literal tab, and code fences are left alone. Return on an empty
*nested* item now steps out one level instead of letting go of the list
(`ListContinuation.Unwrap.keep`; the phone's editor applies the same
decision). How lists, the agenda and filter blocks *show* parent and child
tasks is not decided yet.

Same date, owner — **notes and subtasks in lists.** A row whose task has
notes (lines indented under it that are not list items) or subtasks (task
lines nested under it) carries a summary marker (`▸ 1 note · 1/2 done`);
a click opens the row, showing the notes and the direct subtasks with their
own boxes; a click folds it. A block line `show:notes` opens every row by
default (a click then folds one); it is a display line, removed before the
query runs. The detail is read from the note file, not the index, so a
subtask the query did not match (ticked, under `is:open`) still shows and
counts; a subtask that does match is listed only under its parent when the
parent is listed (in the agenda, within the same group), and as its own row
otherwise. The agenda
panel shows the same marker and opens rows, but still never edits. Task row
labels now use the task's own line only.

- **2026-10-23 (priority).** The refusal of priorities above was for v0 and
  named its own door — "if wanted later, `priority:high`, same convention" —
  and the owner's case opens it: more tasks can be done on a day than ideally
  should be, and the undated agenda group had an order nobody chose (the
  index's path-and-line order). One word field on the task line,
  `priority:high` or `priority:low`, whitespace-delimited and
  position-independent like a date; no word is the ordinary level, and so is
  `priority:normal` for whoever writes the default out. Indexed as a word
  fact (`org.memory.tasks.priority`, `high`|`low`) and one derived sort key
  (`org.memory.tasks.order` = `<rank>|<due or ~>`, rank 1/2/3), so every
  task list — the agenda's groups and `filter` blocks — sorts by a single
  predicate: level, then due date, then the line's place in its note, which
  stays the person's own ordering. The derived key was chosen over a two-key
  kernel sort because it needs no kernel or bindings change; its cost is one
  fact per task line, stated in the indexer (version 3, so the namespace is
  re-derived on upgrade). Operators `priority:high|low|none|normal` on
  `type:task` (negating a level falls to the read, as `-due:week` does).
  `/high` and `/low` write the word in ONE Enter: a field whose provider
  offers exactly one value is inserted from stage one, on both the
  SuggestSession and the editor-completion routes; the two-stage menu is
  unchanged for dates. Styled as a token (high the marker colour; low and
  normal muted); rows carry a thin bar (error for high, outline for low) in
  the agenda panel, filter blocks and both mobile screens; the row label
  hides the word as it hides the dates. **Not built, each on purpose:** a
  flag (one dimension until use shows another); drag-to-reorder (the order
  must be derivable from the text or it does not survive a sync); urgency
  scores (refusal unchanged); and click-to-cycle on the token — the
  decoration seam runs every registration's `activate` in one chain on any
  click, so a cycling activation would fire on every checkbox tick; editing
  the word is the way, and a per-registration click is a seam change for a
  later round. Gate: indexer facts and key order; the search operator over
  the scan oracle; the compiled operator and the five-row ordering against
  the real kernel in `FilterBlockTest`; the one-Enter field in `SuggestTest`;
  the field registry's three entries.

- **2026-10-26 (pills).** § 5's decoration seam gains a third rendering,
  `DecorationRender.Pill(tagLength)`, beside `Replace` and `StyleOnly`: the
  claimed characters render as themselves (zero-delta, so no offset-table
  entry and nothing for an IME to trip on), drawn as a two-part pill — the
  first `tagLength` characters the tag, the rest the body. Like `Replace`
  it gives way on the caret's line. Its first producer is the People
  plugin: `keyword: @handle` (owner: connections were not visible enough on
  task lines), registered ahead of the mention style so the pill takes the
  mention inside it, by the same rule the index uses (`*assign:* @x` is not
  a connection and draws plain). The shell carries the two parts as string
  annotations in the styled text, and every surface that lays the text out
  — the desktop field, the phone's painted overlay, Read view on both —
  paints the rounded backgrounds from its own layout (`Pills.kt`). The
  phone keeps its always-glyph checkboxes and still lets a pill go on the
  line being written (`plainPillLine`); being zero-delta, that moves
  nothing. The phone editor now draws every enabled plugin's decorations,
  as the desktop always has, so mentions are coloured there too. Gate:
  `ConnectionPillsTest` (what is a connection, where the marks land, the
  caret-line rule on both shells, Read coordinates) and
  `ConnectionPillsUiTest` (the tag's pixels in Read view and the editor,
  absent on the caret's line; fails with the painter disabled).

- **2026-10-26 (pill colours).** Owner, the same day: a colour per
  connection, set on its page, and grey for a keyword nobody defines.
  Recognisers stay pure over the line, so colour comes from the index: a
  registration may declare `PillTones` — a lookup and a function from each
  hit to `(key, PillTone)` — and `Pill` gains a `key`. Tones are names
  (`ACCENT`, `BLUE`, `GREEN`, `AMBER`, `RED`, `PURPLE`, `GREY`); the shell
  turns them into tints of a hue over its surface, so a producer never
  picks a pixel and both themes read. The shell runs the lookups off the UI
  thread as the vault cursor moves (`rememberPillTones`); a key the source
  does not answer is grey, and a registration with no source — or an index
  still being built — draws the accent, so nothing flashes grey on open.
  People: the connection page's `colour` property (form field "Pill
  colour"; `gray` forgiven; a name the pill cannot draw is left out and the
  pill takes the accent, not a problem fact, so a stray word never disables
  a connection), indexed as `org.memory.people.definition.colour`
  (indexer version 4, re-derived on upgrade); retired connections answer
  nothing and draw grey. Gate: `PillTonesTest` over the real kernel
  (colour, `gray`, no colour, an undrawable colour, retired, a typo, a
  changed page, the not-ready and unsourced cases, the form) and two
  `ConnectionPillsUiTest` rows that find the green, then the grey, tag and
  none of the accent; all four colour rows fail with every tone forced to
  the accent.

### The date language, and completion without a written date — 2026-10-30

Owner: `due:` and `done:` take the date language of [ADR 0028](0028-line-history-and-task-dates.md)
— days, rolling offsets (`-7d`), calendar periods (`thisweek`, `lastmonth`,
`2026-Q3`), `since:`/`until:` beside `before:`/`after:`, ranges `a..b` — with
a `weekStart` setting (Monday unless set to Sunday). § 4's "a malformed value
matches nothing" stands, and the search box and filter blocks now say which
days a value took and why a value means nothing. `done:` asks a new indexed
fact, `org.memory.tasks.completed`: the written `done:` date, else the day
the kernel's line history says a ticked line reached its text. The
`done:`-stamping on tick (§ 1) is unchanged — inference only covers ticks
made where nobody stamped.

### Moving a due date — 2026-10-31

Owner, after Obsidian Tasks' postpone button: a way to push a task's due date
forward inline, without retyping it. The prior-art note recorded Tasks' button
as living only on query results and unable to date an undated task; both are
widened here.

**The seam.** `EditorDecoration` gains `menu: (line, today) -> TokenMenu?` —
a registration's *own* list of choices for a line. Unlike `activate` it is
never chained: the shell asks one registration, shows its menu, and applies
the one `TokenMenuItem` chosen, whose `rewrite` is pure over the line. This is
the "per-registration click" the priority amendment named as a seam change
for a later round, and it is why the menu cannot fire when a box is ticked.
`today` is passed when the menu opens, so this action does not share § 1's
midnight gap. Shell helpers: `menuTokens` (the menu-bearing tokens in shown
coordinates, the caret's line included — style-only tokens stay drawn there),
`lineMenuAt` (the menu for the line at an offset, fence-aware) and
`applyMenuItem`, which writes nothing unless the range is still exactly the
line as it read when the menu opened.

**The tasks plugin's menu** (`dueDateMenu`) rides on the `due` registration.
A date still ahead is postponed from itself — +1/+2/+3 days, +1/+2 weeks,
+1 month — then Today, Tomorrow, and "Next Monday" (the `weekStart`
setting's day); a date already gone counts from today and lists the named
days first; an undated task is offered a first date. Every row shows the day
it writes; a row that would repeat the date already there, or a day an
earlier row offers, is left out. A typed field takes the `/due` phrases (and
their prefixes) or a step from the date being moved (`+3d`, `2w`, `1m`).
"Remove due date" takes the token and its leading space. Only the ISO token
is written — the date replaced where it stands, or appended — with no
rescheduled state and no postpone count: when a line changed is ADR 0028's
line history's to say.

**Where it is offered.** Desktop editor: an arrow drawn beside a hovered
`due:` token (an overlay — the line never reflows; centred on the digits,
half a cap height above the baseline, not on the line box; it never takes
the focus, since a focus change re-renders the line under the pointer); the
right-click menu of any task line ("Postpone due date…" / "Add a due
date…"); and the plugin's rebindable `dateShortcut`, Ctrl+Shift+D by default,
listed in the shortcuts window. `filter` block rows, in both modes, through
the checkbox's own stale-line and unsaved-edit guard. Phone: a long press on
the date opens the menu as a dialog; a short tap there still places the
caret. The desktop agenda panel's rows still only navigate.

Gate: `DueDateMenuTest` (rows for dated, overdue and undated tasks, dedupe
and group rules, rewrites, typed words, fence-aware line lookup, the stale
guard, a task row's menu and guarded write, the setting round trip);
`DueDateMenuUiTest` (arrow appears on hover beside and level with the date,
a mouse click opens the menu and a choice is on disk at once; the shortcut
dates an undated task from typed words; off a task line and with the plugin
off, nothing); `FilterBlockUiTest` (a row postpones its task in Read and
Edit mode). The tests caught two defects before shipping: a dropped first
row lost its group rule, and the stale guard accepted a line that had grown.
Not run: `MobileTokenMenuTest` (the phone's date targets and the
shown-to-source offset) compiles but was not executed this round, and no
row drives a real long press on a device.
