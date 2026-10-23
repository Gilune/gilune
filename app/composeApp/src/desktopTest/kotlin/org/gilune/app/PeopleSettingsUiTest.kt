package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.gilune.plugins.people.*
import org.junit.Rule
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/// The People plugin's settings screen names where new people and new
/// connections go (owner ask, 2026-10-23): the page defaults that decide
/// it were reachable only from the rail panels, and the settings screen —
/// where a person looks for a location — said nothing.
@OptIn(ExperimentalTestApi::class)
class PeopleSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(run: (File, GiluneState, Gilune) -> Unit) {
        val root = Files.createTempDirectory("gilune-people-settings").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Gilune.open(root.path).use { kernel ->
            val registry = PluginExtensions(listOf(peopleExtensions()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            registry.initializePages(kernel); kernel.project(); kernel.advanceIndex()
            val state = GiluneState(root.path, scope).apply {
                gilune.value = kernel; plugins.addAll(kernel.plugins())
            }
            try { run(root, state, kernel) }
            finally { compose.setContent {}; scope.cancel(); state.gilune.value = null }
        }
        root.deleteRecursively()
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun thePeopleScreenNamesBothFoldersAndOffersToChangeEach() = fixture { _, state, _ ->
        var opened: String? = null
        // Tall enough that the section is on screen: scrolling this pane in
        // a test never settles (the UI thread spins), and a click on an
        // off-screen node lands nowhere.
        compose.setContent {
            GiluneTheme(dark = false) {
                Box(Modifier.requiredSize(1000.dp, 2600.dp)) {
                    SettingsPane(state, state.vaultDir, {}, ThemeChoice.Light, {}, {},
                        initialSection = "plugin:$PEOPLE_PLUGIN", onOpenNote = { opened = it })
                }
            }
        }
        awaitText("Where pages go")
        // The folders come from the page definitions, read off the UI
        // thread, so each row fills in once they are known.
        awaitText("People/Connections")
        compose.onNodeWithTag("page-folder:$PEOPLE_PANEL", useUnmergedTree = true)
            .assertExists().onChildren().assertAny(hasText("People"))
        compose.onNodeWithTag("page-folder:$CONNECTIONS_PANEL", useUnmergedTree = true)
            .assertExists().onChildren().assertAny(hasText("People/Connections"))
        assertEquals(2, compose.onAllNodesWithText("Change…").fetchSemanticsNodes().size, "one way to change each")
        // Change… is the rail panel's Page defaults door: the dialog when
        // no defaults page exists yet, else that page in the editor. This
        // fixture ran the page initialiser, which writes the defaults
        // pages, so the connections row leads to its page.
        compose.onAllNodesWithText("Change…")[1].performClick()
        compose.waitUntil(10_000) {
            opened != null || compose.onAllNodesWithText("Save defaults").fetchSemanticsNodes().isNotEmpty()
        }
        opened?.let { assertTrue(it.startsWith("Settings/Page defaults/"), it) }
            ?: compose.onNodeWithTag("defaults-folder").assertExists()
    }
}
