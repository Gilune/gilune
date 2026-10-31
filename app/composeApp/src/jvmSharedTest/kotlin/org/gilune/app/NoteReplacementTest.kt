// Replacing across notes on the refactoring journal (ADR 0029 § 4). The
// step past the obvious: front matter that holds the pattern is left alone;
// a note that changed after the preview, or has unsaved edits, is refused
// while the rest are written; the search narrows the scope; and undo puts
// back exactly what was changed.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gilune.plugins.findreplace.FindOptions
import org.gilune.plugins.findreplace.FindQuery
import org.gilune.plugins.findreplace.findReplaceExtensions
import uniffi.gilune_ffi.Gilune

class NoteReplacementTest {
    private fun vault(notes: Map<String, String>, run: (File, Gilune) -> Unit) {
        val root = Files.createTempDirectory("gilune-replace").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            PluginExtensions(listOf(findReplaceExtensions())).registerPlugins(kernel)
            notes.forEach { (path, text) -> File(root, path).apply { parentFile.mkdirs() }.writeText(text) }
            // The app reconciles as files arrive; a preview reads notes the
            // kernel already holds.
            kernel.reconcile()
            kernel.project()
            kernel.advanceIndex()
            run(root, kernel)
        }
    }

    private fun body(root: File, path: String) = File(root, path).readText().substringAfter("\n---\n")

    @Test
    fun replacesInBodiesOnlyAndUndoPutsThemBack() = vault(
        mapOf(
            "a.md" to "---\ntopic: cat\n---\ncat and Cat\n",
            "b.md" to "nothing here\n",
            "Work/c.md" to "one cat\n",
        ),
    ) { root, kernel ->
        val query = FindQuery("cat", FindOptions(wholeWord = true))
        val before = listOf("a.md", "b.md", "Work/c.md").associateWith { File(root, it).readText() }
        val preview = findInNotes(root.path, vaultMarkdown(root.path), query, "dog")
        assertEquals(listOf("Work/c.md", "a.md"), preview.notes.map { it.relPath })
        assertEquals(3, preview.matchCount, "the property holding the word is not a match")

        val done = replaceInNotes(kernel, root.path, preview.notes, query, "dog")
        assertEquals(3, done.replaced)
        assertEquals(2, done.notes)
        assertEquals(emptyList(), done.skipped)
        assertTrue(File(root, "a.md").readText().contains("topic: cat"), "front matter untouched")
        assertEquals("dog and dog\n", body(root, "a.md"))
        assertEquals("one dog\n", body(root, "Work/c.md"))
        assertEquals(before["b.md"], File(root, "b.md").readText())

        // Undo is a new revision of each managed note, so the revision stamp
        // in its header moves on; the words go back exactly.
        undoNotesReplacement(kernel, root.path, done.plan.id)
        for ((path, text) in before) assertEquals(text.substringAfter("\n---\n"), body(root, path), path)
        assertTrue(File(root, "a.md").readText().contains("topic: cat"))
    }

    @Test
    fun aNoteChangedAfterThePreviewIsRefusedAndTheRestAreWritten() = vault(
        mapOf("a.md" to "red\n", "b.md" to "red\n"),
    ) { root, kernel ->
        val query = FindQuery("red")
        val preview = findInNotes(root.path, vaultMarkdown(root.path), query, "blue")
        File(root, "b.md").appendText("red again\n")

        val done = replaceInNotes(kernel, root.path, preview.notes, query, "blue")
        assertEquals(1, done.notes)
        assertEquals(listOf("b.md"), done.skipped.map { it.first })
        assertTrue(done.skipped.single().second.contains("changed after the preview"), done.skipped.single().second)
        assertTrue(File(root, "a.md").readText().endsWith("blue\n"))
        assertTrue(File(root, "b.md").readText().endsWith("red\nred again\n"), "the newer words survive")
    }

    @Test
    fun aNoteWithUnsavedEditsIsMarkedAndRefused() = vault(mapOf("a.md" to "red\n")) { root, kernel ->
        val buffer = object : LiveDocumentBuffer {
            override val documentFile = File(root, "a.md").absoluteFile.normalize().path
            override val hasUnsavedText = true
        }
        DocumentEditGate.register(buffer)
        val query = FindQuery("red")
        val preview = findInNotes(root.path, vaultMarkdown(root.path), query, "blue")
        assertTrue(preview.notes.single().unsaved)

        val done = replaceInNotes(kernel, root.path, preview.notes, query, "blue")
        assertEquals(0, done.notes)
        assertTrue(File(root, "a.md").readText().endsWith("red\n"))
        assertEquals(buffer.documentFile, buffer.documentFile) // held until here, so the gate still sees it
    }

    @Test
    fun theSearchNarrowsWhichNotesAreInScope() = vault(
        mapOf("Work/a.md" to "red\n", "Home/b.md" to "red\n"),
    ) { root, kernel ->
        val scoped = notesInScope(kernel, root.path, "path:Work", SearchOperators(), listOf(NOTE_SEARCH_TYPE), LocalDate.now())
        assertEquals(listOf("Work/a.md"), scoped)
        assertEquals(listOf("Home/b.md", "Work/a.md"), notesInScope(kernel, root.path, "", SearchOperators(), listOf(NOTE_SEARCH_TYPE), LocalDate.now()))
    }

    @Test
    fun editsAreExactByteSpansAfterMultibyteText() {
        val source = "---\nt: é\n---\nçà red 🙂 red"
        val bodyStart = source.indexOf("çà")
        val matches = org.gilune.plugins.findreplace.findMatches(source.substring(bodyStart), FindQuery("red"), "x").let {
            (it as org.gilune.plugins.findreplace.FindOutcome.Found).matches
        }
        val edits = bodyEdits(source, bodyStart, matches)
        val bytes = source.toByteArray(Charsets.UTF_8)
        for (edit in edits) {
            assertEquals("red", String(bytes, edit.range.start.toInt(), (edit.range.end - edit.range.start).toInt(), Charsets.UTF_8))
        }
    }
}
