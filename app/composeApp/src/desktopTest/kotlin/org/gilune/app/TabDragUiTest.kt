// Dragging a tab is the half of ADR 0014 stage M+2 that only a pointer
// can answer: the model's moves are proven in TabsTest without a window,
// so what is left to show is that a press, a movement past the slop and a
// release reach them — within a strip, into another pane's strip, and
// onto a pane's outer quarter, where the drop splits it.
//
// Every gesture is injected on the host rather than on a chip, so its
// coordinates are the host's own and a drag may leave the tab it started
// on. Chip geometry is read back from the semantics tree: the label sits
// at the head of its chip and the affordance at its tail, so the pair of
// them bracket the chip without the strip having to say so.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TabDragUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun vault(vararg names: String): File {
        val dir = Files.createTempDirectory("gilune-drag").toFile()
        names.forEach { rel ->
            File(dir, rel).writeText("---\ngilune-id: ${rel.removeSuffix(".md")}\n---\n${rel.removeSuffix(".md")} body\n")
        }
        return dir
    }

    /// The host reads only the vault path, the status line and the change
    /// tick, so no vault has to be opened to render it. Edit/Read is per
    /// tab and seeded from Prefs; the body field a test reads exists only
    /// in Edit.
    private fun show(dir: File, tabs: TabsState) {
        tabs.panes().flatMap { it.tabs }.forEach { it.editor.reading = false }
        val state = GiluneState(dir.absolutePath, CoroutineScope(Dispatchers.Default))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { PaneHost(state, tabs, Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
    }

    private fun host(): Rect = compose.onNodeWithTag("pane-host").fetchSemanticsNode().boundsInRoot

    private fun labels(): List<String> = compose.onAllNodesWithTag("tab-label").fetchSemanticsNodes()
        .map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") }

    private fun labelBounds(index: Int): Rect =
        compose.onAllNodesWithTag("tab-label")[index].fetchSemanticsNode().boundsInRoot

    private fun affordanceBounds(index: Int): Rect =
        compose.onAllNodesWithTag("tab-affordance")[index].fetchSemanticsNode().boundsInRoot

    /// Host-local, which is what the injected gesture speaks.
    private fun local(point: Offset): Offset = point - host().topLeft

    /// Press where the tab is, cross the slop, then walk to the drop and
    /// let go. Only the last position decides the target, but the drag
    /// has to be live by then, so the slop is crossed on its own step.
    private fun drag(from: Offset, via: List<Offset>) {
        compose.onNodeWithTag("pane-host").performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(12f, 0f))
            via.forEach { moveTo(it) }
            release()
        }
        compose.waitForIdle()
    }

    @Test
    fun dragging_a_tab_along_its_own_strip_reorders_it() {
        val dir = vault("alpha.md", "beta.md")
        val tabs = TabsState(dir.absolutePath)
        tabs.open("alpha.md")
        tabs.open("beta.md")
        show(dir, tabs)
        assertEquals(listOf("alpha", "beta"), labels())

        // Past the last chip entirely, so the gap is the end of the strip
        // whatever width the chips took.
        val end = local(Offset(affordanceBounds(1).right + 24f, labelBounds(1).center.y))
        drag(local(labelBounds(0).center), listOf(end))

        assertEquals(listOf("beta.md", "alpha.md"), tabs.activePane.tabs.map { it.relPath })
        assertEquals(listOf("beta", "alpha"), labels())
    }

    @Test
    fun dropping_a_tab_on_the_gap_it_already_fills_changes_nothing() {
        val dir = vault("alpha.md", "beta.md")
        val tabs = TabsState(dir.absolutePath)
        tabs.open("alpha.md")
        val beta = tabs.open("beta.md")
        show(dir, tabs)

        // Past the end of the strip is the last gap, and the last tab is
        // already in it once its own place is accounted for.
        val end = local(Offset(affordanceBounds(1).right + 24f, labelBounds(1).center.y))
        drag(local(labelBounds(1).center), listOf(end))

        assertEquals(listOf("alpha", "beta"), labels())
        assertSame(beta, tabs.activeTab)
    }

    @Test
    fun the_drag_image_follows_the_pointer_and_leaves_on_release() {
        val dir = vault("alpha.md", "beta.md")
        val tabs = TabsState(dir.absolutePath)
        tabs.open("alpha.md")
        tabs.open("beta.md")
        show(dir, tabs)
        compose.onAllNodesWithTag("tab-drag-image").assertCountEquals(0)

        val start = local(labelBounds(0).center)
        compose.onNodeWithTag("pane-host").performMouseInput {
            moveTo(start)
            press()
            moveTo(start + Offset(60f, 0f))
        }
        compose.waitForIdle()

        // The chip stays where it is; what follows the pointer is a
        // label drawn over every pane at the host's root.
        compose.onNodeWithTag("tab-drag-image").assertTextEquals("alpha")
        assertEquals(listOf("alpha", "beta"), labels(), "nothing moves until the release")
        val first = compose.onNodeWithTag("tab-drag-image").fetchSemanticsNode().boundsInRoot

        compose.onNodeWithTag("pane-host").performMouseInput { moveTo(start + Offset(120f, 0f)) }
        compose.waitForIdle()

        val second = compose.onNodeWithTag("tab-drag-image").fetchSemanticsNode().boundsInRoot
        assertTrue(second.left > first.left, "the image follows the pointer")

        compose.onNodeWithTag("pane-host").performMouseInput { release() }
        compose.waitForIdle()

        compose.onAllNodesWithTag("tab-drag-image").assertCountEquals(0)
    }

    @Test
    fun dragging_a_tab_onto_another_panes_strip_moves_it_there() {
        val dir = vault("alpha.md", "beta.md", "gamma.md")
        val tabs = TabsState(dir.absolutePath)
        tabs.open("alpha.md")
        tabs.open("beta.md")
        tabs.open("gamma.md")
        assertTrue(tabs.splitRight(), "gamma moves into a pane of its own")
        show(dir, tabs)
        assertEquals(listOf("alpha", "beta", "gamma"), labels())

        // Gamma's chip is the whole of the right pane's strip, so landing
        // past it is landing at the end of that strip.
        val end = local(Offset(affordanceBounds(2).right + 24f, labelBounds(2).center.y))
        drag(local(labelBounds(0).center), listOf(end))

        assertEquals(
            listOf(listOf("beta.md"), listOf("gamma.md", "alpha.md")),
            tabs.panes().map { pane -> pane.tabs.map { it.relPath } },
        )
        assertEquals(listOf("beta", "gamma", "alpha"), labels())
        assertEquals("alpha.md", tabs.activeTab?.relPath, "the tab arrives in front")
        assertSame(tabs.panes()[1], tabs.activePane, "and its pane is the active one")
        // Two panes, two documents: the one on the right is the tab that
        // just landed there.
        compose.onAllNodesWithTag("note-body")[1].assertTextContains("alpha body", substring = true)
    }

    @Test
    fun dropping_a_tab_on_a_panes_outer_quarter_splits_it() {
        val dir = vault("alpha.md", "beta.md")
        val tabs = TabsState(dir.absolutePath)
        tabs.open("alpha.md")
        tabs.open("beta.md")
        show(dir, tabs)
        assertEquals(1, tabs.panes().size)

        val pane = host()
        // Out of the strip first, then into the outer quarter: a strip
        // under the pointer would have claimed the drop.
        drag(
            local(labelBounds(0).center),
            listOf(
                Offset(pane.width * 0.5f, pane.height * 0.5f),
                Offset(pane.width * 0.9f, pane.height * 0.5f),
            ),
        )

        assertTrue(tabs.root is Split, "the pane split around the drop")
        assertEquals(
            listOf(listOf("beta.md"), listOf("alpha.md")),
            tabs.panes().map { p -> p.tabs.map { it.relPath } },
        )
        assertEquals("alpha.md", tabs.activeTab?.relPath)
        assertEquals(listOf("beta", "alpha"), labels())
    }
}
