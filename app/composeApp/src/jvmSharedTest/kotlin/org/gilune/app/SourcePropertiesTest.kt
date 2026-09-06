package org.gilune.app

import kotlin.test.*
import java.io.File
import java.nio.file.Files
import org.gilune.plugin.sdk.pageJsonString
import uniffi.gilune_ffi.*

class SourcePropertiesTest {
    @Test fun proposalPreservesSourceAndKeepsTextTypedAsText() {
        val raw = "\uFEFF---\r\ngilune-id: abc # identity\r\nname: 'Alice' # comment\r\nextra: {nested: [a,b]}\r\nretired: false\r\n---\r\nbody 😀\r\n"
        val sheet = inspectSourceProperties(raw)
        assertEquals("Alice", sheet.fields.single { it.key == "name" }.stringValue)
        assertFalse(sheet.fields.single { it.key == "extra" }.editable)
        assertFalse(sheet.fields.single { it.key == "gilune-id" }.editable)
        val after = changeSourceProperties(raw, sheet.contentVersion, listOf(PropertyChange("name", pageJsonString("true")), PropertyChange("retired", "true")))
        assertEquals(raw.replace("'Alice'", "\"true\"").replace("retired: false", "retired: true"), after.text)
        assertEquals("string", readSourceProperties(after.text, listOf("name")).single().kind)
        assertEquals("boolean", readSourceProperties(after.text, listOf("retired")).single().kind)
    }

    @Test fun malformedValuesStaleProposalsAndProtectedMetadataCannotReachTheBuffer() {
        val raw = "---\ngilune-id: abc\nname: Alice\n---\nbody"
        val sheet = inspectSourceProperties(raw)
        for (changes in listOf(listOf(PropertyChange("gilune-id", "other")), listOf(PropertyChange("name", "[x]")),
            listOf(PropertyChange("name", "Bob\n---\nbody")), listOf(PropertyChange("name", "a"), PropertyChange("name", "b")))) {
            assertFailsWith<GiluneException.DocumentInvalid> { changeSourceProperties(raw, sheet.contentVersion, changes) }
        }
        assertFailsWith<GiluneException.DocumentInvalid> { changeSourceProperties(raw + "edited", sheet.contentVersion, emptyList()) }
    }

    @Test fun removingAndAddingPropertiesPreservesOrderAndStandaloneComments() {
        val raw = "---\ngilune-id: abc\nold: gone\n# keep this\nnext: stays\n---\nbody"
        val sheet = inspectSourceProperties(raw)
        val after = changeSourceProperties(raw, sheet.contentVersion, listOf(PropertyChange("old", null), PropertyChange("last", "42"))).text
        assertEquals("---\ngilune-id: abc\n# keep this\nnext: stays\nlast: 42\n---\nbody", after)
        val empty = changeSourceProperties("", inspectSourceProperties("").contentVersion, listOf(PropertyChange("name", "Alice"))).text
        assertEquals("---\nname: Alice\n---\n", empty)
    }

    @Test fun propertyOnlyRevisionReconcilesAndReprojectsWithoutLosingUnknownContent() {
        val root = Files.createTempDirectory("gilune-property-edit").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            kernel.captureText("body\n"); kernel.project()
            val path = kernel.recentSources(1u).single().projectionRelPath!!
            val file = File(root, path)
            val original = file.readText()
            val (raw, body) = splitNoteRaw(original)
            val first = changeSourceProperties(raw, inspectSourceProperties(raw).contentVersion, listOf(PropertyChange("name", "Alice"))).text + body
            file.writeText(first); kernel.reconcilePath(path); kernel.project()
            val before = kernel.documentSnapshot(path)
            val proposed = changeSourceProperties(before.content.text, inspectSourceProperties(before.content.text).contentVersion, listOf(PropertyChange("name", "Bob")))
            assertEquals(before.content.text, file.readText(), "proposal itself writes nothing")
            file.writeText(proposed.text); kernel.reconcilePath(path); kernel.project()
            val after = kernel.documentSnapshot(path)
            assertEquals(before.source.objectId, after.source.objectId)
            assertNotEquals(before.source.revisionId, after.source.revisionId)
            assertEquals("Bob", readSourceProperties(after.content.text, listOf("name")).single().value)
        }
    }
}
