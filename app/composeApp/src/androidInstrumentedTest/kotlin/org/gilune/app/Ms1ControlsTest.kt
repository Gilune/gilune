// MS1's control group.
//
// `Ms1ImeSpikeTest.theLegacyFieldStopsOpeningComposingRegionsAfterACommit`
// records a real defect: after a commit, the legacy
// `BasicTextField(TextFieldValue)` never opens another composing region, so
// a keyboard rewriting its composing span in place accumulates every
// intermediate prefix into the document. A defect found through a rig is
// worth nothing until the rig is ruled out, and a defect found in a field
// carrying our decoration is worth nothing until the decoration is ruled
// out. These two tests do both, on the identical rig:
//
//  1. A plain platform `EditText` — the reference implementation of an
//     `InputConnection` — driven by the same calls, re-opens the composing
//     region correctly. So the rig is sound.
//  2. The same Compose field with `VisualTransformation.None` — no
//     decoration, no offset mapping, nothing of ours at all — accumulates
//     exactly as it does with `MarkdownEditTransformation`. So the
//     decoration is not implicated.
//
// Together they place the fault in the legacy Compose text field, which is
// what turns MS1's verdict into a condition on the API rather than a
// condition on ADR 0017 § 2's rendering.

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
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Ms1ControlsTest {
    @get:Rule
    val rule: ComposeContentTestRule = createComposeRule()

    // Seeded before `setContent`: a session that opens over an empty buffer
    // never opens a composing region afterwards (measured), which would make
    // this control prove the wrong thing.
    private val state: MutableState<TextFieldValue> =
        mutableStateOf(TextFieldValue(DOC, TextRange(DOC.length)))
    private var editText: EditText? = null

    @Test
    fun aPlatformEditTextReOpensTheComposingRegionAfterACommit() {
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

        ime { setComposingText("cd", 1) }
        assertEquals(
            DOC + "ab cd",
            plainText(),
            "so the second span replaced the first — no accumulation",
        )
    }

    @Test
    fun theSameComposeFieldAccumulatesWithNoTransformationAtAll() {
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

        ime { setComposingText("a", 1) }
        ime { setComposingText("ab", 1) }
        assertEquals(DOC + "ab", state.value.text)

        ime { commitText("ab ", 1) }
        ime { setComposingText("c", 1) }
        assertNull(state.value.composition, "no region opens after a commit here either")

        ime { setComposingText("cd", 1) }
        assertEquals(
            DOC + "ab ccd",
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

    private fun plainText(): String = rule.runOnUiThread { editText!!.text.toString() }

    private fun composingStart(): Int =
        rule.runOnUiThread { BaseInputConnection.getComposingSpanStart(editText!!.text) }

    private companion object {
        const val DOC = "- [x] done\n- [ ] milk"
        const val SESSION_WAIT = 10_000L
    }
}
