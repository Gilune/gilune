// Reading a note must never change it: front matter is machine contract
// and the body is the user's bytes (ADR 0012).

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {
    private val note = """
        ---
        gilune-id: 01a054cc-c861-7443-8d61-fb079a94c78d
        gilune-type: org.memory.source
        ---
        # Kitchen rewire

        Spark quoted **£1,400** for the ring main.

        - confirm the consumer unit
        - ask about the `RCBO` spec
    """.trimIndent()

    @Test
    fun frontMatterSplitsOffAndSurvivesUntouched() {
        val split = splitNote(note)
        assertEquals(2, split.frontMatter.size)
        assertEquals("gilune-id", split.frontMatter[0].key)
        assertEquals("01a054cc-c861-7443-8d61-fb079a94c78d", split.frontMatter[0].value)
        assertTrue(split.body.startsWith("# Kitchen rewire"))
    }

    @Test
    fun aNoteWithoutFrontMatterIsAllBody() {
        val split = splitNote("just a thought")
        assertTrue(split.frontMatter.isEmpty())
        assertEquals("just a thought", split.body)
    }

    @Test
    fun headingsEmphasisAndListsBecomeBlocks() {
        val blocks = parseMarkdown(splitNote(note).body)
        val heading = blocks.filterIsInstance<MdBlock.Heading>().single()
        assertEquals(1, heading.level)
        assertEquals("Kitchen rewire", heading.spans.joinToString("") { it.text })

        val paragraph = blocks.filterIsInstance<MdBlock.Paragraph>().single()
        assertTrue(paragraph.spans.any { it.bold && it.text == "£1,400" })
        assertTrue(
            paragraph.spans.none { it.text.contains("**") },
            "markers are dropped from the reading, never from the source",
        )

        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(2, items.size)
        assertTrue(items[1].spans.any { it.code && it.text == "RCBO" })
    }

    @Test
    fun aCaptureEmbedIsAReferenceNotARewrite() {
        val blocks = parseMarkdown("![[capture-01a054ccc861.wav]]")
        val embed = blocks.filterIsInstance<MdBlock.Embed>().single()
        assertEquals("capture-01a054ccc861.wav", embed.target)
    }

    @Test
    fun codeFencesKeepTheirContentVerbatim() {
        val blocks = parseMarkdown("```kotlin\nval x = 1\n```")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.text)
    }
}
