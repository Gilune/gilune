// Plugin-extensible query language and display records.
// Parsing compiles a kernel request; result evaluation lives in Rust.

package org.gilune.app

import org.gilune.plugin.sdk.Compiled
import org.gilune.plugin.sdk.SearchOperator
import org.gilune.plugin.sdk.SearchType
import org.gilune.plugin.sdk.SearchedNote

import java.time.LocalDate
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.ConstraintOp
import uniffi.gilune_ffi.TextTarget

/// The operators a query may use: the built-ins, plus whatever is
/// registered beside them. **This is the plugin seam for the search
/// language** — the DSL grows here, never by widening the parser. Once the
/// sandboxed runtime lands (ADR 0010) a downloadable plugin declares its
/// operators in its manifest and they arrive as `extra`; until then the
/// shell registers them in code (ADR 0011).
///
/// Keywords are matched case-insensitively. Duplicate names are rejected;
/// registration order must never silently change a filter's meaning.
class SearchOperators(vararg extra: SearchOperator) {
    private val registered: List<SearchOperator> = extra.toList()

    private val byName: Map<String, SearchOperator> =
        (BUILT_INS + registered).also { operators ->
            val names = operators.map { it.name.lowercase(java.util.Locale.ROOT) }
            require(names.all { it.isNotBlank() && it != "type" } && names.distinct().size == names.size) {
                "Invalid or duplicate search operator registration"
            }
        }.associateBy { it.name.lowercase(java.util.Locale.ROOT) }

    operator fun get(name: String): SearchOperator? = byName[name.lowercase(java.util.Locale.ROOT)]

    val names: Set<String> get() = byName.keys

    /// Combine independent cross-domain contributions with the selected
    /// type's own operators. Neither contributor receives the other plugin.
    /// `today` resolves relative words when the query is evaluated.
    fun scopedTo(type: SearchType, today: LocalDate): SearchOperators {
        val scoped = type.operators(today)
        return if (scoped.isEmpty()) this else SearchOperators(*(registered + scoped).toTypedArray())
    }
}

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
        if (negated) Compiled.Scan else Compiled.Exact(listOf(Constraint.Text(target, argument)))

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
    val terms = buildList {
        var index = 0
        while (index < typed.tokens.size) {
            val membership = parseMembership(typed.tokens, index, scoped)
            if (membership != null) {
                add(membership.parsed)
                index += membership.consumed
            } else {
                parseTerm(typed.tokens[index++], scoped)?.let { add(it) }
            }
        }
    }
    // An empty query finds nothing rather than everything — unchanged, and
    // § 4's empty block for the same reason. A query that names its type
    // and nothing else is **not** empty: it has asked a well-formed
    // question ("tasks", "notes") and answering it with silence would read
    // as a broken query rather than as no filters.
    if (terms.isEmpty() && typed.typeKey == null) return null
    return ParsedQuery(type, terms)
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
    for ((index, token) in tokens.withIndex()) {
        if (membershipMarker(tokens, index) != 0) { rest.add(token); continue }
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

internal sealed interface Term {
    val indexNamespaces: List<String> get() = emptyList()
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
        if (negated) Compiled.Scan else Compiled.Exact(listOf(Constraint.Text(TextTarget.ANY, text)))
}

private class OperatorTerm(private val operator: SearchOperator, private val argument: String) : Term {
    override val indexNamespaces get() = operator.indexNamespaces
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
            return Compiled.Exact(listOf(Constraint.Fact(predicate, op, "")))
        }
        // `contains` is not `equals`, so its negation is not `Ne`.
        return if (negated) {
            Compiled.Scan
        } else {
            Compiled.Exact(listOf(Constraint.Fact(predicate, ConstraintOp.CONTAINS, value)))
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
    var escaped = false
    for (char in query) {
        if (escaped) { token.append(char); escaped = false; continue }
        if (quoted && char == '\\') { token.append(char); escaped = true; continue }
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
            if (operator == null && !token.startsWith('"') && '.' in token.take(colon)) {
                throw InvalidSearchQuery("Field '${token.take(colon)}' is unavailable.")
            }
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
