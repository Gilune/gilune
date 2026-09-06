// ADR 0015 § 2's promise, pinned: a phrase is UI and an ISO date is
// storage, so every phrase resolves or yields nothing at all.
//
// `today` is fixed here — Tuesday 2026-09-01 — and passed in everywhere,
// which is the whole reason the module takes it as a parameter. No test
// in this file would change its answer if run at midnight.

package org.gilune.app

import org.gilune.plugins.tasks.dateCandidates
import org.gilune.plugins.tasks.resolveDatePhrase

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskDatesTest {
    private val today = LocalDate.of(2026, 9, 1)

    private fun iso(phrase: String): String? = resolveDatePhrase(phrase, today)?.toString()

    @Test
    fun theFixedTodayIsATuesday() {
        // Every weekday assertion below reads against this.
        assertEquals(DayOfWeek.TUESDAY, today.dayOfWeek)
    }

    @Test
    fun todayAndTomorrowResolve() {
        assertEquals("2026-09-01", iso("today"))
        assertEquals("2026-09-02", iso("tomorrow"))
    }

    @Test
    fun aWeekdayResolvesToItsNextOccurrence() {
        assertEquals("2026-09-02", iso("wednesday"))
        assertEquals("2026-09-04", iso("friday"))
        assertEquals("2026-09-07", iso("monday"))
    }

    @Test
    fun todaysOwnWeekdayIsAWeekAwayNeverToday() {
        // The documented choice: said on a Tuesday, "tuesday" is the next
        // one. `today` is the word for today.
        assertEquals("2026-09-08", iso("tuesday"))
    }

    @Test
    fun nextWeekAndInAWeekAreBothSevenDaysOn() {
        assertEquals("2026-09-08", iso("next week"))
        assertEquals("2026-09-08", iso("in a week"))
    }

    @Test
    fun anIsoDateIsTakenVerbatim() {
        assertEquals("2026-12-25", iso("2026-12-25"))
        assertEquals("2027-01-01", iso("2027-01-01"))
    }

    @Test
    fun aDateThatDoesNotExistResolvesToNothing() {
        assertNull(iso("2026-13-01"))
        assertNull(iso("2026-02-30"))
    }

    @Test
    fun onlyTheZeroPaddedIsoFormIsAccepted() {
        // The stored form is `yyyy-MM-dd`; a form we would not write is a
        // form we do not read.
        assertNull(iso("2026-9-1"))
        assertNull(iso("26-09-01"))
        assertNull(iso("01/09/2026"))
    }

    @Test
    fun unknownPhrasesResolveToNothing() {
        assertNull(iso(""))
        assertNull(iso("   "))
        assertNull(iso("someday"))
        assertNull(iso("next month"))
        assertNull(iso("nextweek"))
        assertNull(iso("tomorrow morning"))
    }

    @Test
    fun caseAndPaddingDoNotMatter() {
        assertEquals("2026-09-02", iso("  Tomorrow "))
        assertEquals("2026-09-08", iso("NEXT  WEEK"))
        assertEquals("2026-09-07", iso("Monday"))
    }

    @Test
    fun theEmptyFragmentOffersTheWholeListInMenuOrder() {
        val phrases = dateCandidates("", today).map { it.phrase }
        assertEquals(
            listOf(
                "today",
                "tomorrow",
                // The weekday block runs forwards from tomorrow, which is
                // a Wednesday here.
                "wednesday",
                "thursday",
                "friday",
                "saturday",
                "sunday",
                "monday",
                "tuesday",
                "next week",
                "in a week",
            ),
            phrases,
        )
    }

    @Test
    fun everyOfferedRowCarriesAResolvedDate() {
        val candidates = dateCandidates("", today)
        assertTrue(candidates.all { it.iso != null }, "the standing menu never offers an unresolvable row")
        // The weekday block is the seven days after today, in order.
        val weekdays = candidates.subList(2, 9).map { it.iso }
        assertEquals((1L..7L).map { today.plusDays(it).toString() }, weekdays)
    }

    @Test
    fun narrowingIgnoresCase() {
        assertEquals(listOf("tomorrow"), dateCandidates("TOM", today).map { it.phrase })
        assertEquals(listOf("wednesday"), dateCandidates("Wed", today).map { it.phrase })
    }

    @Test
    fun prefixMatchesLeadSubstringMatches() {
        val phrases = dateCandidates("t", today).map { it.phrase }
        // Prefix group first, in menu order, then the rows that merely
        // contain a `t`.
        assertEquals(listOf("today", "tomorrow", "thursday", "tuesday"), phrases.take(4))
        assertTrue(phrases.drop(4).containsAll(listOf("saturday", "next week")))
        assertTrue(phrases.none { it == "monday" }, "`monday` holds no t")
    }

    @Test
    fun aSubstringStillNarrows() {
        val phrases = dateCandidates("day", today).map { it.phrase }
        assertEquals("today", phrases.first())
        assertTrue(phrases.contains("wednesday"))
        assertTrue(phrases.none { it == "next week" })
    }

    @Test
    fun aTypedIsoDateIsItsOwnAndOnlyCandidate() {
        val candidates = dateCandidates("2026-12-25", today)
        assertEquals(1, candidates.size)
        assertEquals("2026-12-25", candidates.single().phrase)
        assertEquals("2026-12-25", candidates.single().iso)
    }

    @Test
    fun aHalfTypedDateKeepsOneUnresolvedRow() {
        // Without this the menu would die at the first digit — an empty
        // candidate set dismisses (§ 2) — and a typed date would be
        // unreachable.
        for (fragment in listOf("2", "20", "2026", "2026-", "2026-09", "2026-09-")) {
            val candidates = dateCandidates(fragment, today)
            assertEquals(1, candidates.size, "`$fragment` keeps the menu alive")
            assertEquals(fragment, candidates.single().phrase)
            assertNull(candidates.single().iso, "`$fragment` is not yet a date")
        }
    }

    @Test
    fun aWellShapedImpossibleDateIsShownAndNotInsertable() {
        val candidate = dateCandidates("2026-13-40", today).single()
        assertEquals("2026-13-40", candidate.phrase)
        assertNull(candidate.iso)
    }

    @Test
    fun nonsenseNarrowsToNothing() {
        assertTrue(dateCandidates("zzz", today).isEmpty())
        assertTrue(dateCandidates("next month", today).isEmpty())
    }
}
