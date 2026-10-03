// Local transcription on a device (ADR 0017 § 4, MB2/MB3).
//
// Two different kinds of test live here and the difference matters:
//
//  - The settings tests are ordinary UI tests. They assert that the model
//    picker is rendered from the shared catalogue and that the phone's
//    recommendation is the phone's, which is the visible half of the
//    platform-default decision.
//  - [whisperActuallyTranscribesOnThisDevice] is an **execution** test. It
//    is the first thing in this repo ever to ask the Android whisper build
//    to transcribe, and it is deliberately gated: it runs only when a
//    model has been seeded onto the device, and it says so rather than
//    passing quietly when it has not. A green run with no model would be a
//    test that proves nothing while looking like it proves everything.
//
// Seeding, from a machine with a cached model:
//   adb push %LOCALAPPDATA%\Gilune\models\ggml-tiny-q5_1.bin \
//     /data/local/tmp/gilune-models/ggml-tiny-q5_1.bin
// The test copies it into app-private storage itself, because that is
// where § 5 puts it and adb cannot write there.

package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptionUiTest {
    @get:Rule
    val rule = createDeviceComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun scratchVault(): String = Files.createTempDirectory("gilune-tx").toString()

    private fun openedState(): GiluneState {
        val state = GiluneState(scratchVault(), scope)
        state.openVault()
        return state
    }

    // ------------------------------------------------------------ settings

    @Test
    fun theTranscriptionScreenOffersTheSharedCatalogue() {
        val state = openedState()
        rule.waitUntil(WAIT) { state.plugins.isNotEmpty() }
        val transcriber = state.plugins.firstOrNull { it.pluginId == TRANSCRIPTION_PLUGIN }
        // If the kernel binary carries no transcriber it declares no
        // plugin, and there is nothing to render a picker for. Say so.
        assumeTrue(
            "the kernel binary declares no transcription plugin, so there is no picker to show",
            transcriber != null,
        )

        rule.setContent {
            GiluneTheme(dark = false) {
                var route by remember { mutableStateOf<SettingsRoute>(SettingsRoute.Plugin(TRANSCRIPTION_PLUGIN)) }
                SettingsScreen(
                    state = state,
                    route = route,
                    onNavigate = { route = it },
                    theme = ThemeChoice.System,
                    onThemeChange = {},
                    idleTimeout = IdleTimeout.Default,
                    onIdleTimeoutChange = {},
                    onExport = {},
                )
            }
        }

        rule.onNodeWithText("Transcription model").assertIsDisplayed()
        rule.onNodeWithText("Spoken language").assertExists()
        // Every catalogue entry is offered, by its own label. The picker
        // is data, not a hard-coded set — the same rule the plugin list
        // itself follows.
        for (model in WHISPER_MODELS) {
            rule.onNodeWithText(phoneModelLabel(model)).assertExists()
        }
    }

    @Test
    fun thePhonesRecommendationIsThePhonesNotTheDesktops() {
        val state = openedState()
        rule.waitUntil(WAIT) { state.plugins.isNotEmpty() }
        assumeTrue(state.plugins.any { it.pluginId == TRANSCRIPTION_PLUGIN })

        rule.setContent {
            GiluneTheme(dark = false) {
                SettingsScreen(
                    state = state,
                    route = SettingsRoute.Plugin(TRANSCRIPTION_PLUGIN),
                    onNavigate = {},
                    theme = ThemeChoice.System,
                    onThemeChange = {},
                    idleTimeout = IdleTimeout.Default,
                    onIdleTimeoutChange = {},
                    onExport = {},
                )
            }
        }
        rule.onNodeWithText("tiny-q5_1 — recommended on phones, 31 MiB").assertExists()
        // And the desktop's own word is not repeated here, which is the
        // whole point of a per-shell label.
        rule.onNodeWithText("base-q5_1 — recommended, 57 MiB").assertDoesNotExist()
    }

    @Test
    fun catchUpIsReachableFromThePluginManager() {
        // § 4's stranded-capture story: anything recorded before a model
        // arrived is recovered by a deliberate action, not by a background
        // sweep.
        val state = openedState()
        rule.waitUntil(WAIT) { state.plugins.isNotEmpty() }
        rule.setContent {
            GiluneTheme(dark = false) {
                SettingsScreen(
                    state = state,
                    route = SettingsRoute.Plugins,
                    onNavigate = {},
                    theme = ThemeChoice.System,
                    onThemeChange = {},
                    idleTimeout = IdleTimeout.Default,
                    onIdleTimeoutChange = {},
                    onExport = {},
                )
            }
        }
        rule.onNodeWithText("Catch up missed captures").assertExists()
        rule.onNodeWithText("Catch up missed captures").performClick()
        rule.waitUntil(WAIT) { state.message.value.isNotBlank() }
    }

    @Test
    fun theEditorDrawsNoMicWhileNothingCouldTranscribe() {
        // ADR 0017 § 3's line, on a real composition: a note opened with
        // no loaded model has no dictation button at all — not a disabled
        // one.
        val state = openedState()
        rule.waitUntil(WAIT) { state.gilune.value != null }
        val note = File(state.vaultDir, "note.md")
        note.parentFile?.mkdirs()
        note.writeText("---\ngilune-id: abc\n---\nhello\n")

        rule.setContent {
            GiluneTheme(dark = false) {
                EditScreen(state = state, relPath = "note.md", transcription = TranscriptionRuntime())
            }
        }
        rule.onNodeWithTag("note-body").assertExists()
        rule.onNodeWithTag("dictate").assertDoesNotExist()
        rule.onNodeWithTag("note-actions").assertDoesNotExist()
    }

    // ----------------------------------------------------------- execution

    /// **The first execution of whisper on Android in this repository.**
    ///
    /// It proves the pipeline end to end on the device's own CPU: a WAV
    /// goes in through the ordinary capture FFI, the kernel's job loop
    /// picks it up, the processor in the `.so` runs, and a transcript
    /// object comes back out. Silence is deliberate as the input — an
    /// empty or blank transcript still proves every link in that chain,
    /// and it costs a fraction of the wall time a spoken clip would.
    ///
    /// What it does **not** prove is accuracy or speed on real hardware.
    /// This is an x86_64 emulator; ADR 0017's MS2 is the ARM64 measurement
    /// and it has not run.
    @Test
    fun whisperActuallyTranscribesOnThisDevice() {
        val seed = File(SEED_DIR, androidWhisperModel(null).fileName)
        assumeTrue(
            "no model seeded at ${seed.absolutePath} — push one to run this (see this file's header)",
            seed.isFile,
        )
        val model = modelFile(context, androidWhisperModel(null))
        model.parentFile?.mkdirs()
        if (!model.isFile) seed.copyTo(model, overwrite = true)
        assertTrue(model.length() > 1_000_000, "the seeded model is not a model")

        val vault = scratchVault()
        Gilune.open(vault).use { gilune ->
            val plugins = gilune.plugins()
            assumeTrue(
                "this kernel binary carries no transcriber",
                plugins.any { it.pluginId == TRANSCRIPTION_PLUGIN },
            )
            gilune.enableWhisper(model.absolutePath)

            // Two seconds of silence in the canonical capture shape.
            val wav = wavBytes(RecordedAudio(ByteArray(16_000 * 2 * 2), 16_000, 1, 16))
            val writer = gilune.beginCapture("wav")
            var offset = 0
            while (offset < wav.size) {
                val end = minOf(offset + CAPTURE_CHUNK, wav.size)
                writer.writeChunk(wav.copyOfRange(offset, end))
                offset = end
            }
            val receipt = writer.finish()

            val started = System.currentTimeMillis()
            val processed = gilune.runProcessing()
            val elapsed = System.currentTimeMillis() - started
            gilune.project()

            val text = gilune.objectText(receipt.sourceId)
            android.util.Log.i(
                "GiluneWhisper",
                "whisper on Android: 2s of silence in ${elapsed}ms — " +
                    "completed=${processed.completed} outputs=${processed.outputs} " +
                    "failed=${processed.failed} skipped=${processed.skippedDuplicate} " +
                    "text=${text?.let { "\"$it\"" } ?: "none"}",
            )

            // What this may and may not assert. The job **running** is the
            // claim: `completed` counts a processor that was found, given
            // the blob and returned Ok. `outputs` is not asserted, because
            // silence is a legitimate empty transcript and a processor
            // that proposes nothing for it is behaving correctly — pinning
            // it to 1 would make this test a claim about whisper's opinion
            // of silence rather than about the pipeline.
            assertEquals(0L, processed.failed, "the transcription job failed on this device")
            assertEquals(
                1L,
                processed.completed,
                "the job did not run at all — a handle with no processor returns an all-zero " +
                    "summary, which is what this asserts against",
            )
        }
    }

    private companion object {
        const val WAIT = 15_000L

        /// Where a seeded model is looked for. `adb push` can write here;
        /// it cannot write app-private storage, so the test copies.
        val SEED_DIR = File("/data/local/tmp/gilune-models")
    }
}
