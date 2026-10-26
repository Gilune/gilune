package org.gilune.plugin.sdk

/** A page type's property sheet (owner, 2026-10-26: "we need a way to show
 * information on specific page types … making some of the data in that
 * section editable"). The shell draws it in the page's properties section —
 * the bar at the top of a note on the desktop, the Properties window on the
 * phone — above the raw front matter, which stays available as it was.
 *
 * Values are read from the page's own front matter, never the index, so the
 * sheet is right the moment the page opens; every edit is one property
 * change through the lossless front-matter edit the Properties window
 * already uses, so the page's Markdown stays the record. Declared under
 * [PAGE_PROPERTIES_SLOT] with the sheet's `id`, beside the page type it
 * describes, so it leaves with its plugin. Pure data: a sheet never sees a
 * file or the buffer. */
const val PAGE_PROPERTIES_SLOT = "pages.properties"

class PagePropertySheet(
    val id: String,
    /** The `gilune-type` this sheet describes. */
    val pageTypeId: String,
    /** What the section calls a page of this type, e.g. "People connection". */
    val title: String,
    val properties: List<PageProperty>,
)

/** One row of a sheet. `key` is the front-matter key it reads and writes. */
sealed interface PageProperty {
    val key: String
    val label: String

    /** Free text, written as a quoted string; committed on Enter or when the
     * field loses focus. Blank is refused when [required]. */
    data class Text(override val key: String, override val label: String, val required: Boolean = false) : PageProperty

    /** A boolean, written `true` or `false`. */
    data class Switch(override val key: String, override val label: String) : PageProperty

    /** One of named options; see [PageChoiceOption]. */
    data class Choice(override val key: String, override val label: String, val options: List<PageChoiceOption>) : PageProperty

    /** Shown, not edited here. With [renameReferences] the row offers the
     * page's "Rename references" tool — a value other notes name (a
     * keyword, a handle) must change everywhere at once, which is that
     * tool's job, not a one-line edit's. */
    data class ReadOnly(override val key: String, override val label: String, val renameReferences: Boolean = false) : PageProperty
}

/** One option of a [PageProperty.Choice]: the value written (null removes
 * the property), its name, and the tone the shell draws its swatch in. */
data class PageChoiceOption(val value: String?, val label: String, val tone: PillTone = PillTone.ACCENT)
