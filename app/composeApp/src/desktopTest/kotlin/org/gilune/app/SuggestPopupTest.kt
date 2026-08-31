// Where the caret popup lands, and what it puts on screen (ADR 0015
// § 2). The placement rule is arithmetic over a rectangle, so it is
// tested without a window; the surface itself is checked through the
// tags it publishes.

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Rule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalTestApi::class)
class SuggestPopupTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 9, 1)
    private val window = IntSize(800, 600)
    private val popup = IntSize(210, 160)

    /// The provider ignores its anchor bounds — the caret rectangle is
    /// the anchor — so any value will do here.
    private val ignored = IntRect(0, 0, 0, 0)

    private fun placedAt(caret: Rect, windowSize: IntSize = window, contentSize: IntSize = popup) =
        CaretPositionProvider(caret).calculatePosition(ignored, windowSize, LayoutDirection.Ltr, contentSize)

    private fun valuesSession(): SuggestSession {
        val fields = listOfNotNull(suggestField("due", "Due date", DATE_VALUES_PROVIDER))
        val session = assertNotNull(SuggestSession.open("- [ ] milk /", 12, fields, "", today))
        return (session.accept() as? SuggestOutcome.Open)?.session ?: fail("stage one passes through")
    }

    @Test
    fun theMenuSitsJustBelowTheCaretWhenThereIsRoom() {
        val placed = placedAt(Rect(100f, 200f, 101f, 218f))
        assertEquals(100, placed.x)
        assertEquals(222, placed.y, "four pixels under the caret's foot")
    }

    @Test
    fun theMenuFlipsAboveTheCaretNearTheWindowFoot() {
        val caret = Rect(100f, 560f, 101f, 578f)
        val placed = placedAt(caret)
        assertEquals(396, placed.y)
        assertTrue(placed.y + popup.height <= caret.top, "it clears the caret's line entirely")
    }

    @Test
    fun aMenuThatFitsNeitherWayIsClampedIntoTheWindow() {
        val placed = placedAt(Rect(10f, 60f, 11f, 78f), windowSize = IntSize(800, 120))
        assertEquals(0, placed.y)
        assertTrue(placed.y >= 0)
    }

    @Test
    fun theMenuSlidesLeftToStayInsideTheWindow() {
        assertEquals(590, placedAt(Rect(700f, 100f, 701f, 118f)).x)
        // Narrower than the menu: the left edge wins over a negative
        // offset that would put it off screen.
        assertEquals(0, placedAt(Rect(10f, 100f, 11f, 118f), windowSize = IntSize(100, 600)).x)
    }

    @Test
    fun theMenuShowsOneRowPerCandidateWithItsResolvedDate() {
        val session = valuesSession()
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.fillMaxSize()) {
                    SuggestPopup(session, Rect(40f, 40f, 41f, 58f), onDismiss = {})
                }
            }
        }
        compose.onNodeWithTag("suggest-popup").assertIsDisplayed()
        assertEquals(
            session.candidates.size,
            compose.onAllNodesWithTag("suggest-row").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("today").assertIsDisplayed()
        compose.onNodeWithText("2026-09-01").assertIsDisplayed()
        compose.onNodeWithText("↵ insert · esc dismiss").assertIsDisplayed()
    }
}
