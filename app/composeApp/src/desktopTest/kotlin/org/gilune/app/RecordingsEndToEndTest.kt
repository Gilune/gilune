// Loading a recording and transcribing one again (ADR 0029), through the
// real kernel, the real shared state and the real NeMo provider loop — only
// the engine is a stand-in, which answers "answer 1", "answer 2", … so a
// second transcription is visible as a second answer.
//
// NeMo is the right provider to drive it with: it is an app-side provider
// with the wordless gate, so "transcribe again" reaches it only because the
// request says so (whisper, in the kernel, has no gate to override).

package org.gilune.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import uniffi.gilune_ffi.Gilune
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecordingsEndToEndTest {
    private val answers = AtomicInteger()

    private fun vault(): Pair<File, GiluneState> {
        val root = Files.createTempDirectory("gilune-recordings").toFile()
        File(root, "Meetings").mkdirs()
        val gilune = Gilune.open(root.path)
        gilune.registerPlugin(nemoDeclaration())
        for (other in gilune.plugins().filter { it.pluginId in TRANSCRIPTION_PROVIDERS && it.pluginId != NEMO_PLUGIN }) {
            gilune.setPluginEnabled(other.pluginId, false)
        }
        for (capability in PROVIDER_LOCAL_CAPABILITIES) gilune.grantCapability(NEMO_PLUGIN, capability)
        assertTrue(registerNemoProcessor(gilune))
        val state = GiluneState(root.path, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        state.gilune.value = gilune
        state.externalProcessing = { g -> runNemoJobs(g, { "answer ${answers.incrementAndGet()}" }) }
        return root to state
    }

    /// A 44.1 kHz stereo WAV somewhere outside the vault, as a phone's or
    /// a meeting tool's export would be.
    private fun exported(name: String): File {
        val format = AudioFormat(44_100f, 16, 2, true, false)
        val frames = 44_100L
        val file = File(Files.createTempDirectory("gilune-export").toFile(), name)
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(ByteArray((frames * 4).toInt())), format, frames),
            AudioFileFormat.Type.WAVE,
            file,
        )
        return file
    }

    private fun body(root: File, rel: String): String = File(root, rel).readText().substringAfter("\n---\n")

    private fun awaitMessage(state: GiluneState, expected: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (state.message.value != expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(expected, state.message.value)
    }

    @Test
    fun aLoadedRecordingBecomesANoteNamedAfterItInTheFolderAskedFor() {
        val (root, state) = vault()
        state.gilune.value!!.use {
            val opened = CountDownLatch(1)
            var note = ""
            runBlocking { loadRecordingFile(state, exported("Standup.wav"), "Meetings") { note = it; opened.countDown() } }
            assertTrue(opened.await(30, TimeUnit.SECONDS), "the note opened: ${state.message.value}")
            assertEquals("Meetings/Standup.md", note)
            assertEquals("recording transcribed", state.message.value)
            assertTrue(File(root, "Meetings/Standup.wav").isFile, "the recording went with its note")
            assertEquals("answer 1", body(root, note).trim())
            val header = File(root, note).readText()
            assertTrue("gilune-blob: Meetings/Standup.wav" in header, header)

            val found = assertNotNull(state.sourceForFile("Meetings/Standup.wav"), "the kernel followed the move")
            assertEquals(note, found.projectionRelPath)
        }
    }

    @Test
    fun transcribingAgainWritesNewWordsEvenThroughAProviderThatSkipsWordedNotes() {
        val (root, state) = vault()
        state.gilune.value!!.use {
            val opened = CountDownLatch(1)
            runBlocking { loadRecordingFile(state, exported("Retro.wav"), null) { opened.countDown() } }
            assertTrue(opened.await(30, TimeUnit.SECONDS), state.message.value)
            val found = assertNotNull(state.sourceForFile("Captures/Retro.wav"))
            assertEquals(false, found.textEdited)

            state.transcribeAgain(found.objectId)
            awaitMessage(state, "transcribed again")
            assertEquals("answer 2", body(root, "Captures/Retro.md").trim())

            // Somebody's own words make the next request one to ask about.
            val path = File(root, "Captures/Retro.md")
            path.writeText(path.readText().replace("answer 2", "answer 2, and the actions we agreed"))
            state.gilune.value!!.reconcile()
            assertEquals(true, state.sourceForFile("Captures/Retro.wav")?.textEdited)
        }
    }

    @Test
    fun withNothingSwitchedOnItSaysSoInsteadOfWaiting() {
        val (_, state) = vault()
        state.gilune.value!!.use { gilune ->
            val opened = CountDownLatch(1)
            runBlocking { loadRecordingFile(state, exported("Call.wav"), null) { opened.countDown() } }
            assertTrue(opened.await(30, TimeUnit.SECONDS), state.message.value)
            val found = assertNotNull(state.sourceForFile("Captures/Call.wav"))
            gilune.setPluginEnabled(NEMO_PLUGIN, false)
            state.transcribeAgain(found.objectId)
            awaitMessage(state, "nothing can transcribe it: switch a transcription plugin on in Settings ▸ Plugins")
        }
    }
}
