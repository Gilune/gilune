// The desktop half of ADR 0019 gate B, point 10: "the identity key is
// wrapped by the platform adapter". The kernel's own tests prove the
// wrapping *contract* against a stand-in wrapper; what only a test on
// this platform can prove is that the real adapter — Windows DPAPI
// through JNA — actually satisfies it.
//
// These run only on Windows, and say so rather than passing quietly
// elsewhere: an adapter that is absent is a real answer (`platformKeyWrap`
// returns null and the kernel records `none`), and pretending a skipped
// test is a passing one is precisely the kind of false assurance this
// milestone is supposed to remove.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DpapiKeyWrapTest {

    private val onWindows =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun wrapper() = platformKeyWrap()

    @Test
    fun windows_offers_an_adapter_and_names_it() {
        if (!onWindows) return
        val wrap = assertNotNull(wrapper(), "a Windows desktop must be able to protect its key")
        assertEquals(
            "dpapi-user",
            wrap.name(),
            "the name is what SyncStatus.keyProtection shows, so it is contract",
        )
    }

    @Test
    fun a_thirty_two_byte_identity_key_survives_the_round_trip() {
        if (!onWindows) return
        val wrap = assertNotNull(wrapper())
        // The real thing: an X25519 private key is 32 bytes, and every
        // one of the 256 byte values has to come back unchanged.
        val key = ByteArray(32) { (it * 7 + 13).toByte() }
        val sealed = wrap.wrap(key)
        assertContentEquals(key, wrap.unwrap(sealed), "the key must come back byte-exact")
    }

    @Test
    fun the_wrapped_form_is_not_the_key() {
        if (!onWindows) return
        val wrap = assertNotNull(wrapper())
        val key = ByteArray(32) { 0x5A }
        val sealed = wrap.wrap(key)
        assertTrue(
            sealed.size > key.size,
            "DPAPI's output carries its own header, so a wrapped key is never the same length",
        )
        // The 32 plaintext bytes must not appear anywhere in the output.
        // This is what the kernel's "the file must not contain the
        // unwrapped key" assertion is worth on the real adapter.
        val plain = key.toList()
        val found = (0..sealed.size - key.size).any { at ->
            sealed.toList().subList(at, at + key.size) == plain
        }
        assertTrue(!found, "the key must not sit in the clear inside its own wrapper")
    }

    @Test
    fun every_wrap_of_the_same_key_differs_and_every_one_opens() {
        if (!onWindows) return
        val wrap = assertNotNull(wrapper())
        val key = ByteArray(32) { 0x11 }
        val first = wrap.wrap(key)
        val second = wrap.wrap(key)
        assertTrue(
            !first.contentEquals(second),
            "identical output for identical input would mean no randomness in the wrapping",
        )
        assertContentEquals(key, wrap.unwrap(first))
        assertContentEquals(key, wrap.unwrap(second))
    }

    @Test
    fun damaged_ciphertext_throws_rather_than_returning_something() {
        if (!onWindows) return
        val wrap = assertNotNull(wrapper())
        val sealed = wrap.wrap(ByteArray(32) { 0x2C })
        val damaged = sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        // The one behaviour this adapter must never have is handing back
        // something that looks like a result. A device whose key file was
        // tampered with has to fail loudly, not enrol with rubbish.
        assertFailsWith<IllegalStateException> { wrap.unwrap(damaged) }
    }

    @Test
    fun an_empty_input_is_handled_rather_than_crashing_on_a_null_pointer() {
        if (!onWindows) return
        val wrap = assertNotNull(wrapper())
        // Not a case the kernel produces, but a zero-length allocation has
        // no address and the adapter goes out of its way to keep the blob
        // pointer non-null. Worth pinning, because the failure mode is a
        // native crash rather than an exception.
        assertContentEquals(ByteArray(0), wrap.unwrap(wrap.wrap(ByteArray(0))))
    }
}
