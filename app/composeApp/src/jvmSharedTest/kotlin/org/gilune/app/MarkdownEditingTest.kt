// E2's one hard invariant: styling never changes the text, so the caret
// and the bytes cannot disagree (ADR 0012).

package org.gilune.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownEditingTest {
    private val palette = MarkdownPalette(
        muted = Color.Gray,
        marker = Color.Blue,
        heading = Color.Black,
        code = Color.Blue,
        codeBackground = Color.LightGray,
        link = Color.Blue,
    )

    @Test
    fun stylingNeverChangesTheText() {
        val source = """
            ---
            gilune-id: abc
            ---
            # Heading

            Some **bold** and *italic* and `code`.

            - a list item
            > a quote
        """.trimIndent()
        val styled = styleMarkdown(source, palette)
        assertEquals(source, styled.text, "the source is the document; styling only decorates it")
    }

    @Test
    fun boldSpansLandOnTheBoldRange() {
        val source = "Some **bold** text"
        val styled = styleMarkdown(source, palette)
        val bold = styled.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertTrue(bold.isNotEmpty(), "a bold span exists")
        assertEquals("**bold**", source.substring(bold.first().start, bold.first().end))
    }

    @Test
    fun everySpanStaysInsideTheText() {
        val source = "# H\n**b** *i* `c` [l](u) ![[e.wav]] ~~s~~\n```\nx\n```"
        val styled = styleMarkdown(source, palette)
        for (span in styled.spanStyles) {
            assertTrue(span.start in 0..source.length && span.end in 0..source.length && span.start <= span.end)
        }
    }

    @Test
    fun frontMatterIsDimmedNotHidden() {
        val source = "---\ngilune-id: abc\n---\nbody"
        val styled = styleMarkdown(source, palette)
        assertEquals(source, styled.text)
        assertTrue(
            styled.spanStyles.any { it.item.color == palette.muted && it.start == 0 },
            "the fence line carries the muted style",
        )
    }
}
