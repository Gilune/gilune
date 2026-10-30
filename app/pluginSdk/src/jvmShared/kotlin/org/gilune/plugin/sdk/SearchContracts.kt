package org.gilune.plugin.sdk

import java.time.LocalDate
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.FactLocality

data class FrontMatterEntry(val key: String, val value: String)

/// A note as an operator sees it, with front matter already split off by
/// `splitNote`, so `body` and `lines` are prose alone. `lines` is computed
/// on first use: an operator that never reads them costs nothing.
class SearchedNote(
    val relPath: String,
    val name: String,
    val body: String,
    val frontMatter: List<FrontMatterEntry>,
) {
    val lines: List<String> by lazy { body.lines() }

    /// The same note with one of its lines for a body — **the narrowed-note
    /// rule** (ADR 0015 § 4), and the whole of what a line-level type needs
    /// from the evaluator. Name, path and front matter are the containing
    /// note's, so terms about text bind to the line and terms about the
    /// note bind to the note, with no new operator interface.
    fun narrowedTo(line: String): SearchedNote = SearchedNote(relPath, name, line, frontMatter)
}

/// One keyword of the query language: `name` is what the user types before
/// the colon, `matches` is its predicate over a note. Implementations must
/// be pure and total — a query is run against every note in the vault, and
/// an operator that throws would lose the whole result set.
///
/// The needle hooks are optional and affect presentation and rank only. An
/// operator that looks for a literal substring says so, and a hit then
/// highlights it (`bodyNeedle`) or counts as a name match (`nameNeedle`).
/// The default — null — means the operator filters without highlighting.
interface SearchOperator {
    val name: String
    val indexNamespaces: List<String> get() = emptyList()

    /** Bind a set-valued field independently of the selected result type. */
    fun setBinding(argument: String): SearchSetBinding? = null

    fun matches(note: SearchedNote, argument: String): Boolean

    fun bodyNeedle(argument: String): String? = null

    fun nameNeedle(argument: String): String? = null

    /// Compile the positive meaning to generic constraints. Exact means the
    /// kernel can decide it completely; Terms is a conservative prefilter;
    /// Scan delegates only the pure predicate over an indexed snapshot.
    /// The host applies whole-term negation, so it calls this with false.
    /// The parameter remains for source compatibility with existing plugins.
    fun compile(argument: String, negated: Boolean): Compiled = Compiled.Scan

    /// What the term turned out to mean, for whoever reads the results — a
    /// person under the search box, or a program running the query (owner,
    /// 2026-10-30). Relative values depend on the day and on settings the
    /// reader cannot see, so `thisweek` should say which days it took; and a
    /// value that means nothing should say so, rather than leave an empty
    /// result to be read as "nothing happened". Null says nothing.
    fun explain(argument: String): SearchExplanation? = null
}

/// One term's account of itself. `description` is what it resolved to,
/// e.g. "done 26 Oct – 1 Nov 2026"; `problem` is why it can match nothing,
/// e.g. "Unknown date ‘thismnth’ — did you mean thismonth?". A term with a
/// problem compiles to [Compiled.Never], so the two always agree.
data class SearchExplanation(val description: String? = null, val problem: String? = null)

/// What a term says to the index.
sealed interface Compiled {
    /// Constraints that must all hold of an anchor. They are ANDed with
    /// every other term's, which is what makes a query across two
    /// plugins a join nobody wrote.
    data class Terms(val constraints: List<Constraint>) : Compiled

    /** Complete declarative meaning; no residual predicate is required. */
    data class Exact(val constraints: List<Constraint>) : Compiled

    /// A pure residual predicate over indexed content; no vault reads.
    data object Scan : Compiled

    /// The term can hold of nothing at all — a date operator handed a
    /// value that is not a date. The query is answered with silence
    /// rather than with a pass over the vault that would keep nothing.
    data object Never : Compiled
}

/// One line a line-level type offers the evaluator. `number` is
/// body-relative and 1-based, exactly as [MatchedLine.number] is, so a hit
/// addresses the buffer the same way whichever type produced it.
data class CandidateLine(val number: Int, val text: String)

/// What a `type:` names — what a hit **is** (ADR 0015 § 4). [extract] is
/// which of the two kinds this is:
///
/// - **note-level** (no extractor): a hit is a note. `note` is the shell's
///   own, and the search panel's semantics unchanged.
/// - **line-level** (an extractor): a hit is a line, and each candidate is
///   judged by [SearchedNote.narrowedTo].
///
/// [operators] is a function of the day the query runs, because relative
/// words resolve **at evaluation**: `due:today` is the query's meaning,
/// not a date that rots.
/// [constraints] is what the type itself asks of the index — for a
/// line-level type, whatever makes a line one of its candidates. It is
/// a conservative prefilter unless [indexed] is true. An indexed type
/// declares its full membership with constraints and required namespaces;
/// other types supply candidate line numbers over the indexed document.
class SearchType(
    val key: String,
    val operators: (today: LocalDate) -> List<SearchOperator> = { emptyList() },
    val extract: ((SearchedNote) -> List<CandidateLine>)? = null,
    val constraints: List<Constraint> = emptyList(),
    val indexed: Boolean = false,
    val caseSensitiveFilter: String = "",
    val indexNamespaces: List<String> = emptyList(),
    /** Opt into complete kernel matching over shared Markdown items. Requires
     * indexed membership; the legacy extractor is not invoked on this path. */
    val itemScoped: Boolean = false,
)

/** A plugin binds one field to an opaque fact and encodes one literal at a
 * time. List parsing, membership, negation and paging belong to the framework.
 * The callback must be pure; it receives no host or target plugin. */
class SearchSetBinding(
    val predicate: String,
    val locality: FactLocality,
    val encodeValue: (String) -> String = { it },
)

