// Reading the vault from the phone shell, and the one guarded write MB1
// performs.
//
// The vault is app-private internal storage (ADR 0017 § 5), so this is
// ordinary `File` I/O under a directory only this app can see. Everything
// here is pure over the filesystem, so it can be tested without a screen.

package org.gilune.app

import java.io.File

/// One note in the flat list the Notes destination shows: where it is,
/// what it is called, and which folder it sits in. No tree — a phone list
/// is flat (ADR 0017 § 8's notes list), and the folder is a subtitle.
data class MobileNote(val relPath: String, val name: String, val folder: String)

/// Every note in the vault, folder-then-name ordered. `.gilune` and every
/// other dot-entry is kernel state, never vault content — the desktop
/// rail's rule, applied here.
fun listNotes(vaultDir: String): List<MobileNote> {
    val root = File(vaultDir)
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .onEnter { !it.name.startsWith(".") }
        .filter { it.isFile && it.name.endsWith(".md") && !it.name.startsWith(".") }
        .map { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            MobileNote(
                relPath = rel,
                name = file.name.removeSuffix(".md"),
                folder = rel.substringBeforeLast('/', ""),
            )
        }
        .sortedWith(compareBy({ it.folder }, { it.name.lowercase() }))
        .toList()
}

/// The title a note shows: its file name, which is what the desktop
/// renames and what Obsidian displays. The document itself is never asked
/// for a title — that would make a heading load-bearing.
fun noteTitle(relPath: String): String = relPath.substringAfterLast('/').removeSuffix(".md")

/// Ticking a box from the Read view (ADR 0017 § 8).
///
/// The rendered checkbox carries the **source line** its block was parsed
/// from, so this needs no text matching: it re-reads the file, checks
/// nothing moved under it, folds `activateLine` over that one line — the
/// same shared function the desktop editor toggles through, so the tasks
/// plugin's `done:` stamp applies identically — and writes the note back
/// with every other byte untouched.
///
/// It returns the new file text, or null when it declined: the file
/// changed since it was read (`mayOverwrite`), the line is gone, or no
/// registration claimed it. Declining is the safe answer — a stale tap
/// must never write.
fun toggleTaskAtLine(
    current: String,
    hashAtRead: String,
    hashOnDisk: String?,
    bodyLine: Int,
    decorations: List<EditorDecoration> = builtInDecorations(),
): String? {
    if (!mayOverwrite(hashAtRead, hashOnDisk)) return null
    val (frontMatter, body) = splitNoteRaw(current)
    val lines = body.split('\n')
    if (bodyLine < 0 || bodyLine >= lines.size) return null
    val rewritten = activateLine(lines[bodyLine], decorations) ?: return null
    if (rewritten == lines[bodyLine]) return null
    val updated = lines.toMutableList().also { it[bodyLine] = rewritten }
    return assembleNote(frontMatter, updated.joinToString("\n"))
}

/// How long a captured WAV runs, from its own header. Whisper is not
/// involved and neither is the kernel: the duration is a fact about the
/// preserved bytes, so it is read off them.
///
/// Defensive by contract — a truncated or non-PCM file answers null and
/// the row simply shows no duration rather than a wrong one.
fun wavDurationMs(bytes: ByteArray): Long? {
    if (bytes.size < 12) return null
    if (!bytes.matches(0, "RIFF") || !bytes.matches(8, "WAVE")) return null
    var byteRate = 0L
    var cursor = 12
    while (cursor + 8 <= bytes.size) {
        val id = String(bytes, cursor, 4, Charsets.US_ASCII)
        val size = bytes.littleEndianInt(cursor + 4)
        if (size < 0) return null
        val bodyStart = cursor + 8
        when (id) {
            "fmt " -> {
                if (bodyStart + 12 > bytes.size) return null
                byteRate = bytes.littleEndianInt(bodyStart + 8).toLong() and 0xFFFFFFFFL
            }
            "data" -> {
                if (byteRate <= 0) return null
                return size.toLong() * 1000L / byteRate
            }
        }
        cursor = bodyStart + size + (size and 1)
    }
    return null
}

/// `m:ss`, the way a recording is spoken about. 62 seconds is 1:02.
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

private fun ByteArray.matches(at: Int, ascii: String): Boolean =
    at + ascii.length <= size && String(this, at, ascii.length, Charsets.US_ASCII) == ascii

private fun ByteArray.littleEndianInt(at: Int): Int {
    if (at + 4 > size) return -1
    return (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or
        ((this[at + 3].toInt() and 0xFF) shl 24)
}
