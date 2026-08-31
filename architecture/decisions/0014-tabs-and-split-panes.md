# ADR 0014: Tabs and Split Panes in the Document Region

Status: **Accepted** (owner ratification 2026-08-31, as proposed: one
buffer per note in v0; Ctrl+1…9 selects tabs).  
Validation: owner ratification; a shell-structure decision, not a spike, and
nothing here crosses the FFI. The drag gesture in stage three is gated on a
Compose spike before it is attempted, as ADR 0012 gates E3.

Amendments (owner, 2026-08-31, after stages M and M+1 shipped):

- The Compose-spike gate on the stage-M+2 drag gesture is **waived**;
  the gesture is implemented directly in the shell.
- Drag scope gains **reordering within a strip**, which the original
  decision left unstated; the tear-out and four-edge drop grid stay
  out, as decided below.
- Split weights are **adjustable by dragging the divider** between
  sibling panes, clamped so no pane collapses; adjusted weights persist
  through the existing layout serialisation. The rail divider is the
  shell's own (ADR 0011).

## Context

ADR 0011 fixed the desktop regions; the document region is one of them, and
it shows exactly one note, because `DesktopApp` holds exactly one
`EditorState`. Reading one note while writing another loses your place in
one of them. The owner's ask is side-by-side editing "a bit like VSCode":
panelling that exists only when something is open in it, and file names in
tabs so a file can be dragged out into a new panel.

Two shipped behaviours constrain the shape. ADR 0012's guard records a
file's hash when its buffer opens and refuses to overwrite a newer file, and
idle autosave writes without being asked; both assume one buffer per file.
Dictation inserts at the caret of the note in front of the user.

## Decision

### The model

- The region holds a **pane tree**: interior nodes are splits with an
  orientation and weights, leaves are **panes**. A pane holds an ordered tab
  list and one active tab; exactly one pane is active.
- A tab is a stable identity plus the note it shows. Its buffer is an
  `EditorState`, **one per open note**, held with the tree in a new
  `TabsState` — the only thing that opens, finds and closes buffers.
  `NoteEditor` is handed one and owns none. Per-note view state (Edit/Read,
  the properties strip) already lives on `EditorState`, so it is per tab and
  survives switching, as ADR 0012 requires; a new tab seeds Edit/Read from
  `Prefs`.
- A pane with no tabs does not exist: closing a split pane's last tab
  dissolves it and its sibling takes the space; closing the only pane's last
  tab restores the placeholder. No pane exists until a split is asked for —
  the owner's "optional panelling". There are no preview tabs: a click in
  the tree opens a permanent tab, and an open never replaces one.

### One buffer per note

- **A note may be open in at most one tab in v0.** Opening a note that is
  open anywhere focuses that tab and activates its pane; `Open to the side`
  on such a note **moves** its tab rather than copying it.
- Two buffers over one file would race: each holds its own hash-at-open,
  each autosaves on idle, and whichever writes first makes the other
  permanently stale. That guard was built for external writers —
  projection, third-party tools — and turning it on ourselves makes a
  stand-off out of an ordinary edit. Lifting the rule means buffers keyed by
  note identity, not by tab: one buffer, several views, sharing text and
  hash, with caret and Edit/Read per view — which is also ADR 0012's split
  source/preview view, and where it should first be built.
- Only a pane's active tab is composed, so only its autosave loop runs.
  **Deactivating a tab flushes it** through the same guarded write, so no
  buffer waits unsaved out of sight (preservation first). A refused flush
  leaves the tab dirty and stale, and **such a tab refuses to close**: its
  pane activates it and shows the stale-buffer banner. No gesture in the app
  discards an edit. Reactivating a clean tab re-checks the hash and reloads,
  which the existing effects do on composition.

### Tabs

- A tab strip renders at the top of each pane; the note action row below it
  is unchanged (path, dictate, Edit/Read, overflow) and acts on that pane's
  active tab. The label is the file stem, and where two open tabs share a
  stem both gain their parent folder.
- Unsaved shows as the dot the header carries today, moved into the tab;
  hover swaps it for a close ×, middle-click closes. A stale tab's dot takes
  the error colour, since it is asking for an answer from out of sight.
- Overflow scrolls horizontally with the active tab kept in view, beside a
  chevron menu of the pane's tabs so none becomes unreachable; tabs keep a
  minimum width rather than shrinking to slivers.
- Keyboard, handled at window level so a focused field cannot swallow it:
  Ctrl+Tab and Ctrl+Shift+Tab cycle the active pane's tabs in strip order
  (most-recently-used deferred), Ctrl+W closes the active tab, Ctrl+1…9
  select the nth, Ctrl+\ splits right. Tabs claim nothing already bound —
  Ctrl+N (new note), Ctrl+F (search) and Ctrl+P (quick open) are taken;
  Ctrl+Shift+C fell free with the capture bar (ADR 0011 amendment) — and
  moving focus between panes by keyboard waits for a reason to exist.

### Splitting

- v0 splits are **explicit**: `Open to the side` in the tree context menu,
  `Split right` in a tab's context menu and the pane overflow, Ctrl+\ for
  the active tab. Side by side only; stacking is deferred, though the model
  permits it.
- **Dragging a tab is stage three and a stretch goal**: onto another pane's
  strip to move it, onto a pane's right edge — a highlighted strip over its
  outer quarter — to split. The full VSCode drop-zone grid (four edges, drop
  into centre, tearing out of the window) is not v0. Compose offers nothing
  to reach for: its drag-and-drop modifiers carry transferable data between
  applications, while an in-window tab drag is a hand-written pointer
  gesture, an overlay drag image, hit-testing against bounds from layout
  callbacks, and edge auto-scroll. Fiddly enough to schedule alone, and
  never the only route to a split.

### Where an open lands

One rule for the tree, quick open, the search panel, capture and note
creation — they already share a single open callback: **reuse, else a new
tab in the active pane.** If any pane holds the note, focus it; otherwise
add a tab after the active one and activate it.

### Dictation, banners, dialogs

- The dictation session — recorder plus target tab — moves into
  `TabsState`; it is composable-local today, so switching tabs mid-dictation
  would orphan a live microphone. One at a time, and the words land in the
  note that started it.
- The stale-buffer banner is per tab, in that tab's pane. The properties
  dialog belongs to its pane's composition and is modal, so at most one is
  open and Apply reaches its own buffer.
- The status line renders inside `NoteEditor` today; with several panes there
  must still be one, so it moves out into the window scaffold below them.
- No new hazard reaches the kernel: with one buffer per note, the writers a
  file sees are those ADR 0012 assumed — this app, projection, other tools.

### Layout across launches

Device-local, in `Prefs`, keyed by vault path: layout is a device fact like
theme (ADR 0011). Persisted from stage two. Restoring is defensive — missing
paths are dropped silently, an unparseable value resets to one empty pane,
and a layout too large for a `Preferences` value keeps the most recent tabs.
Buffers are not restored; they were flushed, and tabs re-read from disk.

### Mobile

Not applicable — one note, no tabs, no panes. `EditorState`, `NoteEditor`
and the pane tree stay in the desktop source set; the shared set must not
learn what a tab is. If a mobile reader ever needs `EditorState`, it is
promoted alone.

## Alternatives considered

### No tabs — one note at a time, chosen from the tree

What ships today, and what Obsidian does on mobile. Rejected: it makes a
split the only way to see two notes, so every comparison costs a pane and
closing that pane loses your place — and the owner asked for names at the
top.

### A window per note

Rejected: a second window is a second shell — status line, key handling,
capture surface, its own view of the tree — and where the OS puts it is not
layout the app can restore. Multi-instance on one vault is already supported
(owner decision 2026-08-31), so anyone wanting a detached window can launch
the app twice.

### A general docking framework

Floating, dockable panels of the JetBrains kind. Rejected: ADR 0011 fixes
the regions, and a docking framework would own the layout that decision
defines. Nothing wants floating tool windows, and it is a large shell
dependency for a two-column need; the pane tree is the wanted part of
docking.

## Consequences

- `Main.kt`: `DesktopApp` holds a `TabsState` where it holds a single
  `EditorState`, the document region becomes a pane host, and the status
  line moves out of `NoteEditor` into the scaffold.
- `NoteEditor` keeps taking an `EditorState` and stops assuming it is the
  only one — footer and dictation leave it, and `Close` routes through the
  tab rather than emptying the buffer.
- `TreeState.selectedFile` becomes derived from the active pane's active tab
  instead of assigned on open, so the rail highlights what is in front.
- Nothing crosses the FFI, so the boundary suite is untouched. Tab and pane
  logic — routing, close refusal, pane dissolution, label disambiguation —
  is pure and tested without composition.
- Delivery is staged, each stage shippable alone and in order: **M** tabs in
  one pane; **M+1** a second pane by explicit split, with persistence;
  **M+2** drag. `Open to the side` appears in menus only from M+1.
- Same-note-in-two-panes and ADR 0012's split source/preview both wait on
  the shared-buffer work above, and should land together.
