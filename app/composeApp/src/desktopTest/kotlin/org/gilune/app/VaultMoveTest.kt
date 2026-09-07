package org.gilune.app

import java.nio.file.Files
import kotlin.test.*
import uniffi.gilune_ffi.Gilune

class VaultMoveTest {
    @Test
    fun movingANotePreservesItsBytesAndTakesEveryAttachment() {
        val root = Files.createTempDirectory("gilune-move").toFile()
        try {
            root.resolve("from").mkdir()
            root.resolve("to").mkdir()
            val files = mapOf("note.md" to "---\ngilune-id: kept\n---\nbody", "note.wav" to "audio", "note.png" to "image")
            files.forEach { (name, content) -> root.resolve("from/$name").writeText(content) }
            root.resolve("from/other.wav").writeText("unrelated")
            assertEquals("to/note.md", moveVaultEntry(root.path, "from/note.md", "to"))
            files.forEach { (name, content) ->
                assertEquals(content, root.resolve("to/$name").readText())
                assertFalse(root.resolve("from/$name").exists())
            }
            assertEquals("unrelated", root.resolve("from/other.wav").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun anAttachmentCollisionRefusesTheWholeMoveBeforeChangingAnyFile() {
        val root = Files.createTempDirectory("gilune-move").toFile()
        try {
            root.resolve("target").mkdir()
            root.resolve("note.md").writeText("note")
            root.resolve("note.wav").writeText("source audio")
            root.resolve("target/note.wav").writeText("destination audio")
            assertFailsWith<IllegalArgumentException> { moveVaultEntry(root.path, "note.md", "target") }
            assertEquals("note", root.resolve("note.md").readText())
            assertEquals("source audio", root.resolve("note.wav").readText())
            assertEquals("destination audio", root.resolve("target/note.wav").readText())
            assertFalse(root.resolve("target/note.md").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun foldersMoveToTheRootWithNestedAndHiddenContentIntact() {
        val root = Files.createTempDirectory("gilune-move").toFile()
        try {
            root.resolve("from/folder/nested/empty").mkdirs()
            root.resolve("from/folder/nested/note.md").writeText("body")
            root.resolve("from/folder/.kept").writeText("hidden")
            assertEquals("folder", moveVaultEntry(root.path, "from/folder", ""))
            assertEquals("body", root.resolve("folder/nested/note.md").readText())
            assertEquals("hidden", root.resolve("folder/.kept").readText())
            assertTrue(root.resolve("folder/nested/empty").isDirectory)
            assertFalse(root.resolve("from/folder").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun invalidDestinationsCannotMoveOrOverwriteContent() {
        val root = Files.createTempDirectory("gilune-move").toFile()
        try {
            root.resolve("folder/child").mkdirs()
            root.resolve("destination/folder").mkdirs()
            root.resolve(".gilune").mkdir()
            root.resolve("folder/note.md").writeText("kept")
            for (destination in listOf("folder", "folder/child", "destination", "", "../escape", ".gilune", "missing")) {
                assertFailsWith<IllegalArgumentException>(destination) { moveVaultEntry(root.path, "folder", destination) }
                assertEquals("kept", root.resolve("folder/note.md").readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun movingAProjectedNoteKeepsItsIdentityAndDoesNotRecreateItsOldPath() {
        val root = Files.createTempDirectory("gilune-move-kernel").toFile()
        try {
            Gilune.open(root.path).use { kernel ->
                kernel.captureText("preserved capture")
                kernel.project()
                val note = root.walkTopDown().first { it.isFile && it.extension == "md" }
                val old = note.relativeTo(root).invariantSeparatorsPath
                val original = note.readText()
                root.resolve("Filed").mkdir()
                val moved = moveVaultEntry(root.path, old, "Filed")
                kernel.reconcile()
                kernel.project()
                assertFalse(root.resolve(old).exists())
                assertEquals(original, root.resolve(moved).readText())
                assertTrue(kernel.openProposals().isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
