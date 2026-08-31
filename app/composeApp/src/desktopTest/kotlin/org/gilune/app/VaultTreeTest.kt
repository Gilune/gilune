// The rail lists what is on disk, and a collapsed folder stays closed —
// the vault is a real folder tree that other tools also write to, so the
// listing is never a cache of one. Captured audio is listed as its note's
// attachment, folded away until the note is expanded. Creating a note
// asks for no name, so the names it picks are a specification too.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultTreeTest {
    private fun vault(): String {
        val root = Files.createTempDirectory("gilune-tree").toFile()
        root.resolve("Captures").mkdirs()
        root.resolve("Captures/one.md").writeText("one")
        root.resolve("Captures/one.wav").writeText("audio")
        root.resolve("Captures/nobody.wav").writeText("audio")
        root.resolve("Projects/House").mkdirs()
        root.resolve("Projects/House/rewire.md").writeText("rewire")
        root.resolve(".gilune/local").mkdirs()
        root.resolve("loose.md").writeText("loose")
        return root.absolutePath
    }

    @Test
    fun collapsedFoldersHideTheirContents() {
        val listing = listVault(vault())
        assertEquals(listOf("Captures", "Projects", "loose.md"), listing.map { it.relPath })
        assertTrue(listing.first { it.relPath == "Captures" }.isDir)
    }

    @Test
    fun expandingAFolderRevealsItsChildrenAtTheRightDepth() {
        val listing = listVault(vault(), expanded = setOf("Projects", "Projects/House"))
        assertEquals(
            listOf("Captures", "Projects", "Projects/House", "Projects/House/rewire.md", "loose.md"),
            listing.map { it.relPath },
        )
        assertEquals(2, listing.first { it.relPath.endsWith("rewire.md") }.depth)
    }

    @Test
    fun kernelStateIsNeverListedAsVaultContent() {
        assertTrue(listVault(vault(), expanded = setOf(".gilune")).none { it.relPath.startsWith(".") })
    }

    @Test
    fun aNotesAttachmentsStayHiddenUntilTheNoteIsExpanded() {
        val listing = listVault(vault(), expanded = setOf("Captures"))
        assertEquals(listOf("Captures", "Captures/one.md", "Projects", "loose.md"), listing.map { it.relPath })
        assertTrue(listing.first { it.relPath == "Captures/one.md" }.hasAttachments)
    }

    @Test
    fun expandingANoteListsItsAttachmentsBeneathItOneLevelIn() {
        val listing = listVault(vault(), expanded = setOf("Captures", "Captures/one.md"))
        assertEquals(
            listOf("Captures", "Captures/one.md", "Captures/one.wav", "Projects", "loose.md"),
            listing.map { it.relPath },
        )
        val note = listing.first { it.relPath == "Captures/one.md" }
        val attachment = listing.first { it.relPath == "Captures/one.wav" }
        assertEquals(VaultEntryKind.NOTE, note.kind)
        assertEquals(VaultEntryKind.ATTACHMENT, attachment.kind)
        assertEquals(note.depth + 1, attachment.depth)
    }

    @Test
    fun nonMarkdownFilesNoNoteOwnsAreListedNowhere() {
        val listing = listVault(
            vault(),
            expanded = setOf("Captures", "Captures/one.md", "Captures/nobody.wav"),
        )
        assertTrue(listing.none { it.relPath == "Captures/nobody.wav" })
    }

    @Test
    fun aNoteWithoutAttachmentsHasNothingToExpand() {
        val note = listVault(vault()).first { it.relPath == "loose.md" }
        assertEquals(VaultEntryKind.NOTE, note.kind)
        assertFalse(note.hasAttachments)
    }

    @Test
    fun directoriesSortBeforeFilesRegardlessOfName() {
        val dir = vault()
        File(dir, "Zebra").mkdirs()
        val listing = listVault(dir).map { it.relPath }
        assertEquals(listOf("Captures", "Projects", "Zebra", "loose.md"), listing)
    }

    @Test
    fun theFirstNewNoteInAFolderIsSimplyUntitled() {
        val dir = vault()
        assertEquals("Untitled", untitledName(File(dir, "Projects/House")))
    }

    @Test
    fun untitledNamesStepPastEveryNameAlreadyOnDisk() {
        val dir = File(vault(), "Captures")
        File(dir, "Untitled.md").writeText("")
        assertEquals("Untitled 1", untitledName(dir))
        File(dir, "Untitled 1.md").writeText("")
        assertEquals("Untitled 2", untitledName(dir))
    }

    @Test
    fun creatingANoteAsksForNoNameAndLeavesAnEmptyFileInTheTargetFolder() {
        val dir = vault()
        val relPath = createUntitledNote(dir, "Projects/House")
        assertEquals("Projects/House/Untitled.md", relPath)
        assertEquals("", File(dir, relPath).readText())
    }

    @Test
    fun aSecondNewNoteNeverOverwritesTheFirst() {
        val dir = vault()
        val first = createUntitledNote(dir, "")
        File(dir, first).writeText("kept")
        val second = createUntitledNote(dir, "")
        assertEquals(listOf("Untitled.md", "Untitled 1.md"), listOf(first, second))
        assertEquals("kept", File(dir, first).readText())
    }
}
