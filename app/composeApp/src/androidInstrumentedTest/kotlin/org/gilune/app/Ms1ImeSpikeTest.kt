// MS1 — the IME spike gating ADR 0017 MB2.
//
// ADR 0017 § 8: "The highest-risk inherited component is
// `DecorationOffsetMapping` under a real IME: a composing span straddling a
// `[ ]`→`☐` replacement is untested, and `VisualTransformation` has a
// documented history of IME bugs (wrong caret after backspace; duplicate
// insertion). Pass = no byte the user did not type, caret stable across
// composing commits, backspace correct at a decoration boundary, no
// duplicate insertion."
//
// WHAT THIS SUITE HONESTLY IS. The ADR asks for a matrix on a physical
// device across Gboard, Samsung Keyboard, an aggressively autocorrecting
// IME and one with a composing region. No physical device exists, so the
// physical matrix is ASSUMED (ADR 0001's pattern) and recorded as a gap in
// `spikes/ms1-ime/README.md`. What an emulator can answer honestly is the
// layer *underneath* every one of those keyboards: `InputConnection`. Every
// IME on Android — Gboard, Samsung, a CJK converter, an autocorrector —
// reaches a text field through exactly these calls, and nothing else:
// setComposingText, setComposingRegion, finishComposingText, commitText,
// deleteSurroundingText, setSelection. This suite obtains the *real*
// connection the platform hands an IME — `View.onCreateInputConnection` on
// the Compose host view, after the field has taken focus and opened a text
// input session — and drives it. It is deliberately NOT
// `performTextInput`, which is a semantics shortcut that never builds a
// composing region at all and so cannot see the bug the ADR names.
//
// Every row runs under BOTH transformation configs (ADR 0017 § 2 and the
// Ratification record, 3): [Reveal.CaretLineRaw] is what desktop ships —
// the caret's line shows raw Markdown — and [Reveal.AlwaysGlyph] is what
// mobile ships, where the checkbox is a glyph on the caret's line too. The
// second is strictly the harder case: it is the only one in which a
// composing region can straddle a live length-delta replacement, which is
// precisely the risk MS1 exists to measure.

package org.gilune.app

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class Ms1ImeSpikeTest(private val reveal: Reveal) {

    /// Which of the two caret rules the transformation is wired for.
    /// `CaretLineRaw` is the desktop's (ADR 0012 E3, kept by owner decision);
    /// `AlwaysGlyph` is mobile's (ADR 0017 § 2), expressed today by passing a
    /// null caret line — the per-registration flag MB2 adds changes who opts
    /// in, not the shape of the offset table this measures.
    enum class Reveal { AlwaysGlyph, CaretLineRaw }

    private val rule: ComposeContentTestRule = createComposeRule()

    /// [Ms1InputSessionRule] must be the OUTER rule: the device's input
    /// methods have to be in the right state before the Compose rule launches
    /// an activity and a field takes focus. Bare `@Rule` ordering is
    /// undefined, so the two are chained explicitly.
    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(Ms1InputSessionRule()).around(rule)

    private val state: MutableState<TextFieldValue> = mutableStateOf(TextFieldValue(""))

    // ---------------------------------------------------------------- (a)

    @Test
    fun composingSpanBySpanThenCommitted_typesExactlyWhatWasTyped() {
        open(DOC + "- [ ] milk\n", caret = 22)

        batch {
            setComposingText("h", 1)
            setComposingText("he", 1)
            setComposingText("hel", 1)
            setComposingText("hell", 1)
            setComposingText("hello", 1)
        }

        assertEquals(DOC + "- [ ] milk\nhello", text(), "no byte the user did not type")
        val beforeCommit = selection()
        assertEquals(TextRange(27), beforeCommit)
        assertNotNull(value().composition, "a composing region is genuinely open")

        ime { finishComposingText() }

        assertEquals(DOC + "- [ ] milk\nhello", text(), "committing invents nothing")
        assertEquals(beforeCommit, selection(), "the caret is stable across the commit")
        assertNull(value().composition, "the composing region closed")
        assertEquals("- ☑ done\n- ☐ milk\nhello", transformed(), "both glyphs still render")
        assertSound()
    }

    // ---------------------------------------------------------------- (b)

    @Test
    fun composingRegionStraddlingTheGlyph_thenReplaced_insertsNothingTwice() {
        // The caret sits on the task line, so under AlwaysGlyph the region
        // set below genuinely straddles a live `[ ]`→☐ replacement. Under
        // CaretLineRaw that line is raw, which is the finding, not a dodge:
        // the desktop rule cannot produce this state at all.
        open(DOC + "- [ ] milk", caret = 21)
        assertEquals(
            if (reveal == Reveal.AlwaysGlyph) "- ☑ done\n- ☐ milk" else "- ☑ done\n- [ ] milk",
            transformed(),
        )

        // "[ ] m" — starts on the marker's first byte, ends past its last.
        // Region and replacement arrive in one batch, which is how an
        // autocorrect actually arrives; that a bare region writes nothing is
        // pinned separately by
        // [composingRegionStraddlingTheGlyph_thenAbandoned_leavesTheBytesAlone].
        batch {
            setComposingRegion(13, 18)
            setComposingText("X", 1)
        }
        assertEquals(DOC + "- Xilk", text(), "the region was replaced once, not twice")
        assertEquals(TextRange(14), selection())
        assertSound()
    }

    @Test
    fun composingRegionOPENINGInsideTheGlyph_thenReplaced_insertsNothingTwice() {
        // The harder edge: a region whose START lands *inside* the replaced
        // range, where the mapping has no exact image and snaps to an edge.
        open(DOC + "- [ ] milk", caret = 21)
        batch {
            setComposingRegion(14, 19) // " ] mi"
            setComposingText("Z", 1)
        }
        assertEquals(DOC + "- [Zlk", text(), "a region opening inside the glyph still replaces once")
        assertEquals(TextRange(15), selection())
        assertSound()
    }

    @Test
    fun composingRegionStraddlingTheGlyph_thenAbandoned_leavesTheBytesAlone() {
        open(DOC + "- [ ] milk", caret = 21)
        batch {
            setComposingRegion(13, 18)
            finishComposingText()
        }

        assertEquals(DOC + "- [ ] milk", text(), "an abandoned composition is a no-op on the document")
        assertNull(value().composition)
        assertSound()
    }

    // ---------------------------------------------------------------- (c)

    @Test
    fun backspaceAtADecorationBoundary_removesTheCorrectByte() {
        // Caret immediately past the marker's `]`.
        open(DOC + "- [ ] milk", caret = 21)
        ime { setSelection(16, 16) }
        ime { deleteSurroundingText(1, 0) }
        assertEquals(DOC + "- [  milk", text(), "the `]` went, and only the `]`")
        assertEquals(TextRange(15), selection())
        assertEquals("- ☑ done\n- [  milk", transformed(), "the broken marker stops decorating")
        assertSound()

        // Caret *inside* the marker: not visually addressable under
        // AlwaysGlyph (it snaps to the glyph's edge) but an IME can put it
        // there with setSelection, and the byte removed must still be right.
        open(DOC + "- [ ] milk", caret = 21)
        ime { setSelection(14, 14) }
        ime { deleteSurroundingText(1, 0) }
        assertEquals(DOC + "-  ] milk", text(), "the `[` went from inside the replacement")
        assertEquals(TextRange(13), selection())
        assertSound()

        // Backspace one byte past the marker, where the marker survives and
        // the table entry stays live under the caret.
        open(DOC + "- [ ] milk", caret = 21)
        ime { setSelection(17, 17) }
        ime { deleteSurroundingText(1, 0) }
        assertEquals(DOC + "- [ ]milk", text(), "the separating space went")
        assertEquals(TextRange(16), selection())
        assertSound()

        // And the whole-word backspace an autocorrecting IME issues.
        open(DOC + "- [ ] milk", caret = 21)
        ime { deleteSurroundingText(4, 0) }
        assertEquals(DOC + "- [ ] ", text())
        assertEquals(TextRange(17), selection())
        assertSound()
    }

    // ---------------------------------------------------------------- (d)

    // Three separate rows, one composing sequence each, so that each row
    // measures one autocorrect shape from a clean start rather than
    // inheriting the composing state the previous sequence left behind.

    @Test
    fun autocorrectReplacesAWordWithALongerOne_besideTheGlyph() {
        open(DOC + "- [ ] milk", caret = 21)
        batch {
            setComposingRegion(17, 21) // "milk"
            setComposingText("bread", 1)
        }
        assertEquals(DOC + "- [ ] bread", text())
        assertEquals(TextRange(22), selection())
        assertSound()

        val beforeCommit = selection()
        ime { finishComposingText() }
        assertEquals(DOC + "- [ ] bread", text(), "the correction committed once")
        assertEquals(beforeCommit, selection(), "caret stable across the commit")
        assertSound()
    }

    @Test
    fun autocorrectReplacesAWordWithAShorterOne_besideTheGlyph() {
        open(DOC + "- [ ] bread", caret = 22)
        batch {
            setComposingRegion(17, 22)
            setComposingText("mi", 1)
        }
        assertEquals(DOC + "- [ ] mi", text())
        assertEquals(TextRange(19), selection())
        assertSound()
    }

    @Test
    fun autocorrectOverARegionAbuttingTheMarker_replacesOnce() {
        open(DOC + "- [ ] milk", caret = 21)
        batch {
            setComposingRegion(16, 21) // " milk", opening on the byte after `]`
            setComposingText("!", 1)
        }
        assertEquals(DOC + "- [ ]!", text())
        assertEquals(TextRange(17), selection())
        assertSound()
    }

    // ---------------------------------------------------------------- (e)

    @Test
    fun rapidComposingChurn_neverCorruptsTheText() {
        val base = "- [ ] a\n- [x] b\n- [ ] c\n"
        open(base, caret = base.length)

        val words = listOf("alpha", "beta", "gamma", "delta")
        for (word in words) {
            // One word is one batch, which is how a keyboard actually
            // delivers a word: `beginBatchEdit` … `endBatchEdit` with the
            // composing span rewritten in place inside it. See
            // [theBoundKeyboardTearsDownTheLegacyFieldsComposingRegion] for
            // what a bound keyboard does to a region BETWEEN batches, and
            // why that is a property of the legacy API rather than of the
            // decoration under test.
            ime {
                beginBatchEdit()
                for (n in 1..word.length) setComposingText(word.take(n), 1)
                commitText("$word ", 1)
                endBatchEdit()
            }
            // Between words, walk the caret onto a glyph boundary and back:
            // the churn an IME's cursor-tracking produces, which is what
            // pushes offsets through the mapping most often.
            ime { setSelection(2, 2) }
            ime { setSelection(text().length, text().length) }
        }

        val expected = base + "alpha beta gamma delta "
        assertEquals(expected, text(), "18 composing spans over 4 words, byte-exact")
        assertEquals(TextRange(expected.length), selection())
        assertEquals(
            "- ☐ a\n- ☑ b\n- ☐ c\nalpha beta gamma delta ",
            transformed(),
            "all three glyphs survived the churn",
        )
        assertSound()
    }

    @Test
    fun rapidChurnInsideOneBatchEdit_neverCorruptsTheText() {
        // What an IME actually does: many commands inside one batch, one
        // recomposition at the end. Held on a single connection deliberately.
        open(DOC + "- [ ] milk\n", caret = 22)
        ime {
            beginBatchEdit()
            for (n in 1..7) setComposingText("compose".take(n), 1)
            setComposingRegion(22, 29)
            setComposingText("composed", 1)
            finishComposingText()
            commitText("!", 1)
            endBatchEdit()
        }

        assertEquals(DOC + "- [ ] milk\ncomposed!", text())
        assertEquals(TextRange(31), selection())
        assertEquals("- ☑ done\n- ☐ milk\ncomposed!", transformed())
        assertSound()
    }

    // --------------------------------------- the finding that gates MB2

    @WithSystemKeyboard
    @Test
    @LegacyImeDiagnostic
    fun theBoundKeyboardTearsDownTheLegacyFieldsComposingRegion() {
        // MS1's headline measurement, and it is NOT about the decoration.
        //
        // THIS ROW SUPERSEDES an earlier formulation, which asserted that the
        // legacy field "stops opening composing regions after a commit" so
        // that unbatched prefixes accumulate. The corruption is real and is
        // still pinned below — but the premise was wrong and the row could
        // only pass when a cross-process race resolved one particular way,
        // which is why it used to fail in BOTH directions on a loaded
        // emulator. The original wording and the evidence that retired it are
        // recorded in `spikes/ms1-ime/README.md`.
        //
        // What actually happens, measured: the legacy field DOES open the
        // region. Then the BOUND KEYBOARD tears it down unprompted — Google
        // LatinIME calls `finishComposingText` of its own accord 150–860 ms
        // after the edit, reacting to what the legacy field reports through
        // `updateSelection`. A platform `EditText` driven by the identical
        // rig under the identical keyboard is never touched at all
        // (`Ms1ControlsTest`), and `VisualTransformation.None` is torn down
        // exactly as this is — so the fault is the legacy field's, not the
        // keyboard's caprice and not the decoration's.
        //
        // This row runs WITH the keyboard bound, because the keyboard is what
        // it measures, and it synchronises on the teardown as an EVENT rather
        // than racing it. Every other row in this suite owns the session
        // exclusively so that nothing can interfere with what it measures.
        open(DOC + "- [ ] milk\n", caret = 22)
        // One held connection, the protocol a real keyboard uses and the
        // one `Ms1TextFieldStateSpikeTest` uses for the same sequence.
        val held = rule.runOnUiThread { findConnection(decorView(), EditorInfo()) }
        assertNotNull(held)
        fun on(block: InputConnection.() -> Unit) {
            rule.runOnUiThread { held.block() }
            rule.waitForIdle()
        }

        // 1. Our command opens a composing region, exactly as the API says it
        //    must. Read INSIDE the same UI-thread block that issues it: an
        //    interjection from another process arrives as its own looper
        //    message and cannot land inside this block, so the observation is
        //    exact rather than a race.
        val atCommand = rule.runOnUiThread {
            held.setComposingText("a", 1)
            state.value
        }
        rule.waitForIdle()
        assertEquals(DOC + "- [ ] milk\na", atCommand.text, "the composing span landed")
        assertEquals(
            TextRange(22, 23),
            atCommand.composition,
            "the legacy field DOES open a composing region",
        )

        // 2. Nobody asked it to, and yet the region goes away. The wait is
        //    bounded and asserted: if a platform ever stops doing this, the
        //    row FAILS here rather than passing quietly — which is exactly
        //    what a physical-device run should be able to check.
        assertTrue(
            awaitCondition(TEARDOWN_WAIT) { value().composition == null },
            "the bound keyboard tore the composing region down within ${TEARDOWN_WAIT}ms",
        )
        assertEquals(
            DOC + "- [ ] milk\na",
            text(),
            "and it wrote no bytes of its own doing so",
        )

        // 3. That teardown IS the corruption the ADR's pass criterion
        //    forbids: the next span, which a keyboard means as a REPLACEMENT
        //    of the one it is still rewriting, finds no region and appends.
        //    Deterministic now, because step 2 waited for the teardown rather
        //    than racing it.
        on { setComposingText("ab", 1) }
        assertEquals(
            DOC + "- [ ] milk\naab",
            text(),
            "so the prefixes accumulate: bytes the user did not type",
        )
        // The bytes are wrong, and the mapping over those wrong bytes is
        // still sound — which is the distinction the verdict turns on.
        assertSound()
    }

    // ------------------------------------------------- what the IME sees

    @Test
    fun theImeSeesTheSourceBytes_neverTheGlyph() {
        // The decisive fact about this design: the legacy connection mirrors
        // the raw `TextFieldValue`, so an autocorrector's dictionary, an
        // IME's word boundaries and every deleteSurroundingText count are
        // computed over the Markdown the user wrote — the glyph exists only
        // in the layout. This is why length-delta decoration is survivable.
        open(DOC + "- [ ] milk", caret = 21)

        val before = ime { getTextBeforeCursor(64, 0)?.toString() }
        assertEquals(DOC + "- [ ] milk", before, "the IME reads bytes, not decorations")
        assertTrue(before!!.contains("[ ]"), before)
        assertTrue(!before.contains("☐"), "no glyph ever crosses the InputConnection")

        val extracted = ime {
            getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()
        }
        assertEquals(DOC + "- [ ] milk", extracted, "extracted text is the source too")
    }

    @Test
    fun theFieldAdvertisesItselfAsAMultilineTextEditor() {
        open(DOC + "- [ ] milk", caret = 21)
        val info = rule.runOnUiThread {
            val info = EditorInfo()
            assertNotNull(findConnection(decorView(), info), "a real IME session is open")
            info
        }
        assertEquals(21, info.initialSelStart, "the IME is told the SOURCE caret offset")
        assertEquals(21, info.initialSelEnd)
    }

    // ------------------------------------------------- one held connection

    @Test
    fun oneConnectionHeldAcrossASequence_behavesLikeARealKeyboard() {
        // A real IME holds one InputConnection until the framework restarts
        // input. If Compose invalidated ours per edit, every keyboard would
        // see a restart storm — so this row is the fidelity check on the
        // route the rest of the suite uses.
        open(DOC + "- [ ] milk\n", caret = 22)
        val held = rule.runOnUiThread { findConnection(decorView(), EditorInfo()) }
        assertNotNull(held)

        rule.runOnUiThread {
            held.beginBatchEdit()
            for (n in 1..5) held.setComposingText("hello".take(n), 1)
            held.finishComposingText()
            held.endBatchEdit()
        }
        rule.waitForIdle()

        assertEquals(DOC + "- [ ] milk\nhello", text(), "one connection carried the whole sequence")
        assertEquals(TextRange(27), selection())
        assertSound()
    }

    // ------------------------------------------------- non-ASCII composing

    @Test
    fun cjkAndSurrogatePairComposing_besideTheGlyph_staysByteExact() {
        // The call sequence a CJK converter issues — a composing span
        // repeatedly rewritten in place, then committed as different
        // characters of a different length — over a live decoration table.
        open(DOC + "- [ ] milk\n", caret = 22)

        batch {
            setComposingText("n", 1)
            setComposingText("ni", 1)
            setComposingText("に", 1)
            setComposingText("にほ", 1)
            setComposingText("日本", 1)
            commitText("日本語", 1)
        }

        assertEquals(DOC + "- [ ] milk\n日本語", text())
        assertEquals(TextRange(25), selection())
        assertSound()

        // A surrogate pair: two chars for one grapheme, next to a table entry.
        batch {
            setComposingText("👍", 1)
            finishComposingText()
        }
        assertEquals(DOC + "- [ ] milk\n日本語👍", text())
        assertEquals(TextRange(27), selection(), "the caret sits past both code units")
        assertEquals("- ☑ done\n- ☐ milk\n日本語👍", transformed())
        assertSound()
    }

    // ------------------------------------------------- caret-line churn

    @Test
    fun typingIntoATaskLine_holdsUnderBothCaretRules() {
        // Under CaretLineRaw the table CHANGES SHAPE as the caret enters and
        // leaves the line — the churn ADR 0017 § 2 says the no-reflow law
        // removes. Under AlwaysGlyph the table is caret-invariant. Both must
        // produce the same bytes; only what renders may differ.
        open(DOC + "- [ ] mil", caret = 20)

        batch {
            setComposingText("k", 1)
            commitText("k", 1)
        }
        assertEquals(DOC + "- [ ] milk", text())

        // Leave the line, which under CaretLineRaw re-collapses it to a glyph.
        ime { setSelection(3, 3) }
        assertEquals(
            if (reveal == Reveal.AlwaysGlyph) "- ☑ done\n- ☐ milk" else "- [x] done\n- ☐ milk",
            transformed(),
            "the caret's arrival is what moves under the desktop rule",
        )
        assertSound()

        ime { setSelection(text().length, text().length) }
        ime { commitText("!", 1) }
        assertEquals(DOC + "- [ ] milk!", text())
        assertSound()
    }

    // ---------------------------------------------------------------- rig

    /// The doc every row carries: a task line that is never the caret's, so
    /// the offset table holds at least one live entry under BOTH configs.
    private val DOC = "- [x] done\n"

    /// Seeds the buffer and waits until a real IME session exists.
    ///
    /// The field must hold its text BEFORE `setContent`, and the
    /// programmatic write must then land AFTER the session exists, so that
    /// every row starts from one identical state.
    ///
    /// An earlier version of this comment claimed the second half was
    /// steering around a settling session that "drops its composing region
    /// perhaps one run in three". That was wrong: the session does not
    /// settle into correctness, and waiting longer never helped. What was
    /// actually happening is that a second process — the bound keyboard —
    /// was on the same connection, tearing composing regions down at
    /// unpredictable moments. [Ms1InputSessionRule] now gives this row sole
    /// ownership of the input session, which is what makes the suite
    /// deterministic; the seeding order below is kept because it is still
    /// the cleanest way to start every row from the same bytes.
    private fun open(source: String, caret: Int) {
        if (!contentSet) {
            state.value = TextFieldValue(source, TextRange(caret))
            contentSet = true
            val focus = FocusRequester()
            rule.setContent {
                LaunchedEffect(Unit) { focus.requestFocus() }
                Host(focus)
            }
            rule.waitForIdle()
            rule.onNodeWithTag(FIELD).assertExists()
            rule.waitUntil(SESSION_WAIT) {
                rule.runOnUiThread { findConnection(decorView(), EditorInfo()) != null }
            }
        }
        rule.runOnUiThread { state.value = TextFieldValue(source, TextRange(caret)) }
        rule.waitForIdle()
        // One harmless IME round trip: a selection set to where the caret
        // already is. Measured, the first command delivered into a freshly
        // opened session is the one at risk, so the suite spends that risk
        // on a command that has no composing region to lose.
        ime { setSelection(caret, caret) }
    }

    private var contentSet = false

    @Composable
    private fun Host(focus: FocusRequester) {
        val current = state.value
        val line = when (reveal) {
            Reveal.AlwaysGlyph -> null
            Reveal.CaretLineRaw -> caretLine(current.text, current.selection.start)
        }
        BasicTextField(
            value = current,
            onValueChange = { state.value = it },
            visualTransformation = MarkdownEditTransformation(PALETTE, line),
            modifier = Modifier.fillMaxSize().testTag(FIELD).focusRequester(focus),
        )
    }

    /// Runs `block` on the field's REAL `InputConnection` — the object the
    /// platform hands an IME — on the UI thread, then lets Compose settle.
    /// The connection is re-obtained per interaction because that is what
    /// the framework itself does whenever input restarts; the held-connection
    /// row above proves a single connection works too.
    /// Delivers a composing sequence the way a keyboard actually does:
    /// inside one `beginBatchEdit`…`endBatchEdit`, so every command lands on
    /// a single edit buffer with no recomposition between them.
    ///
    /// This is not a convenience: it is the delivery shape a real IME uses,
    /// so the matrix measures the offset mapping under the traffic that
    /// actually occurs. Inside one batch every command lands on a single
    /// edit buffer with no recomposition between, which is also why a batch
    /// cannot be interrupted by another actor on the connection.
    ///
    /// An earlier version of this comment said batched delivery was "the
    /// ONLY shape in which the legacy field keeps a composing region
    /// reliably", unbatched losing one "about a quarter of the time". The
    /// loss was real but the cause was not the delivery shape: it was the
    /// bound keyboard tearing regions down between commands, which
    /// [theBoundKeyboardTearsDownTheLegacyFieldsComposingRegion] now pins
    /// directly. With the session owned exclusively, unbatched delivery is
    /// as stable as batched.
    private fun batch(block: InputConnection.() -> Unit) = ime {
        beginBatchEdit()
        block()
        endBatchEdit()
    }

    private fun <R> ime(block: InputConnection.() -> R): R {
        val result = rule.runOnUiThread {
            val ic = findConnection(decorView(), EditorInfo())
                ?: error("no InputConnection — the field has no IME session")
            ic.block()
        }
        rule.waitForIdle()
        return result
    }

    private fun decorView(): View = resumedDecorView()

    private fun findConnection(view: View, info: EditorInfo): InputConnection? =
        imeConnection(view, info)

    private fun value(): TextFieldValue = state.value

    private fun text(): String = state.value.text

    private fun selection(): TextRange = state.value.selection

    private fun transformationNow() = MarkdownEditTransformation(
        PALETTE,
        when (reveal) {
            Reveal.AlwaysGlyph -> null
            Reveal.CaretLineRaw -> caretLine(text(), selection().start)
        },
    ).filter(AnnotatedString(text()))

    private fun transformed(): String = transformationNow().text.text

    /// Compose wraps every [androidx.compose.ui.text.input.OffsetMapping] in
    /// a validator that throws if a mapped offset leaves the string, and the
    /// caret, the composing underline and the IME's cursor-anchor rectangles
    /// all travel through it — so an unsound mapping is a crash, not a
    /// cosmetic fault. This asserts the validator's contract EXHAUSTIVELY at
    /// every offset in both directions, plus monotonicity, which is what
    /// keeps a composing span from inverting its ends.
    private fun assertSound() {
        val result = transformationNow()
        val source = text()
        val rendered = result.text.text
        val mapping = result.offsetMapping

        var previous = -1
        for (offset in 0..source.length) {
            val mapped = mapping.originalToTransformed(offset)
            assertTrue(
                mapped in 0..rendered.length,
                "originalToTransformed($offset) = $mapped out of 0..${rendered.length} for ${source.q()}",
            )
            assertTrue(mapped >= previous, "originalToTransformed is monotone at $offset in ${source.q()}")
            previous = mapped
        }

        previous = -1
        for (offset in 0..rendered.length) {
            val mapped = mapping.transformedToOriginal(offset)
            assertTrue(
                mapped in 0..source.length,
                "transformedToOriginal($offset) = $mapped out of 0..${source.length} for ${source.q()}",
            )
            assertTrue(mapped >= previous, "transformedToOriginal is monotone at $offset in ${source.q()}")
            previous = mapped
        }

        // The caret the user can actually see must round-trip exactly: a
        // caret that is not inside a replaced range is an exact inverse, and
        // the selection is never inside one after any sequence above.
        val caret = selection().start
        val there = mapping.originalToTransformed(caret)
        assertTrue(
            mapping.transformedToOriginal(there) == caret || insideReplacement(source, caret),
            "the caret at $caret does not round-trip in ${source.q()}",
        )
    }

    /// True when `offset` is strictly inside a task marker that renders as a
    /// glyph, the one place the mapping is documented to snap rather than
    /// invert (MarkdownEditing.kt, DecorationOffsetMapping).
    private fun insideReplacement(source: String, offset: Int): Boolean {
        val marker = checkboxMarkerAt(source, offset) ?: return false
        return offset > marker.first
    }

    private fun String.q(): String = "\"" + replace("\n", "\\n") + "\""

    companion object {
        private const val FIELD = "ms1-field"
        private const val SESSION_WAIT = 10_000L

        /// The bound keyboard's teardown was measured at 150–860 ms across
        /// runs on this emulator. The bound is generous against a loaded one;
        /// the row returns as soon as the event lands, so the margin costs
        /// nothing when the machine is fast.
        private const val TEARDOWN_WAIT = 2_000L

        private val PALETTE = MarkdownPalette(
            muted = Color.Gray,
            marker = Color.Blue,
            heading = Color.Black,
            code = Color.Blue,
            codeBackground = Color.LightGray,
            link = Color.Blue,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun configurations(): List<Array<Any>> =
            listOf(arrayOf(Reveal.AlwaysGlyph), arrayOf(Reveal.CaretLineRaw))
    }
}
