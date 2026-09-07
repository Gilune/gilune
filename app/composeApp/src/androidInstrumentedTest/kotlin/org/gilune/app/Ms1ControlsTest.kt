// MS1's control group.
//
// `Ms1ImeSpikeTest.theBoundKeyboardTearsDownTheLegacyFieldsComposingRegion`
// records a real defect: with a keyboard bound, the legacy
// `BasicTextField(TextFieldValue)` has its composing region torn down after
// every edit by the keyboard itself, so a keyboard rewriting its composing
// span in place appends instead of replacing and the document gains bytes
// the user did not type. A defect found through a rig is worth nothing until
// the rig is ruled out, and a defect found in a field carrying our decoration
// is worth nothing until the decoration is ruled out. These two rows do both,
// on the identical rig and under the identical bound keyboard:
//
//  1. A plain platform `EditText` — the reference implementation of an
//     `InputConnection` — driven by the same calls under the same keyboard is
//     never interfered with at all, and re-opens its composing region
//     correctly. So the rig is sound and the keyboard is not simply hostile
//     to everything.
//  2. The same Compose field with `VisualTransformation.None` — no
//     decoration, no offset mapping, nothing of ours at all — is torn down
//     exactly as the decorated one is. So the decoration is not implicated.
//
// Together they place the fault in the legacy Compose text field, which is
// what turns MS1's verdict into a condition on the API rather than a
// condition on ADR 0017 § 2's rendering.
//
// Both rows run WITH the device's keyboard bound ([WithSystemKeyboard]),
// because the keyboard's behaviour is the thing being compared. Every other
// MS1 row owns the input session exclusively — see `Ms1ImeRig.kt`.

package org.gilune.app

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Rule
import androidx.test.filters.SdkSuppress
import org.junit.Test
import org.junit.rules.RuleChain
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ms1ControlsTest {
    private val rule: ComposeContentTestRule = createComposeRule()

    /// [Ms1InputSessionRule] must be the OUTER rule — see `Ms1ImeRig.kt`.
    /// Both rows here are annotated [WithSystemKeyboard], so what it does for
    /// this class is guarantee the keyboard really IS bound before the
    /// activity launches, including after an earlier row failed.
    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(Ms1InputSessionRule()).around(rule)

    // Seeded before `setContent`: a session that opens over an empty buffer
    // never opens a composing region afterwards (measured), which would make
    // this control prove the wrong thing.
    private val state: MutableState<TextFieldValue> =
        mutableStateOf(TextFieldValue(DOC, TextRange(DOC.length)))
    private var editText: EditText? = null

    @WithSystemKeyboard
    @Test
    fun aPlatformEditTextIsNeverTouchedByTheBoundKeyboard() {
        rule.setContent {
            AndroidView(
                factory = { context -> EditText(context).also { editText = it } },
                modifier = Modifier.fillMaxSize(),
            )
            LaunchedEffect(Unit) { editText?.requestFocus() }
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            editText?.requestFocus()
            editText?.setText(DOC)
            editText?.setSelection(DOC.length)
        }
        rule.waitForIdle()
        awaitSession()
        warmUp()

        ime { setComposingText("a", 1) }
        ime { setComposingText("ab", 1) }
        assertEquals(DOC + "ab", plainText(), "the first composing run replaces in place")

        ime { commitText("ab ", 1) }
        assertEquals(DOC + "ab ", plainText())
        assertEquals(-1, composingStart(), "a commit closes the region, as it must")

        ime { setComposingText("c", 1) }
        assertEquals(24, composingStart(), "and the platform opens a NEW one")

        // The measurement that places the fault. Over a window in which the
        // legacy Compose field is torn down every single time — the teardown
        // was measured at 150–860 ms — nothing touches the EditText at all.
        // This is a negative assertion, so it is only as strong as the window
        // it watched; the window is stated rather than implied.
        val interjections = observeFor(WATCH_WINDOW) { editTextSnapshot() }
        assertTrue(
            interjections.isEmpty(),
            "nobody interfered with the EditText in ${WATCH_WINDOW}ms, yet $interjections",
        )

        ime { setComposingText("cd", 1) }
        assertEquals(
            DOC + "ab cd",
            plainText(),
            "so the second span replaced the first — no accumulation",
        )
    }

    @WithSystemKeyboard
    // A reproduction of the API 36 LatinIME observation in spikes/ms1-ime,
    // not an expectation that every Android keyboard must exhibit this bug.
    @SdkSuppress(minSdkVersion = 36, maxSdkVersion = 36)
    @Test
    fun theSameComposeFieldIsTornDownWithNoTransformationAtAll() {
        val focus = FocusRequester()
        rule.setContent {
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(
                value = state.value,
                onValueChange = { state.value = it },
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxSize().testTag("control").focusRequester(focus),
            )
        }
        rule.waitForIdle()
        awaitSession()
        rule.runOnUiThread { state.value = TextFieldValue(DOC, TextRange(DOC.length)) }
        rule.waitForIdle()
        warmUp()

        // The command opens a region, read inside the same UI-thread block
        // that issues it so no other process can have touched it yet.
        val atCommand = rule.runOnUiThread {
            imeConnection(resumedDecorView(), EditorInfo())!!.setComposingText("a", 1)
            state.value
        }
        rule.waitForIdle()
        assertEquals(DOC + "a", atCommand.text, "the composing span landed")
        assertEquals(
            TextRange(21, 22),
            atCommand.composition,
            "with no decoration in play the field opens a region too",
        )

        // And it is torn down just the same, with nothing of ours anywhere
        // near it. Bounded wait on the event: if the platform stops doing
        // this, the row fails here.
        assertTrue(
            awaitCondition(TEARDOWN_WAIT) { liveComposition() == null },
            "the bound keyboard tore the region down within ${TEARDOWN_WAIT}ms here too",
        )

        ime { setComposingText("ab", 1) }
        assertEquals(
            DOC + "aab",
            state.value.text,
            "identical accumulation with no decoration in play: the fault is the API's",
        )
    }

    // ---------------------------------------------------------------- rig

    private fun awaitSession() = rule.waitUntil(SESSION_WAIT) {
        rule.runOnUiThread { imeConnection(resumedDecorView(), EditorInfo()) != null }
    }

    /// The same harmless warm-up round trip `Ms1ImeSpikeTest` performs, so
    /// the platform baseline and the Compose field are driven by an
    /// identical rig and the comparison between them means something.
    private fun warmUp() = ime { setSelection(DOC.length, DOC.length) }

    private fun <R> ime(block: InputConnection.() -> R): R {
        val result = rule.runOnUiThread {
            val ic = imeConnection(resumedDecorView(), EditorInfo())
                ?: error("no InputConnection — the field has no IME session")
            ic.block()
        }
        rule.waitForIdle()
        return result
    }

    private fun liveComposition(): TextRange? = rule.runOnUiThread { state.value.composition }

    private fun plainText(): String = rule.runOnUiThread { editText!!.text.toString() }

    private fun composingStart(): Int =
        rule.runOnUiThread { BaseInputConnection.getComposingSpanStart(editText!!.text) }

    /// Everything about the EditText another actor could disturb: its bytes,
    /// its caret and its composing span.
    private fun editTextSnapshot(): String = rule.runOnUiThread {
        val editable = editText!!.text
        "text=${editable} sel=${editText!!.selectionStart}" +
            " comp=${BaseInputConnection.getComposingSpanStart(editable)}" +
            "..${BaseInputConnection.getComposingSpanEnd(editable)}"
    }

    private companion object {
        const val DOC = "- [x] done\n- [ ] milk"
        const val SESSION_WAIT = 10_000L
        const val TEARDOWN_WAIT = 2_000L

        /// The window the EditText was proved undisturbed over. The Compose
        /// field's teardown lands inside it every time.
        const val WATCH_WINDOW = 1_200L
    }
}
