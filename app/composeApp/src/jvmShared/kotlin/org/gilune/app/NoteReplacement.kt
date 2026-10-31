// Replacing across notes (ADR 0031 § 4) on ADR 0026's journal: the host
// reads the notes in scope, shows every match, and — asked — writes one
// reviewed plan whose entries are body-only edits against the very bytes the
// preview read. Applying, refusing unsaved buffers, and undo are the
// journal's; nothing here writes a file.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.gilune.plugin.sdk.SearchType
import org.gilune.plugins.findreplace.FIND_REPLACE_PLUGIN
import org.gilune.plugins.findreplace.FIND_REPLACE_VERSION
import org.gilune.plugins.findreplace.FindMatch
import org.gilune.plugins.findreplace.FindOutcome
import org.gilune.plugins.findreplace.FindQuery
import org.gilune.plugins.findreplace.findMatches
import uniffi.gilune_ffi.DocumentState
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.RefactoringPlan
import uniffi.gilune_ffi.SourceSpan
import uniffi.gilune_ffi.SourceTextEdit
import uniffi.gilune_ffi.inspectSourceText

/// One note's matches as the preview read it. `source` is the whole file,
/// kept so the plan can refuse a note that changed after it was shown;
/// match offsets are into the body, which starts at `bodyStart`.
internal data class NoteFind(
    val relPath: String,
    val source: String,
    val bodyStart: Int,
    val matches: List<FindMatch>,
    val unsaved: Boolean,
) {
    val body: String get() = source.substring(bodyStart)

    /// The lines that hold a match: 1-based number, the line's text, and
    /// each match's columns within it.
    fun lines(limit: Int = Int.MAX_VALUE): List<MatchedLine> {
        val text = body
        return matches.groupBy { text.lastIndexOf('\n', it.start - 1) + 1 }.entries.take(limit).map { (start, inLine) ->
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            MatchedLine(
                number = text.substring(0, start).count { it == '\n' } + 1,
                text = text.substring(start, end),
                ranges = inLine.map { (it.start - start) until (it.end - start).coerceAtMost(end - start) },
            )
        }
    }
}

internal data class NotesFind(val notes: List<NoteFind> = emptyList(), val problem: String? = null) {
    val matchCount: Int get() = notes.sumOf { it.matches.size }
}

/// The notes a replace may touch: every Markdown file in the vault when
/// the search is blank, otherwise the notes the search answers.
internal fun notesInScope(
    kernel: Gilune,
    vault: String,
    search: String,
    operators: SearchOperators,
    types: List<SearchType>,
    today: LocalDate,
    cancelled: () -> Boolean = { false },
): List<String> {
    if (search.isBlank()) return vaultMarkdown(vault)
    val reader = compileSearch(search, operators, types, today)?.prepare(kernel) ?: return emptyList()
    try {
        val paths = linkedSetOf<String>()
        var cursor: String? = null
        do {
            val page = reader.page(cursor)
            if (!page.indexComplete) throw SearchIndexPending(page.issues)
            page.hits.mapTo(paths) { it.relPath }
            cursor = page.nextCursor
        } while (cursor != null && !cancelled())
        return paths.toList()
    } finally {
        reader.cancel()
    }
}

/// Every Markdown file under the vault, as the journal's own discovery
/// walks it: the local application folder and links out of the vault left
/// alone.
internal fun vaultMarkdown(vault: String): List<String> {
    val root = File(vault).canonicalFile
    return root.walkTopDown().onEnter { directory ->
        directory == root || (directory.name != ".gilune" && !Files.isSymbolicLink(directory.toPath()))
    }.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .sorted()
        .toList()
}

/// Reads each note in scope once and finds the pattern in its body. A note
/// with unsaved edits is still shown — from disk — and marked, because the
/// journal will refuse it until it is saved.
internal fun findInNotes(
    vault: String,
    paths: List<String>,
    query: FindQuery,
    replacement: String?,
    cancelled: () -> Boolean = { false },
): NotesFind {
    if (query.pattern.isEmpty()) return NotesFind()
    val notes = mutableListOf<NoteFind>()
    for (path in paths) {
        if (cancelled()) break
        val source = runCatching { File(vault, path).readText() }.getOrNull() ?: continue
        val bodyStart = bodyStartOf(source)
        when (val outcome = findMatches(source.substring(bodyStart), query, replacement)) {
            is FindOutcome.Invalid -> return NotesFind(problem = outcome.reason)
            is FindOutcome.Found -> if (outcome.matches.isNotEmpty()) {
                notes += NoteFind(path, source, bodyStart, outcome.matches, DocumentEditGate.dirty(vault, path))
            }
        }
    }
    return NotesFind(notes)
}

/// Where the body begins, in characters, by the kernel's own reading of
/// the header — the same boundary the plan's protected ranges use.
private fun bodyStartOf(source: String): Int = charOffset(source, inspectSourceText(source).bodyStart.toLong())

private fun charOffset(text: String, bytes: Long): Int {
    var counted = 0L
    var index = 0
    while (index < text.length && counted < bytes) {
        val code = text.codePointAt(index)
        counted += String(Character.toChars(code)).toByteArray(Charsets.UTF_8).size
        index += Character.charCount(code)
    }
    return index
}

/// What applying a replace came to: the plan (listed in Refactor history,
/// undoable), how many matches were written, and the notes that were not
/// changed with the reason for each.
internal data class NotesReplaced(val plan: RefactoringPlan, val replaced: Int, val notes: Int, val skipped: List<Pair<String, String>>)

/// Writes [replacement] over every match in the ticked notes: one reviewed
/// plan, each entry checked against the bytes the preview read, applied
/// document by document through the journal.
internal fun replaceInNotes(
    kernel: Gilune,
    vault: String,
    notes: List<NoteFind>,
    query: FindQuery,
    replacement: String,
    cancelled: () -> Boolean = { false },
): NotesReplaced {
    kernel.reconcile()
    // A fresh revision marker may be needed before a verified snapshot (as
    // the rename discovery does).
    kernel.project()
    val configuration = refactoringConfiguration(
        mapOf(
            "pattern" to query.pattern,
            "replacement" to replacement,
            "matchCase" to query.options.matchCase.toString(),
            "wholeWord" to query.options.wholeWord.toString(),
            "regex" to query.options.regex.toString(),
        ),
    )
    val plan = kernel.createRefactoringPlan(FIND_REPLACE_PLUGIN, FIND_REPLACE_VERSION, configuration,
        "Replace “${query.pattern}” with “$replacement”")
    var expected = 0
    for (note in notes) {
        if (cancelled()) break
        try {
            check(!DocumentEditGate.dirty(vault, note.relPath)) { "Unsaved edits: save the note and preview again." }
            val snapshot = kernel.documentSnapshot(note.relPath)
            check(snapshot.source.state in listOf(DocumentState.MANAGED, DocumentState.UNMANAGED)) {
                "The note needs resolving first (${snapshot.source.state})."
            }
            check(snapshot.content.text == note.source) { "The note changed after the preview. Preview again." }
            val matches = findMatches(note.body, query, replacement).let { (it as FindOutcome.Found).matches }
            val edits = bodyEdits(note.source, note.bodyStart, matches)
            if (edits.isEmpty()) continue
            kernel.addRefactoringEntry(plan.id, note.relPath, snapshot.source.version, edits)
            expected += matches.size
        } catch (e: Exception) {
            kernel.addRefactoringIssue(plan.id, note.relPath, e.message ?: "This note could not be reviewed.")
        }
    }
    kernel.sealRefactoringPlan(plan.id)
    val applied = applyDocumentRefactoring(kernel, vault, BUNDLED_PLUGIN_EXTENSIONS, plan.id, cancelled = cancelled)
    val entries = allEntries(kernel, plan.id)
    val done = entries.filter { it.state == "applied" }.map { it.relPath }.toSet()
    val skipped = entries.filter { it.state != "applied" }.map { it.relPath to it.detail.ifBlank { it.state } }
    val replaced = notes.filter { it.relPath in done }.sumOf { it.matches.size }
    return NotesReplaced(applied, replaced, done.size, skipped)
}

/// Puts a replace back: the journal's own guarded inverse, applied.
internal fun undoNotesReplacement(kernel: Gilune, vault: String, planId: String): RefactoringPlan {
    val inverse = kernel.prepareRefactoringUndo(planId)
    return applyDocumentRefactoring(kernel, vault, BUNDLED_PLUGIN_EXTENSIONS, inverse.id)
}

private fun allEntries(kernel: Gilune, plan: String) = buildList {
    var offset = 0uL
    do {
        val page = kernel.refactoringEntries(plan, offset, 100u)
        addAll(page)
        offset += page.size.toULong()
    } while (page.size == 100)
}

/// Each match as an exact byte span of the whole source, its replacement
/// beside it — the shape the journal validates.
internal fun bodyEdits(source: String, bodyStart: Int, matches: List<FindMatch>): List<SourceTextEdit> {
    var byte = source.substring(0, bodyStart).toByteArray(Charsets.UTF_8).size.toLong()
    var at = bodyStart
    return matches.map { match ->
        val start = bodyStart + match.start
        val end = bodyStart + match.end
        byte += source.substring(at, start).toByteArray(Charsets.UTF_8).size
        val startByte = byte
        byte += source.substring(start, end).toByteArray(Charsets.UTF_8).size
        at = end
        SourceTextEdit(SourceSpan(startByte.toULong(), byte.toULong()), requireNotNull(match.replacement))
    }
}
