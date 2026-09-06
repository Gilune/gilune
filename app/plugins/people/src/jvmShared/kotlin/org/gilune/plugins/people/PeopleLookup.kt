package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val PEOPLE_LOOKUP = "$PEOPLE_PLUGIN.lookup"

fun peopleLookupRegistration() = IndexedLookupRegistration(PEOPLE_LOOKUP) { PeopleLookup() }

/** People supplies its own fields, normalisation and exact insertion syntax.
 * Ranking, alternative matching and paging belong to the kernel. */
private class PeopleLookup : IndexedLookupProvider {
    override fun query(fragment: String): LookupRequest {
        val typed = casefoldNfc(fragment.removePrefix("@"))
        fun fact(predicate: String, op: ConstraintOp, value: String) =
            Constraint.FactAt(predicate, op, value, FactLocality.DOCUMENT_ROOT)
        return LookupRequest(
            constraints = listOf(fact(PERSON_KIND, ConstraintOp.EQ, "person")),
            alternatives = if (typed.isEmpty()) emptyList() else listOf(
                LookupAlternative(listOf(fact(PERSON_HANDLE, ConstraintOp.EQ, typed)), 0u),
                LookupAlternative(listOf(fact(PERSON_HANDLE, ConstraintOp.PREFIX, typed)), 1u),
                LookupAlternative(listOf(fact(PERSON_NAME_KEY, ConstraintOp.PREFIX, typed)), 2u),
            ),
            sortPredicates = listOf(PERSON_NAME_KEY, PERSON_HANDLE),
            displayPredicates = listOf(PERSON_NAME, PERSON_HANDLE, HANDLE_DISPLAY, PROBLEM),
            requiredNamespaces = listOf(PEOPLE_PLUGIN), pageSize = 30u, cursor = null,
        )
    }

    override fun present(hit: LookupHit): LookupRow {
        fun value(key: String) = hit.facts.firstOrNull { it.predicate == key }?.value
        val handle = value(HANDLE_DISPLAY)
        return LookupRow(
            value(PERSON_NAME) ?: handle ?: "Incomplete person",
            listOfNotNull(handle?.let { "@$it" }, value(PROBLEM), hit.relPath).joinToString(" · "),
            handle?.takeIf(::validToken)?.let { "@$it " },
        )
    }
}
