package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class VaultTreeDragUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(prepare: (File) -> Unit = {}, run: (File, TreeState, TabsState, GiluneState) -> Unit) {
        val root = Files.createTempDirectory("gilune-tree-drag").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        root.resolve("From/Nested").mkdirs()
        root.resolve("To").mkdir()
        root.resolve("From/Nested/note.md").writeText("---\ngilune-id: stable\n---\nbody")
        root.resolve("From/Nested/note.wav").writeText("audio")
        prepare(root)
        val state = GiluneState(root.path, scope)
        val tree = TreeState().apply { expanded.addAll(listOf("From", "From/Nested")) }
        val tabs = TabsState(root.path)
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    VaultTree(state, tree, onOpen = { tabs.open(it) },
                        onRenamed = tabs::noteRenamed, modifier = Modifier.width(280.dp).fillMaxHeight())
                }
            }
        }
        try { run(root, tree, tabs, state) } finally {
            compose.setContent {}
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun centre(tag: String): Offset = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

    private fun drag(source: String, destination: String) {
        val from = centre("vault-entry:$source")
        val to = centre(destination)
        compose.onRoot().performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(30f, 0f))
            moveTo(to)
            release()
        }
        compose.waitForIdle()
    }

    @Test
    fun draggingTheNameMovesANoteAndAttachmentsIntoACollapsedFolder() = fixture { root, tree, tabs, _ ->
        val tab = tabs.open("From/Nested/note.md")
        tab.editor.text = TextFieldValue("unsaved work")
        tab.editor.dirty = true
        tree.selectedFile = tab.relPath
        drag(tab.relPath, "vault-entry:To")
        assertFalse(root.resolve("From/Nested/note.md").exists())
        assertEquals("audio", root.resolve("To/note.wav").readText())
        assertEquals("To/note.md", tab.relPath)
        assertEquals("To/note.md", tree.selectedFile)
        assertTrue("To" in tree.expanded)
        assertTrue(flushEditor(root.path, tab.editor))
        assertTrue(root.resolve("To/note.md").readText().endsWith("unsaved work"))
        compose.onNodeWithTag("vault-entry:To/note.md").assertExists()
    }

    @Test
    fun draggingAFolderToVaultUpdatesItsOpenDescendantsAndExpansion() = fixture { root, tree, tabs, _ ->
        val tab = tabs.open("From/Nested/note.md")
        tree.selectedDir = "From/Nested"
        drag("From/Nested", "vault-root-drop")
        assertTrue(root.resolve("Nested/note.md").exists())
        assertEquals("Nested/note.md", tab.relPath)
        assertEquals("Nested", tree.selectedDir)
        assertTrue("Nested" in tree.expanded)
        assertFalse("From/Nested" in tree.expanded)
    }

    @Test
    fun droppingOnADescendantOrOutsideTheTreeLeavesTheFolderInPlace() = fixture { root, _, _, _ ->
        drag("From", "vault-entry:From/Nested")
        assertTrue(root.resolve("From/Nested/note.md").exists())
        val from = centre("vault-entry:From")
        compose.onRoot().performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(350f, 0f))
            release()
        }
        assertTrue(root.resolve("From/Nested/note.md").exists())
        compose.onNodeWithTag("vault-drag-status").assertDoesNotExist()
    }

    @Test
    fun aNormalClickStillOpensANoteAndTogglesAFolder() = fixture { _, tree, tabs, _ ->
        compose.onNodeWithTag("vault-entry:From/Nested/note.md").performMouseInput { click() }
        assertEquals("From/Nested/note.md", tabs.activePane.active?.relPath)
        compose.onNodeWithTag("vault-entry:From").performMouseInput { click() }
        assertFalse("From" in tree.expanded)
    }

    @Test
    fun aCollisionShowsTheReasonAndLeavesBothNotesIntact() = fixture { root, _, _, state ->
        root.resolve("To/note.md").writeText("existing")
        drag("From/Nested/note.md", "vault-entry:To")
        assertTrue(root.resolve("From/Nested/note.md").exists())
        assertEquals("existing", root.resolve("To/note.md").readText())
        assertTrue(state.message.value.contains("already exists"))
    }

    @Test
    fun hoveringOpensACollapsedFolderSoTheNoteCanBeDroppedIntoItsChild() = fixture(
        prepare = { it.resolve("To/Child").mkdir() },
    ) { root, tree, _, _ ->
        val from = centre("vault-entry:From/Nested/note.md")
        val to = centre("vault-entry:To")
        compose.onRoot().performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(30f, 0f))
            moveTo(to)
        }
        compose.waitUntil(5_000) { "To" in tree.expanded }
        val child = centre("vault-entry:To/Child")
        compose.onRoot().performMouseInput { moveTo(child); release() }
        compose.waitForIdle()
        assertTrue(root.resolve("To/Child/note.md").exists())
        assertEquals("audio", root.resolve("To/Child/note.wav").readText())
    }

    @Test
    fun edgeScrollingKeepsTheDragAliveAfterItsSourceLeavesTheVisibleList() = fixture(
        prepare = { root -> repeat(70) { root.resolve("Folder%02d".format(it)).mkdir() } },
    ) { root, _, _, _ ->
        val from = centre("vault-entry:Folder00")
        val viewport = compose.onNodeWithTag("vault-tree-list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(30f, 0f))
            moveTo(Offset(viewport.center.x, viewport.bottom - 8f))
        }
        var target: Offset? = null
        for (frame in 0 until 400) {
            compose.mainClock.advanceTimeByFrame()
            val bounds = compose.onAllNodesWithTag("vault-entry:Folder40").fetchSemanticsNodes()
                .firstOrNull()?.boundsInRoot
            if (bounds != null && bounds.height > 0 && bounds.center.y < viewport.bottom - 36f) {
                target = bounds.center
                break
            }
        }
        compose.onRoot().performMouseInput { moveTo(target ?: viewport.center); release() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertNotNull(target, "Edge scrolling should reveal the destination")
        assertTrue(root.resolve("Folder40/Folder00").isDirectory)
        assertFalse(root.resolve("Folder00").exists())
    }
}
