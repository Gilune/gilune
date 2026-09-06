// Shared field binding and membership syntax. Plugins encode one literal;
// the kernel evaluates the resulting scoped set constraint.

package org.gilune.app

import org.gilune.plugin.sdk.Compiled
import org.gilune.plugin.sdk.SearchSetBinding
import org.gilune.plugin.sdk.SearchedNote

import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.FactLocality

class InvalidSearchQuery(message: String) : IllegalArgumentException(message)

internal const val MAX_SET_VALUES = 256
internal const val MAX_SET_BYTES = 65_536

private val FIELD_NAME = Regex("[A-Za-z][A-Za-z0-9_.-]*")

internal data class MembershipTerm(val parsed: ParsedTerm, val consumed: Int)

internal fun membershipMarker(tokens: List<String>, at: Int): Int = when {
    tokens.getOrNull(at + 1) == "IN" -> 2
    tokens.getOrNull(at + 1) == "NOT" && tokens.getOrNull(at + 2) == "IN" -> 3
    else -> 0
}

/** Only an explicit field-shaped expression reserves IN. Ordinary prose and
 * quoted phrases keep their old meaning. Unknown structured fields fail
 * visibly instead of quietly becoming text or dropping part of a filter. */
internal fun parseMembership(tokens: List<String>, at: Int, operators: SearchOperators): MembershipTerm? {
    val marker = membershipMarker(tokens, at)
    if (marker == 0) return null
    val raw = tokens[at]
    val head = raw.removePrefix("-")
    val colon = head.indexOf(':')
    val name = if (colon < 0) head else head.take(colon)
    if (!FIELD_NAME.matches(name)) return null
    val rawArgument = if (colon < 0) "" else head.drop(colon + 1)
    val argument = if (rawArgument.length >= 2 && rawArgument.startsWith('"') && rawArgument.endsWith('"')) {
        rawArgument.substring(1, rawArgument.length - 1)
    } else rawArgument
    val operator = operators[name]
    val literal = tokens.getOrNull(at + marker)
    // A bare phrase such as "ALICE IN WONDERLAND" remains prose. A known
    // field or an attempted list is unambiguously a structured filter.
    if (colon < 0 && operator == null && literal?.startsWith('[') != true) return null
    if (raw.startsWith('-') && marker == 3) throw InvalidSearchQuery("Use either NOT IN or a leading minus, not both.")
    val binding = try { operator?.setBinding(argument) }
        catch (error: IllegalArgumentException) { throw InvalidSearchQuery(error.message ?: "Invalid field '$head'.") }
        ?: throw InvalidSearchQuery("Field '$head' is unavailable or does not support IN.")
    val values = parseSetLiteral(literal ?: throw InvalidSearchQuery("IN requires a list of quoted values."))
        .map { value ->
            try { binding.encodeValue(value) }
            catch (error: IllegalArgumentException) { throw InvalidSearchQuery(error.message ?: "Invalid value for '$head'.") }
        }
    if (values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > MAX_SET_BYTES) {
        throw InvalidSearchQuery("Encoded IN values exceed $MAX_SET_BYTES bytes.")
    }
    val term = SetTerm(binding, values.distinct().sorted(), operator?.indexNamespaces.orEmpty())
    return MembershipTerm(ParsedTerm(term, raw.startsWith('-') || marker == 3), marker + 1)
}

private class SetTerm(
    private val binding: SearchSetBinding,
    private val values: List<String>,
    override val indexNamespaces: List<String>,
) : Term {
    override fun matches(note: SearchedNote): Boolean = error("Set membership is evaluated by the kernel.")
    override fun compile(negated: Boolean): Compiled = Compiled.Exact(listOf(
        Constraint.FactIn(binding.predicate, values, binding.locality),
    ))
}

/** Strict JSON string arrays: no nested lists, numbers, booleans or null.
 * Domain-specific lexical rules belong to the binding's value encoder. */
internal fun parseSetLiteral(source: String): List<String> {
    fun invalid(): Nothing = throw InvalidSearchQuery("IN requires a list of quoted values, for example [\"one\", \"two\"].")
    if (source.length > MAX_SET_BYTES * 6 + MAX_SET_VALUES * 3 + 2) {
        throw InvalidSearchQuery("IN list is too large.")
    }
    var offset = 0
    fun whitespace() { while (offset < source.length && source[offset] in " \t\r\n") offset++ }
    fun take(char: Char): Boolean {
        whitespace()
        if (source.getOrNull(offset) != char) return false
        offset++
        return true
    }
    if (!take('[')) invalid()
    val values = mutableListOf<String>()
    var bytes = 0L
    if (!take(']')) {
        while (true) {
            if (!take('"')) invalid()
            val value = StringBuilder()
            var closed = false
            while (offset < source.length) {
                val char = source[offset++]
                if (char == '"') { closed = true; break }
                if (char.code < 0x20) invalid()
                if (char != '\\') { value.append(char); continue }
                when (val escaped = source.getOrNull(offset++) ?: invalid()) {
                    '"', '\\', '/' -> value.append(escaped)
                    'b' -> value.append('\b')
                    'f' -> value.append('\u000c')
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'u' -> {
                        val digits = source.substring(offset, (offset + 4).coerceAtMost(source.length))
                        if (digits.length != 4 || digits.any { it.digitToIntOrNull(16) == null }) invalid()
                        value.append(digits.toInt(16).toChar())
                        offset += 4
                    }
                    else -> invalid()
                }
            }
            if (!closed) invalid()
            val text = value.toString()
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                if (char.isHighSurrogate()) {
                    if (text.getOrNull(index)?.isLowSurrogate() != true) invalid()
                    index++
                } else if (char.isLowSurrogate()) invalid()
            }
            values += text
            bytes += text.toByteArray(Charsets.UTF_8).size
            if (values.size > MAX_SET_VALUES || bytes > MAX_SET_BYTES) {
                throw InvalidSearchQuery("IN supports at most $MAX_SET_VALUES values and $MAX_SET_BYTES bytes.")
            }
            if (take(']')) break
            if (!take(',')) invalid()
        }
    }
    whitespace()
    if (offset != source.length) invalid()
    return values
}
