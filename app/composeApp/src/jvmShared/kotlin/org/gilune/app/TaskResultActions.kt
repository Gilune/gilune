// Checkbox actions from query results share the editor's decoration chain.
// A result is a snapshot: only the exact source line it displayed may change.
package org.gilune.app

import org.gilune.plugin.sdk.EditorDecoration
import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.TokenMenu
import org.gilune.plugin.sdk.TokenMenuItem
import org.gilune.plugins.tasks.TASKS_PLUGIN
import org.gilune.plugins.tasks.TaskHit
import org.gilune.plugins.tasks.tasksDecorations
import org.gilune.plugins.tasks.tasksFilter
import org.gilune.plugins.tasks.tasksWeekStart
import uniffi.gilune_ffi.PluginInfo
import java.io.File
import java.time.LocalDate

/** Returns a rewritten document only while the displayed task line still matches. */
fun toggleTaskLine(
    current: String,
    bodyLine: Int,
    expected: String,
    decorations: List<EditorDecoration> = builtInDecorations(),
): String? = rewriteTaskLine(current, bodyLine, expected) { activateLine(it, decorations) }

/** The same guard for any one-line rewrite — a menu item from a task row. */
fun rewriteTaskLine(current: String, bodyLine: Int, expected: String, rewrite: (String) -> String?): String? {
    val (frontMatter, body) = splitNoteRaw(current)
    val lines = body.split('\n')
    if (bodyLine !in lines.indices || lines[bodyLine] != expected) return null
    val rewritten = rewrite(lines[bodyLine]) ?: return null
    if (rewritten == lines[bodyLine]) return null
    return assembleNote(frontMatter, lines.toMutableList().also { it[bodyLine] = rewritten }.joinToString("\n"))
}

/** The registrations a task row acts through: the editor's, while the tasks plugin styles dates. */
fun taskResultDecorations(plugins: List<PluginInfo>): List<EditorDecoration> =
    if (plugins.pluginContributes(TASKS_PLUGIN, Slots.EDITOR_DECORATIONS)) {
        builtInDecorations() + tasksDecorations(plugins.tasksFilter(), weekStart = plugins.tasksWeekStart())
    } else builtInDecorations()

/** The menu a task row offers — the one its line would offer in the editor. */
fun taskResultMenu(plugins: List<PluginInfo>, hit: TaskHit, today: LocalDate = LocalDate.now()): TokenMenu? =
    lineMenuAt(hit.checkboxLine, 0, taskResultDecorations(plugins), today)?.menu

internal fun toggleTaskResult(state: GiluneState, hit: TaskHit) {
    val decorations = taskResultDecorations(state.plugins.toList())
    changeTaskResult(state, hit) { toggleTaskLine(it, hit.line - 1, hit.checkboxLine, decorations) }
}

internal fun applyTaskResultMenu(state: GiluneState, hit: TaskHit, item: TokenMenuItem) {
    changeTaskResult(state, hit) { rewriteTaskLine(it, hit.line - 1, hit.checkboxLine, item.rewrite) }
}

private fun changeTaskResult(state: GiluneState, hit: TaskHit, rewrite: (String) -> String?) {
    runCatching {
        synchronized(DocumentEditGate.monitor) {
            check(!DocumentEditGate.dirty(state.vaultDir, hit.relPath)) {
                "${hit.relPath} has unsaved edits. Save them before changing this task."
            }
            val file = File(state.vaultDir, hit.relPath)
            val current = file.readText()
            val updated = rewrite(current)
            check(updated != null) { "That task changed; the overview has been refreshed." }
            file.writeText(updated)
        }
    }.onFailure { state.message.value = "Could not update task: ${it.message}" }
    state.noteSaved(hit.relPath)
}
