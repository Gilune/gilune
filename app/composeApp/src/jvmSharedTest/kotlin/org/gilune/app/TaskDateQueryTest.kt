// The date language of `due:` and `done:` (owner, 2026-10-30), pure: every
// form against an injected `today`, so nothing here rots on the day it was
// written.

package org.gilune.app

import org.gilune.plugins.tasks.DateQuestion
import org.gilune.plugins.tasks.DateValueError
import org.gilune.plugins.tasks.WeekStart
import org.gilune.plugins.tasks.describeDateQuestion
import org.gilune.plugins.tasks.resolveDateQuestion

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskDateQueryTest {
    /// A Friday.
    private val today = LocalDate.of(2026, 10, 30)

    private fun span(value: String, weekStart: WeekStart = WeekStart.MONDAY): Pair<String?, String?> =
        (resolveDateQuestion(value, today, weekStart) as DateQuestion.Span).let { it.from?.toString() to it.to?.toString() }

    private fun day(iso: String) = iso to iso

    @Test
    fun wordsAreSingleDays() {
        assertEquals(day("2026-10-30"), span("today"))
        assertEquals(day("2026-10-29"), span("yesterday"))
        assertEquals(day("2026-10-31"), span("tomorrow"))
        assertEquals(day("2026-10-12"), span("2026-10-12"))
    }

    @Test
    fun offsetsCountFromTodayOrFromTheWordBeforeThem() {
        assertEquals(day("2026-10-23"), span("-7d"))
        assertEquals(day("2026-11-02"), span("+3d"))
        assertEquals(day("2026-10-16"), span("-2w"))
        assertEquals(day("2026-09-30"), span("-1m"))
        assertEquals(day("2025-10-30"), span("-1y"))
        assertEquals(day("2026-10-26"), span("yesterday-3d"))
    }

    @Test
    fun periodsAreTheWholeWeekMonthQuarterOrYear() {
        assertEquals("2026-10-26" to "2026-11-01", span("thisweek"))
        assertEquals("2026-10-26" to "2026-10-30", span("thisworkweek"))
        assertEquals("2026-10-01" to "2026-10-31", span("thismonth"))
        assertEquals("2026-10-01" to "2026-12-31", span("thisquarter"))
        assertEquals("2026-01-01" to "2026-12-31", span("thisyear"))
        assertEquals("2026-10-19" to "2026-10-25", span("lastweek"))
        assertEquals("2026-09-01" to "2026-09-30", span("lastmonth"))
        assertEquals("2026-07-01" to "2026-09-30", span("lastquarter"))
        assertEquals("2025-01-01" to "2025-12-31", span("lastyear"))
    }

    @Test
    fun anOffsetAfterAPeriodMovesTheWholePeriod() {
        assertEquals(span("lastmonth"), span("thismonth-1m"))
        assertEquals(span("lastweek"), span("thisweek-1w"))
        assertEquals("2026-11-02" to "2026-11-08", span("thisweek+1w"))
        assertEquals("2026-04-01" to "2026-06-30", span("lastquarter-3m"))
    }

    @Test
    fun absolutePeriodsNameAMonthAYearOrAQuarter() {
        assertEquals("2026-09-01" to "2026-09-30", span("2026-09"))
        assertEquals("2025-01-01" to "2025-12-31", span("2025"))
        assertEquals("2026-07-01" to "2026-09-30", span("2026-Q3"))
        assertEquals("2026-08-01" to "2026-08-31", span("2026-09-1m"), "an absolute period takes an offset too")
    }

    @Test
    fun lastWeekdayIsTheMostRecentOneAndABareWeekdayTheNext() {
        assertEquals(day("2026-10-26"), span("lastmonday"))
        assertEquals(day("2026-10-30"), span("lastfriday"), "today is the most recent Friday")
        assertEquals(day("2026-11-02"), span("monday"))
    }

    @Test
    fun comparisonsAndRanges() {
        assertEquals(null to "2026-10-25", span("before:thisweek"), "before leaves the whole period out")
        assertEquals("2026-11-02" to null, span("after:thisweek"))
        assertEquals("2026-10-26" to null, span("since:thisweek"), "since keeps it")
        assertEquals(null to "2026-11-01", span("until:thisweek"))
        assertEquals("2026-09-01" to "2026-10-31", span("lastmonth..thismonth"))
        assertEquals("2026-10-23" to "2026-10-30", span("-7d..today"))
        assertEquals(null to "2026-10-29", span("overdue"), "the older words keep their meaning")
        assertEquals("2026-10-30" to "2026-11-05", span("week"))
        assertEquals(DateQuestion.None, resolveDateQuestion("none", today))
    }

    @Test
    fun aSundayWeekStartMovesEveryWeek() {
        assertEquals("2026-10-25" to "2026-10-31", span("thisweek", WeekStart.SUNDAY))
        assertEquals("2026-10-26" to "2026-10-30", span("thisworkweek", WeekStart.SUNDAY), "work days stay Monday to Friday")
        assertEquals("2026-10-18" to "2026-10-24", span("lastweek", WeekStart.SUNDAY))
        assertEquals(span("thisweek"), span("thisweek", WeekStart.of("banana")), "an unknown setting is Monday")
    }

    @Test
    fun aValueThatMeansNothingSaysWhyAndSuggests() {
        val typo = assertFailsWith<DateValueError> { resolveDateQuestion("thismnth", today) }
        assertTrue("did you mean thismonth?" in typo.message.orEmpty(), typo.message)
        assertFailsWith<DateValueError> { resolveDateQuestion("", today) }
        assertFailsWith<DateValueError> { resolveDateQuestion("2026-13", today) }
        assertFailsWith<DateValueError> { resolveDateQuestion("2026-Q5", today) }
        assertFailsWith<DateValueError> { resolveDateQuestion("today..yesterday", today) }
        assertFailsWith<DateValueError> { resolveDateQuestion("7d", today) }
    }

    @Test
    fun theDescriptionNamesTheDaysTaken() {
        assertEquals("26 Oct – 1 Nov 2026", describeDateQuestion(resolveDateQuestion("thisweek", today)))
        assertEquals("30 Oct 2026", describeDateQuestion(resolveDateQuestion("today", today)))
        assertEquals("28 Dec 2026 – 3 Jan 2027", describeDateQuestion(resolveDateQuestion("thisweek", LocalDate.of(2026, 12, 30))))
        assertEquals("from 2 Nov 2026", describeDateQuestion(resolveDateQuestion("after:thisweek", today)))
        assertEquals("up to 29 Oct 2026", describeDateQuestion(resolveDateQuestion("overdue", today)))
        assertEquals("no date", describeDateQuestion(DateQuestion.None))
    }
}
