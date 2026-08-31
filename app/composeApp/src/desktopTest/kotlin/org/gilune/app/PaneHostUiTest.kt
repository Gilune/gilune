// The strip is the half of ADR 0014 that only composition can answer:
// the model's rules are proven in TabsTest without a window, so what is
// left to show is that the tabs are drawn, that clicking one brings its
// note to the front, and that the dot and the close × share one slot per
// tab rather than each claiming their own.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PaneHostUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun note(dir: File, rel: String, body: String) {
        val file = File(dir, rel)
        file.parentFile?.mkdirs()
        file.writeText("---\ngilune-id: ${rel.removeSuffix(".md")}\n---\n$body\n")
    }

    @Test
    fun clicking_a_tab_brings_its_note_to_the_front() {
        val vault = Files.createTempDirectory("gilune-panes").toFile()
        note(vault, "alpha.md", "alpha body")
        note(vault, "beta.md", "beta body")
        // The host reads only the vault path, the status line and the
        // change tick, so no vault has to be opened to render it.
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val tabs = TabsState(vault.absolutePath)
        val alpha = tabs.open("alpha.md")
        val beta = tabs.open("beta.md")
        // Edit/Read is per tab and seeded from Prefs; the body field this
        // test reads exists only in Edit.
        alpha.editor.reading = false
        beta.editor.reading = false

        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { PaneHost(state, tabs, Modifier.fillMaxSize()) }
            }
        }

        // Labels are read by tag: the note title carries the same stem as
        // the tab of the note in front.
        val labels = compose.onAllNodesWithTag("tab-label")
        labels.assertCountEquals(2)
        labels[0].assertTextEquals("alpha")
        labels[1].assertTextEquals("beta")
        compose.onNodeWithTag("note-body").assertTextContains("beta body", substring = true)

        labels[0].performMouseInput { click() }

        compose.onNodeWithTag("note-body").assertTextContains("alpha body", substring = true)

        // One slot per tab, whatever is in it: an unsaved edit puts a dot
        // in the slot the close × uses, so the strip cannot jiggle.
        compose.runOnIdle {
            alpha.editor.text = TextFieldValue("alpha body, edited")
            alpha.editor.dirty = true
        }
        compose.onAllNodesWithTag("tab-affordance").assertCountEquals(2)
    }

    /// Two notes open in one pane, drawn: what the button tests below
    /// both need before they can press anything.
    private fun twoTabs(): TabsState {
        val vault = Files.createTempDirectory("gilune-panes").toFile()
        note(vault, "alpha.md", "alpha body")
        note(vault, "beta.md", "beta body")
        val state = GiluneState(vault.absolutePath, CoroutineScope(Dispatchers.Default))
        val tabs = TabsState(vault.absolutePath)
        tabs.open("alpha.md").editor.reading = false
        tabs.open("beta.md").editor.reading = false
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { PaneHost(state, tabs, Modifier.fillMaxSize()) }
            }
        }
        compose.onAllNodesWithTag("tab-label").assertCountEquals(2)
        return tabs
    }

    // The primary press on a chip grew a drag behind it (ADR 0014 stage
    // M+2), and the other two buttons share its pointer loop: these two
    // say that neither lost its gesture to the one that moves tabs.

    @Test
    fun middle_clicking_a_tab_still_closes_it() {
        twoTabs()

        compose.onAllNodesWithTag("tab-label")[1].performMouseInput {
            moveTo(center)
            press(MouseButton.Tertiary)
            release(MouseButton.Tertiary)
        }

        val left = compose.onAllNodesWithTag("tab-label")
        left.assertCountEquals(1)
        left[0].assertTextEquals("alpha")
    }

    @Test
    fun right_clicking_a_tab_still_opens_its_menu() {
        twoTabs()

        compose.onAllNodesWithTag("tab-label")[0].performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }

        compose.onNodeWithText("Split right").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
