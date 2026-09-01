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
/// same shared function the desktop editor toggles through — and writes
/// the note back with every other byte untouched.
///
/// `decorations` is that fold's chain, and it is the **caller's live
/// one**: gated on the plugin and carrying the global filter, exactly as
/// `MobileEditor` and `AgendaScreen` build theirs. Only then does the
/// tasks plugin's `done:` stamp apply here as it applies in the editors.
/// The default is the shell standing alone — the right answer for a test
/// that means to ask about the built-ins, and the wrong one for a screen
/// (a Read view that took it stamped nothing for a whole release).
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

/// Ticking a box from the **agenda** (ADR 0017 § 8, MB3), where the note
/// was never opened and so no whole-file hash was ever taken.
///
/// The guard is therefore the line itself: the row carries the text the
/// search engine read, and this refuses unless that exact text is still on
/// that exact line. That is stricter than [toggleTaskAtLine]'s whole-file
/// comparison in the way that matters here — an agenda row makes a claim
/// about one line, and an unrelated edit elsewhere in the note is no
/// reason to refuse the tick — and it is never looser: a line that moved,
/// changed or went is a line this declines to write.
///
/// `bodyLine` is **body-relative and 0-based**, like [toggleTaskAtLine]'s.
/// `TaskHit.line` is body-relative and **1-based** (`MatchedLine.number`),
/// so its caller subtracts one; that off-by-one is the whole reason this
/// takes the expected text as well.
///
/// Returns the new file text, or null when it declined. Declining is the
/// safe answer — a stale tap must never write.
fun toggleTaskLine(
    current: String,
    bodyLine: Int,
    expected: String,
    decorations: List<EditorDecoration> = builtInDecorations(),
): String? {
    val (frontMatter, body) = splitNoteRaw(current)
    val lines = body.split('\n')
    if (bodyLine < 0 || bodyLine >= lines.size) return null
    if (lines[bodyLine] != expected) return null
    val rewritten = activateLine(lines[bodyLine], decorations) ?: return null
    if (rewritten == lines[bodyLine]) return null
    val updated = lines.toMutableList().also { it[bodyLine] = rewritten }
    return assembleNote(frontMatter, updated.joinToString("\n"))
}

/// Renaming a note from the editor's title field (ADR 0017 § 8's "title
/// rename"), taking the note's attachments with it.
///
/// The kernel tracks a note by its front-matter id, so the note's own
/// history survives any name; an attachment is bound to its note only by
/// sharing its stem, so the stem has to move as one or the pairing is
/// lost — a voice note would keep its audio on disk and lose the link to
/// it. The note moves first: an attachment left behind is merely unlisted,
/// while a note that failed to move would have stolen its attachments'
/// names.
///
/// Returns the new vault-relative path, the old one when the name did not
/// change, or null when the rename was refused. The caller owns syncing
/// and any open-buffer bookkeeping.
///
/// This is the desktop `VaultTree.renameNote`'s contract, written again
/// rather than shared: that one lives in `desktopMain` beside the tree
/// that calls it, and promoting it would move file I/O into the pure
/// shared set for one caller. If a third shell ever needs it, that is the
/// moment to promote it.
fun renameNote(vaultDir: String, relPath: String, newName: String): String? {
    val name = newName.trim()
    if (!isNameSegment(name)) return null
    val source = File(vaultDir, relPath)
    val note = relPath.endsWith(".md")
    val target = File(source.parentFile, if (note && !name.endsWith(".md")) "$name.md" else name)
    if (target.name == source.name) return relPath
    // A case-only rename is the same file on a case-insensitive volume,
    // so `exists()` is not the refusal it looks like.
    val caseOnly = target.name.equals(source.name, ignoreCase = true)
    if ((!caseOnly && target.exists()) || !source.renameTo(target)) return null
    if (note) {
        val stem = source.name.removeSuffix(".md")
        val renamed = target.name.removeSuffix(".md")
        source.parentFile?.listFiles()?.filter { it.attachmentStem() == stem }?.forEach {
            it.renameTo(File(it.parentFile, renamed + it.name.removePrefix(stem)))
        }
    }
    return target.relativeTo(File(vaultDir)).invariantSeparatorsPath
}

/// A name the editor may write: one segment, never a path and never
/// traversal.
private fun isNameSegment(name: String): Boolean =
    name.isNotEmpty() && !name.contains("..") && !name.contains('/') && !name.contains('\\')

/// A non-markdown file belongs to the sibling note that shares its stem:
/// `kitchen.wav` is an attachment of `kitchen.md`. Sharing the stem is the
/// whole of the binding — nothing records it — which is why renaming a
/// note has to take its attachments with it.
private fun File.attachmentStem(): String? =
    if (isFile && !name.startsWith(".") && !name.endsWith(".md")) {
        name.substringBeforeLast('.')
    } else {
        null
    }

/// How long a captured WAV runs, from its own header. Whisper is not
/// involved and neither is the kernel: the duration is a fact about the
/// preserved bytes, so it is read off them.
///
/// Defensive by contract — a truncated or non-PCM file answers null and
/// the row simply shows no duration rather than a wrong one.
///
/// `totalBytes` is the whole file's length, and it is consulted for one
/// case only: a recording the Record widget streamed, whose `data` chunk
/// declares [WAV_SIZE_UNKNOWN] because a stream cannot know its own
/// length (see `wavStreamHeader`). Then the file itself is the answer.
/// Omit it and an unknown size stays unknown, which is the old
/// behaviour and still the honest one.
fun wavDurationMs(bytes: ByteArray, totalBytes: Long? = null): Long? {
    if (bytes.size < 12) return null
    if (!bytes.matches(0, "RIFF") || !bytes.matches(8, "WAVE")) return null
    var byteRate = 0L
    var cursor = 12
    while (cursor + 8 <= bytes.size) {
        val id = String(bytes, cursor, 4, Charsets.US_ASCII)
        val size = bytes.littleEndianInt(cursor + 4)
        val bodyStart = cursor + 8
        if (id == "data") {
            if (byteRate <= 0) return null
            val dataBytes = when {
                size >= 0 -> size.toLong()
                size == WAV_SIZE_UNKNOWN && totalBytes != null -> totalBytes - bodyStart
                else -> return null
            }
            return if (dataBytes < 0) null else dataBytes * 1000L / byteRate
        }
        if (size < 0) return null
        if (id == "fmt ") {
            if (bodyStart + 12 > bytes.size) return null
            byteRate = bytes.littleEndianInt(bodyStart + 8).toLong() and 0xFFFFFFFFL
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
