package org.gilune.plugin.sdk

import uniffi.gilune_ffi.LookupHit
import uniffi.gilune_ffi.LookupRequest

/** Pure query and presentation callbacks. The host executes the plan outside
 * native callbacks, on a worker. No plugin receives a Gilune/store/file handle.
 * Inputs and indexed values remain ordinary data, never a second identity store. */
interface IndexedLookupProvider {
    fun query(fragment: String): LookupRequest
    fun present(hit: LookupHit): LookupRow
}

/** Exact insertion is optional: incomplete/invalid definitions can still be
 * shown without being selectable. Source identity stays on the host result. */
data class LookupRow(val label: String, val detail: String, val insertion: String?)

class IndexedLookupRegistration(
    val id: String,
    val create: (settings: String) -> IndexedLookupProvider,
)

/** Echoed unchanged by the host. A consumer must discard a response when its
 * request, buffer revision, caret, fragment or provider is no longer current.
 * Offsets here are UTF-16; source spans on native hits remain UTF-8 bytes. */
data class LookupContext(
    val requestId: Long,
    val bufferVersion: String,
    val caret: Int,
    val providerId: String,
    val fragment: String,
)
