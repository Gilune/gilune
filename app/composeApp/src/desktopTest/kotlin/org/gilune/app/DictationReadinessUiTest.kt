// Dictation is offered by the voice capture adapter and delivered by the
// transcription plugin. The editor's microphone must not start a dictation
// the transcriber cannot finish, and allowing the transcriber's download
// must take effect without a restart (owner report, 2026-10-21: the
// desktop had never downloaded a model, so every dictation ended as an
// attached capture nobody would see, and the editor said only "no words
// recognised").

package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.rules.RuleChain
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DictationReadinessUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    private class CountingRecorder : AudioRecorder {
        var starts = 0
        override fun start() { starts += 1 }
        override fun stop() = RecordedAudio(ByteArray(0), WAV_SAMPLE_RATE, WAV_CHANNELS, WAV_BITS_PER_SAMPLE)
    }

    private fun transcriber(granted: List<String>, state: String = "enabled") = PluginInfo(
        pluginId = TRANSCRIPTION_PLUGIN,
        name = "Transcription",
        `class` = "processor",
        profile = "bundled",
        required = false,
        state = state,
        processorId = "transcribe",
        subscribes = "source-ready",
        emits = emptyList(),
        capabilities = PROVIDER_LOCAL_CAPABILITIES + NETWORK_CAPABILITY,
        granted = granted,
        ungranted = (PROVIDER_LOCAL_CAPABILITIES + NETWORK_CAPABILITY) - granted.toSet(),
        contributions = emptyList(),
        settings = "",
    )

    private fun vault(): Pair<GiluneState, Gilune> {
        val root = resources.directory("gilune-dictation")
        val kernel = resources.own(Gilune.open(root.path))
        kernel.registerPlugin(voiceCaptureDeclaration())
        root.resolve("note.md").writeText("---\ngilune-id: note\n---\nhello")
        val state = GiluneState(root.path, resources.scope)
        state.gilune.value = kernel
        state.plugins.addAll(kernel.plugins().filter { it.pluginId != TRANSCRIPTION_PLUGIN })
        return state to kernel
    }

    private fun editorWith(runtime: TranscriptionRuntime): Pair<GiluneState, CountingRecorder> {
        val (state, _) = vault()
        val editor = EditorState()
        editor.open(state.vaultDir, "note.md")
        editor.reading = false
        val recorder = CountingRecorder()
        compose.setContent {
            GiluneTheme(dark = false) {
                CompositionLocalProvider(LocalTranscription provides runtime) {
                    Box(Modifier.size(width = 600.dp, height = 400.dp)) {
                        NoteEditor(state, editor, Modifier.size(width = 600.dp, height = 400.dp), recorder)
                    }
                }
            }
        }
        compose.waitForIdle()
        return state to recorder
    }

    private fun mic() = compose.onNodeWithContentDescription("dictate at the cursor", substring = true)

    @Test
    fun aDictationThatNothingCanTranscribeIsNeverStarted() {
        val runtime = TranscriptionRuntime().apply {
            phase = TranscriptionPhase.Absent
            needsConsent = true
        }
        val (state, recorder) = editorWith(runtime)

        mic().performClick()
        compose.waitForIdle()

        assertEquals(0, recorder.starts, "the microphone stays closed while no transcriber can run")
        val said = state.message.value
        assertTrue("Settings ▸ Plugins ▸ Transcription" in said, "the line says where to fix it: $said")
    }

    @Test
    fun aReadyTranscriberStillLetsTheMicrophoneOpen() {
        val runtime = TranscriptionRuntime().apply { phase = TranscriptionPhase.Ready }
        val (state, recorder) = editorWith(runtime)

        mic().performClick()
        compose.waitForIdle()

        assertEquals(1, recorder.starts)
        assertTrue(state.message.value.startsWith("dictating"), state.message.value)
    }

    @Test
    fun aDownloadStillInFlightIsNamedRatherThanCalledMissing() {
        val runtime = TranscriptionRuntime().apply {
            phase = TranscriptionPhase.Downloading
            percent = 40
        }
        val (state, recorder) = editorWith(runtime)

        mic().performClick()
        compose.waitForIdle()

        assertEquals(0, recorder.starts)
        assertTrue("still downloading (40%)" in state.message.value, state.message.value)
    }

    @Test
    fun allowingTheDownloadAsksAgainWithoutARestart() {
        val (state, _) = vault()
        state.plugins.add(transcriber(granted = PROVIDER_LOCAL_CAPABILITIES))
        var asks = 0
        compose.setContent { KeepTranscriptionAsked(state) { asks += 1 } }
        compose.waitForIdle()
        assertEquals(1, asks, "asked once the vault is open")

        // What Settings ▸ Plugins ▸ Transcription ▸ Allow does to the list.
        val at = state.plugins.indexOfFirst { it.pluginId == TRANSCRIPTION_PLUGIN }
        state.plugins[at] = transcriber(granted = PROVIDER_LOCAL_CAPABILITIES + NETWORK_CAPABILITY)
        compose.waitForIdle()
        assertEquals(2, asks, "the grant is acted on now, not at the next launch")

        // The step past: a change to some other plugin is not a reason to
        // ask again, or every unrelated toggle would restart a download.
        val other = state.plugins.indexOfFirst { it.pluginId == VOICE_CAPTURE_PLUGIN }
        state.plugins[other] = state.plugins[other].copy(state = "disabled")
        compose.waitForIdle()
        assertEquals(2, asks)
    }
}
