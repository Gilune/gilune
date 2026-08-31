# Prior art: Obsidian Tasks, and neighbouring plain-text task conventions

Research note for the Tasks-plugin design round, 2026-08-31. Distilled for a
designer, not an implementer. No Gilune implementation detail here.

## 0. Source quality — what I read and what I could not

The Obsidian Tasks user guide is an Obsidian Publish site, but it **does**
convert to readable text through a normal fetch. I read the pages listed in
§ Sources directly; the syntax below is taken from those pages, not inferred.

Gaps and caveats:

- **Quick Reference page** — I could reach it, but could not extract its
  summary tables verbatim. Everything in them is covered by the per-topic
  pages I did read, so no fact below rests on it.
- The comparative section (§ 3) and the criticism section (§ 4) were each
  researched against primary sources — NotePlan's and Logseq's own
  documentation, the todo.txt spec, Dataview's docs, and the plugin's GitHub
  issues. Per-source specifics are cited in place.
- **Release dates are unreliable.** Version *numbers* below are sourced;
  the *dates* attached to releases came back mutually inconsistent and are
  omitted rather than guessed.
- Anything I could not confirm is labelled **unverified** inline. I have not
  guessed anywhere. Two claims in the original brief turned out to be wrong
  and are corrected in place — see § 3.2 (NotePlan does **not** use
  `@due(...)`) and § 4.1 (the performance cost is in rendering, not search).

---

## 1. Obsidian Tasks: the shape of it

### 1.1 What counts as a task

A task is a single-line Markdown checklist item. List markers `-`, `*`, `+`
and numbered lists all work.

```markdown
- [ ] take out the trash
```

Hard constraints the plugin states about itself:

- **Single-line only.** Multi-line descriptions are not supported.
- **Metadata is parsed from the end of the line backwards.** Consequently
  block links and tags must come *after* dates and priorities, or parsing
  breaks. This is a real fragility: the format is position-sensitive despite
  looking free-form.
- Tasks are not read from Canvas cards, code blocks, or comments.
- In Live Preview inside callouts, clicking a checkbox may fail to write the
  done date; the command must be used instead.

**Global filter.** Optionally, only lines containing a configured string (the
common choice is `#task`) count as tasks. Default is unset, meaning *every*
checklist item in the vault is a task. A setting strips the filter string from
the displayed description. Stated downside: a tag used as the global filter
cannot take sub-tags, and changing the setting needs a restart.

This is the plugin's answer to a question any tasks feature must answer: is a
checkbox automatically a task? Tasks defaults to yes and lets you opt into no.

### 1.2 The emoji signifiers (the default format)

Dates, all strictly `YYYY-MM-DD` with leading zeros, appended to the line:

```markdown
- [ ] #task Has a created date ➕ 2023-04-13
- [ ] #task Has a scheduled date ⏳ 2023-04-14
- [ ] #task Has a start date 🛫 2023-04-15
- [ ] #task Has a due date 📅 2023-04-16
- [x] #task Has a done date ✅ 2023-04-17
- [-] #task Has a cancelled date ❌ 2023-04-18
```

Six priorities, signifier only, no value:

```markdown
- [ ] #task Lowest priority ⏬
- [ ] #task Low priority 🔽
- [ ] #task Normal priority
- [ ] #task Medium priority 🔼
- [ ] #task High priority ⏫
- [ ] #task Highest priority 🔺
```

Note the ordering wrinkle: an *unmarked* task ranks between low and medium, so
🔽 sorts **below** no-priority-at-all. That is a deliberate design choice — it
lets you demote things without having to promote everything else — and it is
also exactly the kind of rule nobody infers from reading the vault.

Recurrence, completion behaviour, and dependencies:

```markdown
- [ ] #task Is a recurring task 🔁 every day when done
- [ ] #task Remove this task when done 🏁 delete
- [ ] #task do this first 🆔 dcf64c
- [ ] #task do this after first and some other task ⛔ dcf64c,0h17ye
```

Three semantic layers share one flat line: temporal (dates), ordinal
(priority), and relational (ids, dependencies). Only the first is legible
without the manual.

**Free-text dates are rejected.** The docs are explicit that `📅 today` is not
understood and such a task will not be found by searches. Every date in the
file is absolute. This matters enormously — see § 2.

### 1.3 The Dataview alternative format

Tasks can read and write Dataview-style bracketed inline fields instead:

```markdown
- [ ] #task Has a created date [created:: 2023-04-13]
- [ ] #task Has a due date [due:: 2023-04-16]
- [x] #task Has a done date [completion:: 2023-04-17]
- [ ] #task Is a recurring task [repeat:: every day when done]
- [ ] #task Remove this task when done [onCompletion:: delete]
- [ ] #task do this after first [dependsOn:: dcf64c,0h17ye]
```

Field names differ from the emoji concepts in places — done is `completion`,
recurrence is `repeat` — so the two formats are not a straight transliteration.

**The coexistence story is bad, and this is the single most instructive
failure in the whole plugin:**

- The choice is **global, not per-task**. Selecting Dataview format means the
  plugin stops reading its own emoji signifiers entirely.
- There is **no conversion facility**. A vault does not migrate.
- Reading mode and query results **still render in emoji format** regardless of
  the setting — a long-standing complaint (issue #1890).
- Field order still matters in both formats.

Support for the Dataview format is described by the maintainers as partial,
with differences from Dataview's own behaviour.

Lesson: retrofitting a second serialisation onto a format-coupled parser
produces two half-supported formats, not one flexible one.

### 1.4 Statuses

Core: `- [ ]` and `- [x]`. Beyond that, any character may sit between the
brackets, and each symbol is configured with a *name*, a *next symbol* (which
defines the toggle cycle), and a *type*.

Six types: `TODO`, `IN_PROGRESS`, `ON_HOLD`, `DONE`, `CANCELLED`, `NON_TASK`.
The type is what drives behaviour — crossing into `DONE` adds the done date,
leaving it removes the date again.

```markdown
- [X] Checked
- [-] A dropped/cancelled task
- [?] A question
- [/] A Half Done/In-progress task
```

**Critical readability point:** these require external CSS to display
correctly. The docs ship nine "status collections" mapping to third-party CSS
snippets and themes. Without the right snippet installed, `- [/]` and `- [?]`
are opaque characters in the reader's vault — and completely opaque in any
other editor. The plugin has effectively pushed a chunk of its semantics into
the user's theme.

### 1.5 The edit modal

Opened with a command; edits the task under the cursor, or creates one on a
blank line. Fields: description (resizable, accepts multi-line paste),
priority, recurrence, the six dates, dependencies (before/after), status.
Unused fields can be hidden.

Date entry in the modal is **much more forgiving than the file format**:

- exact — `2022-11-28`
- partial — `6 oct`
- relative — `today`, `tomorrow`, `Saturday`, `10 days`, `2 weeks`
- abbreviations — `td`, `tm`, `yd`
- a native date picker

Relative dates resolve into the future by default; an option disables that.
Fields carry access keys (Alt+letter on Windows). Enter or Apply writes back.

The important observation: **the modal is where the good date UX lives, and the
file never sees any of it.** Everything is resolved to ISO before writing.

### 1.6 Queries

A fenced block whose instructions are one per line, implicitly ANDed:

````markdown
```tasks
not done
due before tomorrow
sort by priority
group by filename
limit 10
```
````

Filter surface, abbreviated but representative:

```text
done | not done
no due date | has due date
due (on|before|after|on or before|on or after) <date>
due (in|before|after|in or before|in or after) <date range>
due date is invalid
happens before tomorrow           # matches start, scheduled OR due
no happens date | has happens date
priority is (above|below|not)? (lowest|low|none|medium|high|highest)
is recurring | is not recurring
is blocked | is blocking
status.type is IN_PROGRESS
description includes report
tags include #work
path includes GitHub
folder includes Projects
heading regex matches /Week \d+/
filter by function task.due.moment?.isSame(...)   # JavaScript
```

The same five operators apply to every date field (`due`, `done`, `scheduled`,
`starts`, `created`, `cancelled`), plus range forms — `last|this|next
week|month|quarter|year`, and numbered ranges `YYYY-Www`, `YYYY-mm`, `YYYY-Qq`,
`YYYY`.

Booleans: `AND`, `OR`, `NOT`, `AND NOT`, `OR NOT`, `XOR`, over parenthesised
filters.

```text
(no due date) OR (due after 2021-04-04)
(path includes GitHub) AND NOT (tags include #todo)
```

Sorting and grouping mirror the filter vocabulary — `sort by due`, `sort by
urgency`, `sort by function`, `group by folder`, `group by due reverse`, and
so on, stacked, first line wins, `reverse` as a suffix. Nested groups render as
h4/h5/h6 headings. The implicit default sort appended to every search is:

```text
sort by status.type
sort by urgency
sort by due
sort by priority
sort by path
```

Layout instructions control the rendering rather than the search: `hide due
date`, `hide backlink`, `hide edit button`, `hide postpone button`, `hide task
count`, `hide toolbar`, `show urgency`, `show tree`, `show group count`,
`short mode` / `full mode`.

Queries also support `#` comment lines and Mustache-style `{{! ... }}` inline
comments, plus presets, a vault-wide global query, and per-file defaults from
front matter. The query block has acquired a templating layer.

### 1.7 What the plugin writes, and when

Writes happen **on the action, not on file save**. There is no save-time
rewrite pass.

- Toggling (checkbox click, command, or context menu) flips the status symbol
  and appends the done date if enabled: `✅ 2021-04-09`. Moving out of `DONE`
  removes it. Cancelled dates behave likewise for `CANCELLED`.
- Created dates are added at creation time if enabled.
- **Recurrence spawns a new line.** Completing a recurring task writes the done
  date on the completed line and inserts a fresh instance — by default *above*
  the completed one, configurable to below:

```markdown
- [ ] take out the trash 🔁 every Sunday 📅 2021-04-25
```

becomes

```markdown
- [ ] take out the trash 🔁 every Sunday 📅 2021-05-02
- [x] take out the trash 🔁 every Sunday 📅 2021-04-25 ✅ 2021-04-24
```

The reference date advances by the rule; other dates keep their relative
offsets. `when done` switches the calculation base from the original date to
the completion date. A recurring task must carry at least one of due,
scheduled or start. Every rule begins with the word `every`:

```text
every 3 days
every weekday
every week on Tuesday, Friday
every month on the last Friday
every January on the 15th
every April and December on the 1st and 24th
```

- **Postpone** (⏩) appears only on rendered query results, never in the file
  or the modal. It shifts the earliest present date (due > scheduled > start);
  right-click offers the next six days, further dates, or removing the date. It
  cannot *add* a date to an undated task.

### 1.8 Urgency

A numeric score, lifted from Taskwarrior, summed over four components, used as
the second key in the default sort and displayable with `show urgency`.
Approximate weights as documented:

| Component | Sample scores |
|---|---|
| Due date | 7 days overdue 12.00 · today 8.80 · 14+ days out 2.40 · none 0.00 |
| Priority | highest 9.0 · high 6.0 · medium 3.9 · none 1.95 · low 0.0 · lowest −1.8 |
| Scheduled | today or earlier 5.0 · later 0.0 · none 0.0 |
| Start | today or earlier 0.0 · tomorrow or later −3.0 · none 0.0 |

So a medium-priority task due today scores 12.7. It is invisible unless asked
for, yet it silently decides default ordering — a hidden ranking function is
doing work the user never consented to and cannot see in the file.

### 1.9 Implied dates — the quietly good idea

"Use filename as default date": tasks in a file whose name contains a date
(`2022-10-12 Wednesday.md`, `20220612 - random thoughts.md`) inherit that date
as a **scheduled** date, but only if they have no scheduled/due/start date of
their own.

Crucially, **nothing is written to the line.** The date is implied, invisible
in the note, and visible only in the edit dialog and in grouping queries.
Restricted to configured folders; requires a restart; custom formats must match
the whole filename.

This is the one place the plugin derives metadata from context instead of
demanding it in the text — and it is the cheapest possible due date for a
daily-note workflow.

---

## 2. Inline date entry — the auto-suggest, in detail

This is the owner's headline, so here is the mechanism precisely.

### 2.1 Trigger

- **No trigger character.** The suggester activates on any line the plugin
  recognises as a task — starting `- [ ]`, `* [ ]` or `+ [ ]`, containing the
  global filter if one is set, and not of type `NON_TASK`.
- Active in Source mode and Live Preview.
- Setting **Minimum match length**, default `0`, range 0–3. At `0` the menu can
  appear immediately on a task line without any typing. Raising it is the
  documented workaround for the suggester swallowing Vim commands like `dd`.
- **Maximum number of auto-suggestions**, 3–20, default 20.
- The menu hides options already present on the line.
- Matching is on any part of the option text, not just its prefix.

### 2.2 The two-stage flow

Stage 1 — **choose the field.** Typing a fragment such as `du` narrows the menu
to entries like `📅 due date` and `⏳ scheduled date`. Accepting inserts the
signifier.

Stage 2 — **choose the value.** With the signifier inserted, the menu re-offers
itself with values appropriate to that field. For a date field it offers common
relative dates; for `🔁` it offers common recurrence rules such as
`🔁 every week`.

Navigation is arrow keys plus Enter, or a mouse click, or simply typing more
characters to narrow. When a `⏎` entry is shown at the top of the menu,
pressing Enter inserts a newline instead of accepting a suggestion — an escape
hatch for the "the menu is in my way" problem.

### 2.3 Natural language, and where it goes

Relative words are offered in the menu and **resolved to absolute ISO dates at
insertion time**. Documented examples, evaluated on 2022-07-11:

| Typed / chosen | Inserted |
|---|---|
| today | `📅 2022-07-11` |
| tomorrow | `📅 2022-07-12` |
| Monday | `📅 2022-07-18` |
| next week | `📅 2022-07-18` |
| next month | `📅 2022-08-11` |

Available for due (📅), scheduled (⏳) and start (🛫).

**The file never contains the phrase.** "Tomorrow" is a UI affordance with a
lifetime of one keystroke; the vault only ever holds `2022-07-12`. This is the
correct decision and it is the single most transferable idea in the plugin: a
relative date written into a file is a bug waiting for the calendar to turn
over, whereas a relative date offered in a menu is pure convenience.

The richer vocabulary (`6 oct`, `10 days`, `2 weeks`, `td`/`tm`/`yd`) lives
**only in the modal**, not in the inline suggester. The inline path is
deliberately narrower than the modal path.

### 2.4 What the inline approach costs

Evidence that an always-on, trigger-less suggester on task lines has real
friction:

- It **blocks other plugins' suggesters** on task lines — a `@`-triggered
  natural-language-dates suggester does not open on a task line, though it
  works on blank lines (issue #2780, and corroborated in the
  natural-language-dates plugin's own bug reports).
- It has had **stuck-menu bugs** after selection against newer Obsidian
  releases (issue #3041).
- It fires on **checklist items that are not tasks** (issue #3266).
- The minimum-match-length setting exists largely to get the menu *out of the
  way* of modal editing.

Owning the keystroke space on every task line is a strong claim, and the
complaints are all downstream of it.

---

## 3. Comparative sweep — plain-text readability

The test applied: **read the raw line in a dumb text editor with nothing
rendering it. Does a human understand it?**

### 3.1 Dataview inline fields

`Key:: Value` — the value runs to end of line. Three forms, and the third is
the interesting one:

```markdown
Basic Field:: Some random Value
I would rate this a [rating:: 9]! It was [mood:: acceptable].
This will not show the (longKeyIDontNeedWhenReading:: key).
```

Square brackets render **both** key and value; **parentheses hide the key** and
render only the value. For task lines the bracketed form is mandatory, because
the field is not the sole content of the line:

```markdown
- [ ] Send an mail to David about the deadline [due:: 2022-04-05].
```

Dataview also accepts bracket-free emoji shorthands, borrowed *from* the Tasks
plugin, mapping to its own textual names — `due` = 🗓️, `completion` = ✅,
`created` = ➕, `start` = 🛫, `scheduled` = ⏳:

```markdown
- [ ] Due this Saturday 🗓️2021-08-29
- [x] Task I finished ahead of schedule ⏳2021-08-29 ✅2021-08-22
```

Note the collision hazard: Dataview's due emoji is 🗓️, Tasks' is 📅.

**Entry: nothing.** Dataview ships **no autocomplete** for inline fields.
Field-name completion is a long-standing open request (issue #2497), and
third-party plugins (Dataview Autocompletion, Better Inline Fields) exist to
fill the gap. Front-matter properties do autocomplete, but that is Obsidian
core, not Dataview. *(Established from the issue title and plugin listings; I
did not open the issue page itself — treat as lightly sourced.)*

**Readability: excellent in brackets, a trap in parentheses.**
`[due:: 2026-09-01]` is self-describing to anyone, in any editor, with no
legend; the cost is verbosity — roughly double `📅 2026-09-01` — and noise when
several fields share a line. The parenthesis form deserves a flag: it is
designed so the key *vanishes* on render, which means **the raw file is
strictly more informative than the rendered view**. That is the right direction
for a plain-text-first product, and the exact inverse of what the Tasks emoji
format does.

### 3.2 NotePlan

Native, no plugin. State lives in the checkbox, dates are separate tokens:
open `- [ ]`, done `- [x]`, cancelled `- [-]`, **rescheduled `- [>]`**, and a
bare `-` is an ordinary bullet, not a task.

```markdown
- [ ] Buy #groceries for @alex >2021-04-24
- [>] Buy #groceries for @alex >2021-04-24
- [x] Buy #groceries for @alex <2021-04-23 @done(2021-04-24)
```

`>YYYY-MM-DD` schedules forward; `<YYYY-MM-DD` is the back-pointer left behind
when a task is moved; `@done(YYYY-MM-DD)` (optionally with `hh:mm`) is appended
automatically when the completion-date setting is on. Repeats are
`@repeat(occurrence/total)`, e.g. `5/10`. Inside daily notes `[[date]]` creates
a reference and `>date` does not — two date syntaxes, two meanings.

**Correction to the brief: NotePlan does not use `@due(...)`.** This was
checked specifically. The "Elements of a Task" reference lists no such token,
and a literal search of the task-management page's markup finds only `@repeat`.
**Scheduling *is* the due mechanism** — `>date` carries the role that `📅` does
in Obsidian Tasks. One early fetch summary asserted `@due` existed; that was a
model conflation and is rejected.

**Entry — directly relevant to our headline.** NotePlan has inline
autocomplete on the date token: as you type, a form opens to select a date, and
natural-language tokens are accepted — `>today`, `>nextweek`, `>Friday`. There
is also a date picker (`Shift+Cmd+D`) and drag-to-calendar. So a second,
independent product converged on *the same answer as Tasks' auto-suggest*:
relative words as input, absolute dates in the file.

**Readability: the best of the four.** `- [x] Buy milk >2021-04-24
@done(2021-04-24)` needs no legend — `>` reads as "goes to", `@done(...)` is
self-describing English, and the checkbox is ordinary Markdown that GitHub and
Obsidian both render. Only `@repeat(5/10)` is opaque, and `<2021-04-23` is
guessable rather than obvious. Being native also means there is no second tool
reading the file by different rules.

### 3.3 Logseq

Org-mode heritage in Markdown clothing. Two selectable keyword "flavours" —
the default `LATER` → `NOW` → `DONE`, or `TODO` → `DOING` → `DONE` — plus
`CANCELED`/`CANCELLED`, `IN-PROGRESS` and `WAIT`/`WAITING`. Priority is
`[#A]`/`[#B]`/`[#C]` after the marker. Dates are org lines inside the block:

```markdown
- LATER do grocery
- LATER [#A] big important and urgent thing
- eg: finish reading 1984 by 29th of May
  DEADLINE: <2021-05-29 Sat>
- eg: daily exercise routine at 7am
  SCHEDULED: <2021-05-26 Wed 7:00 .+1d>
```

Three distinct repeater kinds, and the distinction is real: `.+` repeats from
the date you *marked it done*, `++` keeps the same weekday, `+` repeats from
when it was originally scheduled. `DEADLINE:`/`SCHEDULED:` also work on
ordinary blocks, not just tasks.

Entry is by `Ctrl/Cmd+Enter` to cycle the marker, slash-commands (`/LATER`,
`/A`, `/Deadline`, `/Scheduled` — the date ones open a picker with an
"Add repeater" button), or typing the keyword literally.

**Worth knowing:** this describes the *file-based* Logseq. The DB version moves
status, priority, deadline and scheduled into **properties**, and tasks are
created by command rather than by typing `TODO`. That is a deliberate move
*away* from plain text — a cautionary data point about where keyword-in-text
systems drift when they grow.

**Readability: best keywords, worst dates.** `TODO`/`DOING`/`DONE` as bare
words are the most legible status markers in this survey — a stranger reads
them instantly, no legend, no glyph. But `SCHEDULED: <2021-05-26 Wed 7:00 .+1d>`
is inherited notation: a redundant weekday, angle brackets carrying an
org-mode active/inactive distinction Logseq never surfaces, and `.+1d` vs
`++1w` being genuinely obscure. It also costs a whole extra line per date, so a
task with both a deadline and a schedule is three lines, and nothing renders as
a checkbox outside Logseq.

### 3.4 todo.txt

A whole-file format rather than a note annotation. Position is meaning:

```text
(A) 2011-03-02 Call Mom +Family +PeaceLoveAndHappiness @iphone @phone
x 2011-03-03 2011-03-01 Review Tim's pull request +TodoTxtTouch @github
```

- `x ` at line start = complete
- `(A)`–`(Z)` priority, must be first
- creation date after the priority; on a completed line, the completion date
  comes first and the creation date second
- `+project`, `@context` anywhere after the prefix
- an extension convention of bare `key:value` pairs, both parts free of
  whitespace and colons, one colon separating — `due:2026-09-01`

Due dates are **not in the spec proper**; they are that extension convention.
Priority preservation on completion uses the same hatch: `pri:A`.

**Readability: excellent, and uniquely deliberate about it.** todo.txt is the
only format here that states readability as a design goal and then justifies
each choice by it: the file should be, in the spec's words, "human-readable
without requiring any tools other than a plain text viewer". The positional
rules exist for *sortability* — priority and dates lead so lines sort by
priority or time, and `x` both sorts to the bottom alphabetically and looks
like a filled-in checkbox. `due:2026-09-01` is the leanest self-describing due
date in this survey: one colon, no brackets, no glyph, greppable, sortable.

Two weaknesses. `x 2011-03-03 2011-03-01` is two undifferentiated dates whose
meaning is purely positional — nobody reads that correctly first time. And it
is not Markdown, so nothing renders it as a list anywhere.

### 3.5 Summary

| Convention | Due date | Raw legibility | Inline entry | Cost |
|---|---|---|---|---|
| Tasks emoji | `📅 2026-09-01` | needs a legend | auto-suggest, NL dates | opaque without the plugin |
| Tasks/Dataview | `[due:: 2026-09-01]` | self-describing | same suggester | verbose, bracket noise |
| Dataview alone | `[due:: 2026-09-01]` | self-describing | **none** | hand-typed |
| NotePlan | `>2026-09-01` | near-obvious | autocomplete, NL dates | app-specific `>` |
| Logseq | `DEADLINE: <2026-09-01 Tue>` | clearest label | slash-command + picker | whole extra line |
| todo.txt | `due:2026-09-01` | self-describing | client-specific | positional dates elsewhere |

Two conclusions fall out of this table.

**The emoji format is the only one a stranger cannot read.** Everything else
degrades gracefully to plain text; 📅 is a legend lookup for anyone who has not
memorised it.

**Good inline entry and good raw readability are independent axes.** Tasks has
the best entry and the worst legibility; Dataview alone has good legibility and
no entry affordance at all; NotePlan is the only one that scores well on both,
and it does so with a self-describing token plus natural-language autocomplete.
Nothing forces the trade-off the emoji format appears to make.

---

## 4. Criticism, complexity, and where the scope went

### 4.1 Performance

The best-evidenced complaint, and conceded by the maintainers rather than
merely alleged. The project's own README warns that editing performance
degrades badly when a query displays many hundreds or thousands of tasks, and
recommends capping results with `limit 100`.

- **#697** — "Investigate whether Tasks plugin affects editing speed in large
  vaults — Answer: it does". Root cause given there: the plugin tracks edits by
  reloading all files. Opened 2022, **closed as not planned**.
- **#3492** — ~40,000-note vault; Obsidian reported unusable for 3–10 minutes
  after startup, on both Android and Windows, and the problem disappears when
  Tasks is disabled. **Still open.**
- **#2375** — 2,800 notes, 6,500 tasks; freezes of 3–4 s up to 20 s on notes
  carrying eight or more query blocks. Closed, but never reproduced on demand.
- **#3434** — a *maintainer's own* issue, "Prevent Tasks freezing Obsidian when
  an empty query is used", proposing a default limit of 300 tasks as a
  deliberate breaking change, flagged as especially damaging for first-time
  users.
- **#3202** — file rename and delete taking 4–5 s against a normal 0.5 s.
- **Discussion #1343** — typing lag, which the maintainer attributes to an
  Obsidian bug around Live Preview caching open files; the workaround is to
  close all notes.

**Correction to my own first reading, and the important nuance:** the search is
not the bottleneck. In discussion #3244, against a user reporting ~3 minutes of
startup delay, the maintainer's reference vault of **~17,000 tasks searched in
about 80 ms**, and the actual culprit appeared to be conflicts with other
plugins. A forum user with 7,000+ tasks across 1,500+ files reports no lag at
all on an M2 Mac Mini — while having had significant slowness on a 2020 PC.

So the honest formulation is: **matching tasks is cheap; rendering many results
into the editor is expensive, and edit-tracking that reloads files makes
startup and file operations scale with vault size.** Slowness is real but
hardware- and query-shape-dependent, and the standard mitigations are advisory
(`path includes` to narrow, caution with `regex matches`) rather than
structural.

The design lesson survives the correction, but sharpens: **the cost to guard is
result rendering and whole-vault re-reads, not the matching itself** — and a
default query with no limit is a footgun the maintainers now want to remove
with a breaking change.

### 4.2 The emoji argument

**Be honest about the volume: the direct complaints are modest**, not a
pile-on. Individual threads are small — discussion #1208 ("emoji are
distracting") and issue #373 (a user disliking emoji in text documents, with a
maintainer conceding the palette is a little too bright) drew only single-digit
upvotes. Anyone claiming a groundswell is overstating it.

**The load-bearing evidence is structural, not anecdotal** — it is what the
project itself shipped in response:

- A **second serialisation format** (Dataview, added in 3.0.0) exists largely
  to escape the emoji — then undermined by results rendering in emoji anyway
  (issue #1890).
- **obsidian-tasks-custom-icons**, which replaces the emoji with monochrome
  icons matching the text colour, was **adopted into the obsidian-tasks-group
  organisation** in 2024. A maintainer-blessed de-emojifier is a strong signal.
- Discussion #789 asked for faster emoji insertion — a user noting it would be
  quicker to type ten characters than open the edit modal — and is marked
  *solved by auto-suggest*. **The headline entry feature exists in part to
  compensate for the format being painful to type.** Though #3806 then reports
  the suggester popup itself as distracting.
- Discussion #167 asked for custom emoji or plain text (e.g. `>` for due).

The counter-arguments are real: emoji are compact, language-neutral, scannable
once learnt, and never collide with prose the way a bare keyword can. One user
in #373 specifically warned that textual signifiers cause **line-wrapping on
mobile** — a genuine cost for a phone-first product.

The asymmetry that matters: emoji are pleasant **to read once learnt** and
unpleasant **to type and to encounter cold**. A good suggester fixes the
typing. Nothing fixes the encountering-cold.

**Unverified, and deliberately not asserted:** I found no evidence tying screen
readers or accessibility complaints to Tasks' emoji specifically. There are
general Obsidian screen-reader complaints, but nothing connects them to this
format — do not use accessibility as an argument here without fresh evidence.

### 4.3 Query complexity

The query language is a bespoke DSL with its own filter, sort, group and layout
vocabularies, boolean algebra, regex forms, date-range grammar, presets, a
global query, per-file defaults, comments, Mustache placeholders, and — when
all that runs out — `filter by function`, `sort by function` and `group by
function`, which are JavaScript against a task object model.

That escape hatch is the tell. A DSL that needs an embedded scripting language
has conceded that its surface will never be complete, and has thereby acquired
two languages to document, two to learn, and two to keep stable.

**And then it acquired a security problem.** `filter by function` arrived in
7.21.0; by **8.0.0 JavaScript in queries was disabled by default on security
grounds** — because query text arrives inside synced Markdown, so a malicious
query in a shared or synced vault could execute against it. For a local-first
product that syncs Markdown between devices, this is the most directly
transferable warning in the whole survey: **executable content embedded in
syncable documents is an attack surface, and the plugin walked into it and had
to walk back out.**

The documentation is correspondingly large — Basics, Advanced, Scripting,
Reference, Status Collections and How-Tos — for a feature whose core is "show
me what is due". In fairness, **I found no user complaints about documentation
size**; the docs are more often praised than criticised, so treat the size as
my observation about scope, not as a reported grievance.

Direct evidence of the learning curve is softer than the performance evidence:
guides routinely note it, and at least one blogger (Robb Knight) moved to
another tool citing the simplicity of its UI. That is one person's stated
preference, not a trend.

### 4.4 Where scope crept

Reading the surface as an arc, the plugin grew from *checklist items with
dates* to:

1. six date types (due, scheduled, start, created, done, cancelled)
2. six priorities with a non-obvious ordering rule
3. recurrence with a small natural-language grammar and line-spawning
4. **task dependencies** — `🆔` / `⛔`, with blocked/blocking filters (6.1.0)
5. **on-completion actions** — `🏁 delete` (hide/show on completion, 7.9.0)
6. **custom statuses** with names, types and next-symbol cycles, incl.
   `ON_HOLD`
7. **status collections** — nine third-party CSS packs to make (6) visible
8. a **second serialisation format** (Dataview, 3.0.0), partially supported
9. **urgency scoring** ported from Taskwarrior, on by default, invisible — and
   issue #3371 calls the algorithm somewhat arbitrary
10. **Query File Defaults** (7.15.0), presets (`preset <name>` and
    `{{preset.<name>}}`), a global query, comments, Mustache placeholders
11. **JavaScript** filtering, sorting and grouping (7.21.0), then disabled by
    default for security in 8.0.0
12. a **Query Wizard**, a column/board view with drag-and-drop, translations
    into eight-plus languages
13. UI accretion in results — postpone, edit button, toolbar, tree view, short
    mode, group counts

Items 4, 5, 9 and 11 are a project-management tool wearing a note plugin's
clothes. Item 7 is the clearest smell: a feature whose semantics only exist if
the user installs someone else's stylesheet. Item 9 is the subtlest: a scoring
function the maintainers themselves describe as arbitrary, silently ordering
the user's day.

**In fairness, the maintainers do push back.** Issue #2920 — user-defined
custom fields with their own emoji — was **closed as not planned**. The
creep is real but it is not indiscriminate.

### 4.5 The 20% that delivers the 80%

Triangulating the docs' own "daily agenda" recommendation, the getting-started
path, and what users describe doing:

1. `- [ ] thing 📅 <date>` — a due date on a checklist line.
2. Toggling done, with the done date written automatically.
3. Four queries, which are really one query with four date filters: overdue,
   due today, upcoming, and no date.
4. Recurrence — the one advanced feature that repeatedly earns its keep,
   because re-typing a weekly chore is the thing people actually hate.

Everything else is optional depth. Notably the canonical daily-agenda layout
the docs recommend is *just due-date filtering* — no priorities, no statuses,
no dependencies, no urgency. Third-party guides converge on the same shape: one
dashboard note with a few query blocks (overdue / today / this week) and the
advice to add only metadata you will actually query.

**The refusers are as instructive as the users.** At least one plain-text
advocate rejects the plugin outright on interoperability grounds — the
objection being that managing tasks through a plugin confines the system to one
app, and that interoperability outweighs features. That is precisely the
customer a vault-first product is for, and the emoji format is what loses them.

**Unverified:** no usage survey or telemetry exists that I could find; this 20%
is inferred from documentation emphasis, guide convergence and community
discussion, not measured. Also unverified: no citable Reddit threads surfaced
despite repeated searching, so I make no claim about sentiment there.

---

## 5. What inspiration means here

Recommendations for a deliberately small, from-scratch tasks feature. Each is
one sentence of rationale.

**Take:**

1. **Take the two-stage inline suggester** — type a fragment, pick the field,
   pick the value, because it turns metadata entry into two keystrokes without
   the hand ever leaving the line, and both Obsidian Tasks and NotePlan
   converged on it independently.
2. **Take "natural language is UI, never storage"** — offer `tomorrow` and
   `next monday` in the menu but resolve them to an absolute date at the
   instant of insertion, because a relative date persisted into a file rots
   silently the moment the calendar turns, and every system surveyed that
   accepts natural language still stores ISO.
3. **Take write-on-action stamping** — the done date on completion and the next
   instance on recurrence are both written at the moment of the user's action
   rather than by a save-time rewrite pass, which keeps the file honest and
   avoids a background process that rewrites documents the user is editing.

**Adapt:**

4. **Adapt the signifier to be readable cold — a self-describing key, not an
   emoji** — something in the shape of `due:2026-09-01` survives Notepad, grep,
   and a stranger's first reading, whereas 📅 is a legend lookup that the
   plugin's own community answered by building an emoji-replacement project
   which the maintainers then adopted.
5. **Adapt the implied-date idea and let it cover more than filenames** —
   deriving a date from the note's own context and writing *nothing* into the
   line gives the commonest case a due date for free, and is the one place the
   plugin lets the vault stay clean.
6. **Adapt querying down to a few named, bounded views rather than a DSL** —
   overdue / today / upcoming / undated is what the canonical agenda actually
   is, and a bounded view avoids the failure the maintainers are now fixing
   with a breaking default limit, because the cost is in rendering results, not
   in finding them.

**Refuse:**

7. **Refuse anything whose meaning does not survive reading the file** — that
   single rule rejects the emoji-only signifiers, the CSS-dependent custom
   statuses, the opaque dependency ids (`⛔ dcf64c` cannot be resolved by
   reading), and the invisible urgency score that silently orders the user's
   day; each is meaning parked outside the vault, and the vault is the product.
8. **Refuse executable content inside documents that sync** — Tasks added
   JavaScript query functions and had to disable them by default on security
   grounds once query text was arriving inside synced Markdown, which is
   precisely our threat model and a mistake available to be skipped rather than
   repeated.

**The one-line version:** copy the entry affordance, refuse the storage format.

---

## Sources

Read directly (Obsidian Tasks user guide, `publish.obsidian.md/tasks/`):

- `/Introduction`
- `/Getting+Started/Getting+Started`
- `/Getting+Started/Dates`
- `/Getting+Started/Priority`
- `/Getting+Started/Recurring+Tasks`
- `/Getting+Started/Statuses`
- `/Getting+Started/Global+Filter`
- `/Getting+Started/Use+Filename+as+Default+Date`
- `/Editing/Auto-Suggest` (read twice, second pass for the step-by-step flow)
- `/Editing/Create+or+edit+Task`
- `/Editing/Toggling+and+Editing+Statuses`
- `/Editing/Postponing`
- `/Queries/About+Queries`
- `/Queries/Filters`
- `/Queries/Sorting`
- `/Queries/Grouping`
- `/Queries/Layout`
- `/Queries/Comments`
- `/Advanced/Urgency`
- `/Advanced/Daily+Agenda`
- `/Reference/Task+Formats/About+Task+Formats`
- `/Reference/Task+Formats/Tasks+Emoji+Format`
- `/Reference/Task+Formats/Dataview+Format`
- `/Reference/Status+Collections/About+Status+Collections`
- `/Quick+Reference` — reached, tables not extractable; nothing here depends
  on it

Comparative sources (§ 3), all primary:

- Dataview — `blacksmithgu.github.io/obsidian-dataview/annotation/add-metadata/`
  and `.../annotation/metadata-tasks/`, plus their Markdown sources in the
  repo
- NotePlan — `help.noteplan.co` articles 42 (elements of a task), 110 (how to
  schedule tasks), 63 (project notes and backlinks), 106 (recurring todos),
  52 (tasks, events and reminders), and `noteplan.co/task-management`
- Logseq — the docs repo sources `logseq/docs/master/pages/Tasks.md` and
  `db-version-changes.md` (the rendered docs site is an SPA and was not
  usable; the repo source it is built from was read instead)
- todo.txt — `github.com/todotxt/todo.txt` and its README

Criticism sources (§ 4) — obsidian-tasks-group/obsidian-tasks unless noted:

- README — the `limit 100` performance advice
- #697 editing speed in large vaults (closed as not planned) · #3492 startup
  slowness, ~40k notes (open) · #2375 freezes with many query blocks · #3434
  maintainer's proposal of a default 300-task limit · #3202 slow rename/delete
- Discussion #1343 typing lag · Discussion #3244 the ~17,000 tasks in ~80 ms
  counter-measurement · forum.obsidian.md thread on no lag with 7,000+ tasks
- #373 and Discussion #1208 emoji dislike (both small) · Discussion #167
  custom emoji or text · Discussion #789 emoji insertion, solved by
  auto-suggest · #3806 suggester popup distracting · #1890 Dataview format
  still renders emoji
- obsidian-tasks-group/obsidian-tasks-custom-icons — monochrome icon snippets,
  adopted into the organisation
- #2780 auto-suggest blocks other plugins' suggesters · #3041 menu stays open
  · #3266 fires on non-task items
- #3371 urgency algorithm described as arbitrary · #2920 custom fields closed
  as not planned
- blog.plaintextpaperless.com — rejecting the plugin on interoperability
  grounds · rknight.me — moving to another tool

Sources reached but not fully read, and not relied upon: the Quick Reference
tables; forum threads 48486 and the "Emoji as task symbols" thread (both
returned HTTP 503, cited only via search snippets); Dataview issue #2497.
