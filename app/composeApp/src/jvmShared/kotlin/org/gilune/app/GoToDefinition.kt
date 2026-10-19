// Go to definition (owner, 2026-10-19): with the caret on a reference — a
// person's @mention — one keystroke opens the page it names. The plugin
// says what the caret is on and owns the keystroke; the host runs the
// plugin's lookup and opens the answer, so the shell still knows no
// reference syntax (ADR 0011 amendment).

package org.gilune.app

import org.gilune.plugin.sdk.DefinitionRegistration
import org.gilune.plugin.sdk.DocumentReference
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.inspectSourceText

/** A registration bound to its enabled owner, with the keystroke the
 * owner's settings ask for, spelled the host's way (`F12`, `Ctrl+Alt+G`). */
internal data class BoundDefinition(val pluginId: String, val key: String, val registration: DefinitionRegistration)

/** What one go-to-definition asked for came to. */
internal sealed interface DefinitionResult {
    data class Open(val relPath: String) : DefinitionResult

    /** Said in a line; nothing opens. */
    data class Say(val words: String) : DefinitionResult
}

/** The reference under `offset` in `document`, asked of each registration
 * bound to the pressed keystroke in turn; the first that names one wins. */
internal fun definitionAt(document: String, offset: Int, bindings: List<BoundDefinition>): DocumentReference? {
    if (bindings.isEmpty()) return null
    val snapshot = inspectSourceText(document)
    return bindings.firstNotNullOfOrNull { runCatching { it.registration.definitionAt(snapshot, offset) }.getOrNull() }
}

/** Runs the reference's lookup: exactly one page opens; none, several or a
 * pending index is said rather than guessed at. Blocking — call off the UI
 * thread. */
internal fun resolveDefinition(kernel: Gilune, reference: DocumentReference): DefinitionResult {
    val page = runCatching { kernel.lookup(reference.query.copy(cursor = null, pageSize = 2u)) }
        .getOrElse { return DefinitionResult.Say("could not look up ${reference.label}: ${it.message}") }
    return when {
        !page.indexComplete -> DefinitionResult.Say("still indexing — try ${reference.label} again in a moment")
        page.hits.size == 1 -> DefinitionResult.Open(page.hits.single().relPath)
        page.hits.isEmpty() -> DefinitionResult.Say("no page for ${reference.label} yet")
        else -> DefinitionResult.Say("more than one page claims ${reference.label}")
    }
}
