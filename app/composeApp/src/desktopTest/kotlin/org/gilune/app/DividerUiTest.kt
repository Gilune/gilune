// Dragging the divider between two panes is the half of the ADR 0014
// amendment that only a pointer can answer: the clamp and the arithmetic
// are proven in TabsTest without a window, so what is left to show is
// that a press beside the hairline, a movement and a release reach them,
// and that a divider held past the end of its travel stops there.
//
// Every gesture is injected on the host rather than on the divider, so
// its coordinates are the host's own; the press lands a few pixels off
// the line itself, which only works because the grab band is wider than
// the 1.dp the divider lays out.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DividerUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun vault(vararg names: String): File {
        val dir = Files.createTempDirectory("gilune-divider").toFile()
        names.forEach { rel ->
            File(dir, rel).writeText("---\ngilune-id: ${rel.removeSuffix(".md")}\n---\nbody\n")
        }
        return dir
    }

    /// Two panes side by side, half the host each, with the host on
    /// screen and idle.
    private fun twoPanes(): Split {
        val dir = vault("alpha.md", "beta.md")
        val tabs = TabsState(dir.absolutePath)
        tabs.open("alpha.md")
        tabs.open("beta.md")
        assertTrue(tabs.splitRight(), "beta moves into a pane of its own")
        tabs.panes().flatMap { it.tabs }.forEach { it.editor.reading = false }
        val state = GiluneState(dir.absolutePath, CoroutineScope(Dispatchers.Default))
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { PaneHost(state, tabs, Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        return assertNotNull(tabs.root as? Split)
    }

    private fun host(): Rect = compose.onNodeWithTag("pane-host").fetchSemanticsNode().boundsInRoot

    private fun handle(): Rect =
        compose.onNodeWithTag("split-divider").fetchSemanticsNode().boundsInRoot

    /// Host-local, which is what the injected gesture speaks.
    private fun local(point: Offset): Offset = point - host().topLeft

    /// Press beside the hairline, cross the slop, walk to where the
    /// divider is wanted and let go. The resize is live, so the halfway
    /// step is applied as surely as the last one.
    private fun dragDividerTo(x: Float) {
        val from = local(handle().center) + Offset(3f, 0f)
        val to = Offset(x, from.y)
        compose.onNodeWithTag("pane-host").performMouseInput {
            moveTo(from)
            press()
            moveTo(Offset((from.x + to.x) / 2f, from.y))
            moveTo(to)
            release()
        }
        compose.waitForIdle()
    }

    private fun dragDividerBy(dx: Float) = dragDividerTo(local(handle().center).x + dx)

    @Test
    fun dragging_the_divider_moves_the_weights_it_sits_between() {
        val split = twoPanes()
        assertEquals(0.5f, split.weights[0], 1e-5f)

        val band = handle().width
        assertTrue(
            band > with(compose.density) { 3.dp.toPx() },
            "the grab band is wider than the line it draws: $band",
        )

        val before = local(handle().center).x
        dragDividerBy(90f)

        val widened = split.weights[0]
        assertTrue(widened > 0.5f, "the pane on the left took the space: $widened")
        assertTrue(split.weights[1] < 0.5f, "and the one on the right gave it up")
        assertEquals(1f, split.weights.sum(), 1e-5f)
        // The resize is live, so the divider is under the pointer at the
        // end of the drag rather than a fraction of the way there — the
        // deltas track the pointer, not the handle they keep moving.
        val after = local(handle().center).x
        assertTrue(
            abs(after - (before + 90f)) < 30f,
            "the divider ends where the pointer left it: $before then $after",
        )

        dragDividerBy(-90f)

        assertTrue(split.weights[0] < widened, "and a drag back gives it up again")
        assertEquals(1f, split.weights.sum(), 1e-5f)
    }

    @Test
    fun a_divider_dragged_to_either_edge_stops_at_the_clamp() {
        val split = twoPanes()

        dragDividerTo(2f)

        val squeezed = split.weights[0]
        assertTrue(squeezed < 0.5f, "the divider moved left: $squeezed")
        assertTrue(squeezed >= MIN_PANE_WEIGHT - 1e-4f, "no pane is dragged shut: $squeezed")
        assertEquals(1f, split.weights.sum(), 1e-5f)

        dragDividerTo(host().width - 2f)

        val stretched = split.weights[0]
        assertTrue(stretched > squeezed, "the divider moved right: $stretched")
        assertTrue(stretched <= 1f - MIN_PANE_WEIGHT + 1e-4f, "nor is its neighbour: $stretched")
        assertEquals(1f, split.weights.sum(), 1e-5f)
    }
}
