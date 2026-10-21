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
import kotlin.test.assertNull
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

    /// Plays a WAV in real time, as a microphone would deliver it.
    private class PlaybackRecorder(private val pcm: ByteArray) : AudioRecorder {
        @Volatile private var tee: ((ByteArray) -> Unit)? = null
        private var player: Thread? = null
        override fun listen(listener: ((ByteArray) -> Unit)?) { tee = listener }
        override fun start() {
            player = Thread {
                var at = 0
                while (at < pcm.size) {
                    val end = minOf(at + 3_200, pcm.size)
                    tee?.invoke(pcm.copyOfRange(at, end))
                    at = end
                    Thread.sleep(100)
                }
            }.apply { start() }
        }
        fun awaitEnd() = player?.join()
        override fun stop() = RecordedAudio(pcm, WAV_SAMPLE_RATE, WAV_CHANNELS, WAV_BITS_PER_SAMPLE)
    }

    @Test
    fun wordsAppearInTheNoteWhileTheyAreBeingSpoken() {
        val model = System.getenv("GILUNE_TEST_MODEL")?.let(::File)
        val speech = System.getenv("GILUNE_TEST_SPEECH")?.let(::File)
        assumeTrue("set GILUNE_TEST_MODEL and GILUNE_TEST_SPEECH", model?.isFile == true && speech?.isFile == true)

        val root = Files.createTempDirectory("gilune-live-e2e").toFile()
        val kernel = Gilune.open(root.path)
        try {
            kernel.enableWhisper(model!!.absolutePath)
            val state = GiluneState(root.path, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            state.gilune.value = kernel
            root.resolve("note.md").writeText("---\ngilune-id: n\n---\nSite visit:")
            val editor = EditorState()
            editor.open(root.path, "note.md")
            editor.text = editor.text.copy(selection = androidx.compose.ui.text.TextRange(editor.text.text.length))
            val bytes = speech!!.readBytes()
            val recorder = PlaybackRecorder(bytes.copyOfRange(44, bytes.size))

            val started = System.nanoTime()
            var firstGrey = -1.0
            var firstSettled = -1.0
            val watcher = Thread {
                while (true) {
                    val s = (System.nanoTime() - started) / 1e9
                    val live = editor.live ?: break
                    if (firstGrey < 0 && live.provisional.isNotBlank()) firstGrey = s
                    if (firstSettled < 0 && live.settledAny) firstSettled = s
                    Thread.sleep(20)
                }
            }
            javax.swing.SwingUtilities.invokeAndWait {
                startLiveDictation(state, editor, recorder, kernel.beginLiveTranscription())
            }
            watcher.start()
            recorder.start()
            recorder.awaitEnd()
            val spoken = (System.nanoTime() - started) / 1e9
            val landed = CountDownLatch(1)
            javax.swing.SwingUtilities.invokeAndWait {
                stopLiveDictation(state, editor, editor.live!!) { landed.countDown() }
            }
            assertTrue(landed.await(60, TimeUnit.SECONDS), "the last words landed")
            val done = (System.nanoTime() - started) / 1e9
            watcher.join(1_000)
            val text = editor.text.text
            println("LIVE: first grey ${"%.1f".format(firstGrey)} s, first settled ${"%.1f".format(firstSettled)} s, " +
                "speech ended ${"%.1f".format(spoken)} s, all settled ${"%.1f".format(done)} s\n$text")

            assertTrue(firstGrey in 0.0..3.0, "grey words within a few seconds: $firstGrey")
            assertTrue(firstSettled in 0.0..(spoken - 5), "words settled while still speaking: $firstSettled")
            assertTrue(text.startsWith("Site visit: "), text)
            assertTrue("plasterer" in text.lowercase() && "friday" in text.lowercase(), text)
            val words = text.lowercase().split(Regex("\\s+"))
            assertTrue(words.zipWithNext().none { (a, b) -> a == b }, "no word doubled at a settle: $text")
            assertNull(editor.live, "the session has ended")
        } finally {
            kernel.close()
            root.deleteRecursively()
        }
    }
}
