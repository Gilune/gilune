// Speaker labels on the desktop (ADR 0030), through the real kernel.
//
// The model rows need the environment — `GILUNE_TEST_MODEL` (a whisper
// model), `GILUNE_TEST_SPEAKER_MODELS` (a folder holding the two speaker
// model downloads, as fetched) and `GILUNE_TEST_SPEAKER_WAV` (a canonical
// WAV with two voices taking turns) — and skip without it, on the house
// rule. The rows about which path NeMo takes, and about the download
// check, need nothing.

package org.gilune.app

import uniffi.gilune_ffi.Gilune
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakersEndToEndTest {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    /// The download, served from the folder of real downloads.
    private fun localDownloads(dir: File): (String) -> java.io.InputStream =
        { url -> File(dir, url.substringAfterLast('/')).inputStream() }

    @Test
    fun aDownloadThatIsNotTheRecordedFileIsRefusedAndLeavesNoModel() {
        val root = Files.createTempDirectory("gilune-speakers").toFile()
        assertFailsWith<IllegalStateException> {
            installSpeakerModels(root, open = { ByteArrayInputStream(ByteArray(1024)) })
        }
        assertFalse(speakerModelsPresent(root))
        assertEquals(emptyList(), root.listFiles().orEmpty().map { it.name }, "nothing half-written is left")
    }

    @Test
    fun theRecordedFilesInstallAndReadAsPresent() {
        val downloads = env("GILUNE_TEST_SPEAKER_MODELS")?.let(::File) ?: return println("skipped: set GILUNE_TEST_SPEAKER_MODELS")
        val root = Files.createTempDirectory("gilune-speakers").toFile()
        installSpeakerModels(root, open = localDownloads(downloads))
        assertTrue(speakerModelsPresent(root))
    }

    @Test
    fun whisperWritesATwoVoiceRecordingAsTwoSpeakersTakingTurns() {
        val model = env("GILUNE_TEST_MODEL") ?: return println("skipped: set GILUNE_TEST_MODEL")
        val downloads = env("GILUNE_TEST_SPEAKER_MODELS")?.let(::File) ?: return println("skipped: set GILUNE_TEST_SPEAKER_MODELS")
        val wav = env("GILUNE_TEST_SPEAKER_WAV")?.let { File(it).readBytes() } ?: return println("skipped: set GILUNE_TEST_SPEAKER_WAV")
        val models = Files.createTempDirectory("gilune-speakers").toFile()
        installSpeakerModels(models, open = localDownloads(downloads))

        val vault = Files.createTempDirectory("gilune-speaker-vault").toFile()
        val gilune = Gilune.open(vault.path)
        gilune.enableWhisper(model)
        SpeakerEngine(models).use { engine ->
            gilune.setSpeakerLabeller(DesktopSpeakerLabeller(engine) { null })
            val writer = gilune.beginCapture("wav")
            writer.writeChunk(wav)
            val receipt = writer.finish()
            val started = System.currentTimeMillis()
            gilune.runProcessing()
            println("TRANSCRIBED AND LABELLED in ${System.currentTimeMillis() - started} ms")
            val text = gilune.objectText(receipt.sourceId).orEmpty()
            println(text)
            val speakers = Regex("""^\*\*Speaker (\d+):\*\*""", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
            assertEquals(listOf("1", "2", "1", "2", "1", "2", "1", "2"), speakers, text)
        }
    }

    @Test
    fun withTheCountSetToOneEverythingIsOneVoiceAndPlain() {
        val model = env("GILUNE_TEST_MODEL") ?: return println("skipped: set GILUNE_TEST_MODEL")
        val downloads = env("GILUNE_TEST_SPEAKER_MODELS")?.let(::File) ?: return println("skipped: set GILUNE_TEST_SPEAKER_MODELS")
        val wav = env("GILUNE_TEST_SPEAKER_WAV")?.let { File(it).readBytes() } ?: return println("skipped: set GILUNE_TEST_SPEAKER_WAV")
        val models = Files.createTempDirectory("gilune-speakers").toFile()
        installSpeakerModels(models, open = localDownloads(downloads))
        val gilune = Gilune.open(Files.createTempDirectory("gilune-speaker-vault").toFile().path)
        gilune.enableWhisper(model)
        SpeakerEngine(models).use { engine ->
            gilune.setSpeakerLabeller(DesktopSpeakerLabeller(engine) { 1 })
            val writer = gilune.beginCapture("wav")
            writer.writeChunk(wav)
            val receipt = writer.finish()
            gilune.runProcessing()
            val text = gilune.objectText(receipt.sourceId).orEmpty()
            assertFalse("Speaker" in text, text)
            assertTrue("budget" in text, text)
        }
    }

    /// NeMo's provider loop: a recording goes through the labelled path, a
    /// dictation into a note through the plain one.
    @Test
    fun nemoLabelsRecordingsButNeverDictation() {
        val root = Files.createTempDirectory("gilune-speaker-nemo").toFile()
        val gilune = Gilune.open(root.path)
        gilune.registerPlugin(nemoDeclaration())
        for (other in gilune.plugins().filter { it.pluginId in TRANSCRIPTION_PROVIDERS && it.pluginId != NEMO_PLUGIN }) {
            gilune.setPluginEnabled(other.pluginId, false)
        }
        for (capability in PROVIDER_LOCAL_CAPABILITIES) gilune.grantCapability(NEMO_PLUGIN, capability)
        assertTrue(registerNemoProcessor(gilune))

        fun capture(attached: Boolean): String {
            val writer = if (attached) gilune.beginAttachedCapture("wav") else gilune.beginCapture("wav")
            writer.writeChunk(silentWav())
            return writer.finish().sourceId
        }
        val recording = capture(attached = false)
        val dictation = capture(attached = true)
        gilune.runProcessing()
        runNemoJobs(gilune, { "plain" }, labelled = { "**Speaker 1:** a\n\n**Speaker 2:** b" })
        assertEquals("**Speaker 1:** a\n\n**Speaker 2:** b", gilune.objectText(recording))
        assertEquals("plain", gilune.objectText(dictation))
    }

    private fun silentWav(): ByteArray {
        val data = 16_000 * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data)
        return header.array() + ByteArray(data)
    }
}
