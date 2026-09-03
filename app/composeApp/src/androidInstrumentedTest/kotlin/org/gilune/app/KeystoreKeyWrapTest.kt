// The phone's `KeyWrap` adapter on a real Keystore (ADR 0019 § 4).
//
// M5b shipped this adapter with no on-device round-trip test at all — a
// recorded gap — and the round that closed it found the defect these
// exist for.
//
// **`@Synchronized` was locking the wrong thing.** It takes the
// *instance's* monitor, and there is more than one instance alive:
// `MainActivity` remembers one and `SyncWorker` constructs another on
// every background pass. So two threads could both find no key, both
// generate, and the Keystore's own generate replaces an alias silently —
// leaving the loser holding a key that cannot open what the winner sealed.
// That is an identity thrown away by a race, and the recovery from it is a
// re-pairing of every device.
//
// **What these deliberately never do is delete the alias.** On a phone
// where the app has enrolled, the alias is what opens the real identity
// file, and a test that removed it would perform the exact fault it is
// here to prevent. Creating it is what the app does anyway.

package org.gilune.app

import org.junit.Test
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeystoreKeyWrapTest {

    private fun aliases(): List<String> =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .aliases().toList()

    @Test
    fun theAdapterNamesItselfAsSyncStatusWillShowIt() {
        // `keyProtection` is drawn on Settings ▸ Sync as a sentence about
        // what actually protected the key, so the name is contract.
        assertEquals(KEY_PROTECTION_KEYSTORE, KeystoreKeyWrap().name())
        assertEquals("android-keystore", KeystoreKeyWrap().name())
    }

    @Test
    fun whatOneInstanceSealsAnotherOpens() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val sealed = KeystoreKeyWrap().wrap(secret)
        assertContentEquals(secret, KeystoreKeyWrap().unwrap(sealed))
    }

    @Test
    fun theWireFormatIsTheNonceFollowedByTheSealedBytes() {
        // Written down because the kernel stores these bytes verbatim and
        // a later build has to read what this one sealed.
        val sealed = KeystoreKeyWrap().wrap(ByteArray(16))
        assertTrue(sealed.size > 12 + 16, "nonce, ciphertext and a tag: ${sealed.size} bytes")
    }

    /// The race, run for real.
    @Test
    fun twoInstancesWrappingAtOnceEndUpOnOneKeyAndCanOpenEachOthersBytes() {
        val first = KeystoreKeyWrap()
        val second = KeystoreKeyWrap()
        val plain = "one identity, one alias".toByteArray()

        val go = CountDownLatch(1)
        val done = CountDownLatch(2)
        val sealedByFirst = AtomicReference<ByteArray?>(null)
        val sealedBySecond = AtomicReference<ByteArray?>(null)
        val thrown = AtomicReference<Throwable?>(null)

        for ((adapter, slot) in listOf(first to sealedByFirst, second to sealedBySecond)) {
            Thread {
                try {
                    go.await()
                    slot.set(adapter.wrap(plain))
                } catch (e: Throwable) {
                    thrown.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        go.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "both wraps must finish")
        assertEquals(null, thrown.get(), "a concurrent wrap must not throw: ${thrown.get()}")

        val a = sealedByFirst.get()
        val b = sealedBySecond.get()
        assertTrue(a != null && b != null)
        // The property that matters: whichever key each of them used, it
        // is the same key — so each can open what the other sealed. Under
        // the instance lock the loser's alias had been replaced and this
        // is where it failed.
        assertContentEquals(plain, second.unwrap(a))
        assertContentEquals(plain, first.unwrap(b))
        // And a third adapter, made after the fact, opens both.
        val third = KeystoreKeyWrap()
        assertContentEquals(plain, third.unwrap(a))
        assertContentEquals(plain, third.unwrap(b))

        assertEquals(
            1,
            aliases().count { it == IDENTITY_WRAP_ALIAS },
            "one alias, whatever raced for it",
        )
    }

    @Test
    fun aTamperedSealRefusesRatherThanReturningSomething() {
        // GCM's tag is the whole point of using it: a file somebody edited
        // is not opened as if they had not.
        val sealed = KeystoreKeyWrap().wrap("do not tamper".toByteArray())
        val tampered = sealed.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        val error = runCatching { KeystoreKeyWrap().unwrap(tampered) }.exceptionOrNull()
        assertTrue(error != null, "a tampered seal must not open")
        // And it refuses through the FFI's own error channel, because a
        // stray platform exception across a uniffi callback is a panic
        // rather than a message.
        assertTrue(
            error is uniffi.gilune_ffi.GiluneException,
            "the error channel is the FFI's: $error",
        )
    }

    @Test
    fun somethingTooShortToBeASealIsRefusedBeforeTheKeystoreIsAsked() {
        val error = runCatching { KeystoreKeyWrap().unwrap(ByteArray(4)) }.exceptionOrNull()
        assertTrue(error is uniffi.gilune_ffi.GiluneException, "$error")
        assertTrue(error.message.orEmpty().contains("too short"), error.message.orEmpty())
    }
}
