package org.gilune.app

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/// The formatting arithmetic both shells share: the phone's toolbar and the
/// desktop's shortcuts. Moved here from MobileEditorTest (2026-10-26) so it
/// runs with every desktop suite, not only on a device.
class EditorEditsTest {
    @Test
    fun boldWrapsTheSelectionAndUnwrapsItAgain() {
        val wrapped = emphasisEdit("milk and bread", TextRange(0, 4), "**")
        assertEquals(BufferEdit(0, 4, "**milk**", TextRange(2, 6)), wrapped)
        assertEquals("**milk** and bread", applied("milk and bread", wrapped))

        val unwrapped = emphasisEdit("**milk** and bread", TextRange(2, 6), "**")
        assertEquals("milk and bread", applied("**milk** and bread", unwrapped))
        assertEquals(TextRange(0, 4), unwrapped.selection)
    }

    @Test
    fun boldUnwrapsASelectionThatIncludesItsMarkers() {
        val edit = emphasisEdit("**milk** and bread", TextRange(0, 8), "**")
        assertEquals("milk and bread", applied("**milk** and bread", edit))
        assertEquals(TextRange(0, 4), edit.selection)
    }

    @Test
    fun italicInsideBoldAddsItalicRatherThanStrippingAStar() {
        val text = "**milk** and bread"
        val italic = emphasisEdit(text, TextRange(2, 6), "*")
        assertEquals("***milk*** and bread", applied(text, italic))
        // And back: italic comes off and the bold stays.
        assertEquals(text, applied("***milk*** and bread", emphasisEdit("***milk*** and bread", TextRange(3, 7), "*")))
        // Bold off a bold-italic word leaves the italic.
        assertEquals("*milk* and bread", applied("***milk*** and bread", emphasisEdit("***milk*** and bread", TextRange(3, 7), "**")))
        // A whole selected bold word gains italic too.
        assertEquals("***milk*** and bread", applied(text, emphasisEdit(text, TextRange(0, 8), "*")))
    }

    @Test
    fun pressingBoldTwiceOnAnEmptyLineLeavesItEmpty() {
        val once = emphasisEdit("", TextRange(0), "**")
        val twice = emphasisEdit("****", once.selection, "**")
        assertEquals("", applied("****", twice))
    }

    @Test
    fun boldWithNoSelectionTakesTheWordUnderTheCaret() {
        val edit = emphasisEdit("milk and bread", TextRange(6), "**")
        assertEquals("milk **and** bread", applied("milk and bread", edit))
    }

    @Test
    fun boldOnAnEmptyLineWritesTheMarkersAndSitsBetweenThem() {
        val edit = emphasisEdit("", TextRange(0), "**")
        assertEquals("****", applied("", edit))
        assertEquals(TextRange(2), edit.selection)
    }

    @Test
    fun italicAndStrikeAndCodeAreTheSameToggle() {
        assertEquals("*milk*", applied("milk", emphasisEdit("milk", TextRange(0, 4), "*")))
        assertEquals("~~milk~~", applied("milk", emphasisEdit("milk", TextRange(0, 4), "~~")))
        assertEquals("`milk`", applied("milk", emphasisEdit("milk", TextRange(0, 4), "`")))
    }

    @Test
    fun theListButtonTogglesABulletOnEveryLineItTouches() {
        val on = bulletEdit("milk\nbread", TextRange(0, 10))
        assertEquals("- milk\n- bread", applied("milk\nbread", on))
        val off = bulletEdit("- milk\n- bread", TextRange(0, 14))
        assertEquals("milk\nbread", applied("- milk\n- bread", off))
    }

    @Test
    fun indentAddsTwoSpacesAndOutdentNeverEatsACharacter() {
        assertEquals("  milk", applied("milk", indentEdit("milk", TextRange(0), outdent = false)))
        assertEquals("milk", applied("  milk", indentEdit("  milk", TextRange(0), outdent = true)))
        // Nothing to remove: the line comes back exactly as it was.
        assertEquals("milk", applied("milk", indentEdit("milk", TextRange(0), outdent = true)))
    }

    @Test
    fun headingsToggleAtTheirOwnLevel() {
        assertEquals("# Title", applied("Title", headingEdit("Title", TextRange(0), 1)))
        assertEquals("## Title", applied("# Title", headingEdit("# Title", TextRange(0), 2)))
        assertEquals("Title", applied("# Title", headingEdit("# Title", TextRange(0), 1)))
    }

    @Test
    fun moveLineSwapsWithItsNeighbourAndCarriesTheCaret() {
        val down = assertNotNull(moveLineEdit("one\ntwo\nthree", TextRange(1), up = false))
        assertEquals("two\none\nthree", applied("one\ntwo\nthree", down))
        assertEquals(TextRange(5), down.selection)

        val up = assertNotNull(moveLineEdit("one\ntwo\nthree", TextRange(5), up = true))
        assertEquals("two\none\nthree", applied("one\ntwo\nthree", up))
        // The first line has nowhere to go, and says so rather than
        // rewriting the document to no effect.
        assertNull(moveLineEdit("one\ntwo", TextRange(0), up = true))
        assertNull(moveLineEdit("one\ntwo", TextRange(5), up = false))
    }

    @Test
    fun aLinkTakesTheSelectionAsItsLabelAndLandsTheCaretInTheDestination() {
        val withLabel = linkEdit("see milk", TextRange(4, 8))
        assertEquals("see [milk]()", applied("see milk", withLabel))
        assertEquals(TextRange(11), withLabel.selection)

        val empty = linkEdit("", TextRange(0))
        assertEquals("[]()", applied("", empty))
        assertEquals(TextRange(1), empty.selection)
    }

    private fun applied(text: String, edit: BufferEdit): String =
        text.substring(0, edit.start) + edit.replacement + text.substring(edit.end)
}
