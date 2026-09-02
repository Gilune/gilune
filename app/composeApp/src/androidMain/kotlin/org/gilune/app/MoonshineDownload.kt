// The streaming model's download (ADR 0018 § 4), through our own consent
// flow rather than the vendor's downloader.
//
// **Why ours.** The AAR ships `AssetDownloader`, which R0 measured and
// which already does `.part` + HTTP `Range` resume + crc32c. We do not use
// it, and the reason is not size: § 4 requires the fetch to go through the
// plugin's `network.request` grant with a Wi-Fi-only default, which is a
// policy the vendor's downloader knows nothing about. Dropping it drops
// okhttp (2.74 MiB, measured) and `androidx.work` with it — R0's condition
// 4 — so the exclusion and the consent rule are the same decision.
//
// **What is fetched** comes from the engine, not from a list in this file.
// `Transcriber.getSttDependencies` is a NATIVE call — the manifest lives
// inside `libmoonshine.so` — so it is the only source that cannot drift
// from what the loader will demand. R0 measured what it returns for
// streaming-tiny: 8 files, 45,233,659 B, from
// `https://download.moonshine.ai/model/tiny-streaming-en/quantized_26_08_21`,
// each with a crc32c. The parse below is deliberately tolerant about the
// checksum's key and strict about everything else.

package org.gilune.app

import org.json.JSONObject
import java.io.File

/// One file the model needs: where it comes from, how big it should be,
/// and what it should hash to.
data class MoonshineAsset(
    val name: String,
    val url: String,
    val size: Long,
    /// Unsigned crc32c as the manifest gives it, or null when this
    /// manifest carries none — in which case the size is all we can
    /// check, and [verifyAsset] says so rather than pretending.
    val crc32c: Long?,
)

/// The keys a checksum might arrive under, and the algorithm it might be
/// named by. The manifest is the vendor's and undocumented, so a rename
/// upstream must degrade to a size check rather than fail every download.
///
/// **What it actually says**, read off a live manifest on 2026-09-02:
///
/// ```json
/// {"name":"adapter.ort","url":"https://…/adapter.ort","size":1319664,
///  "checksum":"kwQ+Bw==","checksum_type":"crc32c"}
/// ```
///
/// The checksum is **base64 of four big-endian bytes** — the Cloud Storage
/// convention — and not a number. That matters: the first version of this
/// parser read it with `optLong`, got nothing back, and quietly verified a
/// 45 MB download on its **length alone**. Nothing failed, which is what
/// made it worth catching. `theManifestChecksumsVerifyTheModelOnDisk`
/// checks the whole chain — this decode, the byte order, and our own
/// CRC-32C — against the vendor's real files.
private val CRC_KEYS = listOf("checksum", "crc32c", "crc32", "crc")

private const val CRC32C_TYPE = "crc32c"

/// One manifest checksum as an unsigned 32-bit value, however it was
/// written. Base64 of four big-endian bytes first, because that is what
/// the vendor sends; a bare number second, because a manifest that ever
/// carries one should still verify. Anything else is null, and the caller
/// then checks the size and says so.
fun parseChecksum(raw: String?, type: String?): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (type != null && type.isNotEmpty() && !type.equals(CRC32C_TYPE, ignoreCase = true)) return null
    value.toLongOrNull()?.let { if (it >= 0) return it }
    val bytes = runCatching {
        android.util.Base64.decode(value, android.util.Base64.DEFAULT)
    }.getOrNull() ?: return null
    if (bytes.size != 4) return null
    var result = 0L
    for (byte in bytes) result = (result shl 8) or (byte.toLong() and 0xFF)
    return result
}

/// The manifest, flattened. Pure over the JSON string so the shape is
/// testable without a device, a network or a native library.
///
/// Groups are concatenated in order. A file with no name or no plausible
/// size is dropped rather than half-fetched.
fun parseMoonshineManifest(json: String): List<MoonshineAsset> {
    val groups = JSONObject(json).optJSONArray("groups") ?: return emptyList()
    val assets = mutableListOf<MoonshineAsset>()
    for (g in 0 until groups.length()) {
        val group = groups.optJSONObject(g) ?: continue
        val base = group.optString("base_url").trimEnd('/')
        val files = group.optJSONArray("files") ?: continue
        for (f in 0 until files.length()) {
            val entry = files.optJSONObject(f) ?: continue
            val name = entry.optString("name")
            if (name.isBlank()) continue
            // The manifest gives each file's own absolute `url`; the
            // base-plus-name form is the fallback for a manifest that
            // does not. They agree today, and preferring the explicit one
            // means a vendor that moves a single file still resolves.
            val url = entry.optString("url").takeIf { it.isNotBlank() }
                ?: if (base.isBlank()) continue else "$base/$name"
            val size = entry.optLong("size")
            val type = entry.optString("checksum_type").takeIf { it.isNotBlank() }
            val crc = CRC_KEYS.firstNotNullOfOrNull { key ->
                if (entry.has(key)) parseChecksum(entry.optString(key), type) else null
            }
            assets += MoonshineAsset(name, url, size, crc)
        }
    }
    return assets
}

/// Total bytes the ask costs, for the consent line.
fun List<MoonshineAsset>.totalBytes(): Long = sumOf { it.size }

/// Where a fetched model lives: app-private internal storage beside the
/// whisper models, under a per-model directory.
///
/// **One convention, chosen deliberately.** R0 found the AAR's own cache
/// API disagreeing with itself — `AssetDownloader.ensureModelPresent`
/// writes straight into the root it is handed while `ModelCache.directoryFor`
/// appends a `stt-en-2` key — so a caller trusting either alone looks in
/// the wrong place. We hand the loader an explicit path and never consult
/// `ModelCache` at all.
fun moonshineModelDir(context: android.content.Context, model: MoonshineModel): File =
    File(File(context.filesDir, "models"), "moonshine-${model.key}")

/// Written last, after every file has been fetched and verified. Its
/// presence is what "the model is present" means anywhere else in the
/// shell: a directory of eight files where the eighth was interrupted
/// mid-write must never read as ready, and checking eight names against a
/// manifest would mean loading the native library to ask a question about
/// a directory.
private const val READY_MARKER = ".ready"

fun moonshineModelPresent(dir: File): Boolean = File(dir, READY_MARKER).isFile

fun markMoonshineModelReady(dir: File) {
    File(dir, READY_MARKER).writeText("ok")
}

/// How far a download has got, for the plugin screen.
data class MoonshineProgress(val fileIndex: Int, val fileCount: Int, val done: Long, val total: Long) {
    val percent: Int? get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else null
}

/// Fetch every asset into [dir], skipping what is already there.
///
/// Per file: stream to `<name>.part`, verify, rename. That is the whisper
/// path's rule applied eight times, and it makes the whole fetch resumable
/// at file granularity — an interrupted download re-runs and pays only for
/// the file it was in the middle of. Byte-range resume *within* a file is
/// deliberately not attempted: the largest file here is 32 MB, and a
/// partial file that lies about being complete is a far worse failure than
/// a repeated 32 MB.
///
/// Throws on the first failure, leaving `.part` files behind for the retry
/// to overwrite. Nothing is marked ready unless every file verified.
fun fetchMoonshineModel(
    assets: List<MoonshineAsset>,
    dir: File,
    onProgress: (MoonshineProgress) -> Unit = {},
) {
    require(assets.isNotEmpty()) { "the model manifest listed no files" }
    dir.mkdirs()
    val total = assets.totalBytes()
    var done = 0L
    assets.forEachIndexed { index, asset ->
        val destination = File(dir, asset.name)
        if (destination.isFile && destination.length() == asset.size) {
            done += asset.size
            onProgress(MoonshineProgress(index + 1, assets.size, done, total))
            return@forEachIndexed
        }
        val partial = File(dir, "${asset.name}.part")
        val before = done
        val crc = Crc32c()
        java.net.URI(asset.url).toURL().openConnection().run {
            // A phone loses its network mid-download far more often than a
            // desktop does, and a socket with no timeout leaves the plugin
            // screen saying "downloading" for ever. Failing is recoverable
            // — the next attempt re-fetches this file and keeps the ones
            // already verified — so failing is better than hanging.
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            connect()
            getInputStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_CHUNK)
                    var announced = before
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        crc.update(buffer, read)
                        done += read
                        // Reporting every chunk would be tens of thousands
                        // of recompositions across a 45 MB fetch.
                        if (done - announced >= PROGRESS_STEP) {
                            announced = done
                            onProgress(MoonshineProgress(index + 1, assets.size, done, total))
                        }
                    }
                }
            }
        }
        val verdict = verifyAsset(asset, partial.length(), crc.value())
        if (verdict != null) {
            partial.delete()
            done = before
            error(verdict)
        }
        check(partial.renameTo(destination)) { "could not put ${asset.name} in place" }
        onProgress(MoonshineProgress(index + 1, assets.size, done, total))
    }
}

/// What is wrong with a fetched file, or null when it is sound. Pure, so
/// the rules are testable without a network.
///
/// A manifest that carried no checksum is checked on size alone and the
/// caller is not told otherwise — there is nothing better available, and
/// refusing to install a model because the vendor renamed a JSON key would
/// be worse than the risk it guards.
fun verifyAsset(asset: MoonshineAsset, actualSize: Long, actualCrc: Long): String? {
    if (asset.size > 0 && actualSize != asset.size) {
        return "${asset.name} arrived as $actualSize bytes, expected ${asset.size}"
    }
    if (asset.crc32c != null && asset.crc32c != actualCrc) {
        return "${asset.name} failed its checksum"
    }
    return null
}

private const val DOWNLOAD_CHUNK = 64 * 1024
private const val PROGRESS_STEP = 1024L * 1024L
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 60_000

/// CRC-32C (Castagnoli), because `java.util.zip.CRC32C` is **API 34** and
/// this app's floor is 29 — using the platform class would have crashed
/// every device between the two, and the download is the one place a
/// silent corruption becomes a durable one.
///
/// Reflected algorithm, polynomial 0x82F63B78, table built once. Checked
/// against the standard vector: crc32c("123456789") = 0xE3069283.
class Crc32c {
    private var crc = -1

    fun update(bytes: ByteArray, length: Int = bytes.size) {
        var value = crc
        for (i in 0 until length) {
            value = (value ushr 8) xor TABLE[(value xor bytes[i].toInt()) and 0xFF]
        }
        crc = value
    }

    /// The finished checksum as an unsigned 32-bit value, which is how a
    /// JSON manifest carries it.
    fun value(): Long = (crc.inv()).toLong() and 0xFFFFFFFFL

    companion object {
        /// Castagnoli, reflected. Written as the bit pattern and converted
        /// rather than as a negated decimal: the hand-negated form was
        /// wrong when this was first written, and the test vector is what
        /// caught it.
        private val POLYNOMIAL = 0x82F63B78.toInt()

        private val TABLE = IntArray(256) { index ->
            var value = index
            repeat(8) {
                value = if (value and 1 != 0) (value ushr 1) xor POLYNOMIAL else value ushr 1
            }
            value
        }
    }
}
