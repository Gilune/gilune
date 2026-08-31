// The rail lists what is on disk, and a collapsed folder stays closed —
// the vault is a real folder tree that other tools also write to, so the
// listing is never a cache of one.

package org.gilune.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultTreeTest {
    private fun vault(): String {
        val root = Files.createTempDirectory("gilune-tree").toFile()
        root.resolve("Captures").mkdirs()
        root.resolve("Captures/one.md").writeText("one")
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
}
