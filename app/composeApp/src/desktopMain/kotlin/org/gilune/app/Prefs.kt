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

    /// Whether notes open reading (rendered) or editing (styled source).
    /// A per-note toggle overrides it; the mode is never written into a
    /// document. Editing is the default — this is a capture tool, not a
    /// publishing tool.
    var readingMode: Boolean
        get() = node.getBoolean("readingMode", false)
        set(value) = node.putBoolean("readingMode", value)

    /// The document region's layout, per vault: which notes are open, in
    /// which panes, and which is in front (ADR 0014). Device-local like
    /// theme, and never the vault's business — a vault opened on two
    /// machines is arranged twice.
    fun tabsLayout(vaultDir: String): String? = node.get(layoutKey(vaultDir), null)

    /// A `Preferences` value stops at 8 KiB, so an outsized layout is
    /// dropped rather than truncated: the last good one stands, and the
    /// tabs it names still come back next launch.
    fun saveTabsLayout(vaultDir: String, serialised: String) {
        if (serialised.length > MAX_LAYOUT_CHARS) return
        node.put(layoutKey(vaultDir), serialised)
    }

    /// A key stops at 80 characters, and a vault path does not, so the
    /// path is named by its hash. A collision costs one vault a restored
    /// layout; the restore is defensive by contract.
    private fun layoutKey(vaultDir: String): String =
        "tabs-" + Integer.toHexString(vaultDir.hashCode())

    private const val MAX_LAYOUT_CHARS = 7500
}
