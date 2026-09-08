package org.gilune.app

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gilune.plugin.sdk.PAGE_CREATE_CAPABILITY

internal data class FolderPageCollection(val binding: BoundPageCollection, val folder: String)

/** Resolve contributed folder actions off the UI thread. Portable page defaults
 * remain the single source of truth; hiding a navigation tab does not disable
 * its collection, while revoking the plugin's capability removes the action. */
@Composable
internal fun rememberFolderPageCollections(state: GiluneState): List<FolderPageCollection> {
    val kernel = state.gilune.value
    val plugins = state.plugins.toList()
    val keys = arrayOf(kernel, plugins, state.vaultCursor.value, state.searchRefresh.value)
    var folders by remember(*keys) { mutableStateOf(emptyList<FolderPageCollection>()) }
    LaunchedEffect(*keys) {
        if (kernel == null) return@LaunchedEffect
        try {
            folders = withContext(Dispatchers.IO) {
                BUNDLED_PLUGIN_EXTENSIONS.pageCollections(plugins)
                    .filter { it.collection.folderCreateLabel != null && plugins.any { owner ->
                        owner.pluginId == it.pluginId && PAGE_CREATE_CAPABILITY in owner.granted
                    } }
                    .groupBy { it.pluginId }.flatMap { (owner, collections) ->
                        val definitions = BoundPageCreation(kernel, owner).definitions()
                        collections.mapNotNull { binding ->
                            definitions.singleOrNull { it.typeId == binding.collection.pageTypeId && it.defaultsProblem == null }
                                ?.let { FolderPageCollection(binding, it.defaultFolder.replace('\\', '/')) }
                        }
                    }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { GiluneLog.warn("folder creation actions unavailable", error) }
    }
    return folders
}
