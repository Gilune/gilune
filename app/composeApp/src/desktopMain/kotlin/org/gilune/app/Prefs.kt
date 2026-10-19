// Device-local preferences. These belong to this machine, not to the
// vault: which vault to open is a chicken-and-egg fact the vault cannot
// hold, and appearance is per-device by nature. Anything a plugin or the
// agent must also see lives in the kernel instead (ADR 0011).

package org.gilune.app

import java.util.prefs.Preferences

// `ThemeChoice` moved to `Palette.kt` with the colour schemes (ADR 0017
// § 2): both shells store the same three choices, so the enum is shared
// even though where it is stored is not.

object Prefs {
    private val node: Preferences = Preferences.userRoot().node("org/gilune/app")

    internal val noteAppearance = NoteAppearance({ node.get(it, null) }, { key, value -> node.put(key, value) })

    val navigation = NavigationVisibility(node.get("hiddenNavigation", "").split('\n').filter { it.isNotBlank() }.toSet()) {
        node.put("hiddenNavigation", it.sorted().joinToString("\n"))
    }

    var vaultDir: String
        get() = System.getProperty("gilune.vault")
            ?: node.get("vaultDir", System.getProperty("user.home") + "/Gilune")
        set(value) = node.put("vaultDir", value)

    var theme: ThemeChoice
        get() = runCatching { ThemeChoice.valueOf(node.get("theme", ThemeChoice.System.name)) }
            .getOrDefault(ThemeChoice.System)
        set(value) = node.put("theme", value.name)

    /// How wide the rail stands, in dp, and whether it is shut altogether
    /// (ADR 0011 amendment). Device-local facts like the theme: a vault
    /// opened on two machines is arranged twice. The width is remembered
    /// through a collapse, so reopening the rail brings back the rail the
    /// user had rather than the default one.
    var railWidth: Int
        get() = node.getInt("railWidth", DEFAULT_RAIL_WIDTH)
        set(value) = node.putInt("railWidth", value)

    var railCollapsed: Boolean
        get() = node.getBoolean("railCollapsed", false)
        set(value) = node.putBoolean("railCollapsed", value)

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

    /// Whether this machine has already given the vault one plugin-declared
    /// starting folder. Asked per folder, so a plugin installed later still
    /// gets its own, and a folder the user deletes is not put back.
    ///
    /// A vault given the old fixed set (Notes, Tasks, Captures — one flag
    /// for all three) counts as having had those, so none of them returns.
    fun folderGiven(vaultDir: String, folder: String): Boolean =
        node.getBoolean(folderKey(vaultDir, folder), false) ||
            (node.getBoolean(legacyFoldersKey(vaultDir), false) && folder.lowercase() in LEGACY_STARTING_FOLDERS)

    fun markFolderGiven(vaultDir: String, folder: String) = node.putBoolean(folderKey(vaultDir, folder), true)

    private fun folderKey(vaultDir: String, folder: String): String =
        "folder-" + Integer.toHexString(vaultDir.hashCode()) + "-" + Integer.toHexString(folder.lowercase().hashCode())

    private fun legacyFoldersKey(vaultDir: String): String =
        "folders-" + Integer.toHexString(vaultDir.hashCode())

    private val LEGACY_STARTING_FOLDERS = setOf("notes", "tasks", "captures")

    private const val MAX_LAYOUT_CHARS = 7500

    private const val DEFAULT_RAIL_WIDTH = 240
}
