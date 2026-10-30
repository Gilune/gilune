# ADR 0028: Line history, and a date language for tasks

Date: 2026-10-30
Status: **Accepted** (owner, 2026-10-30) — the date grammar, the five
reader-facing items, and line history as "work it out, write nothing".
Delivered in one change the same day.

## Context

The owner asked how to find tasks completed today, then this week, then over
"the last week" — which might mean the last seven days, this work week, or
since Sunday. `done:` took `today`, `overdue`, `week` (today and the six days
after — a look *forward*), `before:`/`after:` with a day, and ISO days. Nothing
named a calendar period, nothing counted backwards, and a mistyped value
answered with an empty list indistinguishable from "nothing happened". The
same queries will be run by programs (a CLI, an MCP server), which cannot see
what `thisweek` resolved to unless the query says.

Separately: a box ticked in another app (Obsidian, a phone without the
editor) carries no `done:` date, so `done:` could never find it. The kernel
already holds every revision of every managed note — enough to say when a line
reached its current text, as `git blame` does.

## Decision

### 1. One date language for `due:` and `done:`

`resolveDateQuestion` (tasks plugin, `TaskDateQuery.kt`) is the one resolver;
every value is a span of days or "no date".

- **Days:** ISO `YYYY-MM-DD`; `today`, `yesterday`, `tomorrow`;
  `lastmonday`…`lastsunday` (most recent, today included); a bare weekday is
  the *next* one, as the `/` menu already meant.
- **Periods:** `thisweek`, `thisworkweek`, `thismonth`, `thisquarter`,
  `thisyear`; `lastweek`, `lastmonth`, `lastquarter`, `lastyear`; absolute
  `YYYY-MM`, `YYYY-Qn`, `YYYY`.
- **Offsets:** a trailing `+N`/`-N` with a required unit `d`, `w`, `m`, `y`,
  after any term or alone (`-7d` is from today). After a period it moves the
  day the period is taken around, so `thismonth-1m` is last month. Names
  contain no `-` or `+`, so a sign always means arithmetic — the owner's
  syntax point: `this-month-1m` read as one name.
- **Comparisons:** `since:` and `until:` include their term; `before:` and
  `after:` exclude it (the existing meaning); `a..b` runs from the start of `a`
  to the end of `b`.
- **Kept:** `none`, `overdue`, and `week` (the next seven days) unchanged.
- **Week start:** a Tasks setting, `weekStart` = `monday` (default, never
  written) or `sunday`. The work week is Monday–Friday within that week.

Every value compiles exactly — a GE/LE pair on the date predicate — so the
kernel decides, and a negated term is the kernel's complement.

### 2. A query says what it took

`SearchOperator.explain(argument)` (SDK) returns a `SearchExplanation` —
`description` ("done 26 Oct – 1 Nov 2026") or `problem` ("Unknown date
‘thismnth’ — did you mean thismonth? …"). `compileSearch` collects them into
`KernelSearch.explanations` / `problems` (negated terms prefixed "not"). The
search panels draw them under the box; a filter block draws the description
as its first reserved line and replaces its rows with the problem. A future
CLI/MCP gets the same two lists from the same call. A term with a problem
compiles to `Never`, so the account and the answer always agree.

`today` was already an argument of the search API (`compileSearch(today =)`),
so a fixed "today" for programs and tests needs no new door.

### 3. Line history: work it out, write nothing

The kernel derives, per managed note, when each body line reached its current
text (`index/history.rs`):

- replay the object's revisions in `(created_ms, revision_id)` order,
  line-diffing each against the last (shared prefix/suffix trimmed, LCS in
  the middle, a greedy in-order match past 4M table cells);
- a matched line keeps its date; a new line takes the revision's
  `created_ms` — **except** revisions with origin `external-import`
  (adoption), whose lines are undated: they may be years older than the day
  Gilune first saw them;
- finally the head is diffed against the body on disk; lines no revision has
  recorded are undated.

It is cached per object in `index.db` (`line_history`, index schema 14) and
extended by the revisions newer than the cached last one; a count that does
not add up (a synced revision older than the cache) replays from the start.
Derived and disposable like the rest of the index — never synced, never in
the vault.

Indexers receive it through two defaulted `NoteIndexer` methods
(`index_scoped_with_history`, `index_scoped_locations_with_history`); the FFI
`SourceLine` gains `since_ms: Option<i64>` (default null, so other
constructors are unchanged). The local-incremental wrapper folds each
location's line dates into its fingerprint, so a date change re-derives the
item even when its text did not.

### 4. Completion is a fact, not an edit

The tasks indexer (version 4) emits `org.memory.tasks.completed` on a ticked
item: the written `done:` date, else the day (device calendar) of the item's
first line in the history. `done:` queries ask `completed`; `done:none` is the
absence of it, so it no longer lists a ticked task whose date was inferred.
Nothing is written to the note: the owner chose "work it out, write nothing"
over stamping `done:` into files other apps edit.

## Consequences

- A tick made in another app is dated when the kernel **recorded** it — the
  next reconcile — not when it was typed. If Gilune was closed for a week, the
  date is the day it opened.
- Concurrent heads replay in time order; a line both branches wrote is dated
  by the first. The history is an interpretation for queries, not the record.
- Local-only notes (`gilune-sync: local`, `.gilune-local`) are never adopted,
  so have no revisions and no history: their ticks without `done:` stay
  undated.
- The scan path (`matches`) sees written tokens only. Every date value
  compiles exactly, so the kernel's answer — which includes inferred dates —
  is the one that counts; the scan path is an oracle for tests.
- The phone has no week-start control yet; it reads the stored setting, so a
  desktop change applies there.
- Cost: one replay per changed note per pass, cached; a note's first index
  after a rebuild replays all of its revisions once.

## Evidence

Kernel: `index/history/tests.rs` (adoption undated, an edit dates only its
lines, a later tick moves only its own, unrecorded lines unknown, cache
extension equals a full replay, a late synced revision forces a replay) and
`incremental/tests.rs` (a date change re-derives exactly the line and its
item). Shell: `TaskDateQueryTest` (every form, week start, errors,
descriptions), `TaskCompletionTest` (a tick in another app is `done:today`,
an adopted tick is `done:none`, a written date wins, explanations, the
week-start setting reaching the query, a filter block's hint), and
`QueryStateTest` (the line under the search box). Each rule was falsified by
disabling it: the fingerprint's history, the adoption exception, the
indexer's inference, and the week-start wiring.
