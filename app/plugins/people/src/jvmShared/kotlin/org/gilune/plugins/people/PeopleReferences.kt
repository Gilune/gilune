package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val PERSON_REFERENCE = "$PEOPLE_PLUGIN.reference"
const val PERSON_MENTION = "$PEOPLE_PLUGIN.mention"
const val PERSON_CONNECTION = "$PEOPLE_PLUGIN.connection"
const val CONNECTION_KEYWORD = "$PEOPLE_PLUGIN.connection.keyword"
const val CONNECTION_PERSON = "$PEOPLE_PLUGIN.connection.person"
const val REFERENCE_PROBLEM = "$PEOPLE_PLUGIN.reference.problem"

/** The same canonical pair is consumed by facts and query value bindings. */
fun connectionValue(keyword: String, handle: String): String {
    return connectionValue(keyword, handle, ::casefoldNfc)
}

private fun connectionValue(keyword: String, handle: String, canonical: (String) -> String): String {
    require(validToken(keyword) && validToken(handle))
    return "[${pageJsonString(canonical(keyword))},${pageJsonString(canonical(handle))}]"
}

/** Bound memory to one indexing/recognition call. Repeated tokens share the exact
 * native Unicode normalizer's answer without another FFI round trip. This is
 * derived text normalization, never person resolution or a persistent alias. */
private class CanonicalTokens : (String) -> String {
    private val values = HashMap<String, String>()
    override fun invoke(value: String): String = values[value] ?: casefoldNfc(value).also {
        if (values.size < 512) values[value] = it
    }
}

/** Exact raw token ranges are retained for later navigation and refactoring.
 * A reference's item identity is local to the supplied source version. */
data class PersonReference(
    val handle: String,
    val keyword: String?,
    val handleRange: SourceSpan,
    val keywordRange: SourceSpan?,
    val location: SourceLocation,
)

fun recognisePeople(view: SourceView): List<PersonReference> = recognisePeople(view, CanonicalTokens())

private fun escapedAt(text: String, at: Int): Boolean {
    var start = at
    while (start > 0 && text[start - 1] == '\\') start--
    return (at - start) % 2 == 1
}

private fun boundaryAt(text: String, at: Int): Boolean = at == 0 || text[at - 1].isWhitespace() || text[at - 1] in "([{,;:!?=*\"'"

private fun urlAt(text: String, at: Int): Boolean {
    var start = at
    while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] !in "([{\"'") start--
    val prefix = text.substring(start, at)
    return "://" in prefix || prefix.startsWith("mailto:", true) || prefix.startsWith("www.", true)
}

/** Every `@handle` within `range` of `text`, `@` included, by the one rule
 * the index and the editor share: at a word boundary, not escaped, not part
 * of a URL or an email address, sentence-final dots left off, the handle a
 * valid token wholly inside the range. */
internal fun mentionTokens(text: String, range: IntRange): List<IntRange> = buildList {
    var at = text.indexOf('@', range.first)
    while (at >= 0 && at <= range.last) {
        val start = at
        at = text.indexOf('@', at + 1)
        if (!boundaryAt(text, start) || escapedAt(text, start) || urlAt(text, start)) continue
        var end = start + 1
        while (end < text.length && peopleTokenChar(text[end])) end++
        if (end < text.length && text[end] == '@') continue // email-shaped input
        while (end > start + 1 && text[end - 1] == '.') end-- // sentence punctuation
        if (!validToken(text.substring(start + 1, end)) || end - 1 > range.last) continue
        add(start until end)
    }
}

/** The mentions in one line of Markdown as the editor draws them: the same
 * tokens the index records, less any inside an inline code span (the index
 * never sees those; fenced blocks the editor already leaves alone). */
fun mentionsInLine(line: String): List<IntRange> {
    val code = INLINE_CODE.findAll(line).map { it.range }.toList()
    return mentionTokens(line, line.indices).filter { token -> code.none { token.first in it } }
}

private val INLINE_CODE = Regex("`[^`\n]+`")

private fun recognisePeople(view: SourceView, canonical: (String) -> String): List<PersonReference> {
    val text = view.snapshot.text
    // Adjacent ordinary-text events may be split by the Markdown parser (for
    // example escaped punctuation). Merge only actually contiguous raw ranges.
    val allowed = mutableListOf<IntRange>()
    view.snapshot.textRanges.forEach { span ->
        val start = view.charOffset(span.start)
        val end = view.charOffset(span.end)
        if (start < end) {
            val previous = allowed.lastOrNull()
            if (previous != null && previous.last + 1 == start) allowed[allowed.lastIndex] = previous.first until end
            else allowed += start until end
        }
    }
    fun escaped(at: Int): Boolean = escapedAt(text, at)
    fun boundary(at: Int): Boolean = boundaryAt(text, at)
    return buildList {
        for (range in allowed) {
            for (token in mentionTokens(text, range)) {
                val start = token.first
                val end = token.last + 1
                val handle = text.substring(start + 1, end)
                var colon = start
                while (colon > range.first && text[colon - 1] in " \t") colon--
                var keyword: String? = null
                var keywordRange: SourceSpan? = null
                if (colon > range.first && text[colon - 1] == ':') {
                    val keywordEnd = colon - 1
                    var keywordStart = keywordEnd
                    while (keywordStart > range.first && peopleTokenChar(text[keywordStart - 1])) keywordStart--
                    val candidate = text.substring(keywordStart, keywordEnd)
                    if (validToken(candidate) && boundary(keywordStart) && !escaped(keywordStart) && !escaped(keywordEnd) && view.locationAt(keywordStart) == view.locationAt(start)) {
                        keyword = canonical(candidate)
                        keywordRange = SourceSpan(view.byteOffset(keywordStart), view.byteOffset(keywordEnd))
                    }
                }
                add(PersonReference(canonical(handle), keyword,
                    SourceSpan(view.byteOffset(start + 1), view.byteOffset(end)), keywordRange, view.locationAt(start)))
            }
        }
    }
}

internal fun peopleReferenceFacts(view: SourceView): List<ScopedFact> = buildList {
    val canonical = CanonicalTokens()
    fun fact(location: SourceLocation, predicate: String, value: String) { add(ScopedFact(location, predicate, value)) }
    fun reference(location: SourceLocation, handle: String, keyword: String?) {
        fact(location, PERSON_REFERENCE, canonical(handle))
        if (keyword == null) fact(location, PERSON_MENTION, canonical(handle))
        else {
            fact(location, PERSON_CONNECTION, connectionValue(keyword, handle, canonical))
            fact(location, CONNECTION_KEYWORD, canonical(keyword))
            fact(location, CONNECTION_PERSON, canonical(handle))
        }
    }
    recognisePeople(view, canonical).forEach { reference(it.location, it.handle, it.keyword) }
    val connections = view.metadata()["connections"] ?: return@buildList
    fun problem(message: String) { fact(SourceLocation.DocumentRoot, REFERENCE_PROBLEM, message) }
    if (connections.kind != "mapping") {
        problem("The connections property must map keywords to a person reference or a list of references.")
        return@buildList
    }
    for ((key, value) in connections.entries()) {
        val keyword = key.string()
        if (keyword == null || !validToken(keyword)) {
            problem("A connection keyword must be a string without spaces or Markdown punctuation.")
            continue
        }
        val values = if (value.kind == "sequence") value.children() else listOf(value)
        for (entry in values) {
            val raw = entry.string()
            val handle = raw?.takeIf { it.startsWith('@') }?.removePrefix("@")
            if (handle == null || !validToken(handle)) problem("Connection '$keyword' contains an invalid person reference.")
            else reference(SourceLocation.DocumentRoot, handle, keyword)
        }
    }
}
