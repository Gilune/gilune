// The rules an editor must not break, kept apart from any screen so they
// can be tested directly (ADR 0012).
//
// Projection rewrites files under an open editor — canonicalising an
// edit, or adding a transcript to a note you happen to be reading. A
// buffer read before that rewrite is stale, and writing it back would
// silently revert content the kernel had just settled.
//
// Front matter is machine contract rather than prose, so the editor holds
// it apart from the body it lets you type into and writes it back
// verbatim: bytes the user did not change must not change.

package org.gilune.app

import java.security.MessageDigest

/// Front-matter keys the system owns. They carry a note's identity, so an
/// editor shows them but never rewrites or drops them.
const val SYSTEM_KEY_PREFIX = "gilune-"

private const val FENCE = "---"

fun contentHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun contentHash(text: String): String = contentHash(text.toByteArray())

/// Whether a buffer opened at `hashAtOpen` may be written over what is on
/// disk now. A file that vanished may be recreated; a file that changed
/// since it was read may not be overwritten without a decision.
fun mayOverwrite(hashAtOpen: String, hashOnDisk: String?): Boolean =
    hashOnDisk == null || hashOnDisk == hashAtOpen

/// A note split into the raw bytes of its front-matter block and its
/// body. The block is the original text of both fence lines and
/// everything between them, up to and including the newline that ends the
/// closing fence. Without front matter it holds only an optional leading BOM. The
/// split is lossless — `assembleNote(splitNoteRaw(x)) == x` for every
/// input — so an editor can hold the body alone and still write the file
/// back byte for byte.
fun splitNoteRaw(text: String): Pair<String, String> {
    val prelude = if (text.startsWith("\uFEFF")) "\uFEFF" else ""
    fun withoutHeader() = prelude to text.removePrefix(prelude)
    val firstBreak = text.indexOf('\n')
    if (firstBreak < 0 || text.substring(0, firstBreak).removePrefix("\uFEFF").trim() != FENCE) return withoutHeader()
    var cursor = firstBreak + 1
    while (cursor <= text.length) {
        val lineBreak = text.indexOf('\n', cursor)
        val lineEnd = if (lineBreak < 0) text.length else lineBreak
        if (text.substring(cursor, lineEnd).trim() == FENCE) {
            val blockEnd = if (lineBreak < 0) text.length else lineBreak + 1
            return text.substring(0, blockEnd) to text.substring(blockEnd)
        }
        if (lineBreak < 0) break
        cursor = lineBreak + 1
    }
    return withoutHeader()
}

fun assembleNote(frontMatterRaw: String, body: String): String = frontMatterRaw + body
