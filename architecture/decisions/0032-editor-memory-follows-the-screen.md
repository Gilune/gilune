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

**Delivered 2026-11-01, with what the investigation found** (its numbers are
in the "Where typing's memory went" section below). Built as specified:

- `styleWindow` on the transformation, and `nextStyleWindow` for the
  hysteresis;
- the editor follows `bodyScroll` and the field's layout;
- the window starts at the top 8,000 characters;
- a one-entry cache in `filter`.

Tests pin the invariant: whatever the window, the text, the offset mapping
and every wrap-changing span are identical, and colour stays inside the
window's lines. Falsified with the window ignored.

Screenshots of a probe scrolled to the top, line ~800 and the end show
every line coloured.

On its own the window was a small win: live Skia objects at rest fell from
12.9k to 7.3k, and typing barely moved. The colour runs were not the cost;
the typing cost lay elsewhere.

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

**Refined 2026-11-01, after stages 1–3: the slice field.** Stages 1–3 met
the targets for ordinary notes. The long note still peaked at ~+130 MB while
typed into, because each keystroke lays out all 64 KB again. That costs
~330 ms of UI-thread time per keystroke, and the layout's working memory
does not shrink however promptly the dead layouts are collected. Two
cheaper ideas were measured and failed:

- **Splitting the text into paragraph ranges** (so Skia shapes one piece
  at a time) adds an empty line at every boundary: 565 lines against 558.
- **Collecting after every keystroke** still peaked at +100 MB.

A note of ~150 lines typed into at the same speed peaks at base + 50, and
that is the size of one slice. So the slice is what memory needs.

The smaller form of the block-windowed editor keeps **one
`BasicTextField`**, but its value is a slice of the buffer:

- **The slice** runs from one line start to another. It covers the
  visible text plus a screenful either side, and always the whole
  selection and any IME composition. It never cuts a fenced block.
- **Spacers** above and below the field are as tall as the text outside
  it. That text is measured with the same transformation and width, a
  chunk at a time, and cached until the text or the width changes.
- **Every edit** writes the slice back into the one buffer.
- **The slice moves** only when nothing is composing and no pointer is
  held, so a drag or an IME session never sees the field's text shift
  under it.

What it costs, from reading `NoteEditor.kt`:

- **Undo and redo become the editor's own**, kept against the buffer. The
  field's built-in history would restore an old slice.
- **Ctrl+A** selects the buffer (the slice grows to hold it).
- **Every function that mixes the buffer's text with the field's layout
  works in slice coordinates** through one mapping. That is
  `menuTokenUnder`, `caretAnchor`, `formatMenuItems`, `moveByLines`, caret
  follow, the reveal pulse, find shading, collapsed blocks, the date chip,
  the suggest popup anchor, and checkbox and hover hit tests: about a
  dozen functions.
- **Overlays drawn in the scroll box** (pulse, chip, collapsed blocks) add
  the top spacer.
- **Notes below a size threshold** keep the whole-buffer field, where they
  already meet the target.

The pass list above still gates it.

**Spike evidence for the spacers (2026-11-01, throwaway test, deleted).**
The 1,500-line note was measured with the editor's own style and
transformation at widths 420 and 900, whole and in pieces:

- **Pieces measured alone** keep the line count exactly. Their heights,
  though, lose 3–11 px at every piece boundary to line-height trimming at a
  layout's edges: 136 px over 50 pieces.
- **Pieces measured with one line of context on either side**, taking each
  piece's advance from line top to line top, came out exact for
  150-line pieces at width 420 (line 900 at 25,197 px both ways, total
  41,997 both ways). At width 900 they were 6–9 px short over the whole
  note; 40-line pieces were 15–25 px short. That is sub-pixel rounding at
  each boundary.

So the spacers are measured with context, and moving the slice also
anchors the scroll. The first visible line keeps its screen position, and
the scroll offset absorbs whatever the measurement missed.

Offsets in the transformed layout must go through the transformation's
mapping: a checkbox is one glyph for three source characters. The spike's
first figures were wrong for exactly that reason.

## Where typing's memory went (2026-11-01)

Owner target, set mid-investigation: an empty window at ~130 MB, and a note
open and being edited at **base + 50**. Found with the `type:` probe, class
histograms, NMT and thread dumps:

- **Java grew ~25 MB while typing** (the JIT, briefly), and its heap stayed
  fixed. The rest was Skia's native text layouts.
- **Each relayout of the 64 KB note costs ~330 ms of UI-thread time and
  leaves ~10 MB native behind.** That memory is freed only when the Java
  collector finds the wrapper dead. A small heap allocates slowly, so
  collections came far apart. Once typing stopped none ran at all, and
  75–100k dead Skia objects stayed waiting (+100–160 MB).
- **Skia's paragraph cache copied every text it laid out, keyed by the
  text.** An editor makes a new text with every keystroke, so it never
  hit, and it held ~45 MB.
- **The serial collector kept 59 MB of heap committed for 18 MB of live
  objects.**
- **A small young generation does not help.** Layouts survive long enough
  to be promoted, so only a full collection frees them.

Fixes:

- **`NativeGarbage`:** a full collection once 4 MB of thrown-away layout has
  piled up, and again when editing pauses, a note is put away, or
  presentation stops scrolling. Each takes 25–40 ms, off the UI thread.
- **Skia's paragraph cache switched off**, with a test that fails if Compose
  moves it.
- **`-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`** on both launch
  paths.

Final measurements (probe copies with the 4 MB budget; the empty window
was 134 MB):

| Scenario | Before | After |
|---|---|---|
| 6 KB note open | — | 142 MB (+8) |
| 6 KB note, typing peak | 271 MB | **180 MB (+46)** |
| 6 KB note after typing | 162 MB | 156 MB |
| 64 KB note open | 183 MB | 170 MB (+36) |
| 64 KB note presented | 280 MB | 142 MB (+8) |
| 64 KB note, typing peak | 370–490 MB | **250–297 MB — over target** |
| 64 KB note after typing | 296–340 MB | 186 MB (+52) |

The long note's typing peak is the one figure over target. It needs
stage 4's slice field.

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
