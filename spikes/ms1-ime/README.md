# Spike MS1 — The IME and the offset mapping

**Diagnostic execution update (2026-09-07).** The three rows that require the
historical keyboard teardown to occur now opt in through
`gilune.ime.legacyDiagnostics=true`. They keep their original, falsifiable
assertions. API 36 alone does not identify the measured keyboard or its timing:
the hosted image did not reproduce those three observations while its 858
non-opt-in application tests passed. Missing an upstream defect is not an
application correctness failure. Portable composing/commit/undo tests and the
platform EditText control remain in the default release gate on every supported
API. To reproduce or compare the historical observation, run from `app/` (use
`gradlew.bat` on Windows):

```sh
./gradlew :composeApp:connectedDebugAndroidTest -Pgilune.kernel.profile=release \
  '-Pandroid.testInstrumentationRunnerArguments.class=org.gilune.app.Ms1ControlsTest,org.gilune.app.Ms1ImeSpikeTest' \
  '-Pandroid.testInstrumentationRunnerArguments.gilune.ime.legacyDiagnostics=true' --rerun
```

This supersedes the short-lived API-36-only filter. The report below retains
the original environment, observations and physical-device gaps.

Status: **Complete** — verdict **PASS WITH CONDITIONS** (§ Verdict). The
offset mapping ADR 0017 § 8 was chartered to doubt is sound under every
composing sequence measured. A different defect was found in the same place
and it is not ours: with a keyboard bound, Compose's **legacy**
`BasicTextField(TextFieldValue)` has its composing region torn down by that
keyboard after every edit, so an unbatched sequence writes bytes the user did
not type. A platform `EditText` on the same rig under the same keyboard is
never touched. The condition on MB2 follows from that, not from the
decoration. (The mechanism was corrected on 2026-09-02; the superseded
formulation and the evidence that retired it are kept in § The finding that
gates MB2 and § The rig owns the input session.)

Evidence prototype for **ADR 0017 § 2** (the mobile editor's always-glyph
checkbox, the one length-delta decoration in the buffer) and the gate on
**ADR 0017 § 8 MB2**. The tests stay in the tree as regression evidence:

```text
app/composeApp/src/androidInstrumentedTest/kotlin/org/gilune/app/
  Ms1ImeRig.kt                  the route to a real InputConnection, and the
                                ownership of the input session that makes
                                driving it deterministic
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

Tallies: **39 MS1 tests**. The original run (2026-09-01) recorded five
consecutive green runs; those runs were **luck**, and the suite was later
found to be non-deterministic — see § The rig owns the input session, which
records what was wrong, what fixed it, and the re-proof tallies taken on
2026-09-02 after the fix.

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

## The rig owns the input session

**This section supersedes an earlier account of the rig's flakiness, and the
account it replaces was wrong. Both are kept, because a spike that quietly
rewrites its own history is worth nothing.**

What the first version said: that a command delivered into a session "up but
not yet settled" lost its composing region "roughly one run in four", and
that a harmless warm-up round trip steered around it. What was actually
happening: **the rig was not the only actor on the connection.**

The emulator image carries Google LatinIME, it is the default input method,
and it binds to the field the moment the field takes focus. "Installed and
not driven" is not the same as "not bound". On the **legacy** Compose field
it then calls `finishComposingText` of its own accord, unprompted, **150–860
ms after every edit** — reacting to what that field reports through
`updateSelection`. `rule.waitForIdle()` waits only for this process and
returns 50–150 ms in. So every assertion about a composing region was a race
against another process, and the suite failed in **both** directions with
different members each run: a region missing where one had to be open
(`expected TextRange(22,24) but was null`) and a region present where the
old defect row said none could be (`expected null, but was TextRange(25,26)`).

The evidence, in the order that settled it:

| Measurement | Result |
| --- | --- |
| Cold `-no-snapshot` reproduction, 3 runs | 3 / 1 / 4 failures, **different members each run** |
| Every static gate at the moment the rig starts — window focus, host focus, `imm.isActive`, `isAcceptingText` | all already true; the gate was never the problem |
| A sentinel opening and closing a composing region 30 times, seconds into the session | still erratic; the session never "settles", and waiting longer never helped |
| Watching the field for 800 ms after each command *without touching it* | our command opens the region correctly every time, then `comp=null` lands unprompted at +264 / +270 / +386 / +431 / **+864** ms |
| The same rig, same keyboard, driving a platform `EditText` | **zero** interjections, 3 instances × 6 commands, over a 1200 ms window each |
| All input methods disabled, 3 suite runs | fully deterministic: the same 3 rows fail every time, identical assertion text |
| A passive (voice) IME bound instead, 2 suite runs | identical to the above — so it is the keyboard's **commands**, not the binding |

That `EditText` row is the one that matters: under the identical rig and the
identical keyboard the platform's own editor is never touched. So this is the
**legacy Compose field's** doing, not the keyboard being hostile to everyone.

**The fix.** `Ms1InputSessionRule` (in `Ms1ImeRig.kt`) disables the device's
input methods for the duration of a row and restores them in a `finally`, so
the only actor on the connection is the test. It is chained *outside* the
Compose rule — plain `@Rule` ordering is undefined and the input methods must
be right before an activity launches. Restoration runs on failure too, and a
JVM shutdown hook covers a hard kill, because a device left with no input
method would break every later suite on the same emulator. The rows whose
subject **is** the keyboard opt back in with `@WithSystemKeyboard`, and they
synchronise on the interjection as an *event* — `awaitCondition`, a bounded
poll that returns the moment the teardown lands and asserts its return value,
so a platform that stops doing this fails the row rather than passing quietly.

No blind sleeps were added. The one fixed window in the suite is the negative
assertion on the `EditText` control, which states the window it watched.

**Re-proof, 2026-09-02**, all 39 MS1 tests green in every run:

- **six consecutive runs from a cold `-no-snapshot` boot**, 39/39 each;
- **six consecutive runs from a snapshot boot**, 39/39 each;
- **one full Android instrumented suite from each boot type**: 610 tests,
  609 passed, 0 failures, 1 assumption-skip — R0's known `a03` network skip —
  from cold and from snapshot alike.

Falsifiability was checked by removing `@WithSystemKeyboard` so the mechanism
could not occur: all three mechanism instances failed, on the awaited event,
with the message naming it, while the other 30 rows in that class stayed
green.

For comparison, the same four classes before the fix, on a cold boot: **3 / 1
/ 4 failures over three runs, different members each time**.

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

**Superseded formulation, recorded rather than erased.** Until 2026-09-02
this section said: once a composition has been ended, the legacy
`BasicTextField(TextFieldValue)` *never opens another composing region for
the rest of the session* when commands arrive unbatched, so prefixes
accumulate — `"c"` then `"cd"` lands as `ccd`. The corruption is real and is
still pinned. The **premise was wrong**: the field does open the region. And
the row that pinned it could only pass when a cross-process race resolved one
particular way, which is why it later failed in both directions. It was
retired by the measurements in § The rig owns the input session.

**What is measured now.** With a keyboard bound, the legacy field opens the
composing region exactly as the API says it must — and then the **keyboard
tears it down unprompted**, 150–860 ms later, having been given no reason to.
The next composing span, which a keyboard means as a *replacement* of the one
it is still rewriting, therefore finds no region and **appends**:

```text
setComposingText("a")     → "…\na"     composition (22,23)   ← the field is fine
   …150–860 ms later, unprompted:        composition null     ← the keyboard
setComposingText("ab")    → "…\naab"                          ← bytes not typed
```

That is "a byte the user did not type", and it is what the ADR's pass
criterion forbids. The row is
`Ms1ImeSpikeTest.theBoundKeyboardTearsDownTheLegacyFieldsComposingRegion`,
run under both caret rules, with the keyboard deliberately bound. It waits
for the teardown as an event and asserts that it arrived, so it stays
**falsifiable**: a platform that stops doing this fails the row.

Three controls place the fault, and all three ran on the identical rig:

1. **`VisualTransformation.None`** — no decoration, no offset mapping,
   nothing of ours — is torn down identically and accumulates identically.
   Our transformation is not implicated.
   (`Ms1ControlsTest.theSameComposeFieldIsTornDownWithNoTransformationAtAll`)
2. **A plain platform `EditText`** — the reference implementation of an
   `InputConnection` — driven by the same calls under the **same bound
   keyboard** is never interfered with at all: zero interjections over a
   1200 ms window in which the Compose field is torn down every time, and it
   re-opens its composing region correctly afterwards. So the rig is sound,
   and the keyboard is not simply hostile to everything.
   (`Ms1ControlsTest.aPlatformEditTextIsNeverTouchedByTheBoundKeyboard`)
3. **`TextFieldState` + `outputTransformation`** handles the same sequences
   correctly, and was never seen to accumulate in any run.

`InputMethodManager.restartInput` — the framework's own repair mechanism —
does not prevent it. Holding one connection rather than re-obtaining it does
not change it either.

**A batch is atomic against it.** Every command in one
`beginBatchEdit`/`endBatchEdit` lands on a single edit buffer with no
recomposition between, and no `updateSelection` goes out mid-batch, so the
keyboard cannot interject inside one. The teardown then arrives after the
batch ends. Real keyboards batch a word at a time, which is very likely why
this has not sunk the legacy field in production — a word survives, and it is
the seam *between* words that is exposed. It is also why the matrix above
delivers each composing sequence batched: it measures the mapping under the
delivery a real IME uses, with the interference reported as its own row
rather than smeared across every other one.

**The conditions in § Verdict are unchanged, and this reinforces them.** The
mechanism is more specific than the old wording and it points the same way:
the legacy field, alone among the editors measured, provokes a bound keyboard
into destroying its composing state. MB2 was already built on
`TextFieldState` + `outputTransformation`, which is what condition 1 asked
for, and that class was green in every run taken during this investigation.

This section previously claimed "a second, milder symptom of the same
fragility": that a command issued into a session "up but not yet settled"
lost its composing region "roughly one run in four", absorbed by a warm-up
round trip. There is no such symptom. That was the same interference, seen
before its cause was known — a sentinel driven 30 times, seconds into the
session, was still erratic, so nothing was ever settling. The warm-up round
trip is kept only because it starts every row from one identical state.

## Question 2 — `TextFieldState` and `outputTransformation`

**9 September 2026 compatibility note:** these original measurements used
Compose 1.7.6. With the 1.8.2 upgrade, `InputConnection.setSelection` maps
displayed coordinates back to source coordinates, as the text reported to
the keyboard requires. The prototype's backspace row now sends displayed
offset 12 and explicitly checks source offset 16 before deletion. Its exact
result is unchanged: only the closing `]` is removed. The old row's source
offset 16 depended on the earlier implementation's unmapped selection.
The published [Foundation Android 1.8.2 source](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.8.2/foundation-android-1.8.2-sources.jar)
records this conversion in `ImeEditCommandScope.setSelection`.

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
- **The emulator carries Google LatinIME and its UI was not driven** —
  but it was *bound*, and that turned out to matter enormously. Injecting
  keystrokes through `adb shell input` produces key events, not a composing
  session, so driving it that way would have measured a different thing while
  looking like it measured this one; driving the keyboard's own UI headlessly
  is not deterministic. What the suite does instead is control whether the
  keyboard is attached at all: every row but three owns the session
  exclusively, and those three are attached deliberately and measure the
  attachment. A physical-device run should use the real keyboards rather than
  repeat this.
- **The teardown was measured with one keyboard.** Google LatinIME on this
  image is the only keyboard that has ever driven this field. Whether Gboard
  proper, Samsung Keyboard or a CJK converter tear the legacy field's
  composing region down on the same schedule is **assumed**, not measured. The
  mechanism-pinning rows are written to be falsifiable precisely so a
  hardware run can answer this: if a real keyboard does not do it, they fail.
- **x86_64 only.** No ARM64 execution, as everywhere else in this repo.
- **The modern API was not proved immune to the teardown.**
  `Ms1TextFieldStateSpikeTest` owns the input session exclusively, like the
  rest of the matrix, so it measures the API and not the keyboard: it says
  nothing either way about whether a bound keyboard tears *its* composing
  region down. What is on record is circumstantial and worth exactly that
  much: it was green in all fifteen-plus runs taken across this
  investigation, including every run in which the legacy field was failing,
  and it was never seen to accumulate. Standing the comparison up properly
  needs one more row, attached with `@WithSystemKeyboard`, mirroring the
  legacy mechanism row. It is not written. (An earlier version of this entry
  reported the modern field "also lost a post-commit composing region in a
  minority of unbatched sequences"; those observations were taken while the
  keyboard was interfering unrecognised, so they measured the race, not
  the API.)
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
- **Most rows run with no keyboard bound, and a real user always has one.**
  That is the price of determinism, and it is a real limit on what the matrix
  claims: it proves the offset mapping and the byte behaviour of the field
  under a real `InputConnection`, with nothing else on the wire. What a
  keyboard *adds* on top is measured only by the three attached rows. The
  mapping is the thing ADR 0017 § 8 chartered, so this is the right trade —
  but a row that passes here has not been proved to pass with Gboard
  attached.
- **The negative control is only as strong as its window.** The `EditText`
  row asserts that nothing touched it for 1200 ms — the window over which it
  was measured undisturbed, and one in which the Compose field is torn down
  every time. A slower interjection than any yet seen would slip past it.

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
   `VisualTransformation`.** With a keyboard bound, the legacy field has its
   composing region torn down by that keyboard after every edit, so an
   unbatched sequence accumulates the bytes a keyboard did not intend to
   leave — measured deterministically, reproduced with no decoration at all,
   and not reproduced by a platform `EditText` under the same keyboard on the
   same rig. This condition is about the API; ADR 0017 § 2's rendering is
   vindicated. (Condition unchanged since ratification; only the mechanism
   behind it was corrected on 2026-09-02, and it points the same way.)
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
