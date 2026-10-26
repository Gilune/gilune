// A connection page's properties section through the real editor (owner,
// 2026-10-26): its fields edit the page's front matter and save at once —
// the pill colour from the reused picker, the retired switch, the label on
// Enter — and the keyword offers Rename references instead of an edit. The
// step past the obvious: a label left blank is refused, and nothing is
// written for it.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.gilune.plugins.people.CONNECTION_SHEET
import org.gilune.plugins.people.CONNECTION_TYPE
import org.junit.Rule
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.Gilune
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PagePropertySheetUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private lateinit var file: File

    private fun open() {
        val vault = resources.directory("gilune-sheet")
        vault.resolve(".gilune-local").writeText("") // local-only vault: files stay unmanaged (ADR 0007, 2026-10-22)
        file = vault.resolve("reviewer.md")
        file.writeText("---\ngilune-type: $CONNECTION_TYPE\ngilune-id: reviewer-page\nkeyword: \"reviewer\"\nlabel: \"Reviewer\"\nretired: false\n---\n# Notes\n")
        val kernel = resources.own(Gilune.open(vault.path))
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        val state = GiluneState(vault.path, resources.scope)
        state.gilune.value = kernel
        state.plugins.addAll(kernel.plugins())
        val editor = EditorState()
        editor.open(vault.path, "reviewer.md")
        editor.reading = false
        compose.setContent {
            GiluneTheme(dark = false) { Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) } }
        }
        compose.waitForIdle()
    }

    private fun waitForFile(what: String, check: (String) -> Boolean) =
        compose.waitUntil(10_000) { check(file.readText()) }.also { assertTrue(check(file.readText()), what) }

    @Test
    fun theSectionNamesThePageTypeAndOpensOnItsFields() {
        open()
        compose.onNodeWithText("People connection", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("page-sheet-$CONNECTION_SHEET").assertDoesNotExist()
        compose.onNodeWithTag("properties-bar").performClick()
        compose.onNodeWithTag("page-sheet-$CONNECTION_SHEET").assertIsDisplayed()
        compose.onNodeWithTag("page-property-keyword").assertTextEquals("reviewer")
        // The raw lines are folded behind "All properties" when a sheet leads.
        compose.onNodeWithText("gilune-id: reviewer-page").assertDoesNotExist()
        compose.onNodeWithTag("page-sheet-raw").performClick()
        compose.onNodeWithText("gilune-id: reviewer-page").assertIsDisplayed()
    }

    @Test
    fun colourRetiredAndLabelAreEditedInPlaceAndSaved() {
        open()
        compose.onNodeWithTag("properties-bar").performClick()

        compose.onNodeWithTag("page-property-colour").assertContentDescriptionEquals("Pill colour: Default")
        compose.onNodeWithTag("page-property-colour").performClick()
        compose.onNodeWithTag("page-property-colour:green").performClick()
        waitForFile("the colour is written") { it.contains("colour: \"green\"") }
        compose.onNodeWithTag("page-property-colour").assertContentDescriptionEquals("Pill colour: Green")

        compose.onNodeWithTag("page-property-retired").performClick()
        waitForFile("retired is a boolean") { it.contains("retired: true") }

        val label = compose.onNodeWithTag("page-property-label")
        label.performTextReplacement("Code reviewer")
        label.performKeyInput { pressKey(Key.Enter) }
        waitForFile("the label is written on Enter") { it.contains("label: \"Code reviewer\"") }
        assertTrue(file.readText().endsWith("# Notes\n"), "the body is untouched")
    }

    @Test
    fun aBlankLabelIsRefusedAndNothingIsWritten() {
        open()
        compose.onNodeWithTag("properties-bar").performClick()
        val before = file.readText()
        val label = compose.onNodeWithTag("page-property-label")
        label.performTextReplacement("   ")
        label.performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Label can't be empty.").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(before, file.readText())
        assertFalse(file.readText().contains("label: \"\""))
    }

    @Test
    fun theKeywordOffersRenameReferences() {
        open()
        compose.onNodeWithTag("properties-bar").performClick()
        compose.onNodeWithTag("page-property-keyword-rename").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Rename references").fetchSemanticsNodes().isNotEmpty() }
    }
}
