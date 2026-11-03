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
            val pass = runNemoJobs(gilune, { recording -> heard = recording.pieces.sumOf { it.size }; "remember the milk" })
            assertEquals(1L, pass.transcribed)
            assertEquals(WAV_SAMPLE_RATE * 2, heard, "the engine heard the samples, not the header")
            assertEquals("remember the milk", gilune.objectText(source))
            assertEquals(0L, runNemoJobs(gilune, { error("nothing left to hear") }).claimed)
        }
    }

    // -------------------------------------------------------- progress

    @Test
    fun aRecordingSaysHowFarThroughItTheEngineIs() {
        val recording = RecordingPcm.of(ByteArray(PCM_PIECE_BYTES * 3 + 1_000)).reporting { said += it }
        assertEquals(emptyList(), said, "nothing is said before the engine starts")
        val whenHanded = mutableListOf<Int>()
        for (piece in recording.pieces) whenHanded += said.last()
        // A piece counts once the engine has finished with it and asks for
        // the next: the first is handed over at 0%, never at its own share.
        assertEquals(0, whenHanded.first())
        assertTrue(whenHanded.all { it < 100 }, "100% only once the last piece is heard: $whenHanded")
        assertEquals(100, said.last())
        assertEquals(said.sorted().distinct(), said, "each figure once, never backwards: $said")
    }

    private val said = mutableListOf<Int>()

    @Test
    fun aRecordingWhoseHeaderUndercountsItStillEndsAtAHundred() {
        // A stream's header cannot know its length (the phone writes
        // 0xFFFFFFFF); the figure caps below 100 until the end is real.
        val recording = RecordingPcm(10, RecordingPcm.of(ByteArray(PCM_PIECE_BYTES * 2)).pieces).reporting { said += it }
        recording.pieces.forEach { }
        assertEquals(listOf(0, 99, 100), said)
    }

    @Test
    fun theProvidersPassSaysHowFarEachRecordingHasGot() {
        val (_, gilune) = vaultWithNemo()
        gilune.use {
            capture(gilune)
            val pass = runNemoJobs(gilune, { recording -> recording.pieces.forEach { }; "words" }, heard = { said += it })
            assertEquals(1L, pass.transcribed)
            assertEquals(0, said.first())
            assertEquals(100, said.last())
        }
    }

    @Test
    fun aStepIsSaidWithTheEngineByName() {
        assertEquals(
            "transcribing with Parakeet TDT 0.6B v2 · 42%",
            TranscriptionStep.Hearing("Parakeet TDT 0.6B v2", 42).words(),
        )
        assertEquals("transcribed; labelling speakers…", TranscriptionStep.LabellingSpeakers.words())
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
            val recordings = TranscriptionRuntime()
            val live = TranscriptionRuntime()
            val nemo = NemoHost()
            startTranscription(GiluneState(root.path, CoroutineScope(Dispatchers.Default)), gilune, recordings, live, nemo, models)
            assertEquals(TranscriptionPhase.Ready, live.phase, live.failure.orEmpty())
            assertEquals(TranscriptionPhase.Ready, recordings.phase, recordings.failure.orEmpty())
            assertFalse(assertNotNull(nemo.live).resident, "ready means the files are here, not the model in memory")
            assertFalse(assertNotNull(nemo.recordings).resident)
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

    // Switching NeMo on beside whisper no longer switches whisper off (ADR
    // 0033): TranscriptionRolesKernelTest.aProviderSwitchedOnBesideAnotherTakesNoJobs.

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

    @Test
    fun aRecordingsPassLetsItsModelGoUnlessItIsAlsoTheLiveOne() {
        val model = nemoModel("parakeet-tdt-0.6b-v2")
        val dir = realModel(model)
        assumeTrue("set GILUNE_TEST_NEMO_ROOT", dir != null)
        val (_, gilune) = vaultWithNemo()
        gilune.use {
            NemoEngines(model, dir!!).use { engines ->
                val host = NemoHost()
                host.replace(live = null, recordings = engines)
                capture(gilune)
                val steps = mutableListOf<TranscriptionStep>()
                val pass = runRecordingsPass(gilune, host, SpeakerHost(), steps::add)
                assertEquals(1L, pass.claimed, "$pass")
                assertFalse(engines.resident, "the model goes when the pass that needed it ends")
                assertEquals(TranscriptionStep.Hearing(model.name, 100), steps.last())

                // A pass with nothing to hear leaves a warm model alone.
                engines.withOffline { }
                assertEquals(0L, runRecordingsPass(gilune, host, SpeakerHost()) {}.claimed)
                assertTrue(engines.resident, "nothing was heard, so nothing is let go")

                // The same model serving dictation keeps its idle timeout.
                host.replace(live = engines, recordings = engines)
                capture(gilune)
                assertEquals(1L, runRecordingsPass(gilune, host, SpeakerHost()) {}.claimed)
                assertTrue(engines.resident, "the live engine stays warm for the next dictation")
                host.replace(live = null, recordings = null)
            }
        }
    }

    @Test
    fun aModelIsNeverLetGoFromUnderADictation() {
        val model = nemoModel("nemotron-0.6b")
        val dir = realModel(model)
        assumeTrue("set GILUNE_TEST_NEMO_ROOT", dir != null)
        NemoEngines(model, dir!!).use { engines ->
            val session = assertNotNull(engines.liveSession())
            session.accept(ByteArray(3_200))
            engines.letGo()
            assertTrue(engines.resident, "a dictation is listening")
            session.update()
            session.finish()
            engines.letGo()
            assertFalse(engines.resident, "the dictation has ended")

            // A recording heard by the streaming model closes its stream
            // even when it fails part-way, or nothing could let go again.
            val broken = RecordingPcm(3_200, sequence { yield(ByteArray(3_200)); error("the disk went away") })
            assertFailsWith<IllegalStateException> { engines.transcribe(broken) }
            engines.letGo()
            assertFalse(engines.resident, "the failed recording left no stream open")
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
        // quiet at 17 s: the first cut belongs in that quiet, not at 20 s
        // in the middle of a word.
        val sound = FloatArray(150 * rate) { i -> if (i in 17 * rate until 17 * rate + rate / 3) 0f else ((i % 50) - 25) / 25f }
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
        assertTrue(firstCut in 17L * rate..17L * rate + rate / 3, "cut at sample $firstCut")
        // Long segments cost memory the runtime keeps (see SEGMENT_SAMPLES).
        assertTrue(segments.all { it.second.size <= 20 * rate }, "no segment over 20 s")
        var expected = 0L
        for ((start, samples) in segments) {
            assertEquals(expected, start, "segments follow on with nothing between")
            expected += samples.size
        }
        val joined = segments.fold(FloatArray(0)) { all, (_, samples) -> all + samples }
        assertTrue(sound.contentEquals(joined), "every sample is heard once")
    }
}
