// Front matter is machine contract: the editor holds it apart from the
// body it lets you type into, and the bytes the user did not change must
// come back unchanged (ADR 0012).

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrontMatterTest {
    private fun roundTrips(text: String) {
        val (header, body) = splitNoteRaw(text)
        assertEquals(text, assembleNote(header, body), "the split is lossless")
    }

    @Test
    fun theFrontMatterBlockKeepsBothFencesAndTheNewlineAfterTheClosingOne() {
        val text = "---\ngilune-id: abc\n---\n# Kitchen\n\nrewire the ring main\n"
        val (header, body) = splitNoteRaw(text)
        assertEquals("---\ngilune-id: abc\n---\n", header)
        assertEquals("# Kitchen\n\nrewire the ring main\n", body)
        roundTrips(text)
    }

    @Test
    fun aNoteWithoutFrontMatterIsAllBody() {
        val text = "# Kitchen\n\nrewire the ring main\n"
        assertEquals("" to text, splitNoteRaw(text))
        roundTrips(text)
    }

    @Test
    fun aNoteThatIsOnlyFrontMatterHasAnEmptyBody() {
        val text = "---\ngilune-id: abc\ngilune-type: org.memory.note\n---\n"
        assertEquals(text to "", splitNoteRaw(text))
        roundTrips(text)
    }

    @Test
    fun anEmptyFileRoundTrips() {
        assertEquals("" to "", splitNoteRaw(""))
        roundTrips("")
    }

    @Test
    fun aRuleInTheBodyIsNotMistakenForFrontMatter() {
        val text = "# Kitchen\n\n---\n\nrewire the ring main\n"
        assertEquals("" to text, splitNoteRaw(text))
        roundTrips(text)
    }

    @Test
    fun anUnclosedOpeningFenceLeavesTheWholeFileAsBody() {
        val text = "---\ngilune-id: abc\nno closing fence\n"
        assertEquals("" to text, splitNoteRaw(text))
        roundTrips(text)
    }

    @Test
    fun aFileThatEndsOnItsClosingFenceRoundTrips() {
        val text = "---\ngilune-id: abc\n---"
        assertEquals(text to "", splitNoteRaw(text))
        roundTrips(text)
    }

    @Test
    fun lfContentIsCarriedThroughUntouched() {
        val text = "---\ngilune-id: abc\n---\nline one\n\nline three\n\n\n"
        roundTrips(text)
        assertEquals("line one\n\nline three\n\n\n", splitNoteRaw(text).second)
    }

    @Test
    fun theBodyTheEditorHoldsMatchesTheOneTheRendererParses() {
        val text = "---\ngilune-id: abc\n---\n# Kitchen\n\nrewire\n"
        assertEquals(splitNote(text).body, splitNoteRaw(text).second)
    }

    @Test
    fun bomStaysInThePreludeWhenANoteHasNoHeader() {
        val text = "\uFEFFbody\r\n"
        roundTrips(text)
        assertEquals("\uFEFF" to "body\r\n", splitNoteRaw(text))
    }

    @Test
    fun bomAndCrLfArePartOfThePreservedHeader() {
        val text = "\uFEFF---\r\nname: Alice\r\n---\r\nbody\r\n"
        roundTrips(text)
        assertEquals("body\r\n", splitNoteRaw(text).second)
        assertTrue(splitNoteRaw(text).first.startsWith("\uFEFF"))
    }
}
