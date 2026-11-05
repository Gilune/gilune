// Device-local note typography, independently configurable for Edit and Read.
package org.gilune.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

internal enum class NoteFont(val label: String, val family: FontFamily) {
    SansSerif("Sans serif", FontFamily.SansSerif),
    Serif("Serif", FontFamily.Serif),
    Monospace("Monospace", FontFamily.Monospace),
}

/// Where a note's text sits when its lines are narrower than the pane
/// (owner, 2026-11-05: left by preference, centred for those who like it).
internal enum class NoteAlign(val label: String) {
    Left("Left"),
    Centre("Centre"),
}

/// One mode's type. `lineWidth` counts characters — the width of that many
/// zeros in this font and size, the CSS `ch` — so a larger size keeps the
/// same words on a line. It applies only while `limitWidth` is on, and is
/// kept while it is off, so switching back restores the chosen width.
internal data class NoteTypeSettings(
    val font: NoteFont,
    val size: Int,
    val lineHeight: Int,
    val blockSpacing: Int = 10,
    val limitWidth: Boolean = false,
    val lineWidth: Int = 100,
) {
    fun bounded(): NoteTypeSettings {
        val boundedSize = size.coerceIn(10, 32)
        return copy(
            size = boundedSize,
            lineHeight = lineHeight.coerceIn(boundedSize, 96),
            blockSpacing = blockSpacing.coerceIn(0, 32),
            lineWidth = lineWidth.coerceIn(MIN_LINE_WIDTH, MAX_LINE_WIDTH),
        )
    }
    val textStyle: TextStyle get() = TextStyle(fontFamily = font.family, fontSize = size.sp, lineHeight = lineHeight.sp)

    companion object {
        const val MIN_LINE_WIDTH = 40
        const val MAX_LINE_WIDTH = 200
        val Edit = NoteTypeSettings(NoteFont.Monospace, 13, 20)
        val Read = NoteTypeSettings(NoteFont.SansSerif, 14, 22, lineWidth = 80)
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
    /// One choice for both modes: a note switching between them should not
    /// jump from one side of the pane to the other.
    var align by mutableStateOf(NoteAlign.entries.firstOrNull { it.name == read("notes.align") } ?: NoteAlign.Left)
        private set

    fun update(readingMode: Boolean, settings: NoteTypeSettings) {
        val value = settings.bounded()
        val key = if (readingMode) "read" else "edit"
        write("notes.$key.font", value.font.name)
        write("notes.$key.size", value.size.toString())
        write("notes.$key.lineHeight", value.lineHeight.toString())
        write("notes.$key.blockSpacing", value.blockSpacing.toString())
        write("notes.$key.limitWidth", value.limitWidth.toString())
        write("notes.$key.lineWidth", value.lineWidth.toString())
        if (readingMode) reading = value else edit = value
    }

    fun updateAlign(value: NoteAlign) {
        write("notes.align", value.name)
        align = value
    }

    private fun load(mode: String, defaults: NoteTypeSettings): NoteTypeSettings = NoteTypeSettings(
        font = NoteFont.entries.firstOrNull { it.name == read("notes.$mode.font") } ?: defaults.font,
        size = read("notes.$mode.size")?.toIntOrNull() ?: defaults.size,
        lineHeight = read("notes.$mode.lineHeight")?.toIntOrNull() ?: defaults.lineHeight,
        blockSpacing = read("notes.$mode.blockSpacing")?.toIntOrNull() ?: defaults.blockSpacing,
        limitWidth = read("notes.$mode.limitWidth")?.toBooleanStrictOrNull() ?: defaults.limitWidth,
        lineWidth = read("notes.$mode.lineWidth")?.toIntOrNull() ?: defaults.lineWidth,
    ).bounded()
}

internal val LocalNoteAppearance = staticCompositionLocalOf { Prefs.noteAppearance }

/// The space a note's text gives up either side of it, on top of the
/// pane's own padding. Nothing while there is no limit or the pane is
/// already narrower than it — a narrow pane is never narrowed further.
internal data class NoteColumn(val start: Dp, val end: Dp) {
    companion object {
        val Full = NoteColumn(0.dp, 0.dp)
    }
}

/// `available` is the width the text would have with no limit; `limit` the
/// width of the chosen line, or null for none. Pure, so the split is tested
/// without laying anything out.
internal fun noteColumnInsets(available: Float, limit: Float?, align: NoteAlign): Pair<Float, Float> {
    if (limit == null || available <= limit) return 0f to 0f
    val spare = available - limit
    return when (align) {
        NoteAlign.Left -> 0f to spare
        NoteAlign.Centre -> (spare / 2f) to (spare - spare / 2f)
    }
}

/// The column for one mode in a pane `textWidth` wide (its padding already
/// taken off). The line is measured as that many zeros in the mode's own
/// font, rounded up a pixel so a line of exactly that many characters fits.
@Composable
internal fun rememberNoteColumn(textWidth: Dp, settings: NoteTypeSettings, align: NoteAlign): NoteColumn {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val limitPx = remember(settings.limitWidth, settings.font, settings.size, settings.lineWidth, density) {
        if (!settings.limitWidth) null
        else ceil(measurer.measure("0".repeat(settings.lineWidth), settings.textStyle, softWrap = false).size.width.toFloat()) + 1f
    }
    if (limitPx == null) return NoteColumn.Full
    val (start, end) = with(density) { noteColumnInsets(textWidth.toPx(), limitPx, align) }
    return with(density) { NoteColumn(start.toDp(), end.toDp()) }
}
