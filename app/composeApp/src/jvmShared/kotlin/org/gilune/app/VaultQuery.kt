// Compile plugin vocabulary to the kernel search contract and adapt its results.
// No files, matching, extraction, or ranking are performed by this adapter.

package org.gilune.app

import org.gilune.plugin.sdk.Compiled
import org.gilune.plugin.sdk.FrontMatterEntry
import org.gilune.plugin.sdk.SearchType
import org.gilune.plugin.sdk.SearchedNote

import java.time.LocalDate
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.GiluneException
import uniffi.gilune_ffi.SearchClause
import uniffi.gilune_ffi.SearchDocument
import uniffi.gilune_ffi.SearchExtension
import uniffi.gilune_ffi.SearchPage
import uniffi.gilune_ffi.SearchRequest
import uniffi.gilune_ffi.TextTarget

const val SEARCH_PAGE_SIZE = 50u

/// A compiled query, with the account of itself a reader needs (owner,
/// 2026-10-30): [explanations] say what relative terms resolved to — "done
/// 26 Oct – 1 Nov 2026" — and [problems] say why a term can match nothing,
/// so an empty result is never mistaken for an answer. The same lists serve
/// the search box, a filter block and any program that runs a query.
internal class KernelSearch(
    val request: SearchRequest,
    val extension: SearchExtension?,
    val itemScoped: Boolean = false,
    val explanations: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
) {
    fun prepare(gilune: Gilune): uniffi.gilune_ffi.SearchQuery = gilune.prepareSearch(request, itemScoped, extension)

    fun page(gilune: Gilune, cursor: String? = null): SearchPage =
        if (itemScoped) gilune.searchItems(request.copy(cursor = cursor), extension)
        else gilune.search(request.copy(cursor = cursor), extension)
}

internal fun compileSearch(
    query: String,
    operators: SearchOperators = SearchOperators(),
    types: List<SearchType> = listOf(NOTE_SEARCH_TYPE),
    today: LocalDate = LocalDate.now(),
    maxLines: Int = MAX_MATCHED_LINES,
    pageSize: UInt = SEARCH_PAGE_SIZE,
    groupByNote: Boolean = true,
    sortPredicate: String = "",
): KernelSearch? {
    val parsed = parseQuery(query, operators, types, today) ?: return null
    require(!parsed.type.itemScoped || parsed.type.indexed) { "Item-scoped types must declare indexed membership" }
    val predicates = mutableListOf<(SearchedNote) -> Boolean>()
    val clauses = mutableListOf<SearchClause>()
    fun clause(constraints: List<Constraint>, negated: Boolean = false, sensitive: Boolean = false,
               residual: UInt? = null, never: Boolean = false) =
        SearchClause(constraints, negated, sensitive, residual, never)
    if (parsed.type.constraints.isNotEmpty()) clauses += clause(parsed.type.constraints)
    if (parsed.type.caseSensitiveFilter.isNotEmpty()) {
        clauses += clause(listOf(Constraint.Text(TextTarget.BODY, parsed.type.caseSensitiveFilter)), sensitive = true)
    }
    for (term in parsed.terms) {
        when (val compiled = term.term.compile(false)) {
            is Compiled.Exact -> clauses += clause(compiled.constraints, term.negated)
            Compiled.Never -> clauses += clause(emptyList(), term.negated, never = true)
            else -> {
                // Older plugins may supply a conservative pre-filter. Their
                // predicate still runs, against the indexed snapshot only.
                if (compiled is Compiled.Terms && !term.negated) clauses += clause(compiled.constraints)
                val id = predicates.size.toUInt()
                predicates += { note -> term.term.matches(note) }
                clauses += clause(emptyList(), term.negated, residual = id)
            }
        }
    }
    val extractor = parsed.type.extract.takeUnless { parsed.type.indexed }
    val extension = if (predicates.isEmpty() && extractor == null) null else object : SearchExtension {
        override fun matches(key: UInt, document: SearchDocument): Boolean =
            pluginPredicate { predicates[key.toInt()](document.asPluginNote()) }
        override fun candidates(document: SearchDocument): List<UInt> =
            pluginPredicate { extractor?.invoke(document.asPluginNote())?.map { it.number.toUInt() }.orEmpty() }
    }
    val positive = parsed.terms.filterNot { it.negated }
    val accounts = parsed.terms.mapNotNull { term -> term.term.explain()?.let { term to it } }
    return KernelSearch(
        SearchRequest(
            clauses = clauses,
            lineMode = parsed.type.itemScoped || parsed.type.extract != null,
            extensionCandidates = extractor != null,
            groupByNote = groupByNote,
            bodyNeedles = positive.mapNotNull { it.term.bodyNeedle }.filter { it.isNotEmpty() },
            nameNeedles = positive.mapNotNull { it.term.nameNeedle }.filter { it.isNotEmpty() },
            sortPredicate = sortPredicate,
            requiredNamespaces = (parsed.type.indexNamespaces + parsed.terms.flatMap { it.term.indexNamespaces }).distinct().sorted(),
            extensionKey = "${if (extension == null) "" else query}|$today|${operators.names.sorted()}|${parsed.type.key}|${parsed.type.caseSensitiveFilter}",
            pageSize = pageSize.coerceIn(1u, 500u),
            maxLines = maxLines.coerceIn(0, 200).toUInt(),
            cursor = null,
        ), extension, parsed.type.itemScoped,
        explanations = accounts.mapNotNull { (term, account) ->
            account.description?.let { if (term.negated) "not $it" else it }
        },
        problems = accounts.mapNotNull { (_, account) -> account.problem },
    )
}

private fun <T> pluginPredicate(run: () -> T): T = try { run() } catch (error: Exception) {
    // UniFFI can transport a declared error; an arbitrary JVM exception would
    // otherwise become an unexpected callback failure at the Rust boundary.
    throw GiluneException.Invalid("Search plugin failed: ${error.message ?: error.javaClass.simpleName}")
}

private fun SearchDocument.asPluginNote() = SearchedNote(
    relPath, name, body, properties.map { FrontMatterEntry(it.key, it.value) },
)

internal fun uniffi.gilune_ffi.SearchHit.displayHit() = SearchHit(
    relPath, name, nameMatched,
    lines.map { line -> MatchedLine(line.number.toInt(), line.text,
        line.ranges.map { it.start.toInt() until it.end.toInt() }) } +
        items.map { item -> MatchedLine(item.firstLine.toInt(), item.text,
            item.ranges.map { it.start.toInt() until it.end.toInt() }) },
    moreMatches.toInt(), matchCount.toInt(),
)

/** An unavailable or lagging index is not an authoritative empty answer. */
class SearchIndexPending(val issues: List<String>) : IllegalStateException(issues.joinToString("; "))

internal fun SearchPage.requireComplete(): SearchPage {
    if (!indexComplete) throw SearchIndexPending(issues)
    return this
}

/** Full-result compatibility helper for non-UI callers and equivalence tests.
 * Interactive consumers request bounded pages directly. */
@Suppress("UNUSED_PARAMETER")
fun queryVault(
    gilune: Gilune,
    vaultDir: String,
    query: String,
    operators: SearchOperators = SearchOperators(),
    types: List<SearchType> = listOf(NOTE_SEARCH_TYPE),
    today: LocalDate = LocalDate.now(),
    maxLines: Int = MAX_MATCHED_LINES,
): List<SearchHit> {
    val search = compileSearch(query, operators, types, today, maxLines, pageSize = 500u) ?: return emptyList()
    val hits = mutableListOf<SearchHit>()
    search.prepare(gilune).use { reader ->
        var cursor: String? = null
        do {
            val page = reader.page(cursor)
            if (!page.indexComplete) throw SearchIndexPending(page.issues)
            hits += page.hits.map { it.displayHit() }
            cursor = page.nextCursor
        } while (cursor != null)
    }
    return hits
}
