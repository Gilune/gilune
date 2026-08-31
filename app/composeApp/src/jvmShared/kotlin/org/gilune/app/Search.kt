// Search over the vault as it sits on disk — names, prose and front
// matter — behind a small Obsidian-flavoured query language. The operator
// set is an open registry rather than a fixed grammar, because the
// language is a plugin surface (ADR 0010, ADR 0011): a plugin adds a
// keyword, it does not patch this file.
//
// Pure over a directory and a string, so it is testable without a screen.
// There is no index: a search reads each note once, which is honest for
// the vault sizes v0 targets — 05-plugin-system § index plugins is where
// the answer goes when it stops being.

package org.gilune.app

import java.io.File

/// A note as an operator sees it, with front matter already split off by
/// `splitNote`, so `body` and `lines` are prose alone. `lines` is computed
/// on first use: an operator that never reads them costs nothing.
class SearchedNote(
    val relPath: String,
    val name: String,
    val body: String,
    val frontMatter: List<FrontMatterEntry>,
) {
    val lines: List<String> by lazy { body.lines() }
}

/// One keyword of the query language: `name` is what the user types before
/// the colon, `matches` is its predicate over a note. Implementations must
/// be pure and total — a query is run against every note in the vault, and
/// an operator that throws would lose the whole result set.
///
/// The needle hooks are optional and affect presentation and rank only. An
/// operator that looks for a literal substring says so, and a hit then
/// highlights it (`bodyNeedle`) or counts as a name match (`nameNeedle`).
/// The default — null — means the operator filters without highlighting.
interface SearchOperator {
    val name: String

    fun matches(note: SearchedNote, argument: String): Boolean

    fun bodyNeedle(argument: String): String? = null

    fun nameNeedle(argument: String): String? = null
}

/// The operators a query may use: the built-ins, plus whatever is
/// registered beside them. **This is the plugin seam for the search
/// language** — the DSL grows here, never by widening the parser. Once the
/// sandboxed runtime lands (ADR 0010) a downloadable plugin declares its
/// operators in its manifest and they arrive as `extra`; until then the
/// shell registers them in code (ADR 0011).
///
/// Keywords are matched case-insensitively, and a later registration
/// replaces an earlier one of the same name, so a plugin may deliberately
/// override a built-in.
class SearchOperators(vararg extra: SearchOperator) {
    private val byName: Map<String, SearchOperator> =
        (BUILT_INS + extra).associateBy { it.name.lowercase() }

    operator fun get(name: String): SearchOperator? = byName[name.lowercase()]

    val names: Set<String> get() = byName.keys
}

private val FILE_OPERATOR = object : SearchOperator {
    override val name = "file"
    override fun matches(note: SearchedNote, argument: String) =
        note.name.contains(argument, ignoreCase = true)

    override fun nameNeedle(argument: String) = argument
}

private val PATH_OPERATOR = object : SearchOperator {
    override val name = "path"
    override fun matches(note: SearchedNote, argument: String) =
        note.relPath.contains(argument, ignoreCase = true)
}

private val CONTENT_OPERATOR = object : SearchOperator {
    override val name = "content"
    override fun matches(note: SearchedNote, argument: String) =
        note.body.contains(argument, ignoreCase = true)

    override fun bodyNeedle(argument: String) = argument
}

private val BUILT_INS = listOf(FILE_OPERATOR, PATH_OPERATOR, CONTENT_OPERATOR)

/// A body line a note matched on. `number` is **body-relative and
/// 1-based**: front matter is split off before searching, so line 1 is the
/// first line of prose, not the first line of the file. Each range indexes
/// into `text` with both ends inclusive; overlapping occurrences are
/// merged so a highlighter can paint them straight through.
data class MatchedLine(val number: Int, val text: String, val ranges: List<IntRange>)

/// One note the query kept. `lines` is empty when nothing in the body
/// matched — a note found only by its name or its metadata is a real hit
/// with nothing to quote — and `nameMatched` says which. `moreMatches`
/// counts the matching lines beyond `MAX_MATCHED_LINES` that were dropped;
/// `matchCount` counts highlighted spans across the whole body, dropped
/// lines included.
data class SearchHit(
    val relPath: String,
    val name: String,
    val nameMatched: Boolean,
    val lines: List<MatchedLine>,
    val moreMatches: Int,
    val matchCount: Int,
)

/// How many matching lines a hit carries before the rest are only counted.
const val MAX_MATCHED_LINES = 5

/// Notes matching `query`, best first. The grammar, Obsidian-flavoured and
/// deliberately forgiving:
///
/// - whitespace separates terms and every term must hold (AND);
/// - a bare term matches the body or the note name, case-insensitively;
/// - `"a quoted phrase"` is one term, so the words must be adjacent;
/// - a leading `-` negates any term;
/// - `keyword:argument` invokes a registered operator — an unrecognised
///   keyword is not an error, it degrades to a bare term, because a typo
///   must not silently empty the result set;
/// - `[key:value]` matches a front-matter entry whose key is `key` and
///   whose value contains `value`; `[key]` alone matches notes that carry
///   the key at all.
///
/// Gaps, honestly: there is no OR and no grouping in v0 — a query is a
/// conjunction and nothing else; bare terms do not search front matter
/// (`[key:value]` is how metadata is addressed); and quotes delimit a
/// phrase, so a literal `"` cannot be searched for.
///
/// Ranking puts name matches first, then more matches ahead of fewer, then
/// path order so results are stable. Markdown files only, dot-directories
/// excluded, each file read once. Unreadable files are skipped rather than
/// failing the search.
fun searchVault(
    vaultDir: String,
    query: String,
    operators: SearchOperators = SearchOperators(),
): List<SearchHit> {
    val terms = parseQuery(query, operators)
    if (terms.isEmpty()) return emptyList()
    val root = File(vaultDir)
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .onEnter { it == root || !it.name.startsWith(".") }
        .filter { it.isFile && it.name.endsWith(".md") }
        .mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val note = splitNote(text)
            SearchedNote(
                relPath = file.relativeTo(root).invariantSeparatorsPath,
                name = file.name.removeSuffix(".md"),
                body = note.body,
                frontMatter = note.frontMatter,
            )
        }
        .mapNotNull { note -> hitFor(note, terms) }
        .sortedWith(RANK)
        .toList()
}

private val RANK = compareByDescending<SearchHit> { it.nameMatched }
    .thenByDescending { it.matchCount }
    .thenBy { it.relPath }

private fun hitFor(note: SearchedNote, terms: List<ParsedTerm>): SearchHit? {
    for (term in terms) {
        if (term.term.matches(note) == term.negated) return null
    }
    // Only terms the note had to satisfy describe why it is here: a
    // negated term is a reason it was not excluded, never a highlight.
    val kept = terms.filterNot { it.negated }.map { it.term }
    val bodyNeedles = kept.mapNotNull { it.bodyNeedle }.filter { it.isNotEmpty() }
    val nameMatched = kept.any { term ->
        term.nameNeedle?.let { note.name.contains(it, ignoreCase = true) } == true
    }

    val lines = mutableListOf<MatchedLine>()
    var matchCount = 0
    var moreMatches = 0
    note.lines.forEachIndexed { index, text ->
        val ranges = rangesIn(text, bodyNeedles)
        if (ranges.isEmpty()) return@forEachIndexed
        matchCount += ranges.size
        if (lines.size < MAX_MATCHED_LINES) lines.add(MatchedLine(index + 1, text, ranges)) else moreMatches++
    }
    return SearchHit(note.relPath, note.name, nameMatched, lines, moreMatches, matchCount)
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

private sealed interface Term {
    fun matches(note: SearchedNote): Boolean
    val bodyNeedle: String? get() = null
    val nameNeedle: String? get() = null
}

private class TextTerm(private val text: String) : Term {
    override fun matches(note: SearchedNote) =
        note.body.contains(text, ignoreCase = true) || note.name.contains(text, ignoreCase = true)

    override val bodyNeedle get() = text
    override val nameNeedle get() = text
}

private class OperatorTerm(private val operator: SearchOperator, private val argument: String) : Term {
    override fun matches(note: SearchedNote) = operator.matches(note, argument)
    override val bodyNeedle get() = operator.bodyNeedle(argument)
    override val nameNeedle get() = operator.nameNeedle(argument)
}

/// `[key:value]`, or `[key]` when `value` is null. The key is the whole
/// key; the value need only be contained, so `[tags:home]` finds a note
/// tagged `home, chores`.
private class MetaTerm(private val key: String, private val value: String?) : Term {
    override fun matches(note: SearchedNote) = note.frontMatter.any { entry ->
        entry.key.equals(key, ignoreCase = true) &&
            (value == null || entry.value.contains(value, ignoreCase = true))
    }
}

private class ParsedTerm(val term: Term, val negated: Boolean)

private fun parseQuery(query: String, operators: SearchOperators): List<ParsedTerm> =
    tokenise(query).mapNotNull { parseTerm(it, operators) }

/// Whitespace splits terms, except inside a quoted phrase or a bracketed
/// metadata term, where it is part of the term.
private fun tokenise(query: String): List<String> {
    val tokens = mutableListOf<String>()
    val token = StringBuilder()
    var quoted = false
    var brackets = 0
    for (char in query) {
        when {
            char == '"' -> {
                quoted = !quoted
                token.append(char)
            }
            char == '[' && !quoted -> {
                brackets++
                token.append(char)
            }
            char == ']' && !quoted && brackets > 0 -> {
                brackets--
                token.append(char)
            }
            char.isWhitespace() && !quoted && brackets == 0 -> {
                if (token.isNotEmpty()) tokens.add(token.toString())
                token.clear()
            }
            else -> token.append(char)
        }
    }
    if (token.isNotEmpty()) tokens.add(token.toString())
    return tokens
}

private fun parseTerm(raw: String, operators: SearchOperators): ParsedTerm? {
    val negated = raw.length > 1 && raw.startsWith('-')
    val token = if (negated) raw.substring(1) else raw

    if (token.length > 2 && token.startsWith('[') && token.endsWith(']')) {
        val inside = token.substring(1, token.length - 1)
        val colon = inside.indexOf(':')
        val key = (if (colon < 0) inside else inside.take(colon)).trim()
        val value = if (colon < 0) null else unquote(inside.drop(colon + 1).trim())
        if (key.isNotEmpty()) return ParsedTerm(MetaTerm(key, value), negated)
    } else {
        val colon = token.indexOf(':')
        if (colon > 0) {
            val operator = operators[token.take(colon)]
            val argument = unquote(token.drop(colon + 1))
            if (operator != null && argument.isNotEmpty()) {
                return ParsedTerm(OperatorTerm(operator, argument), negated)
            }
        }
    }
    // Everything else is prose, including a keyword nobody registered.
    val text = token.replace("\"", "").trim()
    return if (text.isEmpty()) null else ParsedTerm(TextTerm(text), negated)
}

private fun unquote(text: String): String =
    if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
        text.substring(1, text.length - 1)
    } else {
        text
    }
