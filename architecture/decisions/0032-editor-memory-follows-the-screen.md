# ADR 0032: Editor memory follows the screen, not the note

Date: 2026-11-01
Status: **Proposed — staging ratified** (owner, 2026-11-01: "Ok let's do
it"). Each stage below is gated on its own measurement; stage 4 is gated on
a spike. Accepted when the targets are met.

## Context

Gilune holds plain text, and the owner's rule is that it should be light
(the kernel is in Rust for that reason). The 2026-11-01 measurements (09 §
Desktop footprint) show how much a note costs while it is open on the
desktop. They used a 64 KB, 1,500-line note, drawn on the processor and
compared with an empty window at ~140 MB:

| Shown | Extra memory | Why |
|---|---|---|
| Plain text in the editor | +22 MB | the whole buffer is one Skia paragraph |
| The same bytes as styled Markdown | +70 MB | ~6,300 style runs at ~7.5 KB native each |
| Presentation (Read) mode | +136 MB | `MarkdownBody` is a plain `Column`: every block is composed and laid out |
| 150 characters typed into it | 175 → 372 MB, 270 after a full GC | each keystroke lays the whole note out again; the dead layout waits for a cleaner, and the allocator keeps its high-water mark |

None of this is a leak: every layout is either live or waiting for the
collector. The cost comes from the design, which makes memory grow with the
note's length. A person sees perhaps 60 lines at a time, so the editor
should cost about the same for a 60-line note and a 6,000-line one.

The editor is a single legacy `BasicTextField(TextFieldValue)` inside a
`verticalScroll`, rendered through `MarkdownEditTransformation` (Compose
1.8.2, `NoteEditor.kt`). Every change re-runs the transformation over the
whole buffer: it parses each line, styles it, and places the decorations
and their offset table. The shell itself calls `filter` at seven further
sites per composition. About twenty features ask geometry of the one
`TextLayoutResult`:

- caret-follow scrolling;
- the due-date chip and token menus;
- click → offset;
- the editor's own Up/Down caret movement;
- the reveal pulse;
- find-match shading (`getPathForRange`);
- the `/` and completion popups;
- collapsed filter blocks;
- the dictation ghost.

The phone editor (`MobileEditor.kt`, ADR 0017/MS1) is a different field
(`TextFieldState` + `OutputTransformation`). It paints its styling as a
second whole-buffer `BasicText`, so it may pay twice. It has not been
measured.

## Decision

**The memory an open note costs is bounded by what is on screen, not by
the note's length.** Targets for the 64 KB note, measured with the same
probe method, against an empty window:

- editing: ≤ +15 MB;
- presentation mode: ≤ +15 MB;
- after 150 typed characters and a settle: within 15 MB of where typing
  started.

The cheapest levers come first. Each stage ships only if its measurement
moves, and the next stage starts only if the targets are still missed.
ADR 0012 holds throughout: the Markdown text is always the document, and
no stage introduces a document model.

### Stage 1 — presentation mode lays out what is visible

`MarkdownBody` renders its blocks through a `LazyColumn`, so only blocks
on or near the screen are composed. A lazy list cannot sit inside a
`verticalScroll`, so the list becomes the scroll container:

- `Presentation.kt` and the phone's `NotesScreen.kt` give up their outer
  `verticalScroll`, and their header becomes the list's first item;
- short, fixed previews (the appearance sample in `GeneralSettings.kt`)
  keep a non-lazy form.

Presentation has no caret and no cross-block selection, so nothing depends
on every block being laid out.
Things to keep working:

- reveal-at-line scrolls to a block index (`animateScrollToItem`);
- a click still acts;
- filter-block rows still render.

This also benefits the phone, whose Notes Read view draws through the same
`MarkdownBody`. Expected: +136 MB → near the plain-editor figure.

**Delivered 2026-11-01; target met.** `LazyMarkdownBody` (jvmShared
`MarkdownView.kt`) shares one per-block renderer with `MarkdownBody`.
Presentation (`Presentation.kt`) and the phone's Read view
(`NotesScreen.kt`) use it; their title and buttons are its header item.
Presentation's arrow and Page keys scroll the list.

Measured with the same probe method, two runs each: the 64 KB note
presented went from +141 MB to **+13–15 MB** over an empty window.

`PresentationModeUiTest.aLongNoteComposesOnlyWhatIsOnScreenAndPageDownStillScrolls`
pins the behaviour. It was falsified against the old column, which
composed all 2,000 blocks. The phone was not measured or run on a device;
it compiles.

### Stage 2 — the allocator gives memory back

Add a Windows application manifest to the Rust launcher
(`.github/windows-launcher`) opting the process into the **Segment Heap**
(`<heapType>SegmentHeap</heapType>`, Windows 10 2004+). The NT heap keeps
its high-water mark. The Segment Heap decommits freed memory more
aggressively and fragments less, which is why Chromium-based browsers
adopted it. This addresses the "270 MB after GC" half of the churn, and it
helps every native allocator in the process: Skia, SQLite, the kernel and
the JVM's own malloc.

It costs one manifest and one `DesktopLaunchArgumentsTest`-style row that
proves the launcher embeds it. Expected: the settled figure after typing
falls toward the live figure. Untested — the first thing measured.
Rejected route: the per-executable registry switch (Image File Execution
Options) is a machine setting, not ours to write.

**Delivered 2026-11-01; the expectation was wrong.** What shipped:

- `.github/windows-launcher/gilune.manifest`, embedded as RT_MANIFEST 1 by
  `package-windows-linux.py`. It is the launcher's only manifest; the 1.3.2
  launcher had none.
- A package test that pins the single resource line and the manifest's
  single setting (`test_launcher_resource_embeds_the_segment_heap_manifest`).

How it was measured: two locally built launchers that differ only in the
manifest, with the same app jar. A `type:` probe appends 150 letters to
the 64 KB note from inside the app, so no keystroke reaches any window.
Reading the process heap's signature confirmed the heap each copy was
given. Four runs each:

- **At rest: 182 MB every run on the Segment Heap, 193–217 MB on the NT
  heap.** A small saving, and a consistent one.
- **After typing, a full GC and 3 minutes:** Segment Heap 297–419 MB, NT
  heap 315–433 MB. That is +115–237 MB kept on both, and the runs overlap.
  Nothing more is returned between the first and third minute.

So typing's cost is not freed memory the allocator sits on. Something
still holds it after a collection, and the Segment Heap cannot return
memory that is still in use. Stage 2 stays for its saving at rest. The
churn moves to stage 3 (fewer, smaller layouts), plus finding what holds
the memory. CPU cost was not measured.

The real packaging path was also checked. Run 41358723089 cross-built the
MSI on Linux, and its `Gilune.exe` carries exactly one manifest with the
setting. Started from the unpacked MSI on an empty vault, the app ran on
the Segment Heap and idled at 130 MB.

### Stage 3 — style only what can be seen

`MarkdownEditTransformation` takes a **style window**: the visible line
range plus one screen above and below. The transformed text and every
length-changing decoration (checkbox glyphs, collapsed blocks, the dictation
ghost) stay whole-document, so the offset table, clicks, find and the caret
are unchanged. What the window limits is the colour-only spans: muted
markers, links, task dates, mentions, decoration roles. None of them moves
a line. How many of the ~6,300 runs are colour-only is **counted before
building**: the stage is worth building only if they are most of them.

Two rules keep scrolling stable:

- **Metric-changing spans stay whole-document.** That means font size,
  weight and style: headings, `**bold**`, `*italic*`, and the italic
  muted front matter. These change how a line wraps. A span that sets both
  colour and metrics keeps its metric half everywhere, and only its colour
  is windowed. Off-screen text therefore wraps exactly as it will when it
  scrolls into view, and nothing jumps.
- **The window moves with hysteresis.** It is re-centred only when the
  viewport leaves its inner band, so scrolling costs a relayout every
  screen or so, not every frame. That relayout costs the same as one
  keystroke today.

Alongside the window, the transformation's result is computed **once per
buffer, caret line and window**, and the eight `filter` callers share it,
instead of re-parsing the document at each one.

Expected: styled +70 MB moves toward the plain +22 MB, by the colour-only
share of the runs. Each keystroke's dead layout shrinks by the same
proportion.

### Stage 4 — lay out only what can be seen (spike first)

This stage applies only if the targets are still missed after stages 1–3.
At that point the remaining cost is the whole-buffer plain layout (+22 MB)
and its churn. The candidate is a **block-windowed editor**:

- one `String` buffer, as now;
- rendered as a lazy list of blocks (paragraphs, list items, fences), with
  only the visible blocks laid out;
- the block holding the caret is a live text field over its slice of the
  buffer;
- every edit is applied to the one buffer, and blocks are re-derived from
  it.

Undo becomes ours, at buffer level. Selection becomes ours too: a range of
buffer offsets, drawn per block, with copy, cut, type-over and delete
applied to the buffer.

The spike must pass all of these before the stage is accepted, on a
6,000-line note:

1. **Typing, IME and caret:**
   - IME composition stays inside a block: CJK and dead keys on AWT, and
     the MS1 rows on Android if the phone follows;
   - Up/Down, Page Up/Down and Ctrl+Home/End cross blocks with the column
     kept.
2. **Selection and undo:**
   - mouse-drag and Shift+click selection across blocks;
   - Ctrl+A, copy and cut, and typing over a cross-block selection;
   - one Ctrl+Z undoes a cross-block replace.
3. **Every existing geometry consumer** listed in Context works per block,
   including find's Replace all as one edit and filter blocks collapsing
   in place.
4. **Feel and cost:**
   - scrolling a long note is no worse than today at the same window size;
   - memory meets the targets.

If the spike fails, the fallback is ADR 0012's own sanctioned outcome: a
**size threshold** above which the editor drops colour styling. Its value
is measured, not guessed.

## Alternatives considered

### A custom text surface

The editor would draw its own lines with `TextMeasurer`, laying out only
the visible ones, and take input through `PlatformTextInputModifierNode`.
That gives the most control and the most code: caret, selection, IME,
clipboard, accessibility and context menus would all be Gilune's. It is
rejected unless the block-windowed spike shows that Compose's fields
cannot be confined to a block.

### Collapse off-screen text inside the one field

The transformation already collapses filter blocks into reserved blank
lines. It could do the same to everything off screen. Rejected: the height
of hidden text is only known by laying it out, so estimates make the
scroll position jump. Selection across hidden ranges also turns odd.

### Forcing garbage collection after edits

Measured and rejected (2026-11-01). One GC every 10 keystrokes peaked
higher (401 MB), and each collection cost ~28 ms. Cleaners run
asynchronously, and the NT heap does not shrink anyway.

### Leave it

Rejected by the owner's rule. A long meeting note is ordinary use, and
+70–136 MB per open note for plain text is not light.

## Consequences

- Stages 1–3 are contained:
  - Stage 1 touches `MarkdownView.kt`.
  - Stage 2 touches the launcher.
  - Stage 3 touches `MarkdownEditTransformation`, plus a scroll-state input
    from `NoteEditor`.
  - Existing transformation tests keep their meaning: the default window is
    the whole document.
- A test pins Stage 3's invariant: for any window, the transformed text,
  offset mapping and metric spans are identical to the whole-document
  result, and only colour spans differ.
- Every stage is measured with the scratchpad probe method recorded in
  CLAUDE.md (installed-app copies, `JAVA_TOOL_OPTIONS`, jcmd). Results go
  into 09 § Desktop footprint.
- The phone is measured before it is changed, under the data-wipe rule:
  export the emulator's vault before any install or instrumented run. If
  the phone pays the doubled cost, Stage 3's window applies to its painted
  sibling `BasicText` too.
- Stage 4, if it happens, replaces the legacy `TextFieldValue` field on the
  desktop. That also retires the latent risk MS1 recorded: the desktop sits
  on the field whose composing it found corrupt.
