// The shortcuts window's catalogue (owner ask, 2026-10-27: every keyboard
// shortcut, from the app and every plugin, in one place). The step past
// the obvious: no two rows claim the same keys with the default bindings,
// and a plugin's row follows the binding its settings hold.

package org.gilune.app

import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutsTest {
    private val keystrokeRows = setOf("Enter", "Enter twice", "Tab", "Shift+Tab", "Shift+Enter", "Ctrl+]", "Ctrl+[")

    @Test
    fun everyFormattingActionIsListedUnderItsOwnKeys() {
        val formatting = shortcutGroups(emptyList()).single { it.title == "Formatting" }
        assertEquals(FORMAT_ACTIONS.map { it.keys to it.label }, formatting.shortcuts.map { it.keys to it.action })
    }

    @Test
    fun noTwoRowsClaimTheSameKeys() {
        val rows = shortcutGroups(emptyList()).flatMap { it.shortcuts }
        // Nest/lift are listed twice on purpose (Tab and Ctrl+]).
        val claimed = rows.map { it.keys }.filter { it !in keystrokeRows }
        assertEquals(claimed, claimed.distinct(), "a key named twice: ${claimed.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun theEditorMatchesExactlyTheKeysTheListNames() {
        assertEquals("Bold", formatActionFor("Ctrl+B")?.label)
        assertEquals("Numbered list", formatActionFor("Ctrl+Shift+7")?.label)
        // Modifiers are part of the keystroke: Ctrl+7 is the window's tab key.
        assertNull(formatActionFor("Ctrl+7"))
        assertNull(formatActionFor("Ctrl+Alt+B"))
    }

    @Test
    fun aPluginRowAppearsOnlyWhileItIsOnAndShowsItsOwnBinding() {
        val tasksOff = plugin("org.memory.tasks", "Tasks", "disabled", """{"taskShortcut":"Ctrl+Shift+L"}""")
        assertNull(shortcutGroups(listOf(tasksOff)).firstOrNull { it.title == "Tasks" })

        val tasksOn = plugin("org.memory.tasks", "Tasks", "enabled", """{"taskShortcut":"Ctrl+Shift+L"}""")
        val group = assertNotNull(shortcutGroups(listOf(tasksOn)).firstOrNull { it.title == "Tasks" })
        assertEquals(listOf("Ctrl+Shift+L"), group.shortcuts.map { it.keys })
    }

    @Test
    fun theFilterMatchesEveryWordInTheKeysOrTheAction() {
        val row = Shortcut("Ctrl+Shift+7", "Numbered list")
        assertTrue(row.matches("number"))
        assertTrue(row.matches("ctrl+shift"))
        assertTrue(row.matches("list shift"))
        assertTrue(row.matches(""))
        assertFalse(row.matches("bold"))
    }

    private fun plugin(id: String, name: String, state: String, settings: String) = PluginInfo(
        pluginId = id,
        name = name,
        `class` = "builtin",
        profile = "",
        required = false,
        state = state,
        processorId = null,
        subscribes = null,
        emits = emptyList(),
        capabilities = emptyList(),
        granted = emptyList(),
        ungranted = emptyList(),
        contributions = emptyList(),
        settings = settings,
    )
}
