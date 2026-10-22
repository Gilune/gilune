// In-app updates on Windows (owner ask, 2026-10-21: "so I don't have to go
// to GitHub, download the MSI and reinstall it each time"). Every tagged
// release already publishes `Gilune-<version>.msi` with a fixed MSI upgrade
// code, so a newer installer replaces the installed one in place; all the
// app has to do is notice, fetch and hand over.
//
// The rules, in the order they bite:
//  - Nothing is asked of the network until the person has said yes once
//    (`Prefs.updateChecks`). "Check now" in About is its own yes.
//  - Only a tagged, non-draft, non-prerelease GitHub release counts
//    (`releases/latest` answers exactly that), and only a strictly newer
//    MAJOR.MINOR.PATCH than this build. A dev build never offers to
//    install over itself.
//  - The installer is downloaded from this repository's release download
//    path only, and verified against the SHA-256 GitHub publishes for the
//    asset before anything runs it. No published digest, no install.
//  - Installing hands the MSI to msiexec from a small script that waits for
//    this process to exit, installs (Windows asks for permission — the app
//    lives in Program Files), and starts Gilune again whatever the
//    installer answered, so a cancelled prompt never leaves you without
//    the app. Every buffer is flushed before the app goes, exactly as on
//    closing the window.

package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/// The repository releases are published to. One place, because the
/// download-path check below is only as good as this string.
const val RELEASE_REPOSITORY = "Gilune/gilune"
private const val LATEST_RELEASE_API = "https://api.github.com/repos/$RELEASE_REPOSITORY/releases/latest"
private const val DOWNLOAD_PREFIX = "https://github.com/$RELEASE_REPOSITORY/releases/download/"

/// How often a running app looks again, once allowed to look at all.
const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

// ------------------------------------------------------------- versions

/// A release version as the tags spell it (`v1.0.9`) and as the build
/// carries it (`1.0.9`). Anything else — `1.0.0-dev`, a pre-release
/// suffix — is not a version updates compare, and parses to null.
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        fun parse(text: String): AppVersion? =
            PATTERN.matchEntire(text.trim())?.destructured?.let { (major, minor, patch) ->
                runCatching { AppVersion(major.toInt(), minor.toInt(), patch.toInt()) }.getOrNull()
            }
    }
}

// ------------------------------------------------------------- releases

/// One downloadable file of a release, with the digest GitHub computed
/// when it was uploaded (`sha256:<hex>` in the API; just the hex here).
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class Release(val version: AppVersion, val tag: String, val pageUrl: String, val assets: List<ReleaseAsset>) {
    /// The Windows installer this release ships, if it ships one this app
    /// may run: named for its own version, downloaded from this
    /// repository's release path, and carrying a digest to check it by.
    val installer: ReleaseAsset?
        get() = assets.firstOrNull { asset ->
            asset.name.equals("Gilune-$version.msi", ignoreCase = true) &&
                asset.url.startsWith(DOWNLOAD_PREFIX) &&
                asset.sha256 != null
        }
}

/// GitHub's `releases/latest` answer, read for the few fields updates use.
/// Null when it is not a release this app understands: a draft, a
/// pre-release, or a tag that is not a version.
fun parseLatestRelease(json: String): Release? {
    val root = MiniJson.parse(json) as? Map<*, *> ?: return null
    if (root["draft"] == true || root["prerelease"] == true) return null
    val tag = root["tag_name"] as? String ?: return null
    val version = AppVersion.parse(tag) ?: return null
    val page = root["html_url"] as? String ?: "https://github.com/$RELEASE_REPOSITORY/releases/tag/$tag"
    val assets = (root["assets"] as? List<*>).orEmpty().mapNotNull { entry ->
        val asset = entry as? Map<*, *> ?: return@mapNotNull null
        ReleaseAsset(
            name = asset["name"] as? String ?: return@mapNotNull null,
            url = asset["browser_download_url"] as? String ?: return@mapNotNull null,
            size = (asset["size"] as? Number)?.toLong() ?: -1,
            sha256 = (asset["digest"] as? String)
                ?.takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")
                ?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } },
        )
    }
    return Release(version, tag, page, assets)
}

// ------------------------------------------------------------- download

/// Copies `input` to `target` through a `.part` file, hashing as it goes,
/// and keeps the result only if it is the file GitHub says it is. The
/// partial file never stands where an installer is expected, and a file
/// that fails the check is deleted rather than left for anyone to run.
fun downloadVerified(
    input: InputStream,
    asset: ReleaseAsset,
    target: File,
    progress: (Long) -> Unit = {},
): File {
    val expected = requireNotNull(asset.sha256) { "the release publishes no checksum for ${asset.name}" }
    target.parentFile?.mkdirs()
    val partial = File(target.parentFile, target.name + ".part")
    val digest = MessageDigest.getInstance("SHA-256")
    var copied = 0L
    try {
        input.use { source ->
            partial.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                    copied += read
                    progress(copied)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (asset.size >= 0 && copied != asset.size) {
            throw IOException("the download was ${copied} bytes, not ${asset.size}")
        }
        if (actual != expected) throw IOException("the download does not match its published checksum")
        target.delete()
        if (!partial.renameTo(target)) throw IOException("could not move the download into place")
        return target
    } finally {
        partial.delete()
    }
}

// -------------------------------------------------------------- install

/// The script that does the install once the app has gone: wait for this
/// process (and the launcher that started it) to exit — bounded, so a
/// stuck process cannot hold the update for ever — run the installer, and
/// start Gilune again whatever it answered. `msiexec` writes a verbose log
/// beside the installer, the one place to look if an update misbehaves.
///
/// Every tool is named by its full path: `cmd` looks in the working
/// directory before `PATH`, and the downloads folder is not where Gilune
/// should be taking an installer program from. `msiexec` is a parameter
/// only so a test can stand one in.
fun installScript(
    msi: File,
    appExe: File,
    waitFor: List<Long>,
    log: File,
    msiexec: String = """%SystemRoot%\System32\msiexec.exe""",
): String = buildString {
    val system = """%SystemRoot%\System32"""
    appendLine("@echo off")
    for (pid in waitFor) {
        appendLine("set /a tries=0")
        appendLine(":wait$pid")
        appendLine("\"$system\\tasklist.exe\" /NH /FI \"PID eq $pid\" 2>nul | \"$system\\find.exe\" \"$pid\" >nul || goto gone$pid")
        appendLine("set /a tries+=1")
        appendLine("if %tries% geq 60 goto gone$pid")
        // `timeout` refuses to run without console input; ping is the
        // portable one-second wait.
        appendLine("\"$system\\PING.EXE\" -n 2 127.0.0.1 >nul")
        appendLine("goto wait$pid")
        appendLine(":gone$pid")
    }
    // `call` so the script carries on afterwards whatever the program is;
    // a batch script waits for a windowed program like msiexec to finish.
    appendLine("call \"$msiexec\" /i \"${msi.absolutePath}\" /passive /norestart /l*v \"${log.absolutePath}\"")
    // `/b`: no console of its own — the app is a window either way.
    appendLine("start \"\" /b \"${appExe.absolutePath}\"")
}

/// What this process is, for the installer: the installed `Gilune.exe`,
/// and the process ids to wait out. Null when this is not an installed
/// Windows build — a dev run, a test, Linux — which is then offered the
/// release page instead.
data class InstalledApp(val exe: File, val processes: List<Long>)

fun installedApp(): InstalledApp? {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
    if (AppVersion.parse(APP_VERSION) == null) return null
    // The released MSI's launcher (`.github/windows-launcher`) puts the
    // install root's `resources` on the command line; a jpackage build
    // names its launcher outright. Either way the answer must be the
    // `Gilune.exe` the installer owns, or there is nothing to restart.
    val exe = (
        System.getProperty("jpackage.app-path")?.let(::File)
            ?: System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.resolve("Gilune.exe") }
        )?.takeIf { it.isFile && it.name.equals("Gilune.exe", ignoreCase = true) } ?: return null
    val self = ProcessHandle.current()
    val parent = self.parent().orElse(null)
    return InstalledApp(exe, processesToWaitFor(self.pid(), parent?.pid(), parent?.info()?.command()?.orElse(null)))
}

/// Which processes the installer must wait out. This one always; its
/// parent only when the parent is a `Gilune.exe` launcher holding the
/// files — the jpackage launcher, or the earlier MSI launcher that ran
/// `javaw.exe` and waited on it. The current MSI launcher hosts the
/// runtime in its own process, so the parent is whatever started Gilune
/// (Explorer, a shell) and waiting on it would hold the update for the
/// script's whole bound.
fun processesToWaitFor(selfPid: Long, parentPid: Long?, parentCommand: String?): List<Long> =
    listOfNotNull(
        selfPid,
        parentPid?.takeIf { parentCommand.orEmpty().endsWith("Gilune.exe", ignoreCase = true) },
    )

// ---------------------------------------------------------------- state

sealed interface UpdateStatus {
    /// Nothing asked yet this session.
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val latest: AppVersion?) : UpdateStatus
    data class Available(val release: Release) : UpdateStatus
    data class Downloading(val release: Release, val fraction: Float) : UpdateStatus
    data class Failed(val words: String, val release: Release? = null) : UpdateStatus
    /// The installer has been handed over and the app is on its way out.
    data class Installing(val release: Release) : UpdateStatus
}

/// The update machinery the window and the About screen share. The
/// network and the process are both injected, so the state machine is
/// tested without either.
class Updater(
    private val scope: CoroutineScope,
    private val current: AppVersion? = AppVersion.parse(APP_VERSION),
    private val installed: InstalledApp? = installedApp(),
    private val downloads: File = localGiluneDir("updates"),
    private val fetchText: (String) -> String = ::httpText,
    private val openDownload: (String) -> InputStream = ::httpStream,
    private val launch: (File) -> Unit = ::launchDetached,
    private val saveConsent: (Boolean) -> Unit = { Prefs.updateChecks = it },
    consent: Boolean? = Prefs.updateChecks,
) {
    var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set

    /// The person's answer to "may Gilune look for updates?": null until
    /// asked. Only a yes lets the app look on its own; Check now in About
    /// is a yes for that one look.
    var consent by mutableStateOf(consent)
        private set

    fun answer(allowed: Boolean) {
        consent = allowed
        saveConsent(allowed)
        if (allowed) check()
    }

    /// Whether the one-time question is still to be asked: only of a build
    /// that can update itself, because only there is the answer worth
    /// having.
    val shouldAsk: Boolean get() = consent == null && canInstall

    /// The window's way out: flush every buffer and exit. Set by `main`.
    var quit: () -> Unit = {}

    /// A banner dismissed with Later stays dismissed for this run only.
    var dismissed by mutableStateOf(false)

    /// Whether this build can install an update itself, rather than only
    /// point at the release page.
    val canInstall: Boolean get() = installed != null && current != null

    /// What this build is, in words, for the About screen.
    val describeBuild: String
        get() = when {
            current == null -> "a development build ($APP_VERSION) — updates are for installed releases"
            installed == null -> "version $current — install updates from the release page"
            else -> "version $current"
        }

    private var checking: Job? = null

    fun check() {
        if (checking?.isActive == true) return
        if (status is UpdateStatus.Downloading || status is UpdateStatus.Installing) return
        status = UpdateStatus.Checking
        checking = scope.launch {
            status = try {
                val release = withContext(Dispatchers.IO) { parseLatestRelease(fetchText(LATEST_RELEASE_API)) }
                when {
                    release == null -> UpdateStatus.UpToDate(null)
                    current != null && release.version > current -> {
                        dismissed = false
                        UpdateStatus.Available(release)
                    }
                    else -> UpdateStatus.UpToDate(release.version)
                }
            } catch (e: Throwable) {
                UpdateStatus.Failed("could not reach GitHub (${e.message ?: e.javaClass.simpleName})")
            }
        }
    }

    /// Looks now and then once a day, for as long as the app runs. The
    /// first look waits a little so it never competes with the window
    /// coming up.
    fun checkPeriodically() = scope.launch {
        delay(10_000)
        while (isActive) {
            if (consent == true) check()
            delay(UPDATE_CHECK_INTERVAL_MS)
        }
    }

    /// Download, verify, and hand over. One action, because "Update now"
    /// is one decision; a failure at any step says so and leaves the
    /// installed app exactly as it was.
    fun update(release: Release) {
        // A second click while one is under way is the same decision.
        if (status is UpdateStatus.Downloading || status is UpdateStatus.Installing) return
        val app = installed ?: run {
            status = UpdateStatus.Failed("this build cannot update itself — use the release page", release)
            return
        }
        val asset = release.installer ?: run {
            status = UpdateStatus.Failed("this release has no verifiable Windows installer", release)
            return
        }
        status = UpdateStatus.Downloading(release, 0f)
        scope.launch {
            try {
                val msi = withContext(Dispatchers.IO) {
                    // Only the update in hand is kept: an older download is
                    // never the one to run.
                    downloads.listFiles()?.forEach { it.delete() }
                    var shown = -1
                    downloadVerified(openDownload(asset.url), asset, File(downloads, asset.name)) { copied ->
                        // A percent at a time: a state write per buffer
                        // would be two thousand recompositions.
                        val percent = if (asset.size > 0) (copied * 100 / asset.size).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            status = UpdateStatus.Downloading(release, percent / 100f)
                        }
                    }
                }
                val log = File(downloads, "install-${release.version}.log")
                val script = File(downloads, "install-${release.version}.cmd")
                withContext(Dispatchers.IO) {
                    script.writeText(installScript(msi, app.exe, app.processes, log).replace("\n", "\r\n"))
                    launch(script)
                }
                status = UpdateStatus.Installing(release)
                quit()
            } catch (e: Throwable) {
                status = UpdateStatus.Failed("the update did not download (${e.message ?: e.javaClass.simpleName})", release)
            }
        }
    }
}

/// Runs the install script. Java starts Windows children without a console
/// window, so nothing flashes up; the installer shows its own progress.
private fun launchDetached(script: File) {
    ProcessBuilder("cmd.exe", "/c", script.absolutePath)
        .directory(script.parentFile)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

private fun connect(url: String): HttpURLConnection =
    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Gilune/$APP_VERSION")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

private fun httpText(url: String): String {
    val connection = connect(url)
    val code = connection.responseCode
    if (code == 403 || code == 429) throw IOException("GitHub is limiting requests; try again later")
    if (code == 404) throw IOException("no release has been published")
    if (code !in 200..299) throw IOException("GitHub answered $code")
    return connection.inputStream.use { it.readBytes().decodeToString() }
}

private fun httpStream(url: String): InputStream {
    require(url.startsWith(DOWNLOAD_PREFIX)) { "not a $RELEASE_REPOSITORY release download" }
    val connection = connect(url)
    val code = connection.responseCode
    if (code !in 200..299) throw IOException("the download answered $code")
    return connection.inputStream
}

// ----------------------------------------------------------------- json

/// Just enough JSON for one GitHub answer: objects, arrays, strings,
/// numbers, booleans and null, to maps, lists and values. The settings
/// documents get by with targeted extraction; a release lists assets,
/// and reading a list of objects by regular expression is how the wrong
/// asset's digest gets paired with the right asset's name.
internal object MiniJson {
    fun parse(text: String): Any? = Reader(text).run {
        val value = value()
        skipSpace()
        require(index == text.length) { "trailing characters at $index" }
        value
    }

    private class Reader(val text: String) {
        var index = 0

        fun skipSpace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun value(): Any? {
            skipSpace()
            require(index < text.length) { "unexpected end" }
            return when (val c = text[index]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("unexpected '$c' at $index")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, index)) { "unexpected text at $index" }
            index += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            index++
            val map = LinkedHashMap<String, Any?>()
            skipSpace()
            if (text.getOrNull(index) == '}') { index++; return map }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                require(text.getOrNull(index) == ':') { "expected ':' at $index" }
                index++
                map[key] = value()
                skipSpace()
                when (text.getOrNull(index)) {
                    ',' -> index++
                    '}' -> { index++; return map }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $index")
                }
            }
        }

        private fun array(): List<Any?> {
            index++
            val list = ArrayList<Any?>()
            skipSpace()
            if (text.getOrNull(index) == ']') { index++; return list }
            while (true) {
                list += value()
                skipSpace()
                when (text.getOrNull(index)) {
                    ',' -> index++
                    ']' -> { index++; return list }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $index")
                }
            }
        }

        private fun string(): String {
            require(text.getOrNull(index) == '"') { "expected a string at $index" }
            index++
            val out = StringBuilder()
            while (true) {
                require(index < text.length) { "unterminated string" }
                when (val c = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = text[index++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> { out.append(text.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                            else -> throw IllegalArgumentException("bad escape '\\$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDouble()
        }
    }
}
