package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import uniffi.gilune_ffi.DocumentState
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.GiluneException
import uniffi.gilune_ffi.SourceLocation
import uniffi.gilune_ffi.SourceSpan
import uniffi.gilune_ffi.SourceTextEdit
import uniffi.gilune_ffi.inspectSourceText
import uniffi.gilune_ffi.sourceByteToUtf16
import uniffi.gilune_ffi.sourceLocationAt
import uniffi.gilune_ffi.sourceUtf16ToByte

/** Uses only the public boundary: no application registries or domain plugins. */
class DocumentSourceTest {
    @Test
    fun sharedLocationsDistinguishAnItemItsContinuationAndItsChild() {
        val text = "\uFEFF---\r\nname: Fixture\r\n---\r\n- 😀 parent\r\n  continued\r\n  - child\r\n\r\noutside"
        val snapshot = inspectSourceText(text)
        assertEquals(text, snapshot.text)
        assertEquals(2, snapshot.items.size)
        val parent = snapshot.items.first()
        val child = snapshot.items.last()
        assertEquals(parent.key, child.parentKey)
        fun locate(word: String) = sourceLocationAt(text, sourceUtf16ToByte(text, text.indexOf(word).toULong()))
        assertEquals(SourceLocation.DocumentRoot, locate("Fixture"))
        assertEquals(SourceLocation.MarkdownItem(parent.key), locate("continued"))
        assertEquals(SourceLocation.MarkdownItem(child.key), locate("child"))
        assertTrue(locate("outside") is SourceLocation.BodyLine)
        val byteOffset = sourceUtf16ToByte(text, text.indexOf("continued").toULong())
        assertEquals(text.indexOf("continued").toULong(), sourceByteToUtf16(text, byteOffset))
        val insideSurrogate = (text.indexOf("😀") + 1).toULong()
        assertFailsWith<GiluneException.DocumentInvalid> { sourceUtf16ToByte(text, insideSurrogate) }
    }

    @Test
    fun unmanagedPreviewPreservesExactBytesAndRejectsAnExternalEdit() {
        val root = Files.createTempDirectory("gilune-document-contract").toFile()
        val file = File(root, "fixture.md")
        val text = "- 😀 label=red\r\n  continuation\r\n"
        file.writeText(text)
        Gilune.open(root.absolutePath).use { gilune ->
            val before = gilune.documentSnapshot("fixture.md")
            assertEquals(DocumentState.UNMANAGED, before.source.state)
            assertNull(before.source.objectId)
            assertNull(before.source.revisionId)
            val start = sourceUtf16ToByte(text, text.indexOf("red").toULong())
            val edits = listOf(SourceTextEdit(SourceSpan(start, start + 3uL), "blue"))
            val prepared = gilune.prepareDocumentChange("fixture.md", before.source.version, edits)
            assertEquals(text.replace("red", "blue"), prepared.after.text)
            assertEquals(text, file.readText())
            assertFalse(prepared.after.text.contains("gilune-id"))
            file.writeText("external replacement\r\n" + text)
            assertFailsWith<GiluneException.DocumentChanged> {
                gilune.prepareDocumentChange("fixture.md", before.source.version, edits)
            }
        }
    }

    @Test
    fun managedPreviewCannotAlterIdentityOrPretendExternalBytesAreARevision() {
        val root = Files.createTempDirectory("gilune-managed-document").toFile()
        Gilune.open(root.absolutePath).use { gilune ->
            val receipt = gilune.captureText("- label=red")
            gilune.project()
            val path = receipt.relPath.replace(".txt", ".md")
            val before = gilune.documentSnapshot(path)
            assertEquals(DocumentState.MANAGED, before.source.state)
            val id = before.source.objectId!!
            val start = sourceUtf16ToByte(before.content.text, before.content.text.indexOf(id).toULong())
            assertFailsWith<GiluneException.DocumentInvalid> {
                gilune.prepareDocumentChange(path, before.source.version,
                    listOf(SourceTextEdit(SourceSpan(start, start + id.length.toULong()), "forged")))
            }
            File(root, path).writeText(before.content.text.replace("red", "blue"))
            val after = gilune.documentSnapshot(path)
            assertEquals(DocumentState.NEEDS_RECONCILIATION, after.source.state)
            assertEquals(id, after.source.objectId)
            assertNull(after.source.revisionId)
            assertFailsWith<GiluneException.DocumentUnavailable> {
                gilune.prepareDocumentChange(path, after.source.version, emptyList())
            }
        }
    }

    @Test
    fun editablePropertiesSurviveReconciliationAndReprojection() {
        val root = Files.createTempDirectory("gilune-property-roundtrip").toFile()
        Gilune.open(root.absolutePath).use { gilune ->
            val receipt = gilune.captureText("freeform body\n")
            gilune.project()
            val path = receipt.relPath.replace(".txt", ".md")
            val file = File(root, path)
            val properties = "# keep this\nname: 'Example # One'\ncustom:\n  values: [a, b]\n"
            file.writeText("\uFEFF" + file.readText()
                .replaceFirst("---\n", "---\n$properties").replace("\n", "\r\n"))
            gilune.reconcilePath(path)
            gilune.project()
            val settled = gilune.documentSnapshot(path)
            assertEquals(DocumentState.MANAGED, settled.source.state)
            assertTrue(settled.content.text.startsWith("\uFEFF---\r\n"))
            assertTrue(settled.content.text.contains(properties.replace("\n", "\r\n")))
            assertTrue(settled.content.text.endsWith("freeform body\r\n"))
            assertEquals(0L, gilune.reconcilePath(path).edits)
            assertEquals(settled.content.text, file.readText())
        }
    }

}
