// The plugin as the host sees it (ADR 0029 § 1, § 5): its declaration, the
// two shell-published slots it fills, and its keys — every one a setting
// with a default, so a person can rebind any of them.

package org.gilune.plugins.findreplace

import org.gilune.plugin.sdk.BundledPluginExtensions
import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.escapeJsonString
import org.gilune.plugin.sdk.jsonString
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo

const val FIND_REPLACE_PLUGIN = "org.memory.find-replace"

/// Recorded with every plan replacing across notes, so the journal says
/// which language matched (ADR 0026's provider version).
const val FIND_REPLACE_VERSION = "1"

fun findReplaceDeclaration(): PluginDeclaration = PluginDeclaration(
    pluginId = FIND_REPLACE_PLUGIN,
    name = "Find and replace",
    `class` = "domain",
    profile = "bundled",
    required = false,
    processorId = null,
    subscribes = null,
    emits = emptyList(),
    // Writes go through the host's reviewed journal, never the plugin.
    capabilities = emptyList(),
    contributions = listOf(
        PluginContribution(Slots.EDITOR_FIND, "$FIND_REPLACE_PLUGIN.note", "Find and replace in the note"),
        PluginContribution(Slots.SEARCH_REPLACE, "$FIND_REPLACE_PLUGIN.notes", "Replace across notes"),
    ),
)

fun findReplaceExtensions(): BundledPluginExtensions = BundledPluginExtensions(declaration = findReplaceDeclaration())

/// The plugin's keys: what the settings call each, its default, and what
/// the settings screen and shortcuts window say it does.
enum class FindReplaceKey(val setting: String, val default: String, val label: String) {
    FIND("findKey", "Ctrl+F", "Find in the note"),
    REPLACE("replaceKey", "Ctrl+H", "Replace in the note"),
    REPLACE_IN_NOTES("replaceInNotesKey", "Ctrl+Shift+H", "Replace across notes"),
    NEXT("nextKey", "F3", "Next match"),
    PREVIOUS("previousKey", "Shift+F3", "Previous match"),
}

/// The key as the settings bind it, or its default.
fun findReplaceKey(settings: String, key: FindReplaceKey): String =
    jsonString(settings, key.setting)?.trim()?.takeIf { it.isNotEmpty() } ?: key.default

fun List<PluginInfo>.findReplaceKey(key: FindReplaceKey): String =
    findReplaceKey(firstOrNull { it.pluginId == FIND_REPLACE_PLUGIN }?.settings.orEmpty(), key)

/// The settings document with one key rebound, or — `null` — back to its
/// default. Every other entry is carried through untouched.
fun findReplaceSettingsDocument(current: String, key: FindReplaceKey, value: String?): String {
    val others = Regex("\"" + Regex.escape(key.setting) + "\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*,?")
        .replace(current.trim().ifEmpty { "{}" }, "")
        .trim().removePrefix("{").removeSuffix("}").trim().trimEnd(',')
    val entries = listOfNotNull(
        value?.let { "\"${key.setting}\":\"${escapeJsonString(it)}\"" },
        others.takeIf { it.isNotEmpty() },
    )
    return "{" + entries.joinToString(",") + "}"
}
