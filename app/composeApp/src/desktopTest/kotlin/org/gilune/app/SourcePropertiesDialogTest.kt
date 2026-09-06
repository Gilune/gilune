package org.gilune.app

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import kotlin.test.*
import uniffi.gilune_ffi.*

@OptIn(ExperimentalTestApi::class)
class SourcePropertiesDialogTest {
    @get:Rule val compose = createComposeRule()
    private fun awaitField() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("property-name").fetchSemanticsNodes().isNotEmpty() }

    @Test fun sharedDialogEditsTextWithoutRewritingOtherPropertiesOrBody() {
        val raw = "---\ngilune-id: abc\nname: Alice # comment\nnested: [a,b]\n---\n"
        val editor = EditorState().apply { frontMatterRaw = raw; text = TextFieldValue("body 😀") }
        var dismissed = false
        compose.setContent { GiluneTheme(false) { Box(Modifier.requiredSize(520.dp, 720.dp)) { SourcePropertiesDialog(editor.document(), { dismissed = true }, editor::applyProperties) } } }
        awaitField()
        compose.onNodeWithTag("property-name").performTextClearance()
        compose.onNodeWithTag("property-name").performTextInput("true")
        compose.runOnIdle { editor.text = TextFieldValue("body changed while form is open") }
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(10_000) { dismissed }
        assertEquals(raw.replace("name: Alice", "name: \"true\""), editor.frontMatterRaw)
        assertTrue(editor.dirty)
        assertEquals("body changed while form is open", editor.text.text)
    }

    @Test fun concurrentHeaderChangeRequiresReviewInsteadOfOverwritingIt() {
        val raw = "---\nname: Alice\n---\n"
        val editor = EditorState().apply { frontMatterRaw = raw; text = TextFieldValue("body 😀") }
        var dismissed = false
        compose.setContent { GiluneTheme(false) { Box(Modifier.requiredSize(520.dp, 720.dp)) { SourcePropertiesDialog(editor.document(), { dismissed = true }, editor::applyProperties) } } }
        awaitField()
        compose.runOnIdle { editor.frontMatterRaw = raw.replace("Alice", "Externally updated") }
        compose.onNodeWithTag("property-name").performTextInput(" edited")
        compose.onNodeWithText("Apply").performClick()
        val message = "The properties changed while this form was open. Reopen it to review the current values."
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(dismissed)
        assertTrue(editor.frontMatterRaw.contains("Externally updated"))
        assertFalse(editor.dirty)
    }
}
