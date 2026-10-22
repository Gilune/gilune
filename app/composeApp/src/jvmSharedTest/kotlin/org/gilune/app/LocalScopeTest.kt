// The keep-on-this-device toggle, pinned without a screen (ADR 0007,
// amendment 2026-10-22, rule (c)): when it is offered, what it says, and
// that the marks it writes are exactly the ones the kernel reads.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// A desktop's sync facts with only the switch worth varying, for every
/// test that gates on it.
internal fun localScopeSyncStatus(enabled: Boolean, wanted: Boolean = enabled) = uniffi.gilune_ffi.SyncStatus(
        enabled = enabled,
        deviceId = "device-0",
        deviceName = "This desktop",
        fingerprint = "WXYZ-1234",
        identityPub = "bb".repeat(32),
        keyProtection = "none",
        enrolment = "continued",
        enrolmentNotice = null,
        listeningOn = if (enabled) "192.168.1.20:51837" else null,
        listeningAddresses = if (enabled) listOf("192.168.1.20:51837") else emptyList(),
        wanted = wanted,
        advertising = enabled,
        discovery = "mdns-sd",
        pairing = null,
        peers = emptyList(),
    )

class LocalScopeTest {

    private fun syncStatus(enabled: Boolean, wanted: Boolean = enabled) = localScopeSyncStatus(enabled, wanted)

    @Test
    fun theToggleIsOfferedOnlyWhileTheVaultsSyncSwitchIsOn() {
        assertFalse(showLocalScopeToggle(null))
        assertFalse(showLocalScopeToggle(syncStatus(enabled = false, wanted = false)))
        assertTrue(showLocalScopeToggle(syncStatus(enabled = true)))
        // Switched on and not bound this session is still switched on.
        assertTrue(showLocalScopeToggle(syncStatus(enabled = false, wanted = true)))
    }

    @Test
    fun aNotesOwnHeaderSaysWhetherItIsLocal() {
        assertTrue(noteIsLocal("---\ngilune-sync: local\n---\n"))
        assertTrue(noteIsLocal("---\ntitle: x\nGilune-Sync:  local  # mine\n---\n"))
        assertFalse(noteIsLocal("---\ngilune-sync: shared\n---\n"))
        assertFalse(noteIsLocal("---\ntitle: local\n---\n"))
        assertFalse(noteIsLocal(""))
    }

    @Test
    fun theLabelsSayWhatWillHappenNotWhatIs() {
        assertEquals("Keep this note on this device", localScopeLabel(isDir = false, local = false))
        assertEquals("Sync this note again", localScopeLabel(isDir = false, local = true))
        assertEquals("Keep this folder on this device", localScopeLabel(isDir = true, local = false))
        assertEquals("Sync this folder again", localScopeLabel(isDir = true, local = true))
        assertEquals("Sync this note", localScopeMenuLabel(isDir = false, inherited = false))
        assertEquals("Sync this folder", localScopeMenuLabel(isDir = true, inherited = false))
        assertEquals("Kept here by a folder above", localScopeMenuLabel(isDir = false, inherited = true))
        assertTrue(localScopeNotice("Diary/today.md", true).contains("not synced"))
        assertTrue(localScopeNotice("Diary/today.md", false).contains("syncs again"))
    }

    @Test
    fun theMarkIsWrittenAndClearedThroughTheKernelsOwnPropertyEdit() {
        // A note with no header gains one holding only the mark.
        val marked = noteTextWithLocalScope("just words\n", true)
        assertTrue(noteIsLocal(splitNoteRaw(marked).first), marked)
        assertTrue(marked.endsWith("just words\n"), "the body is untouched: $marked")
        // A note that has the mark loses it and keeps everything else.
        val cleared = noteTextWithLocalScope("---\ntitle: Dune\ngilune-sync: local\n---\nbody\n", false)
        assertFalse(noteIsLocal(splitNoteRaw(cleared).first), cleared)
        assertTrue(cleared.contains("title: Dune\n"), cleared)
        assertTrue(cleared.endsWith("body\n"), cleared)
        // Kernel-owned keys survive beside it.
        val headed = "---\ngilune-id: abc\ngilune-type: org.memory.note\n---\nbody\n"
        val keptId = noteTextWithLocalScope(headed, true)
        assertTrue(keptId.contains("gilune-id: abc\n"), keptId)
        assertTrue(noteIsLocal(splitNoteRaw(keptId).first), keptId)
    }

    @Test
    fun aFolderMarkerIsMadeAndRemovedAsAFile() {
        val vault = Files.createTempDirectory("gilune-local-scope").toFile()
        File(vault, "Private").mkdirs()
        assertFalse(folderIsLocal(vault.path, "Private"))
        assertTrue(setFolderLocal(vault.path, "Private", true))
        assertTrue(File(vault, "Private/$LOCAL_MARKER").isFile)
        assertFalse(setFolderLocal(vault.path, "Private", true), "already marked changes nothing")
        assertTrue(folderIsLocal(vault.path, "Private"))
        assertTrue(setFolderLocal(vault.path, "Private", false))
        assertFalse(folderIsLocal(vault.path, "Private"))
        assertFalse(setFolderLocal(vault.path, "Private", false))
    }

    @Test
    fun theRowStatesComeFromTheMarkerTheHeaderAndTheFoldersAbove() {
        val vault = Files.createTempDirectory("gilune-local-rows").toFile()
        File(vault, "Private/Deep").mkdirs()
        File(vault, "Notes").mkdirs()
        File(vault, "Private/$LOCAL_MARKER").writeText("")
        File(vault, "Private/Deep/secret.md").writeText("secret\n")
        File(vault, "Notes/plan.md").writeText("plan\n")
        File(vault, "Notes/diary.md").writeText("---\ngilune-sync: local\n---\nmine\n")
        val rows = localScopeOf(
            vault.path,
            listOf("Private" to true, "Private/Deep" to true, "Private/Deep/secret.md" to false, "Notes" to true, "Notes/plan.md" to false, "Notes/diary.md" to false),
        )
        assertEquals(LocalScopeState(local = true, inherited = false), rows["Private"], "its own marker, changeable here")
        assertEquals(LocalScopeState(local = true, inherited = true), rows["Private/Deep"], "kept by the folder above")
        assertEquals(LocalScopeState(local = true, inherited = true), rows["Private/Deep/secret.md"])
        assertEquals(LocalScopeState(local = false, inherited = false), rows["Notes"])
        assertEquals(LocalScopeState(local = false, inherited = false), rows["Notes/plan.md"])
        assertEquals(LocalScopeState(local = true, inherited = false), rows["Notes/diary.md"], "its own key, changeable here")
        assertTrue(localScopeTooltip(false, rows.getValue("Private/Deep/secret.md")).contains("folder above"))
        assertTrue(localScopeTooltip(false, rows.getValue("Notes/diary.md")).contains("switch on"))
        assertTrue(localScopeTooltip(true, rows.getValue("Notes")).contains("switch off"))
        // A marker at the vault root covers everything.
        File(vault, LOCAL_MARKER).writeText("")
        assertTrue(inheritsLocal(vault.path, "Notes/plan.md"))
        assertTrue(inheritsLocal(vault.path, "Notes"))
    }

    @Test
    fun aNoteOnDiskIsMarkedAndClearedInPlace() {
        val vault = Files.createTempDirectory("gilune-local-note").toFile()
        File(vault, "note.md").writeText("---\ntitle: T\n---\nwords\n")
        assertFalse(noteIsLocalOnDisk(vault.path, "note.md"))
        assertTrue(setNoteLocalOnDisk(vault.path, "note.md", true))
        assertTrue(noteIsLocalOnDisk(vault.path, "note.md"))
        assertFalse(setNoteLocalOnDisk(vault.path, "note.md", true), "already marked changes nothing")
        assertTrue(setNoteLocalOnDisk(vault.path, "note.md", false))
        assertFalse(noteIsLocalOnDisk(vault.path, "note.md"))
        assertEquals("---\ntitle: T\n---\nwords\n", File(vault, "note.md").readText())
        assertFalse(noteIsLocalOnDisk(vault.path, "missing.md"))
    }
}
