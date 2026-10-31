// The desktop's log file (ADR 0029): readable, bounded, and never the
// thing that fails.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFileTest {
    private val dir: File = Files.createTempDirectory("gilune-log").toFile()
    private val at = Instant.parse("2026-10-30T12:00:00Z")

    @Test
    fun anEntryIsOneLineWithItsStackTraceIndentedBeneath() {
        val log = LogFile(File(dir, "logs"))
        log.write("WARN", "the provider failed", IllegalStateException("model missing"), at)
        val lines = log.current.readLines()
        assertEquals("2026-10-30T12:00:00Z WARN the provider failed", lines[0])
        assertEquals("    java.lang.IllegalStateException: model missing", lines[1])
        assertTrue(lines.drop(1).all { it.startsWith("    ") }, "every trace line is indented")
        assertTrue(lines.any { "LogFileTest" in it }, "the trace says where")
    }

    @Test
    fun pastItsSizeItRollsIntoOnePreviousFileAndNoMore() {
        val log = LogFile(dir, maxBytes = 200)
        repeat(40) { log.write("INFO", "entry $it", at = at) }
        assertTrue(log.current.length() <= 200 + 60, "${log.current.length()}")
        assertTrue(log.previous.isFile)
        assertEquals(setOf("gilune.log", "gilune.1.log"), dir.list()!!.toSet())
        assertTrue(log.current.readText().contains("entry 39"), "the newest is in the current file")
    }

    @Test
    fun aLogThatCannotBeWrittenIsSilentNotAFailure() {
        val blocked = File(dir, "blocked").apply { writeText("a file where the folder should be") }
        LogFile(blocked).write("ERROR", "nowhere to go")
        assertFalse(File(blocked, "gilune.log").exists())
    }
}
