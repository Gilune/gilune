// Spike R0 — does Moonshine streaming actually work here, and what does it
// cost? (ADR 0018 § 6.) Report: `spikes/r0-moonshine/README.md`.
//
// SPIKE SCAFFOLDING — see the header of `R0Moonshine.kt`. No product file
// imports any of this and the AAR is scoped to the test source set.
//
// GATING, and why it is shaped this way. Two tests run on any suite invocation
// because they need only the packaged native library: the ABI check and the
// download-manifest measurement. Everything else needs a 77 MB model that the
// suite must never fetch on its own, so it SKIPS via `assumeTrue` unless the
// model is already on the device — the MS1 precedent. The one test that does
// touch the network additionally requires `-e gilune.r0.fetch 1` to be passed
// explicitly, so no CI run and no absent-minded `connectedAndroidTest` can
// start a download.

package org.gilune.app

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.Transcriber
import android.os.Build
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class R0MoonshineSpikeTest {

    companion object {
        // One paced and one unpaced session, shared across the tests that
        // interrogate them, so the model is loaded twice per suite run rather
        // than once per assertion.
        private val pacedRun: R0Run by lazy {
            R0.streamPcm(R0.modelDir()!!, R0.readWav16kMonoS16(R0.speechWav()!!), paced = true)
        }
        private val unpacedRun: R0Run by lazy {
            R0.streamPcm(R0.modelDir()!!, R0.readWav16kMonoS16(R0.speechWav()!!), paced = false)
        }
    }

    private fun assumeSpeech(): File {
        R0.assumeModel()
        val wav = R0.speechWav()
        assumeTrue("R0 SKIP: no speech clip at /data/local/tmp/gilune-r0/speech.wav", wav != null)
        return wav!!
    }

    // ----------------------------------------------------- runs without a model

    /**
     * The first question R0 exists to ask: does any of this load on the ABI
     * every instrumented run uses? `ort` cannot even build for
     * `x86_64-linux-android` on its current release (research § 2.2), so this
     * is not a formality.
     */
    @Test
    fun a01_theAarLoadsAndRunsOnThisAbi() {
        R0.log("device abi=${Build.SUPPORTED_ABIS.joinToString()} sdk=${Build.VERSION.SDK_INT}")
        // `JNI` does not self-load on class init; `ensureLibraryLoaded` runs
        // `System.loadLibrary("moonshine-jni")`, and the platform linker then
        // resolves that library's DT_NEEDED on `libmoonshine.so` and
        // `libonnxruntime.so` out of the same APK lib directory. All three
        // must be present for this call to return — which is what makes it a
        // real check that the packaging works, not just that a class exists.
        val loadStart = System.nanoTime()
        JNI.ensureLibraryLoaded()
        R0.log("System.loadLibrary(moonshine-jni) + deps: ${(System.nanoTime() - loadStart) / 1_000_000}ms")
        val version = JNI.moonshineGetVersion()
        R0.log("moonshineGetVersion()=$version (header ${JNI.MOONSHINE_HEADER_VERSION})")
        assertTrue(version > 0, "the native library loaded but reported no version")
        assertEquals(0, JNI.MOONSHINE_ERROR_NONE)
    }

    /**
     * What the model ask actually costs, measured rather than inferred.
     *
     * `getSttDependencies` is a NATIVE call: the file list lives inside
     * `libmoonshine.so`, which is why the research could not answer this and
     * why the number could only ever come from a device. It returns the same
     * manifest `AssetDownloader` resolves, so parsing it here tells us what
     * would be fetched, from where, and how big — WITHOUT fetching it.
     */
    @Test
    fun a02_theModelDownloadManifestIsMeasuredWithoutFetchingIt() {
        val json = Transcriber.getSttDependencies("en", R0.specOptions())
        R0.log("getSttDependencies(en, model_arch=${R0.ARCH_TINY_STREAMING}) =")
        R0.log(json)

        val root = JSONObject(json)
        val groups = root.optJSONArray("groups")
        assertTrue(groups != null && groups.length() > 0, "manifest declared no groups")

        var total = 0L
        var files = 0
        for (g in 0 until groups!!.length()) {
            val group = groups.getJSONObject(g)
            val base = group.optString("base_url")
            R0.log("group $g base_url=$base")
            val list = group.optJSONArray("files") ?: continue
            for (f in 0 until list.length()) {
                val entry = list.getJSONObject(f)
                val name = entry.optString("name")
                val size = entry.optLong("size")
                total += size
                files++
                R0.log("  %-46s %12d bytes  %8.2f MB".format(name, size, size / 1e6))
            }
        }
        R0.log("MANIFEST TOTAL: $files files, $total bytes = %.2f MB (%.2f MiB)"
            .format(total / 1e6, total / 1048576.0))
        assertTrue(files > 0, "manifest declared no files")
    }

    // ------------------------------------------------------ the one network test

    /**
     * Requires `-e gilune.r0.fetch 1`. Downloads streaming-tiny once and
     * measures what landed on disk, which is the check on the manifest above:
     * a declared size and a delivered size are different claims.
     */
    @Test
    fun a03_fetchTheStreamingTinyModel() {
        assumeTrue("R0 SKIP: pass -e gilune.r0.fetch 1 to allow the download", R0.fetchRequested())
        // R1 dropped okhttp (ADR 0018 § 4's consent flow needs our own
        // downloader, and R0 condition 4 wanted the dependency gone), so
        // the vendor's `AssetDownloader` cannot run in this tree any more.
        // The measurement it made is recorded in the spike README and is
        // not re-runnable here — which is the honest state of this test
        // rather than a failure. `MoonshineDownloadTest` covers what R1
        // fetches instead.
        assumeTrue(
            "R0 SKIP: okhttp was excluded in R1, so the vendor AssetDownloader is unreachable " +
                "(see spikes/r0-moonshine/README.md § The transitive dependencies)",
            runCatching { Class.forName("okhttp3.OkHttpClient") }.isSuccess,
        )
        val root = R0.downloadRoot().apply { mkdirs() }
        val start = System.nanoTime()
        val dir = AssetDownloader().ensureModelPresent(root, R0.spec()) { path, index, count, got, want ->
            if (got == want) R0.log("fetched [$index/$count] $path ($got bytes)")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val files = dir.walkTopDown().filter { it.isFile }.toList().sortedBy { it.name }
        val total = files.sumOf { it.length() }
        R0.log("downloaded into ${dir.absolutePath} in ${elapsedMs}ms")
        files.forEach { R0.log("  %-46s %12d bytes  %8.2f MB".format(it.name, it.length(), it.length() / 1e6)) }
        R0.log("ON-DISK TOTAL: ${files.size} files, $total bytes = %.2f MB (%.2f MiB)"
            .format(total / 1e6, total / 1048576.0))
        R0.log("cache key = ${ModelCache.key(R0.spec())}")
        assertTrue(files.isNotEmpty(), "the downloader reported success but wrote nothing")
    }

    // ------------------------------------------------------------- the machinery

    /**
     * Session init: what a shell pays before it can listen at all. R1 would
     * pay this on the capture screen, so it is a UI budget, not a footnote.
     */
    @Test
    fun a04_sessionInitialisationCost() {
        val dir = R0.assumeModel()
        val cold = R0.streamPcm(dir, R0.silence(200), paced = false)
        val warm = R0.streamPcm(dir, R0.silence(200), paced = false)
        R0.log("session init: cold load=${cold.loadMs}ms streamOpen=${cold.streamOpenMs}ms | " +
            "warm load=${warm.loadMs}ms streamOpen=${warm.streamOpenMs}ms")
        assertTrue(cold.loadMs > 0, "load reported no time at all")
    }

    /**
     * The gate figure. Audio arrives at the rate a person speaks it; the
     * question is how long after they start talking the first word appears,
     * and how far behind the speaker that word is.
     */
    @Test
    fun a05_timeToFirstTokenOnPacedSpeech() {
        assumeSpeech()
        val run = pacedRun
        val first = run.firstText
        R0.log("PACED: TTFT=${run.ttftMs}ms lag=${run.firstTextLagMs}ms " +
            "firstText=\"${first?.text}\" audioFedAtFirstText=${first?.audioFedMs}ms")
        assertTrue(first != null, "no LineTextChanged with text ever arrived — streaming did not work")
        assertTrue(run.finalText.isNotBlank(), "the session produced no completed text")
    }

    /**
     * The floor underneath that: fed flat out, how much audio must the engine
     * see before it will commit to a word? Paced TTFT can never beat this.
     */
    @Test
    fun a06_theMachineryFloorFedFlatOut() {
        assumeSpeech()
        val run = unpacedRun
        R0.log("UNPACED: firstText at wall=${run.ttftMs}ms after audioFed=" +
            "${run.firstText?.audioFedMs}ms; whole ${run.audioMs}ms clip decoded in " +
            "${run.decodeWallMs}ms (rtf=%.3f)".format(run.decodeWallMs.toDouble() / run.audioMs))
        assertTrue(run.firstText != null, "no partial arrived even fed flat out")
    }

    /**
     * The caption feed's shape, as ADR 0018 § 1 must render it: which events
     * arrive, in what order, how often, and — the load-bearing claim — whether
     * `LineCompleted` really does freeze a line.
     */
    @Test
    fun a07_theLineLifecycleAsActuallyReceived() {
        assumeSpeech()
        val run = pacedRun
        val kinds = run.events.groupingBy { it.kind }.eachCount()
        R0.log("event tally: $kinds")

        val byLine = run.events.filter { it.lineId >= 0 }.groupBy { it.lineId }
        byLine.forEach { (id, events) ->
            val revisions = events.filter { it.kind == "LineTextChanged" }
            val completedAt = events.indexOfFirst { it.kind == "LineCompleted" }
            R0.log("line $id: ${events.size} events, ${revisions.size} text revisions, " +
                "completedAtIndex=$completedAt")
            revisions.forEach { R0.log("    +${it.atMs}ms \"${it.text}\"") }

            // The one guarantee the vendor gives, checked rather than trusted:
            // "once LineCompleted has been called, the library will never alter
            // that line's text again". A caption that renders completed lines
            // in the settled role depends on it entirely.
            if (completedAt >= 0) {
                val frozen = events[completedAt].text
                events.drop(completedAt + 1).forEach {
                    assertEquals(frozen, it.text, "line $id changed after LineCompleted (${it.kind})")
                }
            }
        }

        // Cadence: the gap between consecutive callbacks is what a UI redraws on.
        val gaps = run.events.zipWithNext { a, b -> b.atMs - a.atMs }.filter { it >= 0 }
        if (gaps.isNotEmpty()) {
            R0.log("cadence ms: min=${gaps.min()} median=${gaps.sorted()[gaps.size / 2]} max=${gaps.max()}")
        }
        assertTrue(run.completed.isNotEmpty(), "no line was ever completed")
    }

    /**
     * The control. Silence must produce no words: a caption that invents
     * speech during a quiet recording is worse than no caption, and it is the
     * failure mode whisper already shows us with `[BLANK_AUDIO]`
     * (ADR 0018 § Consequences).
     */
    @Test
    fun a08_silenceProducesNoWords() {
        val dir = R0.assumeModel()
        val run = R0.streamPcm(dir, R0.silence(5_000), paced = false)
        val spoken = run.completed.map { it.text.trim() }.filter { it.isNotEmpty() }
        R0.log("silence: ${run.events.size} events, completed lines with text = $spoken")
        assertTrue(spoken.isEmpty(), "five seconds of digital silence produced words: $spoken")
    }

    /**
     * Does the caller's chunk size change what the caption sees? The
     * decompiled `isUpdateDue` says no — cadence is governed by
     * `updateInterval`, not by how the shell slices its buffer — which matters
     * because our recorder's chunking is fixed by the capture path, not by
     * the transcriber.
     */
    @Test
    fun a09_chunkSizeDoesNotGovernCadence() {
        assumeSpeech()
        val dir = R0.modelDir()!!
        val pcm = R0.readWav16kMonoS16(R0.speechWav()!!)
        val fine = R0.streamPcm(dir, pcm, chunkMs = 20, paced = false)
        R0.log("chunk 20ms: events=${fine.events.size} completed=${fine.completed.size} " +
            "text=\"${fine.finalText}\"")
        R0.log("chunk 100ms: events=${unpacedRun.events.size} completed=${unpacedRun.completed.size} " +
            "text=\"${unpacedRun.finalText}\"")
        assertTrue(fine.finalText.isNotBlank(), "20 ms chunks produced nothing")
    }

    /**
     * ADR 0018 § 6 sets the bar at "TTFT under about a second", and the
     * default configuration lands just the wrong side of it here. So the
     * question the gate actually needs answering is whether that is a property
     * of the ENGINE or of one tunable: `transcription_interval` defaults to
     * 0.5 s, and nothing in the ADR chose that number.
     *
     * The trade is real and this is what measures it — a shorter interval
     * means more decode passes over the same audio, so the caption is prompter
     * and the battery bill is larger.
     */
    @Test
    fun a10_updateIntervalMovesTimeToFirstToken() {
        assumeSpeech()
        val dir = R0.modelDir()!!
        val pcm = R0.readWav16kMonoS16(R0.speechWav()!!)
        listOf(0.5, 0.25, 0.1).forEach { interval ->
            val run = R0.streamPcm(dir, pcm, paced = true, updateInterval = interval)
            R0.log("INTERVAL ${interval}s -> TTFT=${run.ttftMs}ms lag=${run.firstTextLagMs}ms " +
                "audioAtFirstText=${run.firstText?.audioFedMs}ms events=${run.events.size} " +
                "decodeWall=${run.decodeWallMs}ms rtf=%.3f firstText=\"${run.firstText?.text}\""
                    .format(run.decodeWallMs.toDouble() / run.audioMs))
            assertTrue(run.finalText.isNotBlank(), "interval ${interval}s produced no text")
        }
    }
}
