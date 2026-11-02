// Transcription (NVIDIA NeMo) on the desktop (ADR 0027 S3): the verified
// download, the provider's job loop against a real kernel, the plugin's
// settings and its place among the transcribers — and, opt-in with real
// models (GILUNE_TEST_NEMO_ROOT naming a folder of unpacked archives), the
// engines themselves.

package org.gilune.app

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
            val pass = runNemoJobs(gilune, { recording -> heard = recording.pieces.sumOf { it.size }; "remember the milk" })
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
            val text = engines.transcribe(RecordingPcm.of(pcm!!))
            println("PARAKEET: ${(System.nanoTime() - started) / 1_000_000} ms (incl. load)\n$text")
            assertTrue("plasterer" in text.lowercase() && "friday" in text.lowercase(), text)
        }
    }

    /// An hour-long recording through the real engines, as the window runs
    /// them: GILUNE_TEST_LONG_SPEECH names a canonical WAV of two voices
    /// taking turns, GILUNE_TEST_SPEAKER_ROOT the speaker models as
    /// installed. Reports the most heap the run ever held.
    @Test
    fun nemotronHearsAnHourLongRecordingWithItsSpeakers() {
        val model = nemoModel("nemotron-0.6b")
        val dir = realModel(model)
        val wav = System.getenv("GILUNE_TEST_LONG_SPEECH")?.let(::File)?.takeIf { it.isFile }
        val speakers = System.getenv("GILUNE_TEST_SPEAKER_ROOT")?.let(::File)?.takeIf { speakerModelsPresent(it) }
        assumeTrue("set GILUNE_TEST_NEMO_ROOT, GILUNE_TEST_LONG_SPEECH and GILUNE_TEST_SPEAKER_ROOT", dir != null && wav != null && speakers != null)
        val (_, gilune) = vaultWithNemo()
        gilune.use {
            val writer = gilune.beginCapture("wav")
            wav!!.inputStream().use { input ->
                val buffer = ByteArray(PCM_PIECE_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    writer.writeChunk(buffer.copyOf(read))
                }
            }
            val source = writer.finish().sourceId
            val pools = java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == java.lang.management.MemoryType.HEAP }
            repeat(2) { System.gc() }
            pools.forEach { it.resetPeakUsage() }
            val started = System.nanoTime()
            val host = SpeakerHost()
            NemoEngines(model, dir!!).use { engines ->
                SpeakerEngine(speakers!!).use { engine ->
                    host.replace(engine) { null }
                    val pass = runNemoJobs(gilune, engines::transcribe, labelled = host.labelling(engines))
                    assertEquals(1L, pass.transcribed, "transcribed, not failed: $pass")
                }
            }
            val peak = pools.sumOf { it.peakUsage.used }
            val text = gilune.objectText(source).orEmpty()
            val named = Regex("\\*\\*Speaker (\\d+):\\*\\*").findAll(text).map { it.groupValues[1] }.toSet()
            println("LONG: ${wav!!.length() shr 20} MB in ${(System.nanoTime() - started) / 1_000_000_000} s, " +
                "heap peak ${peak shr 20} MB of ${Runtime.getRuntime().maxMemory() shr 20} MB, " +
                "${text.length} characters, speakers $named\n${text.take(600)}")
            assertTrue("plasterer" in text.lowercase(), text.take(600))
            assertEquals(setOf("1", "2"), named, "two voices, named the same in every window")
        }
    }

    // ------------------------------------------------ long recordings

    /// The heap in use once the collector has had its say.
    private fun heapInUse(): Long {
        repeat(2) { System.gc() }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun aLongRecordingIsHeardAPieceAtATimeAndNeverHeldWhole() {
        // Fifty minutes of 16 kHz PCM, 96 MB: what a user's hour-long
        // meeting ran out of heap on, held as bytes, as bytes without the
        // header and as floats at once.
        val pcmBytes = 96L shl 20
        val (_, gilune) = vaultWithNemo()
        gilune.use {
            val writer = gilune.beginCapture("wav")
            // The header alone; its sizes are not what anything reads.
            writer.writeChunk(wavBytes(RecordedAudio(ByteArray(0), WAV_SAMPLE_RATE, 1, 16)))
            val chunk = ByteArray(PCM_PIECE_BYTES)
            var written = 0L
            while (written < pcmBytes) {
                writer.writeChunk(chunk)
                written += chunk.size
            }
            writer.finish()
            val before = heapInUse()
            var heard = 0L
            var largestPiece = 0
            var mostHeld = 0L
            var samples = -1L
            val pass = runNemoJobs(gilune, { recording ->
                samples = recording.samples
                for (piece in recording.pieces) {
                    heard += piece.size
                    largestPiece = maxOf(largestPiece, piece.size)
                    if (heard % (16L shl 20) < piece.size) mostHeld = maxOf(mostHeld, heapInUse() - before)
                }
                "a long meeting"
            })
            assertEquals(0L, pass.failed)
            assertEquals(pcmBytes, heard, "every sample reached the engine")
            assertEquals(pcmBytes / 2, samples, "the engine was told how long the recording is")
            assertTrue(largestPiece <= PCM_PIECE_BYTES, "pieces of $largestPiece bytes")
            assertTrue(mostHeld < 24L shl 20, "the provider held ${mostHeld shr 20} MB while the engine listened")
        }
    }

    @Test
    fun wavPiecesTakeOffTheHeaderAndKeepEverySampleWhole() {
        val wav = ByteArray(WAV_HEADER_BYTES + 1_001) { (it * 7).toByte() }
        "RIFF".toByteArray().copyInto(wav)
        val sizes = listOf(10, 40, 7, 600, 3, 385)
        var at = 0
        val chunks = sizes.map { size -> wav.copyOfRange(at, at + size).also { at += size } }
        val pieces = wavPieces(chunks.asSequence()).toList()
        assertTrue(pieces.all { it.size % 2 == 0 && it.isNotEmpty() }, pieces.map { it.size }.toString())
        val joined = pieces.fold(ByteArray(0)) { all, piece -> all + piece }
        assertEquals(wav.copyOfRange(WAV_HEADER_BYTES, WAV_HEADER_BYTES + 1_000).toList(), joined.toList())

        val notAWav = wav.copyOf().also { "RIFX".toByteArray().copyInto(it) }
        assertFailsWith<IllegalArgumentException> { wavPieces(sequenceOf(notAWav)).toList() }
        assertFailsWith<IllegalArgumentException> { wavPieces(sequenceOf(wav.copyOf(30))).toList() }
    }

    @Test
    fun aWholeRecordingModelHearsALongRecordingInSegmentsCutWhereItIsQuiet() {
        val rate = WAV_SAMPLE_RATE
        // Two and a half minutes of sound with a third of a second of
        // quiet at 52 s: the first cut belongs in that quiet, not at 60 s
        // in the middle of a word.
        val sound = FloatArray(150 * rate) { i -> if (i in 52 * rate until 52 * rate + rate / 3) 0f else ((i % 50) - 25) / 25f }
        val segments = mutableListOf<Pair<Long, FloatArray>>()
        val cutter = QuietSegments { samples, start -> segments += start to samples }
        var at = 0
        while (at < sound.size) {
            val to = minOf(at + 32_768, sound.size)
            cutter.push(sound.copyOfRange(at, to))
            at = to
        }
        cutter.finish()
        assertTrue(segments.all { it.second.size <= SEGMENT_SAMPLES }, segments.map { it.second.size }.toString())
        val firstCut = segments[1].first
        assertTrue(firstCut in 52L * rate..52L * rate + rate / 3, "cut at sample $firstCut")
        var expected = 0L
        for ((start, samples) in segments) {
            assertEquals(expected, start, "segments follow on with nothing between")
            expected += samples.size
        }
        val joined = segments.fold(FloatArray(0)) { all, (_, samples) -> all + samples }
        assertTrue(sound.contentEquals(joined), "every sample is heard once")
    }
}
