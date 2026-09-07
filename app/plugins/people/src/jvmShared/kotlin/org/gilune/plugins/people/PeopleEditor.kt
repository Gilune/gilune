package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val PEOPLE_EDITOR = "$PEOPLE_PLUGIN.editor"

private fun rootFact(key: String, value: String) = Constraint.FactAt(key, ConstraintOp.EQ, value, FactLocality.DOCUMENT_ROOT)

internal fun definitionLookup(kind: String, value: String, predicate: String, identifier: String) = LookupRequest(
    listOf(rootFact(kind, value), rootFact(predicate, identifier)), emptyList(), emptyList(),
    listOf(PERSON_NAME, HANDLE_DISPLAY, KEYWORD, LABEL), listOf(PEOPLE_PLUGIN), 2u, null)

fun peopleEditorRegistration() = EditorCompletionRegistration(PEOPLE_EDITOR, triggers = setOf('@', '/')) { context, _ ->
    val continued = context.continuation
    val mention = continued?.start ?: editorTrigger(context, '@')
    val slash = editorTrigger(context, '/')
    val start = mention ?: slash ?: if (context.toolbar) context.caret else null
    if (start == null || (start < context.caret && !ordinaryEditorText(context.text, start, context.caret))) null
    else if (mention != null) {
        val required = continued?.data?.let { key ->
            listOf(definitionLookup(CONNECTION_KIND, "connection", KEYWORD, key).let { query ->
                query.copy(constraints = query.constraints + rootFact(RETIRED, "false"))
            })
        }.orEmpty()
        val fragmentStart = continued?.fragmentStart ?: start + 1
        val fragment = context.text.substring(fragmentStart.coerceAtMost(context.caret), context.caret)
        if (fragment.isNotEmpty() && !validToken(fragment)) null else {
            val provider = peopleLookupRegistration().create("")
            EditorCompletion(start, context.caret,
                options = if (validToken(fragment)) listOf(EditorOption("Create person…", "@$fragment",
                    create = EditorCreate(PEOPLE_PANEL, mapOf("name" to fragment, "handle" to fragment)) { source ->
                        val handle = SourceView(source).metadata()["handle"]?.string()?.takeIf(::validToken)
                        EditorOption("Insert created person", insertion = handle?.let { (continued?.data?.let { key -> "$key: " } ?: "") + "@$it " },
                            unique = handle?.let { definitionLookup(PERSON_KIND, "person", PERSON_HANDLE, casefoldNfc(it)) }, requiredDefinitions = required)
                    })) else emptyList(),
                lookup = provider.query(fragment), present = { hit ->
                    val row = provider.present(hit)
                    val handle = hit.facts.firstOrNull { it.predicate == PERSON_HANDLE }?.value
                    EditorOption(row.label, row.detail, row.insertion?.let { (continued?.data?.let { key -> "$key: " } ?: "") + it },
                        unique = handle?.let { definitionLookup(PERSON_KIND, "person", PERSON_HANDLE, it) }, requiredDefinitions = required)
                })
        }
    } else {
        val fragment = if (slash == null) "" else context.text.substring(start + 1, context.caret)
        val typed = casefoldNfc(fragment)
        fun prefix(key: String) = Constraint.FactAt(key, ConstraintOp.PREFIX, typed, FactLocality.DOCUMENT_ROOT)
        EditorCompletion(start, context.caret, lookup = LookupRequest(
            listOf(rootFact(CONNECTION_KIND, "connection"), rootFact(RETIRED, "false")),
            if (typed.isEmpty()) emptyList() else listOf(LookupAlternative(listOf(prefix(KEYWORD)), 0u)),
            listOf(KEYWORD), listOf(KEYWORD, LABEL, PROBLEM), listOf(PEOPLE_PLUGIN), 30u, null),
            present = { hit ->
                fun value(key: String) = hit.facts.firstOrNull { it.predicate == key }?.value
                val keyword = value(KEYWORD)
                EditorOption(value(LABEL) ?: keyword ?: "Incomplete connection", keyword.orEmpty(),
                    continueWith = keyword?.takeIf { validToken(it) && value(PROBLEM) == null },
                    unique = keyword?.let { definitionLookup(CONNECTION_KIND, "connection", KEYWORD, it) })
            })
    }
}

private fun ordinaryEditorText(text: String, start: Int, end: Int): Boolean {
    val view = SourceView(inspectSourceText(text))
    val from = view.byteOffset(start)
    val to = view.byteOffset(end)
    return view.snapshot.textRanges.any { from >= it.start && to <= it.end }
}
