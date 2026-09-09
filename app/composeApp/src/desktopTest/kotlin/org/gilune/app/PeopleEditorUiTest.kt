package org.gilune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import org.gilune.plugins.people.*
import org.junit.Rule
import org.junit.rules.RuleChain
import java.nio.file.Files
import kotlin.test.*
import uniffi.gilune_ffi.*

@OptIn(ExperimentalTestApi::class)
class PeopleEditorUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private fun fixture(run: (GiluneState, Gilune) -> Unit) {
        val root = resources.directory("gilune-people-ui")
        val scope = resources.scope
        resources.own(Gilune.open(root.path)).let { kernel ->
            BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
            BUNDLED_PLUGIN_EXTENSIONS.registerIndexers(kernel)
            BUNDLED_PLUGIN_EXTENSIONS.initializePages(kernel)
            root.resolve("alice.md").writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: alice\nname: Alice Smith\nhandle: alice.smith\n---\n")
            kernel.reconcilePath("alice.md")
            val state = GiluneState(root.path, scope)
            state.gilune.value = kernel
            state.plugins.addAll(kernel.plugins())
            run(state, kernel)
        }
    }

    private fun waitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun theRealEditorKeepsFrontmatterContextAndTranslatesBodyInsertion() = fixture { state, _ ->
        val header = "---\ncustom: kept\n---\n"
        java.io.File(state.vaultDir, "note.md").writeText(header + "---\n@ali")
        val editor = EditorState()
        editor.open(state.vaultDir, "note.md")
        editor.reading = false
        editor.text = TextFieldValue("---\n", TextRange(4))
        compose.setContent { GiluneTheme(dark = false) { Box(Modifier.fillMaxSize()) {
            NoteEditor(state, editor, Modifier.fillMaxSize())
        } } }
        compose.onNodeWithTag("note-body").performTextInput("@ali")
        compose.chooseFirstEditorOption("Alice Smith")
        compose.waitUntil(10_000) { editor.text.text == "---\n@alice.smith " }
        assertEquals(header + "---\n@alice.smith ", editor.document())
    }

    @Test fun selectingAConnectionAndPersonInsertsOneCompleteMarkdownToken() = fixture { state, _ ->
        var text by mutableStateOf(TextFieldValue("Publish /ow", TextRange(11)))
        compose.setContent {
            val ui = rememberEditorCompletions(state, text.text, text.selection.end, true) { start, end, value ->
                text = TextFieldValue(text.text.replaceRange(start, end, value), TextRange(start + value.length))
            }
            Column { BasicTextField(text, { text = it }, Modifier.testTag("body")); EditorCompletionMenu(ui, state) }
        }
        compose.chooseFirstEditorOption("Owner")
        compose.waitForFirstEditorOption("Alice Smith")
        assertEquals("Publish /ow", text.text)
        compose.chooseFirstEditorOption("Alice Smith")
        compose.waitUntil(10_000) { text.text == "Publish owner: @alice.smith " }
    }

    @Test fun creatingFromLookupReturnsTheNewHandleToTheOriginalEditor() = fixture { state, _ ->
        var text by mutableStateOf(TextFieldValue("Hello @alicia", TextRange(13)))
        compose.setContent {
            val ui = rememberEditorCompletions(state, text.text, text.selection.end, true) { start, end, value ->
                text = TextFieldValue(text.text.replaceRange(start, end, value), TextRange(start + value.length))
            }
            Column { BasicTextField(text, { text = it }, Modifier.testTag("body")); EditorCompletionMenu(ui, state) }
        }
        compose.chooseFirstEditorOption("Create person…")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Create") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(10_000) { text.text == "Hello @alicia " }
        assertTrue(java.io.File(state.vaultDir, "People/alicia.md").exists())
    }

    @Test fun linksNavigateResolvedReferencesAndExposeUnresolvedOnes() = fixture { state, _ ->
        var opened: String? = null
        compose.setContent { DocumentLinksButton(state, "Hello @alice.smith and @missing") { opened = it } }
        compose.onNodeWithText("Links").performClick()
        waitText("@missing")
        compose.onNodeWithText("@missing").performClick()
        waitText("Unresolved reference. The text remains searchable.")
        compose.onNodeWithText("Back to links").performClick()
        compose.onNodeWithText("@alice.smith").performClick()
        waitText("alice.md")
        compose.onNodeWithText("alice.md").performClick()
        assertEquals("alice.md", opened)
    }

    @Test fun renamePreviewApplyAndUndoUseTheSharedDialog() = fixture { state, kernel ->
        val root = java.io.File(state.vaultDir)
        root.resolve("note.md").writeText("owner: @alice.smith")
        val editor = EditorState().also { it.open(state.vaultDir, "alice.md"); it.reading = false }
        compose.setContent { GiluneTheme(dark = false) { Box(Modifier.fillMaxSize()) { NoteEditor(state, editor, Modifier.fillMaxSize()) } } }
        compose.onNodeWithText("Insert…").assertDoesNotExist()
        compose.onNodeWithText("Refactor…").assertDoesNotExist()
        compose.onNodeWithContentDescription("note actions").performClick()
        compose.onNodeWithText("Rename references…").performClick()
        waitText("Rename handle")
        compose.onNodeWithText("Rename handle").performClick()
        compose.onNodeWithText("New name").performTextInput("alicia")
        compose.onNodeWithText("Prepare preview").performClick()
        waitText("Apply reviewed changes / resume")
        assertEquals("owner: @alice.smith", root.resolve("note.md").readText())
        compose.onNodeWithText("Apply reviewed changes / resume").performClick()
        waitText("Preview undo")
        assertEquals("owner: @alicia", root.resolve("note.md").readText())
        compose.onNodeWithText("Preview undo").performClick()
        waitText("Apply reviewed changes / resume")
        compose.onNodeWithText("Apply reviewed changes / resume").performClick()
        compose.waitUntil(10_000) { root.resolve("note.md").readText() == "owner: @alice.smith" }
        assertTrue(kernel.refactoringPlans().size >= 2)
    }
}
