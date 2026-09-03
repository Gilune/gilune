// Moonshine as the committed-transcript provider (ADR 0018 § 2, § 3, R3).
//
// Three kinds of test, and the split is deliberate:
//
//  - **Pure.** The decisions — what to do with a claimed job, what document
//    to submit, how the engine's events become a transcript, how a WAV's
//    chunks become samples. No vault, no engine, no model.
//  - **The route.** The whole of claim → stream → submit driven against a
//    **real vault through the real FFI**, with a stand-in engine in place of
//    45 MB of model. Everything the ADR promises about the route — one
//    outcome per capture, a lease the kernel loop does not burn, a failure
//    that retries, words landing in the note's own body — is asserted here,
//    where it can be asserted deterministically.
//  - **Execution.** The real engine over real speech, assume-gated on the
//    model being seeded, so a green run without one proves nothing while
//    looking like it proved everything (the MoonshineCaptionTest rule).

package org.gilune.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// A stand-in for the engine: it answers whatever the test wants, counts
/// how often it was asked, and can throw on demand. It is handed the very
/// same chunk sequence the real decoder gets, so what it sees is what
/// crossed the boundary.
private class StandInEngine(
    private val answer: (ByteArray) -> String,
) : TranscriptEngine {
    var decodes = 0
        private set
    var closed = false
        private set
    var bytesSeen = 0L
        private set
    var chunksSeen = 0
        private set

    override fun decode(chunks: Sequence<ByteArray>): String {
        decodes++
        val whole = ArrayList<Byte>()
        for (chunk in chunks) {
            chunksSeen++
            bytesSeen += chunk.size
            for (byte in chunk) whole.add(byte)
        }
        return answer(whole.toByteArray())
    }

    override fun close() {
        closed = true
    }
}

class MoonshineProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ------------------------------------------------------------ pure

    @Test
    fun aRecordingWithNoWordsIsTheOnlyThingWorthDecoding() {
        assertEquals(
            ProviderAction.Decode,
            providerAction("""{"media":"wav","rel_path":"Captures/a.wav"}"""),
        )
        assertEquals(
            ProviderAction.Decode,
            providerAction("""{"media":"wav","text":""}"""),
            "an empty text is no words at all",
        )
        assertEquals(
            ProviderAction.Decode,
            providerAction("""{"media":"wav","text":"   "}"""),
        )
    }

    @Test
    fun aCaptureThatAlreadyHasWordsIsCompletedWithoutWritingAnything() {
        // § 3's scope, as a decision. This is what stops a provider switch
        // re-decoding a vault: idempotency is keyed by processor id, so a
        // new provider sees every past capture as a fresh job, and this is
        // the answer it gives to the ones that already have words.
        val action = providerAction("""{"media":"wav","text":"remember the milk"}""")
        assertEquals(ProviderAction.NothingToDo("already has words"), action)
    }

    @Test
    fun aCaptureThatIsNotAudioIsNotThisProvidersBusiness() {
        // Whisper's own rule, mirrored: `process` returns no outputs for a
        // subject whose media is not wav, and the job completes.
        assertEquals(
            ProviderAction.NothingToDo("not a recording"),
            providerAction("""{"media":"txt","text":""}"""),
        )
        assertEquals(
            ProviderAction.NothingToDo("not a recording"),
            providerAction("""{}"""),
        )
    }

    @Test
    fun aDocumentThatCannotBeReadIsAFaultAndNotASkip() {
        val action = providerAction("not json at all")
        assertTrue(action is ProviderAction.Fault, "an unreadable document must retry, not complete")
    }

    @Test
    fun theSubmittedDocumentIsTheCapturesOwnWithTheWordsPutIn() {
        // ADR 0013: the transcript revises the subject, so every fact the
        // capture carried — its media, its blob link — must survive. This
        // is whisper's shape, field for field.
        val subject = """{"media":"wav","rel_path":"Captures/2026/a.wav","duration_ms":1400}"""
        val document = JSONObject(transcriptDocument(subject, "remember the milk"))
        assertEquals("remember the milk", document.getString("text"))
        assertEquals("wav", document.getString("media"))
        assertEquals("Captures/2026/a.wav", document.getString("rel_path"))
        assertEquals(1400, document.getInt("duration_ms"))
    }

    @Test
    fun theTranscriptKeepsEveryLineHoweverManyThereAre() {
        // Deliberately not the caption's fold: `CaptionBuffer` keeps the
        // last six lines because a caption is three rows tall, and a
        // transcript that dropped the first half of a long recording would
        // be a data loss dressed as a display cap.
        val collector = TranscriptCollector()
        for (line in 0 until 40) {
            collector.accept(CaptionEvent(CaptionEvent.TEXT_CHANGED, line.toLong(), "line$line", true))
        }
        val text = collector.text()
        assertTrue(text.startsWith("line0 line1"), "the beginning was dropped: $text")
        assertTrue(text.endsWith("line39"))
        assertEquals(40, text.split(" ").size)
    }

    @Test
    fun aSettledLineNeverChangesAndABlankOneNeverOpens() {
        val collector = TranscriptCollector()
        collector.accept(CaptionEvent(CaptionEvent.TEXT_CHANGED, 1, "the quick brown", false))
        collector.accept(CaptionEvent(CaptionEvent.TEXT_CHANGED, 1, "the quick brown fox.", true))
        collector.accept(CaptionEvent(CaptionEvent.TEXT_CHANGED, 1, "nonsense after completion", false))
        collector.accept(CaptionEvent(CaptionEvent.TEXT_CHANGED, 2, "   ", false))
        assertEquals("the quick brown fox.", collector.text())
    }

    @Test
    fun silenceProducesNoWordsAtAll() {
        // R0 measured it: five seconds of digital silence produced zero
        // events. No events means no lines means no transcript — and the
        // loop writes nothing for a blank one.
        assertEquals("", TranscriptCollector().text())
    }

    @Test
    fun theWavHeaderIsReadOffItsFixedOffsetsAndItsSizeFieldIsIgnored() {
        // A streamed capture writes 0xFFFFFFFF into both size fields
        // because a stream cannot know its own length (`Audio.kt`). The
        // kernel's own reader ignores them and so must this one, or every
        // widget recording would be refused.
        val streamed = wavStreamHeader()
        assertNull(canonicalWavComplaint(streamed))
        assertNull(canonicalWavComplaint(wavBytes(RecordedAudio(ByteArray(64), WAV_SAMPLE_RATE, 1, 16))))
    }

    @Test
    fun anUnsupportedWavShapeIsRefusedInTheSameWordsTheKernelUses() {
        val stereo = wavBytes(RecordedAudio(ByteArray(64), 44_100, 2, 16))
        val complaint = canonicalWavComplaint(stereo)
        assertEquals(
            "unsupported WAV shape: 2ch 44100Hz 16bit (expected 1ch 16000Hz 16bit)",
            complaint,
        )
        assertEquals("not a canonical Gilune WAV", canonicalWavComplaint(ByteArray(44)))
    }

    @Test
    fun samplesSurviveAHeaderAndASampleSplitAcrossChunks() {
        // 64 KiB chunk boundaries fall wherever they fall: through the
        // header, and between a sample's two bytes.
        val pcm = ByteArray(1000) { (it % 251).toByte() }
        val wav = wavBytes(RecordedAudio(pcm, WAV_SAMPLE_RATE, 1, 16))
        val whole = WavPcmDecoder().feed(wav)
        assertEquals(500, whole.size)

        val split = WavPcmDecoder()
        val samples = ArrayList<Float>()
        var offset = 0
        // Sizes chosen to cut the header in three and then to leave an odd
        // byte dangling on every subsequent chunk.
        for (size in generateSequence { listOf(20, 20, 7, 13, 101) }.flatten()) {
            if (offset >= wav.size) break
            val end = minOf(offset + size, wav.size)
            for (sample in split.feed(wav.copyOfRange(offset, end))) samples.add(sample)
            offset = end
        }
        assertNull(split.complaint)
        assertEquals(whole.toList(), samples, "a chunk boundary changed the audio")
    }

    // ----------------------------------------------------------- the route

    /// A vault with the provider installed, enabled and registered on this
    /// handle — the state the shell puts it in.
    private fun providerVault(): Gilune {
        val gilune = Gilune.open(scratchVault())
        gilune.registerPlugin(moonshineDeclaration())
        assertTrue(
            registerMoonshineProcessor(gilune, gilune.plugins()),
            "the external processor did not register",
        )
        return gilune
    }

    private fun capture(gilune: Gilune, seconds: Int = 1): String {
        val wav = wavBytes(RecordedAudio(ByteArray(WAV_SAMPLE_RATE * 2 * seconds), WAV_SAMPLE_RATE, 1, 16))
        val writer = gilune.beginCapture("wav")
        var offset = 0
        while (offset < wav.size) {
            val end = minOf(offset + CAPTURE_CHUNK, wav.size)
            writer.writeChunk(wav.copyOfRange(offset, end))
            offset = end
        }
        return writer.finish().sourceId
    }

    @Test
    fun aRecordingBecomesTheWordsOfItsOwnNote() {
        providerVault().use { gilune ->
            val source = capture(gilune)
            val engine = StandInEngine { "remember the milk" }
            val pass = runMoonshineJobs(gilune, { engine })

            assertEquals(1L, pass.transcribed, "the capture was not transcribed")
            assertEquals(0L, pass.failed)
            assertEquals("remember the milk", gilune.objectText(source))

            // ADR 0013: the words are the capture note's own body, and the
            // audio stays linked rather than embedded.
            gilune.project()
            val note = noteFor(gilune, source)
            assertTrue(note.contains("remember the milk"), "the note has no words in it: $note")
            assertTrue(note.contains("gilune-blob:"), "the audio link was lost")

            // The vault can name the engine that wrote them: this plugin
            // declares the processor whose origin the kernel stamps, and
            // it is not whisper's.
            val plugin = gilune.plugins().single { it.pluginId == MOONSHINE_PLUGIN }
            assertEquals(MOONSHINE_PROCESSOR, plugin.processorId)
            assertEquals(SOURCE_READY_EVENT, plugin.subscribes)
            assertEquals(listOf(REVISION_ADDED_EVENT), plugin.emits)
        }
    }

    @Test
    fun theBytesTheEngineSeesAreTheBytesTheKernelCommitted() {
        providerVault().use { gilune ->
            capture(gilune, seconds = 3)
            val engine = StandInEngine { "words" }
            runMoonshineJobs(gilune, { engine })

            // 3 s at the canonical rate is 96 KiB of PCM plus a header —
            // more than one 64 KiB chunk, so the blob genuinely crossed in
            // pieces and was never held whole on either side.
            assertEquals(WAV_HEADER_BYTES + WAV_SAMPLE_RATE * 2 * 3, engine.bytesSeen.toInt())
            assertTrue(engine.chunksSeen > 1, "the blob arrived in one copy: ${engine.chunksSeen}")
            assertTrue(engine.closed, "the model was left resident after the pass")
        }
    }

    @Test
    fun oneRecordingProducesExactlyOneTranscriptionOutcome() {
        // § 3's whole point. One capture, one job, one outcome — and a
        // second pass finds nothing to do rather than writing again.
        providerVault().use { gilune ->
            val source = capture(gilune)
            val first = runMoonshineJobs(gilune, { StandInEngine { "the first words" } })
            assertEquals(1L, first.claimed)
            assertEquals(1L, first.transcribed)

            val second = runMoonshineJobs(gilune, { StandInEngine { "different words" } })
            assertEquals(0L, second.claimed, "a completed job was claimed again")
            assertEquals("the first words", gilune.objectText(source))
        }
    }

    @Test
    fun theKernelsOwnLoopDoesNotBurnTheProvidersLease() {
        // R2 shipped the exclusion; this is the shell wiring it. Without
        // the registration, `runProcessing` claims a job it has no
        // implementation for, and the provider then waits out a lease it
        // never took — costing an attempt on every pass.
        providerVault().use { gilune ->
            capture(gilune)
            repeat(3) { gilune.runProcessing() }
            val claim = gilune.claimJob(MOONSHINE_PROCESSOR)
            assertTrue(claim != null, "the kernel loop took the provider's job")
            claim!!.use {
                assertEquals(1L, it.job().attempts, "an attempt was spent on nothing")
                assertEquals(MOONSHINE_PROCESSOR, it.job().processorId)
            }
        }
    }

    @Test
    fun aCatchUpAfterASwitchTouchesOnlyTheRecordingsWithNoWords() {
        // The switch, as it happens: captures made while a different
        // transcriber was writing already have words, and a catch-up under
        // the new provider must leave them exactly as they are. The
        // catch-up itself is `backfillJobs`, which queues one job per past
        // capture — the scope is the provider's, at claim time.
        Gilune.open(scratchVault()).use { gilune ->
            val worded = capture(gilune)
            val silent = capture(gilune)

            // Words that did not come from this provider: a person typed
            // them into the note, which reconcile ingests as a revision.
            gilune.project()
            val note = File(gilune.vaultPath(), relPath(gilune, worded))
            note.writeText(note.readText().trimEnd() + "\nwritten by hand\n")
            gilune.reconcile()
            assertTrue(
                gilune.objectText(worded)?.contains("written by hand") == true,
                "the fixture is wrong: the capture has no words to protect",
            )

            // Now the provider arrives and the catch-up runs.
            gilune.registerPlugin(moonshineDeclaration())
            registerMoonshineProcessor(gilune, gilune.plugins())
            val wordless = wordlessRecordings(gilune)
            assertEquals(listOf(silent), wordless.map { it.objectId }, "the count the shell shows")

            assertEquals(2L, gilune.backfillJobs(), "the queue is blanket; the writing is scoped")
            val before = gilune.objectText(worded)
            val pass = runMoonshineJobs(gilune, { StandInEngine { "the new engine's words" } })

            assertEquals(2L, pass.claimed)
            assertEquals(1L, pass.transcribed, "only the wordless capture was decoded")
            assertEquals(1L, pass.skipped)
            assertEquals(before, gilune.objectText(worded), "a note that had words was rewritten")
            assertEquals("the new engine's words", gilune.objectText(silent))
            assertTrue(wordlessRecordings(gilune).isEmpty())
        }
    }

    @Test
    fun aBlankTranscriptWritesNothingAtAll() {
        // § Consequences: "a blank transcript writes nothing" — the rule
        // for both engines. Whisper's `[BLANK_AUDIO]` is the wart this
        // avoids; Moonshine's silence is genuinely empty.
        providerVault().use { gilune ->
            val source = capture(gilune)
            val pass = runMoonshineJobs(gilune, { StandInEngine { "   " } })
            assertEquals(1L, pass.skipped)
            assertEquals(0L, pass.transcribed)
            assertTrue(gilune.objectText(source).isNullOrBlank(), "silence became a note body")
            // And the job is done rather than retried forever.
            assertNull(gilune.claimJob(MOONSHINE_PROCESSOR))
        }
    }

    @Test
    fun anEngineThatThrowsFailsTheJobIntoTheShippedBackoff() {
        providerVault().use { gilune ->
            val source = capture(gilune)
            val broken = runMoonshineJobs(
                gilune,
                { StandInEngine { throw IllegalStateException("the engine threw") } },
            )
            assertEquals(1L, broken.failed)
            assertEquals(0L, broken.transcribed)
            assertTrue(gilune.objectText(source).isNullOrBlank(), "a failure wrote something")

            // Not "gone" and not "immediately back": an app-side engine
            // that throws takes the very same backoff-then-quarantine path
            // a Rust processor's `Err` takes, which is one second on the
            // first attempt.
            assertNull(gilune.claimJob(MOONSHINE_PROCESSOR), "the backoff was not applied")
            Thread.sleep(BACKOFF_MS)
            val retry = gilune.claimJob(MOONSHINE_PROCESSOR)
            assertTrue(retry != null, "a failed job did not come back")
            retry!!.use {
                assertEquals(2L, it.job().attempts, "the attempt was not counted")
            }
        }
    }

    @Test
    fun theRetryAfterAFailureLandsTheWords() {
        providerVault().use { gilune ->
            val source = capture(gilune)
            runMoonshineJobs(gilune, { StandInEngine { throw IllegalStateException("no model") } })
            Thread.sleep(BACKOFF_MS)

            val pass = runMoonshineJobs(gilune, { StandInEngine { "the retry succeeds" } })
            assertEquals(1L, pass.transcribed)
            assertEquals("the retry succeeds", gilune.objectText(source))
        }
    }

    @Test
    fun theWidgetPathGetsWordsOnItsOwnHandle() {
        // ADR 0018 § 6 R3: "`RecordService` registering the external
        // processor so widget captures still get words". The service opens
        // a second `Gilune` on the same vault, so it must register the
        // implementation itself — registration is per-handle by design.
        val vault = scratchVault()
        val source = Gilune.open(vault).use { app ->
            app.registerPlugin(moonshineDeclaration())
            capture(app)
        }
        Gilune.open(vault).use { service ->
            // The service's own handle: nothing registered here yet, so a
            // claim is refused outright rather than silently succeeding.
            assertTrue(runCatching { service.claimJob(MOONSHINE_PROCESSOR) }.isFailure)

            assertTrue(registerMoonshineProcessor(service, service.plugins()))
            val pass = runMoonshineJobs(service, { StandInEngine { "spoken into the widget" } })
            assertEquals(1L, pass.transcribed)
            assertEquals("spoken into the widget", service.objectText(source))
        }
    }

    @Test
    fun aDisabledProviderRegistersNothingAndClaimsNothing() {
        Gilune.open(scratchVault()).use { gilune ->
            gilune.registerPlugin(moonshineDeclaration())
            capture(gilune)
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, false)

            assertFalse(registerMoonshineProcessor(gilune, gilune.plugins()))
            // Parked, not destroyed: switching it back on resumes the very
            // same job (ADR 0018 § 3).
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, true)
            assertTrue(registerMoonshineProcessor(gilune, gilune.plugins()))
            assertEquals(1L, runMoonshineJobs(gilune, { StandInEngine { "resumed" } }).transcribed)
        }
    }

    /// **Registering is not choosing** (ADR 0018 § 3, § 6).
    ///
    /// The defect: the kernel defaults a plugin it has never seen to
    /// Enabled, so simply registering the declaration switched Moonshine on
    /// beside whisper — on a fresh install and on an R1 → R3 upgrade alike.
    /// Two transcribers were on at once, the Plugins screen showed both,
    /// and every recording queued a moonshine job that nothing in the
    /// process would ever claim.
    ///
    /// Against a real registry, because the thing being asserted is what
    /// the *kernel* does with an unseen plugin — a fixture would have
    /// agreed with whatever this test assumed.
    @Test
    fun aFreshVaultEndsWithExactlyOneTranscriberOnAndItIsTheIncumbent() {
        Gilune.open(scratchVault()).use { gilune ->
            gilune.registerPlugin(moonshineDeclaration())
            // The state the defect left behind, stated so the fix is
            // visible rather than assumed.
            assertEquals(
                2,
                gilune.plugins().count { it.pluginId in TRANSCRIPTION_PROVIDERS && it.state == "enabled" },
                "the kernel defaults an unseen plugin to enabled — that is why the rule exists",
            )

            val said = applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN)

            val after = gilune.plugins()
            assertEquals(
                1,
                after.count { it.pluginId in TRANSCRIPTION_PROVIDERS && it.state == "enabled" },
                "exactly one transcription plugin is enabled at a time",
            )
            assertEquals(
                TRANSCRIPTION_PLUGIN,
                after.activeTranscriber(),
                "whisper is the incumbent until MS2 has measured both on real hardware",
            )
            assertTrue(said != null && said.contains("One transcriber writes your notes"), "$said")

            // Off means nothing is queued (§ 3), and this is the orphan
            // the defect was quietly accumulating: a recording made while
            // Moonshine is off produces a job for whisper and none for
            // Moonshine. Switching Moonshine on afterwards finds nothing
            // waiting, because enabling resumes parked jobs and never
            // backfills — so the capture below never had one.
            capture(gilune)
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, true)
            assertTrue(registerMoonshineProcessor(gilune, gilune.plugins()))
            assertNull(
                gilune.claimJob(MOONSHINE_PROCESSOR),
                "a capture made while the provider was off must not have queued it a job",
            )
        }
    }

    /// And the rule is idempotent and never undoes a person's choice: a
    /// second open finds nothing to do, and a vault where Moonshine was
    /// chosen keeps it.
    @Test
    fun theRuleRunsOnEveryOpenWithoutTakingBackAChoiceSomebodyMade() {
        Gilune.open(scratchVault()).use { gilune ->
            gilune.registerPlugin(moonshineDeclaration())
            applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN)
            assertNull(applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN), "nothing left to say")

            // Now the person chooses Moonshine, through the switch's own
            // plan. Re-registering — which happens on every open — must
            // leave that alone.
            val switch = providerSwitch(gilune.plugins(), MOONSHINE_PLUGIN, enable = true)
            for (displaced in switch.disable) gilune.setPluginEnabled(displaced, false)
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, true)

            gilune.registerPlugin(moonshineDeclaration())
            assertNull(applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN))
            assertEquals(MOONSHINE_PLUGIN, gilune.plugins().activeTranscriber())
        }
    }

    @Test
    fun switchingProviderIsOneWriteThatLeavesOneTranscriberOn() {
        // The shell rule end to end against a real registry: the switch's
        // plan, applied, leaves exactly one provider enabled.
        Gilune.open(scratchVault()).use { gilune ->
            gilune.registerPlugin(moonshineDeclaration())
            applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN)
            val before = gilune.plugins()
            assertEquals(
                TRANSCRIPTION_PLUGIN,
                before.activeTranscriber(),
                "whisper is the incumbent until somebody chooses otherwise",
            )

            val switch = providerSwitch(before, MOONSHINE_PLUGIN, enable = true)
            for (displaced in switch.disable) gilune.setPluginEnabled(displaced, false)
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, true)

            val after = gilune.plugins()
            assertEquals(MOONSHINE_PLUGIN, after.activeTranscriber())
            assertEquals(
                1,
                after.count { it.pluginId in TRANSCRIPTION_PROVIDERS && it.state == "enabled" },
                "exactly one transcription plugin is enabled at a time",
            )
        }
    }

    @Test
    fun underMoonshineActiveOneRecordingHasExactlyOneTranscriptionOutcome() {
        // § 3's hole closed by the shell rule, end to end: a wav queues one
        // job **per enabled provider**, so the fix is that only one
        // provider is ever enabled. With whisper switched off its job is
        // parked and moonshine's is excluded from the kernel loop, so the
        // capture is transcribed exactly once, by the engine the person
        // chose.
        Gilune.open(scratchVault()).use { gilune ->
            gilune.registerPlugin(moonshineDeclaration())
            val switch = providerSwitch(gilune.plugins(), MOONSHINE_PLUGIN, enable = true)
            for (displaced in switch.disable) gilune.setPluginEnabled(displaced, false)
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, true)
            assertEquals(MOONSHINE_PLUGIN, gilune.plugins().activeTranscriber())
            registerMoonshineProcessor(gilune, gilune.plugins())

            val source = capture(gilune)
            val kernelPass = gilune.runProcessing()
            assertEquals(0L, kernelPass.outputs, "the kernel's loop wrote words it should not have")
            assertEquals(0L, kernelPass.completed)

            val pass = runMoonshineJobs(gilune, { StandInEngine { "one engine, one outcome" } })
            assertEquals(1L, pass.transcribed)
            assertEquals("one engine, one outcome", gilune.objectText(source))

            // And nothing is left claimable for anybody afterwards.
            assertEquals(0L, gilune.runProcessing().outputs)
            assertNull(gilune.claimJob(MOONSHINE_PROCESSOR))
        }
    }

    @Test
    fun theShellRunsTheProviderWhereverItRunsTheKernelsLoop() {
        // The seam that makes any of this reach a person: `runProcessing`
        // deliberately leaves an external processor's jobs alone, so every
        // place `GiluneState` runs the kernel's loop — a capture, a
        // dictation, a sync, a catch-up — must run the provider's too, or
        // the words would arrive only when something else happened to ask.
        val vault = scratchVault()
        Gilune.open(vault).use { gilune ->
            gilune.registerPlugin(moonshineDeclaration())
            registerMoonshineProcessor(gilune, gilune.plugins())
            val source = capture(gilune)

            val state = GiluneState(vault, kotlinx.coroutines.MainScope())
            state.gilune.value = gilune
            state.externalProcessing = { handle ->
                runMoonshineJobs(handle, { StandInEngine { "through the shell's own path" } })
                    .transcribed
            }
            state.backfill()

            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && gilune.objectText(source).isNullOrBlank()) {
                Thread.sleep(100)
            }
            assertEquals("through the shell's own path", gilune.objectText(source))
        }
    }

    // ------------------------------------------------------------ execution

    /// **The real engine, over the committed blob.** Everything above proves
    /// the route; this proves the thing at the end of it. Assume-gated on
    /// the seeded model and R0's speech clip.
    @Test
    fun theRealEngineTranscribesACommittedRecordingIntoItsNote() {
        val model = modelDir()
        assumeTrue("SKIP: no streaming model on this device", model != null)
        val speech = R0.speechWav()
        assumeTrue("SKIP: no speech clip at /data/local/tmp/gilune-r0/speech.wav", speech != null)

        providerVault().use { gilune ->
            val wav = speech!!.readBytes()
            val writer = gilune.beginCapture("wav")
            var offset = 0
            while (offset < wav.size) {
                val end = minOf(offset + CAPTURE_CHUNK, wav.size)
                writer.writeChunk(wav.copyOfRange(offset, end))
                offset = end
            }
            val source = writer.finish().sourceId

            val pass = runMoonshineJobs(
                gilune,
                { MoonshineDecoder(model!!, MOONSHINE_ARCH_TINY_STREAMING) },
            )
            assertEquals(1L, pass.transcribed, "the engine wrote no words")
            val words = gilune.objectText(source).orEmpty()
            android.util.Log.i("GiluneMoonshine", "committed transcript: \"$words\"")
            assertTrue(
                words.contains("fox", ignoreCase = true),
                "the committed transcript is not the words that were spoken: \"$words\"",
            )
            gilune.project()
            assertTrue(noteFor(gilune, source).contains("fox", ignoreCase = true))
        }
    }

    // ------------------------------------------------------------- helpers

    private fun modelDir(): File? {
        val ours = moonshineModelDir(context, moonshineModel(null))
        if (moonshineModelPresent(ours)) return ours
        return R0.modelDir()
    }

    private fun scratchVault(): String = Files.createTempDirectory("gilune-provider").toString()

    private fun relPath(gilune: Gilune, sourceId: String): String =
        gilune.recentSources(50u).single { it.objectId == sourceId }.projectionRelPath
            ?: error("the capture was never projected")

    private fun noteFor(gilune: Gilune, sourceId: String): String =
        File(gilune.vaultPath(), relPath(gilune, sourceId)).readText()

    private companion object {
        /// The kernel's first-attempt backoff (`jobs.rs`: 1,000 ms shifted
        /// by attempts − 1), plus enough slack for an emulator's clock.
        const val BACKOFF_MS = 1_400L
    }
}
