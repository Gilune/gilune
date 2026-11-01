// Where the phone finds a due date to long-press (ADR 0015 amendment
// 2026-10-31): the date's range in the shown text, which the checkbox glyph
// before it shifts, and the way back from a shown offset to the source —
// the caret a short tap on the date places.

package org.gilune.app

import org.gilune.plugins.tasks.tasksDecorations

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MobileTokenMenuTest {
    private val decorations = mobileEditorDecorations(builtInDecorations() + tasksDecorations("", LocalDate.of(2026, 10, 31)))

    @Test
    fun aDateIsFoundWhereTheFieldShowsIt() {
        val source = "intro\n- [ ] a due:2026-11-03\n- [x] b"
        val shown = renderedEditorText(source, decorations)
        val target = menuTargets(source, decorations).single()
        assertEquals("due:2026-11-03", shown.substring(target.transformedStart, target.transformedEnd))
        assertEquals("- [ ] a due:2026-11-03", source.substring(target.lineStart, target.lineEnd))
        assertNotNull(target.decoration.menu, "the menu travels through the phone's rebuilt registrations")
    }

    @Test
    fun aShownOffsetMapsBackToTheSource() {
        val source = "- [ ] a due:2026-11-03"
        val shown = renderedEditorText(source, decorations)
        val at = shown.indexOf("due:")
        assertEquals(source.indexOf("due:"), sourceOffset(source, at, decorations))
        assertEquals(source.length, sourceOffset(source, shown.length, decorations))
        // Inside the glyph lands at the marker's start.
        assertEquals(2, sourceOffset(source, 2, decorations))
    }
}
