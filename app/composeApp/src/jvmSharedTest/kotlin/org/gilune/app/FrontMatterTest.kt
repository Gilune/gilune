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
    fun systemLinesSurviveByteIdenticalWhenCustomKeysChange() {
        val raw = "---\ngilune-id:   0193f0c2-9a\ntags: kitchen\ngilune-type: org.memory.note\n---\n"
        val edited = applyFrontMatterEdits(raw, listOf("tags" to "kitchen, wiring", "status" to "open"))
        assertTrue(
            edited.contains("\ngilune-id:   0193f0c2-9a\n"),
            "a system line is contract; its bytes are carried over, spacing included",
        )
        assertTrue(edited.contains("\ngilune-type: org.memory.note\n"))
        assertEquals(
            listOf("gilune-id", "gilune-type", "tags", "status"),
            splitNote(edited).frontMatter.map { it.key },
            "system keys keep their order and custom keys follow them",
        )
    }

    @Test
    fun customKeysRenderInTheOrderGiven() {
        val edited = applyFrontMatterEdits(
            "---\ngilune-id: abc\n---\n",
            listOf("zeta" to "1", "alpha" to "2"),
        )
        assertEquals("---\ngilune-id: abc\nzeta: 1\nalpha: 2\n---\n", edited)
    }

    @Test
    fun offeringSystemKeysBackDoesNotDuplicateThem() {
        val raw = "---\ngilune-id: abc\n---\n"
        val edited = applyFrontMatterEdits(raw, listOf("gilune-id" to "tampered", "tags" to "kitchen"))
        assertEquals("---\ngilune-id: abc\ntags: kitchen\n---\n", edited)
    }

    @Test
    fun removingEveryCustomKeyFromANoteWithoutSystemKeysRemovesTheBlock() {
        val raw = "---\ntags: kitchen\n---\n"
        assertEquals("", applyFrontMatterEdits(raw, emptyList()))
        assertEquals("# Kitchen\n", assembleNote(applyFrontMatterEdits(raw, emptyList()), "# Kitchen\n"))
    }

    @Test
    fun removingEveryCustomKeyKeepsABlockThatStillHasSystemKeys() {
        val raw = "---\ngilune-id: abc\ntags: kitchen\n---\n"
        assertEquals("---\ngilune-id: abc\n---\n", applyFrontMatterEdits(raw, emptyList()))
    }

    @Test
    fun addingAPropertyToANoteWithoutFrontMatterCreatesAWellFormedBlock() {
        val body = "# Kitchen\n"
        val header = applyFrontMatterEdits("", listOf("tags" to "kitchen"))
        val note = assembleNote(header, body)
        assertEquals("---\ntags: kitchen\n---\n# Kitchen\n", note)
        assertEquals(header to body, splitNoteRaw(note))
    }

    @Test
    fun aBlankKeyIsNotWrittenAsAProperty() {
        assertEquals("", applyFrontMatterEdits("", listOf("  " to "orphan")))
    }
}
