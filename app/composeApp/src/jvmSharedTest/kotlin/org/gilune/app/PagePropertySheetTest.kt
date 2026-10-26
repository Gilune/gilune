// Page property sheets (owner, 2026-10-26): a page type's editable fields in
// its properties section, starting with People connections. These rows pin
// what a sheet reads, what one edit writes, and when a page has a sheet;
// PagePropertySheetUiTest drives it through the real editor.

package org.gilune.app

import org.gilune.plugin.sdk.PageProperty
import org.gilune.plugins.people.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PagePropertySheetTest {
    private val page = "---\ngilune-type: $CONNECTION_TYPE\ngilune-id: reviewer-page\nkeyword: \"reviewer\"\n" +
        "label: \"Reviewer\"\nretired: false\n---\n# Notes\n\nSomething written here.\n"

    private fun installed(state: String = "enabled") =
        installedPlugin(PEOPLE_PLUGIN, state, peopleExtensions().declaration.contributions)

    @Test
    fun aConnectionPageHasTheSheetWhileThePluginIsOn() {
        val registry = PluginExtensions(listOf(peopleExtensions()))
        val sheet = assertNotNull(registry.pagePropertySheet(listOf(installed()), CONNECTION_TYPE))
        assertEquals("People connection", sheet.title)
        assertEquals(listOf("label", "keyword", "retired", "colour"), sheet.properties.map { it.key })
        assertTrue((sheet.properties[1] as PageProperty.ReadOnly).renameReferences, "a keyword is renamed everywhere, not edited here")
        assertNull(registry.pagePropertySheet(listOf(installed("disabled")), CONNECTION_TYPE), "switched off, the section is raw again")
        assertNull(registry.pagePropertySheet(listOf(installed()), PERSON_TYPE), "people pages have no sheet yet")
        assertNull(registry.pagePropertySheet(listOf(installed()), null))
    }

    @Test
    fun theSheetReadsThePagesOwnValues() {
        val values = assertNotNull(sheetValues(page))
        assertEquals("Reviewer", values["label"], "a string's own words, not its quotes")
        assertEquals("reviewer", values["keyword"])
        assertEquals("false", values["retired"])
        assertNull(values["colour"], "no colour written yet")
    }

    @Test
    fun eachEditIsOnePropertyAndTheRestOfThePageIsUntouched() {
        val sheet = connectionPropertySheet()
        fun property(key: String) = sheet.properties.single { it.key == key }

        val coloured = sourceWithChange(page, pagePropertyChange(property("colour"), "green"))
        assertTrue(coloured.contains("colour: \"green\""), coloured)
        assertTrue(coloured.endsWith("# Notes\n\nSomething written here.\n"), "the body is the person's and stays theirs")

        val retired = sourceWithChange(coloured, pagePropertyChange(property("retired"), "true"))
        assertTrue(retired.contains("retired: true"), "a switch writes a boolean, not a string")

        val relabelled = sourceWithChange(retired, pagePropertyChange(property("label"), "Code reviewer"))
        assertEquals("Code reviewer", sheetValues(relabelled)?.get("label"))

        // Default removes the property, and the page reads as before it.
        val plain = sourceWithChange(coloured, pagePropertyChange(property("colour"), null))
        assertEquals(page, plain)
        assertFalse(plain.contains("colour"))
    }
}
