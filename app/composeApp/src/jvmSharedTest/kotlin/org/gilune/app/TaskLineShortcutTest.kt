// The task shortcut (Ctrl+L by default, Obsidian's "toggle checkbox
// status") asked of the caret's line. The rule is arithmetic over a string
// so the phone can ask it later; the desktop editor only wires the key.
// `|` marks the caret before and after.

package org.gilune.app

import org.gilune.plugins.tasks.tasksDecorations
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskLineShortcutTest {
    private val today = LocalDate.of(2026, 10, 20)
    private val decorations = builtInDecorations() + tasksDecorations("", today)

    private fun press(marked: String): String {
        val caret = marked.indexOf('|')
        val text = marked.replace("|", "")
        val edit = taskLineShortcut(text, caret, decorations) ?: return marked
        val written = edit.applyTo(text)
        val moved = edit.map(caret)
        return written.substring(0, moved) + "|" + written.substring(moved)
    }

    @Test fun aPlainLineBecomesATaskAndTheCaretStaysWithItsText() {
        assertEquals("- [ ] mi|lk", press("mi|lk"))
        assertEquals("- [ ] |milk", press("|milk"))
        assertEquals("- [ ] milk|", press("milk|"))
    }

    @Test fun anIndentedLineKeepsItsIndentation() {
        assertEquals("  - [ ] mi|lk", press("  mi|lk"))
        assertEquals("\t- [ ] |milk", press("\t|milk"))
        assertEquals(" |  - [ ] milk", press(" |  milk"), "a caret inside the indentation stays there")
    }

    @Test fun aBulletGainsAMarkerAndKeepsItsBullet() {
        assertEquals("- [ ] mi|lk", press("- mi|lk"))
        assertEquals("* [ ] |milk", press("* |milk"))
        assertEquals("  + [ ] milk|", press("  + milk|"))
    }

    @Test fun anEmptyLineTakesAMarkerWithTheCaretAfterIt() {
        assertEquals("- [ ] |", press("|"))
        assertEquals("one\n- [ ] |\ntwo", press("one\n|\ntwo"))
        assertEquals("  - [ ] |", press("  |"))
    }

    @Test fun onlyTheCaretsLineChanges() {
        assertEquals("one\n- [ ] tw|o\nthree", press("one\ntw|o\nthree"))
    }

    @Test fun aTaskTicksThroughTheChainAndIsStampedDone() {
        val edit = taskLineShortcut("- [ ] milk", 3, decorations)!!
        assertTrue(edit.activated, "a tick is an action, written at once")
        assertEquals("- [x] mi|lk done:2026-10-20", press("- [ ] mi|lk"))
        assertEquals("- [x] milk| done:2026-10-20", press("- [ ] milk|"), "the caret stays at the end of the words")
    }

    @Test fun aTickedTaskUnticksAndLosesItsDoneDate() {
        assertEquals("- [ ] milk|", press("- [x] milk| done:2026-10-01"))
        assertEquals("- [ ] milk and| bread", press("- [x] milk done:2026-10-01 and| bread"))
        assertEquals("  * [ ] |milk", press("  * [X] |milk"))
    }

    @Test fun withoutTheTasksPluginATickIsTheCheckboxAlone() {
        val edit = taskLineShortcut("- [ ] milk", 0, builtInDecorations())!!
        assertEquals("- [x] milk", edit.after)
    }

    @Test fun typingAMarkerIsNotAnAction() {
        assertFalse(taskLineShortcut("milk", 0, decorations)!!.activated)
        assertFalse(taskLineShortcut("- milk", 0, decorations)!!.activated)
    }

    @Test fun insideAFenceNothingHappens() {
        val fenced = "```\nmilk\n- [ ] bread\n```\n"
        assertNull(taskLineShortcut(fenced, fenced.indexOf("milk"), decorations))
        assertNull(taskLineShortcut(fenced, fenced.indexOf("bread"), decorations), "a quoted task is text")
        assertNull(taskLineShortcut(fenced, 1, decorations), "the delimiter belongs to the block")
        assertNull(taskLineShortcut(fenced, fenced.lastIndexOf("```") + 1, decorations))
        assertEquals("- [ ] |", press("```\nx\n```\n|").substringAfterLast('\n'), "after the fence closes it works again")
    }

    @Test fun anOrderedItemIsLeftAlone() {
        assertNull(taskLineShortcut("1. milk", 3, decorations))
        assertNull(taskLineShortcut("  2) milk", 3, decorations))
    }

    @Test fun offsetsPastTheLineMoveByItsChange() {
        val edit = taskLineShortcut("milk\nbread", 0, decorations)!!
        assertEquals(5 + 6, edit.map(5))
        assertEquals(0, taskLineShortcut("one\nmilk", 5, decorations)!!.map(0))
    }
}
