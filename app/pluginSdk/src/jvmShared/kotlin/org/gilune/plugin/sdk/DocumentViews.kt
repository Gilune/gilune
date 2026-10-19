package org.gilune.plugin.sdk

import uniffi.gilune_ffi.LocationQuery
import uniffi.gilune_ffi.LookupRequest
import uniffi.gilune_ffi.SourceTextSnapshot

/** Domain-neutral detail shapes. Queries carry opaque facts; the host owns
 * execution, source navigation, pagination and lifecycle invalidation. */
/** `written` is where the reference stands in the described text, as
 * character ranges of the snapshot — what a reading surface makes
 * clickable, so the host can open a reference without knowing its syntax.
 * Empty means the reference is listed but not drawn as a link. */
data class DocumentReference(val label: String, val query: LookupRequest, val create: EditorCreate? = null,
    val written: List<IntRange> = emptyList())
data class DocumentSection(val label: String, val query: LocationQuery)
data class DocumentView(val title: String, val references: List<DocumentReference>, val sections: List<DocumentSection>)
class DocumentViewRegistration(val id: String, val describe: (SourceTextSnapshot) -> DocumentView?)
