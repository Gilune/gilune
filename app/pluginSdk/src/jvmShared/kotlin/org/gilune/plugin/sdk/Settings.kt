package org.gilune.plugin.sdk

// Compatibility helpers for existing bundled settings documents.
// A strict structured settings contract is a separate migration.
fun jsonString(document: String, key: String): String? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .find(document)?.groupValues?.get(1)?.let(::unescapeJsonString)

/// The same targeted extraction for a boolean. Absent — or anything that
/// is not `true`/`false` — is null, so every caller states its own default
/// rather than inheriting one from a parse failure.
fun jsonBoolean(document: String, key: String): Boolean? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)\\b")
        .find(document)?.groupValues?.get(1)?.toBooleanStrictOrNull()

private fun unescapeJsonString(raw: String): String = buildString(raw.length) {
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char == '\\' && index + 1 < raw.length) {
            append(
                when (val escaped = raw[index + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    'b' -> '\b'
                    else -> escaped
                },
            )
            index += 2
        } else {
            append(char)
            index++
        }
    }
}

fun escapeJsonString(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
