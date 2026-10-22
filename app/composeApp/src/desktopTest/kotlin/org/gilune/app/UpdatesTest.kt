// In-app updates without the network or a real installer: the release is a
// fixture shaped like GitHub's `releases/latest`, downloads are byte arrays,
// and the install script runs on Windows against a stand-in `msiexec` that
// only writes down what it was asked.

package org.gilune.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun releaseJson(
    tag: String,
    msi: ByteArray = ByteArray(0),
    digest: String? = "sha256:" + sha256(msi),
    draft: Boolean = false,
    prerelease: Boolean = false,
    url: String = "https://github.com/Gilune/gilune/releases/download/$tag/Gilune-${tag.removePrefix("v")}.msi",
): String = """
    {"url":"https://api.github.com/repos/Gilune/gilune/releases/1","tag_name":"$tag",
     "html_url":"https://github.com/Gilune/gilune/releases/tag/$tag","draft":$draft,"prerelease":$prerelease,
     "author":{"login":"sneddobuilds","id":1,"site_admin":false},
     "body":"What changed:\n- \"quoted\" é notes\n","assets":[
      {"name":"gilune-debug-signed.apk","size":12,"digest":"sha256:${"0".repeat(64)}",
       "browser_download_url":"https://github.com/Gilune/gilune/releases/download/$tag/gilune-debug-signed.apk",
       "uploader":{"login":"github-actions[bot]"}},
      {"name":"Gilune-${tag.removePrefix("v")}.msi","size":${msi.size},
       ${digest?.let { "\"digest\":\"$it\"," } ?: "\"digest\":null,"}
       "browser_download_url":"$url","content_type":"application/x-msi","download_count":3}
    ]}
""".trimIndent()

class UpdatesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val temp: File = Files.createTempDirectory("gilune-updates").toFile()

    @AfterTest fun tearDown() {
        scope.cancel()
        temp.deleteRecursively()
    }

    @Test fun versionsAreTheTagsOwnAndOnlyStrictlyNewerCounts() {
        assertEquals(AppVersion(1, 0, 9), AppVersion.parse("v1.0.9"))
        assertEquals(AppVersion(1, 0, 9), AppVersion.parse("1.0.9"))
        assertNull(AppVersion.parse("1.0.0-dev"), "a dev build is not a version updates compare")
        assertNull(AppVersion.parse("v1.1"))
        assertTrue(AppVersion(1, 0, 10) > AppVersion(1, 0, 9), "numeric, not alphabetical")
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 9, 9))
        assertFalse(AppVersion(1, 0, 8) > AppVersion(1, 0, 8))
    }

    @Test fun theLatestReleaseIsReadForItsInstallerAndTheInstallersDigest() {
        val msi = "installer".toByteArray()
        val release = assertNotNull(parseLatestRelease(releaseJson("v1.0.9", msi)))
        assertEquals(AppVersion(1, 0, 9), release.version)
        assertEquals("https://github.com/Gilune/gilune/releases/tag/v1.0.9", release.pageUrl)
        val installer = assertNotNull(release.installer)
        assertEquals("Gilune-1.0.9.msi", installer.name)
        assertEquals(sha256(msi), installer.sha256, "the MSI's own digest, not the APK's")
        assertEquals(msi.size.toLong(), installer.size)
    }

    @Test fun anInstallerWithoutADigestOrFromElsewhereIsNotOffered() {
        assertNull(parseLatestRelease(releaseJson("v1.0.9", digest = null))!!.installer, "no checksum, no install")
        assertNull(parseLatestRelease(releaseJson("v1.0.9", digest = "md5:abc"))!!.installer)
        assertNull(
            parseLatestRelease(releaseJson("v1.0.9", url = "https://example.com/Gilune-1.0.9.msi"))!!.installer,
            "only this repository's release downloads",
        )
    }

    @Test fun draftsPreReleasesAndOddTagsAreNotReleases() {
        assertNull(parseLatestRelease(releaseJson("v1.0.9", draft = true)))
        assertNull(parseLatestRelease(releaseJson("v1.0.9", prerelease = true)))
        assertNull(parseLatestRelease(releaseJson("nightly")))
        assertFailsWith<IllegalArgumentException> { parseLatestRelease("{\"tag_name\":") }
    }

    @Test fun aDownloadIsKeptOnlyIfItIsTheFileGitHubPublished() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val asset = ReleaseAsset("Gilune-1.0.9.msi", "u", bytes.size.toLong(), sha256(bytes))
        val target = File(temp, "Gilune-1.0.9.msi")
        var last = 0L
        downloadVerified(ByteArrayInputStream(bytes), asset, target) { last = it }
        assertTrue(target.readBytes().contentEquals(bytes))
        assertEquals(bytes.size.toLong(), last)

        val tampered = bytes.copyOf().also { it[100] = 1 }
        target.delete()
        assertFailsWith<java.io.IOException> { downloadVerified(ByteArrayInputStream(tampered), asset, target) }
        assertFalse(target.exists(), "a file that fails the check is not left to run")
        assertFalse(File(temp, "Gilune-1.0.9.msi.part").exists())

        assertFailsWith<java.io.IOException>("a short download fails on size") {
            downloadVerified(ByteArrayInputStream(bytes.copyOf(10)), asset, target)
        }
        assertFalse(target.exists())
    }

    @Test fun theInstallerWaitsForALauncherParentButNotForWhateverOpenedGilune() {
        // The MSI's launcher hosts the runtime in its own process, so its
        // parent is Explorer or a shell: waiting on that would hold every
        // update for the script's whole bound. A `Gilune.exe` parent (a
        // jpackage launcher, or the earlier one that ran javaw.exe) still
        // holds the files and is waited for.
        assertEquals(listOf(7L), processesToWaitFor(7L, 3L, """C:\Windows\explorer.exe"""))
        assertEquals(listOf(7L, 3L), processesToWaitFor(7L, 3L, """C:\Program Files\Gilune\Gilune.exe"""))
        assertEquals(listOf(7L, 3L), processesToWaitFor(7L, 3L, """c:\program files\gilune\GILUNE.EXE"""))
        assertEquals(listOf(7L), processesToWaitFor(7L, 3L, null))
        assertEquals(listOf(7L), processesToWaitFor(7L, null, null))
    }

    private fun updater(
        json: () -> String,
        current: AppVersion? = AppVersion(1, 0, 8),
        installed: InstalledApp? = InstalledApp(File(temp, "Gilune.exe"), listOf(1L)),
        download: (String) -> ByteArray = { ByteArray(0) },
        launched: MutableList<File> = mutableListOf(),
        consent: Boolean? = null,
        saved: MutableList<Boolean> = mutableListOf(),
    ) = Updater(
        scope = scope,
        current = current,
        installed = installed,
        downloads = File(temp, "updates"),
        fetchText = { json() },
        openDownload = { ByteArrayInputStream(download(it)) },
        launch = { launched += it },
        saveConsent = { saved += it },
        consent = consent,
    )

    private fun Updater.await(until: (UpdateStatus) -> Boolean): UpdateStatus {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until(status)) {
            check(System.currentTimeMillis() < deadline) { "status stuck at $status" }
            Thread.sleep(10)
        }
        return status
    }

    @Test fun aNewerReleaseIsAvailableAndTheSameOneIsUpToDate() {
        val newer = updater({ releaseJson("v1.0.9") })
        newer.check()
        assertEquals(AppVersion(1, 0, 9), assertIs<UpdateStatus.Available>(newer.await { it is UpdateStatus.Available }).release.version)

        val same = updater({ releaseJson("v1.0.8") })
        same.check()
        assertEquals(AppVersion(1, 0, 8), assertIs<UpdateStatus.UpToDate>(same.await { it is UpdateStatus.UpToDate }).latest)

        val dev = updater({ releaseJson("v9.0.0") }, current = null)
        dev.check()
        dev.await { it is UpdateStatus.UpToDate }
        assertFalse(dev.canInstall, "a development build never installs over itself")
    }

    @Test fun anUnreachableGitHubIsSaidAndNothingElseChanges() {
        val offline = updater({ throw java.io.IOException("no route") })
        offline.check()
        val failed = assertIs<UpdateStatus.Failed>(offline.await { it is UpdateStatus.Failed })
        assertTrue("no route" in failed.words)
        assertNull(failed.release, "a failed look offers nothing to install")
    }

    @Test fun updatingDownloadsVerifiesHandsOverAndQuits() {
        val msi = "the real installer".toByteArray()
        val launched = mutableListOf<File>()
        val updater = updater({ releaseJson("v1.0.9", msi) }, download = { msi }, launched = launched)
        var quit = 0
        updater.quit = { quit++ }
        updater.check()
        val release = assertIs<UpdateStatus.Available>(updater.await { it is UpdateStatus.Available }).release
        File(temp, "updates").mkdirs()
        File(temp, "updates/Gilune-1.0.7.msi").writeText("stale")

        updater.update(release)
        updater.update(release) // a double click
        updater.await { it is UpdateStatus.Installing }
        assertEquals(1, quit, "the app closes itself so the installer can replace it")
        assertEquals(1, launched.size, "a double click is one update")
        val script = launched.single()
        assertTrue(script.readText().contains("Gilune-1.0.9.msi"))
        assertTrue(File(temp, "updates/Gilune-1.0.9.msi").readBytes().contentEquals(msi))
        assertFalse(File(temp, "updates/Gilune-1.0.7.msi").exists(), "an older download is never the one to run")
    }

    @Test fun aTamperedDownloadIsRefusedAndTheAppStays() {
        val msi = "the real installer".toByteArray()
        val launched = mutableListOf<File>()
        val updater = updater({ releaseJson("v1.0.9", msi) }, download = { "something else".toByteArray() }, launched = launched)
        var quit = 0
        updater.quit = { quit++ }
        updater.check()
        val release = assertIs<UpdateStatus.Available>(updater.await { it is UpdateStatus.Available }).release
        updater.update(release)
        val failed = assertIs<UpdateStatus.Failed>(updater.await { it is UpdateStatus.Failed })
        assertEquals(release, failed.release, "Try again stays on offer")
        assertTrue(launched.isEmpty(), "nothing was run")
        assertEquals(0, quit)
    }

    @Test fun aBuildThatCannotInstallOnlyPointsAtTheReleasePage() {
        val updater = updater({ releaseJson("v1.0.9") }, installed = null)
        updater.check()
        val release = assertIs<UpdateStatus.Available>(updater.await { it is UpdateStatus.Available }).release
        assertFalse(updater.canInstall)
        assertFalse(updater.shouldAsk, "a build that cannot update itself is not asked")
        updater.update(release)
        assertIs<UpdateStatus.Failed>(updater.status)
    }

    @Test fun theQuestionIsAskedOnceAndOnlyAYesLooks() {
        var looks = 0
        val saved = mutableListOf<Boolean>()
        val updater = updater({ looks++; releaseJson("v1.0.8") }, saved = saved)
        assertTrue(updater.shouldAsk)
        updater.answer(false)
        assertFalse(updater.shouldAsk)
        assertEquals(listOf(false), saved)
        assertEquals(0, looks, "a no is not a look")

        updater.answer(true)
        updater.await { it is UpdateStatus.UpToDate }
        assertEquals(1, looks, "a yes looks straight away")
        assertEquals(listOf(false, true), saved)
    }

    @Test fun theInstallScriptWaitsForTheAppThenInstallsThenReopensIt() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val dir = File(temp, "script").apply { mkdirs() }
        val record = File(dir, "record.txt")
        // A stand-in installer that only writes down what it was asked, and
        // answers 1602 — the person said no at the Windows prompt.
        val stub = File(dir, "stub-msiexec.cmd").apply {
            writeText("@echo off\r\necho msiexec %* >> \"${record.absolutePath}\"\r\nexit /b 1602\r\n")
        }
        // A planted `msiexec` in the working folder must not be what runs.
        File(dir, "msiexec.cmd").writeText("@echo off\r\necho PLANTED >> \"${record.absolutePath}\"\r\n")
        // The "app" it reopens, recording that it was started. `start` runs
        // a batch file under `cmd /K`, so it ends itself.
        val app = File(dir, "reopen.cmd").apply {
            writeText("@echo off\r\necho reopened >> \"${record.absolutePath}\"\r\nexit\r\n")
        }
        val msi = File(dir, "Gilune 1.0.9.msi").apply { writeText("x") }
        val holder = ProcessBuilder("ping", "-n", "4", "127.0.0.1").redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val script = File(dir, "install.cmd")
        script.writeText(
            installScript(msi, app, listOf(holder.pid()), File(dir, "install.log"), msiexec = stub.absolutePath)
                .replace("\n", "\r\n"),
        )
        assertTrue("""%SystemRoot%\System32\msiexec.exe""" in installScript(msi, app, emptyList(), File(dir, "l")))

        val started = System.nanoTime()
        val run = ProcessBuilder("cmd.exe", "/c", script.absolutePath).directory(dir)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(run.waitFor(60, TimeUnit.SECONDS), "the script finishes")
        assertFalse(holder.isAlive, "the installer ran only once the app had gone")
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 1_500, "it waited for the app")
        // `start /b` returns before the reopened app has written its line.
        val deadline = System.currentTimeMillis() + 10_000
        while (!record.exists() || "reopened" !in record.readText()) {
            check(System.currentTimeMillis() < deadline) { "never reopened: ${record.takeIf { it.exists() }?.readText()}" }
            Thread.sleep(50)
        }
        val lines = record.readLines().map { it.trim() }
        assertFalse("PLANTED" in lines, "the installer is taken from System32, never the working folder")
        assertTrue(lines[0].startsWith("msiexec /i \"${msi.absolutePath}\" /passive /norestart /l*v"), lines[0])
        assertEquals("reopened", lines[1], "Gilune comes back whatever the installer answered")
    }

    @Test fun miniJsonReadsWhatGitHubSends() {
        val parsed = MiniJson.parse("""{"a":[1,-2.5e1,true,false,null,"x\"y\\zé\n"],"b":{}}""") as Map<*, *>
        assertEquals(listOf(1L, -25.0, true, false, null, "x\"y\\zé\n"), parsed["a"])
        assertEquals(emptyMap<String, Any?>(), parsed["b"])
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("[1,]") }
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("{} x") }
    }
}
