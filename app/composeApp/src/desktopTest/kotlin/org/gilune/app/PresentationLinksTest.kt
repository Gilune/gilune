// The rules presentation mode's clicks follow, without a window: which
// note a link names, and where the parser says drawn words came from —
// the offsets a plugin's recognition is laid over.

package org.gilune.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresentationLinksTest {
    private val notes = listOf("Plan.md", "Projects/Plan.md", "Projects/Deep/Other.md", "People/alice.md")

    @Test
    fun aBareNameFindsTheNoteOfThatNameAndTheShortestPathWins() {
        assertEquals("Plan.md", resolveWikiLink(notes, "plan"))
        assertEquals("Projects/Deep/Other.md", resolveWikiLink(notes, "Other"))
    }

    @Test
    fun aPathAnAnchorAndAnExtensionAreAllUnderstood() {
        assertEquals("Projects/Plan.md", resolveWikiLink(notes, "Projects/Plan"))
        assertEquals("Projects/Plan.md", resolveWikiLink(notes, "projects/plan.md#Goals"))
        assertNull(resolveWikiLink(notes, "Missing"))
        assertNull(resolveWikiLink(notes, "#only-an-anchor"))
    }

    @Test
    fun aMarkdownLinkResolvesFromTheNotesFolderThenTheVaultAndNeverOutside() {
        val vault = Files.createTempDirectory("gilune-links").toFile()
        vault.resolve("Projects").mkdirs()
        vault.resolve("Projects/Plan.md").writeText("x")
        vault.resolve("Projects/My Note.md").writeText("x")
        vault.resolve("Top.md").writeText("x")
        File(vault.parentFile, "outside.md").also { it.writeText("x") }

        assertEquals("Projects/Plan.md", resolveVaultAddress(vault.path, "Projects/here.md", "Plan.md"))
        assertEquals("Projects/Plan.md", resolveVaultAddress(vault.path, "Projects/here.md", "Plan"))
        assertEquals("Projects/My Note.md", resolveVaultAddress(vault.path, "Projects/here.md", "My%20Note.md#part"))
        assertEquals("Top.md", resolveVaultAddress(vault.path, "Projects/here.md", "Top.md"))
        assertNull(resolveVaultAddress(vault.path, "Projects/here.md", "../../outside.md"), "a link never leaves the vault")
    }

    @Test
    fun spansRememberWhereTheirWordsBeganEvenAfterTrimmingAndMarkers() {
        val body = "intro\n\n- [ ] call @alice.smith **soon** ok\n  plain tail"
        val item = parseMarkdown(body).filterIsInstance<MdBlock.ListItem>().single()
        for (span in item.spans.filter { it.source >= 0 }) {
            assertEquals(span.text.replace(" ", "\n"), body.substring(span.source, span.source + span.text.length).replace(" ", "\n"),
                "span '${span.text}' maps back to its own source")
        }
        val call = item.spans.first()
        assertEquals(body.indexOf("call"), call.source, "the task marker's characters are accounted for")
    }

    @Test
    fun aBareAddressAndAnAngleBracketedOneAreBothLinks() {
        val spans = (parseMarkdown("See https://example.org and <https://gilune.test/x> now").single() as MdBlock.Paragraph).spans
        assertEquals(listOf("https://example.org", "https://gilune.test/x"), spans.mapNotNull { it.link })
        assertEquals("See https://example.org and https://gilune.test/x now", spans.joinToString("") { it.text })
    }

    private fun File(parent: java.io.File, name: String) = java.io.File(parent, name)
}
