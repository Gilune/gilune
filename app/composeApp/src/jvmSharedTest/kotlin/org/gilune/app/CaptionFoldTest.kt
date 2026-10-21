// The caption fold's rules (ADR 0018 § 1), shared since ADR 0027 S0 by the
// phone's live caption and the desktop's live dictation. Moved out of
// CaptionTest unchanged; the event trajectories are R0's measured trace
// (`spikes/r0-moonshine/README.md` § Question 5), not invented.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionFoldTest {
    private fun started(id: Long, text: String) = CaptionEvent(CaptionEvent.STARTED, id, text, false)
    private fun changed(id: Long, text: String, complete: Boolean = false) =
        CaptionEvent(CaptionEvent.TEXT_CHANGED, id, text, complete)
    private fun updated(id: Long, text: String, complete: Boolean = false) =
        CaptionEvent(CaptionEvent.UPDATED, id, text, complete)
    private fun completed(id: Long, text: String) = CaptionEvent(CaptionEvent.COMPLETED, id, text, true)

    private fun fold(vararg events: CaptionEvent): List<CaptionLine> =
        events.fold(emptyList()) { lines, event -> foldCaption(lines, event) }

    private fun display(vararg events: CaptionEvent): CaptionDisplay = captionDisplay(fold(*events))

    // ------------------------------------------------ the co-firing events

    @Test
    fun aStartedLineAndItsFirstTextAreOneLineNotTwo() {
        // R0: "`LineStarted` and `LineTextChanged` fire together, same
        // instant, same text. A UI can listen to `LineTextChanged` alone
        // and miss nothing." So the pair must fold to one line.
        val lines = fold(started(1, "The quick"), changed(1, "The quick"))
        assertEquals(1, lines.size)
        assertEquals("The quick", lines.single().text)
        assertFalse(lines.single().settled)
    }

    @Test
    fun theRedundantUpdatedEventChangesNothing() {
        // R0: "`LineUpdated` and `LineTextChanged` also co-fire on every
        // revision. The pair is redundant for our purposes."
        val once = fold(changed(1, "The quick brown"))
        val twice = fold(changed(1, "The quick brown"), updated(1, "The quick brown"))
        assertEquals(once, twice, "a redundant event moved the caption")
    }

    @Test
    fun replayingAnEventIsANoOp() {
        val once = fold(changed(1, "hello"), changed(1, "hello there"))
        val again = fold(changed(1, "hello"), changed(1, "hello there"), changed(1, "hello there"))
        assertEquals(once, again)
    }

    // -------------------------------------------------- mid-phrase rewrites

    @Test
    fun partialsRewriteMidPhraseAndTheCaptionFollowsThemWholesale() {
        // R0's actual trajectory for one line, verbatim. "fox can" becomes
        // "fox jumps up so"; "lace of the" becomes "lazy dog." These are
        // not tail refinements, which is exactly why ADR 0018 § 1 exempts
        // the caption from the editor's no-reflow law.
        val shown = display(
            changed(1, "The quick, quick, quick,"),
            changed(1, "The quick brown"),
            changed(1, "The quick brown fox can"),
            changed(1, "The quick brown fox jumps up so"),
            changed(1, "The quick brown fox jumps over the lace of the"),
        )
        assertEquals("The quick brown fox jumps over the lace of the", shown.live)
        assertTrue(shown.settled.isEmpty(), "nothing is settled until the engine says so")
    }

    @Test
    fun theEntireRevisableRegionIsMutedNotJustItsTail() {
        // The whole unsettled run is the live text — there is no stable
        // prefix promoted into the settled role, because R0 showed there
        // is no stable prefix.
        val shown = display(completed(1, "One."), changed(2, "Two words so far"))
        assertEquals(listOf("One."), shown.settled)
        assertEquals("Two words so far", shown.live)
    }

    @Test
    fun twoLinesInFlightReadAsOneRun() {
        val shown = display(changed(1, "first"), changed(2, "second"))
        assertEquals("first second", shown.live)
        assertTrue(shown.settled.isEmpty())
    }

    // --------------------------------------------------------- freezing

    @Test
    fun theCompletionFlagSettlesTheLineBeforeTheCompletedEventArrives() {
        // R0: "The last `LineTextChanged` before `LineCompleted` already
        // carries `isComplete = true` and the final text. `LineCompleted`
        // adds no new text." So completion is read off the flag, and the
        // event that follows must be a no-op.
        val settledByFlag = fold(changed(1, "The quick brown fox jumps over the lazy dog.", complete = true))
        assertTrue(settledByFlag.single().settled, "the completion flag did not settle the line")

        val withEvent = foldCaption(settledByFlag, completed(1, "The quick brown fox jumps over the lazy dog."))
        assertEquals(settledByFlag, withEvent, "LineCompleted changed a line it was only confirming")
    }

    @Test
    fun aSettledLineNeverChangesAgain() {
        // R0 asserted this of the engine for every line ("LineCompleted
        // really does freeze"). We enforce it here too: the settled role
        // promises words that will not move, and that promise is ours to
        // keep even if the engine ever breaks its own.
        val settled = fold(completed(1, "The quick brown fox jumps over the lazy dog."))
        val meddled = fold(
            completed(1, "The quick brown fox jumps over the lazy dog."),
            changed(1, "something else entirely"),
            updated(1, "or this"),
            completed(1, "or even this"),
        )
        assertEquals(settled, meddled)
        assertEquals("The quick brown fox jumps over the lazy dog.", meddled.single().text)
    }

    // ---------------------------------------------------------- silence

    @Test
    fun silenceProducesAnEmptyCaption() {
        // R0: five seconds of digital silence produced ZERO events. No
        // events must mean no caption — and, defensively, an event
        // carrying no words must not open an empty row either.
        assertTrue(captionDisplay(emptyList()).isEmpty)
        assertTrue(display(started(1, ""), changed(1, "   ")).isEmpty)
        assertTrue(fold(changed(1, "")).isEmpty(), "a blank partial opened a line")
    }

    @Test
    fun aCompletedButBlankLineDrawsNothing() {
        assertTrue(display(completed(1, "   ")).isEmpty)
    }

    // ------------------------------------------------------- the three rows

    @Test
    fun theCaptionKeepsThreeRowsWithTheLiveOneAlwaysAmongThem() {
        // "capped at about three lines, older lines scrolling off" (§ 1).
        val shown = display(
            completed(1, "One."),
            completed(2, "Two."),
            completed(3, "Three."),
            changed(4, "and now"),
        )
        assertEquals(listOf("Two.", "Three."), shown.settled, "the oldest line did not scroll off")
        assertEquals("and now", shown.live)
        assertEquals(CAPTION_LINES, shown.settled.size + 1)
    }

    @Test
    fun withNothingInFlightThreeSettledLinesShow() {
        val shown = display(completed(1, "One."), completed(2, "Two."), completed(3, "Three."))
        assertEquals(listOf("One.", "Two.", "Three."), shown.settled)
        assertEquals("", shown.live)
    }

    @Test
    fun anErrorEventLeavesTheWordsAlone() {
        // The session tears itself down on an error; the fold's only job
        // is to not corrupt what is already on screen on the way out.
        val before = fold(completed(1, "One."), changed(2, "and"))
        val after = foldCaption(before, CaptionEvent(CaptionEvent.ERROR, -1, "boom", false))
        assertEquals(before, after)
    }
}
