// Replace across notes in the Search panel (ADR 0031 § 4), through the
// real panel and kernel. The step past the obvious: an untick leaves that
// note alone, Undo puts the rest back, and with the plugin off the panel is
// plain search with no way in.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import java.io.File
import org.gilune.plugins.findreplace.FIND_REPLACE_PLUGIN
import org.junit.Rule
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.Gilune
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NotesReplaceUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private fun panel(pluginOn: Boolean = true): File {
        val root = resources.directory("gilune-notes-replace")
        val kernel = resources.own(Gilune.open(root.path))
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.registerIndexers(kernel)
        root.resolve("a.md").writeText("red fish\n")
        root.resolve("b.md").writeText("red hat\n")
        kernel.reconcile()
        kernel.project()
        kernel.advanceIndex()
        val state = GiluneState(root.path, resources.scope)
        state.gilune.value = kernel
        state.plugins.addAll(kernel.plugins().filter { pluginOn || it.pluginId != FIND_REPLACE_PLUGIN })
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.size(width = 320.dp, height = 600.dp)) {
                    SearchPanel(state, remember { FocusRequester() }, onOpen = {}, Modifier.size(width = 320.dp, height = 600.dp))
                }
            }
        }
        compose.waitForIdle()
        return root
    }

    private fun body(root: File, path: String) = root.resolve(path).readText().substringAfter("\n---\n")

    @Test
    fun replacesInTheTickedNotesAndUndoPutsThemBack() {
        val root = panel()
        compose.onNodeWithTag("search-toggle-replace").performClick()
        compose.onNodeWithTag("notes-find").performTextInput("red")
        compose.onNodeWithTag("notes-replacement").performTextInput("blue")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("notes-note:b.md").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("notes-tick:b.md").performClick()
        compose.onNodeWithTag("notes-replace-all").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notes-undo").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("blue fish\n", body(root, "a.md"))
        assertEquals("red hat\n", body(root, "b.md"), "the unticked note is left alone")

        compose.onNodeWithTag("notes-undo").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notes-undo").fetchSemanticsNodes().isEmpty() }
        assertEquals("red fish\n", body(root, "a.md"))
    }

    @Test
    fun withThePluginOffThePanelOffersNoReplace() {
        panel(pluginOn = false)
        assertEquals(0, compose.onAllNodesWithTag("search-toggle-replace").fetchSemanticsNodes().size)
    }
}
