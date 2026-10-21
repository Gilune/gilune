// Dictation through the real kernel and a real model: an attached capture
// is committed, the transcription plugin's processor transcribes it, and
// the words come back for the caret. Opt-in, like the kernel's whisper
// rows: GILUNE_TEST_MODEL names a ggml model and GILUNE_TEST_SPEECH a
// 16 kHz mono 16-bit WAV of speech.

package org.gilune.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class DictationEndToEndTest {
    @Test
    fun spokenWordsComeBackForTheCaretAndNoNoteIsMade() {
        val model = System.getenv("GILUNE_TEST_MODEL")?.let(::File)
        val speech = System.getenv("GILUNE_TEST_SPEECH")?.let(::File)
        assumeTrue("set GILUNE_TEST_MODEL and GILUNE_TEST_SPEECH", model?.isFile == true && speech?.isFile == true)

        val root = Files.createTempDirectory("gilune-dictation-e2e").toFile()
        val kernel = Gilune.open(root.path)
        try {
            kernel.enableWhisper(model!!.absolutePath)
            val state = GiluneState(root.path, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            state.gilune.value = kernel
            val bytes = speech!!.readBytes()
            val audio = RecordedAudio(bytes.copyOfRange(44, bytes.size), WAV_SAMPLE_RATE, WAV_CHANNELS, WAV_BITS_PER_SAMPLE)

            val landed = CountDownLatch(1)
            var words = ""
            state.dictate(audio) { words = it; landed.countDown() }

            assertTrue(landed.await(120, TimeUnit.SECONDS), "no words came back: ${state.message.value}")
            assertTrue("plasterer" in words.lowercase(), "the words are the speech: $words")
            assertTrue(state.message.value == "dictated", state.message.value)
            // An attached capture belongs to the note being written; it
            // never becomes a note of its own (ADR 0013).
            kernel.project()
            val notes = root.walkTopDown().filter { it.isFile && it.extension == "md" && ".gilune" !in it.path }.toList()
            assertTrue(notes.isEmpty(), "no note was projected: $notes")
        } finally {
            kernel.close()
            root.deleteRecursively()
        }
    }
}
