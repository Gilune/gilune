package org.gilune.app

import org.gilune.plugin.sdk.PageCreationService
import uniffi.gilune_ffi.CreatePageRequest
import uniffi.gilune_ffi.Gilune

/** Composition supplies the owner once; plugins receive only the scoped service.
 * Call from the host's worker context, like other kernel operations. */
internal class BoundPageCreation(private val kernel: Gilune, private val pluginId: String) : PageCreationService {
    override fun definitions(): List<uniffi.gilune_ffi.PageDefinition> {
        // Specific configuration reads belong to form preparation, never search.
        kernel.pageDefinitions(pluginId).mapNotNull { it.defaultsPath }.distinct().forEach { kernel.reconcilePath(it) }
        return kernel.pageDefinitions(pluginId)
    }
    override fun create(request: CreatePageRequest) = kernel.createPage(pluginId, request)
    override fun initialize() = kernel.initializePages(pluginId)
}
