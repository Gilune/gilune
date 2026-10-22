package org.gilune.app

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/// Owner feedback on the tree (2026-10-19): a folder can be made at the
/// vault root, a naming row clicked away from leaves nothing behind, and
/// Reveal in Explorer finds a note whose path has a space in it.
@OptIn(ExperimentalTestApi::class)
class VaultTreeFoldersUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(run: (File, GiluneState, TreeState) -> Unit) {
        val root = Files.createTempDirectory("gilune-folders").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        root.resolve("People").mkdirs()
        root.resolve("People/someone.md").writeText("body")
        val state = GiluneState(root.path, scope)
        val tree = TreeState()
        compose.setContent {
            GiluneTheme(dark = false) {
                VaultTree(state, tree, onOpen = { tree.selectedFile = it }, modifier = Modifier.width(280.dp).height(400.dp))
            }
        }
        try { run(root, state, tree) } finally { compose.setContent {}; scope.cancel(); root.deleteRecursively() }
    }

    private fun nameField() = compose.onNode(hasSetTextAction())

    /// Below the last row: the vault itself.
    private fun blankSpace(button: MouseButton = MouseButton.Primary) =
        compose.onNodeWithTag("vault-tree-list").performMouseInput {
            click(Offset(centerX, bottom - 10f), button)
        }

    private fun dirsOf(root: File) = root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet()

    private fun rightClick(tag: String) {
        compose.onNodeWithTag(tag).performMouseInput { click(center, MouseButton.Secondary) }
        compose.waitForIdle()
    }

    @Test
    fun theRightClickMenuCarriesASyncSwitchOnlyWhileTheVaultSyncsAndTheSwitchWritesTheMark() = fixture { root, state, tree ->
        tree.expanded.add("People")
        compose.waitForIdle()
        // Sync off: the menu has no switch.
        rightClick("vault-entry:People")
        compose.onNodeWithTag("vault-sync:People").assertDoesNotExist()
        blankSpace() // the menu is its own popup root: a click outside closes it
        compose.waitForIdle()

        state.syncStatus.value = localScopeSyncStatus(enabled = true)
        compose.waitForIdle()
        rightClick("vault-entry:People")
        compose.onNodeWithTag("vault-sync:People").assertIsOn()
        // Choosing the folder's switch writes its marker; the menu stays
        // open and the switch shows the new state.
        compose.onNodeWithTag("vault-sync:People").performClick()
        compose.waitForIdle()
        assertTrue(root.resolve("People/$LOCAL_MARKER").isFile)
        compose.onNodeWithTag("vault-sync:People").assertIsOff()
        blankSpace() // the menu is its own popup root: a click outside closes it
        compose.waitForIdle()
        // The folder's row says so; the note inside, kept only by the
        // folder, does not repeat it.
        compose.onNodeWithTag(useUnmergedTree = true, testTag = "vault-local:People").assertExists()
        compose.onNodeWithTag(useUnmergedTree = true, testTag = "vault-local:People/someone.md").assertDoesNotExist()

        // The note inside follows the folder and cannot be switched on its own.
        rightClick("vault-entry:People/someone.md")
        compose.onNodeWithTag("vault-sync:People/someone.md").assertIsOff().assertIsNotEnabled()
        blankSpace()
        compose.waitForIdle()

        // Back on removes the marker, in place.
        rightClick("vault-entry:People")
        compose.onNodeWithTag("vault-sync:People").assertIsOff().performClick()
        compose.waitForIdle()
        assertFalse(root.resolve("People/$LOCAL_MARKER").exists())
        compose.onNodeWithTag("vault-sync:People").assertIsOn()
        blankSpace()
        compose.waitForIdle()

        // A note's own switch marks the note itself, and back.
        rightClick("vault-entry:People/someone.md")
        compose.onNodeWithTag("vault-sync:People/someone.md").assertIsOn().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(noteIsLocal(splitNoteRaw(root.resolve("People/someone.md").readText()).first))
        compose.onNodeWithTag("vault-sync:People/someone.md").assertIsOff()
        blankSpace()
        compose.waitForIdle()
        // A kept note's row says so, exactly as a folder's does.
        compose.onNodeWithTag(useUnmergedTree = true, testTag = "vault-local:People/someone.md").assertExists()
        compose.onNodeWithTag(useUnmergedTree = true, testTag = "vault-local:People").assertDoesNotExist()
        rightClick("vault-entry:People/someone.md")
        compose.onNodeWithTag("vault-sync:People/someone.md").assertIsOff().performClick()
        compose.waitForIdle()
        assertFalse(noteIsLocal(splitNoteRaw(root.resolve("People/someone.md").readText()).first))
        compose.onNodeWithTag("vault-sync:People/someone.md").assertIsOn()
        blankSpace()
        compose.waitForIdle()
        compose.onNodeWithTag(useUnmergedTree = true, testTag = "vault-local:People/someone.md").assertDoesNotExist()
    }

    @Test
    fun clickingAwayFromAnUnnamedFolderCreatesNothing() = fixture { root, _, _ ->
        compose.onNodeWithContentDescription("new folder").performClick()
        nameField().assertIsFocused()
        blankSpace()
        nameField().assertDoesNotExist()
        assertEquals(setOf("People"), dirsOf(root))
    }

    @Test
    fun clickingAnotherRowAlsoAnswersTheNamingRow() = fixture { root, _, _ ->
        compose.onNodeWithContentDescription("new folder").performClick()
        compose.onNodeWithTag("vault-entry:People").performClick()
        nameField().assertDoesNotExist()
        assertEquals(setOf("People"), dirsOf(root))
    }

    @Test
    fun clickingAwayFromANamedFolderKeepsTheName() = fixture { root, _, _ ->
        compose.onNodeWithContentDescription("new folder").performClick()
        nameField().performTextInput("Projects")
        blankSpace()
        nameField().assertDoesNotExist()
        assertTrue(root.resolve("Projects").isDirectory)
    }

    @Test
    fun escapeStillCancelsANamedFolder() = fixture { root, _, _ ->
        compose.onNodeWithContentDescription("new folder").performClick()
        nameField().performTextInput("Projects")
        nameField().performKeyInput { pressKey(Key.Escape) }
        nameField().assertDoesNotExist()
        assertFalse(root.resolve("Projects").exists())
    }

    @Test
    fun clickingBlankSpaceMakesTheRootTheTargetAgain() = fixture { root, _, tree ->
        compose.onNodeWithTag("vault-entry:People").performClick()
        assertEquals("People", tree.selectedDir)
        blankSpace()
        assertEquals("", tree.selectedDir)
        compose.onNodeWithContentDescription("new folder").performClick()
        nameField().performTextInput("Top")
        nameField().performKeyInput { pressKey(Key.Enter) }
        assertTrue(root.resolve("Top").isDirectory)
        assertFalse(root.resolve("People/Top").exists())
    }

    @Test
    fun theBlankSpaceMenuCreatesAtTheRootWhateverFolderIsChosen() = fixture { root, _, tree ->
        compose.onNodeWithTag("vault-entry:People").performClick()
        assertEquals("People", tree.selectedDir)
        blankSpace(MouseButton.Secondary)
        compose.onNodeWithText("New folder").performClick()
        nameField().performTextInput("Top")
        nameField().performKeyInput { pressKey(Key.Enter) }
        assertTrue(root.resolve("Top").isDirectory)
        compose.onNodeWithTag("vault-entry:Top").assertIsDisplayed()
    }

    @Test
    fun theVaultHeaderMenuCreatesAtTheRoot() = fixture { root, _, _ ->
        compose.onNodeWithTag("vault-entry:People").performClick()
        compose.onNodeWithTag("vault-root-drop").performMouseInput { click(button = MouseButton.Secondary) }
        compose.onNodeWithText("New folder").performClick()
        nameField().performTextInput("Top")
        nameField().performKeyInput { pressKey(Key.Enter) }
        assertTrue(root.resolve("Top").isDirectory)
    }

    @Test
    fun aFolderRowStillOffersItsOwnMenuOnly() = fixture { root, _, _ ->
        compose.onNodeWithTag("vault-entry:People").performMouseInput { click(button = MouseButton.Secondary) }
        compose.onNodeWithText("New folder").performClick()
        nameField().performTextInput("Inner")
        nameField().performKeyInput { pressKey(Key.Enter) }
        assertTrue(root.resolve("People/Inner").isDirectory)
        assertFalse(root.resolve("Inner").exists())
    }

    @Test
    fun revealHandsExplorerThePathAsAnArgumentOfItsOwn() {
        val note = File("C:/Vault with space/My note.md")
        assertEquals(listOf("explorer.exe", "/select,", note.absolutePath), revealCommand(note))
    }

    // The starting folders moved to the plugins that use them (owner,
    // 2026-10-19): VaultFoldersTest.
}
