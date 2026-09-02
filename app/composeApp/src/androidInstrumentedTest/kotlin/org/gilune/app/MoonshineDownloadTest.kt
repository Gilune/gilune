// The caption model's download (ADR 0018 § 4), tested without a network.
//
// The manifest fixture below is R0's measured one — the real base URL, the
// real eight file names and the real sizes summing to 45,233,659 B. Using
// the measurement as the fixture is deliberate: if the vendor's shape ever
// changes, this file should be updated from a new device measurement
// (`R0MoonshineSpikeTest.a02` logs the raw JSON) rather than adjusted to
// keep the parser green.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoonshineDownloadTest {
    private val base = "https://download.moonshine.ai/model/tiny-streaming-en/quantized_26_08_21"

    /// **The vendor's real manifest**, copied from a live
    /// `getSttDependencies` on 2026-09-02 (only the entry order and
    /// whitespace differ). Using the real thing as the fixture is the
    /// whole point: the first version of this parser was written against
    /// an invented one with numeric checksums, and it silently verified
    /// nothing.
    private val manifest = """
        {"groups":[{"base_url":"$base","files":[
          {"name":"adapter.ort","url":"$base/adapter.ort","size":1319664,
           "checksum":"kwQ+Bw==","checksum_type":"crc32c"},
          {"name":"cross_kv.ort","url":"$base/cross_kv.ort","size":1287544,
           "checksum":"76wzFQ==","checksum_type":"crc32c"},
          {"name":"decoder_kv.ort","url":"$base/decoder_kv.ort","size":32583720,
           "checksum":"KJjeNw==","checksum_type":"crc32c"},
          {"name":"encoder.ort","url":"$base/encoder.ort","size":7675440,
           "checksum":"UjAIpQ==","checksum_type":"crc32c"},
          {"name":"frontend.model.ort","url":"$base/frontend.model.ort","size":23344,
           "checksum":"aM/+wQ==","checksum_type":"crc32c"},
          {"name":"frontend.weights.ort","url":"$base/frontend.weights.ort","size":2093464,
           "checksum":"WYZ7EQ==","checksum_type":"crc32c"},
          {"name":"streaming_config.json","url":"$base/streaming_config.json","size":509,
           "checksum":"HGL0Ug==","checksum_type":"crc32c"},
          {"name":"tokenizer.bin","url":"$base/tokenizer.bin","size":249974,
           "checksum":"B7s10Q==","checksum_type":"crc32c"}
        ]}]}
    """.trimIndent()

    // ---------------------------------------------------------- the manifest

    @Test
    fun theManifestIsTheEightFilesR0Measured() {
        val assets = parseMoonshineManifest(manifest)
        assertEquals(8, assets.size)
        assertEquals(45_233_659L, assets.totalBytes(), "the consent ask is not what R0 measured")
        assertEquals("adapter.ort", assets.first().name)
        assertEquals("$base/adapter.ort", assets.first().url)
    }

    @Test
    fun everyRealFileArrivesWithAChecksumWeCanRead() {
        // The bug this test exists for: `optLong` on a base64 string
        // returns nothing, every `crc32c` came back null, and a 45 MB
        // download was verified on its LENGTH alone — silently, with
        // nothing failing. A parser that reads the vendor's real manifest
        // and finds no checksums is broken however green it looks.
        val assets = parseMoonshineManifest(manifest)
        assertTrue(assets.all { it.crc32c != null }, "a real manifest entry produced no checksum")
        assertEquals(0x93043E07L, assets.first { it.name == "adapter.ort" }.crc32c)
        assertEquals(0x2898DE37L, assets.first { it.name == "decoder_kv.ort" }.crc32c)
    }

    @Test
    fun theChecksumIsBase64OfFourBigEndianBytes() {
        // "kwQ+Bw==" is 0x93 0x04 0x3E 0x07 — the Cloud Storage
        // convention, big-endian, and the byte order is not a guess: the
        // instrumented `theManifestChecksumsVerifyTheModelOnDisk` hashes
        // the vendor's own 45 MB of files against these values.
        assertEquals(0x93043E07L, parseChecksum("kwQ+Bw==", "crc32c"))
        assertEquals(0L, parseChecksum("AAAAAA==", "crc32c"))
        assertEquals(0xFFFFFFFFL, parseChecksum("/////w==", "crc32c"))

        // A bare number still verifies, so a manifest that ever carries
        // one is not silently unchecked.
        assertEquals(1234L, parseChecksum("1234", "crc32c"))
        assertEquals(1234L, parseChecksum("1234", null))

        // Anything we cannot read, or an algorithm we do not implement,
        // is null — and `verifyAsset` then falls back to the size rather
        // than failing every download.
        assertNull(parseChecksum("kwQ+Bw==", "md5"), "an md5 must not be read as a crc32c")
        assertNull(parseChecksum("", "crc32c"))
        assertNull(parseChecksum(null, "crc32c"))
        assertNull(parseChecksum("nonsense!!", "crc32c"))
        assertNull(parseChecksum("AAAAAAAA", "crc32c"), "six bytes is not a crc32c")
    }

    @Test
    fun aFilesOwnUrlWinsOverTheBaseAndName() {
        val assets = parseMoonshineManifest(
            """{"groups":[{"base_url":"$base","files":[
               {"name":"a.ort","url":"https://elsewhere.example/a.ort","size":1}]}]}""",
        )
        assertEquals("https://elsewhere.example/a.ort", assets.single().url)
    }

    @Test
    fun aTrailingSlashOnTheBaseDoesNotDoubleUp() {
        val assets = parseMoonshineManifest(
            """{"groups":[{"base_url":"$base/","files":[{"name":"a.ort","size":1}]}]}""",
        )
        assertEquals("$base/a.ort", assets.single().url)
    }

    @Test
    fun aManifestWithNoChecksumsStillInstalls() {
        // The key is the vendor's and undocumented. A rename upstream must
        // degrade to a size check, not fail every download.
        val assets = parseMoonshineManifest(
            """{"groups":[{"base_url":"$base","files":[{"name":"a.ort","size":10}]}]}""",
        )
        assertNull(assets.single().crc32c)
        assertNull(verifyAsset(assets.single(), actualSize = 10, actualCrc = 999))
    }

    @Test
    fun otherSpellingsOfTheChecksumAreAccepted() {
        // The key is the vendor's and undocumented. `checksum` is what it
        // sends today; a rename to the algorithm's own name is the likely
        // shape of a change, and a numeric value is the likely shape of a
        // different vendor.
        val named = parseMoonshineManifest(
            """{"groups":[{"base_url":"$base","files":[{"name":"a.ort","size":10,"crc32c":"kwQ+Bw=="}]}]}""",
        )
        assertEquals(0x93043E07L, named.single().crc32c)

        val numeric = parseMoonshineManifest(
            """{"groups":[{"base_url":"$base","files":[{"name":"a.ort","size":10,"checksum":77}]}]}""",
        )
        assertEquals(77L, numeric.single().crc32c)
    }

    @Test
    fun rubbishInTheManifestIsDroppedRatherThanHalfFetched() {
        assertTrue(parseMoonshineManifest("""{"groups":[]}""").isEmpty())
        assertTrue(parseMoonshineManifest("""{}""").isEmpty())
        assertTrue(
            parseMoonshineManifest("""{"groups":[{"base_url":"","files":[{"name":"a","size":1}]}]}""").isEmpty(),
            "a file with nowhere to come from is not a file",
        )
        assertTrue(
            parseMoonshineManifest("""{"groups":[{"base_url":"$base","files":[{"size":1}]}]}""").isEmpty(),
            "a file with no name is not a file",
        )
    }

    // -------------------------------------------------------- verification

    @Test
    fun aFileThatArrivedWrongIsRefused() {
        val asset = MoonshineAsset("encoder.ort", "$base/encoder.ort", 7_675_440, 42L)
        assertNull(verifyAsset(asset, 7_675_440, 42L))
        assertNotNull(verifyAsset(asset, 7_675_439, 42L), "a short file passed")
        assertNotNull(verifyAsset(asset, 7_675_440, 43L), "a corrupt file passed")
        assertTrue(verifyAsset(asset, 7_675_440, 43L)!!.contains("checksum"))
    }

    // ------------------------------------------------------------- crc32c

    @Test
    fun theChecksumIsCrc32cAndNotSomethingElse() {
        // The standard Castagnoli vector. `java.util.zip.CRC32C` is API 34
        // and this app's floor is 29, so this is ours and must be checked
        // against the published answer rather than against itself.
        val crc = Crc32c()
        crc.update("123456789".toByteArray())
        assertEquals(0xE3069283L, crc.value())
    }

    @Test
    fun anEmptyStreamHashesToZero() {
        assertEquals(0L, Crc32c().value())
    }

    @Test
    fun feedingInPiecesIsTheSameAsFeedingAtOnce() {
        // The download hashes 64 KiB at a time, so this is the property it
        // actually relies on.
        val bytes = ByteArray(1000) { (it * 31).toByte() }
        val whole = Crc32c().apply { update(bytes) }.value()
        val pieces = Crc32c().apply {
            update(bytes.copyOfRange(0, 7))
            update(bytes.copyOfRange(7, 999))
            update(bytes.copyOfRange(999, 1000))
        }.value()
        assertEquals(whole, pieces)
    }

    @Test
    fun theLengthArgumentIsHonoured() {
        // The recorder hands over a reused buffer with a length, and so
        // does the downloader's read loop; hashing the whole array would
        // hash whatever the last read left behind.
        val buffer = ByteArray(16) { it.toByte() }
        val prefix = Crc32c().apply { update(buffer, 4) }.value()
        val exact = Crc32c().apply { update(byteArrayOf(0, 1, 2, 3)) }.value()
        assertEquals(exact, prefix)
    }

    // --------------------------------------------------------- readiness

    @Test
    fun aHalfWrittenModelIsNeverReady() {
        // Eight files where the eighth was interrupted must not read as a
        // model. The marker is written last and only after every file
        // verified, so its presence is the whole answer.
        val dir = java.nio.file.Files.createTempDirectory("gilune-moonshine").toFile()
        java.io.File(dir, "tokenizer.bin").writeText("not really")
        assertTrue(!moonshineModelPresent(dir), "a directory with files in it read as ready")
        markMoonshineModelReady(dir)
        assertTrue(moonshineModelPresent(dir))
    }

    @Test
    fun eachModelGetsItsOwnDirectory() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dir = moonshineModelDir(context, moonshineModel(null))
        assertTrue(dir.absolutePath.startsWith(context.filesDir.absolutePath), "the model must be app-private")
        assertTrue(dir.name.contains(DEFAULT_MOONSHINE_MODEL))
        // Beside the whisper models, not inside them: switching engines
        // must never mean re-downloading either.
        assertEquals(modelsDir(context), dir.parentFile)
    }

    // ------------------------------------------------------------- the PCM

    @Test
    fun theRecordersBytesBecomeTheEnginesSamples() {
        // 16-bit little-endian signed, scaled by 32768 — the same
        // conversion R0's WAV reader did, because feeding the engine
        // anything else is silence that looks like a bug.
        val bytes = byteArrayOf(0, 0, 0x00, 0x40, 0x00, -128, -1, 0x7F)
        val samples = pcm16ToFloat(bytes)
        assertEquals(4, samples.size)
        assertEquals(0f, samples[0])
        assertEquals(0.5f, samples[1], 0.0001f)
        assertEquals(-1f, samples[2], 0.0001f)
        assertEquals(0.99997f, samples[3], 0.0001f)
    }

    @Test
    fun anOddTrailingByteIsIgnoredRatherThanMisread() {
        assertEquals(1, pcm16ToFloat(byteArrayOf(0, 0x40, 0x7F), 3).size)
        assertEquals(0, pcm16ToFloat(ByteArray(0)).size)
    }
}
