// What sits under a task (owner, 2026-10-21): its notes — lines indented
// under it that are not list items of their own — and its subtasks, the
// task lines nested one level in. Lists show it folded behind a marker on
// the task's row; a click opens one row, and `show:notes` in a filter block
// opens them all.
//
// Read from the note itself rather than from the index: the index answers
// which tasks match, but a subtask that does not match (ticked, under
// `is:open`) still counts towards its parent's progress, and the file is
// the one place that knows it is there.

package org.gilune.app

import org.gilune.plugins.tasks.TaskHit
import java.io.File

/** A subtask line: 1-based body line, its text as written (the guard a
 * tick checks against), and whether it is ticked. */
data class SubtaskLine(val line: Int, val text: String, val done: Boolean)

/** Everything under one task, in the order it is written. */
data class TaskDetail(val notes: List<String>, val subtasks: List<SubtaskLine>) {
    val isEmpty get() = notes.isEmpty() && subtasks.isEmpty()
    val lineCount get() = notes.size + subtasks.size

    /** The marker's words: `2 notes · 1/3 done`. */
    val summary: String get() = listOfNotNull(
        notes.size.takeIf { it > 0 }?.let { if (it == 1) "1 note" else "$it notes" },
        subtasks.takeIf { it.isNotEmpty() }?.let { "${it.count(SubtaskLine::done)}/${it.size} done" },
    ).joinToString(" · ")
}

/** The key a row's detail and its open state are kept under. */
fun TaskHit.detailKey(): String = "$relPath:$line"

/** The task on 1-based `line` of `body`: the lines indented deeper than it
 * belong to it, blank lines included only where more of it follows. Lines
 * under a subtask are that subtask's, not the parent's notes. */
fun taskDetailAt(body: String, line: Int): TaskDetail {
    val lines = body.split('\n')
    val at = line - 1
    if (at !in lines.indices) return TaskDetail(emptyList(), emptyList())
    val base = indentWidth(lines[at])
    val block = mutableListOf<Int>()
    var next = at + 1
    while (next < lines.size) {
        val text = lines[next]
        if (text.isBlank()) {
            val resumes = (next + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
            if (resumes == null || indentWidth(lines[resumes]) <= base) break
        } else if (indentWidth(text) <= base) {
            break
        } else {
            block += next
        }
        next++
    }
    val notes = mutableListOf<String>()
    val subtasks = mutableListOf<SubtaskLine>()
    var subtaskIndent: Int? = null
    for (index in block) {
        val text = lines[index]
        val indent = indentWidth(text)
        val owner = subtaskIndent
        if (owner != null && indent > owner) continue // the subtask's own
        subtaskIndent = null
        val marker = DETAIL_TASK_LINE.find(text)
        if (marker != null) {
            subtasks += SubtaskLine(index + 1, text, marker.groupValues[1] != " ")
            subtaskIndent = indent
        } else {
            val trimmed = text.trim()
            notes += DETAIL_BULLET.find(trimmed)?.let { "• " + trimmed.substring(it.value.length) } ?: trimmed
        }
    }
    return TaskDetail(notes, subtasks)
}

/** The detail of every row with any, reading each note once. A note that
 * cannot be read contributes nothing — a list is never stopped by one. */
fun taskDetails(vaultDir: String, rows: List<TaskHit>): Map<String, TaskDetail> =
    rows.groupBy { it.relPath }.flatMap { (relPath, hits) ->
        val body = runCatching { splitNoteRaw(File(vaultDir, relPath).readText()).second }.getOrNull()
            ?: return@flatMap emptyList()
        hits.mapNotNull { hit -> taskDetailAt(body, hit.line).takeIf { !it.isEmpty }?.let { hit.detailKey() to it } }
    }.toMap()

/** The rows to list, leaving out each subtask whose parent is also listed:
 * it is shown under its parent's marker instead, not twice (owner,
 * 2026-10-21). A subtask whose parent is not listed keeps its own row. */
fun withoutListedSubtasks(rows: List<TaskHit>, details: Map<String, TaskDetail>): List<TaskHit> {
    val underAListedParent = rows.flatMap { parent ->
        details[parent.detailKey()]?.subtasks.orEmpty().map { "${parent.relPath}:${it.line}" }
    }.toSet()
    return rows.filterNot { it.detailKey() in underAListedParent }
}

private val DETAIL_TASK_LINE =Regex("""^[ \t]*[-*+][ \t]+\[([ xX])]""")
private val DETAIL_BULLET = Regex("""^[-*+][ \t]+""")

/** Indentation in columns, a tab counting as four as CommonMark has it. */
private fun indentWidth(line: String): Int {
    var width = 0
    for (char in line) {
        when (char) {
            ' ' -> width++
            '\t' -> width += 4 - width % 4
            else -> return width
        }
    }
    return width
}
