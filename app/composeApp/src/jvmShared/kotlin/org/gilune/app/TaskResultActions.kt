// Checkbox actions from query results share the editor's decoration chain.
// A result is a snapshot: only the exact source line it displayed may change.
package org.gilune.app

import org.gilune.plugin.sdk.EditorDecoration
import org.gilune.plugin.sdk.Slots
import org.gilune.plugins.tasks.TASKS_PLUGIN
import org.gilune.plugins.tasks.TaskHit
import org.gilune.plugins.tasks.tasksDecorations
import org.gilune.plugins.tasks.tasksFilter
import java.io.File

/** Returns a rewritten document only while the displayed task line still matches. */
fun toggleTaskLine(
    current: String,
    bodyLine: Int,
    expected: String,
    decorations: List<EditorDecoration> = builtInDecorations(),
): String? {
    val (frontMatter, body) = splitNoteRaw(current)
    val lines = body.split('\n')
    if (bodyLine !in lines.indices || lines[bodyLine] != expected) return null
    val rewritten = activateLine(lines[bodyLine], decorations) ?: return null
    if (rewritten == lines[bodyLine]) return null
    return assembleNote(frontMatter, lines.toMutableList().also { it[bodyLine] = rewritten }.joinToString("\n"))
}

internal fun toggleTaskResult(state: GiluneState, hit: TaskHit) {
    val plugins = state.plugins.toList()
    val decorations = if (plugins.pluginContributes(TASKS_PLUGIN, Slots.EDITOR_DECORATIONS)) {
        builtInDecorations() + tasksDecorations(plugins.tasksFilter())
    } else builtInDecorations()
    runCatching {
        synchronized(DocumentEditGate.monitor) {
            check(!DocumentEditGate.dirty(state.vaultDir, hit.relPath)) {
                "${hit.relPath} has unsaved edits. Save them before changing this task."
            }
            val file = File(state.vaultDir, hit.relPath)
            val current = file.readText()
            val updated = toggleTaskLine(current, hit.line - 1, hit.checkboxLine, decorations)
            check(updated != null) { "That task changed; the overview has been refreshed." }
            file.writeText(updated)
        }
    }.onFailure { state.message.value = "Could not update task: ${it.message}" }
    state.noteSaved(hit.relPath)
}
