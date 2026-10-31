// The shortcuts window shades the row under the pointer (owner ask,
// 2026-10-31) so the eye can follow an action across to its keys. The
// step past the obvious: moving on clears the row left behind — one row
// shaded at a time, never a trail.

package org.gilune.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShortcutsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRowUnderThePointerIsShadedAndOnlyThatRow() {
        compose.setContent { GiluneTheme(dark = false) { ShortcutsDialog(emptyList(), onDismiss = {}) } }
        compose.waitForIdle()

        compose.onNodeWithTag("shortcut:Ctrl+N").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        assertEquals("hovered", hoverOf("shortcut:Ctrl+N"))
        assertEquals(null, hoverOf("shortcut:Ctrl+P"))

        compose.onNodeWithTag("shortcut:Ctrl+P").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        assertEquals(null, hoverOf("shortcut:Ctrl+N"), "the row left behind clears")
        assertEquals("hovered", hoverOf("shortcut:Ctrl+P"))
    }

    private fun hoverOf(tag: String): String? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
}
