// The phone's `KeyWrap` adapter (ADR 0019 § 4).
//
// The kernel mints and holds an X25519 static identity; what protects that
// key at rest is the platform's job, and on Android that is the Keystore.
// **It wraps the file rather than holding the identity itself**, and that
// is the ADR's decision rather than a shortcut: AndroidKeyStore cannot
// import or export a raw X25519 private key, so the only thing it can do
// for a Noise identity is seal the bytes the kernel wrote. So this is an
// AES-256-GCM key that never leaves the Keystore, used to seal and open
// one small file.
//
// **What it protects, said plainly.** The key material is encrypted at
// rest and the AES key cannot be read out of the Keystore, so a copy of
// the app's private storage — a backup, another app that somehow reached
// the file, a pulled disk image — is not a usable identity. It does
// **not** protect the key from code running as Gilune itself: anything
// executing in this app's UID can ask the Keystore to open the file, and
// that is precisely what `06-sync-and-security.md` means by
// "platform-protected where available". The settings screen says the same
// thing in the same words rather than implying more.
//
// The key is generated lazily, on the first wrap, so a user who never
// turns sync on never has one.

package org.gilune.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import uniffi.gilune_ffi.GiluneException
import uniffi.gilune_ffi.KeyWrap
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/// The Keystore provider's own name, not a file path: keys live inside
/// the platform, and the alias is the only handle there is.
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/// One alias for one purpose. Changing it is equivalent to throwing the
/// identity away, so it is a constant and never derived from anything.
const val IDENTITY_WRAP_ALIAS = "gilune-identity-wrap"

/// Recorded in the identity file and shown in Settings ▸ Sync, so a
/// person is told what actually protected their key rather than what was
/// intended to.
const val KEY_PROTECTION_KEYSTORE = "android-keystore"

private const val TRANSFORMATION = "AES/GCM/NoPadding"

/// GCM's standard nonce length. The Keystore generates it — the key spec
/// requires randomised encryption, so supplying our own is refused, which
/// is the behaviour we want: a reused nonce under one key is the one way
/// to break GCM outright, and this makes it unexpressible.
private const val IV_BYTES = 12

private const val TAG_BITS = 128

/// The wire format, which is the whole of the file's compatibility
/// surface: `IV(12) || ciphertext-and-tag`. Written here rather than
/// inferred anywhere, because the kernel stores these bytes verbatim and
/// a later build has to read what this one sealed.
class KeystoreKeyWrap : KeyWrap {

    override fun name(): String = KEY_PROTECTION_KEYSTORE

    override fun wrap(plaintext: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) {
            throw GiluneException.Invalid(
                "the Keystore produced a ${iv?.size ?: 0}-byte nonce, and this format carries $IV_BYTES",
            )
        }
        val sealed = cipher.doFinal(plaintext)
        iv + sealed
    } catch (e: GiluneException) {
        throw e
    } catch (e: Throwable) {
        // Nothing about the plaintext travels in the message — only what
        // went wrong. A key that reaches a log line has left the device.
        throw GiluneException.Invalid("this phone's Keystore could not seal the identity key: ${e.message}")
    }

    /// Opening is deliberately **not** allowed to create anything. A
    /// missing key here means the sealed file outlived the key that
    /// sealed it — a restored backup is the ordinary way that happens —
    /// and the honest outcome is a clean failure that the shell reports
    /// as "sync unavailable". Minting a fresh Keystore key at this
    /// moment would produce one that cannot open the file, and the next
    /// write would overwrite an identity whose peers still pin it.
    override fun unwrap(ciphertext: ByteArray): ByteArray = try {
        if (ciphertext.size <= IV_BYTES) {
            throw GiluneException.Invalid("the sealed identity key is too short to be one")
        }
        val key = existingKey()
            ?: throw GiluneException.Invalid(
                "the Keystore key that sealed this device's identity is no longer on this " +
                    "phone — a restored backup is a new device and has to be paired again",
            )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES))
        cipher.doFinal(ciphertext, IV_BYTES, ciphertext.size - IV_BYTES)
    } catch (e: GiluneException) {
        throw e
    } catch (e: Throwable) {
        throw GiluneException.Invalid("this phone's Keystore could not open the identity key: ${e.message}")
    }

    /// The key, generated on first use. Synchronised because two threads
    /// generating at once would leave one of them holding a key that
    /// cannot open what the other sealed — the Keystore's own generate
    /// replaces an alias silently.
    @Synchronized
    private fun wrappingKey(): SecretKey = existingKey() ?: generateKey()

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(IDENTITY_WRAP_ALIAS, null) as? SecretKey
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                IDENTITY_WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // A fresh nonce per encryption, enforced by the platform.
                .setRandomizedEncryptionRequired(true)
                // No lock-screen requirement: the identity has to be
                // openable when a background sync runs with the phone in
                // a pocket, and gating it behind an unlock would make
                // "sync while you are not looking" impossible rather than
                // safer. What guards the file is the app sandbox plus
                // this key never leaving the Keystore.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
