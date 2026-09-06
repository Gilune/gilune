package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.gilune.plugins.people.*
import uniffi.gilune_ffi.Gilune

@OptIn(ExperimentalTestApi::class)
class PageCollectionPanelTest {
    @get:Rule val compose = createComposeRule()
    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun state(): GiluneState {
        val root = Files.createTempDirectory("gilune-people-ui").toFile()
        val kernel = Gilune.open(root.absolutePath)
        val registry = PluginExtensions(listOf(peopleExtensions()))
        registry.registerPlugins(kernel); registry.registerIndexers(kernel); kernel.advanceIndex()
        return GiluneState(root.absolutePath, CoroutineScope(Dispatchers.Default)).apply {
            gilune.value = kernel; plugins.addAll(kernel.plugins())
        }
    }
    private fun show(state: GiluneState, panel: String = PEOPLE_PANEL, opened: (String) -> Unit = {}) {
        val binding = BUNDLED_PLUGIN_EXTENSIONS.pageCollections(state.plugins.toList()).single { it.collection.id == panel }
        compose.setContent { GiluneTheme(dark = false) {
            Box(Modifier.requiredSize(360.dp, 640.dp)) { PageCollectionPanel(binding, state, opened, Modifier.fillMaxSize()) }
        } }
    }

    @Test fun genericFormCreatesPersonAndOpensItsMarkdown() {
        val state = state()
        var opened: String? = null
        show(state, opened = { opened = it })
        awaitText("No pages yet.")
        compose.onNodeWithText("New person").performClick()
        compose.onNodeWithTag("page-field-name").performTextInput("Alice Smith")
        compose.onNodeWithTag("page-field-handle").performTextInput("alice.smith")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(10_000) { opened != null }
        assertEquals("People/alice.smith.md", opened)
        val snapshot = state.gilune.value!!.documentSnapshot(opened!!)
        assertTrue(snapshot.content.text.contains("handle: \"alice.smith\""))
        awaitText("Alice Smith")
        compose.onNodeWithText("@alice.smith").assertIsDisplayed()
        compose.onNodeWithText("New person").performClick()
        compose.onNodeWithTag("page-field-name").performTextInput("Another Alice")
        compose.onNodeWithTag("page-field-handle").performTextInput("ALICE.SMITH")
        compose.onNodeWithText("Create").performClick()
        awaitText("A page already uses that identifier. Open the existing page or choose another.")
        assertEquals(1, state.gilune.value!!.search(compileSearch("type:person", types = searchTypesFor(state.plugins.toList()))!!.request, null).hits.size)
    }

    @Test fun initializedConnectionsAreVisibleAndUsersCanAddTheirOwn() {
        val state = state()
        var opened: String? = null
        show(state, CONNECTIONS_PANEL) { opened = it }
        awaitText("Owner")
        compose.onNodeWithText("Assign").assertIsDisplayed()
        compose.onNodeWithText("New connection").performClick()
        compose.onNodeWithTag("page-field-keyword").performTextInput("reviewer")
        compose.onNodeWithTag("page-field-label").performTextInput("Reviewer")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(10_000) { opened != null }
        assertEquals("People/Connections/reviewer.md", opened)
        val text = state.gilune.value!!.documentSnapshot(opened!!).content.text
        assertTrue(text.contains("label: \"Reviewer\""))
        assertTrue(text.contains("retired: false"))
        awaitText("Reviewer")
    }

    @Test fun directoryLookupFindsByNameOrHandleBeyondTheFirstPageAndOpensTheSource() {
        val state = state()
        val kernel = state.gilune.value!!
        repeat(35) { n ->
            File(state.vaultDir, "person-$n.md").writeText("---\ngilune-id: imported-$n\ngilune-type: $PERSON_TYPE\nname: Person $n\nhandle: handle.$n\n---\n")
        }
        kernel.reconcile()
        var opened: String? = null
        show(state, opened = { opened = it })
        compose.onNodeWithTag("collection-lookup").performTextInput("handle.34")
        awaitText("1 of 1")
        compose.onNodeWithTag("lookup-result-person-34.md").performClick()
        assertEquals("person-34.md", opened)
        compose.onNodeWithTag("collection-lookup").performTextReplacement("Person")
        awaitText("30 of 35")
        compose.onNodeWithTag("lookup-results").performScrollToIndex(30)
        compose.onNodeWithText("Load more").performClick()
        awaitText("35 of 35")
        compose.onNodeWithTag("collection-lookup").performTextReplacement("nobody")
        awaitText("No matches.")
        compose.onNodeWithText("35 of 35").assertDoesNotExist()
        compose.onNodeWithText("New person").assertIsDisplayed()
    }

    @Test fun portableDefaultsAreCreatedOpenedAndUsedByTheNextForm() {
        val state = state()
        var opened: String? = null
        show(state, opened = { opened = it })
        awaitText("No pages yet.")
        compose.onNodeWithText("Page defaults").performClick()
        awaitText("Save defaults")
        compose.onNodeWithTag("defaults-folder").performTextReplacement("Contacts")
        compose.onNodeWithTag("defaults-template").performTextReplacement("# Conversation notes\n\n")
        compose.onNodeWithText("Save defaults").performClick()
        compose.waitUntil(10_000) { opened != null }
        val defaultsPath = opened!!
        assertTrue(defaultsPath.startsWith("Settings/Page defaults/"))
        assertTrue(state.gilune.value!!.documentSnapshot(defaultsPath).content.text.contains("folder: \"Contacts\""))
        opened = null
        compose.onNodeWithText("New person").performClick()
        awaitText("Contacts")
        compose.onNodeWithTag("page-field-name").performTextInput("Bob")
        compose.onNodeWithTag("page-field-handle").performTextInput("bob")
        val defaultsFile = File(state.vaultDir, defaultsPath)
        defaultsFile.writeText(defaultsFile.readText().replace("Contacts", "New contacts").replace("# Conversation notes", "# Reviewed template"))
        compose.onNodeWithText("Create").performClick()
        awaitText("Page defaults changed. Reload and review them before creating.")
        compose.onNodeWithText("Reload defaults").performScrollTo().performClick()
        awaitText("New contacts")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(10_000) { opened != null }
        assertEquals("New contacts/bob.md", opened)
        assertTrue(state.gilune.value!!.documentSnapshot(opened!!).content.text.contains("# Reviewed template"))
        opened = null
        compose.onNodeWithText("Page defaults").performClick()
        compose.waitUntil(10_000) { opened != null }
        assertEquals(defaultsPath, opened)
    }
}
