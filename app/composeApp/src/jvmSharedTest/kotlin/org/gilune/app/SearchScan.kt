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
