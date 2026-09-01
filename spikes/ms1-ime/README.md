# Spike MS1 — The IME and the offset mapping

Status: **Complete** — verdict **PASS WITH CONDITIONS** (§ Verdict). The
offset mapping ADR 0017 § 8 was chartered to doubt is sound under every
composing sequence measured. A different defect was found in the same place
and it is not ours: Compose's **legacy** `BasicTextField(TextFieldValue)`
stops opening composing regions after a commit, which writes bytes the user
did not type. The condition on MB2 follows from that, not from the
decoration.

Evidence prototype for **ADR 0017 § 2** (the mobile editor's always-glyph
checkbox, the one length-delta decoration in the buffer) and the gate on
**ADR 0017 § 8 MB2**. The tests stay in the tree as regression evidence:

```text
app/composeApp/src/androidInstrumentedTest/kotlin/org/gilune/app/
  Ms1ImeRig.kt                  the route to a real InputConnection
  Ms1ImeSpikeTest.kt            the matrix, ×2 transformation configs
  Ms1TextFieldStateSpikeTest.kt the modern-API prototype (questions 2 and 3)
  Ms1ControlsTest.kt            the two controls the verdict rests on
```

Nothing outside `androidInstrumentedTest/` was touched. No production file,
no dependency, no ADR.

## What was asked

ADR 0017 § 8, verbatim: the spike runs an IME matrix and answers three
questions — "does the mapping hold; should the editor move to
`TextFieldState` with `outputTransformation`, which drops manual offset
mapping and holds 'styling never changes bytes' in the API itself; and where
does undo come from. Pass = no byte the user did not type, caret stable
across composing commits, backspace correct at a decoration boundary, no
duplicate insertion."

## The honest scope, stated first

The ADR asks for that matrix **on a physical device** — Gboard, Samsung
Keyboard, one aggressively autocorrecting IME, one with a composing region.
**No physical device exists.** The physical matrix is therefore **assumed**,
exactly as ADR 0001's device budgets are, and it is the first entry under
Honest gaps.

What an emulator can answer honestly is the layer underneath every one of
those keyboards. An IME reaches a text field through one object and no other
— an `InputConnection` — and through a small, fixed set of calls on it:
`setComposingText`, `setComposingRegion`, `finishComposingText`,
`commitText`, `deleteSurroundingText`, `setSelection`, delivered inside
`beginBatchEdit`/`endBatchEdit`. Gboard's autocorrect, a CJK converter's
candidate replacement and a swipe prediction differ in *which* sequences they
send, not in the surface they send them through. This spike drives that
surface directly. It does not claim to have run a keyboard.

## Ran on

- Emulator AVD `gilune-s1`, headless (`-no-window -gpu swiftshader_indirect`).
- **Android 16, API 36, x86_64.** Google LatinIME is installed on the image
  and was deliberately **not** used — see Honest gaps.
- Compose Multiplatform 1.7.3 (Compose Foundation 1.7.x), Kotlin 2.1.20,
  AGP 8.10.1, minSdk 29 / targetSdk 36.
- Windows 11 host; SDK at `%LOCALAPPDATA%\Android\Sdk` with the stale
  `ANDROID_HOME` overridden per process, per CLAUDE.md.

Tallies: **39 MS1 tests, five consecutive green runs**, no flakes. Full
Android instrumented suite **389/389** green (MB0's and MB1's suites
unchanged beside these). Desktop **394/394** green — MS1 touched no desktop
code and the number is unchanged.

## The rig, and which layer each row exercises

`Ms1ImeRig.kt` walks the resumed activity's view tree and calls
`View.onCreateInputConnection(EditorInfo())`, taking the first non-null
answer. That is the *same method the platform calls* when a keyboard binds
to a field, so the object returned is the real connection with the real
`EditorInfo` filled in — confirmed by its class on the modern API,
`NullableInputConnectionWrapperApi34`.

This is deliberately **not** `performTextInput`. That helper drives the
semantics `InsertTextAtCursor` action and never opens a composing region at
all, so it cannot see the thing ADR 0017 § 8 asks about.

Per-row layer disclosure, because it matters:

| What | Layer |
| --- | --- |
| Every text mutation, caret move and composing region in every row | Real `InputConnection` on the focused Compose field |
| The transformed string each row asserts | `MarkdownEditTransformation.filter` called directly — a pure function over the same input the field gets |
| The offset-mapping soundness check (`assertSound`) | The same pure call; the mapping is then walked exhaustively at **every** offset in both directions |
| "The field did not crash" | In vivo: Compose wraps every `OffsetMapping` in a validator that throws during composition, so a run that completes is a run the validator passed |

`assertSound()` is the strongest single check in the suite: after every step
of every row it asserts, for all offsets `0..length`, that
`originalToTransformed` and `transformedToOriginal` stay in bounds and are
monotone non-decreasing — Compose's own contract, checked exhaustively
rather than sampled — plus that the visible caret round-trips exactly
wherever it is not inside a replaced range.

Both configs run every row, via `@Parameterized`:

- **`AlwaysGlyph`** — mobile's rule (ADR 0017 § 2): the checkbox is a glyph
  on the caret's line too, so a length-delta replacement is live *under the
  caret* at all times. Expressed today by passing a null caret line; the
  per-registration flag MB2 adds changes who opts in, not the shape of the
  table measured here.
- **`CaretLineRaw`** — the desktop's rule, kept by owner decision
  (Ratification record, 3).

Every document under test carries a second task line that is never the
caret's, so the offset table holds a live entry under **both** configs.

## The matrix

| ADR 0017 § 8 criterion | Result | Rows |
| --- | --- | --- |
| Composing text typed span-by-span then committed — no byte the user did not type | **Pass** | `composingSpanBySpanThenCommitted` |
| Caret stable across composing commits | **Pass** | same row; `autocorrectReplacesAWordWithALongerOne` asserts the caret is identical either side of `finishComposingText` |
| Composing region **straddling** a `[ ]`→☐ replacement, then edited — no duplicate insertion | **Pass** | `composingRegionStraddlingTheGlyph_thenReplaced`, `…_thenAbandoned` |
| The same with the region **opening inside** the replacement, where the mapping has no exact image and snaps | **Pass** | `composingRegionOPENINGInsideTheGlyph_thenReplaced` |
| Backspace correct at a decoration boundary | **Pass** | `backspaceAtADecorationBoundary_removesTheCorrectByte`, four caret positions |
| Autocorrect-style replace of a different length near the glyph | **Pass** | three rows: longer, shorter, and a region abutting the marker's last byte |
| Rapid composing churn — text never corrupted | **Pass** | `rapidComposingChurn` (4 words, 18 composing spans, byte-exact), `rapidChurnInsideOneBatchEdit` |
| Offsets sound throughout | **Pass** | `assertSound()` after every step of every row, both directions, every offset |

Beyond the ADR's list, and worth keeping:

- **CJK and a surrogate pair.** The call sequence a converter issues — a
  composing span rewritten in place then committed as different characters
  of a different length — followed by a two-code-unit emoji, both beside a
  live table entry. Byte-exact, caret exact.
- **One held connection.** A real keyboard holds one connection until input
  restarts. A whole composing sequence over a single held connection behaves
  identically to one over freshly obtained connections.
- **What the IME is shown.** On the legacy API, `getTextBeforeCursor` and
  `getExtractedText` return the **source** — `- [ ] milk`, never `☐` — and
  `EditorInfo.initialSelStart` is the source offset. The decoration exists
  only in the layout. On the modern API this is **not** true; see below.

### Backspace, in detail

The four positions, all byte-correct and all with the mapping sound
afterwards: immediately past the marker's `]` (removes the `]`, and the
broken marker correctly stops decorating); **inside** the marker between `[`
and the state character (not visually addressable under `AlwaysGlyph`, since
the caret snaps to the glyph's edge — but an IME can put it there with
`setSelection`, and the byte removed is still the right one); one byte past
the marker, where the marker survives and the table entry stays live under
the caret; and a four-character whole-word delete.

## The finding that gates MB2

Once a composition has been ended — by `commitText` or by
`finishComposingText` — the legacy `BasicTextField(TextFieldValue)` **never
opens another composing region** for the rest of the session when commands
arrive unbatched. `setComposingText` still inserts its text, so a keyboard
rewriting its composing span in place gets every intermediate prefix
accumulated into the document: `"c"` then `"cd"` lands as `ccd`. A later
`setComposingRegion` is ignored the same way, which is how an autocorrect
would silently double a word.

That is "a byte the user did not type", and it is what the ADR's pass
criterion forbids.

Three controls place the fault, and all three ran on the identical rig:

1. **`VisualTransformation.None`** — no decoration, no offset mapping,
   nothing of ours — produces a byte-identical trace. Our transformation is
   not implicated. (`Ms1ControlsTest.theSameComposeFieldAccumulatesWithNoTransformationAtAll`)
2. **A plain platform `EditText`** — the reference implementation of an
   `InputConnection` — re-opens the region correctly: composing span
   `24,25` where Compose reports none, and the second span replaces the
   first. So the rig is sound.
   (`Ms1ControlsTest.aPlatformEditTextReOpensTheComposingRegionAfterACommit`)
3. **`TextFieldState` + `outputTransformation`** handles the same sequence
   correctly.

`InputMethodManager.restartInput` — the framework's own repair mechanism —
does not restore it. Holding one connection rather than re-obtaining it does
not change it either.

**It is invisible inside a batch.** Every command in one
`beginBatchEdit`/`endBatchEdit` lands on a single edit buffer with no
recomposition between, so composition bookkeeping survives. Real keyboards
batch, which is very likely why this has not sunk the legacy field in
production — and it is why the matrix above delivers each composing sequence
batched, measuring the mapping under the delivery a real IME uses. The
defect is reported as its own row
(`theLegacyFieldStopsOpeningComposingRegionsAfterACommit`) rather than
smeared across every other one.

A second, milder symptom of the same fragility: a command delivered into a
session that is up but not yet settled loses its composing region, roughly
one run in four. The rig steers around it with a harmless warm-up round trip
so that the suite measures the mapping rather than the settling.

## Question 2 — `TextFieldState` and `outputTransformation`

Prototyped far enough to answer, not migrated:
`Ms1TextFieldStateSpikeTest` stands the same checkbox decoration up on
`BasicTextField(state = …, outputTransformation = …)` and drives it through
the same real `InputConnection` on the same sequences.

**It removes the manual mapping risk outright.** The whole of the modern
decoration is:

```kotlin
OutputTransformation {
    for ((range, glyph) in glyphEdits(asCharSequence().toString()).asReversed()) {
        replace(range.first, range.last + 1, glyph)
    }
}
```

No offset table, no `OffsetMapping`, no inverse to get wrong. The
transformation states the edits; Compose watches them and derives the
mapping in both directions itself. `DecorationOffsetMapping` — the ~40 lines
ADR 0017 § 8 calls the highest-risk inherited component, including the
snap-to-nearer-edge rule for offsets with no exact image — has no
counterpart to write.

**And it holds ADR 0012's invariant in the type system.** The buffer an
output transformation writes to is not the `TextFieldState`; there is no
path by which a rendering can reach the document. "Styling never changes
bytes" stops being a rule the shell must keep and becomes a fact of the API.

**The one place it is worse, and MB2 inherits it.** Where the legacy
connection mirrors the raw value, the modern one hands the IME the
**output-transformed** text and reports the caret in transformed
coordinates — measured: `getTextBeforeCursor` returns `- ☑ done\n- ☐ milk`,
and `initialSelStart` is 17 where the source offset is 21. Compose maps the
IME's edits back correctly (the straddle, backspace and replace rows all
pass), but the keyboard's own word segmentation, dictionary and suggestion
logic see the glyph as a character of the document. This is survivable only
because ADR 0017 § 2 permits exactly one length-delta decoration and it is
delimited on both sides — bullet, space, glyph, space — so it is its own
word and no autocorrect span can straddle it. That is now a condition, not
a coincidence.

**Porting cost**, from having built it: the transformation itself is
smaller. The real work is at the call sites — `TextFieldValue` and its
`selection`/`composition` give way to `TextFieldState` with `edit {}` and
`undoState`; every shared helper that takes or returns a `TextFieldValue`
(`replaceRange`, `insertAtCaret`, `activateLine`, the `/` suggester's
session, the checkbox tap's offset lookup) needs an adapter or a second
entry point; `OutputTransformation` is `@ExperimentalFoundationApi` in
Compose 1.7.x, so the opt-in is explicit. Desktop keeps the legacy field —
none of this obliges the shells to converge, and ADR 0017 already has the
shells deliberately differing on the caret rule.

## Question 3 — where undo comes from

- **Desktop, today**: nothing. There is no undo stack; the buffer is written
  in single writes and the document is the file.
- **Legacy `BasicTextField(TextFieldValue)`**: nothing. The legacy field has
  no undo history at all, so an editor on it must keep its own stack of
  `TextFieldValue`s and decide what a coalesced "one undo" means.
- **`TextFieldState`**: `state.undoState`, carrying `canUndo`, `canRedo`,
  `undo()`, `redo()` and `clearHistory()`. Measured, not read off the
  documentation: `undoIsCarriedByTheApi` types through the IME, asserts the
  history opened, undoes back to the original document and redoes forward
  again. It captures IME-originated edits without the shell keeping
  anything.

So undo is a third, independent reason to prefer the modern API on mobile,
and the answer to "where does undo come from" on the legacy one is "you
write it".

## Honest gaps

- **The physical-device matrix is ASSUMED.** No Gboard, no Samsung Keyboard,
  no third-party autocorrector, no CJK IME has driven this field. ADR 0001's
  pattern applies verbatim: this spike may be accepted on that basis and
  must be revisited when the owner's device arrives. What is claimed here is
  the `InputConnection` contract, which is the surface all of them use — not
  the sequences any particular one of them chooses to send.
- **The emulator carries Google LatinIME and it was not driven.** Injecting
  keystrokes through `adb shell input` produces key events, not a composing
  session, so it would have measured a different thing while looking like it
  measured this one. Driving the keyboard's own UI headlessly is not
  deterministic. A physical-device run should use the real keyboards rather
  than repeat this.
- **x86_64 only.** No ARM64 execution, as everywhere else in this repo.
- **The modern API was not proved immune to the composing fragility.** In
  runs taken while the rig was still being settled, the modern field also
  lost a post-commit composing region in a minority of unbatched sequences.
  No rate is quoted, because the rig was changing underneath those
  observations and a number would imply more than was measured. What is
  solid: the legacy field does it every time and deterministically; the
  platform `EditText` never did it; the modern field is markedly better and
  is not proven perfect.
- **Desktop composing is untested.** This measurement is Android-only. The
  desktop editor ships the legacy field with the same transformation, and
  desktop IMEs (a Windows CJK IME, for one) reach it through AWT input
  methods, not `InputConnection`. Nothing here says whether that path is
  sound. It is a separate spike if anyone ever types Japanese into the
  desktop app.
- **Element-scoped reveal is not covered.** ADR 0017 § 2 ships line-scoped
  for v1 and names element-scope as a later refinement. If it lands, the
  table changes shape as the caret moves *within* a line, which is a new
  input to this question and wants its own row here.
- **No performance measurement.** The transformation is re-run per
  recomposition on the whole buffer; this spike measured correctness only.
  A long note under composing churn is unmeasured.
- **The rig's warm-up is a real caveat.** The suite inserts a harmless IME
  round trip before each row because the first command into a fresh session
  can lose its composing region. A real keyboard's first keystroke gets no
  such warm-up. Whether a user's very first character after focusing a field
  is at risk is exactly the sort of thing only a physical device answers.

## Verdict

**PASS WITH CONDITIONS.** MB2 may build on the current transformation. The
offset mapping holds: straddling composing regions, regions opening inside a
replacement, backspace at every boundary position, autocorrect replacements
longer and shorter, rapid churn, CJK and a surrogate pair all leave the
bytes exact, the caret stable across commits, and the mapping in bounds and
monotone at every offset in both directions — under both caret rules, with
mobile's always-glyph rule (the harder one, and the one MB2 ships) fully
covered. No duplicate insertion was observed in any row.

The conditions:

1. **The mobile editor must use `TextFieldState` with
   `outputTransformation`, not `BasicTextField(TextFieldValue)` with
   `VisualTransformation`.** The legacy field stops opening composing
   regions after a commit and accumulates the bytes a keyboard did not
   intend to leave — measured deterministically, reproduced with no
   decoration at all, and not reproduced by a platform `EditText` on the
   same rig. This condition is about the API; ADR 0017 § 2's rendering is
   vindicated.
2. **No length-delta decoration may sit inside a word.** On the modern API
   the IME is shown the decorated text, so a glyph inside a word would land
   inside the keyboard's dictionary lookups. ADR 0017 § 2 already permits
   only the checkbox, and it is delimited on both sides; this makes that a
   requirement rather than an accident, and any future length-delta
   decoration needs its own row in this suite before it ships.
3. **If any surface does ship the legacy field, it must deliver composing
   sequences batched** and must not assume a composing region survives a
   commit.
4. **The physical-device matrix remains outstanding and assumed.** MB2 may
   proceed on the ADR 0001 basis; the matrix is run when the device arrives,
   and a failure there reopens this verdict.
