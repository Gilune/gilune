package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

/** Independent operator contributions. No selected type or target plugin is
 * available here: the shared composer combines these with target membership. */
fun peopleSearchOperators(): List<SearchOperatorRegistration> =
    listOf("ref", "mention", "connected", "connection", "page-connection").map { suffix ->
        SearchOperatorRegistration("people.$suffix", "people.$suffix") { _, _ -> PeopleOperator(suffix) }
    }

private class PeopleOperator(private val suffix: String) : SearchOperator {
    override val name = "people.$suffix"
    override val indexNamespaces = listOf(PEOPLE_PLUGIN)
    private val locality get() = if (suffix == "page-connection") FactLocality.DOCUMENT_ROOT else FactLocality.EXACT
    private fun handle(raw: String): String {
        val value = raw.removePrefix("@")
        require(validToken(value)) { "Choose a valid person handle." }
        return casefoldNfc(value)
    }
    private fun keyword(raw: String): String {
        require(validToken(raw)) { "Choose a valid connection keyword." }
        return casefoldNfc(raw)
    }
    override fun matches(note: SearchedNote, argument: String): Boolean = error("People filters are evaluated by the kernel")
    override fun compile(argument: String, negated: Boolean): Compiled = try {
        val (predicate, value) = when (suffix) {
            "ref" -> PERSON_REFERENCE to handle(argument)
            "mention" -> PERSON_MENTION to handle(argument)
            "connected" -> CONNECTION_PERSON to handle(argument)
            else -> {
                val parts = argument.split(':')
                when (parts.size) {
                    1 -> CONNECTION_KEYWORD to keyword(parts[0])
                    2 -> PERSON_CONNECTION to connectionValue(keyword(parts[0]), handle(parts[1]))
                    else -> throw IllegalArgumentException("Use keyword:@handle.")
                }
            }
        }
        Compiled.Exact(listOf(Constraint.FactAt(predicate, ConstraintOp.EQ, value, locality)))
    } catch (_: IllegalArgumentException) { Compiled.Never }

    override fun setBinding(argument: String): SearchSetBinding = when (suffix) {
        "ref", "mention", "connected" -> {
            require(argument.isEmpty()) { "Put person handles inside the list." }
            val predicate = when (suffix) { "ref" -> PERSON_REFERENCE; "mention" -> PERSON_MENTION; else -> CONNECTION_PERSON }
            SearchSetBinding(predicate, locality, ::handle)
        }
        else -> {
            val keyword = keyword(argument)
            SearchSetBinding(PERSON_CONNECTION, locality) { connectionValue(keyword, handle(it)) }
        }
    }
}
