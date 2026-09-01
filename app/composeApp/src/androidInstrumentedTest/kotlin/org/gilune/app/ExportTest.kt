// Export a copy (ADR 0017 § 5). The vault is app-private, so this is the
// only way the files leave the phone before M5 — which makes "what is in
// the zip" a promise worth testing rather than assuming.

package org.gilune.app

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {
    private fun vault(): String {
        val root = Files.createTempDirectory("gilune-export").toFile()
        root.resolve("Captures").mkdirs()
        root.resolve("Projects/Kitchen").mkdirs()
        root.resolve(".gilune").mkdirs()
        root.resolve("Captures/note.md").writeText("---\ngilune-id: a\n---\nspoken\n")
        root.resolve("Captures/note.wav").writeBytes(wavBytes(RecordedAudio(ByteArray(320), 16_000, 1, 16)))
        root.resolve("Projects/Kitchen/rewire.md").writeText("# Rewire\n")
        root.resolve(".gilune/kernel.db").writeText("not the user's documents")
        root.resolve(".hidden.md").writeText("nor this")
        return root.absolutePath
    }

    @Test
    fun theExportCarriesTheVaultAndNotTheDatabase() {
        assertEquals(
            listOf("Captures/note.md", "Captures/note.wav", "Projects/Kitchen/rewire.md"),
            exportEntries(vault()),
        )
    }

    @Test
    fun recordingsTravelWithTheirNotes() {
        assertTrue(
            exportEntries(vault()).any { it.endsWith(".wav") },
            "the recording is the preserved source; an export without it is not a copy of the vault",
        )
    }

    @Test
    fun theZipHoldsExactlyThoseEntriesWithTheirBytes() {
        val dir = vault()
        val out = ByteArrayOutputStream()
        val written = writeVaultZip(dir, out)
        assertEquals(3, written)

        val found = mutableMapOf<String, Int>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                found[entry.name] = zip.readBytes().size
                entry = zip.nextEntry
            }
        }
        assertEquals(exportEntries(dir).toSet(), found.keys)
        assertEquals(
            java.io.File(dir, "Captures/note.md").length().toInt(),
            found["Captures/note.md"],
            "bytes go across whole; an export that truncates is worse than none",
        )
    }

    @Test
    fun anAbsentVaultExportsNothingRatherThanFailing() {
        assertEquals(emptyList(), exportEntries("/no/such/vault"))
    }

    @Test
    fun theFileNameIsDatedSoTwoExportsAreTwoFiles() {
        assertEquals("gilune-vault-2026-09-01.zip", exportFileName("2026-09-01"))
    }
}
