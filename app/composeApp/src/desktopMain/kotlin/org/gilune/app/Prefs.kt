// Device-local preferences. These belong to this machine, not to the
// vault: which vault to open is a chicken-and-egg fact the vault cannot
// hold, and appearance is per-device by nature. Anything a plugin or the
// agent must also see lives in the kernel instead (ADR 0011).

package org.gilune.app

import java.util.prefs.Preferences

enum class ThemeChoice { System, Light, Dark }

object Prefs {
    private val node: Preferences = Preferences.userRoot().node("org/gilune/app")

    var vaultDir: String
        get() = System.getProperty("gilune.vault")
            ?: node.get("vaultDir", System.getProperty("user.home") + "/Gilune")
        set(value) = node.put("vaultDir", value)

    var theme: ThemeChoice
        get() = runCatching { ThemeChoice.valueOf(node.get("theme", ThemeChoice.System.name)) }
            .getOrDefault(ThemeChoice.System)
        set(value) = node.put("theme", value.name)

    /// Whether notes open rendered or as raw Markdown. A per-note toggle
    /// overrides it; the mode is never written into a document.
    var readingMode: Boolean
        get() = node.getBoolean("readingMode", true)
        set(value) = node.putBoolean("readingMode", value)
}
