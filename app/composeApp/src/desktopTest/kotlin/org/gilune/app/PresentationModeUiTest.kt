// Presentation mode (owner, 2026-10-19): no caret, and a click acts
// instead of starting an edit. Each test is one thing a click must do, on
// the real renderer: tick (and stamp, as the editor does), open a
// person's page from an @mention the People plugin recognised, open a
// [[wiki link]] and a Markdown link — and Esc gives the caret back where
// it was, which is what makes presenting safe to step in and out of.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.gilune.plugins.people.PERSON_TYPE
import org.junit.Rule
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PresentationModeUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private lateinit var root: File
    private lateinit var state: GiluneState
    private val opened = mutableListOf<String>()

    private fun vault(): EditorState {
        root = resources.directory("gilune-present")
        val kernel = resources.own(Gilune.open(root.path))
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.registerIndexers(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.initializePages(kernel)
        root.resolve("alice.md").writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: alice\nname: Alice Smith\nhandle: alice.smith\n---\n")
        kernel.reconcilePath("alice.md")
        root.resolve("Projects").mkdirs()
        root.resolve("Projects/Plan.md").writeText("the plan\n")
        state = GiluneState(root.path, resources.scope)
        state.gilune.value = kernel
        state.plugins.addAll(kernel.plugins())
        return EditorState()
    }

    private fun presentOnly(body: String) {
        val editor = vault()
        root.resolve("note.md").writeText("---\ngilune-id: note\n---\n$body")
        editor.open(root.path, "note.md")
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.size(width = 700.dp, height = 400.dp)) {
                    PresentedNote(state, editor, builtInDecorations(), openNote = { opened += it }, write = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun fullEditor(body: String, caret: Int = 0): EditorState {
        val editor = vault()
        root.resolve("note.md").writeText("---\ngilune-id: note\n---\n$body")
        editor.open(root.path, "note.md")
        editor.text = TextFieldValue(editor.text.text, TextRange(caret))
        editor.reading = true
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.size(width = 700.dp, height = 400.dp)) {
                    NoteEditor(state, editor, Modifier.size(width = 700.dp, height = 400.dp))
                }
            }
        }
        compose.waitForIdle()
        return editor
    }

    /// Clicks the middle of `words` where the renderer drew them, read off
    /// the text's own layout rather than guessed.
    private fun clickWords(words: String) {
        val node: SemanticsNodeInteraction = compose.onNode(hasText(words, substring = true))
        val text = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val at = text.indexOf(words) + words.length / 2
        val box: Rect = layouts.single().getBoundingBox(at)
        node.performMouseInput { click(box.center) }
        compose.waitForIdle()
    }

    @Test
    fun clickingAMentionOpensThePersonsPage() {
        presentOnly("talk to @alice.smith today")
        // The People plugin's recognition runs off the main thread: wait
        // until the mention is drawn as a link, then click it once — a
        // click before that is plain text, and repeated clicks would leave
        // lookups running when the test's vault is taken down.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("@alice.smith", substring = true)).fetchSemanticsNodes().any { node ->
                val texts: List<androidx.compose.ui.text.AnnotatedString> =
                    runCatching { node.config[androidx.compose.ui.semantics.SemanticsProperties.Text] }.getOrDefault(emptyList())
                texts.any { text -> text.getLinkAnnotations(0, text.text.length).isNotEmpty() }
            }
        }
        clickWords("@alice.smith")
        compose.waitUntil(10_000) { opened.isNotEmpty() }
        assertEquals(listOf("alice.md"), opened)
    }

    @Test
    fun clickingAWikiLinkOpensTheNoteOfThatName() {
        presentOnly("see [[plan]] next")
        clickWords("plan")
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf("Projects/Plan.md"), opened)
    }

    @Test
    fun clickingAMarkdownLinkToAVaultNoteOpensIt() {
        presentOnly("read [the plan](Projects/Plan.md) first")
        clickWords("the plan")
        assertEquals(listOf("Projects/Plan.md"), opened)
    }

    @Test
    fun clickingATaskTicksItOnDiskAndStampsItsDate() {
        fullEditor("- [ ] milk\n")
        compose.onNodeWithContentDescription("task not done").performClick()
        compose.waitUntil(5_000) { root.resolve("note.md").readText().contains("- [x] milk") }
        // The editor's own chain: with the tasks plugin on, ticking stamps.
        assertTrue(root.resolve("note.md").readText().contains("done:${LocalDate.now()}"))
    }

    @Test
    fun presentingHasNoCaretAndEscReturnsToEditingWhereTheCaretWas() {
        val editor = fullEditor("first line\nsecond line\n", caret = 14)
        assertEquals(0, compose.onAllNodesWithTag("note-body").fetchSemanticsNodes().size, "no field to type into")

        compose.onNodeWithTag("presented-note").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()

        assertFalse(editor.reading)
        assertEquals(TextRange(14), editor.text.selection, "the caret is where it was left")
        compose.onNodeWithTag("note-body").assertExists()
    }
}
