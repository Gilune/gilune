// The people plugin in the editor (owner, 2026-10-19): a mention is drawn
// as a link by the same rule the index records it, and go to definition —
// F12 unless the plugin's settings say otherwise — names the person under
// the caret.

package org.gilune.app

import org.gilune.plugins.people.DEFAULT_DEFINITION_KEY
import org.gilune.plugins.people.PEOPLE_PLUGIN
import org.gilune.plugins.people.mentionsInLine
import org.gilune.plugins.people.peopleDefinitionKey
import org.gilune.plugins.people.peopleDefinitionRegistration
import org.gilune.plugins.people.peopleMentionDecoration
import org.gilune.plugins.people.peopleSettingsDocument
import org.gilune.plugin.sdk.DecorationRole
import uniffi.gilune_ffi.inspectSourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeopleEditingTest {
    private fun words(line: String) = mentionsInLine(line).map { line.substring(it.first, it.last + 1) }

    @Test
    fun aMentionIsTheAtAndTheHandleAndNothingThatMerelyContainsAnAt() {
        assertEquals(listOf("@alice.smith", "@bob"), words("talk to @alice.smith and @bob."))
        assertEquals(listOf("@carol"), words("(@carol) mail me@example.com or see https://x.org/@dave"))
        assertEquals(emptyList(), words("an escaped \\@alice, and code `@alice` stay text"))
        assertEquals(listOf("@ali"), words("owner: @ali"))
    }

    @Test
    fun theDecorationStylesMentionsAsLinksWithoutRewritingThem() {
        val decoration = peopleMentionDecoration()
        assertEquals(DecorationRole.LINK, decoration.role)
        val line = "- [ ] ask @alice.smith"
        assertEquals(listOf("@alice.smith"), decoration.recognise(line).map { line.substring(it.range.first, it.range.last + 1) })
        assertNull(decoration.activate, "styling never writes")
    }

    @Test
    fun theKeyIsF12UntilTheSettingsSayOtherwiseAndOtherKeysSurviveAChange() {
        assertEquals(DEFAULT_DEFINITION_KEY, peopleDefinitionKey(""))
        assertEquals("F12", DEFAULT_DEFINITION_KEY)
        val set = peopleSettingsDocument("{\"future\":true}", "Ctrl+Alt+G")
        assertEquals("Ctrl+Alt+G", peopleDefinitionKey(set))
        assertEquals(true, org.gilune.plugin.sdk.jsonBoolean(set, "future"), "another key is carried through")
        val reset = peopleSettingsDocument(set, null)
        assertEquals(DEFAULT_DEFINITION_KEY, peopleDefinitionKey(reset))
        assertEquals(true, org.gilune.plugin.sdk.jsonBoolean(reset, "future"))
        assertEquals("{}", peopleSettingsDocument("", null))
    }

    @Test
    fun theCaretAnywhereOnAMentionNamesThePersonAndOffItNothing() {
        val text = "---\ngilune-id: n\n---\nsee @alice.smith today"
        val snapshot = inspectSourceText(text)
        val at = text.indexOf("@alice")
        val registration = peopleDefinitionRegistration()
        for (offset in listOf(at, at + 3, at + "@alice.smith".length)) {
            assertEquals("@alice.smith", registration.definitionAt(snapshot, offset)?.label, "caret at $offset")
        }
        assertNull(registration.definitionAt(snapshot, at - 2), "on 'see', not a mention")
        assertNull(registration.definitionAt(snapshot, text.length), "after 'today'")
    }

    @Test
    fun bindingsCarryTheOwnersKeyAndLeaveWithThePlugin() {
        val people = org.gilune.plugins.people.peopleExtensions().declaration
        fun installed(state: String, settings: String = "") =
            installedPlugin(PEOPLE_PLUGIN, state, people.contributions, settings)
        assertEquals(listOf("F12"), BUNDLED_PLUGIN_EXTENSIONS.definitions(listOf(installed("enabled"))).map { it.key })
        assertEquals(listOf("Ctrl+Alt+G"), BUNDLED_PLUGIN_EXTENSIONS.definitions(
            listOf(installed("enabled", peopleSettingsDocument("", "Ctrl+Alt+G")))).map { it.key })
        assertEquals(emptyList(), BUNDLED_PLUGIN_EXTENSIONS.definitions(listOf(installed("disabled"))))
        assertEquals(emptyList(), BUNDLED_PLUGIN_EXTENSIONS.editorDecorations(listOf(installed("disabled"))),
            "switched off, mentions are plain text again")
        assertEquals(1, BUNDLED_PLUGIN_EXTENSIONS.editorDecorations(listOf(installed("enabled"))).size)
    }
}
