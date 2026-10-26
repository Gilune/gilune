package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val PEOPLE_EDITOR = "$PEOPLE_PLUGIN.editor"

private fun rootFact(key: String, value: String) = Constraint.FactAt(key, ConstraintOp.EQ, value, FactLocality.DOCUMENT_ROOT)

internal fun definitionLookup(kind: String, value: String, predicate: String, identifier: String) = LookupRequest(
    listOf(rootFact(kind, value), rootFact(predicate, identifier)), emptyList(), emptyList(),
    listOf(PERSON_NAME, HANDLE_DISPLAY, KEYWORD, LABEL), listOf(PEOPLE_PLUGIN), 2u, null)

fun peopleEditorRegistration() = EditorCompletionRegistration(PEOPLE_EDITOR, triggers = setOf('@', '/')) { context, _ ->
    val mention = editorTrigger(context, '@')
    val slash = editorTrigger(context, '/')
    val start = mention ?: slash ?: if (context.toolbar) context.caret else null
    if (start == null || (start < context.caret && !ordinaryEditorText(context.text, start, context.caret))) null
    else if (mention != null) {
        val fragment = context.text.substring(start + 1, context.caret)
        if (fragment.isNotEmpty() && !validToken(fragment)) null else {
            val provider = peopleLookupRegistration().create("")
            EditorCompletion(start, context.caret,
                options = if (validToken(fragment)) listOf(EditorOption("Create person…", "@$fragment",
                    create = EditorCreate(PEOPLE_PANEL, mapOf("name" to fragment, "handle" to fragment)) { source ->
                        val handle = SourceView(source).metadata()["handle"]?.string()?.takeIf(::validToken)
                        EditorOption("Insert created person", insertion = handle?.let { "@$it " },
                            unique = handle?.let { definitionLookup(PERSON_KIND, "person", PERSON_HANDLE, casefoldNfc(it)) })
                    })) else emptyList(),
                lookup = provider.query(fragment), present = { hit ->
                    val row = provider.present(hit)
                    val handle = hit.facts.firstOrNull { it.predicate == PERSON_HANDLE }?.value
                    EditorOption(row.label, row.detail, row.insertion,
                        unique = handle?.let { definitionLookup(PERSON_KIND, "person", PERSON_HANDLE, it) })
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
                // Choosing a connection writes it at once and leaves the caret
                // after `@`, so the ordinary mention lookup opens on the person
                // and typing narrows it (owner ask, 2026-10-26). Escape there
                // leaves `assign: @` as the user's own bytes.
                EditorOption(value(LABEL) ?: keyword ?: "Incomplete connection", keyword.orEmpty(),
                    insertion = keyword?.takeIf { validToken(it) && value(PROBLEM) == null }?.let { "$it: @" },
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
