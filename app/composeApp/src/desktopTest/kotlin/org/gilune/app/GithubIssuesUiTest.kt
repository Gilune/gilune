package org.gilune.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.gilune.plugins.people.*
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GithubIssuesUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(run: (File, GiluneState) -> Unit) {
        val root = Files.createTempDirectory("gilune-issues").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        root.resolve("Folder").mkdirs()
        root.resolve("Folder/note.md").writeText("body")
        val state = GiluneState(root.path, scope)
        try { run(root, state) } finally { compose.setContent {}; scope.cancel(); root.deleteRecursively() }
    }

    private fun tree(state: GiluneState): TreeState {
        val tree = TreeState().apply { expanded.add("Folder") }
        compose.setContent {
            GiluneTheme(dark = false) {
                VaultTree(state, tree, onOpen = { tree.selectedFile = it }, modifier = Modifier.width(280.dp).fillMaxHeight())
            }
        }
        return tree
    }

    @Test
    fun folderContextMenuCreatesANoteInThatFolder() = fixture { root, state ->
        tree(state)
        compose.onNodeWithTag("vault-entry:Folder").performMouseInput { click(button = MouseButton.Secondary) }
        compose.onNodeWithText("New note").performClick()
        assertTrue(root.resolve("Folder/Untitled.md").isFile)
        compose.onNodeWithTag("vault-entry:Folder/Untitled.md").assertIsDisplayed()
    }

    @Test
    fun folderContextMenuCreatesAnImmediatelyVisibleSubfolder() = fixture { root, state ->
        tree(state)
        compose.onNodeWithTag("vault-entry:Folder").performMouseInput { click(button = MouseButton.Secondary) }
        compose.onNodeWithText("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Nested")
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        assertTrue(root.resolve("Folder/Nested").isDirectory)
        compose.onNodeWithTag("vault-entry:Folder/Nested").assertIsDisplayed()
    }

    @Test
    fun deleteOnAFocusedFileOrFolderOpensConfirmationAndCancelPreservesIt() = fixture { root, state ->
        tree(state)
        for (path in listOf("Folder/note.md", "Folder")) {
            val row = compose.onNodeWithTag("vault-entry:$path")
            row.performMouseInput { click() }
            row.performKeyInput { pressKey(Key.Delete) }
            compose.onNodeWithText("Delete ${path.substringAfterLast('/')}?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(root.resolve(path).exists())
        }
    }

    @Test
    fun confirmingDeleteRemovesAnEmptyFolderFromDiskAndTheTree() = fixture { root, state ->
        root.resolve("Empty").mkdir()
        uniffi.gilune_ffi.Gilune.open(root.path).use { kernel ->
            state.gilune.value = kernel
            tree(state)
            val row = compose.onNodeWithTag("vault-entry:Empty")
            row.performMouseInput { click() }
            row.performKeyInput { pressKey(Key.Delete) }
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(10_000) { state.message.value == "deleted Empty" }
            assertFalse(root.resolve("Empty").exists())
            row.assertDoesNotExist()
            compose.setContent {}
            state.gilune.value = null
        }
    }

    @Test
    fun expandingAFolderDoesNotSelectItAlongsideTheOpenFile() = fixture { _, state ->
        tree(state)
        compose.onNodeWithTag("vault-entry:Folder/note.md").performClick().assertIsSelected()
        compose.onNodeWithTag("vault-entry:Folder").performClick().assertIsNotSelected()
        compose.onNodeWithTag("vault-entry:Folder").performClick().assertIsNotSelected()
        compose.onNodeWithTag("vault-entry:Folder/note.md").assertIsSelected()
    }

    @Test
    fun peopleNavigationCanBeHiddenAndRestoredInSettingsWithoutDisablingThePlugin() = fixture { _, state ->
        val people = installedPlugin(PEOPLE_PLUGIN, contributions = peopleExtensions().declaration.contributions)
        state.plugins.add(people)
        var saved = emptySet<String>()
        val navigation = NavigationVisibility { saved = it }
        val tree = TreeState().apply { railPanel = RailPanel(PEOPLE_PANEL) }
        compose.setContent {
            GiluneTheme(dark = false) {
                Column {
                    RailPanels(state, tree, FocusRequester(), {}, {}, Modifier.height(240.dp), navigation = navigation)
                    PluginNavigationSettings(people, navigation)
                }
            }
        }
        assertEquals(Icons.Outlined.People, navigationIcon(PEOPLE_PANEL))
        compose.onNodeWithContentDescription("People connections").assertDoesNotExist()
        compose.onNodeWithContentDescription("People").performMouseInput { click(button = MouseButton.Secondary) }
        compose.onNodeWithText("Hide People").performClick()
        compose.onNodeWithContentDescription("People").assertDoesNotExist()
        compose.onNodeWithTag("vault-tree").assertIsDisplayed()
        assertTrue(PEOPLE_PANEL in saved)
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithContentDescription("People").assertIsDisplayed()
        assertEquals("enabled", state.plugins.single().state)
        assertTrue(NavigationVisibility(saved).visible(navigationPanels(state.plugins)).any { it.key == PEOPLE_PANEL })
    }

    @Test
    fun aboutShowsTheAppVersionAsWellAsTheKernelVersion() = fixture { root, state ->
        compose.setContent {
            GiluneTheme(dark = false) {
                SettingsPane(state, root.path, {}, ThemeChoice.Light, {}, {}, initialSection = "About")
            }
        }
        compose.onNodeWithText("app $APP_VERSION", substring = true).assertIsDisplayed()
        compose.onNodeWithText("kernel ", substring = true).assertIsDisplayed()
    }
}
