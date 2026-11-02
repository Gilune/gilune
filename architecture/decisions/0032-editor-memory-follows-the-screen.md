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

**Delivered 2026-11-02 (owner: "Yes, build it") — smaller than planned.**
The field's value stays the whole buffer. Only what Skia lays out is a
slice, so undo, selection, Ctrl+A, copy and typing over a selection stay
the field's own, and none of the costs listed above arose:

- **Placeholders, not spacers.** The visual transformation replaces the
  text above the slice and the text below it with one zero-width
  character each. Each placeholder is a paragraph whose line height is
  the measured height of what it stands for, with `LineHeightStyle.Trim.None`.
  A spike laid such paragraphs out at exactly the height asked, 20 px to
  120,000 px. A paragraph boundary starts a new line, so the
  placeholders need no line breaks of their own and add no lines.
- **One offset table.** The placeholders are two more entries in the
  table checkboxes and collapsed blocks already use. So `decorationAt`,
  `menuTokens`, `collapsedBlocks`, the caret anchor, find shading and
  the date chip answer against the text actually laid out, given the
  slice. Overlays need no spacer added: the placeholders fill the height.
- **Chunks** (`sliceChunks`) are cut before a line where a hash of it and
  the line above lands on one in 32. That is about 32 lines a chunk, never
  inside a fence, with a forced cut after 160 lines. The cuts depend only
  on the text around them, so typing in one chunk leaves every other
  chunk's text — and its measured height — as it was. (Cuts at "the
  first blank line after N lines" would cascade after every edit.)
- **Heights** (`NoteSlice.kt`) are measured per chunk between one line of
  context either side, cached by chunk text, and measured again only on a
  new text, width, style or set of registrations. A chunk holding a fence
  is also keyed by the filter results. Each chunk comes out within a
  pixel of its height inside the whole note (`NoteSliceTest`): line tops
  are whole pixels. Width changes settle for 300 ms before re-measuring.
- **The slice** (`EditorSlice`) holds its start offset and its distance
  from the end, so typing inside it moves neither edge. It is kept while
  the view stays half a screen inside it, and rebuilt to reach a screen
  and a half beyond the view. The caret's chunk and one either side stay
  inside while the caret is near the view. A caret moved far away
  (a reveal, a search hit, Ctrl+End) pins the slice to it until the view
  arrives. The slice never moves under a held pointer or an IME
  composition. When it moves, the line at the top of the view is put
  back where it was, absorbing the per-chunk rounding.
- **Notes under 400 lines are laid out whole**, as before.

What the pass list asked, and what is evidenced:

1. **Typing and caret:** typing at a revealed far line lands there
   (`NoteSliceUiTest`). Up/Down onto a placeholder steps through the
   buffer's own lines instead (`stepLines`, unit-tested), but no UI test
   drives it: the test harness's key input does not reach the field's own
   navigation, even on a short note. IME composition happens inside the
   one field as before. AWT/CJK composing was untested before this stage
   and still is.
2. **Selection and undo:** select-all of the whole buffer, typed over,
   then one Ctrl+Z restores it (`NoteSliceUiTest`).
3. **Geometry consumers:** through the one mapping, as above.
4. **Feel and cost:** screenshots at the top, middle and end of the
   1,500-line note show every line styled. The `type:` probe now types at
   the end of the note's first line, on screen, where a person types. (It
   used to append at the very end, which a slice never lays out.) Probe
   copies of the installed app, the 2026-11-01 build against this one,
   150 fast letters, private bytes sampled every half second:

   | 64 KB note | Open | Typing peak | Settled | Process CPU |
   |---|---|---|---|---|
   | `\n` endings | 166 → 143.5 MB | 273 → 192 MB | 190 → 161 MB | 35.6 → 8.7 s |
   | `\r\n` endings | 172 → 143 MB | 300 → 190 MB | 200 → 160 MB | 58.0 → 7.8 s |

   The 6 KB note is under the threshold and unchanged: 138 MB open, a
   ~200 MB peak. Against the owner's target (a ~130 MB base, editing at
   base + 50), both notes now peak about 60 MB over an empty window
   (134 MB). The target is close but not met. Half-second sampling
   catches peaks the earlier figures (180 MB for the short note) missed.

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

## What each keystroke allocated (2026-11-02)

The owner's correction: collections cost CPU, so stop the allocation
rather than collect more often. Measured per keystroke, typing from
inside the app:
- the UI thread's allocated bytes (`ThreadMXBean`);
- Skia's own object counts (`org.jetbrains.skia.impl.Stats`);
- Flight Recorder's allocation and CPU samples;
- the same layouts timed in a test JVM against Skia directly.

Found, in order of size:

- **Our transformation re-read every line on every keystroke.** That was
  18.9 MB of Java per keystroke in the 64 KB note and 2.7 MB in the 6 KB
  one. ~37% of it was regex matchers: a matcher per line per registration
  (tasks, people, checkbox, every Markdown rule). `LineMemo` keeps each
  line's recogniser answers and spans between keystrokes.
  - It is keyed by line text, which the decoration contract already makes
    pure.
  - Registrations are kept by identity, and styles per palette and scale.
  - A hit allocates nothing, and lines nobody asks for any more are swept.

  The result: **18.9 → 7.4 MB** and **2.7 → 1.3 MB** per keystroke.
- **Windows line endings made Skia's layout quadratic.** One paragraph
  holding `\r` took 2,079 ms for the 64 KB note against 17 ms with `\n`
  (8 KB: 44 vs 2 ms). The transformation now draws `\r` as a zero-width
  space: same length, so no offset moves, and every rule still reads the
  real text. The test notes behind the 2026-11-01 table had Windows
  endings, so its long-note figures carry this cost too.
- **The style window re-read every line's metrics on every keystroke**
  (`getOffsetForPosition` builds an object per line). It now asks only when
  the view moves, the viewport changes or the line count changes.
- **Forced collections were one full GC per keystroke on the long note**
  (29 ms each, ~11% of a keystroke's CPU), and one every four on the short
  note. They were needed because each layout's Java graph overflowed the
  default survivor spaces and was promoted. Promoted objects are freed
  only by a full collection, so their native memory waited for one.
  `-XX:SurvivorRatio=1 -XX:TargetSurvivorRatio=90` gives most layouts room
  to die young. With `NativeGarbage` collecting only on pauses, 60
  keystrokes at a moderate pace cost:

  | | Collections before | Collections, survivors and pause only |
  |---|---|---|
  | 64 KB note | 60 full + 25 young, 1,861 ms | 1 full + 100 young, 396 ms |
  | 6 KB note | 15 full, 407 ms | 1 full + 21 young, 74 ms |

  But sustained fast typing into the 64 KB note still promotes some
  layouts, and without a forced collection it peaked at **463 MB**. So the
  budget stays, raised from 4 to 16 MB: 150 fast letters peaked at 286 MB
  with 4 MB (37.8 s of process CPU), 292 MB with 16 MB (32.3 s) and 311 MB
  with 32 MB. Run-to-run noise is about ±20 MB. A note opens at the same
  size as before. (Enlarging the whole young generation as well cost
  11 MB at rest, and was not adopted.)

**What is left is laying out the whole styled note.** With the style
window, Skia receives ~3,300 run boundaries for the 64 KB note and takes
~90 ms to lay it out. The same text takes 27 ms with headings alone and
17 ms unstyled. Compose also builds a style object and a native paint per
boundary. The cost is proportional to how much styled text is laid out,
not to what changed. Lines off the screen cannot shed their wrap-changing
spans without moving when they scroll in, so this is stage 4's slice
field.

Measured on probe copies typing 150 letters fast, the build of
2026-11-01 against this one, same launcher and notes. Private bytes were
sampled every half second; "CPU" is the process's from open to settled.

| Note | Open | Typing peak | Settled | CPU |
|---|---|---|---|---|
| 6 KB | 138 → 138 MB | 197 → 201 MB | 155 → 157 MB | 7.8 → 6.6 s |
| 64 KB, `\n` endings | 166 → 168 MB | 273 → 295 MB | 190 → 195 MB | 35.6 → 35.9 s |
| 64 KB, `\r\n` endings | 172 → 169 MB | 300 → 293 MB | 200 → 196 MB | 58.0 → 33.2 s |

**Memory did not move**, within run-to-run noise (about ±20 MB at the
peak). What a keystroke allocates in Java halved, but Java was never where
the memory was: it is the native layout of the whole note, unchanged. CPU
fell by 15% on the short note and 43% on the note with Windows line endings.
On the long `\n` note it is unchanged: Skia's layout of the styled text
dominates. Both remaining costs are stage 4's to remove.

## What the empty window is made of (2026-11-02)

The owner asked whether Skia was the expensive part. To find out, the
stack was measured a layer at a time on the installed app's own runtime
and jars, with the launcher's JVM flags. Each layer is a minimal program;
each figure is stable across two runs to within 1 MB:

| Layer | Private bytes | Task Manager | JVM-accounted |
|---|---|---|---|
| The JVM, idle | 58 MB | — | 53 MB |
| + a Swing window | 67 MB | — | 60 MB |
| + a bare Compose window, one line of text | 99 MB | — | 80 MB |
| + Material 3 and a text field holding a 150-line note | 109 MB | 80 MB | 84 MB |
| + the Rust kernel open on the vault, projected | 112 MB | — | 88 MB |
| Gilune's empty window | 137–139 MB | 115 MB | 100 MB |
| …with a 256 px window icon | 125–130 MB | **102 MB** | 100 MB |

"Task Manager" is its default Memory column: the private working set,
what has actually been touched. Private bytes is committed memory, which
every other figure in this ADR reports. The two differ because the JVM
commits heap it has not used yet.

- **Compose and Skia cost ~41 MB over a bare JVM**, against 9 MB for
  Swing: Compose's classes (metaspace +12–15 MB) and Skia's library,
  software surface and threads (native +14–18 MB). That is the fixed
  price of the toolkit ADR 0001 chose.
- **The kernel costs 3–4 MB.** Opening it and projecting the vault is
  cheap.
- **The window icon cost 9–10 MB of private bytes (13 MB in Task
  Manager).** The window held the 1,254 px logo decoded for its whole
  life. It now has its own 256 px copy (`WINDOW_ICON`; `WindowIconTest`
  fails on a larger one), and the full logo stays for packaging.
- **What is left of Gilune's own share, ~22 MB in Task Manager,** is
  mostly its classes: ~1,900 more than the bare app (its own code, 189
  generated binding classes, JNA), +9 MB of metaspace, +2 MB of code.
  They load as features are first used. Shrinking that means loading
  less at startup, feature by feature.
- **A smaller starting heap (`-Xms16m`) was measured and not adopted.**
  It lowers private bytes by 13–16 MB, but Task Manager does not move
  (102 → 103 MB): the extra committed heap had never been touched.

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
asynchronously, and the NT heap does not shrink anyway. The budgeted
collection that replaced it stays, with the budget raised from 4 to 16 MB
on 2026-11-02 once most layouts die young. Retiring it outright was
measured and rejected: fast typing into the 64 KB note then peaked at
463 MB.

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
