package org.gilune.app

import kotlin.test.*

class NoteAppearanceTest {
    @Test fun editAndReadChoicesPersistIndependentlyAndResetIndependently() {
        val values = mutableMapOf<String, String>()
        val appearance = NoteAppearance(values::get) { key, value -> values[key] = value }
        val edit = NoteTypeSettings(NoteFont.Serif, 19, 28)
        val read = NoteTypeSettings(NoteFont.SansSerif, 22, 34, 18)
        appearance.update(false, edit)
        appearance.update(true, read)
        val restored = NoteAppearance(values::get) { key, value -> values[key] = value }
        assertEquals(edit, restored.edit)
        assertEquals(read, restored.reading)
        restored.update(false, NoteTypeSettings.Edit)
        assertEquals(NoteTypeSettings.Edit, NoteAppearance(values::get).edit)
        assertEquals(read, NoteAppearance(values::get).reading)
    }

    @Test fun malformedPreferencesCannotProduceUnreadableOrOverlappingText() {
        val values = mapOf("notes.edit.font" to "missing", "notes.edit.size" to "999",
            "notes.edit.lineHeight" to "-20", "notes.read.size" to "NaN", "notes.read.blockSpacing" to "999")
        val appearance = NoteAppearance(values::get)
        assertEquals(NoteFont.Monospace, appearance.edit.font)
        assertEquals(32, appearance.edit.size)
        assertEquals(32, appearance.edit.lineHeight)
        assertEquals(14, appearance.reading.size)
        assertEquals(32, appearance.reading.blockSpacing)
    }
}
