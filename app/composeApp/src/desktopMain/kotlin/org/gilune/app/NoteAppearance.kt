// Device-local note typography, independently configurable for Edit and Read.
package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

internal enum class NoteFont(val label: String, val family: FontFamily) {
    SansSerif("Sans serif", FontFamily.SansSerif),
    Serif("Serif", FontFamily.Serif),
    Monospace("Monospace", FontFamily.Monospace),
}

internal data class NoteTypeSettings(val font: NoteFont, val size: Int, val lineHeight: Int, val blockSpacing: Int = 10) {
    fun bounded(): NoteTypeSettings {
        val boundedSize = size.coerceIn(10, 32)
        return copy(size = boundedSize, lineHeight = lineHeight.coerceIn(boundedSize, 96), blockSpacing = blockSpacing.coerceIn(0, 32))
    }
    val textStyle: TextStyle get() = TextStyle(fontFamily = font.family, fontSize = size.sp, lineHeight = lineHeight.sp)

    companion object {
        val Edit = NoteTypeSettings(NoteFont.Monospace, 13, 20)
        val Read = NoteTypeSettings(NoteFont.SansSerif, 14, 22)
    }
}

internal class NoteAppearance(
    private val read: (String) -> String? = { null },
    private val write: (String, String) -> Unit = { _, _ -> },
) {
    var edit by mutableStateOf(load("edit", NoteTypeSettings.Edit))
        private set
    var reading by mutableStateOf(load("read", NoteTypeSettings.Read))
        private set

    fun update(readingMode: Boolean, settings: NoteTypeSettings) {
        val value = settings.bounded()
        val key = if (readingMode) "read" else "edit"
        write("notes.$key.font", value.font.name)
        write("notes.$key.size", value.size.toString())
        write("notes.$key.lineHeight", value.lineHeight.toString())
        write("notes.$key.blockSpacing", value.blockSpacing.toString())
        if (readingMode) reading = value else edit = value
    }

    private fun load(mode: String, defaults: NoteTypeSettings): NoteTypeSettings = NoteTypeSettings(
        font = NoteFont.entries.firstOrNull { it.name == read("notes.$mode.font") } ?: defaults.font,
        size = read("notes.$mode.size")?.toIntOrNull() ?: defaults.size,
        lineHeight = read("notes.$mode.lineHeight")?.toIntOrNull() ?: defaults.lineHeight,
        blockSpacing = read("notes.$mode.blockSpacing")?.toIntOrNull() ?: defaults.blockSpacing,
    ).bounded()
}

internal val LocalNoteAppearance = staticCompositionLocalOf { Prefs.noteAppearance }
