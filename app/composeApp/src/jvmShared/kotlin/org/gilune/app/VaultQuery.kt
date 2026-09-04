// The query language over the system index (ADR 0020 § 2.4): the shell's
// half of "plugins own the words, the kernel owns the join".
//
// What replaced the scan, in one sentence: **the index says which notes
// could possibly match, and only those are read.** Every term still
// decides for itself, against the very note it always read — so the
// compilation in `Search.kt` is a pre-filter and nothing else, and the
// answer here is the answer the walk gave, with the files that could not
// have contributed never opened.
//
// That asymmetry is the whole safety argument and it is worth stating
// plainly: a constraint that names too many anchors costs a read; a
// constraint that named too few would lose a hit and nothing downstream
// could notice. So an operator compiles only what it can state exactly,
// and `Compiled.Scan` — the default — is always correct.
//
// The lines a hit quotes are read here rather than stored as facts: line
// text is already in the kernel's full-text index and a second copy in
// the fact table would be one fact with two homes.

package org.gilune.app

import java.time.LocalDate
import uniffi.gilune_ffi.Anchor
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.ConstraintOp
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.QueryScope
import uniffi.gilune_ffi.TextTarget

/// How many notes one query may answer with. The kernel's own ceiling
/// (`index::MAX_RESULTS`), so this is the bound rather than a second one:
/// a vault larger than this is a vault whose search wants paging, which
/// is a decision for the day somebody has one.
const val MAX_QUERY_NOTES = 10_000u

/// Every note, as a constraint.
///
/// A query made only of negations — `-milk`, or a bare `type:note` — asks
/// the index to name anchors and none of its terms can. Rather than
/// special-casing "no constraints" into a directory walk, the enumeration
/// *is* a constraint: every note has a path row, and every path contains
/// the empty string. The cost is the honest one — such a query reads what
/// it always read.
private val EVERY_NOTE = Constraint.Text(TextTarget.PATH, "")

/// Notes matching `query`, best first — the same hits, in the same order,
/// that reading every file in the vault would have produced.
///
/// `types` is the type registry and `today` the day relative words resolve
/// against; both default so a caller that only wants note-level search
/// says nothing about either. `maxLines` caps the lines one hit quotes;
/// the rest are counted in `moreMatches`, and a caller rendering rows
/// rather than a panel (T4's `filter` blocks) raises it.
///
/// A note the index names and the disk no longer holds is skipped, as an
/// unreadable file always was.
fun queryVault(
    gilune: Gilune,
    vaultDir: String,
    query: String,
    operators: SearchOperators = SearchOperators(),
    types: List<SearchType> = listOf(NOTE_SEARCH_TYPE),
    today: LocalDate = LocalDate.now(),
    maxLines: Int = MAX_MATCHED_LINES,
): List<SearchHit> {
    val parsed = parseQuery(query, operators, types, today) ?: return emptyList()
    val constraints = compileConstraints(parsed) ?: return emptyList()
    val extract = parsed.type.extract
    return anchors(gilune, constraints)
        .mapNotNull { anchor -> searchedNote(vaultDir, anchor.relPath) }
        .mapNotNull { note ->
            if (extract == null) {
                hitFor(note, parsed.terms, maxLines)
            } else {
                lineHitFor(note, parsed.terms, extract, maxLines)
            }
        }
        .sortedWith(RANK)
}

/// The conjunction a parsed query becomes, or null when a term can hold
/// of nothing and the answer is silence.
///
/// Every anchor is asked for at **note** scope. A line-level type is
/// answered by re-deriving its candidates from the note itself, which the
/// hit already needs the note's text for, so asking for lines as well
/// would be asking the same question twice.
private fun compileConstraints(parsed: ParsedQuery): List<Constraint>? {
    val constraints = parsed.type.constraints.toMutableList()
    for (term in parsed.terms) {
        when (val compiled = term.term.compile(term.negated)) {
            is Compiled.Terms -> constraints += compiled.constraints
            Compiled.Never -> return null
            Compiled.Scan -> Unit
        }
    }
    // `Ne` and `NotExists` narrow what other constraints found and name
    // nothing of their own, so a query with only those would be answered
    // with silence rather than with the vault (ADR 0020 § 10, K3).
    if (constraints.none { it.generates() }) constraints += EVERY_NOTE
    return constraints
}

private fun anchors(gilune: Gilune, constraints: List<Constraint>): List<Anchor> =
    runCatching { gilune.query(constraints, QueryScope.NOTE, MAX_QUERY_NOTES) }
        .onFailure { GiluneLog.warn("a query could not be answered from the index", it) }
        .getOrDefault(emptyList())

private fun Constraint.generates(): Boolean = when (this) {
    is Constraint.Fact -> op != ConstraintOp.NE && op != ConstraintOp.NOT_EXISTS
    is Constraint.Text -> true
}
