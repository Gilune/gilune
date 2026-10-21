// Return on a list line continues the list, and Return on an empty item
// lets go of it (owner rule, 2026-09-01). Both editors ask this one
// function, so the rule cannot be true on one platform and not the other
// — which is the whole reason it is arithmetic over a string rather than
// a key handler in two places.
//
// The matrix below is the rule. Everything the editors add on top is
// wiring: when to ask (a newline was typed, and not by the `/` menu) and
// how to write the answer down.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListContinuationTest {
    /// The buffer as it stands the instant after Return: the newline is in
    /// the text and the caret is just past it. `|` marks the caret.
    private fun afterReturn(marked: String): Pair<String, Int> {
        val caret = marked.indexOf('|')
        val text = marked.replace("|", "")
        return text to caret
    }

    private fun decide(marked: String): ListContinuation? {
        val (text, caret) = afterReturn(marked)
        return listContinuation(text, caret)
    }

    /// The decision applied, written back with the caret marked again, so
    /// a case reads as the keystroke's whole effect rather than as a
    /// prefix string.
    private fun press(marked: String): String {
        val (text, caret) = afterReturn(marked)
        val decision = listContinuation(text, caret) ?: return marked
        val (written, moved) = decision.applyTo(text, caret)
        return written.substring(0, moved) + "|" + written.substring(moved)
    }

    private fun continuation(marked: String): String {
        val decision = decide(marked)
        assertTrue(decision is ListContinuation.Continue, "expected a continuation, got $decision")
        return decision.prefix
    }

    // ------------------------------------------------------------ continue

    @Test
    fun aTaskLineWithContentContinuesAsAFreshUncheckedTask() {
        assertEquals("- [ ] ", continuation("- [ ] milk\n|"))
        assertEquals("- [ ] milk\n- [ ] |", press("- [ ] milk\n|"))
    }

    @Test
    fun aTickedTaskContinuesUnticked() {
        // The next thing you write down is not already done.
        assertEquals("- [ ] ", continuation("- [x] milk\n|"))
        assertEquals("- [ ] ", continuation("- [X] milk\n|"))
    }

    @Test
    fun aPlainBulletContinuesAsABullet() {
        assertEquals("- ", continuation("- milk\n|"))
        assertEquals("* ", continuation("* milk\n|"))
        assertEquals("+ ", continuation("+ milk\n|"))
    }

    @Test
    fun indentationAndTheBulletCharacterAreTheItemsOwn() {
        assertEquals("  * [ ] ", continuation("  * [ ] milk\n|"))
        assertEquals("\t- ", continuation("\t- milk\n|"))
        // The space between bullet and marker is the line's, not a
        // convention imposed on it.
        assertEquals("-  [ ] ", continuation("-  [ ] milk\n|"))
    }

    @Test
    fun continuingHappensWhereverTheItemSitsInTheDocument() {
        assertEquals(
            "# Shopping\n\n- [ ] milk\n- [ ] |\n- [ ] bread\n",
            press("# Shopping\n\n- [ ] milk\n|\n- [ ] bread\n"),
        )
    }

    // -------------------------------------------------------------- unwrap

    @Test
    fun anEmptyTaskItemLetsGoOfTheList() {
        // A list must never trap you: the marker goes, the caret comes
        // back onto the line it was on, and no blank line is left over.
        assertEquals(ListContinuation.Unwrap(0 until 7), decide("- [ ] \n|"))
        assertEquals("|", press("- [ ] \n|"))
    }

    @Test
    fun anEmptyBulletLetsGoTheSameWay() {
        assertEquals("|", press("- \n|"))
    }

    @Test
    fun anEmptyNestedItemStepsOutALevelBeforeTheListLetsGo() {
        // Owner, 2026-10-21 (subtasks): a child, Return, Return is the next
        // parent — the nested empty item moves out one level, marker kept.
        assertEquals("* |", press("  * \n|"))
        assertEquals("- [ ] |", press("  - [ ] \n|"))
        assertEquals("  - [ ] |", press("    - [ ] \n|"), "one level at a time")
        assertEquals("- [ ] |", press("\t- [ ]\n|"), "a tab is one level too")
    }

    @Test
    fun aTaskMarkerWithNoTrailingSpaceIsStillAnEmptyItem() {
        assertEquals("|", press("- [ ]\n|"))
    }

    @Test
    fun aBulletWithNothingAfterItIsNotAnItemAtAll() {
        // The space is part of what makes a bullet a bullet, here as in
        // every other marker regex in the shell — the same rule that keeps
        // `- []` from being a task marker. A lone `-` is a line of text, so
        // Return on it is an ordinary Return.
        assertNull(decide("-\n|"))
        assertNull(decide("-milk\n|"))
    }

    @Test
    fun unwrappingLeavesEverythingElseWhereItWas() {
        assertEquals(
            "- [ ] milk\n|\nprose\n",
            press("- [ ] milk\n- [ ] \n|\nprose\n"),
        )
    }

    @Test
    fun anEmptyTickedItemUnwrapsRatherThanContinuing() {
        assertEquals("|", press("- [x] \n|"))
    }

    // ------------------------------------------------------- mid-item Return

    @Test
    fun returnInsideAnItemSplitsItAndTheRestFollowsANewMarker() {
        // Obsidian's behaviour: the text after the caret has already been
        // carried down by the break, so it lands after the new marker.
        assertEquals("- [ ] milk \n- [ ] |and bread", press("- [ ] milk \n|and bread"))
        assertEquals("- milk \n- |and bread", press("- milk \n|and bread"))
    }

    @Test
    fun theHalfAboveTheCaretIsNotTheWholeItem() {
        // The marker line is empty here, but the item is not: `milk` was
        // carried down by the same keystroke. Unwrapping would delete a
        // marker the user is still using.
        assertEquals("- [ ] \n- [ ] |milk", press("- [ ] \n|milk"))
        assertEquals("- \n- |milk", press("- \n|milk"))
    }

    @Test
    fun whitespaceAfterTheCaretIsNotContent() {
        // Trailing spaces carried down are still an empty item.
        assertEquals("|  ", press("- [ ] \n|  "))
    }

    // --------------------------------------------------------------- null

    @Test
    fun aProseLineContinuesNothing() {
        assertNull(decide("just words\n|"))
        assertNull(decide("# A heading\n|"))
        assertNull(decide("> a quote\n|"))
        assertNull(decide("\n|"))
    }

    @Test
    fun orderedListsAreOutOfScopeAsTheyAreForTheCheckbox() {
        assertNull(decide("1. milk\n|"))
    }

    @Test
    fun aListQuotedInsideAFenceIsText() {
        assertNull(decide("```\n- [ ] quoted\n|"))
        assertNull(decide("```sh\n- quoted\n|"))
        // The fence closed, so the list below it is a list again.
        assertEquals("- [ ] ", continuation("```\n- [ ] quoted\n```\n- [ ] real\n|"))
    }

    @Test
    fun anUnterminatedFenceRunsToTheEnd() {
        assertNull(decide("intro\n```\n- [ ] quoted\n|"))
    }

    @Test
    fun aCaretThatIsNotJustPastANewlineAnswersNothing() {
        assertNull(listContinuation("- [ ] milk\n", 0))
        assertNull(listContinuation("- [ ] milk\n", 5))
        assertNull(listContinuation("- [ ] milk\n", 99))
        assertNull(listContinuation("", 0))
    }

    // ------------------------------------------------- the typed-newline gate

    @Test
    fun oneNewlineTypedAtTheCaretIsTheOnlyTransitionThatCounts() {
        assertTrue(insertedNewline("- [ ] milk", 10, 10, "- [ ] milk\n", 11), "Return at the end")
        assertTrue(insertedNewline("- [ ] milk", 6, 6, "- [ ] \nmilk", 7), "Return mid-line")
        // A selection replaced by the break is still one newline typed.
        assertTrue(insertedNewline("- [ ] milk", 6, 10, "- [ ] \n", 7), "Return over a selection")
    }

    @Test
    fun anythingElseIsNotATypedNewline() {
        assertFalse(insertedNewline("- [ ] milk", 10, 10, "- [ ] milkx", 11), "an ordinary character")
        assertFalse(insertedNewline("- [ ] milk", 10, 10, "- [ ] milk\n\n", 12), "two newlines pasted")
        assertFalse(insertedNewline("- [ ] milk", 10, 10, "- [ ] milk", 10), "no change at all")
        assertFalse(insertedNewline("- [ ] milk\n", 11, 11, "- [ ] milk", 10), "a deletion")
        assertFalse(
            insertedNewline("- [ ] milk", 10, 10, "- [ ] oats\n", 11),
            "a newline plus a rewrite elsewhere",
        )
    }

    @Test
    fun theWholeGestureComposes() {
        // What each editor does, end to end: watch for the transition,
        // then ask the rule about the buffer the transition produced.
        val before = "- [ ] milk"
        val after = "- [ ] milk\n"
        assertTrue(insertedNewline(before, before.length, before.length, after, after.length))
        assertEquals(
            "- [ ] milk\n- [ ] ",
            listContinuation(after, after.length)!!.applyTo(after, after.length).text,
        )
    }
}

class IndentEditTest {
    @Test
    fun aCaretInsideTheLineMovesWithIt() {
        val edit = indentEdit("- [ ] child", 4, 4, outdent = false)
        assertEquals("  - [ ] child", edit.text)
        assertEquals(6, edit.selectionStart)
    }

    @Test
    fun outdentNeverPullsTheCaretBeforeItsLine() {
        val text = "a\n  - b"
        val edit = indentEdit(text, 3, 3, outdent = true) // between the two spaces
        assertEquals("a\n- b", edit.text)
        assertEquals(2, edit.selectionStart, "clamped to the line's start")
    }

    @Test
    fun aListQuotedInAFenceIsText() {
        val text = "```\n- [ ] quoted\n```"
        val edit = indentEdit(text, 6, 6, outdent = false)
        assertEquals(text.substring(0, 6) + LIST_INDENT + text.substring(6), edit.text,
            "Tab types indentation at the caret instead of nesting a quoted list")
    }

    @Test
    fun outdentAtTheTopLevelChangesNothing() {
        val edit = indentEdit("- [ ] top", 9, 9, outdent = true)
        assertEquals("- [ ] top", edit.text)
        assertEquals(9, edit.selectionStart)
    }
}
