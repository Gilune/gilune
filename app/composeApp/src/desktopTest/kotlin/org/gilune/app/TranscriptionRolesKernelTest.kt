// Transcription roles against the real kernel (ADR 0033 §§ 4, 6): the
// Recordings picker moves waiting recordings and withholds the old
// provider, the plugin manager's switch never makes two take jobs, and
// registering again never moves anybody's choice.
//
// Whisper is not in this build's test kernel, so a stand-in declaration
// with whisper's id, processor and engines plays it, as NemoTest does.

package org.gilune.app

import org.gilune.plugin.sdk.jsonString
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptionRolesKernelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun vault(name: String = "vault"): Gilune {
        val gilune = Gilune.open(folder.newFolder(name).path)
        gilune.registerPlugin(whisperStandInDeclaration())
        // The desktop's own registration: NeMo arrives switched off beside
        // a transcriber that already takes the recordings.
        registerNemoOnHandle(gilune)
        return gilune
    }

    private fun capture(gilune: Gilune): String {
        val wav = wavBytes(RecordedAudio(ByteArray(WAV_SAMPLE_RATE / 2), WAV_SAMPLE_RATE, 1, 16))
        val writer = gilune.beginCapture("wav")
        writer.writeChunk(wav)
        return writer.finish().sourceId
    }

    private fun Gilune.plugin(id: String): PluginInfo = plugins().first { it.pluginId == id }

    private val parakeet = EngineChoice(NEMO_PLUGIN, "parakeet-tdt-0.6b-v2")

    @Test
    fun choosingAnotherPluginForRecordingsMovesTheWaitingOnesAndWithholdsTheOld() {
        vault().use { gilune ->
            assertEquals("disabled", gilune.plugin(NEMO_PLUGIN).state, "registering is not choosing")
            capture(gilune)
            capture(gilune)
            assertEquals(2L, gilune.status().jobsPending, "two recordings wait for whisper")

            val said = chooseRecordingsEngine(gilune, parakeet)

            assertTrue("2 waiting recordings moved" in said, said)
            val nemo = gilune.plugin(NEMO_PLUGIN)
            assertEquals("enabled", nemo.state)
            assertTrue(nemo.takesJobs)
            assertEquals("parakeet-tdt-0.6b-v2", jsonString(nemo.settings, RECORDINGS_ENGINE_KEY))
            val whisper = gilune.plugin(TRANSCRIPTION_PLUGIN)
            assertEquals("enabled", whisper.state, "the old provider stays on for while you speak")
            assertFalse(whisper.takesJobs)
            assertEquals(parakeet, gilune.plugins().recordingsChoice())

            assertTrue(registerNemoProcessor(gilune))
            val claimed = generateSequence { gilune.claimJob(NEMO_PROCESSOR)?.also { it.close() } }.take(5).count()
            assertEquals(2, claimed, "both waiting recordings are NeMo's now")
        }
    }

    @Test
    fun afterTheSwitchANewRecordingQueuesForTheNewEngineOnly() {
        // The step past the hand-over: what arrives next is not queued
        // twice, once for the engine that no longer takes recordings.
        vault().use { gilune ->
            chooseRecordingsEngine(gilune, parakeet)
            capture(gilune)
            assertEquals(1L, gilune.status().jobsPending)
            assertTrue(registerNemoProcessor(gilune))
            assertNotNull(gilune.claimJob(NEMO_PROCESSOR)).close()

            // And back: choosing whisper again moves what waits back to it,
            // and choosing the engine already chosen moves nothing.
            capture(gilune)
            val back = chooseRecordingsEngine(gilune, EngineChoice(TRANSCRIPTION_PLUGIN, "tiny"))
            assertTrue("1 waiting recording moved" in back, back)
            assertTrue(gilune.plugin(TRANSCRIPTION_PLUGIN).takesJobs)
            assertFalse(gilune.plugin(NEMO_PLUGIN).takesJobs)
            assertNull(gilune.claimJob(NEMO_PROCESSOR))
            val again = chooseRecordingsEngine(gilune, EngineChoice(TRANSCRIPTION_PLUGIN, "base"))
            assertFalse("moved" in again, again)
            assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, "base"), gilune.plugins().recordingsChoice())
        }
    }

    @Test
    fun aProviderSwitchedOnBesideAnotherTakesNoJobs() {
        vault().use { gilune ->
            val said = switchTranscriptionProvider(gilune, NEMO_PLUGIN, enable = true)
            assertTrue("still transcribes your recordings" in said, said)
            val nemo = gilune.plugin(NEMO_PLUGIN)
            assertEquals("enabled", nemo.state)
            assertFalse(nemo.takesJobs)
            assertEquals("enabled", gilune.plugin(TRANSCRIPTION_PLUGIN).state, "switching one on never switches another off")
            capture(gilune)
            assertEquals(1L, gilune.status().jobsPending, "one job, whisper's")
            assertTrue(registerNemoProcessor(gilune))
            assertNull(gilune.claimJob(NEMO_PROCESSOR))
        }
    }

    @Test
    fun switchingOffTheRecordingsTakerLeavesRecordingsWaitingAndHandsThemToNobody() {
        vault().use { gilune ->
            switchTranscriptionProvider(gilune, NEMO_PLUGIN, enable = true)
            val said = switchTranscriptionProvider(gilune, TRANSCRIPTION_PLUGIN, enable = false)
            assertTrue("recordings: nothing chosen" in said, said)
            assertNull(gilune.plugins().recordingsChoice())
            assertFalse(gilune.plugin(NEMO_PLUGIN).takesJobs, "nobody takes them until a person chooses")
            // The step past: switching a provider on when nobody takes
            // recordings gives them to it.
            switchTranscriptionProvider(gilune, NEMO_PLUGIN, enable = false)
            val on = switchTranscriptionProvider(gilune, NEMO_PLUGIN, enable = true)
            assertTrue("transcribes your recordings" in on, on)
            assertEquals(NEMO_PLUGIN, gilune.plugins().recordingsChoice()?.pluginId)
        }
    }

    @Test
    fun registeringAgainNeverMovesAChoice() {
        val root: File
        vault().use { gilune ->
            root = File(gilune.vaultPath())
            chooseRecordingsEngine(gilune, parakeet)
            chooseLiveEngine(gilune, EngineChoice(TRANSCRIPTION_PLUGIN, "tiny"))
        }
        fun snapshot(gilune: Gilune) = gilune.plugins().filter { it.pluginId in TRANSCRIPTION_PROVIDERS }
            .map { Triple(it.state, it.takesJobs, it.settings) }
        lateinit var before: List<Triple<String, Boolean, String>>
        Gilune.open(root.path).use { gilune ->
            before = snapshot(gilune)
            gilune.registerPlugin(whisperStandInDeclaration())
            registerNemoOnHandle(gilune)
            registerNemoOnHandle(gilune)
            assertNull(keepOneRecordingsTaker(gilune))
            assertEquals(before, snapshot(gilune))
            assertEquals(parakeet, gilune.plugins().recordingsChoice())
            assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, "tiny"), gilune.plugins().liveChoice())
        }
    }

    @Test
    fun twoTakingJobsKeepsTheFirstAndWithholdsTheRest() {
        vault().use { gilune ->
            // The CLI's hole: switched on without the host's care.
            gilune.setPluginEnabled(NEMO_PLUGIN, true)
            gilune.setTakesJobs(NEMO_PLUGIN, true)
            assertNotNull(keepOneRecordingsTaker(gilune))
            assertTrue(gilune.plugin(TRANSCRIPTION_PLUGIN).takesJobs)
            assertFalse(gilune.plugin(NEMO_PLUGIN).takesJobs)
            assertEquals("enabled", gilune.plugin(NEMO_PLUGIN).state, "nothing is switched off")
            assertNull(keepOneRecordingsTaker(gilune), "and nothing to say the second time")
        }
    }

    @Test
    fun anExistingSetupKeepsItsEnginesUntilSomebodyChooses() {
        vault().use { gilune ->
            gilune.setPluginSettings(TRANSCRIPTION_PLUGIN, transcriptionSettingsDocument("sv", "tiny", true))
            assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, "tiny"), gilune.plugins().recordingsChoice())
            assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, "tiny"), gilune.plugins().liveChoice())

            // Choosing NeMo for while you speak switches it on taking no
            // recordings, and leaves whisper's recordings and language.
            val said = chooseLiveEngine(gilune, EngineChoice(NEMO_PLUGIN, "nemotron-0.6b"))
            assertTrue("Nemotron 0.6B" in said, said)
            val nemo = gilune.plugin(NEMO_PLUGIN)
            assertEquals("enabled", nemo.state)
            assertFalse(nemo.takesJobs)
            assertEquals(EngineChoice(NEMO_PLUGIN, "nemotron-0.6b"), gilune.plugins().liveChoice())
            assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, "tiny"), gilune.plugins().recordingsChoice())
            val whisper = gilune.plugin(TRANSCRIPTION_PLUGIN).settings
            assertEquals("sv", jsonString(whisper, "language"))
            assertEquals("", jsonString(whisper, LIVE_ENGINE_KEY))

            chooseLiveEngine(gilune, null)
            assertNull(gilune.plugins().liveChoice())
            assertTrue(gilune.plugin(NEMO_PLUGIN).state == "enabled", "Off switches nothing off")
        }
    }

    @Test
    fun whisperInBothRolesUsesOneModel() {
        vault().use { gilune ->
            chooseLiveEngine(gilune, EngineChoice(TRANSCRIPTION_PLUGIN, "tiny"))
            val said = chooseRecordingsEngine(gilune, EngineChoice(TRANSCRIPTION_PLUGIN, "base"))
            assertTrue("one model at a time" in said, said)
            assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, "base"), gilune.plugins().liveChoice())
            // The step past: whisper live beside NeMo recordings is two
            // plugins, and changing one leaves the other alone.
            chooseRecordingsEngine(gilune, parakeet)
            val live = chooseLiveEngine(gilune, EngineChoice(TRANSCRIPTION_PLUGIN, "tiny-q5_1"))
            assertFalse("one model at a time" in live, live)
            assertEquals(parakeet, gilune.plugins().recordingsChoice())
        }
    }

    @Test
    fun dictationNeedsTheLiveEngineOrTheRecordingsOne() {
        val ready = TranscriptionRuntime().apply { phase = TranscriptionPhase.Ready }
        val absent = TranscriptionRuntime().apply {
            phase = TranscriptionPhase.Absent
            absentWords = "nothing is chosen for recordings — Settings ▸ Transcription"
        }
        assertNull(dictationUnreadyReason(recordings = absent, live = ready), "live words need no recordings engine")
        assertNull(dictationUnreadyReason(recordings = ready, live = TranscriptionRuntime()))
        assertEquals(absent.absentWords, dictationUnreadyReason(recordings = absent, live = TranscriptionRuntime()))
    }
}
