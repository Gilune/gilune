// Transcription (NVIDIA NeMo) on the desktop (ADR 0027 S3): the verified
// download, the provider's job loop against a real kernel, the plugin's
// settings and its place among the transcribers — and, opt-in with real
// models (GILUNE_TEST_NEMO_ROOT naming a folder of unpacked archives), the
// engines themselves.

package org.gilune.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NemoTest {
    @get:Rule
    val folder = TemporaryFolder()

    // ------------------------------------------------------------ install

    private fun archive(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, content) in entries) {
                val data = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun model(archive: String, bytes: ByteArray, sha256: String = sha(bytes)) =
        NemoModel("test", "Test", archive, sha256, bytes.size.toLong(), "1 KB", NemoKind.STREAMING, "test", "")

    @Test
    fun aVerifiedArchiveUnpacksIntoItsOwnFolder() {
        val bytes = archive(mapOf("m/tokens.txt" to "a 1", "m/encoder.int8.onnx" to "e"))
        val root = folder.newFolder("models")
        val m = model("m", bytes)
        assertFalse(nemoModelPresent(m, root))
        installNemoModel(m, root) { bytes.inputStream() }
        assertTrue(nemoModelPresent(m, root))
        assertEquals("a 1", File(root, "m/tokens.txt").readText())
        assertEquals(listOf("m"), root.list()!!.toList(), "no archive or staging folder left behind")
    }

    @Test
    fun anArchiveThatDoesNotMatchItsChecksumIsNeverUnpacked() {
        val bytes = archive(mapOf("m/tokens.txt" to "a 1"))
        val root = folder.newFolder("models")
        val m = model("m", bytes, sha256 = "0".repeat(64))
        val refused = assertFailsWith<IllegalStateException> { installNemoModel(m, root) { bytes.inputStream() } }
        assertTrue("checksum" in refused.message.orEmpty(), refused.message)
        assertFalse(nemoModelPresent(m, root))
        assertEquals(emptyList(), root.list()!!.toList(), "nothing kept from a download that failed its check")
    }

    @Test
    fun anArchiveEntryCannotWriteOutsideItsFolder() {
        // The step past a good checksum: a published archive is trusted to
        // be what it says, but an entry naming ../ must still go nowhere.
        val bytes = archive(mapOf("m/tokens.txt" to "a 1", "m/../../escaped.txt" to "x"))
        val root = folder.newFolder("models")
        assertFailsWith<IllegalStateException> { installNemoModel(model("m", bytes), root) { bytes.inputStream() } }
        assertFalse(File(root.parentFile, "escaped.txt").exists())
        assertFalse(File(root, "escaped.txt").exists())
        assertFalse(nemoModelPresent(model("m", bytes), root))
    }

    // ----------------------------------------------------- the job loop

    private fun vaultWithNemo(): Pair<File, Gilune> {
        val root = folder.newFolder("vault")
        val gilune = Gilune.open(root.path)
        // Whisper is not in this build's test kernel unless the feature is,
        // so NeMo is the transcriber here; switch any other off.
        gilune.registerPlugin(nemoDeclaration())
        for (other in gilune.plugins().filter { it.pluginId in TRANSCRIPTION_PROVIDERS && it.pluginId != NEMO_PLUGIN }) {
            gilune.setPluginEnabled(other.pluginId, false)
        }
        for (capability in PROVIDER_LOCAL_CAPABILITIES) gilune.grantCapability(NEMO_PLUGIN, capability)
        assertTrue(registerNemoProcessor(gilune))
        return root to gilune
    }

    private fun capture(gilune: Gilune): String {
        val wav = wavBytes(RecordedAudio(ByteArray(WAV_SAMPLE_RATE * 2), WAV_SAMPLE_RATE, 1, 16))
        val writer = gilune.beginCapture("wav")
        writer.writeChunk(wav)
        return writer.finish().sourceId
    }

    @Test
    fun aRecordingsWordsLandInItsNoteThroughTheProvider() {
        val (_, gilune) = vaultWithNemo()
        gilune.use {
            val source = capture(gilune)
            var heard = -1
            val pass = runNemoJobs(gilune, { pcm -> heard = pcm.size; "remember the milk" })
            assertEquals(1L, pass.transcribed)
            assertEquals(WAV_SAMPLE_RATE * 2, heard, "the engine heard the samples, not the header")
            assertEquals("remember the milk", gilune.objectText(source))
            assertEquals(0L, runNemoJobs(gilune, { error("nothing left to hear") }).claimed)
        }
    }

    @Test
    fun aNoteEditedWhileItIsBeingHeardKeepsThePersonsWords() {
        val (root, gilune) = vaultWithNemo()
        gilune.use {
            val source = capture(gilune)
            gilune.project()
            val note = root.resolve("Captures").listFiles { f -> f.extension == "md" }!!.single()
            val rel = note.relativeTo(root).invariantSeparatorsPath
            val pass = runNemoJobs(gilune, {
                // Somebody types into the note while the engine is busy.
                note.writeText(note.readText() + "\nwritten by hand")
                gilune.reconcilePath(rel)
                "the engine's words"
            })
            assertEquals(1L, pass.skipped, "the engine's answer was not written over the edit")
            assertEquals(0L, pass.failed, "and it is not a failure to retry")
            assertFalse(gilune.objectText(source).orEmpty().contains("the engine's words"))
        }
    }

    // -------------------------------------------------------- residency

    // The model is in memory for the work, not for the session (09 § Desktop
    // footprint): 1.3.2 loaded Nemotron when the vault opened and held
    // ~700 MB for a dictation that might never come (owner, 2026-11-01).
    // These folders pass the app's presence test but hold no model, so any
    // load throws — which is how a row sees one happen.

    private fun presentButEmpty(model: NemoModel): File {
        val models = folder.newFolder("models")
        File(models, model.archive).mkdirs()
        File(File(models, model.archive), READY_MARKER).writeText("")
        return models
    }

    @Test
    fun bringingNemoUpLeavesTheModelOnDisk() {
        val (root, gilune) = vaultWithNemo()
        gilune.use {
            val models = presentButEmpty(gilune.plugins().nemoModel())
            val runtime = TranscriptionRuntime()
            val nemo = NemoHost()
            bringUpNemo(GiluneState(root.path, CoroutineScope(Dispatchers.Default)), gilune, runtime, nemo, models)
            assertEquals(TranscriptionPhase.Ready, runtime.phase, runtime.failure.orEmpty())
            assertFalse(assertNotNull(nemo.engines).resident, "ready means the files are here, not the model in memory")
        }
    }

    @Test
    fun aLiveSessionLoadsTheModelWithItsFirstAudioNotBefore() {
        // The session is made on the click that starts the microphone; a
        // load there froze the editor for the ~3 s Nemotron takes.
        val model = nemoModel(DEFAULT_NEMO_MODEL)
        NemoEngines(model, File(presentButEmpty(model), model.archive)).use { engines ->
            val silent = assertNotNull(engines.liveSession())
            assertFalse(engines.resident)
            assertEquals(emptyList(), silent.finish(), "a dictation that heard nothing loads nothing to end")
            val heard = assertNotNull(engines.liveSession())
            assertFailsWith<IllegalStateException> { heard.accept(ByteArray(3_200)) }
        }
    }

    // --------------------------------------------------------- settings

    private fun installed(id: String, state: String = "enabled", settings: String = "", name: String = id) = PluginInfo(
        pluginId = id, name = name, `class` = "provider", profile = "bundled", required = false, state = state,
        processorId = null, subscribes = null, emits = emptyList(), capabilities = emptyList(), granted = emptyList(),
        ungranted = emptyList(), contributions = emptyList(), settings = settings,
    )

    @Test
    fun nemotronIsTheDefaultAndShowsWordsUnlessTurnedOff() {
        val fresh = listOf(installed(NEMO_PLUGIN))
        assertEquals("nemotron-0.6b", fresh.nemoModel().key)
        assertTrue(fresh.nemoLive())
        val parakeetOff = listOf(installed(NEMO_PLUGIN, settings = nemoSettingsDocument("parakeet-tdt-0.6b-v2", false)))
        assertEquals(NemoKind.WHOLE_RECORDING, parakeetOff.nemoModel().kind)
        assertFalse(parakeetOff.nemoLive())
        assertEquals("nemotron-0.6b", listOf(installed(NEMO_PLUGIN, settings = """{"model":"gone"}""")).nemoModel().key)
    }

    @Test
    fun switchingNemoOnSwitchesWhisperOff() {
        val plugins = listOf(
            installed(TRANSCRIPTION_PLUGIN, name = "Transcription (Whisper)"),
            installed(NEMO_PLUGIN, state = "disabled", name = "Transcription (NVIDIA NeMo)"),
        )
        val switch = providerSwitch(plugins, NEMO_PLUGIN, enable = true)
        assertEquals(listOf(TRANSCRIPTION_PLUGIN), switch.disable)
        assertTrue("Transcription (Whisper) switched off" in switch.message, switch.message)
        assertEquals(TRANSCRIPTION_PLUGIN, plugins.activeTranscriber())
    }

    @Test
    fun aVaultWhoseNotesWhisperWritesGetsNemoSwitchedOff() {
        val root = folder.newFolder("vault-rule")
        Gilune.open(root.path).use { gilune ->
            gilune.registerPlugin(voiceCaptureDeclaration())
            // Stand whisper in for the incumbent, as a whisper build has it.
            gilune.registerPlugin(nemoDeclaration().copy(pluginId = TRANSCRIPTION_PLUGIN, processorId = "transcribe"))
            registerNemoOnHandle(gilune)
            val nemo = gilune.plugins().first { it.pluginId == NEMO_PLUGIN }
            assertEquals("disabled", nemo.state, "registering is not choosing")
            assertFalse(registerNemoProcessor(gilune))
        }
    }

    @Test
    fun theReAskKeyHearsTheNemoPluginToo() {
        val before = listOf(installed(NEMO_PLUGIN, state = "disabled"))
        val after = listOf(installed(NEMO_PLUGIN, state = "enabled"))
        assertTrue(transcriptionPlanKey(before) != transcriptionPlanKey(after))
    }

    // ------------------------------------------- real models, opt-in

    private fun realModel(model: NemoModel): File? {
        val root = System.getenv("GILUNE_TEST_NEMO_ROOT")?.let(::File) ?: return null
        return File(root, model.archive).takeIf { it.isDirectory }
    }

    private fun speech(): ByteArray? =
        System.getenv("GILUNE_TEST_SPEECH")?.let(::File)?.takeIf { it.isFile }?.readBytes()?.let { it.copyOfRange(44, it.size) }

    @Test
    fun nemotronShowsWordsWhileTheyAreSpoken() {
        val model = nemoModel("nemotron-0.6b")
        val dir = realModel(model)
        val pcm = speech()
        assumeTrue("set GILUNE_TEST_NEMO_ROOT and GILUNE_TEST_SPEECH", dir != null && pcm != null)
        NemoEngines(model, dir!!).use { engines ->
            val loadStarted = System.nanoTime()
            engines.withOnline { }
            val loadMs = (System.nanoTime() - loadStarted) / 1_000_000
            val started = System.nanoTime()
            var firstWords = -1.0
            var settledBeforeStop = 0
            val words = mutableListOf<String>()
            val done = CountDownLatch(1)
            val run = LiveDictationRun(engines.liveSession()!!, deliver = { update ->
                val now = (System.nanoTime() - started) / 1e9
                if (firstWords < 0 && update.any { it.text.isNotBlank() }) firstWords = now
                synchronized(words) {
                    for (l in update.filter { it.complete }) { words += l.text; settledBeforeStop++ }
                }
            }, fellBehind = {}, stepSeconds = 0.2)
            run.start()
            var at = 0
            while (at < pcm!!.size) {
                val end = minOf(at + 3_200, pcm.size)
                run.offer(pcm.copyOfRange(at, end))
                at = end
                Thread.sleep(100)
            }
            val spoken = (System.nanoTime() - started) / 1e9
            run.stop { last -> synchronized(words) { words += last.filter { it.complete }.map { it.text } }; done.countDown() }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            val text = synchronized(words) { words.joinToString(" ") }
            println("NEMOTRON: load $loadMs ms, first words ${"%.1f".format(firstWords)} s, " +
                "$settledBeforeStop settled before the stop at ${"%.1f".format(spoken)} s\n$text")
            assertTrue(firstWords in 0.0..2.0, "words within two seconds: $firstWords")
            assertTrue("plasterer" in text.lowercase() || "plaster" in text.lowercase(), text)
            assertTrue("friday" in text.lowercase(), text)
        }
    }

    @Test
    fun aRealModelDownloadsVerifiesAndUnpacks() {
        assumeTrue("set GILUNE_TEST_NEMO_DOWNLOAD=1 to fetch 106 MB", System.getenv("GILUNE_TEST_NEMO_DOWNLOAD") == "1")
        val model = nemoModel("fastconformer")
        val root = folder.newFolder("downloaded")
        var progress = 0L
        val started = System.nanoTime()
        installNemoModel(model, root, onProgress = { progress = it })
        println("DOWNLOAD: ${model.size} in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue(nemoModelPresent(model, root))
        assertTrue(progress > 0, "progress was reported")
        NemoEngines(model, File(root, model.archive)).use { engines ->
            assertTrue(engines.liveSession() != null, "the unpacked model loads and streams")
        }
    }

    @Test
    fun parakeetHearsAWholeRecording() {
        val model = nemoModel("parakeet-tdt-0.6b-v2")
        val dir = realModel(model)
        val pcm = speech()
        assumeTrue("set GILUNE_TEST_NEMO_ROOT and GILUNE_TEST_SPEECH", dir != null && pcm != null)
        NemoEngines(model, dir!!).use { engines ->
            assertNull(engines.liveSession(), "a whole-recording model offers no live session")
            val started = System.nanoTime()
            val text = engines.transcribe(pcm!!)
            println("PARAKEET: ${(System.nanoTime() - started) / 1_000_000} ms (incl. load)\n$text")
            assertTrue("plasterer" in text.lowercase() && "friday" in text.lowercase(), text)
        }
    }
}
