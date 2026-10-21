// Where a click on a task or a search match puts the caret (owner,
// 2026-10-21): the end of its line — or, if the note moved on since it was
// indexed, the line that still says what the hit said.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteLinesTest {
    private val body = "# Kitchen\n\nsome text\n- [ ] buy milk\nafter"

    @Test
    fun theNumberedLineIsMetAtItsEnd() {
        assertEquals(body.indexOf("- [ ] buy milk") + "- [ ] buy milk".length, revealOffset(body, 4, "- [ ] buy milk"))
        assertEquals("# Kitchen".length, revealOffset(body, 1))
    }

    @Test
    fun aNoteEditedSinceItWasIndexedIsMetByTheLinesText() {
        val moved = "new first line\n$body"
        assertEquals(moved.indexOf("- [ ] buy milk") + "- [ ] buy milk".length, revealOffset(moved, 4, "- [ ] buy milk"),
            "line 4 is now 'some text'; the task is found where it went")
    }

    @Test
    fun aLineThatIsGoneFallsBackToTheNumberWithinTheBody() {
        assertEquals(body.length, revealOffset(body, 99, "vanished"), "kept within the body: the last line's end")
        assertEquals("# Kitchen".length, revealOffset(body, 0), "below one is the first line")
        assertNull(revealOffset("", 3))
    }
}
