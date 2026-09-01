// The `filter` fence (ADR 0015 § 4, T4): a fenced block whose info string
// is `filter` holds the search language, one filter per line, and renders
// as a live bounded list of what it matches. The block is **not** a tasks
// feature — `type:` says what a hit is, and the fence is the shell's own
// registration, so blocks keep rendering with every removable plugin gone.
//
// Pure over a string, a type registry and a day: no screen, no clock, no
// Compose and no state. `today` arrives from the caller because a relative
// word resolves **at evaluation** (§ 4), and a mobile reader could render
// the same block from this same code (§ 7).
//
// Nothing here parses a second language. The block's text becomes one
// query in the DSL `Search.kt` already takes, and `searchVault` answers it.

package org.gilune.app

import java.time.LocalDate
import uniffi.gilune_ffi.PluginInfo

/// The info string the shell claims (§ 4). No surveyed tool uses it —
/// Obsidian Tasks uses `tasks` — and a stranger, Notepad and GitHub all
/// still see a legible fenced code block labelled `filter`.
const val FILTER_FENCE = "filter"

/// § 4's cap, and it is not optional: matching is cheap and *rendering*
/// results is what freezes an editor, so the cap sits on the rows drawn
/// and the rest are counted.
const val FILTER_ROW_CAP = 50

/// The same cap in **Edit mode** (owner, 2026-09-01). A block there sits
/// in the editor's own line grid and pushes the note it is written in down
/// the page, so a big query must not eat the document: eight rows, then
/// the `+N more` the reader already knows how to read. Read mode, where
/// the block is the whole page, keeps [FILTER_ROW_CAP].
const val FILTER_EDIT_ROW_CAP = 8

/// How many editor lines a collapsed block reserves: **one per row it
/// draws**, one more for a `+N more` footer, and never fewer than one —
/// a block with no matches still says so. The transformation reserves
/// exactly this many blank lines and the overlay draws exactly this many
/// rows into them, so the two cannot disagree and nothing below the block
/// mis-sits.
fun filterBlockLines(result: FilterResult): Int = when (result) {
    is FilterResult.Hint -> 1
    is FilterResult.Tasks -> maxOf(result.rows.size, 1) + if (result.more > 0) 1 else 0
    is FilterResult.Notes -> maxOf(result.rows.size, 1) + if (result.more > 0) 1 else 0
}

/// How many candidate lines one note may contribute before the engine
/// stops quoting them. A block wants every task in a note, not the five a
/// search panel quotes, and this is well past the rendering cap — what it
/// does drop is counted, so a `+N more` never under-reports.
private const val FILTER_SCAN_LINES = 200

/// What a block is, decided **before** anything is evaluated (§ 4). The
/// three degenerate readings are separated from the runnable one here
/// rather than inside the engine, because they need different answers: an
/// unregistered `type:` finds nothing *and* an empty result set says
/// nothing about why, which is precisely what a disabled plugin leaves
/// behind.
sealed interface FilterBlock {
    /// No filters at all. **Matches nothing** and says so — the empty
    /// query is the freeze § 4 refuses to ship.
    data object Empty : FilterBlock

    /// Filters, but no `type:`. **No guess**: a block that does not say
    /// what it is looking at is answered with a hint, never with a default.
    data object NoType : FilterBlock

    /// A `type:` nobody registered — a typo, or a plugin switched off or
    /// removed. The key is carried so the hint can name it.
    data class UnknownType(val key: String) : FilterBlock

    /// One query, in the search DSL, against a registered type. `typeKey`
    /// is the **registered** spelling, not what was typed, so a renderer
    /// may switch on it.
    data class Runnable(val query: String, val typeKey: String) : FilterBlock
}

/// One note a `type:note` block matched. Note-level hits are notes, which
/// is the search panel's semantics unchanged (§ 4).
data class NoteRow(val name: String, val relPath: String)

/// What a block draws. `more` is what the cap held back, so the footer can
/// be honest about a result set it is not showing all of.
sealed interface FilterResult {
    /// The single inline row a degenerate block renders (§ 4).
    data class Hint(val text: String) : FilterResult

    data class Tasks(val rows: List<TaskHit>, val more: Int) : FilterResult

    data class Notes(val rows: List<NoteRow>, val more: Int) : FilterResult
}

/// The block's text as one query. **Line-joining is the AND rule**: the
/// DSL already ANDs by whitespace, so one filter per line joined by spaces
/// is "the search DSL's own semantics applied twice over" (§ 4) and needs
/// no second parser. Blank lines are ignored; there is no comment syntax,
/// because `#` opens a plausible term.
fun filterQuery(block: String): String =
    block.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/// Reads the block, and only reads it: which of the four it is.
///
/// `note` resolves whether or not the caller listed it, exactly as
/// `searchVault` resolves it, so classification can never call a block
/// unknown that the engine would happily run.
fun classifyFilterBlock(block: String, types: List<SearchType>): FilterBlock {
    val query = filterQuery(block)
    if (query.isEmpty()) return FilterBlock.Empty
    val key = filterTypeKey(query) ?: return FilterBlock.NoType
    val type = (types + NOTE_SEARCH_TYPE).firstOrNull { it.key.equals(key, ignoreCase = true) }
        ?: return FilterBlock.UnknownType(key)
    return FilterBlock.Runnable(query, type.key)
}

/// The hint a degenerate block renders, or null for a runnable one.
/// Terse, and it names what is actually registered right now rather than
/// what was registered when this was written — a removed contributor's
/// type must not be offered as the fix.
fun filterHint(block: FilterBlock, types: List<SearchType>): String? = when (block) {
    FilterBlock.Empty -> "an empty filter matches nothing"
    FilterBlock.NoType -> "a filter names its type: — try ${registeredTypes(types)}"
    is FilterBlock.UnknownType -> "no type called ${block.key} is registered — is its plugin on?"
    is FilterBlock.Runnable -> null
}

/// What a block with no matches says. Drawing nothing at all would read as
/// a broken block rather than as an answered question.
const val FILTER_NO_MATCHES = "nothing matches"

/// Evaluate one block against the vault, from the **live plugin list** —
/// the door the shell comes in by. The plugins are what decide which types
/// exist, so the removal story runs through here: switch the tasks plugin
/// off and `type:task` is a type nobody registered, which is the honest
/// answer rather than an empty list.
fun runFilterBlock(
    vaultDir: String,
    block: String,
    plugins: List<PluginInfo>,
    today: LocalDate,
    cap: Int = FILTER_ROW_CAP,
): FilterResult = evaluateFilterBlock(vaultDir, block, searchTypesFor(plugins), today, cap)

/// The same against a type registry directly — what [runFilterBlock] calls
/// once it has one, and the form a renderer uses when it has already built
/// the registry for this composition. Two names rather than an overload
/// because both lists erase to the same JVM signature.
///
/// One pass over the vault per block (§ 4: whole vault, each file read
/// once, no index), so a note carrying several blocks pays several passes.
/// The tick-not-keystroke rule and the row cap are what keep that honest.
fun evaluateFilterBlock(
    vaultDir: String,
    block: String,
    types: List<SearchType>,
    today: LocalDate,
    cap: Int = FILTER_ROW_CAP,
): FilterResult {
    val classified = classifyFilterBlock(block, types)
    if (classified !is FilterBlock.Runnable) {
        // Total by construction: only a runnable block has no hint.
        return FilterResult.Hint(filterHint(classified, types).orEmpty())
    }
    val hits = searchVault(
        vaultDir,
        classified.query,
        types = types,
        today = today,
        maxLines = FILTER_SCAN_LINES,
    )
    // **What renders is the type's row** (§ 4). There are two types in v1,
    // so the choice is a branch here rather than a renderer carried on
    // `SearchType`; a third type is where moving it earns its keep, and
    // `search.types` is the seam it would move through.
    if (classified.typeKey.equals(TASK_SEARCH_TYPE, ignoreCase = true)) {
        val rows = taskRows(hits)
        // Lines the engine stopped quoting are rows this block would have
        // drawn, so they are counted with the ones the cap held back.
        val dropped = hits.sumOf { it.moreMatches }
        return FilterResult.Tasks(rows.take(cap), (rows.size - cap).coerceAtLeast(0) + dropped)
    }
    val rows = hits.map { NoteRow(it.name, it.relPath) }
    return FilterResult.Notes(rows.take(cap), (rows.size - cap).coerceAtLeast(0))
}

private fun registeredTypes(types: List<SearchType>): String {
    val offered = if (types.any { it.key.equals(NOTE_SEARCH_TYPE.key, ignoreCase = true) }) {
        types
    } else {
        types + NOTE_SEARCH_TYPE
    }
    return offered.joinToString(" or ") { "type:${it.key}" }
}

private const val FILTER_TYPE_KEYWORD = "type"

/// The block's `type:` term, or null. Mirrors `Search.kt`'s `splitType`,
/// which is file-private — the first occurrence wins, the comparison is
/// case-insensitive, a bracketed metadata term is never a type term, and
/// `-type:` is not a form. Duplicated deliberately and narrowly, exactly
/// as `TaskSearch.kt` duplicates `tokenPattern`: if the engine ever
/// publishes it, this becomes the import.
private fun filterTypeKey(query: String): String? {
    for (token in filterTokens(query)) {
        val colon = if (token.startsWith('[')) -1 else token.indexOf(':')
        if (colon <= 0) continue
        val argument = unquoteFilterToken(token.drop(colon + 1))
        if (argument.isNotEmpty() && token.take(colon).equals(FILTER_TYPE_KEYWORD, ignoreCase = true)) {
            return argument
        }
    }
    return null
}

/// Whitespace splits terms, except inside a quoted phrase or a bracketed
/// metadata term. A mirror of the engine's tokeniser for the same reason,
/// and only so far as finding the type term needs.
private fun filterTokens(query: String): List<String> {
    val tokens = mutableListOf<String>()
    val token = StringBuilder()
    var quoted = false
    var brackets = 0
    for (char in query) {
        when {
            char == '"' -> {
                quoted = !quoted
                token.append(char)
            }
            char == '[' && !quoted -> {
                brackets++
                token.append(char)
            }
            char == ']' && !quoted && brackets > 0 -> {
                brackets--
                token.append(char)
            }
            char.isWhitespace() && !quoted && brackets == 0 -> {
                if (token.isNotEmpty()) tokens.add(token.toString())
                token.clear()
            }
            else -> token.append(char)
        }
    }
    if (token.isNotEmpty()) tokens.add(token.toString())
    return tokens
}

private fun unquoteFilterToken(text: String): String =
    if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
        text.substring(1, text.length - 1)
    } else {
        text
    }
