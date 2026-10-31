package org.gilune.app

import org.gilune.plugin.sdk.SearchType
import org.gilune.plugin.sdk.SuggestField
import org.gilune.plugins.tasks.TASK_FIELDS_SLOT
import org.gilune.plugins.tasks.tasksExtensions
import org.gilune.plugins.people.peopleExtensions
import org.gilune.plugins.findreplace.findReplaceExtensions

import java.time.LocalDate
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo

/** Application composition root: this is where the build chooses which
 * domain implementations it ships. Resolvers and renderers do not choose. */
val BUNDLED_PLUGIN_EXTENSIONS = PluginExtensions(listOf(tasksExtensions(), peopleExtensions(), findReplaceExtensions()))

fun searchTypesFor(plugins: List<PluginInfo>): List<SearchType> =
    BUNDLED_PLUGIN_EXTENSIONS.searchTypes(plugins)

fun registerShellIndexers(gilune: Gilune) = BUNDLED_PLUGIN_EXTENSIONS.registerIndexers(gilune)

fun searchOperatorsFor(plugins: List<PluginInfo>, today: LocalDate): SearchOperators =
    BUNDLED_PLUGIN_EXTENSIONS.searchOperators(plugins, today)

/** Tasks publishes this field slot; provider lookup and lifecycle are host contracts. */
fun taskFields(plugins: List<PluginInfo>): List<SuggestField> =
    BUNDLED_PLUGIN_EXTENSIONS.fields(TASK_FIELDS_SLOT, plugins)
