// The idle timer's boundaries (ADR 0017 § 1). Pure over three facts, so
// the rule can be checked without a clock, a screen or a vault.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals

class IdleComposerTest {
    private val fiveMinutes = 5 * 60_000L

    private fun verdict(
        draft: String = "half a thought",
        away: Long? = 0L,
        now: Long = 0L,
        timeout: IdleTimeout = IdleTimeout.Default,
    ) = composerReturn(draft, away, now, timeout)

    @Test
    fun theDefaultIsFiveMinutes() {
        assertEquals(5, IdleTimeout.Default.minutes)
    }

    @Test
    fun exactlyTheTimeoutExpiresAndCommits() {
        assertEquals(
            ComposerReturn.CommitAndClear,
            verdict(away = 0L, now = fiveMinutes),
            "five minutes away with the timer at five is expiry, not a near miss",
        )
    }

    @Test
    fun aMillisecondShortOfTheTimeoutResumes() {
        assertEquals(ComposerReturn.Keep, verdict(away = 0L, now = fiveMinutes - 1))
    }

    @Test
    fun wellPastTheTimeoutCommits() {
        assertEquals(ComposerReturn.CommitAndClear, verdict(away = 0L, now = fiveMinutes * 100))
    }

    @Test
    fun anEmptyComposerNeverFires() {
        assertEquals(ComposerReturn.Keep, verdict(draft = "", away = 0L, now = fiveMinutes * 10))
        assertEquals(
            ComposerReturn.Keep,
            verdict(draft = "  \n\t ", away = 0L, now = fiveMinutes * 10),
            "whitespace is not a thought: there is nothing to commit and nothing to lose",
        )
    }

    @Test
    fun neverKeepsHoweverLongItHasBeen() {
        assertEquals(
            ComposerReturn.Keep,
            verdict(away = 0L, now = fiveMinutes * 1000, timeout = IdleTimeout.Never),
        )
        assertEquals(null, IdleTimeout.Never.minutes)
    }

    @Test
    fun havingNeverLeftKeeps() {
        assertEquals(ComposerReturn.Keep, verdict(away = null, now = fiveMinutes * 10))
    }

    @Test
    fun aClockThatMovedBackwardsKeeps() {
        assertEquals(
            ComposerReturn.Keep,
            verdict(away = fiveMinutes * 10, now = 0L),
            "guessing wrong towards waiting costs a stale composer; the other way commits a live thought",
        )
    }

    @Test
    fun aShorterTimeoutFiresSooner() {
        assertEquals(ComposerReturn.CommitAndClear, verdict(away = 0L, now = 60_000L, timeout = IdleTimeout(1)))
        assertEquals(ComposerReturn.Keep, verdict(away = 0L, now = 60_000L, timeout = IdleTimeout(2)))
    }

    @Test
    fun neverSurvivesAStoreRoundTrip() {
        for (choice in IdleTimeout.CHOICES) {
            assertEquals(choice, IdleTimeout.fromStored(choice.stored), "${choice.label} did not round-trip")
        }
    }

    @Test
    fun neverIsStoredApartFromZeroMinutes() {
        assertEquals(IdleTimeout.Never, IdleTimeout.fromStored(IdleTimeout.NEVER_STORED))
        assertEquals(IdleTimeout(0), IdleTimeout.fromStored(0), "zero minutes is a setting, not a refusal")
    }
}
