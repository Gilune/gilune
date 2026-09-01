// The phone's read of the vault, and the one write MB1 performs.
//
// The toggle is the interesting half: a tap on a rendered glyph must
// change three characters and no others, must go through the shared
// `activateLine` so the tasks plugin's `done:` stamp applies, and must
// decline rather than clobber when the file moved under the reader.

package org.gilune.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultFilesTest {
    private val note = "---\ngilune-id: abc\n---\n# List\n\n- [ ] milk\n- [x] bread\n\nprose\n"

    private fun toggle(text: String, line: Int, hashAtRead: String = contentHash(text)) =
        toggleTaskAtLine(text, hashAtRead, contentHash(text), line, decorations = builtInDecorations())

    @Test
    fun tickingABoxChangesThreeCharactersAndNoOthers() {
        val toggled = toggle(note, 2)!!
        assertEquals(note.replace("- [ ] milk", "- [x] milk"), toggled)
        assertEquals(note.length, toggled.length)
    }

    @Test
    fun untickingIsTheSameMoveBackwards() {
        assertEquals(note.replace("- [x] bread", "- [ ] bread"), toggle(note, 3))
    }

    @Test
    fun frontMatterIsNeverTouched() {
        val toggled = toggle(note, 2)!!
        assertTrue(toggled.startsWith("---\ngilune-id: abc\n---\n"), "identity must survive a tick")
    }

    @Test
    fun theLineIndexIsIntoTheBodyNotTheFile() {
        // Body line 0 is `# List`; the file's line 0 is the fence. Getting
        // this wrong writes to the wrong line, which is the whole reason
        // the parser carries a line number rather than matching text.
        assertNull(toggle(note, 0), "a heading is not a task")
        assertEquals("- [ ] milk", splitNoteRaw(note).second.split('\n')[2])
    }

    @Test
    fun aLineNobodyClaimsIsLeftAlone() {
        assertNull(toggle(note, 4), "blank")
        assertNull(toggle(note, 5), "prose")
        assertNull(toggle(note, 99), "off the end of the note")
        assertNull(toggle(note, -1))
    }

    @Test
    fun aFileThatChangedSinceItWasReadIsNeverOverwritten() {
        assertNull(
            toggleTaskAtLine(note, contentHash("something else entirely"), contentHash(note), 2),
            "a stale tap must decline, not clobber",
        )
    }

    @Test
    fun aFileThatVanishedMayStillBeWritten() {
        // `mayOverwrite` treats a missing file as recreatable; the note is
        // still the note the reader had.
        assertEquals(
            note.replace("- [ ] milk", "- [x] milk"),
            toggleTaskAtLine(note, contentHash(note), null, 2),
        )
    }

    @Test
    fun theTasksPluginStampsThroughTheSameCall() {
        // The toggle folds the whole decoration chain, so a registered
        // stamper writes its date on the same tap the desktop stamps on.
        val chain = builtInDecorations() + tasksDecorations(today = java.time.LocalDate.of(2026, 9, 1))
        val stamped = toggleTaskAtLine(note, contentHash(note), contentHash(note), 2, chain)!!
        assertTrue(stamped.contains("- [x] milk done:2026-09-01"), "got: $stamped")
    }

    @Test
    fun aNoteWithNoFrontMatterTogglesTheSameWay() {
        val plain = "- [ ] alone\n"
        assertEquals("- [x] alone\n", toggle(plain, 0))
    }

    @Test
    fun everyMarkdownFileIsListedAndKernelStateIsNot() {
        val root = Files.createTempDirectory("gilune-list").toFile()
        root.resolve("Captures").mkdirs()
        root.resolve(".gilune").mkdirs()
        root.resolve("Captures/spoken.md").writeText("x")
        root.resolve("top.md").writeText("x")
        root.resolve("Captures/spoken.wav").writeText("x")
        root.resolve(".gilune/state.md").writeText("x")

        val notes = listNotes(root.absolutePath)
        assertEquals(listOf("top.md", "Captures/spoken.md"), notes.map { it.relPath })
        assertEquals("", notes[0].folder)
        assertEquals("Captures", notes[1].folder)
        assertEquals("spoken", notes[1].name)
    }

    @Test
    fun anAbsentVaultListsNothing() {
        assertEquals(emptyList(), listNotes("/no/such/vault"))
    }

    @Test
    fun theTitleIsTheFileName() {
        assertEquals("rewire", noteTitle("Projects/Kitchen/rewire.md"))
        assertEquals("top", noteTitle("top.md"))
    }
}
