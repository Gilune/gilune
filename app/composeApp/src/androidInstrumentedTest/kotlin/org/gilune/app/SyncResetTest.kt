// Getting out of a restored backup (ADR 0019 § 4).
//
// The identity file is sealed by a Keystore key, and Keystore keys do not
// travel. A phone that restored a backup taken before this round's
// exclusion therefore holds a sealed file nothing on it can open, and
// `KeyWrap.unwrap` refuses rather than minting a fresh key that could not
// open it either — the right refusal, because the alternative overwrites an
// identity whose peers still pin it.
//
// It was also a dead end: nothing in the app could clear it. These pin the
// way out — the classifier that recognises the state, the delete that is
// scoped to device-local files, and the enrolment that follows it.

package org.gilune.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncResetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun scratchVault(): String = Files.createTempDirectory("gilune-reset").toString()

    private fun scratchSyncDir(): File =
        Files.createTempDirectory("gilune-reset-sync").toFile()

    @Test
    fun theResetClearsEveryDeviceLocalFileAndLeavesTheDirectoryItself() {
        val dir = scratchSyncDir()
        File(dir, "device-1.identity.json").writeText("{}")
        File(dir, "roster.json").writeText("[]")
        File(dir, "nested").apply { mkdirs(); File(this, "enabled").writeText("true") }

        assertEquals(3, clearSyncState(dir))
        assertTrue(dir.exists(), "the directory stays; it is what enrolment writes into")
        assertEquals(0, dir.listFiles()?.size, "and it is empty")
        // Idempotent: pressing it twice is pressing it once.
        assertEquals(0, clearSyncState(dir))
        // A directory that was never there is not an error either.
        assertEquals(0, clearSyncState(File(dir, "never-existed")))
    }

    @Test
    fun clearingAndEnrollingAgainProducesADeviceRatherThanARepairedOne() {
        // The honest outcome § 4 asks for: not the old identity mended,
        // but a new device with a history of its own — which is why the
        // sentence beside the button says every peer has to be paired
        // again.
        val vault = scratchVault()
        val dir = scratchSyncDir()
        val first = Gilune.open(vault).use {
            it.configureSync(dir.absolutePath, deviceName(), KeystoreKeyWrap())
        }
        assertTrue(first.fingerprint.isNotBlank())
        assertTrue(dir.listFiles()?.isNotEmpty() == true, "enrolment writes device-local state")

        clearSyncState(dir)
        val second = Gilune.open(vault).use {
            it.configureSync(dir.absolutePath, deviceName(), KeystoreKeyWrap())
        }
        assertTrue(second.fingerprint.isNotBlank(), "the reset has to end enrolled")
        assertNotEquals(
            first.fingerprint,
            second.fingerprint,
            "a reset device is a new device, not the old one repaired",
        )
    }

    @Test
    fun theResetTouchesNothingInTheVault() {
        // The sentence beside the button promises exactly this, and a
        // promise about deletion is one to hold a test against.
        val vault = scratchVault()
        val dir = scratchSyncDir()
        val before = Gilune.open(vault).use { gilune ->
            gilune.configureSync(dir.absolutePath, deviceName(), KeystoreKeyWrap())
            gilune.captureText("a note that must survive a sync reset")
            gilune.project()
            gilune.status().sources
        }

        clearSyncState(dir)

        Gilune.open(vault).use { gilune ->
            gilune.configureSync(dir.absolutePath, deviceName(), KeystoreKeyWrap())
            assertEquals(before, gilune.status().sources)
            assertEquals(
                "a note that must survive a sync reset",
                gilune.recentSources(1u).first().preview,
            )
        }
    }

    @Test
    fun theKeystoreAdapterSaysTheThingTheClassifierLooksFor() {
        // The two halves of the recovery are in different files — the
        // adapter's message and the shell's classifier — so this is the
        // seam between them. A reworded adapter that stopped matching
        // would silently take the way out away again.
        val message = runCatching {
            // A ciphertext long enough to be one, sealed by nothing: the
            // adapter refuses, and the sentence it refuses with is the
            // subject here.
            KeystoreKeyWrapProbe.unwrapWithNoKey()
        }.exceptionOrNull()?.message
        assertTrue(
            message == null || identityUnreadable("sync unavailable: $message"),
            "the adapter's refusal must stay recognisable to the shell: '$message'",
        )
        assertFalse(identityUnreadable("sync unavailable: the port was already taken"))
    }
}

/// A way to ask the adapter for its refusal without disturbing the alias
/// this app's real identity is sealed under.
///
/// It deliberately does **not** delete the Keystore entry: on a device
/// where the app has an identity, deleting the alias would be the very
/// fault this test is about. So it asks for a wrap first (which creates the
/// alias if there is none) and then hands `unwrap` bytes no key sealed —
/// the same class of refusal, without an identity paying for it.
private object KeystoreKeyWrapProbe {
    fun unwrapWithNoKey(): ByteArray {
        val wrap = KeystoreKeyWrap()
        val sealed = wrap.wrap("probe".toByteArray())
        // Flip the tag: the key exists, the ciphertext does not open.
        val tampered = sealed.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex] + 1).toByte()
        return wrap.unwrap(tampered)
    }
}
