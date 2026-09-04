// A vault of hand-written notes, open and indexed, for the tests that
// need the real thing.
//
// Since stage P of ADR 0020 a query is answered by the kernel, so a test
// about the query language needs a kernel: a temp directory, an open
// handle, this shell's indexers registered on it, and reconciliation to
// put the files on the change feed. Notes written here carry front matter
// with an id no object claims, which makes them import candidates — files
// the kernel does not write and does not touch, which is exactly what a
// note somebody else made is.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import uniffi.gilune_ffi.Gilune

class IndexedVault(prefix: String = "gilune-indexed") {
    val root: File = Files.createTempDirectory(prefix).toFile()
    val dir: String = root.absolutePath
    val gilune: Gilune = Gilune.open(dir)

    init {
        // The plugin has to exist before its indexer may be registered:
        // a namespace belongs to a plugin, and removing the plugin is
        // what takes it away (ADR 0011).
        gilune.registerPlugin(tasksDeclaration())
        registerShellIndexers(gilune)
    }

    /// Write a note and bring the index up to it, the way the shell's own
    /// save does: reconcile that one file, then let the indexers run.
    fun write(relPath: String, text: String): String {
        val file = File(root, relPath)
        file.parentFile?.mkdirs()
        file.writeText(text)
        gilune.reconcilePath(relPath)
        gilune.advanceIndex()
        return relPath
    }

    /// A note the kernel itself wrote, projected into `Captures/`. The
    /// other half of a real vault, and the half that carries `gilune-*`
    /// front matter written by the kernel rather than by a test.
    fun capture(text: String): String {
        gilune.captureText(text)
        gilune.project()
        return gilune.recentSources(1u).first().projectionRelPath!!
    }

    fun remove(relPath: String) {
        File(root, relPath).delete()
        gilune.reconcile()
        gilune.advanceIndex()
    }

    /// Everything on disk, taken in whole. For a test that wrote files
    /// behind the vault's back.
    fun catchUp() {
        gilune.reconcile()
        gilune.advanceIndex()
    }
}
