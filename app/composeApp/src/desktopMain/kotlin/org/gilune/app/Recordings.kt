// Transcribing a recording again, and loading one from outside the vault
// (ADR 0029) — the desktop's two doors onto the kernel's `process_again`
// and the ordinary capture path.

package org.gilune.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/// A request to transcribe a note again that would replace words somebody
/// wrote, held until they answer.
data class TranscribeAgain(val objectId: String, val note: String)

/// The tree's "Transcribe again" on a recording. Asked off the UI thread,
/// because finding a recording that was moved hashes it once.
///
/// A recording Gilune captured gets new words in its own note — straight
/// away when the note's text is still the last transcript, after `confirm`
/// when somebody has changed it. Any other WAV or AIFF beside a note (one
/// copied in, say) has no note of its own to write into, so it is
/// transcribed into a new note next to it.
suspend fun transcribeRecordingAt(
    state: GiluneState,
    relPath: String,
    confirm: (TranscribeAgain) -> Unit,
    onOpen: (String) -> Unit,
) {
    // A lookup that failed is not an answer: falling through to "not a
    // recording" would copy a recording Gilune already holds into a second
    // note.
    val lookup = withContext(Dispatchers.IO) { runCatching { state.sourceForFile(relPath) } }
    val found = lookup.getOrElse {
        GiluneLog.warn("could not look up the recording $relPath", it)
        state.message.value = "could not transcribe ${relPath.substringAfterLast('/')} again: ${it.message}"
        return
    }
    val note = found?.projectionRelPath
    when {
        found != null && note != null && found.textEdited -> confirm(TranscribeAgain(found.objectId, note))
        found != null && note != null -> state.transcribeAgain(found.objectId)
        else -> {
            val file = File(state.vaultDir, relPath)
            val folder = relPath.substringBeforeLast('/', "")
            loadRecordingFile(state, file, folder, onOpen)
        }
    }
}

/// Ask which recording to load. Null when the person cancels.
fun chooseRecordingFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Transcribe a recording"
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("Recordings (WAV, AIFF)", *LOADABLE_RECORDING_SUFFIXES.toTypedArray())
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/// Load a recording into a new voice note named after the file. `folder`
/// is where the note goes — vault-relative, `""` for the root — or null to
/// leave it in Captures with the other new recordings.
suspend fun loadRecordingFile(state: GiluneState, file: File, folder: String?, onOpen: (String) -> Unit) {
    state.message.value = "reading ${file.name}…"
    val wav = try {
        withContext(Dispatchers.IO) { loadRecording(file) }
    } catch (e: RecordingUnreadable) {
        state.message.value = e.message ?: "${file.name} cannot be transcribed"
        return
    } catch (e: Exception) {
        GiluneLog.warn("could not read the recording ${file.path}", e)
        state.message.value = "could not read ${file.name}: ${e.message}"
        return
    }
    state.captureRecording(
        wav,
        place = { note -> placeLoadedNote(state.vaultDir, note, file.nameWithoutExtension, folder) },
        onOpenNote = onOpen,
    )
}

/// Name the new note after the file it came from, then move it where it
/// was asked for. Its recording moves with it (the rail pairs them by
/// name). Either step may be refused — a name taken, a folder gone — and
/// the note is simply left where it is.
internal fun placeLoadedNote(vaultDir: String, note: String, name: String, folder: String?): String {
    // Free where it is made and where it is going: a name free only in
    // Captures would make the move collide and leave the note behind
    // (found live, 2026-10-30: a recording beside its own note).
    val directories = listOfNotNull(File(vaultDir, note).parentFile, folder?.let { File(vaultDir, it) })
    val free = freeNoteName(directories, name.trim().ifEmpty { "Recording" })
    var placed = renameNote(vaultDir, note, free) ?: note
    if (folder != null && placed.substringBeforeLast('/', "") != folder) {
        placed = runCatching { moveVaultEntry(vaultDir, placed, folder) }
            .onFailure { GiluneLog.warn("could not move $placed into ${folder.ifEmpty { "the vault root" }}", it) }
            .getOrDefault(placed)
    }
    return placed
}

/// `name`, or `name 1`, `name 2`, … — the first no note in any of
/// `directories` already has. A recording beside a note shares its name, so a note made
/// from it never can.
internal fun freeNoteName(directories: List<File>, name: String): String {
    fun taken(candidate: String) = directories.any { File(it, "$candidate.md").exists() }
    if (!taken(name)) return name
    var n = 1
    while (taken("$name $n")) n++
    return "$name $n"
}

@Composable
fun TranscribeAgainDialog(state: GiluneState, request: TranscribeAgain, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Transcribe ${request.note.substringAfterLast('/').removeSuffix(".md")} again?") },
        text = {
            Text(
                "This note's text has changed since it was transcribed. Transcribing it again " +
                    "replaces the text with new words. Gilune keeps the current text in the note's " +
                    "history, but nothing in the app shows that history yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    state.transcribeAgain(request.objectId)
                    onClose()
                },
                modifier = Modifier.testTag("transcribe-again-confirm"),
            ) { Text("Replace the text") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
