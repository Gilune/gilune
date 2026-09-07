package org.gilune.plugin.sdk

import java.time.LocalDate
import uniffi.gilune_ffi.LookupHit
import uniffi.gilune_ffi.LookupRequest

/** Immutable editor input. Coordinates are UTF-16, exclusive at the end.
 * A continuation belongs to one provider and never grants access to a buffer. */
data class EditorContext(
    val text: String,
    val caret: Int,
    val today: LocalDate,
    val continuation: EditorContinuation? = null,
    val toolbar: Boolean = false,
    val fields: List<SuggestField> = emptyList(),
    val documentKey: String = "",
)

data class EditorContinuation(val start: Int, val fragmentStart: Int, val data: String, val triggerText: String = "/")

/** Exactly one action: insert literal text, advance a menu without editing,
 * or open the standard creation form with explicit initial values. */
data class EditorOption(
    val label: String,
    val detail: String = "",
    val insertion: String? = null,
    val continueWith: String? = null,
    val create: EditorCreate? = null,
    val unique: LookupRequest? = null,
    val requiredDefinitions: List<LookupRequest> = emptyList(),
)
data class EditorCreate(val collectionId: String, val values: Map<String, String>,
    val complete: ((uniffi.gilune_ffi.SourceTextSnapshot) -> EditorOption)? = null)

/** A pure proposal. The host executes indexed lookup on a worker, owns paging,
 * and validates the complete input again before accepting an option. */
class EditorCompletion(
    val start: Int,
    val end: Int,
    val options: List<EditorOption> = emptyList(),
    val lookup: LookupRequest? = null,
    val present: (LookupHit) -> EditorOption = { EditorOption(it.relPath) },
)

class EditorCompletionRegistration(
    val id: String,
    val fieldSlot: String? = null,
    val triggers: Set<Char> = setOf('/'),
    val complete: (EditorContext, String) -> EditorCompletion?,
)

/** Trigger discovery only; syntax eligibility remains with the contributor.
 * A trigger inside a word, an escape, or a non-collapsed range is never inferred. */
fun editorTrigger(context: EditorContext, marker: Char): Int? {
    if (context.caret !in 0..context.text.length) return null
    var start = context.caret
    while (start > 0 && !context.text[start - 1].isWhitespace()) start--
    if (start >= context.caret || context.text[start] != marker) return null
    return start
}
