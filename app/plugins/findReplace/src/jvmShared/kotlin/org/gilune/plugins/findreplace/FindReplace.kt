// Find and replace (ADR 0029): one matching language for the bar over a
// note and for replacing across notes, pure over strings so every shell
// answers a pattern the same way. The host draws, steps and writes; this
// file only says where a pattern matches and what each match becomes.

package org.gilune.plugins.findreplace

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/// The three switches beside the pattern (§ 2).
data class FindOptions(val matchCase: Boolean = false, val wholeWord: Boolean = false, val regex: Boolean = false)

data class FindQuery(val pattern: String, val options: FindOptions = FindOptions())

/// One match and, when a replacement was asked for, what it becomes.
/// `range` is half-open over the searched text.
data class FindMatch(val start: Int, val end: Int, val replacement: String? = null) {
    val range: IntRange get() = start until end
}

sealed interface FindOutcome {
    data class Found(val matches: List<FindMatch>) : FindOutcome

    /// The pattern or the replacement cannot be used; `reason` is said to
    /// the person as it stands.
    data class Invalid(val reason: String) : FindOutcome
}

val FindOutcome.matches: List<FindMatch> get() = (this as? FindOutcome.Found)?.matches.orEmpty()

/// Every non-empty match of [query] in [text], in order. With a
/// [replacement], each match carries its replacement: literal outside regex
/// mode, `$1` / `${name}` inside it. An empty pattern finds nothing, and so
/// does a pattern that can only match nothing — an empty match has nothing
/// to show or replace.
fun findMatches(text: String, query: FindQuery, replacement: String? = null): FindOutcome {
    if (query.pattern.isEmpty()) return FindOutcome.Found(emptyList())
    val pattern = try {
        compile(query)
    } catch (e: PatternSyntaxException) {
        return FindOutcome.Invalid("not a valid pattern: ${e.description}")
    }
    val written = replacement?.let { if (query.options.regex) it else Matcher.quoteReplacement(it) }
    val matcher = pattern.matcher(text)
    val found = mutableListOf<FindMatch>()
    // appendReplacement writes the gap since the last match and then the
    // replacement, so each match's own replacement is what follows the gap.
    val scratch = StringBuilder()
    var appended = 0
    while (matcher.find()) {
        if (matcher.end() == matcher.start()) continue
        val replaced = if (written == null) null else try {
            val before = scratch.length
            matcher.appendReplacement(scratch, written)
            scratch.substring(before + (matcher.start() - appended)).also { appended = matcher.end() }
        } catch (e: IllegalArgumentException) {
            return FindOutcome.Invalid("the replacement cannot be used: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            return FindOutcome.Invalid("the replacement names a group the pattern does not have")
        }
        found += FindMatch(matcher.start(), matcher.end(), replaced)
    }
    return FindOutcome.Found(found)
}

/// [text] with every match replaced, and how many there were; null when
/// the pattern or replacement is invalid.
fun replaceAll(text: String, query: FindQuery, replacement: String): Pair<String, Int>? {
    val matches = (findMatches(text, query, replacement) as? FindOutcome.Found)?.matches ?: return null
    return applyMatches(text, matches) to matches.size
}

/// Writes each match's replacement into [text]; matches must be in order
/// and not overlap, as [findMatches] returns them.
fun applyMatches(text: String, matches: List<FindMatch>): String = buildString(text.length) {
    var at = 0
    for (match in matches) {
        append(text, at, match.start)
        append(requireNotNull(match.replacement) { "a match without a replacement" })
        at = match.end
    }
    append(text, at, text.length)
}

/// The match to step to from [caret]: the first starting at or after it
/// going forward, the last ending at or before it going back, wrapping at
/// either end. Null when there are none.
fun stepMatch(matches: List<FindMatch>, caret: Int, forward: Boolean): Int? {
    if (matches.isEmpty()) return null
    return if (forward) matches.indexOfFirst { it.start >= caret }.takeIf { it >= 0 } ?: 0
    else matches.indexOfLast { it.end <= caret }.takeIf { it >= 0 } ?: matches.lastIndex
}

private const val WORD = "[\\p{L}\\p{N}_]"

private fun compile(query: FindQuery): Pattern {
    val body = if (query.options.regex) query.pattern else Pattern.quote(query.pattern)
    val bounded = if (query.options.wholeWord) "(?<!$WORD)(?:$body)(?!$WORD)" else body
    val flags = Pattern.MULTILINE or if (query.options.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    return Pattern.compile(bounded, flags)
}
