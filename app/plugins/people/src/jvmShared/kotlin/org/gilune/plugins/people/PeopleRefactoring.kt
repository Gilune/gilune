package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val PEOPLE_REFACTORING = "$PEOPLE_PLUGIN.rename"

fun peopleRefactoringRegistration() = RefactoringRegistration(PEOPLE_REFACTORING, "1",
    actions = { source ->
        val properties = SourceView(source).metadata()
        when (properties["gilune-type"]?.string()) {
            PERSON_TYPE -> properties["handle"]?.string()?.let { listOf(RefactoringAction("handle", "Rename handle", it)) }.orEmpty()
            CONNECTION_TYPE -> properties["keyword"]?.string()?.let { listOf(RefactoringAction("keyword", "Rename connection", it, true)) }.orEmpty()
            else -> emptyList()
        }
    },
    validate = { intent ->
        require(intent.operation in listOf("handle", "keyword") && validToken(intent.before) && validToken(intent.after)) { "Choose valid names without spaces or Markdown punctuation." }
        require(intent.before != intent.after) { "Choose a different spelling." }
        require(!intent.merge || intent.operation == "keyword") { "Person identities cannot be merged by a handle rename." }
        fun query(value: String) = if (intent.operation == "handle") definitionLookup(PERSON_KIND, "person", PERSON_HANDLE, casefoldNfc(value))
            else definitionLookup(CONNECTION_KIND, "connection", KEYWORD, casefoldNfc(value))
        RefactoringValidation(query(intent.before), query(intent.after))
    },
    candidates = { intent -> LocationQuery(listOf(Constraint.FactAt(
        if (intent.operation == "handle") PERSON_REFERENCE else CONNECTION_KEYWORD, ConstraintOp.EQ,
        casefoldNfc(intent.before), FactLocality.EXACT)), LocationKind.ANY, listOf(PEOPLE_PLUGIN), 100u, null) },
    propose = ::peopleRenameEdits,
)

fun peopleRenameEdits(intent: RefactoringIntent, source: SourceTextSnapshot): List<SourceTextEdit> {
    val view = SourceView(source)
    val before = casefoldNfc(intent.before)
    val edits = recognisePeople(view).mapNotNull { ref ->
        val range = if (intent.operation == "handle" && ref.handle == before) ref.handleRange
            else if (intent.operation == "keyword" && ref.keyword == before) ref.keywordRange else null
        range?.let { SourceTextEdit(it, intent.after) }
    }.toMutableList()
    val properties = view.metadata()
    val definitionType = if (intent.operation == "handle") PERSON_TYPE else CONNECTION_TYPE
    if (properties["gilune-type"]?.string() == definitionType &&
        properties[intent.operation]?.string()?.let(::casefoldNfc) == before) {
        val change = if (intent.merge) PropertyChange("retired", "true") else PropertyChange(intent.operation, pageJsonString(intent.after))
        val changed = changeSourceProperties(source.text, source.contentVersion, listOf(change))
        edits += sourceDifference(source.text, changed.text)
    }
    val connections = properties["connections"]
    if (connections?.kind == "mapping") {
        var changed = false
        val values = linkedMapOf<String, String>()
        for ((key, value) in connections.entries()) {
            val keyword = key.string() ?: error("Connections with non-string keys need manual source editing.")
            val renamedKey = if (intent.operation == "keyword" && casefoldNfc(keyword) == before) {
                changed = true; intent.after
            } else keyword
            fun encode(entry: SourceValue): String {
                val raw = entry.string()
                if (intent.operation == "handle" && raw?.startsWith('@') == true && casefoldNfc(raw.substring(1)) == before) {
                    changed = true; return pageJsonString("@${intent.after}")
                }
                return when (entry.kind) {
                    "string" -> pageJsonString(raw!!)
                    "sequence" -> entry.children().joinToString(",", "[", "]", transform = ::encode)
                    "boolean", "number" -> entry.value!!
                    "null" -> "null"
                    else -> error("This connection value needs manual source editing.")
                }
            }
            val encoded = encode(value)
            val existing = values.keys.singleOrNull { casefoldNfc(it) == casefoldNfc(renamedKey) }
            if (existing != null) {
                require(intent.merge) { "This page already has the destination keyword. Review a merge." }
                fun elements(json: String) = if (json.startsWith('[')) json.substring(1, json.length - 1) else json
                values[existing] = "[${elements(values.getValue(existing))},${elements(encoded)}]"
            } else values[renamedKey] = encoded
        }
        if (changed) {
            val entry = source.metadataEntries.singleOrNull { it.key == "connections" }
                ?: error("This frontmatter layout needs manual source editing.")
            val raw = source.text.substring(view.charOffset(entry.range.start), view.charOffset(entry.range.end))
            val newline = if (raw.contains("\r\n")) "\r\n" else "\n"
            val trailing = raw.splitToSequence(newline).toList().asReversed()
                .takeWhile { it.isBlank() || it.trimStart().startsWith('#') }.asReversed().joinToString(newline)
            val prefix = source.text.substring(view.charOffset(entry.range.start), view.charOffset(entry.valueRange.start))
            val encoded = values.entries.joinToString(", ", "{", "}") { (key, value) -> "${pageJsonString(key)}: $value" }
            edits += SourceTextEdit(entry.range, "$prefix $encoded$newline$trailing")
        }
    }
    return edits.sortedBy { it.range.start }
}
