# ADR 0031: Find and replace

Date: 2026-10-31
Status: **Accepted** (owner, 2026-10-31) — editor-convention keys, all three
stages in one round, replace-across-notes inside the Search panel, a plugin
that can be switched off, every key reassignable.

## Context

The desktop had vault search (Ctrl+F, the query language of ADR 0015/0021)
and nothing that finds or changes text in the note being written. The owner
asked for Notepad++-style replace, then for the whole feature: search answers
"which notes are about this?", find and replace answers "where is this exact
text, and change it". They overlap at one point — choosing which notes a
replace touches — and are otherwise different tools.

The vault-wide half needs what ADR 0026 already built for People renames:
an immutable reviewed plan, version-checked application document by
document, refusal of unsaved buffers, durable undo, and normal revisions
that sync. Its kernel journal is provider-neutral; its host orchestration
is rename-shaped (a defining page, original/destination lookups).

## Decision

### 1. A bundled plugin, switched on by default

`org.memory.find-replace` ("Find and replace") declares two shell-published
slots, `editor.find` (the bar over a note) and `search.replace` (the
replace rows in the Search panel). The shell draws both only while an
enabled plugin contributes them. Off: Ctrl+F opens vault search as it used
to, the plugin's keys do nothing, and its rows leave the shortcuts window.
The matching core is the plugin's (`plugins/findReplace`, pure Kotlin, no
host), so the phone can adopt the same semantics later.

### 2. One matching language

A find is a pattern plus three switches: **match case**, **whole word**
(the match is not flanked by a letter, digit or `_`), **regex** (Java
syntax, `MULTILINE` so `^`/`$` are per line; replacement understands `$1`
and `${name}`; outside regex mode the replacement is literal). Empty
matches are skipped — a pattern that can only match nothing finds nothing.
An invalid regex is reported in words and matches nothing. Notepad++'s
"Extended" mode is not offered: regex covers `\n` and `\t`.

Find and replace work on the note's **body**. The title, front matter and
properties are outside them: renames and properties have their own guarded
paths, and `gilune-*` keys are protected by the kernel.

### 3. In the note: the find bar (Ctrl+F, Ctrl+H)

A bar across the top of the note: the pattern, "n of m", previous/next,
the three switches, close. Ctrl+H opens it with the replace row: the
replacement, **Replace** (the current match, then step on) and **All**.
Every match is shaded behind the text, the current one more strongly; the
current match's line shows raw Markdown (the caret-line rule of ADR 0012)
so a match inside a checkbox or a collapsed filter block is visible.
Stepping wraps at either end and moves the selection, so Esc leaves the
caret on the match. Enter / Shift+Enter step from the pattern field.
Replace all is one buffer edit — one Ctrl+Z puts it back — and an edit like
any other: the buffer goes dirty and autosave writes it. A note being read
(presentation mode) switches to editing when the bar opens. Each tab keeps
its own bar.

### 4. Across notes: replace rows in the Search panel (Ctrl+Shift+H)

No new rail destination (the rail was getting busy). The Search panel's
query keeps its meaning and becomes the **scope**: blank means every note,
otherwise the notes it answers. Under it the plugin's rows: pattern,
switches, replacement, and a preview of every match grouped by note with a
tick per note. **Replace in N notes** prepares a plan on ADR 0026's journal
for the ticked notes — provider `org.memory.find-replace`, configuration =
the pattern, switches and replacement — seals it and applies it. Each
document is version-checked against the bytes the preview read; a note with
unsaved edits is refused (save, then preview again); edits are body-only
spans. The result names how many matches in how many notes changed, and
**Undo** prepares and applies the journal's generic inverse. The plan is
also listed in Refactor history.

Discovery reads the notes in scope off the UI thread after typing settles.
It does not use the index to narrow candidates (regex and short patterns
cannot be answered by trigrams) — a vault-sized read per settled pattern,
stated as a cost, not hidden.

### 5. Keys: editor convention, every one reassignable

| Action | Default | Owner |
|---|---|---|
| Find in the note | Ctrl+F | plugin setting |
| Replace in the note | Ctrl+H | plugin setting |
| Replace across notes | Ctrl+Shift+H | plugin setting |
| Next / previous match | F3 / Shift+F3 | plugin setting |
| Search the vault | Ctrl+Shift+F | shell (was Ctrl+F) |

The plugin's five keys are rebound on its settings screen with the same
control Tasks and People use; the shortcuts window shows them as bound.
Find, replace and the step keys act only while a note is in front; with no
note open, the find key opens vault search. Ctrl+F with the plugin off opens
vault search.

## Consequences

- Muscle memory: vault search moves from Ctrl+F to Ctrl+Shift+F. The
  workspace and search guides and the rail tooltip say so.
- Project convention adopted alongside (09 § Keyboard shortcuts): every
  shortcut is reassignable unless a decision says otherwise. The shell's
  own keys (Ctrl+N, formatting, tabs, Ctrl+Shift+F) predate the rule and
  are not yet reassignable — a separate round (a shell keymap).
- The phone gets none of this yet; the core is shared so it can.
- Gaps: discovery is a scan, not an index query; a match spanning a front
  matter boundary is out of scope by construction; replacing inside a note
  that is open *and* dirty in another tab requires saving it first.
