// Connection pills are painted (owner, 2026-10-26), proven by the pixels:
// the tag's tint appears in Read view and in the editor off the caret's
// line, and is gone when the caret is on that line. The step past the
// obvious is that last row — the pill must give way, not linger. Colour,
// the same day: a connection page's `colour` is the pill's, and a keyword
// no page defines is grey — each checked as the one colour present, not
// beside the accent.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.gilune.plugin.sdk.PillTone
import org.gilune.plugins.people.CONNECTION_TYPE
import org.gilune.plugins.people.PEOPLE_CONNECTION_STYLE
import org.gilune.plugins.people.peopleConnectionDecoration
import org.gilune.plugins.people.peopleMentionDecoration
import org.junit.Rule
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.Gilune
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectionPillsUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private val note = "- [ ] Ship it assign: @phil.jackson"
    private val people = builtInDecorations() + listOf(peopleConnectionDecoration(), peopleMentionDecoration())
    private var tagColour = Color.Unspecified
    private var accentTag = Color.Unspecified

    /// The tag colour a tone draws in this theme, as the shell's palette says.
    @androidx.compose.runtime.Composable
    private fun tagOf(tone: PillTone): Color {
        val item = pillItem(PEOPLE_CONNECTION_STYLE, "probe")
        return pillPalette(PillToneMap(setOf(PEOPLE_CONNECTION_STYLE), mapOf(item to tone), ready = true))(item).tag
    }

    private fun tagPixels(tag: String): Int {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val wanted = tagColour.toArgb()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].toArgb() == wanted) count++
        return count
    }

    private fun read(pills: List<ReadPill>) {
        compose.setContent {
            GiluneTheme(dark = false) {
                tagColour = tagOf(PillTone.ACCENT)
                Box(Modifier.width(600.dp).testTag("read")) {
                    MarkdownBody(parseMarkdown(note), pills = pills)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun readViewPaintsTheTagBehindTheConnection() {
        read(pillsIn(note, people))
        assertTrue(tagPixels("read") > 20, "the tag's tint is on screen")
    }

    @Test
    fun readViewWithoutPillsPaintsNoTag() {
        read(emptyList())
        assertEquals(0, tagPixels("read"))
    }

    /// A real editor over a vault with the default connections plus `pages`
    /// (file name to source), its pills expected in `tone`. Waits for the
    /// colours to arrive: they are looked up off the UI thread.
    private fun editorOn(body: String, tone: PillTone, pages: Map<String, String> = emptyMap()): EditorState {
        val vault = resources.directory("gilune-pills")
        vault.resolve(".gilune-local").writeText("") // local-only vault: files stay unmanaged (ADR 0007, 2026-10-22)
        vault.resolve("note.md").writeText("---\ngilune-id: abc\n---\n$body")
        val kernel = resources.own(Gilune.open(vault.path))
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.registerIndexers(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.initializePages(kernel)
        for ((name, source) in pages) {
            vault.resolve(name).writeText(source)
            kernel.reconcilePath(name)
        }
        val state = GiluneState(vault.path, resources.scope)
        state.gilune.value = kernel
        state.plugins.addAll(kernel.plugins())
        val editor = EditorState()
        editor.open(vault.path, "note.md")
        editor.reading = false
        compose.setContent {
            GiluneTheme(dark = false) {
                tagColour = tagOf(tone)
                accentTag = tagOf(PillTone.ACCENT)
                Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) }
            }
        }
        compose.waitUntil(10_000) { tagPixels("note-body") > 20 }
        return editor
    }

    @Test
    fun aConnectionPagesColourIsThePillsColour() {
        val reviewer = "---\ngilune-type: $CONNECTION_TYPE\ngilune-id: reviewer-page\nkeyword: reviewer\nlabel: Reviewer\nretired: false\ncolour: green\n---\n"
        editorOn("- [ ] Check it reviewer: @phil.jackson", PillTone.GREEN, mapOf("reviewer.md" to reviewer))
        tagColour = accentTag
        assertEquals(0, tagPixels("note-body"), "green instead of the accent, not as well as it")
    }

    @Test
    fun aKeywordNoPageDefinesIsGrey() {
        editorOn("- [ ] Ship it asign: @phil.jackson", PillTone.GREY)
        tagColour = accentTag
        assertEquals(0, tagPixels("note-body"), "the typo is grey once the colours arrive")
    }

    @Test
    fun theEditorDrawsThePillUntilTheCaretIsOnItsLine() {
        val editor = editorOn("$note\nsecond line", PillTone.ACCENT)
        assertTrue(tagPixels("note-body") > 20, "no caret: every line decorates, the pill with it")

        // The caret on the second line: the first still shows its pill.
        compose.onNodeWithTag("note-body").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { editor.text = TextFieldValue(editor.text.text, TextRange(editor.text.text.length)) }
        compose.waitForIdle()
        assertTrue(tagPixels("note-body") > 20, "another line's caret leaves the pill alone")

        // The caret on the connection's own line: plain text, no pill.
        compose.runOnIdle { editor.text = TextFieldValue(editor.text.text, TextRange(note.length)) }
        compose.waitForIdle()
        assertEquals(0, tagPixels("note-body"), "the line being written shows its words")
        assertEquals("$note\nsecond line", editor.text.text, "drawing changed no byte")
    }
}
