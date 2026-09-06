package org.gilune.app

import kotlin.test.*
import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

class SourceViewTest {
    @Test fun sdkCoordinatesAgreeWithNativeSourceMapAtEveryCodepoint() {
        val text = "\uFEFF---\r\nname: Café\r\n---\r\n- 😀 outer\r\n  more\r\n  - 子 inner\r\n\r\nprose\r\n"
        val view = SourceView(inspectSourceText(text))
        var char = 0
        while (char < text.length) {
            val byte = view.byteOffset(char)
            assertEquals(sourceUtf16ToByte(text, char.toULong()), byte)
            assertEquals(char, view.charOffset(byte))
            assertEquals(sourceLocationAt(text, byte), view.locationAt(char))
            char += Character.charCount(text.codePointAt(char))
        }
        assertEquals(text.toByteArray().size.toULong(), view.byteOffset(text.length))
        assertEquals(text.length, view.charOffset(view.byteOffset(text.length)))
        val emoji = text.indexOf("😀")
        assertFailsWith<IllegalArgumentException> { view.byteOffset(emoji + 1) }
        assertFailsWith<IllegalArgumentException> { view.charOffset(view.byteOffset(emoji) + 1uL) }
    }

    @Test fun registrationCannotMixScopedAndLegacyInputs() {
        val legacy = object : SourceIndexer {
            override fun index(relPath: String, source: String) = emptyList<Fact>()
        }
        val scoped = object : ScopedIndexer {
            override fun index(relPath: String, source: SourceTextSnapshot) = emptyList<ScopedFact>()
        }
        val declaration = PluginDeclaration("fixture.values", "Values", "domain", "bundled", false, null, null, emptyList(), emptyList(), emptyList())
        assertFailsWith<IllegalArgumentException> {
            PluginExtensions(listOf(BundledPluginExtensions(declaration,
                sourceIndexer = SourceIndexerRegistration(1, emptyList()) { legacy },
                scopedIndexer = ScopedIndexerRegistration(1, emptyList()) { scoped })))
        }
    }
}
