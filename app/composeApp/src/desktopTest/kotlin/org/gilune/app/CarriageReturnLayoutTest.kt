package org.gilune.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import kotlin.test.Test
import kotlin.test.assertTrue

// ADR 0032: Skia lays out a paragraph holding carriage returns in time that
// grows with the square of its length — a 64 KB note with Windows line
// endings took ~2 s on this machine where `\n` alone took ~20 ms. The
// editor's transformation draws each `\r` as a zero-width space, so what
// reaches Skia is the fast case. The bound is loose enough for a slow
// runner and still a long way under the slow case.
class CarriageReturnLayoutTest {
    private val palette = MarkdownPalette(
        heading = Color.Black, marker = Color.Gray, muted = Color.Gray,
        code = Color.Black, codeBackground = Color.White, link = Color.Blue,
    )

    @Test
    fun aLongNoteWithWindowsLineEndingsLaysOutAsFastAsOneWithout() {
        val note = (1..1500).joinToString("\r\n") { "Line $it with some words to wrap across the width of the editor." }
        val drawn = MarkdownEditTransformation(palette).filter(AnnotatedString(note)).text.text
        val fonts = FontCollection().setDefaultFontManager(FontMgr.default)
        fonts.paragraphCache.setEnabled(false)
        fun layoutMs(text: String): Long {
            val started = System.nanoTime()
            ParagraphBuilder(ParagraphStyle(), fonts).use { builder ->
                builder.pushStyle(TextStyle().setFontSize(13f))
                builder.addText(text)
                builder.build().use { it.layout(800f) }
            }
            return (System.nanoTime() - started) / 1_000_000
        }
        layoutMs(drawn) // warm
        val ms = layoutMs(drawn)
        assertTrue(ms < 500, "the drawn note took $ms ms to lay out")
    }
}
