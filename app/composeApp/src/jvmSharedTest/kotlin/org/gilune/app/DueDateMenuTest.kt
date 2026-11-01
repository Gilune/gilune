// The due-date menu (ADR 0015 amendment 2026-10-31) as data: what it
// offers a dated, an overdue and an undated task, what each item writes,
// what typed words mean, and the shell's two questions of it — which line a
// position asks about, and whether that line still reads as it did.

package org.gilune.app

import org.gilune.plugins.tasks.DEFAULT_DATE_SHORTCUT
import org.gilune.plugins.tasks.TaskHit
import org.gilune.plugins.tasks.WeekStart
import org.gilune.plugins.tasks.dueDateMenu
import org.gilune.plugins.tasks.removeDue
import org.gilune.plugins.tasks.setDue
import org.gilune.plugins.tasks.tasksDateShortcut
import org.gilune.plugins.tasks.tasksDateShortcutKey
import org.gilune.plugins.tasks.tasksDateShortcutSetting
import org.gilune.plugins.tasks.tasksDecorations
import org.gilune.plugins.tasks.tasksSettingsDocument
import org.gilune.plugins.tasks.tasksShortcutSetting

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DueDateMenuTest {
    // A Saturday.
    private val today = LocalDate.of(2026, 10, 31)

    private fun rows(line: String, on: LocalDate = today, weekStart: WeekStart = WeekStart.MONDAY) =
        assertNotNull(dueDateMenu(line, on, weekStart = weekStart)).items.map { "${it.label}|${it.detail}" }

    @Test
    fun aDateStillAheadIsPostponedFromItselfStepsFirst() {
        val menu = assertNotNull(dueDateMenu("- [ ] Call the plasterer due:2026-11-03", today))
        assertEquals("Postpone due date", menu.name)
        assertEquals("Postpone from Tue 3 Nov", menu.title)
        assertEquals(
            listOf(
                "+1 day|Wed 4 Nov", "+2 days|Thu 5 Nov", "+3 days|Fri 6 Nov", "+1 week|Tue 10 Nov",
                "+2 weeks|Tue 17 Nov", "+1 month|Thu 3 Dec",
                "Today|Sat 31 Oct", "Tomorrow|Sun 1 Nov", "Next Monday|Mon 2 Nov",
                "Remove due date|",
            ),
            menu.items.map { "${it.label}|${it.detail}" },
        )
        assertEquals(listOf(false, false, false, false, false, false, true, false, false, true), menu.items.map { it.startsGroup })
    }

    @Test
    fun anOverdueDateMovesFromTodayAndNoTwoRowsWriteTheSameDay() {
        val menu = assertNotNull(dueDateMenu("- [ ] Send the drawings due:2026-10-29", today))
        assertEquals("Overdue · moves from today", menu.title)
        // +1 day is tomorrow and +2 days next Monday: named once, by name.
        assertEquals(
            listOf(
                "Today|Sat 31 Oct", "Tomorrow|Sun 1 Nov", "Next Monday|Mon 2 Nov",
                "+3 days|Tue 3 Nov", "+1 week|Sat 7 Nov", "+2 weeks|Sat 14 Nov", "+1 month|Mon 30 Nov",
                "Remove due date|",
            ),
            menu.items.map { "${it.label}|${it.detail}" },
        )
        // The +3 days row opens its group even though +1 and +2 dropped out.
        assertTrue(menu.items.single { it.label == "+3 days" }.startsGroup)
    }

    @Test
    fun theDateAlreadyThereIsNeverOffered() {
        // Due tomorrow: "Tomorrow" would write what is there.
        assertTrue(rows("- [ ] a due:2026-11-01").none { it.startsWith("Tomorrow") })
        // Due today, and so not overdue: postponed from today, and "Today" goes.
        val dueToday = assertNotNull(dueDateMenu("- [ ] a due:2026-10-31", today))
        assertEquals("Postpone from Sat 31 Oct", dueToday.title)
        assertTrue(dueToday.items.none { it.label == "Today" })
    }

    @Test
    fun anUndatedTaskIsOfferedAFirstDate() {
        val menu = assertNotNull(dueDateMenu("- [ ] Order skirting", today))
        assertEquals("Add a due date", menu.name)
        assertEquals("Add a due date", menu.title)
        assertEquals(
            listOf("Today|Sat 31 Oct", "Tomorrow|Sun 1 Nov", "Next Monday|Mon 2 Nov", "In a week|Sat 7 Nov", "In a month|Mon 30 Nov"),
            menu.items.map { "${it.label}|${it.detail}" },
        )
        assertEquals("- [ ] Order skirting due:2026-11-01", menu.items[1].rewrite("- [ ] Order skirting"))
    }

    @Test
    fun onlyTasksTheFilterOwnsHaveAMenu() {
        assertNull(dueDateMenu("Prose with due:2026-11-03", today))
        assertNull(dueDateMenu("1. [ ] ordered", today))
        assertNull(dueDateMenu("- [ ] no tag due:2026-11-03", today, filter = "#task"))
        assertNotNull(dueDateMenu("- [ ] tagged #task due:2026-11-03", today, filter = "#task"))
    }

    @Test
    fun nextWeekFollowsTheWeekStartSetting() {
        val wednesday = LocalDate.of(2026, 10, 28)
        assertTrue("Next Sunday|Sun 1 Nov" in rows("- [ ] a", wednesday, WeekStart.SUNDAY))
        assertTrue("Next Monday|Mon 2 Nov" in rows("- [ ] a", wednesday))
    }

    @Test
    fun aDayInAnotherYearSaysTheYear() {
        assertTrue("+1 month|Wed 20 Jan 2027" in rows("- [ ] a due:2026-12-20"))
    }

    @Test
    fun theDateChangesWhereItStandsAndNothingElseMoves() {
        val line = "  - [ ] Call due:2026-11-03 priority:high  "
        assertEquals("  - [ ] Call due:2026-11-09 priority:high  ", setDue(line, LocalDate.of(2026, 11, 9)))
        // Two due dates: the first is the one every reader takes.
        assertEquals("- [ ] a due:2026-12-01 due:2026-11-05", setDue("- [ ] a due:2026-11-03 due:2026-11-05", LocalDate.of(2026, 12, 1)))
        // Appended after a trailing space without adding a second one.
        assertEquals("- [ ] a due:2026-11-01", setDue("- [ ] a ", LocalDate.of(2026, 11, 1)))
        // A well-shaped date that does not exist is corrected in place.
        assertEquals("- [ ] a due:2026-03-02", setDue("- [ ] a due:2026-02-30", LocalDate.of(2026, 3, 2)))
    }

    @Test
    fun removingTakesTheTokenAndItsSpaceOnly() {
        assertEquals("- [ ] Call priority:high", removeDue("- [ ] Call due:2026-11-03 priority:high"))
        assertEquals("- [ ] Call", removeDue("- [ ] Call due:2026-11-03"))
        assertEquals("- [ ] Call", removeDue("- [ ] Call"))
        val menu = assertNotNull(dueDateMenu("- [x] Done due:2026-11-03 done:2026-10-30", today))
        assertEquals("- [x] Done done:2026-10-30", menu.items.last().rewrite("- [x] Done due:2026-11-03 done:2026-10-30"))
    }

    @Test
    fun typedWordsMeanWhatTheSlashMenuMeansOrAStepFromTheDate() {
        val menu = assertNotNull(dueDateMenu("- [ ] a due:2026-11-03", today))
        val typed = assertNotNull(menu.typed)
        fun said(words: String) = typed(words)?.let { "${it.label}|${it.detail}" }
        assertEquals("friday|Fri 6 Nov", said("Friday"))
        assertEquals("friday|Fri 6 Nov", said("fri"))
        assertEquals("tomorrow|Sun 1 Nov", said("tom"))
        assertEquals("2026-11-20|Fri 20 Nov", said("2026-11-20"))
        // Steps count from the date being moved, as the rows do.
        assertEquals("+3 days|Fri 6 Nov", said("+3d"))
        assertEquals("+2 weeks|Tue 17 Nov", said("2w"))
        assertEquals("+1 month|Thu 3 Dec", said("+1m"))
        assertNull(said(""))
        assertNull(said("whenever"))
        assertNull(said("2026-02-30"))
        assertNull(said("+0d"))
        assertEquals("- [ ] a due:2026-11-06", typed("fri")!!.rewrite("- [ ] a due:2026-11-03"))
        // Overdue: steps count from today.
        assertEquals("+3 days|Tue 3 Nov", dueDateMenu("- [ ] a due:2026-10-01", today)!!.typed!!("3d")?.let { "${it.label}|${it.detail}" })
    }

    // -- The shell's side: which line a position asks, and the stale guard.

    private val decorations = builtInDecorations() + tasksDecorations("", today)

    @Test
    fun aPositionAsksTheLineItIsOnAndNotOneInAFence() {
        val text = "Intro\n- [ ] a due:2026-11-03\n```\n- [ ] quoted due:2026-11-03\n```\nprose"
        val found = assertNotNull(lineMenuAt(text, text.indexOf("a due"), decorations, today))
        assertEquals("- [ ] a due:2026-11-03", text.substring(found.lineRange.first, found.lineRange.last + 1))
        assertNull(lineMenuAt(text, text.indexOf("quoted"), decorations, today))
        assertNull(lineMenuAt(text, text.indexOf("```"), decorations, today))
        assertNull(lineMenuAt(text, text.length, decorations, today), "prose has nothing to offer")
        assertNull(lineMenuAt(text, text.indexOf("a due"), builtInDecorations(), today), "no tasks plugin, no menu")
    }

    @Test
    fun theMenuTokenIsFoundOnTheCaretsLineToo() {
        val text = "- [ ] a due:2026-11-03\n- [ ] b"
        val onCaret = menuTokens(text, caretLine = 0, decorations)
        assertEquals(listOf("due:2026-11-03"), onCaret.map { text.substring(it.range.first, it.range.last + 1) })
        // Off the caret's line the checkbox becomes a glyph; the token's
        // place in the shown text moves with it, its source range does not.
        val off = menuTokens(text, caretLine = 1, decorations).single()
        assertEquals(onCaret.single().range, off.range)
        assertTrue(off.transformedStart < onCaret.single().transformedStart)
    }

    @Test
    fun aLineThatChangedWhileTheMenuWasOpenIsLeftAlone() {
        val text = "x\n- [ ] a due:2026-11-03\ny"
        val found = assertNotNull(lineMenuAt(text, 4, decorations, today))
        val line = "- [ ] a due:2026-11-03"
        val item = found.menu.items.first()
        assertEquals("x\n- [ ] a due:2026-11-04\ny", applyMenuItem(text, found.lineRange, line, item))
        assertNull(applyMenuItem("x\n- [ ] a due:2026-11-03 edited\ny", found.lineRange, line, item))
    }

    @Test
    fun aTaskRowOffersItsLinesMenuAndWritesThroughTheGuard() {
        val hit = TaskHit("Work/a.md", "a", 2, "- [ ] a due:2026-11-03", LocalDate.of(2026, 11, 3), null)
        val menu = assertNotNull(taskResultMenu(listOf(installedTasksPlugin()), hit, today))
        assertEquals("Postpone from Tue 3 Nov", menu.title)
        assertNull(taskResultMenu(listOf(installedTasksPlugin(state = "disabled")), hit, today))
        val note = "---\ngilune-id: x\n---\nfirst\n- [ ] a due:2026-11-03\n"
        assertEquals(
            "---\ngilune-id: x\n---\nfirst\n- [ ] a due:2026-11-04\n",
            rewriteTaskLine(note, hit.line - 1, hit.checkboxLine, menu.items.first().rewrite),
        )
        assertNull(rewriteTaskLine(note.replace("due:2026-11-03", "due:2026-11-05"), hit.line - 1, hit.checkboxLine, menu.items.first().rewrite))
    }

    @Test
    fun theDateShortcutIsTheTasksPluginsToChange() {
        assertEquals(DEFAULT_DATE_SHORTCUT, tasksDateShortcutKey(tasksSettingsDocument("")))
        assertNull(tasksDateShortcutSetting(tasksSettingsDocument("")), "the default is not written down")
        val set = tasksSettingsDocument("#task", panel = false, shortcut = "Ctrl+L", dateShortcut = "Alt+D")
        assertEquals("Alt+D", tasksDateShortcutKey(set))
        assertEquals("Ctrl+L", tasksShortcutSetting(set), "the task shortcut survives beside it")
        assertEquals("Alt+D", listOf(installedTasksPlugin(settings = set)).tasksDateShortcut())
    }
}
