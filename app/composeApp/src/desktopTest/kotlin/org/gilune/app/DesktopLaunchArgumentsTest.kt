// What the installed app's JVM is told, against what the build file says.
//
// Compose's `application { jvmArgs }` feeds two things at once: the `run`
// task a developer uses and the launcher cfg jpackage writes into the
// installer. That is easy to forget, and forgetting it shipped: the 1.0.1
// MSI carried `-Djna.library.path=D:/a/gilune/gilune/kernel/target/debug`,
// a directory on the CI runner that built it, to every machine that
// installed it. Nothing broke — `Main.kt` puts the app's own resource
// directory on the path first — but the installed app was being handed a
// stranger's build tree to search for native code.
//
// So the rule is asserted by reading the build file: arguments shared with
// the packaged launcher may not name a build directory, and the footprint
// flags that make the desktop app cost 376 MB instead of 504 must be
// there. Reading source is the same device `AlertKindCouplingTest` uses,
// and for the same reason — the fact lives in a file this test does not
// own, and a copy of it here would be the bug.
//
// Desktop-only on purpose: `jvmSharedTest` compiles into the instrumented
// suite, where the repository is not on the device and this could only
// skip.

package org.gilune.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLaunchArgumentsTest {

    private fun buildFile(): File {
        var here: File? = File(System.getProperty("user.dir")).absoluteFile
        while (here != null) {
            val candidate = File(here, "app/composeApp/build.gradle.kts")
            if (candidate.isFile) return candidate
            here = here.parentFile
        }
        error(
            "build.gradle.kts was not found above ${System.getProperty("user.dir")} — this " +
                "test must fail loudly rather than skip, or what the installer carries is " +
                "unchecked",
        )
    }

    /// The block's code with its prose removed. The comments explain what
    /// must not be there by naming it, so a test reading them would fail
    /// on its own explanation.
    private fun code(header: String): String =
        block(buildFile().readText(), header)
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /// The text of the `{ … }` block that `header` opens, brace-matched so
    /// that nested blocks come with it and the next one does not.
    private fun block(source: String, header: String): String {
        val opened = source.indexOf(header)
        assertTrue(opened >= 0, "`$header` has gone from the build file")
        var depth = 0
        var at = opened + header.length - 1
        val body = StringBuilder()
        while (at < source.length) {
            val ch = source[at]
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) return body.toString()
            }
            body.append(ch)
            at++
        }
        error("`$header` is never closed")
    }

    @Test
    fun theInstalledAppIsNeverHandedTheBuildMachinesKernelDirectory() {
        val shared = code("application {")
        assertFalse(
            shared.contains("kernel/target"),
            "the packaged launcher cfg inherits these arguments, so a path into the build " +
                "tree of whichever machine ran jpackage would be installed on every other " +
                "machine: $shared",
        )
        assertFalse(
            shared.contains("jna.library.path"),
            "the packaged app finds its own dll through `compose.application.resources.dir` " +
                "(Main.kt); a second, absent directory is only there to mislead: $shared",
        )
    }

    @Test
    fun aDevRunStillFindsTheKernelItJustBuilt() {
        // The other half of moving that argument: it moved, it was not
        // deleted. Without it `./gradlew :composeApp:run` loads no kernel
        // at all, and every UI probe stops working.
        val devRun = code("""tasks.matching { it.name == "run" }.configureEach {""")
        assertTrue(
            devRun.contains("jna.library.path") && devRun.contains("kernel/target"),
            "a dev run has no app resource directory to fall back on: $devRun",
        )
    }

    @Test
    fun theInstalledAppKeepsTheMeasuredFootprintFlags() {
        // Measured 2026-09-04 on an idle app with one small note: G1's
        // bookkeeping alone had committed 75 MB of native memory for a
        // heap using 13, sized from a 16 GB default maximum.
        val shared = code("application {")
        FOOTPRINT_FLAGS.forEach {
            assertTrue(shared.contains(it), "$it is what makes the installed app cost 376 MB")
        }
    }

    @Test
    fun theWindowsLauncherPassesTheSameFootprintFlags() {
        // The MSI's Gilune.exe hosts the JVM itself and never reads the
        // build file's list, so a flag added there alone reached the Linux
        // package and dev runs but not Windows.
        val launcher = File(buildFile().parentFile.parentFile.parentFile, ".github/windows-launcher/src/main.rs")
        assertTrue(launcher.isFile, "the Windows launcher is not where it was: $launcher")
        val code = launcher.readLines().joinToString("\n") { it.substringBefore("//") }
        FOOTPRINT_FLAGS.forEach {
            assertTrue(code.contains("\"$it\""), "the Windows launcher does not pass $it")
        }
    }

    private companion object {
        // Measured: SerialGC, the heap bounds and CICompilerCount on
        // 2026-09-04; sun.java2d.d3d=false (35 MB, 16 threads) and the heap
        // free ratios (heap given back after a collection) on 2026-11-01.
        val FOOTPRINT_FLAGS = listOf(
            "-XX:+UseSerialGC",
            "-Xms32m",
            "-Xmx768m",
            "-XX:CICompilerCount=2",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-Dsun.java2d.d3d=false",
        )
    }
}
