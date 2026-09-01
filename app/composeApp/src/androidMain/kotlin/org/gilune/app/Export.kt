// Export a copy (ADR 0017 § 5).
//
// The phone's vault is app-private, so until M5 sync exists this is the
// only way the *files* leave the device. It is a one-way export of bytes
// the shell already reads — a zip written through the system file picker
// — so it needs no kernel change and nothing is ever trapped.
//
// Kernel state is deliberately excluded: `.gilune` is a database, not the
// user's documents, and a half-copied one would be worse than none. What
// goes in the zip is what a person would recognise as their vault.

package org.gilune.app

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/// Everything the export will carry, as vault-relative paths in a stable
/// order. Pure over the filesystem, so what the zip contains is testable
/// without writing one.
fun exportEntries(vaultDir: String): List<String> {
    val root = File(vaultDir)
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .onEnter { !it.name.startsWith(".") }
        .filter { it.isFile && !it.name.startsWith(".") }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .sorted()
        .toList()
}

/// Streams the vault into `out` as a zip. Streamed rather than buffered:
/// a vault of recordings does not fit in a phone's heap, and the whole
/// point of the export is that a large one still leaves.
///
/// Returns the number of files written.
fun writeVaultZip(vaultDir: String, out: OutputStream): Int {
    val root = File(vaultDir)
    var written = 0
    ZipOutputStream(out.buffered()).use { zip ->
        for (rel in exportEntries(vaultDir)) {
            val file = File(root, rel)
            if (!file.isFile) continue
            zip.putNextEntry(ZipEntry(rel))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            written++
        }
    }
    return written
}

/// The name the picker offers. Dated, because an export is a snapshot and
/// two of them should not be the same file.
fun exportFileName(stamp: String): String = "gilune-vault-$stamp.zip"
