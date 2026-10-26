// Connection pills (owner, 2026-10-26): `assign: @phil.jackson` is drawn as
// a two-part pill off the caret's line and as plain text on it. These rows
// pin where the marks land; ConnectionPillsUiTest pins that they are drawn.

package org.gilune.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import org.gilune.plugins.people.PEOPLE_CONNECTION_STYLE
import org.gilune.plugins.people.connectionsInLine
import org.gilune.plugins.people.peopleConnectionDecoration
import org.gilune.plugins.people.peopleMentionDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionPillsTest {
    private val palette = MarkdownPalette(
        muted = Color.Gray,
        marker = Color.Blue,
        heading = Color.Black,
        code = Color.Blue,
        codeBackground = Color.LightGray,
        link = Color(0xFF0000EE),
        pillTag = Color(0xFF442200),
    )
    private val people = builtInDecorations() + listOf(peopleConnectionDecoration(), peopleMentionDecoration())
    private val line = "- [ ] Ship it assign: @phil.jackson"

    private fun marks(text: AnnotatedString, tag: String) =
        text.getStringAnnotations(tag, 0, text.length).map { text.text.substring(it.start, it.end) }

    @Test
    fun aKeywordBeforeAMentionIsAConnection() {
        val (range, tag, keyword) = connectionsInLine(line).single()
        assertEquals("assign: @phil.jackson", line.substring(range.first, range.last + 1))
        assertEquals("assign:".length, tag)
        assertEquals("assign", keyword)
        assertEquals("reviewer", connectionsInLine("Reviewer: @alice").single().third, "the colour key is lower-cased")
        assertEquals(1, connectionsInLine("owner:@alice").size, "no space needed after the colon")
        assertEquals(1, connectionsInLine("x assign:\t@alice").size, "a tab will do")
    }

    @Test
    fun onlyWhatTheIndexCallsAConnectionIsAPill() {
        for (text in listOf(
            "*assign:* @phil.jackson", // the star sits between colon and mention
            "plain @phil.jackson", // a mention, not a connection
            "`assign: @phil.jackson`", // quoted code
            "assign\\: @phil.jackson", // escaped colon
            "reassign:x @phil", // something other than spaces between
            "(assign): @phil", // the bracket is not part of a keyword
        )) {
            assertTrue(connectionsInLine(text).isEmpty(), text)
        }
    }

    @Test
    fun offTheCaretLineTheConnectionIsMarkedAsATwoPartPillAndNoByteMoves() {
        val transformed = MarkdownEditTransformation(palette, caretLine = 1, decorations = people)
            .filter(AnnotatedString("$line\nanother line"))
        assertEquals(listOf("assign:"), marks(transformed.text, PILL_TAG))
        assertEquals(listOf(" @phil.jackson"), marks(transformed.text, PILL_BODY))
        val tagStart = transformed.text.text.indexOf("assign:")
        assertTrue(transformed.text.spanStyles.any { it.start == tagStart && it.item.color == palette.pillTag }, "tag words take the tag colour")
        // Only the checkbox changes length; the pill's characters are its own.
        assertTrue(transformed.text.text.endsWith("Ship it assign: @phil.jackson\nanother line"))
        // `[ ]` becomes one glyph, two characters shorter; the pill adds no
        // shift of its own.
        val atHandle = line.indexOf("@phil")
        assertEquals(atHandle - 2, transformed.offsetMapping.originalToTransformed(atHandle))
    }

    @Test
    fun onTheCaretLineItIsPlainTextWithTheMentionStillALink() {
        val transformed = MarkdownEditTransformation(palette, caretLine = 0, decorations = people)
            .filter(AnnotatedString(line))
        assertEquals(line, transformed.text.text)
        assertEquals(OffsetMapping.Identity, transformed.offsetMapping)
        assertTrue(marks(transformed.text, PILL_TAG).isEmpty())
        val handle = line.indexOf("@phil")
        assertTrue(transformed.text.spanStyles.any { it.start == handle && it.item.color == palette.link }, "the mention keeps its colour")
    }

    @Test
    fun thePhoneKeepsItsGlyphsButStillLetsThePillGoOnTheLineBeingWritten() {
        val transformed = MarkdownEditTransformation(palette, caretLine = null, decorations = people, plainPillLine = 0)
            .filter(AnnotatedString("$line\n$line"))
        assertEquals(2, transformed.text.text.count { it == '☐' }, "both checkboxes render")
        assertEquals(1, marks(transformed.text, PILL_TAG).size, "only the second line is a pill")
        val second = transformed.text.getStringAnnotations(PILL_TAG, 0, transformed.text.length).single()
        assertTrue(second.start > transformed.text.text.indexOf('\n'))
    }

    @Test
    fun aReaderGetsThePillsInSourceCoordinates() {
        val source = "Intro\n$line"
        val (range, tag, item) = pillsIn(source, people).single()
        assertEquals("assign: @phil.jackson", source.substring(range.first, range.last + 1))
        assertEquals(7, tag)
        assertEquals(pillItem(PEOPLE_CONNECTION_STYLE, "assign"), item, "the mark names its colour key")
        assertTrue(pillsIn("```\n$line\n```", people).isEmpty(), "quoted in a fence, nothing is drawn")
    }
}
