// The live caption's state (ADR 0018 § 1), as R0 measured the engine
// rather than as a naive reading of its API would guess.
//
// Everything in this file is pure. No Moonshine type is named here and
// nothing here touches a model, a thread or a microphone — `Moonshine.kt`
// owns the engine and maps its callbacks onto [CaptionEvent], and
// `CaptureScreen.kt` renders [CaptionDisplay]. That split is what lets the
// semantics below be tested without a 45 MB download.
//
// Shared since ADR 0027 S0: the desktop's live dictation folds whisper's
// streaming lines through the same rules, because "a settled line never
// changes again" is exactly when dictated words may enter the buffer.
//
// The five findings this fold exists to honour, all from
// `spikes/r0-moonshine/README.md` § Question 5:
//
//  1. **`LineStarted` and `LineTextChanged` fire together**, same instant,
//     same text. So a start is not a distinct state — it is an upsert.
//  2. **`LineUpdated` co-fires with `LineTextChanged`** on every revision.
//     The pair is redundant, so both must fold idempotently.
//  3. **The last `LineTextChanged` before `LineCompleted` already carries
//     `isComplete = true` and the final text.** `LineCompleted` adds no
//     new text. So completion is read off the FLAG, not off the event
//     name, and both paths settle the same line to the same bytes.
//  4. **`LineCompleted` really does freeze** — asserted for every line in
//     R0's `a07`. A settled line is therefore immutable here too, defensively:
//     the caption's settled role promises words that will not move, and
//     that promise is ours to keep even if the engine ever breaks its own.
//  5. **Silence produces nothing at all** — zero events for five seconds
//     of digital silence. So an empty caption needs no special case: no
//     events means no lines, and blank text never opens a line.
//
// And the finding that shapes the *rendering*: partials rewrite
// **mid-phrase**, not just at the tail ("lace of the" → "lazy dog."). The
// whole unsettled region is therefore volatile and is drawn in the muted
// role as one run — there is no stable prefix to promote.

package org.gilune.app

import uniffi.gilune_ffi.PluginInfo

/// One transcriber callback, flattened to the four fields the caption
/// needs. Provider-neutral on purpose: the fold below is the caption's
/// law, and a second engine (or a test) reaches it without either side
/// naming the other.
data class CaptionEvent(
    val kind: String,
    val lineId: Long,
    val text: String,
    val complete: Boolean,
) {
    companion object {
        const val STARTED = "LineStarted"
        const val TEXT_CHANGED = "LineTextChanged"
        const val UPDATED = "LineUpdated"
        const val COMPLETED = "LineCompleted"
        const val ERROR = "Error"
    }
}

/// A line of caption. [settled] means `LineCompleted` (or the completion
/// flag that precedes it) has landed: the words are final and render in
/// the settled role.
data class CaptionLine(val id: Long, val text: String, val settled: Boolean)

/// How many lines are kept at all. The display shows [CAPTION_LINES] of
/// them; a couple more are retained so that a line completing while
/// another is in flight does not pop the block's height about.
const val CAPTION_RETAINED = 6

/// "Capped at about three lines, older lines scrolling off" (§ 1).
const val CAPTION_LINES = 3

/// Fold one event into the caption. Total and order-independent within a
/// line: replaying the same event twice is a no-op, which is what makes
/// the redundant `LineUpdated`/`LineTextChanged` pair harmless.
///
/// [retain] is how many lines survive the fold, and it is a parameter
/// rather than a constant because the **same five findings** govern a
/// committed transcript, where dropping anything would be a data loss
/// dressed as a display cap. The caption keeps six; the provider's
/// collector keeps everything (`Int.MAX_VALUE`). Until this round the
/// provider had a second, hand-written copy of this fold — a
/// `LinkedHashMap` obeying the same rules — and two copies of a fold is one
/// copy that quietly drifts from the spike that measured it.
fun foldCaption(
    lines: List<CaptionLine>,
    event: CaptionEvent,
    retain: Int = CAPTION_RETAINED,
): List<CaptionLine> {
    // An engine error says nothing about the words already on screen. The
    // session kills the caption on error (`Moonshine.kt`); the fold's job
    // is only to not corrupt what is showing on the way out.
    if (event.kind == CaptionEvent.ERROR) return lines

    val existing = lines.firstOrNull { it.id == event.lineId }
    // Finding 4: a settled line never changes again, whatever arrives.
    if (existing != null && existing.settled) return lines

    // Findings 3 and 5: completion is the flag OR the event, and blank
    // text never opens a line — silence must leave the caption empty
    // rather than showing an empty settled row.
    val settled = event.complete || event.kind == CaptionEvent.COMPLETED
    if (existing == null && event.text.isBlank()) return lines

    val line = CaptionLine(event.lineId, event.text, settled)
    val next = if (existing == null) lines + line else lines.map { if (it.id == line.id) line else it }
    return next.takeLast(retain)
}

/// What the caption block draws: frozen lines in the settled role, and
/// the entire revisable region as one muted run.
///
/// The revisable region is joined rather than listed because it is one
/// volatile thing — R0 showed the words inside it change mid-phrase, so
/// there is nothing in there worth giving a line of its own.
data class CaptionDisplay(val settled: List<String>, val live: String) {
    val isEmpty: Boolean get() = settled.isEmpty() && live.isBlank()

    companion object {
        val EMPTY = CaptionDisplay(emptyList(), "")
    }
}

/// The bottom-anchored view of the lines: at most [CAPTION_LINES] rows,
/// the live one always among them because it is the one being spoken.
fun captionDisplay(lines: List<CaptionLine>, maxLines: Int = CAPTION_LINES): CaptionDisplay {
    val live = lines.filter { !it.settled }.joinToString(" ") { it.text.trim() }.trim()
    val settled = lines.filter { it.settled }.map { it.text.trim() }.filter { it.isNotEmpty() }
    // The live run occupies one of the rows, so the frozen ones take
    // what is left — which is what makes older lines scroll off the top
    // as speech continues.
    val room = if (live.isEmpty()) maxLines else maxLines - 1
    return CaptionDisplay(settled.takeLast(room.coerceAtLeast(0)), live)
}

/// The whole caption state for one recording. Plain state plus the fold;
/// the shell wraps it in Compose state (see `LiveCaption` in
/// `Moonshine.kt`).
class CaptionBuffer {
    var lines: List<CaptionLine> = emptyList()
        private set

    fun accept(event: CaptionEvent): CaptionDisplay {
        lines = foldCaption(lines, event)
        return captionDisplay(lines)
    }

    fun clear() {
        lines = emptyList()
    }
}

/// **The gate** (ADR 0018 § 1: "Gated: plugin enabled AND caption setting
/// AND model present — otherwise recording behaves exactly as today").
///
/// Three conditions and no fourth. In particular it does NOT consult the
/// network grant: consent gates the *download*, and a model already on
/// disk captions whether or not permission was later revoked — revoking
/// network permission means "fetch nothing more", not "forget the file
/// you have".
fun captionOffered(plugins: List<PluginInfo>, modelPresent: Boolean): Boolean {
    val plugin = plugins.firstOrNull { it.pluginId == MOONSHINE_PLUGIN } ?: return false
    if (plugin.state != "enabled") return false
    return plugins.moonshineCaption() && modelPresent
}
