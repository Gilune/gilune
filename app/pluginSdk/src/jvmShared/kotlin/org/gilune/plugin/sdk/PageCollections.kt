package org.gilune.plugin.sdk

import uniffi.gilune_ffi.SearchProperty

const val PAGE_COLLECTIONS_SLOT = "pages.collections"
data class PageField(val key: String, val label: String, val initial: String = "", val hint: String = "")
data class PagePreset(val label: String, val values: Map<String, String>)
data class PageDraft(val relativeFile: String, val source: String, val identityValue: String? = null)
data class PageRow(val title: String, val subtitle: String = "", val problem: String? = null)

/** Pure domain definitions, rendered with standard forms/lists by either shell.
 * Creation callback receives form values and the effective source template and
 * emits editable text; it cannot write. */
class PageCollectionRegistration(
    val id: String,
    val pageTypeId: String,
    val searchType: String,
    val title: String,
    val createLabel: String,
    val fields: List<PageField>,
    val prepare: (Map<String, String>, String) -> PageDraft,
    val row: (List<SearchProperty>, String) -> PageRow,
    val presets: List<PagePreset> = emptyList(),
    val identityPredicate: String? = null,
    val lookupProviderId: String? = null,
    /** Optional desktop folder-menu action, shown on this type's effective
     * default folder. The host uses the same creation form and capability. */
    val folderCreateLabel: String? = null,
)
