// The `/` menu's rules, tested without a screen (ADR 0015 § 2): when it
// may open at all, how typing narrows it, and — the part that touches the
// user's file — the exact range it replaces and the exact bytes it
// writes.
//
// `today` is fixed at Tuesday 2026-09-01 and passed in, so an expected
// ISO date in this file is a constant, not a moving target.

package org.gilune.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SuggestTest {
    private val today = LocalDate.of(2026, 9, 1)
    private val due = assertNotNull(SuggestField("due", "Due date", dateValues("due")))
    private val fields = listOf(due)

    /// `- [ ] milk /` with the caret just past the slash — the state § 2
    /// opens on.
    private val line = "- [ ] milk /"
    private val caret = line.length

    private fun open(
        lineText: String = line,
        caretInLine: Int = lineText.length,
        filter: String = "",
    ): SuggestSession? = SuggestSession.open(lineText, caretInLine, fields, filter, today)

    private fun accepted(outcome: SuggestOutcome): Acceptance =
        (outcome as? SuggestOutcome.Insert)?.acceptance ?: fail("expected an insertion, got $outcome")

    private fun stillOpen(outcome: SuggestOutcome): SuggestSession =
        (outcome as? SuggestOutcome.Open)?.session ?: fail("expected the menu to stay up, got $outcome")

    // ---- the trigger --------------------------------------------------

    @Test
    fun aSlashAfterASpaceOnATaskLineOpensASession() {
        val session = assertNotNull(open())
        assertEquals(SuggestStage.FIELDS, session.stage)
        assertEquals(11, session.trigger)
        assertEquals(11..11, session.replaceRange)
    }

    @Test
    fun aSlashInProseDoesNotOpen() {
        assertNull(open("just some prose /"))
        assertNull(open("# heading /"))
    }

    @Test
    fun aSlashOnAPlainListItemDoesNotOpen() {
        // A bullet is not a task; the marker is what makes the line one.
        assertNull(open("- milk /"))
    }

    @Test
    fun aMidWordSlashDoesNotOpen() {
        assertNull(open("- [ ] and/"))
        assertNull(open("- [ ] http://"))
    }

    @Test
    fun theCaretMustSitImmediatelyAfterTheSlash() {
        assertNull(open("- [ ] milk / ")) // a space has been typed since
        assertNull(open(line, caretInLine = caret - 1)) // caret before the slash
        assertNull(open("- [ ] milk"))
    }

    @Test
    fun checkedIndentedAndStarredTaskLinesAllTrigger() {
        assertNotNull(open("- [x] milk /"))
        assertNotNull(open("  * [ ] milk /"))
        assertNotNull(open("\t+ [X] milk /"))
    }

    @Test
    fun theGlobalFilterGatesTheTrigger() {
        assertNull(open(line, filter = "#task"))
        assertNotNull(open("- [ ] milk #task /", filter = "#task"))
    }

    @Test
    fun anEmptyFilterAcceptsEveryChecklistLine() {
        // The shipped default (§ 1): every GFM checkbox is a task.
        assertNotNull(open(line, filter = ""))
    }

    @Test
    fun withNoRegisteredFieldsThereIsNoMenu() {
        assertNull(SuggestSession.open(line, caret, emptyList(), "", today))
    }

    @Test
    fun theTriggerPredicateStandsAlone() {
        assertTrue(SuggestSession.triggered(line, caret, ""))
        assertFalse(SuggestSession.triggered("- [ ] and/", 10, ""))
        assertFalse(SuggestSession.triggered(line, caret, "#task"))
        assertFalse(SuggestSession.triggered("", 0, ""))
    }

    // ---- the two stages ------------------------------------------------

    @Test
    fun stageOneOffersTheRegisteredFields() {
        val session = assertNotNull(open())
        assertEquals(listOf("Due date"), session.candidates.map { it.display })
        assertEquals(listOf("due"), session.candidates.map { it.detail })
        assertNull(session.field)
    }

    @Test
    fun theSingleFieldPassesThroughOnEnterToItsValues() {
        val session = stillOpen(assertNotNull(open()).accept())
        assertEquals(SuggestStage.VALUES, session.stage)
        assertEquals("due", session.field?.key)
        assertEquals("today", session.candidates.first().display)
        assertEquals("2026-09-01", session.candidates.first().detail)
        assertEquals("due:2026-09-01", session.candidates.first().insert)
    }

    @Test
    fun slashEnterTomEnterIsTheWholePath() {
        // § 2's worked example, end to end.
        val fieldStage = assertNotNull(open())
        val valueStage = stillOpen(fieldStage.accept())
        val typed = assertNotNull(valueStage.update("- [ ] milk /tom", 15))
        assertEquals(listOf("tomorrow"), typed.candidates.map { it.display })
        val acceptance = accepted(typed.accept())
        assertEquals(11..14, acceptance.replaceRange)
        assertEquals("due:2026-09-02 ", acceptance.text)
    }

    @Test
    fun typingNarrowsStageOneToo() {
        val session = assertNotNull(open())
        val narrowed = assertNotNull(session.update("- [ ] milk /du", 14))
        assertEquals(listOf("Due date"), narrowed.candidates.map { it.display })
    }

    @Test
    fun stageOneTypingStaysInsideTheAcceptedRange() {
        // Typing `du` to pick the field leaves those bytes on the line;
        // the acceptance reaches back to the slash and takes them with
        // it, so the line never keeps a half-typed field name.
        val text = "- [ ] milk /dutom"
        val fieldStage = assertNotNull(assertNotNull(open()).update("- [ ] milk /du", 14))
        val valueStage = stillOpen(fieldStage.accept())
        val typed = assertNotNull(valueStage.update(text, 17))
        assertEquals("tom", typed.fragment)
        val acceptance = accepted(typed.accept())
        assertEquals(11..16, acceptance.replaceRange)
        assertEquals("/dutom", text.substring(11, 17))
        assertEquals("- [ ] milk due:2026-09-02 ", written(text, acceptance))
    }

    @Test
    fun anEmptyCandidateSetDismisses() {
        val session = stillOpen(assertNotNull(open()).accept())
        assertNull(session.update("- [ ] milk /zzz", 15))
    }

    @Test
    fun deletingTheSlashDismisses() {
        val session = assertNotNull(open())
        assertNull(session.update("- [ ] milk ", 11))
    }

    @Test
    fun movingTheCaretBackPastTheFragmentDismisses() {
        val session = stillOpen(assertNotNull(open()).accept())
        assertNull(session.update("- [ ] milk /tom", 11))
    }

    @Test
    fun losingTheTaskMarkerDismisses() {
        // The line stops being a task line under the menu: same shape,
        // same offsets, no marker.
        val session = stillOpen(assertNotNull(open()).accept())
        assertNull(session.update("- ( ) milk /tom", 15))
    }

    // ---- the keyboard ---------------------------------------------------

    @Test
    fun arrowsWrapThroughTheCandidates() {
        val session = stillOpen(assertNotNull(open()).accept())
        assertEquals(11, session.candidates.size)
        assertEquals(0, session.selected)
        assertEquals(1, session.moveDown().selected)
        assertEquals(10, session.moveUp().selected)
        assertEquals(0, session.moveUp().moveDown().selected)
        assertEquals(0, session.moveDown().moveUp().selected)
        // Down off the end comes back to the top.
        val last = (1..10).fold(session) { acc, _ -> acc.moveDown() }
        assertEquals(10, last.selected)
        assertEquals(0, last.moveDown().selected)
    }

    @Test
    fun theHighlightedRowIsWhatEnterWrites() {
        val session = stillOpen(assertNotNull(open()).accept()).moveDown()
        assertEquals("tomorrow", session.selectedCandidate?.display)
        assertEquals("due:2026-09-02 ", accepted(session.accept()).text)
    }

    @Test
    fun narrowingPutsTheHighlightBackOnTheFirstRow() {
        val session = stillOpen(assertNotNull(open()).accept()).moveDown().moveDown()
        val narrowed = assertNotNull(session.update("- [ ] milk /t", 13))
        assertEquals(0, narrowed.selected)
    }

    @Test
    fun aTransitionNeverMutatesTheSessionItCameFrom() {
        val session = stillOpen(assertNotNull(open()).accept())
        session.moveDown().moveDown()
        session.update("- [ ] milk /tom", 15)
        assertEquals(0, session.selected)
        assertEquals("", session.fragment)
    }

    // ---- what reaches the file -------------------------------------------

    @Test
    fun anUnresolvableRowWritesNothingAndKeepsTheMenuUp() {
        val session = stillOpen(assertNotNull(open()).accept())
        val typing = assertNotNull(session.update("- [ ] milk /2026-09", 19))
        assertEquals(1, typing.candidates.size)
        assertNull(typing.candidates.single().insert)
        // Enter is swallowed: § 2's "nothing unresolvable is inserted".
        assertEquals(typing.candidates, stillOpen(typing.accept()).candidates)
    }

    @Test
    fun aTypedIsoDateIsAcceptedVerbatim() {
        val session = stillOpen(assertNotNull(open()).accept())
        val typed = assertNotNull(session.update("- [ ] milk /2026-12-25", 22))
        assertEquals("due:2026-12-25 ", accepted(typed.accept()).text)
    }

    @Test
    fun everyAcceptanceWritesTheTokenAndOneSpace() {
        val session = stillOpen(assertNotNull(open()).accept())
        for (index in session.candidates.indices) {
            val row = (1..index).fold(session) { acc, _ -> acc.moveDown() }
            val text = accepted(row.accept()).text
            assertTrue(text.startsWith("due:"), "$text is a due token")
            assertTrue(text.endsWith(" "), "$text ends with its separator")
            assertEquals(text.trim(), row.selectedCandidate?.insert)
        }
    }

    @Test
    fun openAtReportsDocumentOffsets() {
        val text = "notes\n- [ ] milk /\nmore\n"
        val session = assertNotNull(SuggestSession.openAt(text, 18, fields, "", today))
        assertEquals(17, session.trigger)
        assertEquals(17..17, session.replaceRange)
        val values = stillOpen(session.accept())
        val typed = assertNotNull(values.updateAt("notes\n- [ ] milk /tod\nmore\n", 21))
        val acceptance = accepted(typed.accept())
        assertEquals(17..20, acceptance.replaceRange)
        assertEquals("notes\n- [ ] milk due:2026-09-01 \nmore\n", written("notes\n- [ ] milk /tod\nmore\n", acceptance))
    }

    @Test
    fun aCaretOnAnotherLineEndsTheSession() {
        val text = "notes\n- [ ] milk /\nmore\n"
        val session = assertNotNull(SuggestSession.openAt(text, 18, fields, "", today))
        assertNull(session.updateAt(text, 2))
    }

    // ---- the range primitive ---------------------------------------------

    @Test
    fun replaceRangePutsTheCaretAfterWhatItWrote() {
        val value = TextFieldValue("- [ ] milk /tom", TextRange(15))
        val written = replaceRange(value, 11..14, "due:2026-09-02 ")
        assertEquals("- [ ] milk due:2026-09-02 ", written.text)
        assertEquals(TextRange(26), written.selection)
        assertEquals(written.text.length, written.selection.start)
    }

    @Test
    fun replaceRangeNeitherTrimsNorAutoSpaces() {
        // `insertAtCaret` (desktop) trims its input and adds a separating
        // space when the character before the caret is not whitespace.
        // Neither happens here, which is why § 5 asks for a second
        // primitive rather than a flag on the first.
        val value = TextFieldValue("ab/cd", TextRange(3))
        val written = replaceRange(value, 2..2, "  X  ")
        assertEquals("ab  X  cd", written.text)
        assertEquals(TextRange(7), written.selection)
    }

    @Test
    fun replaceRangeIsExactAtBothEnds() {
        val value = TextFieldValue("0123456789")
        assertEquals("0z9", replaceRange(value, 1..8, "z").text)
        assertEquals("z123456789", replaceRange(value, 0..0, "z").text)
        assertEquals("012345678z", replaceRange(value, 9..9, "z").text)
    }

    @Test
    fun escapeLeavesTheLineExactlyAsTypedAndReconstructible() {
        // Dismissal is dropping the session: nothing was ever removed
        // from the buffer, so there is nothing to put back. What the user
        // typed is precisely the span an acceptance would have replaced.
        val text = "- [ ] milk /tom"
        val session = stillOpen(assertNotNull(open()).accept())
        val typed = assertNotNull(session.update(text, 15))
        val range = typed.replaceRange
        assertEquals("/tom", text.substring(range.first, range.last + 1))
        assertEquals(text, "- [ ] milk /tom")
    }

    private fun written(text: String, acceptance: Acceptance): String =
        replaceRange(TextFieldValue(text), acceptance.replaceRange, acceptance.text).text
}
