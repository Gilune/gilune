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

    /// A contributed renderer for a fenced block. Unknown fences remain text.
    const val EDITOR_BLOCKS = "editor.blocks"
}

