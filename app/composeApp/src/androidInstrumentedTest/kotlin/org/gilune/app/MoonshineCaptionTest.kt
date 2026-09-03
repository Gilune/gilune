// The live caption on a device (ADR 0018 § 1, R1).
//
// Three kinds of test, and the difference is the point:
//
//  - **Execution.** A real streaming session over real speech, driven with
//    our own PCM exactly as the shell drives it. Gated on the model being
//    present and says so when it is not, on the MS1/M4b precedent: a green
//    run with no model would prove nothing while looking like it proved
//    everything.
//  - **Containment.** The rule that makes R1 shippable — a caption that
//    fails, or that never starts, must leave the recording byte-identical.
//    Asserted against the kernel's own receipt, not by inspection.
//  - **Calm.** R0 could not answer ADR 0018 § 6's "does the caption read
//    calmly in Compose" because it was chartered to touch no product
//    surface, and its verdict names that as R1's first obligation. The
//    screenshot test below renders the real block through the real theme
//    and **asserts the block's height never changes** while the words
//    rewrite themselves — which is what calm means here — leaving the PNGs
//    behind as the evidence a person can look at.
//
// Seeding the model, when the app has not downloaded one:
//   the R0 spike's own roots are searched too, so a device that ran R0
//   already has what these tests need.

package org.gilune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoonshineCaptionTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /// Our own model directory first, then the roots R0 used — so a device
    /// that ran the spike can run these without a second 45 MB download.
    private fun captionModelDir(): File? {
        val ours = moonshineModelDir(context, moonshineModel(null))
        if (moonshineModelPresent(ours)) return ours
        return R0.modelDir()
    }

    private fun assumeModel(): File {
        val dir = captionModelDir()
        assumeTrue(
            "SKIP: no streaming model on this device (see spikes/r0-moonshine/README.md for seeding)",
            dir != null,
        )
        return dir!!
    }

    // ----------------------------------------------------------- execution

    /// **The first time the shell's own caption code has run an engine.**
    /// R0 proved the library streams; this proves our session, our fold
    /// and our display do, over the same clip, at the interval R0's
    /// condition 1 requires.
    @Test
    fun theCaptionFillsWithWordsWhileTheSpeechIsStillPlaying() {
        val dir = assumeModel()
        val wav = R0.speechWav()
        assumeTrue("SKIP: no speech clip at /data/local/tmp/gilune-r0/speech.wav", wav != null)

        val display = AtomicReference(CaptionDisplay.EMPTY)
        val failure = AtomicReference<String?>(null)
        val session = MoonshineSession(
            modelDir = dir,
            arch = MOONSHINE_ARCH_TINY_STREAMING,
            onDisplay = { display.set(it) },
            onFailure = { failure.set(it) },
        )
        session.start()

        // Fed at the rate a person speaks, in the recorder's own 4 KiB
        // reads — so what runs here is what runs during a recording, and a
        // queue that cannot keep up would show as dropped words rather
        // than as a passing test.
        val pcm = wav!!.readBytes()
        var offset = WAV_HEADER
        val started = System.currentTimeMillis()
        var firstWordsAt: Long? = null
        while (offset < pcm.size) {
            val end = minOf(offset + RECORDER_CHUNK, pcm.size)
            session.feed(pcm.copyOfRange(offset, end))
            offset = end
            if (firstWordsAt == null && !display.get().isEmpty) {
                firstWordsAt = System.currentTimeMillis() - started
            }
            val owedMs = (offset - WAV_HEADER).toLong() * 1000 / (WAV_SAMPLE_RATE * 2)
            val elapsed = System.currentTimeMillis() - started
            if (owedMs > elapsed) Thread.sleep(owedMs - elapsed)
        }
        // The tail: the last partial settles shortly after the audio ends.
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < deadline && display.get().live.isNotBlank()) Thread.sleep(100)

        val shown = display.get()
        val words = (shown.settled + shown.live).joinToString(" ").trim()
        android.util.Log.i(
            "GiluneCaption",
            "caption after ${(System.currentTimeMillis() - started)}ms: firstWordsAt=${firstWordsAt}ms " +
                "settled=${shown.settled} live=\"${shown.live}\"",
        )
        session.stop()

        assertEquals(null, failure.get(), "the session reported a failure")
        assertTrue(words.isNotBlank(), "the engine produced no caption at all")
        // R0 got all three sentences off this clip on every run. The
        // caption showing them is the whole claim of R1.
        assertTrue(
            words.contains("fox", ignoreCase = true),
            "the caption did not contain the words that were spoken: \"$words\"",
        )
        // Words appeared while audio was still being fed — a caption that
        // only fills at the end is not a live caption.
        assertTrue(firstWordsAt != null, "no words appeared until after the speech had finished")
    }

    // ---------------------------------------------------------- containment

    /// ADR 0018 § 1: "the live path may never fail the recording". The
    /// strongest form of that available without a microphone: the same
    /// bytes committed through the same FFI, once alone and once beside a
    /// caption session that is dying, produce the same content-addressed
    /// blob.
    @Test
    fun aCaptionThatCannotStartLeavesTheRecordingByteIdentical() {
        val audio = spokenShapedSilence()

        val control = Gilune.open(scratchVault()).use { commit(it, audio) }

        // A session over a directory with no model in it: `loadFromFiles`
        // throws, `fail` runs, the caption dies before it ever drew.
        val failure = AtomicReference<String?>(null)
        val nowhere = File(Files.createTempDirectory("gilune-no-model").toString())
        val session = MoonshineSession(
            modelDir = nowhere,
            arch = MOONSHINE_ARCH_TINY_STREAMING,
            onDisplay = {},
            onFailure = { failure.set(it) },
        )
        session.start()
        val withCaption = Gilune.open(scratchVault()).use { gilune ->
            // Feed it exactly as the recorder's tee would, while the
            // capture is being committed.
            var offset = 0
            while (offset < audio.size) {
                val end = minOf(offset + RECORDER_CHUNK, audio.size)
                session.feed(audio.copyOfRange(offset, end))
                offset = end
            }
            commit(gilune, audio)
        }

        assertEquals(control.first, withCaption.first, "the committed blob's digest changed")
        assertEquals(control.second, withCaption.second, "the committed blob's size changed")

        // And it did fail, so the test is asserting what it claims to.
        val deadline = System.currentTimeMillis() + 10_000
        while (failure.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(failure.get() != null, "the session was expected to fail and did not")
        assertFalse(session.alive)
        session.stop()
    }

    @Test
    fun feedingADeadOrStoppedSessionIsHarmless() {
        // The recorder calls `feed` on its own thread with a reused
        // buffer, at whatever moment the session happens to be in. None of
        // those moments may throw — the read loop that keeps the recording
        // going is on the other end of that call.
        val session = MoonshineSession(
            modelDir = File(Files.createTempDirectory("gilune-no-model").toString()),
            arch = MOONSHINE_ARCH_TINY_STREAMING,
            onDisplay = {},
        )
        session.feed(ByteArray(64)) // before start
        session.start()
        session.feed(ByteArray(0)) // empty
        session.feed(ByteArray(1)) // half a sample
        session.stop()
        session.feed(ByteArray(64)) // after stop
        repeat(200) { session.feed(ByteArray(RECORDER_CHUNK)) } // long past the queue's depth
    }

    // ------------------------------------------------------- verification

    /// The whole verification chain against the vendor's own bytes: the
    /// **native** manifest, its base64 checksums, the byte order they are
    /// packed in, and our own CRC-32C — over 45 MB of real model files.
    ///
    /// This is the test the silent-fallback bug needed. The first parser
    /// read the checksum with `optLong`, got null from a base64 string,
    /// and verified the download on its length alone; every unit test
    /// passed and the model installed. Only hashing the real files says
    /// whether any of it works.
    @Test
    fun theManifestChecksumsVerifyTheModelOnDisk() {
        val dir = assumeModel()
        val assets = moonshineManifest(moonshineModel(null))
        assertTrue(assets.isNotEmpty(), "the engine returned no manifest")
        assertTrue(
            assets.all { it.crc32c != null },
            "the live manifest produced no checksums — verification would be size-only",
        )

        var checked = 0
        for (asset in assets) {
            val file = File(dir, asset.name)
            // A directory seeded by the R0 spike may not carry every file
            // under the same name; verify what is there.
            if (!file.isFile) continue
            val crc = Crc32c()
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, read)
                }
            }
            assertEquals(
                null,
                verifyAsset(asset, file.length(), crc.value()),
                "${asset.name} did not verify against the vendor's own checksum",
            )
            checked++
        }
        assertTrue(checked >= 6, "only $checked of ${assets.size} model files were present to verify")
    }

    // -------------------------------------------------------- registration

    /// The provider arrives through the kernel's ordinary registry, and
    /// **the network is never granted by arriving**. § 5: the AAR is
    /// packaged so the code needs no consent beyond the enable switch, the
    /// 45 MB model download is the ask, and it always waits for an explicit
    /// yes.
    ///
    /// R3 changed what else arrives with it. The declaration now names a
    /// processor and two **local** capabilities — `blobs.read` and
    /// `objects.write`, which are what claiming a job and submitting its
    /// words actually do — and those are consented by installing the
    /// application, on the same host policy whisper and the voice adapter
    /// already follow.
    @Test
    fun registeringTheProviderGrantsItsLocalCapabilitiesAndNeverTheNetwork() {
        Gilune.open(scratchVault()).use { gilune ->
            val state = GiluneState(scratchVault(), kotlinx.coroutines.MainScope())
            state.gilune.value = gilune
            registerMoonshine(state, gilune)

            val plugin = gilune.plugins().firstOrNull { it.pluginId == MOONSHINE_PLUGIN }
            assertTrue(plugin != null, "the provider did not register")
            // **Registered and switched off**, because whisper is still the
            // transcriber (ADR 0018 § 3, § 6). The kernel defaults an
            // unseen plugin to Enabled, which for the *second* transcriber
            // means bundling it switched it on for everybody — so the shell
            // switches it back off and says so. Moonshine is opt-in until
            // MS2. `MoonshineProviderTest` holds the rule itself; what
            // matters here is that registration still grants and declares
            // everything below whatever the switch ends up at.
            assertEquals("disabled", plugin!!.state, "registering is not choosing")
            assertEquals(
                listOf("blobs.read", "objects.write", NETWORK_CAPABILITY),
                plugin.capabilities,
            )
            assertEquals(listOf("blobs.read", "objects.write"), plugin.granted.sorted())
            assertEquals(listOf(NETWORK_CAPABILITY), plugin.ungranted)
            assertEquals(MOONSHINE_PROCESSOR, plugin.processorId, "R3 declares the processor")
            assertEquals(SOURCE_READY_EVENT, plugin.subscribes)

            // Registering twice is the idempotent path every bundled
            // declaration uses; it must not double anything, and a
            // capability the installed declaration already named is never
            // granted again — which is what keeps a revoke a revoke.
            gilune.revokeCapability(MOONSHINE_PLUGIN, "blobs.read")
            registerMoonshine(state, gilune)
            assertEquals(1, gilune.plugins().count { it.pluginId == MOONSHINE_PLUGIN })
            assertEquals(
                listOf("objects.write"),
                gilune.plugins().single { it.pluginId == MOONSHINE_PLUGIN }.granted,
                "a revoked capability was re-granted by a re-registration",
            )

            // And nothing is fetched by registering. Switched off, there
            // is no provider to plan for at all — which is the first half
            // of "nothing downloads because the app opened".
            assertEquals(
                MoonshinePlan.NoProvider,
                moonshinePlan(gilune.plugins(), { false }, metered = false),
            )
            // Switched on by a person, with no model on disk, the plan
            // asks for consent rather than reaching for the network.
            val switch = providerSwitch(gilune.plugins(), MOONSHINE_PLUGIN, enable = true)
            for (displaced in switch.disable) gilune.setPluginEnabled(displaced, false)
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, true)
            assertEquals(
                MoonshinePlan.NeedsConsent(moonshineModel(null)),
                moonshinePlan(gilune.plugins(), { false }, metered = false),
            )
        }
    }

    // ---------------------------------------------------------------- calm

    /// R0's condition 2, discharged. The block is rendered through the
    /// app's own theme at successive states of R0's **measured**
    /// trajectory, and the assertion is that its pixel height never
    /// changes: the words rewrite themselves mid-phrase, and nothing on
    /// screen moves because of it.
    ///
    /// The PNGs are left on the device for a person to look at, because
    /// "reads calmly" is finally a human judgement and this test can only
    /// prove the mechanical half of it.
    @Test
    fun theCaptionHoldsStillWhileItsWordsRewriteThemselves() {
        val trajectory = listOf(
            // R0's own trace for line 1, verbatim, then two more lines
            // completing behind it.
            display(changed(1, "The quick, quick, quick,")),
            display(changed(1, "The quick brown fox can")),
            display(changed(1, "The quick brown fox jumps over the lace of the")),
            display(completed(1, "The quick brown fox jumps over the lazy dog.")),
            display(
                completed(1, "The quick brown fox jumps over the lazy dog."),
                changed(2, "Gilune keeps every recording exactly as it was made"),
            ),
            display(
                completed(1, "The quick brown fox jumps over the lazy dog."),
                completed(2, "Jai Yun keeps every recording exactly as it was made."),
                changed(3, "Words should appear while I am still speaking, not after I"),
            ),
        )

        var shown by mutableStateOf(CaptionDisplay.EMPTY)
        var dark by mutableStateOf(false)
        rule.setContent {
            GiluneTheme(dark) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column { CaptionBlock(shown) }
                }
            }
        }

        val heights = mutableSetOf<Int>()
        val out = File(context.getExternalFilesDir(null), "caption").apply { mkdirs() }
        for (theme in listOf(false, true)) {
            rule.runOnIdle { dark = theme }
            trajectory.forEachIndexed { index, state ->
                rule.runOnIdle { shown = state }
                rule.waitForIdle()
                // The words being spoken must be ON SCREEN, not merely
                // composed. The first build of this block anchored its
                // content to the TOP — the height assertion below passed
                // and the live line was clipped out of sight entirely.
                // A screenshot caught it; this is the assertion that
                // would have.
                if (state.live.isNotBlank()) {
                    rule.onNodeWithTag("caption-live").assertIsDisplayed()
                }
                val image = rule.onNodeWithTag("caption").captureToImage()
                heights += image.height
                val name = "caption-${if (theme) "dark" else "light"}-$index.png"
                File(out, name).outputStream().use { stream ->
                    image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
        }
        android.util.Log.i("GiluneCaption", "caption screenshots in ${out.absolutePath}")

        // The mechanical half of "calm": one height, across every state of
        // a mid-phrase rewrite and across both themes. Anything above the
        // caption — the composer, the feed — therefore cannot move.
        assertEquals(1, heights.size, "the caption changed height as its words changed: $heights")
    }

    // --------------------------------------------------------------- helpers

    private fun changed(id: Long, text: String, complete: Boolean = false) =
        CaptionEvent(CaptionEvent.TEXT_CHANGED, id, text, complete)

    private fun completed(id: Long, text: String) = CaptionEvent(CaptionEvent.COMPLETED, id, text, true)

    private fun display(vararg events: CaptionEvent): CaptionDisplay =
        captionDisplay(events.fold(emptyList()) { lines, event -> foldCaption(lines, event) })

    private fun scratchVault(): String = Files.createTempDirectory("gilune-caption").toString()

    /// Two seconds in the canonical capture shape. Silence, deliberately:
    /// this test is about the bytes surviving unchanged, and identical
    /// input is what makes "identical output" mean something.
    private fun spokenShapedSilence(): ByteArray =
        wavBytes(RecordedAudio(ByteArray(WAV_SAMPLE_RATE * 2 * 2), WAV_SAMPLE_RATE, 1, 16))

    /// Commit through the ordinary streamed-capture FFI and answer what
    /// the kernel says it stored.
    private fun commit(gilune: Gilune, wav: ByteArray): Pair<String, ULong> {
        val writer = gilune.beginCapture("wav")
        var offset = 0
        while (offset < wav.size) {
            val end = minOf(offset + CAPTURE_CHUNK, wav.size)
            writer.writeChunk(wav.copyOfRange(offset, end))
            offset = end
        }
        val receipt = writer.finish()
        return receipt.sha256 to receipt.bytes
    }

    private companion object {
        /// The canonical 44-byte PCM header the clip opens with.
        const val WAV_HEADER = 44

        /// `AndroidAudioRecorder`'s own read size, so the pacing here is
        /// the pacing a recording produces.
        const val RECORDER_CHUNK = 4096

        const val SETTLE_MS = 8_000L
    }
}
