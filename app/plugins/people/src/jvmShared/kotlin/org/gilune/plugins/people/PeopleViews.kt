package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val PEOPLE_VIEW = "$PEOPLE_PLUGIN.view"

fun peopleViewRegistration() = DocumentViewRegistration(PEOPLE_VIEW) { source ->
    val view = SourceView(source)
    val handles = peopleReferenceFacts(view).filter { it.predicate == PERSON_REFERENCE }.map { it.value }.distinct()
    // Each mention as written, `@` included, keyed by the same canonical
    // handle the facts carry.
    val written = recognisePeople(view).groupBy({ it.handle }) {
        (view.charOffset(it.handleRange.start) - 1) until view.charOffset(it.handleRange.end)
    }
    val references = handles.map { handle -> DocumentReference("@$handle",
        definitionLookup(PERSON_KIND, "person", PERSON_HANDLE, handle),
        EditorCreate(PEOPLE_PANEL, mapOf("name" to handle, "handle" to handle)),
        written[handle].orEmpty()) }
    val properties = view.metadata()
    val handle = properties["handle"]?.string()?.takeIf(::validToken)
    val sections = if (properties["gilune-type"]?.string() == PERSON_TYPE && handle != null) {
        listOf("All references" to PERSON_REFERENCE, "Mentions" to PERSON_MENTION, "Connections" to CONNECTION_PERSON).map { (label, predicate) ->
            DocumentSection(label, LocationQuery(listOf(Constraint.FactAt(predicate, ConstraintOp.EQ,
                casefoldNfc(handle), FactLocality.EXACT)), LocationKind.ANY, listOf(PEOPLE_PLUGIN), 30u, null))
        }
    } else emptyList()
    if (references.isEmpty() && sections.isEmpty()) null else DocumentView("People", references, sections)
}
