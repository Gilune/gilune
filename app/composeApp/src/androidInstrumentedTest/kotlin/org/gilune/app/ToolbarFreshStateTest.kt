package org.gilune.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class ToolbarFreshStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slashUsesTheCaretAtClickTimeEvenBeforeRecomposition() {
        val body = TextFieldState("- [ ] milk \n", TextRange.Zero)
        show(body)
        val click = action("slash")
        compose.runOnUiThread {
            body.edit { selection = TextRange(11) }
            // No frame can replace the rendered callback between these calls.
            click()
        }
        assertEquals("- [ ] milk /\n", body.text.toString())
        assertEquals(TextRange(12), body.selection)
    }

    @Test fun panelActionUsesTheLatestTextAndSelection() {
        val body = TextFieldState("old text", TextRange.Zero)
        show(body)
        compose.onNodeWithTag("more").performClick()
        val click = action("code")
        compose.runOnUiThread {
            body.edit {
                replace(0, length, "fresh text")
                selection = TextRange(0, 5)
            }
            click()
        }
        assertEquals("`fresh` text", body.text.toString())
    }

    private fun show(body: TextFieldState) {
        compose.setContent {
            GiluneTheme(dark = false) {
                EditorToolbar(body, emptyList(), null, {}, {})
            }
        }
    }

    private fun action(tag: String): () -> Boolean = checkNotNull(
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
    )
}
