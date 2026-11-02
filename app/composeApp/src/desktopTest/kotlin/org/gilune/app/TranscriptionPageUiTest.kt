package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import uniffi.gilune_ffi.Gilune
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// Settings ▸ Transcription (ADR 0033 § 6) over a real kernel: engines from
/// two plugins, one of them switched off, and a Recordings choice that
/// moves the job routing.
@OptIn(ExperimentalTestApi::class)
class TranscriptionPageUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(run: (GiluneState, Gilune) -> Unit) {
        val root = Files.createTempDirectory("gilune-transcription-page").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Gilune.open(root.path).use { kernel ->
            kernel.registerPlugin(whisperStandInDeclaration())
            registerNemoOnHandle(kernel)
            val state = GiluneState(root.path, scope).apply {
                gilune.value = kernel
                plugins.addAll(kernel.plugins())
            }
            try {
                compose.setContent {
                    GiluneTheme(dark = false) {
                        // Tall enough that every row is on screen: a click
                        // on an off-screen node lands nowhere.
                        Box(Modifier.requiredSize(1000.dp, 3200.dp)) {
                            SettingsPane(state, state.vaultDir, {}, ThemeChoice.Light, {}, {}, initialSection = "transcription")
                        }
                    }
                }
                compose.waitForIdle()
                run(state, kernel)
            } finally {
                compose.setContent {}
                scope.cancel()
                state.gilune.value = null
            }
        }
        root.deleteRecursively()
    }

    private fun tag(role: String, pluginId: String, engine: String) = "engine-$role-$pluginId-$engine"

    @Test
    fun thePageListsEnginesFromBothPluginsAndChoosingRecordingsMovesTheJobs() = fixture { state, kernel ->
        // Whisper's rows in both roles, chosen by carry-over.
        compose.onNodeWithTag(tag("recordings", TRANSCRIPTION_PLUGIN, DEFAULT_WHISPER_MODEL)).assertIsSelected()
        compose.onNodeWithTag(tag("live", TRANSCRIPTION_PLUGIN, DEFAULT_WHISPER_MODEL)).assertIsSelected()
        // NeMo is switched off and still offered, and a whole-recording
        // model has no row while you speak.
        compose.onNodeWithTag(tag("recordings", NEMO_PLUGIN, "parakeet-tdt-0.6b-v2") + "-state", useUnmergedTree = true)
            .assertTextEquals("Switched off")
        compose.onNodeWithTag(tag("live", NEMO_PLUGIN, "nemotron-0.6b")).assertExists()
        assertEquals(
            0,
            compose.onAllNodesWithTag(tag("live", NEMO_PLUGIN, "parakeet-tdt-0.6b-v2"), useUnmergedTree = true).fetchSemanticsNodes().size,
        )

        compose.onNodeWithTag(tag("recordings", NEMO_PLUGIN, "parakeet-tdt-0.6b-v2")).performClick()
        compose.waitUntil(10_000) {
            state.plugins.any { it.pluginId == NEMO_PLUGIN && it.state == "enabled" && it.takesJobs }
        }

        val plugins = kernel.plugins()
        assertTrue(plugins.first { it.pluginId == NEMO_PLUGIN }.takesJobs)
        assertFalse(plugins.first { it.pluginId == TRANSCRIPTION_PLUGIN }.takesJobs, "the old provider takes none")
        assertEquals(EngineChoice(NEMO_PLUGIN, "parakeet-tdt-0.6b-v2"), plugins.recordingsChoice())
        // Its model is not on this computer, so the choice also led to the
        // plugin's own screen, where the download lives.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("nemo-model-parakeet-tdt-0.6b-v2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                nemoModelPresent(nemoModel("parakeet-tdt-0.6b-v2"))
        }
    }
}
