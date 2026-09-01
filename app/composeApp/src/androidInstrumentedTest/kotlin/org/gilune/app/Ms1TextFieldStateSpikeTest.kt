// MS1's second and third questions (ADR 0017 § 8): "should the editor move
// to `TextFieldState` with `outputTransformation`, which drops manual offset
// mapping and holds 'styling never changes bytes' in the API itself; and
// where does undo come from."
//
// This is a PROTOTYPE, not a migration. It stands the same checkbox
// decoration up on the modern API — `BasicTextField(state = …,
// outputTransformation = …)` — and drives it through the same real
// `InputConnection` the legacy suite uses, so the two are compared on
// identical sequences rather than on documentation. What it is measuring:
//
//  1. Whether the offset mapping still has to be written by hand. On the
//     legacy API it does: `MarkdownEditTransformation` builds a table and
//     `DecorationOffsetMapping` inverts it, and every bug ADR 0017 § 8 names
//     lives in that inversion. On the modern API the transformation is
//     expressed as *edits to a buffer* and Compose derives the mapping from
//     the edits it watched — the code below never writes an offset.
//  2. Whether the bytes are structurally safe. The output buffer is a
//     separate object from `TextFieldState`; there is no path by which a
//     rendering can reach the document. ADR 0012's invariant stops being a
//     rule the shell must keep and becomes a fact of the API.
//  3. Where undo comes from: `TextFieldState.undoState`, which the legacy
//     `TextFieldValue` field simply does not have.

package org.gilune.app

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalFoundationApi::class)
class Ms1TextFieldStateSpikeTest {

    @get:Rule
    val rule: ComposeContentTestRule = createComposeRule()

    private val state = TextFieldState(DOC, initialSelection = TextRange(DOC.length))

    @Test
    fun theOutputTransformationCannotReachTheDocument() {
        open()
        // The glyph is rendered — the field is laid out with it — and the
        // document is untouched. On the legacy API this is a property the
        // shell has to preserve by never writing through the transformation;
        // here the output buffer simply is not the state.
        assertEquals(DOC, state.text.toString(), "rendering wrote nothing")
        rule.onNodeWithTag(FIELD).assertExists()
        assertEquals("- ☑ done\n- ☐ milk", renderOf(DOC), "the same decoration, expressed as edits")
    }

    @Test
    fun composingRegionStraddlingTheGlyph_thenReplaced_insertsNothingTwice() {
        open()
        // The exact sequence the legacy suite's row (b) runs, in the same
        // batched delivery, so the two results compare like for like.
        ime {
            beginBatchEdit()
            setComposingRegion(13, 18)
            setComposingText("X", 1)
            endBatchEdit()
        }
        assertEquals("- [x] done\n- Xilk", state.text.toString(), "replaced once, not twice")
        assertEquals(TextRange(14), state.selection)
    }

    @Test
    fun backspaceAtADecorationBoundary_removesTheCorrectByte() {
        open()
        ime { setSelection(16, 16) }
        ime { deleteSurroundingText(1, 0) }
        assertEquals("- [x] done\n- [  milk", state.text.toString(), "the `]` went, and only the `]`")
        assertEquals(TextRange(15), state.selection)
    }

    @Test
    fun theImeSeesTheDECORATEDTextOnThisApi_whichIsTheTradeOff() {
        open()
        // The one place the modern API is WORSE, and it must be stated
        // plainly because MB2 inherits it: where the legacy connection
        // mirrors the raw `TextFieldValue`, this one hands the IME the
        // OUTPUT-transformed text, and reports the caret in transformed
        // coordinates. Compose maps the IME's edits back — the tests above
        // prove it does — but the keyboard's own word segmentation,
        // dictionary and suggestion logic see the glyph as a character of
        // the document.
        val before = ime { getTextBeforeCursor(64, 0)?.toString() }
        assertEquals("- ☑ done\n- ☐ milk", before, "the IME is shown the rendering")
        assertTrue(!before!!.contains("[ ]"), "and not the source")

        val info = EditorInfo()
        rule.runOnUiThread { assertNotNull(imeConnection(resumedDecorView(), info)) }
        assertEquals(17, info.initialSelStart, "the caret is reported transformed (source 21)")

        // Which is survivable only because the one length-delta decoration
        // ADR 0017 § 2 permits is delimited on both sides — bullet, space,
        // glyph, space — so it is its own word and no autocorrect span can
        // straddle it. A length-delta decoration placed INSIDE a word would
        // put the glyph inside the IME's dictionary lookups.
        assertTrue(before.contains("- ☑ "), "the glyph stands alone between spaces")
        assertTrue(before.contains("- ☐ "), "on every task line")
    }

    // NOT a test, deliberately: the unbatched after-a-commit comparison.
    //
    // Driven unbatched, the legacy field accumulates composing prefixes
    // EVERY run (pinned by
    // Ms1ImeSpikeTest.theLegacyFieldStopsOpeningComposingRegionsAfterACommit).
    // This field mostly does not — but it did accumulate in a minority of
    // runs on this rig, so the comparison is recorded in
    // `spikes/ms1-ime/README.md` with its counts rather than asserted here.
    // A test that passes most of the time is worse than a number that is
    // honest about how often it was taken.

    @Test
    fun undoIsCarriedByTheApi() {
        open()
        // ADR 0017 § 8's third question, answered by the type: the modern
        // state object owns an undo history, and it captures IME-originated
        // edits without the shell keeping a stack of its own.
        assertTrue(!state.undoState.canUndo, "a freshly opened document has nothing to undo")

        ime { commitText(" and bread", 1) }
        assertEquals("- [x] done\n- [ ] milk and bread", state.text.toString())
        assertTrue(state.undoState.canUndo, "the IME's commit entered the undo history")

        rule.runOnUiThread { state.undoState.undo() }
        rule.waitForIdle()
        assertEquals(DOC, state.text.toString(), "undo restored the document")
        assertTrue(state.undoState.canRedo, "and redo is the other half, also free")

        rule.runOnUiThread { state.undoState.redo() }
        rule.waitForIdle()
        assertEquals("- [x] done\n- [ ] milk and bread", state.text.toString())
    }

    // ---------------------------------------------------------------- rig

    private fun open() {
        val focus = FocusRequester()
        rule.setContent {
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(
                state = state,
                outputTransformation = CheckboxOutput,
                modifier = Modifier.fillMaxSize().testTag(FIELD).focusRequester(focus),
            )
        }
        rule.waitForIdle()
        rule.waitUntil(SESSION_WAIT) {
            rule.runOnUiThread { imeConnection(resumedDecorView(), EditorInfo()) != null }
        }
        // The same settling warm-up the legacy rig needs: a command issued
        // into a session still coming up from `setContent` can lose its
        // composing region. Writing the document the state already holds
        // changes nothing and makes every row start from one state.
        rule.runOnUiThread {
            state.setTextAndPlaceCursorAtEnd(DOC)
            state.undoState.clearHistory()
        }
        rule.waitForIdle()
        // The same harmless warm-up round trip the legacy rig performs, so
        // the two are driven like for like.
        ime { setSelection(DOC.length, DOC.length) }
    }

    private fun <R> ime(block: InputConnection.() -> R): R {
        val result = rule.runOnUiThread {
            val ic = imeConnection(resumedDecorView(), EditorInfo())
                ?: error("no InputConnection — the field has no IME session")
            ic.block()
        }
        rule.waitForIdle()
        return result
    }

    /// What the prototype renders. `TextFieldBuffer` is constructible only by
    /// Compose — which is itself the point being made — so the rendering is
    /// proved in the field above and mirrored here over the same edit list.
    private fun renderOf(source: String): String = applyGlyphs(source)

    companion object {
        private const val FIELD = "ms1-tfs-field"
        private const val SESSION_WAIT = 10_000L
        private const val DOC = "- [x] done\n- [ ] milk"

        /// The checkbox decoration on the modern API. Note what is absent:
        /// there is no offset table, no `OffsetMapping`, and no inverse to
        /// get wrong. The transformation states the edits; Compose watches
        /// them and derives the mapping in both directions itself.
        ///
        /// Edits are applied back to front so earlier indices stay valid —
        /// the one piece of index care the API still asks for.
        private val CheckboxOutput = OutputTransformation {
            for ((range, glyph) in glyphEdits(asCharSequence().toString()).asReversed()) {
                replace(range.first, range.last + 1, glyph)
            }
        }

        private val TASK = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")

        private fun glyphEdits(source: String): List<Pair<IntRange, String>> {
            val edits = mutableListOf<Pair<IntRange, String>>()
            var offset = 0
            for (line in source.split('\n')) {
                TASK.find(line)?.groups?.get(4)?.let { marker ->
                    val start = offset + marker.range.first
                    val glyph = if (line[marker.range.first + 1] == ' ') "☐" else "☑"
                    edits += (start until start + 3) to glyph
                }
                offset += line.length + 1
            }
            return edits
        }

        private fun applyGlyphs(source: String): String {
            var out = source
            for ((range, glyph) in glyphEdits(source).asReversed()) {
                out = out.substring(0, range.first) + glyph + out.substring(range.last + 1)
            }
            return out
        }
    }
}
