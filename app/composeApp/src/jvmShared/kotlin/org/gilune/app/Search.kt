// The query language: what a term means, and how it narrows the system
// index before any file is opened.
//
// The operator set is an open registry rather than a fixed grammar,
// because the language is a plugin surface (ADR 0010, ADR 0011): a plugin
// adds a keyword, it does not patch this file. `type:` (ADR 0015 § 4) is
// the same idea one level up — a plugin adds what a *hit* is, and its
// keywords arrive through that type rather than loose. ADR 0020 § 2.4
// makes both of those registries the **compilation** route as well: an
// operator says what it means *and* how that reads as a constraint on the
// kernel's index, and the kernel joins the constraints nobody wrote a
// join for.
//
// Everything here is pure over strings, so it is testable without a
// screen. `VaultQuery.kt` is what runs it against a vault: it asks the
// index which notes could possibly match, reads only those, and decides
// each one with the very predicates below — so a constraint that narrows
// too little costs a read and a constraint that would narrow too much is
// simply not compiled.

package org.gilune.app

import java.time.LocalDate
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.ConstraintOp
import uniffi.gilune_ffi.TextTarget

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

    /// The same note with one of its lines for a body — **the narrowed-note
    /// rule** (ADR 0015 § 4), and the whole of what a line-level type needs
    /// from the evaluator. Name, path and front matter are the containing
    /// note's, so terms about text bind to the line and terms about the
    /// note bind to the note, with no new operator interface.
    fun narrowedTo(line: String): SearchedNote = SearchedNote(relPath, name, line, frontMatter)
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

    /// How this operator narrows the system index (ADR 0020 § 2.4), or
    /// [Compiled.Scan] when it cannot say.
    ///
    /// **[matches] stays the decision.** Whatever is returned here is a
    /// pre-filter over anchors: the notes it names are read and judged by
    /// [matches] exactly as they always were. So the contract is
    /// one-sided and easy to honour — a compilation may name **more**
    /// anchors than the operator would keep, and must never name fewer.
    /// The default says nothing, so an operator written before this
    /// existed, or one whose meaning no constraint can express, still
    /// works and merely costs the reads it always cost.
    ///
    /// `negated` is the DSL's leading `-`. An operator that cannot state
    /// the exact complement of itself must answer [Compiled.Scan] for it:
    /// a constraint that narrowed a negation too far would drop a hit,
    /// which is the one failure this seam cannot recover from.
    fun compile(argument: String, negated: Boolean): Compiled = Compiled.Scan
}

/// What a term says to the index.
sealed interface Compiled {
    /// Constraints that must all hold of an anchor. They are ANDed with
    /// every other term's, which is what makes a query across two
    /// plugins a join nobody wrote.
    data class Terms(val constraints: List<Constraint>) : Compiled

    /// Nothing the index can be asked. The term is decided when the note
    /// is read, and costs whatever the other terms left to read.
    data object Scan : Compiled

    /// The term can hold of nothing at all — a date operator handed a
    /// value that is not a date. The query is answered with silence
    /// rather than with a pass over the vault that would keep nothing.
    data object Never : Compiled
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
    private val registered: List<SearchOperator> = extra.toList()

    private val byName: Map<String, SearchOperator> =
        (BUILT_INS + registered).associateBy { it.name.lowercase() }

    operator fun get(name: String): SearchOperator? = byName[name.lowercase()]

    val names: Set<String> get() = byName.keys

    /// The same set with the active type's keywords layered on top
    /// (ADR 0015 § 4). **Operators arrive through a type**, so `due:` is a
    /// keyword under `task` and prose under `note`, and switching a plugin
    /// off cannot leave a keyword behind meaning something nobody can
    /// answer. `today` is passed because a relative word resolves at
    /// evaluation, not at registration.
    fun scopedTo(type: SearchType, today: LocalDate): SearchOperators {
        val scoped = type.operators(today)
        return if (scoped.isEmpty()) this else SearchOperators(*(registered + scoped).toTypedArray())
    }
}

/// One line a line-level type offers the evaluator. `number` is
/// body-relative and 1-based, exactly as [MatchedLine.number] is, so a hit
/// addresses the buffer the same way whichever type produced it.
data class CandidateLine(val number: Int, val text: String)

/// What a `type:` names — what a hit **is** (ADR 0015 § 4). [extract] is
/// which of the two kinds this is:
///
/// - **note-level** (no extractor): a hit is a note. `note` is the shell's
///   own, and the search panel's semantics unchanged.
/// - **line-level** (an extractor): a hit is a line, and each candidate is
///   judged by [SearchedNote.narrowedTo].
///
/// [operators] is a function of the day the query runs, because relative
/// words resolve **at evaluation**: `due:today` is the query's meaning,
/// not a date that rots.
/// [constraints] is what the type itself asks of the index — for a
/// line-level type, whatever makes a line one of its candidates. It is
/// the same one-sided contract [SearchOperator.compile] takes: [extract]
/// stays the decision, so these may name more lines than the type would
/// claim and must never name fewer.
class SearchType(
    val key: String,
    val operators: (today: LocalDate) -> List<SearchOperator> = { emptyList() },
    val extract: ((SearchedNote) -> List<CandidateLine>)? = null,
    val constraints: List<Constraint> = emptyList(),
)

/// The shell's own type, always registered beside whatever a plugin
/// contributes, exactly as [SearchOperators] always carries its built-ins.
val NOTE_SEARCH_TYPE = SearchType("note")

/// The three built-ins are exactly the kernel's three text targets, which
/// is not a coincidence: `TextTarget` was given a target per shipped
/// keyword so each of them compiles to one constraint and no post-filter
/// (ADR 0020 § 10, K3).
private fun textOperator(
    keyword: String,
    target: TextTarget,
    holds: (SearchedNote, String) -> Boolean,
) = object : SearchOperator {
    override val name = keyword
    override fun matches(note: SearchedNote, argument: String) = holds(note, argument)
    override fun compile(argument: String, negated: Boolean): Compiled =
        if (negated) Compiled.Scan else Compiled.Terms(listOf(Constraint.Text(target, argument)))

    override fun bodyNeedle(argument: String) = argument.takeIf { target == TextTarget.BODY }

    override fun nameNeedle(argument: String) = argument.takeIf { target == TextTarget.NAME }
}

private val FILE_OPERATOR = textOperator("file", TextTarget.NAME) { note, argument ->
    note.name.contains(argument, ignoreCase = true)
}

private val PATH_OPERATOR = textOperator("path", TextTarget.PATH) { note, argument ->
    note.relPath.contains(argument, ignoreCase = true)
}

private val CONTENT_OPERATOR = textOperator("content", TextTarget.BODY) { note, argument ->
    note.body.contains(argument, ignoreCase = true)
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

/// One query, read: the type it names, the terms beside it, and the
/// operators those terms were parsed against.
///
/// The grammar, Obsidian-flavoured and deliberately forgiving:
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
///   the key at all;
/// - `type:key` says what a hit is (ADR 0015 § 4), absent it is `note` and
///   nothing a person has already typed changes meaning.
///
/// Gaps, honestly: there is no OR and no grouping in v0 — a query is a
/// conjunction and nothing else; bare terms do not search front matter
/// (`[key:value]` is how metadata is addressed); and quotes delimit a
/// phrase, so a literal `"` cannot be searched for.
///
/// A `type:` **nobody registered finds nothing** — an unanswerable
/// question is not a text search — which is precisely what a disabled or
/// removed contributor leaves behind, and is [ParsedQuery] being null. A
/// type with no terms beside it is every hit of that type.
internal class ParsedQuery(val type: SearchType, val terms: List<ParsedTerm>)

internal fun parseQuery(
    query: String,
    operators: SearchOperators,
    types: List<SearchType>,
    today: LocalDate,
): ParsedQuery? {
    val typed = splitType(tokenise(query))
    val type = resolveType(types, typed.typeKey) ?: return null
    val scoped = operators.scopedTo(type, today)
    val terms = typed.tokens.mapNotNull { parseTerm(it, scoped) }
    // An empty query finds nothing rather than everything — unchanged, and
    // § 4's empty block for the same reason. A query that names its type
    // and nothing else is **not** empty: it has asked a well-formed
    // question ("tasks", "notes") and answering it with silence would read
    // as a broken query rather than as no filters.
    if (terms.isEmpty() && typed.typeKey == null) return null
    return ParsedQuery(type, terms)
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

/// `note` is answerable whether or not the caller listed it — the
/// built-in the registry always carries — while any other key must have
/// been registered. A caller's own `note` type wins over the built-in.
private fun resolveType(types: List<SearchType>, key: String?): SearchType? {
    val wanted = key ?: NOTE_SEARCH_TYPE.key
    return (types + NOTE_SEARCH_TYPE).firstOrNull { it.key.equals(wanted, ignoreCase = true) }
}

private const val TYPE_KEYWORD = "type"

private class TypedQuery(val typeKey: String?, val tokens: List<String>)

/// `type:` is the one keyword the parser answers itself, because it
/// decides which operators are in scope and so cannot be one of them. The
/// first occurrence wins and a second is ignored: a query says what it is
/// looking at once. `-type:` is not a form — negating what a hit *is*
/// means nothing — so it falls through to the term parser and degrades to
/// prose, like any keyword nobody registered.
private fun splitType(tokens: List<String>): TypedQuery {
    var key: String? = null
    val rest = mutableListOf<String>()
    for (token in tokens) {
        val colon = if (token.startsWith('[')) -1 else token.indexOf(':')
        val argument = if (colon > 0) unquote(token.drop(colon + 1)) else ""
        if (argument.isNotEmpty() && token.take(colon).equals(TYPE_KEYWORD, ignoreCase = true)) {
            if (key == null) key = argument
            continue
        }
        rest.add(token)
    }
    return TypedQuery(key, rest)
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

internal sealed interface Term {
    fun matches(note: SearchedNote): Boolean
    val bodyNeedle: String? get() = null
    val nameNeedle: String? get() = null

    /// What this term asks of the index — [SearchOperator.compile]'s
    /// one-sided contract, and the same reasoning: never fewer anchors
    /// than [matches] would keep.
    fun compile(negated: Boolean): Compiled = Compiled.Scan
}

private class TextTerm(private val text: String) : Term {
    override fun matches(note: SearchedNote) =
        note.body.contains(text, ignoreCase = true) || note.name.contains(text, ignoreCase = true)

    override val bodyNeedle get() = text
    override val nameNeedle get() = text

    /// `Any` is body **or** name in one constraint, because a conjunction
    /// cannot express that `or` (ADR 0020 § 10, K3).
    override fun compile(negated: Boolean): Compiled =
        if (negated) Compiled.Scan else Compiled.Terms(listOf(Constraint.Text(TextTarget.ANY, text)))
}

private class OperatorTerm(private val operator: SearchOperator, private val argument: String) : Term {
    override fun matches(note: SearchedNote) = operator.matches(note, argument)
    override val bodyNeedle get() = operator.bodyNeedle(argument)
    override val nameNeedle get() = operator.nameNeedle(argument)
    override fun compile(negated: Boolean) = operator.compile(argument, negated)
}

/// `[key:value]`, or `[key]` when `value` is null. The key is the whole
/// key; the value need only be contained, so `[tags:home]` finds a note
/// tagged `home, chores`.
private class MetaTerm(private val key: String, private val value: String?) : Term {
    override fun matches(note: SearchedNote) = note.frontMatter.any { entry ->
        entry.key.equals(key, ignoreCase = true) &&
            (value == null || entry.value.contains(value, ignoreCase = true))
    }

    /// The kernel reads a note's front matter into its own namespace
    /// (ADR 0020 § 10, P): one note-level fact per top-level entry, keyed
    /// by the lower-cased key, which is what makes `[Mood]` and `[mood]`
    /// one question here and there. Absence compiles too — `-[key]` is
    /// "no fact with that predicate", which is exactly what it means.
    override fun compile(negated: Boolean): Compiled {
        val predicate = frontMatterPredicate(key)
        if (value == null) {
            val op = if (negated) ConstraintOp.NOT_EXISTS else ConstraintOp.EXISTS
            return Compiled.Terms(listOf(Constraint.Fact(predicate, op, "")))
        }
        // `contains` is not `equals`, so its negation is not `Ne`.
        return if (negated) {
            Compiled.Scan
        } else {
            Compiled.Terms(listOf(Constraint.Fact(predicate, ConstraintOp.CONTAINS, value)))
        }
    }
}

/// A front-matter key as the kernel's `frontmatter` namespace names it.
/// One home for the spelling, because a query and the facts it reads have
/// to agree about it.
fun frontMatterPredicate(key: String): String = "frontmatter.${key.lowercase()}"

internal class ParsedTerm(val term: Term, val negated: Boolean)

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
