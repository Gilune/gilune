// What plugins give a vault to start with (owner, 2026-10-19): folders are
// declared by the plugins that use them (the vault's Notes and Captures,
// People's People — Tasks none), and the tasks plugin writes one Tasks
// page of live example lists, once. Against a real kernel where the claim
// is about the kernel; pure where the rule is.

package org.gilune.app

import org.gilune.plugin.sdk.PAGE_CREATE_CAPABILITY
import org.gilune.plugin.sdk.PAGE_INITIALIZERS_SLOT
import org.gilune.plugin.sdk.PAGE_TYPES_SLOT
import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.vaultFolderContribution
import org.gilune.plugins.tasks.TASKS_PAGE_FILE
import org.gilune.plugins.tasks.TASKS_PAGE_TEMPLATE
import org.gilune.plugins.tasks.TASKS_PLUGIN
import org.gilune.plugins.tasks.tasksDeclaration
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginStartingContentTest {
    private val roots = mutableListOf<File>()
    private val kernels = mutableListOf<Gilune>()

    @AfterTest
    fun cleanUp() {
        kernels.forEach { runCatching { it.close() } }
        roots.forEach { it.deleteRecursively() }
    }

    private fun vault(): Pair<File, Gilune> {
        val root = Files.createTempDirectory("gilune-start").toFile().also { roots += it }
        return root to Gilune.open(root.path).also { kernels += it }
    }

    // ---------------------------------------------------------------- folders

    @Test
    fun theVaultDeclaresNotesAndCapturesAndPeopleDeclaresPeopleAndTasksNothing() {
        val (_, kernel) = vault()
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        assertEquals(setOf("Notes", "Captures", "People"), declaredFolders(kernel.plugins()).toSet(), "no Tasks folder")
    }

    @Test
    fun aDisabledPluginsFoldersAndMalformedOnesAreNotDeclared() {
        val plugins = listOf(
            installedPlugin("a", contributions = listOf(vaultFolderContribution("a/f", "Alpha"))),
            installedPlugin("b", state = "disabled", contributions = listOf(vaultFolderContribution("b/f", "Beta"))),
            installedPlugin("c", contributions = listOf(
                vaultFolderContribution("c/1", "../Outside"),
                vaultFolderContribution("c/2", ""),
                vaultFolderContribution("c/3", "Alpha"),
                vaultFolderContribution("c/4", "Deep/Nested/"),
            )),
        )
        assertEquals(listOf("Alpha", "Deep/Nested"), declaredFolders(plugins))
    }

    @Test
    fun eachFolderIsMadeOnceAndADeletedOneStaysDeleted() {
        val root = Files.createTempDirectory("gilune-folders").toFile().also { roots += it }
        root.resolve("notes").mkdirs()
        val given = mutableSetOf<String>()
        fun create(folders: List<String>) = createDeclaredFolders(root.path, folders, { it in given }, { given += it })

        assertEquals(listOf("Captures"), create(listOf("Notes", "Captures")), "an existing folder in any casing stands")
        assertEquals(setOf("Notes", "Captures"), given, "and counts as given")

        root.resolve("Captures").delete()
        assertEquals(emptyList(), create(listOf("Notes", "Captures")), "deleted stays deleted")
        assertFalse(root.resolve("Captures").exists())

        // A plugin enabled later still gets its own.
        assertEquals(listOf("People"), create(listOf("Notes", "Captures", "People")))
        assertTrue(root.resolve("People").isDirectory)
    }

    // ------------------------------------------------------------ Tasks page

    @Test
    fun everyExampleListOnTheTasksPageRuns() {
        val types = searchTypesFor(listOf(installedTasksPlugin()))
        val blocks = fencedBlocks(TASKS_PAGE_TEMPLATE).filter { it.info.equals(FILTER_FENCE, ignoreCase = true) }
        assertEquals(5, blocks.size)
        for (block in blocks) {
            val classified = classifyFilterBlock(block.body, types)
            assertTrue(classified is FilterBlock.Runnable, "runs: ${block.body} → $classified")
        }
    }

    @Test
    fun theTasksPageIsWrittenAtTheRootOnceAndNotPutBackWhenDeleted() {
        val (root, kernel) = vault()
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        assertEquals(emptyList(), BUNDLED_PLUGIN_EXTENSIONS.initializePages(kernel))
        kernel.project()
        val page = root.resolve(TASKS_PAGE_FILE)
        assertTrue(page.isFile, "Tasks.md at the vault root")
        assertTrue(page.readText().contains("```filter"), "with its example lists")
        assertFalse(root.resolve("Tasks").exists(), "and no Tasks folder")

        page.delete()
        kernel.reconcile()
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        BUNDLED_PLUGIN_EXTENSIONS.initializePages(kernel)
        kernel.project()
        assertFalse(page.exists(), "deleted stays deleted")
    }

    @Test
    fun aVaultThatHadTheTasksPluginBeforeGetsThePageOnceAndARevokeIsKept() {
        val (root, kernel) = vault()
        // The plugin as it was: no page, no capability.
        val before = tasksDeclaration().let { d ->
            d.copy(capabilities = emptyList(),
                contributions = d.contributions.filter { it.slot != PAGE_TYPES_SLOT && it.slot != PAGE_INITIALIZERS_SLOT })
        }
        kernel.registerPlugin(before)
        assertFalse(PAGE_CREATE_CAPABILITY in kernel.plugins().single { it.pluginId == TASKS_PLUGIN }.granted)

        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        assertTrue(PAGE_CREATE_CAPABILITY in kernel.plugins().single { it.pluginId == TASKS_PLUGIN }.granted,
            "the grown declaration's new capability was never offered, so it is granted once")
        BUNDLED_PLUGIN_EXTENSIONS.initializePages(kernel)
        kernel.project()
        assertTrue(root.resolve(TASKS_PAGE_FILE).isFile)

        kernel.revokeCapability(TASKS_PLUGIN, PAGE_CREATE_CAPABILITY)
        BUNDLED_PLUGIN_EXTENSIONS.registerPlugins(kernel)
        assertFalse(PAGE_CREATE_CAPABILITY in kernel.plugins().single { it.pluginId == TASKS_PLUGIN }.granted,
            "a revoke is never undone by the next open")
    }

    @Test
    fun theVaultPluginsFolderDeclarationsAreInTheSlotTheShellReads() {
        val (_, kernel) = vault()
        val vault = kernel.plugins().single { it.pluginId == "org.memory.vault" }
        assertEquals(listOf("Notes", "Captures"), vault.contributions.filter { it.slot == Slots.VAULT_FOLDERS }.map { it.label })
    }
}
