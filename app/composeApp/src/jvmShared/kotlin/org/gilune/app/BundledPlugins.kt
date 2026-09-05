package org.gilune.app

import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo

/** Application composition root: this is where the build chooses which
 * domain implementations it ships. Resolvers and renderers do not choose. */
val BUNDLED_PLUGIN_EXTENSIONS = PluginExtensions(listOf(tasksExtensions()))

fun searchTypesFor(plugins: List<PluginInfo>): List<SearchType> =
    BUNDLED_PLUGIN_EXTENSIONS.searchTypes(plugins)

fun registerShellIndexers(gilune: Gilune) = BUNDLED_PLUGIN_EXTENSIONS.registerIndexers(gilune)
