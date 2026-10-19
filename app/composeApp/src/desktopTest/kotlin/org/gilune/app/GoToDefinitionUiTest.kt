// Go to definition through the real editor (owner, 2026-10-19): the caret
// on an @mention and F12 opens that person's page in a tab. The plugin owns
// the keystroke, so the step past the obvious is a rebinding: once the
// settings name another key, F12 does nothing and the new key does it.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.gilune.plugins.people.PEOPLE_PLUGIN
import org.gilune.plugins.people.PERSON_TYPE
import org.gilune.plugins.people.peopleSettingsDocument
import org.junit.Rule
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.Gilune
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GoToDefinitionUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private val body = "talk to @alice.smith today"

    private fun editorOn(caret: Int, keySettings: String? = null): Pair<GiluneState, TabsState> {
        val root = resources.directory("gilune-definition")
        val kernel = resources.own(Gilune.open(root.path))
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.registerIndexers(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.initializePages(kernel)
        if (keySettings != null) kernel.setPluginSettings(PEOPLE_PLUGIN, keySettings)
        root.resolve("alice.md").writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: alice\nname: Alice Smith\nhandle: alice.smith\n---\n")
        kernel.reconcilePath("alice.md")
        root.resolve("note.md").writeText("---\ngilune-id: note\n---\n$body")
        val state = GiluneState(root.path, resources.scope)
        state.gilune.value = kernel
        state.plugins.addAll(kernel.plugins())
        val tabs = TabsState(root.path)
        val tab = tabs.open("note.md")
        tab.editor.open(root.path, "note.md")
        tab.editor.text = TextFieldValue(tab.editor.text.text, TextRange(caret))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.size(width = 700.dp, height = 400.dp)) {
                    NoteEditor(state, tab.editor, Modifier.size(width = 700.dp, height = 400.dp), tabs = tabs, tab = tab)
                }
            }
        }
        compose.onNodeWithTag("note-body").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        return state to tabs
    }

    private fun press(vararg codes: Long) {
        val keys = codes.map { Key(it) }
        compose.onNodeWithTag("note-body").performKeyInput {
            keys.dropLast(1).forEach { keyDown(it) }
            pressKey(keys.last())
            keys.dropLast(1).reversed().forEach { keyUp(it) }
        }
        compose.waitForIdle()
    }

    @Test
    fun f12OnAMentionOpensThePersonsPage() {
        val (_, tabs) = editorOn(caret = body.indexOf("smith"))
        press(Key.F12.keyCode)
        compose.waitUntil(10_000) { tabs.activeTab?.relPath == "alice.md" }
    }

    @Test
    fun f12OffAMentionSaysSoAndOpensNothing() {
        val (state, tabs) = editorOn(caret = 2)
        press(Key.F12.keyCode)
        compose.waitUntil(10_000) { state.message.value.startsWith("nothing to go to here") }
        assertEquals("note.md", tabs.activeTab?.relPath)
    }

    @Test
    fun aRebindingMovesTheKeystrokeF12StopsAndTheNewKeyWorks() {
        val (_, tabs) = editorOn(caret = body.indexOf("smith"), keySettings = peopleSettingsDocument("", "Ctrl+Alt+G"))
        press(Key.F12.keyCode)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("note.md", tabs.activeTab?.relPath, "F12 is no longer the binding")

        press(Key.CtrlLeft.keyCode, Key.AltLeft.keyCode, Key.G.keyCode)
        compose.waitUntil(10_000) { tabs.activeTab?.relPath == "alice.md" }
    }

    @Test
    fun keystrokesAreSpelledOneWayForRecordingAndMatching() {
        assertEquals("F12", keystroke(KeyEvent.VK_F12, ctrl = false, alt = false, shift = false, meta = false))
        assertEquals("Ctrl+Alt+G", keystroke(KeyEvent.VK_G, ctrl = true, alt = true, shift = false, meta = false))
        assertEquals("Shift+F12", keystroke(KeyEvent.VK_F12, ctrl = false, alt = false, shift = true, meta = false))
        assertNull(keystroke(KeyEvent.VK_CONTROL, ctrl = true, alt = false, shift = false, meta = false), "a lone modifier is not yet a keystroke")
        assertTrue(keystrokeMatches("ctrl + alt + g", "Ctrl+Alt+G"), "hand-edited spellings are forgiven")
    }
}
