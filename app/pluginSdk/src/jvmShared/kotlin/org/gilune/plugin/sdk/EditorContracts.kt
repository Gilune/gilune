package org.gilune.plugin.sdk


import java.time.LocalDate

/// The palette role a decorated span takes. A producer names a role, never
/// a colour, so the theme stays the shell's. [NONE] adds no span at all —
/// the checkbox glyph, which the core styling rules dress like any other
/// character.
enum class DecorationRole { NONE, MUTED, MARKER, LINK, CODE }

/// What one recognised match renders as. [Replace] swaps those characters
/// for `text` and the lengths may differ — the shell's offset table carries
/// the difference. [StyleOnly] is the zero-delta case: the bytes render as
/// themselves and only the role's span is added, which is all a `due:` token
/// needs (ADR 0015 § 1).
sealed interface DecorationRender {
    data class Replace(val text: String) : DecorationRender

    data object StyleOnly : DecorationRender
}

/// One match a recogniser claims: a half-open range *within its line*, and
/// how it renders. A range outside the line, or an empty one, is discarded
/// by the shell rather than trusted.
data class DecorationMatch(val range: IntRange, val render: DecorationRender)

/// A registration into the `editor.decorations` seam (ADR 0015 § 5).
///
/// `recognise` is asked one line at a time and returns the ranges it claims;
/// `role` styles every one of them; `activate` — when present — rewrites the
/// whole line the user activated and makes the registration clickable. All
/// three are pure over the string: a registration never sees the buffer, and
/// nothing it returns can change a byte except through an activation the
/// user performs.
///
/// `firesInsideCodeFences` is off by default, so task metadata never becomes
/// somebody's agenda because it was quoted in an example (§ 1). The checkbox
/// slice opts in, because presentation of a GFM element is not metadata and
/// that is what the shell already did.
class EditorDecoration(
    val id: String,
    val recognise: (line: String) -> List<DecorationMatch>,
    val role: DecorationRole = DecorationRole.NONE,
    val firesInsideCodeFences: Boolean = false,
    val activate: ((line: String) -> String)? = null,
)

/// One offerable row. `display` is the words, `detail` the machine form
/// shown alongside — the resolved ISO date in stage two, the field key in
/// stage one — and `insert` the exact token to write.
///
/// `insert` is null for a row that cannot be accepted yet: a date still
/// being typed keeps the menu alive without being insertable. Enter on such
/// a row writes nothing.
data class SuggestCandidate(val display: String, val detail: String, val insert: String?)

/** A field and its pure value provider. The host resolves the contributed
 * provider identifier; the provider returns exact insertion text. */
class SuggestField(
    val key: String,
    val label: String,
    val values: (fragment: String, today: LocalDate) -> List<SuggestCandidate>,
)

