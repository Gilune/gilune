package org.gilune.plugin.sdk

import uniffi.gilune_ffi.LocationQuery
import uniffi.gilune_ffi.LookupRequest
import uniffi.gilune_ffi.SourceTextSnapshot

/** Domain-neutral detail shapes. Queries carry opaque facts; the host owns
 * execution, source navigation, pagination and lifecycle invalidation. */
data class DocumentReference(val label: String, val query: LookupRequest, val create: EditorCreate? = null)
data class DocumentSection(val label: String, val query: LocationQuery)
data class DocumentView(val title: String, val references: List<DocumentReference>, val sections: List<DocumentSection>)
class DocumentViewRegistration(val id: String, val describe: (SourceTextSnapshot) -> DocumentView?)
