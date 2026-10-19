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

    @Test
    fun defaultFoldersAreCreatedBesideWhatIsThere() {
        val root = Files.createTempDirectory("gilune-defaults").toFile()
        try {
            root.resolve("notes").mkdirs()
            root.resolve("People").mkdirs()
            createDefaultFolders(root.path)
            assertEquals(setOf("notes", "People", "Tasks", "Captures"), dirsOf(root), "an existing folder in any casing stands")
        } finally {
            root.deleteRecursively()
        }
    }
}
