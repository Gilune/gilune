// Per-connection pill colours (owner, 2026-10-26): a connection page's
// `colour` colours its pills; a keyword no live page defines is grey. These
// rows run the real kernel and index, so what the editors draw is what a
// page says.

package org.gilune.app

import org.gilune.plugin.sdk.PillTone
import org.gilune.plugins.people.*
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PillTonesTest {
    private fun item(keyword: String) = pillItem(PEOPLE_CONNECTION_STYLE, keyword)

    private fun connectionPage(keyword: String, extra: String = "", retired: Boolean = false) =
        "---\ngilune-type: $CONNECTION_TYPE\ngilune-id: $keyword-page\nkeyword: $keyword\nlabel: ${keyword.replaceFirstChar { it.uppercase() }}\nretired: $retired\n$extra---\n"

    private fun fixture(run: (File, Gilune, PluginExtensions) -> Unit) {
        val root = Files.createTempDirectory("gilune-pill-tones").toFile()
        root.resolve(".gilune-local").writeText("") // local-only vault: files stay unmanaged (ADR 0007, 2026-10-22)
        Gilune.open(root.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(peopleExtensions()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            registry.initializePages(kernel) // the default assign and owner pages, no colour
            run(root, kernel, registry)
        }
    }

    private fun write(root: File, kernel: Gilune, name: String, text: String) {
        root.resolve(name).writeText(text)
        kernel.reconcilePath(name)
    }

    @Test
    fun aPagesColourColoursItsPillsAndTheRestFollowTheRules() = fixture { root, kernel, registry ->
        write(root, kernel, "reviewer.md", connectionPage("reviewer", "colour: green\n"))
        write(root, kernel, "client.md", connectionPage("client", "colour: Gray\n"))
        write(root, kernel, "former.md", connectionPage("former", "colour: red\n", retired = true))
        write(root, kernel, "odd.md", connectionPage("odd", "colour: chartreuse\n"))
        val tones = loadPillTones(kernel, registry.editorDecorations(kernel.plugins()))

        assertTrue(tones.ready)
        assertEquals(PillTone.GREEN, tones.toneOf(item("reviewer")))
        assertEquals(PillTone.GREY, tones.toneOf(item("client")), "gray is forgiven for grey")
        assertEquals(PillTone.ACCENT, tones.toneOf(item("assign")), "a page with no colour draws the accent")
        assertEquals(PillTone.ACCENT, tones.toneOf(item("odd")), "a colour the pill cannot draw is the accent too")
        assertEquals(PillTone.GREY, tones.toneOf(item("former")), "a retired connection is not live")
        assertEquals(PillTone.GREY, tones.toneOf(item("asign")), "a keyword no page defines")
    }

    @Test
    fun aChangedPageChangesThePillOnTheNextLookup() = fixture { root, kernel, registry ->
        write(root, kernel, "reviewer.md", connectionPage("reviewer", "colour: green\n"))
        write(root, kernel, "reviewer.md", connectionPage("reviewer", "colour: purple\n"))
        assertEquals(PillTone.PURPLE, loadPillTones(kernel, registry.editorDecorations(kernel.plugins())).toneOf(item("reviewer")))
    }

    @Test
    fun withNothingToAskEveryPillIsTheAccentNotGrey() {
        val waiting = PillToneMap(setOf(PEOPLE_CONNECTION_STYLE))
        assertFalse(waiting.ready)
        assertEquals(PillTone.ACCENT, waiting.toneOf(item("anything")), "no flash of grey while the index is built")
        val unsourced = PillToneMap(ready = true)
        assertEquals(PillTone.ACCENT, unsourced.toneOf(pillItem("another.plugin.pill", "x")), "a registration with no source")
    }

    @Test
    fun theFormWritesANamedColourAndRefusesAnyOther() {
        val draft = connectionDraft(mapOf("keyword" to "reviewer", "colour" to "Green"))
        assertTrue(draft.source.contains("colour: \"green\"") || draft.source.contains("colour: green"), draft.source)
        assertFalse(connectionDraft(mapOf("keyword" to "reviewer")).source.contains("colour"), "left empty, nothing is written")
        assertFailsWith<IllegalArgumentException> { connectionDraft(mapOf("keyword" to "reviewer", "colour" to "chartreuse")) }
    }
}
