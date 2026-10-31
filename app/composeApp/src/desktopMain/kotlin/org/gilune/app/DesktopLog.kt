// The desktop's log file (ADR 0029).
//
// Shared code has reported its failures through `GiluneLog` since the
// external-provider round, and on the desktop they went to standard error
// — which a packaged app does not have: the launcher starts the JVM with no
// console, so every warning was written nowhere. Now they go to a file in
// this machine's own Gilune folder (never the vault: a log describes one
// machine, and a vault is copied between them), together with anything
// that escapes a thread uncaught, which before this ended the app without
// a word.
//
// One file, rolled once it passes a megabyte into a single previous file,
// so the folder never holds more than about two megabytes. Plain text, one
// entry per line with its stack trace beneath, so it can be read in any
// editor and pasted into an issue.

package org.gilune.app

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/// A size-bounded log file. Separate from where it is installed so the
/// rolling and the format are tested on a temporary folder.
class LogFile(val directory: File, private val maxBytes: Long = 1L shl 20) {
    val current: File get() = File(directory, "gilune.log")
    val previous: File get() = File(directory, "gilune.1.log")

    @Synchronized
    fun write(level: String, message: String, cause: Throwable? = null, at: Instant = Instant.now()) {
        try {
            directory.mkdirs()
            if (current.length() > maxBytes) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(entry(level, message, cause, at), Charsets.UTF_8)
        } catch (_: Exception) {
            // A log that cannot be written must never become the failure:
            // the disk is full or the folder is gone, and the app carries on.
        }
    }

    companion object {
        fun entry(level: String, message: String, cause: Throwable?, at: Instant): String = buildString {
            append(at).append(' ').append(level).append(' ').append(message).append('\n')
            if (cause != null) {
                val trace = StringWriter()
                cause.printStackTrace(PrintWriter(trace))
                trace.toString().lineSequence().filter { it.isNotBlank() }.forEach { append("    ").append(it).append('\n') }
            }
        }
    }
}

object DesktopLog {
    /// `%LOCALAPPDATA%\Gilune\logs` on Windows; `~/Gilune/logs` elsewhere.
    val file = LogFile(localGiluneDir("logs"))

    /// Point shared code's warnings at the file, catch what escapes every
    /// thread, and say what is running. Called first thing in `main`.
    fun install() {
        GiluneLog.sink = { message, cause ->
            file.write("WARN", message, cause)
            System.err.println("gilune: $message" + (cause?.let { ": $it" } ?: ""))
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            file.write("ERROR", "uncaught on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error) ?: error.printStackTrace()
        }
        file.write(
            "INFO",
            "Gilune $APP_VERSION starting on ${System.getProperty("os.name")} " +
                "${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}",
        )
    }
}
