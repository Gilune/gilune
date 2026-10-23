package org.gilune.app

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.gilune.plugins.people.*
import org.junit.Rule
import uniffi.gilune_ffi.CreatePageRequest
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FolderPageCreationUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(run: (File, GiluneState, Gilune) -> Unit) {
        val root = Files.createTempDirectory("gilune-folder-person").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Gilune.open(root.path).use { kernel ->
            val registry = PluginExtensions(listOf(peopleExtensions()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            registry.initializePages(kernel); kernel.project(); kernel.advanceIndex()
            val state = GiluneState(root.path, scope).apply {
                gilune.value = kernel; plugins.addAll(kernel.plugins())
            }
            try { run(root, state, kernel) }
            finally { compose.setContent {}; scope.cancel(); state.gilune.value = null }
        }
        root.deleteRecursively()
    }

    private fun show(state: GiluneState, expanded: List<String> = emptyList(), opened: (String) -> Unit = {}) {
        val tree = TreeState().apply { this.expanded.addAll(expanded) }
        compose.setContent { GiluneTheme(dark = false) {
            VaultTree(state, tree, opened, modifier = Modifier.width(320.dp).fillMaxHeight())
        } }
    }

    private fun menu(path: String) {
        compose.onNodeWithTag("vault-entry:$path").performMouseInput { click(button = MouseButton.Secondary) }
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun create(name: String, handle: String) {
        awaitText("Create person")
        compose.onNodeWithText("Create person").performClick()
        compose.onNodeWithTag("page-field-name").performTextInput(name)
        compose.onNodeWithTag("page-field-handle").performTextInput(handle)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Create") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create").performClick()
    }

    /// The connections folder offers the same door the people folder does
    /// (owner ask, 2026-10-23): the collection had every part of it but the
    /// label that puts it on the folder's menu.
    @Test fun connectionsFolderCreatesATypedConnection() = fixture { root, state, kernel ->
        var opened: String? = null
        show(state, expanded = listOf("People")) { opened = it }
        menu("People/Connections")
        awaitText("Create connection")
        compose.onNodeWithText("Create connection").performClick()
        compose.onNodeWithTag("page-field-keyword").performTextInput("reviewer")
        compose.onNodeWithTag("page-field-label").performTextInput("Reviewer")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Create") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(10_000) { opened != null }
        assertEquals("People/Connections/reviewer.md", opened)
        val text = kernel.documentSnapshot(opened!!).content.text
        assertTrue(text.contains("gilune-type: $CONNECTION_TYPE"), text)
        assertTrue(text.contains("keyword: \"reviewer\"") || text.contains("keyword: reviewer"), text)
        assertTrue(root.resolve(opened!!).isFile)
    }

    @Test fun peopleFolderCreatesAndRevealsTypedPersonAndRejectsDuplicateHandles() = fixture { root, state, kernel ->
        var opened: String? = null
        show(state) { opened = it }
        menu("People")
        create("Alice Smith", "alice.smith")
        compose.waitUntil(10_000) { opened != null }
        assertEquals("People/alice.smith.md", opened)
        val snapshot = kernel.documentSnapshot(opened!!)
        assertTrue(snapshot.content.text.contains("gilune-type: $PERSON_TYPE"))
        assertTrue(snapshot.content.text.contains("name: \"Alice Smith\""))
        assertTrue(snapshot.content.text.contains("# About"))
        compose.onNodeWithTag("vault-entry:People/alice.smith.md").assertIsDisplayed()
        val original = root.resolve(opened!!).readText()
        menu("People")
        create("Another Alice", "ALICE.SMITH")
        awaitText("A page already uses that identifier. Open the existing page or choose another.")
        assertEquals(original, root.resolve(opened!!).readText())
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, root.resolve("People").listFiles()!!.count { it.isFile })
    }

    @Test fun menuFollowsPortableFolderDefaultsAndUsesItsTemplate() = fixture { root, state, kernel ->
        root.resolve("Contacts").mkdir()
        val service = BoundPageCreation(kernel, PEOPLE_PLUGIN)
        val defaults = service.definitions().single { it.typeId == "$PEOPLE_PLUGIN/person-defaults" }
        service.create(CreatePageRequest(defaults.typeId, defaults.version, UUID.randomUUID().toString(),
            "Settings/people-defaults.md", "---\nfolder: Contacts\n---\n# Conversations\n"))
        kernel.project(); state.refresh()
        var opened: String? = null
        show(state) { opened = it }
        menu("Contacts")
        create("Bob", "bob")
        compose.waitUntil(10_000) { opened != null }
        assertEquals("Contacts/bob.md", opened)
        assertTrue(root.resolve(opened!!).readText().contains("# Conversations"))
        menu("People")
        compose.onNodeWithText("New note").assertIsDisplayed()
        compose.onNodeWithText("Create person").assertDoesNotExist()
        compose.onNodeWithTag("vault-root-drop").performMouseInput { click() }

        // Changing the saved folder updates the existing tree, including a
        // root destination, without restarting or reopening the navigation.
        val config = root.resolve("Settings/people-defaults.md")
        config.writeText(config.readText().replace("folder: Contacts", "folder: \"\""))
        kernel.reconcilePath("Settings/people-defaults.md")
        compose.runOnIdle { state.refresh() }
        opened = null
        compose.onNodeWithTag("vault-root-drop").performMouseInput { click(button = MouseButton.Secondary) }
        create("Charlie", "charlie")
        compose.waitUntil(10_000) { opened != null }
        assertEquals("charlie.md", opened)
        assertTrue(root.resolve(opened!!).readText().contains("# Conversations"))
        menu("Contacts")
        compose.onNodeWithText("Create person").assertDoesNotExist()
    }

    @Test fun ordinaryFoldersAndNotesHaveNoPersonActionAndDisablingPeopleRemovesIt() = fixture { root, state, kernel ->
        root.resolve("Other").mkdir()
        root.resolve("People/note.md").writeText("Ordinary note")
        show(state)
        menu("People")
        awaitText("Create person")
        compose.onNodeWithTag("vault-root-drop").performMouseInput { click() }
        menu("Other")
        compose.onNodeWithText("Create person").assertDoesNotExist()
        compose.onNodeWithTag("vault-root-drop").performMouseInput { click() }
        compose.onNodeWithTag("vault-entry:People").performClick()
        menu("People/note.md")
        compose.onNodeWithText("Create person").assertDoesNotExist()
        compose.onNodeWithTag("vault-root-drop").performMouseInput { click() }
        kernel.setPluginEnabled(PEOPLE_PLUGIN, false)
        compose.runOnIdle { state.refresh() }
        menu("People")
        compose.onNodeWithText("Create person").assertDoesNotExist()
        compose.onNodeWithTag("vault-root-drop").performMouseInput { click() }
        kernel.setPluginEnabled(PEOPLE_PLUGIN, true)
        kernel.revokeCapability(PEOPLE_PLUGIN, org.gilune.plugin.sdk.PAGE_CREATE_CAPABILITY)
        compose.runOnIdle { state.refresh() }
        menu("People")
        compose.onNodeWithText("Create person").assertDoesNotExist()
        assertEquals("Ordinary note", root.resolve("People/note.md").readText())
    }

    @Test fun cancellingPreparedPersonFormLeavesTheFolderUnchanged() = fixture { root, state, _ ->
        val before = root.resolve("People").walkTopDown().map { it.relativeTo(root).path }.toList()
        show(state)
        menu("People")
        awaitText("Create person")
        compose.onNodeWithText("Create person").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Create") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("page-field-name").performTextInput("Unfinished person")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("page-field-name").assertDoesNotExist()
        assertEquals(before, root.resolve("People").walkTopDown().map { it.relativeTo(root).path }.toList())
    }
}
