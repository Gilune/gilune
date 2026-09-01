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

    // ---- source lines ----------------------------------------------------
    // A rendered surface has no caret, so the only honest route from
    // something drawn back to the bytes behind it is the line the parser
    // kept (ADR 0017 § 8). These pin that number down.

    @Test
    fun everyBlockKnowsTheLineItStartsOn() {
        val body = "# Title\n\nprose\n\n- one\n- two\n\n```sh\nls\n```\n\n---\n"
        val blocks = parseMarkdown(body)
        assertEquals(0, blocks.filterIsInstance<MdBlock.Heading>().single().line)
        assertEquals(2, blocks.filterIsInstance<MdBlock.Paragraph>().single().line)
        assertEquals(listOf(4, 5), blocks.filterIsInstance<MdBlock.ListItem>().map { it.line })
        assertEquals(7, blocks.filterIsInstance<MdBlock.Code>().single().line)
        assertEquals(11, blocks.filterIsInstance<MdBlock.Rule>().single().line)
    }

    @Test
    fun aTaskItemCarriesItsStateAndItsMarkerStaysOutOfTheReading() {
        val blocks = parseMarkdown("- [ ] milk\n- [x] bread\n- plain\n")
            .filterIsInstance<MdBlock.ListItem>()
        assertEquals(listOf(false, true, null), blocks.map { it.task })
        assertEquals(
            listOf("milk", "bread", "plain"),
            blocks.map { item -> item.spans.joinToString("") { it.text } },
            "the glyph stands for the brackets; the source keeps them",
        )
        assertEquals(listOf(0, 1, 2), blocks.map { it.line })
    }

    @Test
    fun theLineIsTheOneTheTaskMarkerIsOn() {
        val body = "---\nnot front matter here\n---\n\n- [ ] first\n- [ ] second\n"
        val items = parseMarkdown(body).filterIsInstance<MdBlock.ListItem>()
        for (item in items) {
            assertTrue(
                body.split('\n')[item.line].contains("[ ]"),
                "line ${item.line} is ${body.split('\n')[item.line]}",
            )
        }
    }

    @Test
    fun bracketsThatAreNotATaskMarkerAreNotOne() {
        val items = parseMarkdown("- see [ ] in the middle\n").filterIsInstance<MdBlock.ListItem>()
        assertEquals(null, items.single().task)
        assertTrue(items.single().spans.joinToString("") { it.text }.contains("[ ]"))
    }
}
