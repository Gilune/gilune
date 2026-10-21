// Opening a note at a line (owner, 2026-10-21): a task clicked in a list,
// or a matched line in search, opens its note with the caret on that line
// rather than at the top. The line is a request the next editor to open
// that note answers; whatever opened the note stays as it was.

package org.gilune.app

/** A line of a note's body: 1-based, as task hits and search matches count
 * them. `text` is what the line said when it was found, so a note edited
 * since can still be met at the right line. */
data class NoteLine(val relPath: String, val line: Int, val text: String? = null)

/** Where the caret goes for `target` in `body`: the end of the line it
 * names. If that line no longer reads `text`, the first line that does is
 * taken instead — the note moved on since it was indexed — and failing
 * both, the numbered line kept within the body. Null for an empty body. */
fun revealOffset(body: String, line: Int, text: String? = null): Int? {
    if (body.isEmpty()) return null
    val lines = body.split('\n')
    val numbered = (line - 1).coerceIn(0, lines.lastIndex)
    val index = when {
        text == null || lines[numbered] == text -> numbered
        else -> lines.indexOf(text).takeIf { it >= 0 } ?: numbered
    }
    return lines.take(index).sumOf { it.length + 1 } + lines[index].length
}
