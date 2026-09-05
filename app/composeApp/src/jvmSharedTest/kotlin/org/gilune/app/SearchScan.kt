// The scan the index replaced, kept as the oracle it now is.
//
// This is `searchVault` as it shipped through T1–T4 and MB3: walk the
// vault, read every `.md`, judge each one with the query's terms. It was
// the production engine until stage P of ADR 0020, and it is here — in
// test sources, reachable by nothing the app ships — because the only
// convincing evidence that the index answers the same question is the
// old answer, computed the old way, on the same corpus.
//
// It reads the vault's files and is therefore the *definition*; the
// engine narrows before it reads and is the *implementation*. If they
// ever disagree, this one is right and `SearchEquivalenceTest` says so by
// name.

package org.gilune.app

import java.io.File
import java.time.LocalDate

/// Notes matching `query`, best first, by reading every file.
///
/// The grammar is `Search.kt`'s, unchanged: the parse, the terms, the
/// hit construction and the ranking are all shared with the engine, so
/// what differs between the two is exactly the set of notes considered
/// and nothing else. Markdown files only, dot-directories excluded, each
/// file read once; an unreadable file is skipped rather than failing the
/// search.
fun searchVault(
    vaultDir: String,
    query: String,
    operators: SearchOperators = SearchOperators(),
    types: List<SearchType> = listOf(NOTE_SEARCH_TYPE),
    today: LocalDate = LocalDate.now(),
    maxLines: Int = MAX_MATCHED_LINES,
): List<SearchHit> {
    val parsed = parseQuery(query, operators, types, today) ?: return emptyList()
    val root = File(vaultDir)
    if (!root.isDirectory) return emptyList()
    val extract = parsed.type.extract
    return root.walkTopDown()
        .onEnter { it == root || !it.name.startsWith(".") }
        .filter { it.isFile && it.name.endsWith(".md") }
        .mapNotNull { file -> searchedNote(vaultDir, file.relativeTo(root).invariantSeparatorsPath) }
        .mapNotNull { note ->
            if (extract == null) {
                hitFor(note, parsed.terms, maxLines)
            } else {
                lineHitFor(note, parsed.terms, extract, maxLines)
            }
        }
        .sortedWith(RANK)
        .toList()
}

/// A note as the language sees it, read off the disk. The name is the
/// file's stem and the path is `/`-separated and relative to the vault,
/// which is what every operator and every anchor already means by them.
internal fun searchedNote(vaultDir: String, relPath: String): SearchedNote? {
    val text = runCatching { java.io.File(vaultDir, relPath).readText() }.getOrNull() ?: return null
    val note = splitNote(text)
    return SearchedNote(
        relPath = relPath,
        name = relPath.substringAfterLast('/').removeSuffix(".md"),
        body = note.body,
        frontMatter = note.frontMatter,
    )
}


/// Name matches first, then more matches ahead of fewer, then path order
/// so results are stable.
internal val RANK = compareByDescending<SearchHit> { it.nameMatched }
    .thenByDescending { it.matchCount }
    .thenBy { it.relPath }

internal fun hitFor(note: SearchedNote, terms: List<ParsedTerm>, maxLines: Int): SearchHit? {
    if (!keeps(note, terms)) return null
    val bodyNeedles = bodyNeedles(terms)

    val lines = mutableListOf<MatchedLine>()
    var matchCount = 0
    var moreMatches = 0
    note.lines.forEachIndexed { index, text ->
        val ranges = rangesIn(text, bodyNeedles)
        if (ranges.isEmpty()) return@forEachIndexed
        matchCount += ranges.size
        if (lines.size < maxLines) lines.add(MatchedLine(index + 1, text, ranges)) else moreMatches++
    }
    return SearchHit(note.relPath, note.name, nameMatched(note, terms), lines, moreMatches, matchCount)
}

/// The line-level path (ADR 0015 § 4): the type says which lines are
/// candidates, and each is judged on its own as a narrowed note. Every
/// line that survives **is** a hit line, highlighted or not — a task kept
/// by `due:today` alone has nothing to emphasise and must still show.
internal fun lineHitFor(
    note: SearchedNote,
    terms: List<ParsedTerm>,
    extract: (SearchedNote) -> List<CandidateLine>,
    maxLines: Int,
): SearchHit? {
    val kept = extract(note).filter { keeps(note.narrowedTo(it.text), terms) }
    if (kept.isEmpty()) return null
    val bodyNeedles = bodyNeedles(terms)

    val lines = mutableListOf<MatchedLine>()
    var matchCount = 0
    var moreMatches = 0
    for (candidate in kept) {
        val ranges = rangesIn(candidate.text, bodyNeedles)
        matchCount += ranges.size
        if (lines.size < maxLines) lines.add(MatchedLine(candidate.number, candidate.text, ranges)) else moreMatches++
    }
    return SearchHit(note.relPath, note.name, nameMatched(note, terms), lines, moreMatches, matchCount)
}

private fun keeps(note: SearchedNote, terms: List<ParsedTerm>): Boolean {
    for (term in terms) {
        if (term.term.matches(note) == term.negated) return false
    }
    return true
}

// Only terms the note had to satisfy describe why it is here: a negated
// term is a reason it was not excluded, never a highlight.
private fun bodyNeedles(terms: List<ParsedTerm>): List<String> =
    terms.filterNot { it.negated }.mapNotNull { it.term.bodyNeedle }.filter { it.isNotEmpty() }

private fun nameMatched(note: SearchedNote, terms: List<ParsedTerm>): Boolean =
    terms.filterNot { it.negated }.any { parsed ->
        parsed.term.nameNeedle?.let { note.name.contains(it, ignoreCase = true) } == true
    }

/// Every occurrence of every needle in one line, merged where they
/// overlap so the ranges stay disjoint and ordered.
private fun rangesIn(line: String, needles: List<String>): List<IntRange> {
    val found = mutableListOf<IntRange>()
    for (needle in needles) {
        var at = line.indexOf(needle, 0, ignoreCase = true)
        while (at >= 0) {
            found.add(at..at + needle.length - 1)
            at = line.indexOf(needle, at + 1, ignoreCase = true)
        }
    }
    if (found.size < 2) return found
    val merged = mutableListOf<IntRange>()
    for (range in found.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last) {
            if (range.last > last.last) merged[merged.size - 1] = last.first..range.last
        } else {
            merged.add(range)
        }
    }
    return merged
}


// Legacy row evaluation, retained only as the independent result oracle.
fun taskRows(hits: List<SearchHit>): List<TaskHit> = hits
    .flatMap { hit ->
        hit.lines.map { line ->
            TaskHit(
                relPath = hit.relPath,
                name = hit.name,
                line = line.number,
                text = line.text,
                due = taskDateIn(line.text, DUE_FIELD)?.let(LocalDate::parse),
                done = taskDateIn(line.text, DONE_FIELD)?.let(LocalDate::parse),
            )
        }
    }
    .sortedWith(TASK_ORDER)

private val TASK_ORDER = compareBy<TaskHit>(
    { it.due == null },
    { it.due ?: LocalDate.MAX },
    { it.relPath },
    { it.line },
)


fun agenda(rows: List<TaskHit>, today: LocalDate, cap: Int = AGENDA_ROW_CAP): List<AgendaSection> {
    val open = rows.filterNot { isCheckedTaskLine(it.text) }
    val horizon = today.plusDays(AGENDA_HORIZON_DAYS)
    return AgendaGroup.entries.mapNotNull { group ->
        val members = open.filter { group.holds(it.due, today, horizon) }
        if (members.isEmpty()) {
            null
        } else {
            AgendaSection(group, members.take(cap), (members.size - cap).coerceAtLeast(0))
        }
    }
}

fun agendaEmpty(rows: List<TaskHit>, sections: List<AgendaSection>): AgendaEmpty? = when {
    sections.isNotEmpty() -> null
    rows.isEmpty() -> AgendaEmpty.NO_TASKS
    else -> AgendaEmpty.NONE_OPEN
}

private fun AgendaGroup.holds(due: LocalDate?, today: LocalDate, horizon: LocalDate): Boolean = when (this) {
    AgendaGroup.OVERDUE -> due != null && due < today
    AgendaGroup.TODAY -> due == today
    AgendaGroup.NEXT_SEVEN -> due != null && due > today && !due.isAfter(horizon)
    AgendaGroup.UNDATED -> due == null
}


const val AGENDA_SCAN_LINES = 200
fun agendaHits(gilune: uniffi.gilune_ffi.Gilune?, vaultDir: String, types: List<SearchType>, today: LocalDate): List<SearchHit> =
    if (gilune == null) emptyList() else queryVault(gilune, vaultDir, AGENDA_QUERY, types = types, today = today, maxLines = AGENDA_SCAN_LINES)
