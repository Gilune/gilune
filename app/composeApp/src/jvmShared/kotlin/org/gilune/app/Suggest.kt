// The `/` menu's state machine (ADR 0015 § 2, the `editor.suggest` seam
// in § 5). Two stages — the registered fields, then the chosen field's
// values — driven by the keyboard, and answering one question when Enter
// arrives: **which exact range of the buffer to replace, and with exactly
// what text**.
//
// A session never touches the buffer. It reads the line and the caret and
// returns ranges; writing is the caller's, through [replaceRange]. That
// is what makes dismissal free: Escape drops the session and the `/` and
// whatever follows it stay on screen as the user's own bytes, because
// nothing ever removed them.
//
// The session is **immutable**. Every transition returns a new one, so a
// Compose caller holding it in `mutableStateOf` recomposes on the
// assignment, and a test can hold two generations at once. Pure over
// strings and dates, no Compose, so it belongs to `jvmShared` and a
// mobile editor could drive the same machine (ADR 0006).

package org.gilune.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.time.LocalDate
import java.util.Locale

/// One offerable row. `display` is the words, `detail` the machine form
/// shown alongside — the resolved ISO date in stage two, the field key in
/// stage one — and `insert` the exact token to write.
///
/// `insert` is null for a row that cannot be accepted yet: a date still
/// being typed keeps the menu alive without being insertable (see
/// [DateCandidate]). Enter on such a row writes nothing.
data class SuggestCandidate(val display: String, val detail: String, val insert: String?)

/// A field the `/` menu offers in stage one, and whose values it offers
/// in stage two. Shaped to be **built from a contribution**: ADR 0015 § 3
/// has a `tasks.fields` contribution declare `key`, `label` and a values
/// provider id in its `config`, and [suggestField] turns those three
/// strings into one of these. `due` is the first registration; a People
/// plugin's `assignee` is another, arriving through the same door.
///
/// `values` is honestly Kotlin in v1 — the wasm route needs a
/// `suggest(line, fragment) -> candidates` export the WIT contract has no
/// shape for (§ 7). The signature is the shape that export would take.
class SuggestField(
    val key: String,
    val label: String,
    val values: (fragment: String, today: LocalDate) -> List<SuggestCandidate>,
)

/// What Enter decided: replace exactly `replaceRange` (inclusive of both
/// ends, as `String.replaceRange(IntRange, …)` is) with exactly `text`.
/// The range spans the trigger `/` through everything typed since, so
/// accepting cleans up the user's typing as it writes the token.
data class Acceptance(val replaceRange: IntRange, val text: String)

enum class SuggestStage { FIELDS, VALUES }

/// The result of Enter. Three outcomes, because Enter in stage one and
/// Enter in stage two are different events: one advances the menu, the
/// other writes.
sealed interface SuggestOutcome {
    /// The menu stays up, in this state. Stage one passing through to
    /// stage two, or Enter on a row that is not insertable yet.
    data class Open(val session: SuggestSession) : SuggestOutcome

    /// Write this, then drop the session.
    data class Insert(val acceptance: Acceptance) : SuggestOutcome

    /// Nothing to offer; the menu is over. The buffer is untouched.
    data object Dismissed : SuggestOutcome
}

class SuggestSession private constructor(
    /// Document offset of the line's first character, so every range this
    /// session reports is in the caller's coordinates.
    private val lineStart: Int,
    private val triggerInLine: Int,
    private val caretInLine: Int,
    /// Where the *current stage's* fragment begins. Stage two starts a
    /// fresh fragment, while [replaceRange] still reaches back to the
    /// trigger — so typing `du`, Enter, `tom` narrows on `tom` and
    /// replaces `/dutom`.
    private val fragmentStart: Int,
    private val fields: List<SuggestField>,
    private val filter: String,
    private val today: LocalDate,
    /// Stage one's fields, aligned one-to-one with [candidates]; empty in
    /// stage two.
    private val offered: List<SuggestField>,
    val stage: SuggestStage,
    /// The field chosen in stage one; null while stage one is up.
    val field: SuggestField?,
    /// What has been typed since this stage began.
    val fragment: String,
    val candidates: List<SuggestCandidate>,
    val selected: Int,
) {
    /// Document offset of the trigger `/`.
    val trigger: Int get() = lineStart + triggerInLine

    /// Document offset of the caret.
    val caret: Int get() = lineStart + caretInLine

    /// What accepting would replace: the `/` through everything typed
    /// since it, inclusive. Never empty — it always holds at least the
    /// `/` itself.
    val replaceRange: IntRange get() = trigger..(caret - 1)

    val selectedCandidate: SuggestCandidate? get() = candidates.getOrNull(selected)

    /// Re-read the line after a keystroke. Returns null when the session
    /// is over: the `/` was deleted or typed past, the caret left the
    /// fragment or the line, the line stopped being a task line, or
    /// nothing matches what was typed (§ 2's "an empty candidate set
    /// dismisses").
    ///
    /// The caller passes the line as it now stands rather than the key
    /// that changed, because that is what `onValueChange` hands it and
    /// because a paste, an undo and a click are then the same event.
    fun update(lineText: String, caretInLine: Int, today: LocalDate = this.today): SuggestSession? {
        if (caretInLine < fragmentStart || caretInLine > lineText.length) return null
        if (!triggered(lineText, triggerInLine + 1, filter)) return null
        val typed = lineText.substring(fragmentStart, caretInLine)
        if (typed.contains('\n')) return null
        val offers = when (stage) {
            SuggestStage.FIELDS -> narrowFields(fields, typed)
            SuggestStage.VALUES -> emptyList()
        }
        val rows = when (stage) {
            SuggestStage.FIELDS -> offers.map { it.row() }
            SuggestStage.VALUES -> field?.values?.invoke(typed, today).orEmpty()
        }
        if (rows.isEmpty()) return null
        return copy(
            caretInLine = caretInLine,
            today = today,
            offered = offers,
            fragment = typed,
            candidates = rows,
            // A changed list is a changed menu: the highlight goes back
            // to the top rather than to whatever now sits at the old
            // index.
            selected = 0,
        )
    }

    /// [update] over a whole document, for a caller that holds the
    /// buffer rather than the line. A caret that has moved to another
    /// line ends the session.
    fun updateAt(text: String, caret: Int, today: LocalDate = this.today): SuggestSession? {
        val bounded = caret.coerceIn(0, text.length)
        val start = lineStartFor(text, bounded)
        if (start != lineStart) return null
        return update(text.substring(start, lineEndFor(text, bounded)), bounded - start, today)
    }

    /// ↓. Wraps, so the last row's neighbour is the first.
    fun moveDown(): SuggestSession = copy(selected = (selected + 1) % candidates.size)

    /// ↑. Wraps the other way.
    fun moveUp(): SuggestSession = copy(selected = (selected + candidates.size - 1) % candidates.size)

    /// Enter. In stage one it passes the highlighted field through to its
    /// values — with one registered field that is § 2's single entry
    /// Enter passes through, which needs no special case because a
    /// one-row stage one already behaves that way. In stage two it
    /// returns the range and the text to write.
    fun accept(): SuggestOutcome {
        val candidate = selectedCandidate ?: return SuggestOutcome.Dismissed
        if (stage == SuggestStage.FIELDS) {
            val chosen = offered.getOrNull(selected) ?: return SuggestOutcome.Dismissed
            val rows = chosen.values("", today)
            if (rows.isEmpty()) return SuggestOutcome.Dismissed
            return SuggestOutcome.Open(
                copy(
                    fragmentStart = caretInLine,
                    offered = emptyList(),
                    stage = SuggestStage.VALUES,
                    field = chosen,
                    fragment = "",
                    candidates = rows,
                    selected = 0,
                ),
            )
        }
        // Nothing unresolvable is inserted: a half-typed date swallows
        // Enter and leaves the menu up.
        val token = candidate.insert ?: return SuggestOutcome.Open(this)
        return SuggestOutcome.Insert(Acceptance(replaceRange, "$token "))
    }

    private fun copy(
        caretInLine: Int = this.caretInLine,
        fragmentStart: Int = this.fragmentStart,
        today: LocalDate = this.today,
        offered: List<SuggestField> = this.offered,
        stage: SuggestStage = this.stage,
        field: SuggestField? = this.field,
        fragment: String = this.fragment,
        candidates: List<SuggestCandidate> = this.candidates,
        selected: Int = this.selected,
    ) = SuggestSession(
        lineStart = lineStart,
        triggerInLine = triggerInLine,
        caretInLine = caretInLine,
        fragmentStart = fragmentStart,
        fields = fields,
        filter = filter,
        today = today,
        offered = offered,
        stage = stage,
        field = field,
        fragment = fragment,
        candidates = candidates,
        selected = selected,
    )

    companion object {
        /// Opens a session iff § 2's trigger holds, which is all of:
        ///
        /// 1. the caret sits **immediately after** a `/`;
        /// 2. that `/` was typed at a **word boundary** — the character
        ///    before it is whitespace, or it opens the line — so `and/or`
        ///    is text;
        /// 3. the line is a **task line** (the same marker E3 renders as
        ///    a checkbox);
        /// 4. when the global filter is non-empty, the line **contains
        ///    it** (§ 1: the setting that decides which checkboxes are
        ///    tasks);
        /// 5. some field is on offer.
        ///
        /// `lineStart` is the document offset of the line, and every
        /// range the session reports is shifted by it. Pass 0 to work in
        /// line coordinates, or use [openAt] to have it computed.
        ///
        /// Fences are the caller's business: this machine sees one line
        /// and cannot know it sits inside a ``` block, where § 1 says a
        /// task line is not a task. The caller withholds the call there.
        fun open(
            lineText: String,
            caretInLine: Int,
            fields: List<SuggestField>,
            filter: String,
            today: LocalDate,
            lineStart: Int = 0,
        ): SuggestSession? {
            if (!triggered(lineText, caretInLine, filter)) return null
            val offers = narrowFields(fields, "")
            if (offers.isEmpty()) return null
            return SuggestSession(
                lineStart = lineStart,
                triggerInLine = caretInLine - 1,
                caretInLine = caretInLine,
                fragmentStart = caretInLine,
                fields = fields,
                filter = filter,
                today = today,
                offered = offers,
                stage = SuggestStage.FIELDS,
                field = null,
                fragment = "",
                candidates = offers.map { it.row() },
                selected = 0,
            )
        }

        /// [open] over a whole document: finds the caret's line, so the
        /// session's ranges are document offsets ready for
        /// [replaceRange].
        fun openAt(
            text: String,
            caret: Int,
            fields: List<SuggestField>,
            filter: String,
            today: LocalDate,
        ): SuggestSession? {
            val bounded = caret.coerceIn(0, text.length)
            val start = lineStartFor(text, bounded)
            val end = lineEndFor(text, bounded)
            return open(text.substring(start, end), bounded - start, fields, filter, today, start)
        }

        /// The trigger predicate alone, so a caller can ask the cheap
        /// question before building anything — and so the rule is
        /// testable on its own.
        fun triggered(lineText: String, caretInLine: Int, filter: String): Boolean {
            if (caretInLine < 1 || caretInLine > lineText.length) return false
            if (lineText[caretInLine - 1] != '/') return false
            val before = caretInLine - 2
            if (before >= 0 && !lineText[before].isWhitespace()) return false
            if (!SUGGEST_TASK_LINE.containsMatchIn(lineText)) return false
            return filter.isEmpty() || lineText.contains(filter)
        }
    }
}

/// Replaces exactly `range` (inclusive of both ends) with exactly
/// `replacement`, leaving the caret immediately after what was written.
///
/// The primitive ADR 0015 § 5 asks for beside `insertAtCaret`, which
/// **cannot express this**: it trims its input, inserts a separating
/// space when the character before the caret is not whitespace, and
/// writes at the caret rather than over a range. All three are right for
/// dictated words and wrong for a token that must land byte-for-byte
/// where the user's `/` was. Nothing here trims, pads or reflows.
fun replaceRange(value: TextFieldValue, range: IntRange, replacement: String): TextFieldValue {
    val text = value.text
    val from = range.first.coerceIn(0, text.length)
    val to = (range.last + 1).coerceIn(from, text.length)
    val written = text.substring(0, from) + replacement + text.substring(to)
    return TextFieldValue(written, TextRange(from + replacement.length))
}

/// The values provider id ADR 0015 § 3 names in a contribution's
/// `config`. A provider id rather than a lambda because a contribution is
/// data — strings the kernel never interprets — and the behaviour behind
/// the id is the shell's until the wasm contract can carry it (§ 7).
const val DATE_VALUES_PROVIDER = "org.gilune.dates"

/// Stage two for a date field: what the user typed, narrowed and
/// resolved, with `<key>:<ISO>` as the token to write.
fun dateValues(key: String): (String, LocalDate) -> List<SuggestCandidate> = { fragment, today ->
    dateCandidates(fragment, today).map { candidate ->
        SuggestCandidate(
            display = candidate.phrase,
            detail = candidate.iso ?: "",
            insert = candidate.iso?.let { "$key:$it" },
        )
    }
}

/// Builds a field from the three strings a `tasks.fields` contribution
/// carries. An unknown provider id yields null — a contribution this host
/// cannot serve offers nothing, rather than a row that does nothing.
fun suggestField(key: String, label: String, values: String): SuggestField? =
    when (values) {
        DATE_VALUES_PROVIDER -> SuggestField(key, label, dateValues(key))
        else -> null
    }

/// Stage one shows the field's label, with its key as the machine form
/// beside it — the key is what the line will carry, so it is worth
/// seeing.
private fun SuggestField.row(): SuggestCandidate = SuggestCandidate(label, key, null)

/// Prefix matches first, then substring matches, each in registration
/// order — the same rule stage two narrows by, so the two stages of one
/// menu do not behave differently. Label and key both match, because a
/// user who knows the stored form types it.
private fun narrowFields(fields: List<SuggestField>, fragment: String): List<SuggestField> {
    val typed = fragment.trim().lowercase(Locale.ROOT)
    if (typed.isEmpty()) return fields
    fun label(field: SuggestField) = field.label.lowercase(Locale.ROOT)
    fun key(field: SuggestField) = field.key.lowercase(Locale.ROOT)
    val prefix = fields.filter { label(it).startsWith(typed) || key(it).startsWith(typed) }
    val contains = fields.filter { it !in prefix && (label(it).contains(typed) || key(it).contains(typed)) }
    return prefix + contains
}

/// A task marker directly after a bullet, optionally indented — the same
/// shape `MarkdownEditing.kt` renders as a checkbox glyph, duplicated
/// because that one is file-private. If it is ever published, this
/// should become the import: one definition of a task line is the point
/// of ADR 0015 § 1.
private val SUGGEST_TASK_LINE = Regex("""^([ \t]*)([-*+])([ \t]+)(\[[ xX]])""")

private fun lineStartFor(text: String, offset: Int): Int {
    var index = offset
    while (index > 0 && text[index - 1] != '\n') index--
    return index
}

private fun lineEndFor(text: String, offset: Int): Int {
    var index = offset
    while (index < text.length && text[index] != '\n') index++
    return index
}
