// Keeping a note or a folder on this device (ADR 0007, amendment
// 2026-10-22, rule (c)), as the shell offers it.
//
// The kernel's rule is two marks: `gilune-sync: local` in a note's own
// front matter, and a `.gilune-local` file in a folder that covers
// everything under it. Both are the person's to write with any tool;
// these helpers are the shell writing them from a menu. The note's mark
// goes through the kernel's property service — the same route the
// Properties window takes — so the header is edited losslessly and a
// stale buffer is refused rather than overwritten; the folder's mark is
// a file, so it is made and removed as one.
//
// Offered only while the vault's sync switch is on: with sync off nothing
// leaves anyway, and a toggle that changed nothing would read as a
// promise about something the switch already answers.

package org.gilune.app

import uniffi.gilune_ffi.PropertyChange
import uniffi.gilune_ffi.SyncStatus
import uniffi.gilune_ffi.changeSourceProperties
import uniffi.gilune_ffi.inspectSourceProperties
import java.io.File

/// The folder marker's name, as the kernel reads it.
const val LOCAL_MARKER = ".gilune-local"

/// The note key and the one value the kernel honours.
const val LOCAL_SYNC_KEY = "gilune-sync"
const val LOCAL_SYNC_VALUE = "local"

/// Whether the menus offer the toggle at all: the vault's sync switch,
/// as the person left it — `wanted` rather than `enabled`, because a
/// machine that could not bind its port this session still syncs by the
/// person's decision.
fun showLocalScopeToggle(status: SyncStatus?): Boolean = status?.wanted == true

private val localKeyLine = Regex("""(?im)^\s*gilune-sync\s*:\s*local\s*(#.*)?$""")

/// Whether a note's own front matter keeps it on this device.
fun noteIsLocal(frontMatterRaw: String): Boolean = localKeyLine.containsMatchIn(frontMatterRaw)

/// The property change that sets or clears the mark.
fun localScopeChange(local: Boolean): PropertyChange =
    PropertyChange(LOCAL_SYNC_KEY, if (local) LOCAL_SYNC_VALUE else null)

/// What the menu item says, given what the entry is and what it is now.
fun localScopeLabel(isDir: Boolean, local: Boolean): String = when {
    isDir && local -> "Sync this folder again"
    isDir -> "Keep this folder on this device"
    local -> "Sync this note again"
    else -> "Keep this note on this device"
}

/// What the status line says once the change is made.
fun localScopeNotice(relPath: String, local: Boolean): String =
    if (local) "$relPath stays on this device and is not synced" else "$relPath syncs again"

/// A note's text with the mark set or cleared, through the kernel's own
/// lossless property edit. Throws `GiluneException.DocumentInvalid` for
/// a header the kernel cannot edit, which the caller reports.
fun noteTextWithLocalScope(text: String, local: Boolean): String =
    changeSourceProperties(text, inspectSourceProperties(text).contentVersion, listOf(localScopeChange(local))).text

fun folderIsLocal(vaultDir: String, folderRel: String): Boolean =
    File(File(vaultDir, folderRel), LOCAL_MARKER).isFile

/// Make or remove a folder's marker. Answers whether anything changed.
fun setFolderLocal(vaultDir: String, folderRel: String, local: Boolean): Boolean {
    val marker = File(File(vaultDir, folderRel), LOCAL_MARKER)
    return if (local) {
        if (marker.isFile) false else { marker.parentFile?.mkdirs(); marker.writeText(""); true }
    } else {
        marker.isFile && marker.delete()
    }
}

/// Whether a note on disk carries the mark, read from its header alone.
fun noteIsLocalOnDisk(vaultDir: String, relPath: String): Boolean {
    val file = File(vaultDir, relPath)
    if (!file.isFile) return false
    return noteIsLocal(splitNoteRaw(file.readText()).first)
}

/// What a row's switch shows: whether the entry is kept here, and
/// whether that comes from a folder above it (then the row cannot change
/// it, and its switch is drawn off and disabled).
data class LocalScopeState(val local: Boolean, val inherited: Boolean)

/// Whether any folder from the vault root down to (but not including)
/// `rel`'s own name carries the marker.
fun inheritsLocal(vaultDir: String, rel: String): Boolean {
    var dir = File(vaultDir)
    if (File(dir, LOCAL_MARKER).isFile) return true
    val parts = rel.split('/').dropLast(1)
    for (part in parts) {
        dir = File(dir, part)
        if (File(dir, LOCAL_MARKER).isFile) return true
    }
    return false
}

/// The switch state of every listed entry, read once per refresh: a
/// marker check per folder on the way down and one header per visible
/// note. Rows that are not notes or folders are absent.
fun localScopeOf(vaultDir: String, entries: List<Pair<String, Boolean>>): Map<String, LocalScopeState> =
    entries.associate { (rel, isDir) ->
        val inherited = inheritsLocal(vaultDir, rel)
        val own = if (isDir) folderIsLocal(vaultDir, rel) else noteIsLocalOnDisk(vaultDir, rel)
        rel to LocalScopeState(local = own || inherited, inherited = inherited)
    }

/// What the menu item beside the switch says. The switch itself shows
/// the state; the words say what the row is and why it may be fixed.
fun localScopeMenuLabel(isDir: Boolean, inherited: Boolean): String = when {
    inherited -> "Kept here by a folder above"
    isDir -> "Sync this folder"
    else -> "Sync this note"
}

/// What the switch says when hovered.
fun localScopeTooltip(isDir: Boolean, scope: LocalScopeState): String {
    val what = if (isDir) "This folder" else "This note"
    return when {
        scope.inherited -> "$what is kept on this device by a folder above it"
        scope.local -> "$what is kept on this device — switch on to sync it again"
        else -> "$what syncs with your other devices — switch off to keep it here"
    }
}

/// Set or clear a note's mark on disk, for a note no editor holds.
/// Answers whether the file changed.
fun setNoteLocalOnDisk(vaultDir: String, relPath: String, local: Boolean): Boolean {
    val file = File(vaultDir, relPath)
    val before = file.readText()
    val after = noteTextWithLocalScope(before, local)
    if (after == before) return false
    file.writeText(after)
    return true
}
