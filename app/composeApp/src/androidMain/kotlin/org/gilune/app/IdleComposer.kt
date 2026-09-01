// The one number that governs returning (ADR 0017 § 1, ratified).
//
// Drafts' "New Draft After" is the mechanism: one idle timer decides
// whether coming back resumes your half-written thought or starts a fresh
// one. Gilune inverts its ending — an expired composer holding text
// **commits it as a capture** and opens a fresh one. Nothing is ever
// discarded (constitution rule 1), and `Captures/` is already the inbox.
//
// Pure, so the boundaries are testable without a screen or a clock.

package org.gilune.app

/// How long the app may be away before a composer holding text commits
/// it. `minutes == null` is Never — the composer waits indefinitely.
data class IdleTimeout(val minutes: Int?) {
    /// The single integer DataStore holds. Never is negative rather than
    /// zero, because zero minutes is a real (if brisk) setting and must
    /// not collide with "don't".
    val stored: Int get() = minutes ?: NEVER_STORED

    val label: String get() = when (minutes) {
        null -> "Never"
        1 -> "1 minute"
        else -> "$minutes minutes"
    }

    companion object {
        const val NEVER_STORED = -1

        /// Five minutes (Ratification record, 1).
        val Default = IdleTimeout(5)
        val Never = IdleTimeout(null)

        /// What the settings screen offers. Never is last, and it is a
        /// real choice rather than a hidden one.
        val CHOICES: List<IdleTimeout> =
            listOf(IdleTimeout(1), IdleTimeout(2), Default, IdleTimeout(10), IdleTimeout(30), Never)

        fun fromStored(value: Int): IdleTimeout =
            if (value < 0) Never else IdleTimeout(value)
    }
}

/// What returning to the foreground should do with the text still in the
/// composer.
enum class ComposerReturn {
    /// Resume the half-written thought exactly as it was left.
    Keep,

    /// Commit it as a capture and open a fresh composer. Never a discard:
    /// the text becomes a note before the field is cleared.
    CommitAndClear,
}

/// The decision, made from three facts and nothing else.
///
/// The boundaries, stated rather than implied:
/// - **Blank keeps.** Whitespace is not a thought; there is nothing to
///   commit and nothing to lose, so an empty composer never fires.
/// - **Never keeps**, whatever the elapsed time.
/// - **Never having left keeps** — `backgroundedAtMs` is null until the
///   app is actually backgrounded.
/// - **Exactly the timeout expires.** Five minutes away with the timer at
///   five commits; the comparison is `>=`.
/// - **A backwards clock keeps.** If the wall clock moved back while the
///   app was away, elapsed reads negative and the draft is resumed. The
///   cost of guessing wrong in that direction is a composer that waits
///   too long; the other direction commits a thought someone was still
///   in the middle of.
fun composerReturn(
    draft: String,
    backgroundedAtMs: Long?,
    nowMs: Long,
    timeout: IdleTimeout,
): ComposerReturn {
    if (draft.isBlank()) return ComposerReturn.Keep
    if (backgroundedAtMs == null) return ComposerReturn.Keep
    val minutes = timeout.minutes ?: return ComposerReturn.Keep
    val elapsed = nowMs - backgroundedAtMs
    if (elapsed < 0) return ComposerReturn.Keep
    return if (elapsed >= minutes * 60_000L) ComposerReturn.CommitAndClear else ComposerReturn.Keep
}
