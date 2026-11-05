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

    @Test fun lineWidthPersistsPerModeAndPlacementOnceForBoth() {
        val values = mutableMapOf<String, String>()
        val appearance = NoteAppearance(values::get) { key, value -> values[key] = value }
        assertFalse(appearance.edit.limitWidth)
        assertFalse(appearance.reading.limitWidth)
        assertEquals(NoteAlign.Left, appearance.align)
        appearance.update(false, appearance.edit.copy(limitWidth = true, lineWidth = 120))
        appearance.updateAlign(NoteAlign.Centre)
        val restored = NoteAppearance(values::get)
        assertTrue(restored.edit.limitWidth)
        assertEquals(120, restored.edit.lineWidth)
        assertFalse(restored.reading.limitWidth)
        assertEquals(80, restored.reading.lineWidth)
        assertEquals(NoteAlign.Centre, restored.align)
        // Switching the limit off keeps the width chosen for next time.
        restored.update(false, restored.edit.copy(limitWidth = false))
        assertEquals(120, NoteAppearance(values::get).edit.lineWidth)
    }

    @Test fun malformedWidthPreferencesFallBackOrAreBounded() {
        val values = mapOf("notes.edit.lineWidth" to "5", "notes.read.lineWidth" to "9000",
            "notes.edit.limitWidth" to "maybe", "notes.align" to "Diagonal")
        val appearance = NoteAppearance(values::get)
        assertEquals(NoteTypeSettings.MIN_LINE_WIDTH, appearance.edit.lineWidth)
        assertEquals(NoteTypeSettings.MAX_LINE_WIDTH, appearance.reading.lineWidth)
        assertFalse(appearance.edit.limitWidth)
        assertEquals(NoteAlign.Left, appearance.align)
    }

    @Test fun theColumnGivesUpOnlyTheSpareWidthOnTheChosenSide() {
        assertEquals(0f to 0f, noteColumnInsets(1000f, null, NoteAlign.Centre))
        // A pane already narrower than the line is never narrowed further.
        assertEquals(0f to 0f, noteColumnInsets(500f, 600f, NoteAlign.Centre))
        assertEquals(0f to 0f, noteColumnInsets(600f, 600f, NoteAlign.Left))
        assertEquals(0f to 400f, noteColumnInsets(1000f, 600f, NoteAlign.Left))
        assertEquals(200f to 200f, noteColumnInsets(1000f, 600f, NoteAlign.Centre))
        val (start, end) = noteColumnInsets(1001f, 600f, NoteAlign.Centre)
        assertEquals(401f, start + end)
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
