package org.gilune.plugin.sdk

object Slots {
    const val CAPTURE_ACTIONS = "capture.actions"
    const val NOTE_ACTIONS = "note.actions"
    const val NAVIGATION = "navigation"
    const val COMMAND_PALETTE = "command.palette"
    const val SETTINGS_FORM = "settings.form"

    /// The editor seams ADR 0015 § 5 adds. Shell-published: anyone may
    /// contribute, and the shell renders what they declare.
    const val EDITOR_DECORATIONS = "editor.decorations"
    const val EDITOR_SUGGEST = "editor.suggest"
    const val SEARCH_TYPES = "search.types"
    const val SEARCH_OPERATORS = "search.operators"
    const val SUGGEST_VALUES = "suggest.values"
    const val INDEXED_LOOKUP = "lookup.indexed"
    const val EDITOR_COMPLETIONS = "editor.completions"
    const val DOCUMENT_VIEWS = "document.views"
    const val DOCUMENT_REFACTORINGS = "document.refactorings"

    /// A contributed renderer for a fenced block. Unknown fences remain text.
    const val EDITOR_BLOCKS = "editor.blocks"

    /// "Go to definition" over a plugin's references: the plugin says what
    /// the caret is on, the host runs its lookup and opens the answer.
    const val EDITOR_DEFINITIONS = "editor.definitions"

    /// A folder a vault starts with because a plugin uses it. The host
    /// creates each one once per vault on a machine; one the user deletes
    /// stays deleted. Config: `{"folder":"Name"}`.
    const val VAULT_FOLDERS = "vault.folders"
}

/** A starting folder (`Slots.VAULT_FOLDERS`): a vault-relative path. */
fun vaultFolderContribution(id: String, folder: String) =
    uniffi.gilune_ffi.PluginContribution(Slots.VAULT_FOLDERS, id, folder, "{\"folder\":${pageJsonString(folder)}}")

