// A tree move uses the vault's ordinary files, just like an external file
// browser. Preflight the whole note/attachment group before moving anything;
// never replace another file, and undo completed moves if a later one fails.
package org.gilune.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun movedPath(path: String, source: String, target: String): String = when {
    path == source -> target
    path.startsWith("$source/") -> target + path.removePrefix(source)
    else -> path
}

internal fun canMoveInto(source: String, directory: String): Boolean =
    source.isNotEmpty() && directory != source && !directory.startsWith("$source/") &&
        directory != source.substringBeforeLast('/', "")

private fun vaultPath(root: Path, relative: String): Path {
    require(relative.isEmpty() || relative.split('/').all {
        it.isNotEmpty() && !it.startsWith('.') && !it.contains('\\') && !it.contains(':')
    }) { "Choose a folder inside the vault." }
    val path = root.resolve(relative).normalize()
    require(path.startsWith(root)) { "Choose a folder inside the vault." }
    var ancestor = path
    while (ancestor != root) {
        require(!Files.isSymbolicLink(ancestor)) { "Linked folders cannot be moved through the tree." }
        ancestor = ancestor.parent
    }
    require(path.toFile().canonicalFile.toPath().startsWith(root)) { "Choose a folder inside the vault." }
    return path
}

/// Move a note with its attachments, or a whole directory, without replacing
/// existing content. Returns the new relative path; throws on refusal or IO
/// failure. The caller must update open paths and reconcile after success.
internal fun moveVaultEntry(vaultDir: String, relPath: String, directory: String): String {
    require(canMoveInto(relPath, directory)) { "Choose a different folder outside the item being moved." }
    val root = File(vaultDir).canonicalFile.toPath()
    val source = vaultPath(root, relPath)
    val parent = vaultPath(root, directory)
    require(Files.exists(source, NOFOLLOW_LINKS)) { "The item no longer exists." }
    require(Files.isDirectory(parent)) { "The destination folder no longer exists." }
    require(!parent.startsWith(source)) { "A folder cannot be moved inside itself." }
    val target = parent.resolve(source.fileName)
    val moves = mutableListOf(source to target)
    if (Files.isRegularFile(source) && relPath.endsWith(".md")) {
        val stem = source.fileName.toString().removeSuffix(".md")
        val attachments = source.parent.toFile().listFiles()
            ?: throw IOException("Could not read the note's attachments.")
        attachments.filter { it.attachmentStem() == stem }.forEach {
            val attachment = vaultPath(root, it.relativeTo(root.toFile()).invariantSeparatorsPath)
            moves.add(attachment to parent.resolve(attachment.fileName))
        }
    }
    moves.forEach { (_, destination) ->
        require(!Files.exists(destination, NOFOLLOW_LINKS)) {
            "${destination.fileName} already exists in the destination folder."
        }
    }
    val completed = mutableListOf<Pair<Path, Path>>()
    try {
        moves.forEach { (from, to) ->
            Files.move(from, to)
            completed.add(from to to)
        }
    } catch (failure: IOException) {
        completed.asReversed().forEach { (from, to) ->
            try { Files.move(to, from) } catch (rollback: IOException) { failure.addSuppressed(rollback) }
        }
        if (failure.suppressed.isNotEmpty()) {
            throw IOException("Move interrupted; files remain in the source and destination folders.", failure)
        }
        throw failure
    }
    return root.relativize(target).toFile().invariantSeparatorsPath
}
