// The desktop's `KeyWrap` adapter (ADR 0019 § 4): Windows DPAPI, user
// scope, reached through JNA — which is already a dependency of this
// module — rather than through `jna-platform`, which is not, and which
// two function declarations do not justify adding.
//
// **What this protects, said plainly.** DPAPI's user scope derives its
// key from this Windows account, so the wrapped identity key is unusable
// to another account on the machine and to anyone who walks off with the
// drive. It does **not** protect the key from code running as this user:
// any program in this account can hand the same bytes back to
// `CryptUnprotectData`. That is exactly what `06-sync-and-security.md`
// means by "platform-protected where available", and it is why
// `SyncStatus.keyProtection` names the adapter that ran instead of
// promising secrecy.
//
// There is no quiet fallback anywhere in here. A wrap that cannot happen
// throws, so the kernel never stores plaintext believing it was
// protected; a desktop that cannot reach DPAPI at all is offered no
// adapter, and the kernel records `none`, which the settings screen says
// out loud.

package org.gilune.app

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import uniffi.gilune_ffi.KeyWrap

/// `DATA_BLOB` — a length and a pointer, which is every argument and
/// every result these two calls have.
@Structure.FieldOrder("cbData", "pbData")
internal class DataBlob : Structure() {
    @JvmField
    var cbData: Int = 0

    @JvmField
    var pbData: Pointer? = null
}

/// The two Crypt32 entry points, declared here rather than imported.
/// Every argument this code never uses — the description string, the
/// optional entropy, the reserved word and the prompt struct — is typed
/// as a pointer so it can be passed as the null it always is.
internal interface Crypt32 : StdCallLibrary {
    fun CryptProtectData(
        pDataIn: DataBlob,
        szDataDescr: Pointer?,
        pOptionalEntropy: Pointer?,
        pvReserved: Pointer?,
        pPromptStruct: Pointer?,
        dwFlags: Int,
        pDataOut: DataBlob,
    ): Boolean

    fun CryptUnprotectData(
        pDataIn: DataBlob,
        ppszDataDescr: Pointer?,
        pOptionalEntropy: Pointer?,
        pvReserved: Pointer?,
        pPromptStruct: Pointer?,
        dwFlags: Int,
        pDataOut: DataBlob,
    ): Boolean
}

/// DPAPI allocates what it returns with `LocalAlloc`, so the caller
/// releases it with `LocalFree` or leaks it once per call.
internal interface Kernel32Heap : StdCallLibrary {
    fun LocalFree(hMem: Pointer?): Pointer?
}

/// Windows DPAPI as a `KeyWrap`. Constructed only through
/// [DpapiKeyWrap.available], which proves it works before offering it.
class DpapiKeyWrap internal constructor(
    private val crypt: Crypt32,
    private val heap: Kernel32Heap,
) : KeyWrap {

    override fun name(): String = PROTECTION_NAME

    override fun wrap(plaintext: ByteArray): ByteArray =
        transform("protect", plaintext) { input, output ->
            crypt.CryptProtectData(input, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
        }

    override fun unwrap(ciphertext: ByteArray): ByteArray =
        transform("unprotect", ciphertext) { input, output ->
            crypt.CryptUnprotectData(input, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
        }

    /// Both calls have the same shape: bytes in through one blob, bytes
    /// out through another that Windows allocated. A failure is a thrown
    /// exception rather than an empty array, because the one thing this
    /// class must never do is hand back something that looks like a
    /// result.
    private fun transform(
        what: String,
        bytes: ByteArray,
        call: (DataBlob, DataBlob) -> Boolean,
    ): ByteArray {
        // A zero-length allocation has no address, and a blob whose
        // pointer is null is not the same request as a blob of no bytes.
        val buffer = Memory(maxOf(bytes.size, 1).toLong())
        try {
            buffer.write(0, bytes, 0, bytes.size)
            val input = DataBlob().apply {
                cbData = bytes.size
                pbData = buffer
            }
            val output = DataBlob()
            if (!call(input, output)) {
                throw IllegalStateException(
                    "Windows DPAPI could not $what this device's identity key " +
                        "(error ${Native.getLastError()})",
                )
            }
            val produced = output.pbData
                ?: throw IllegalStateException(
                    "Windows DPAPI reported success but returned nothing when asked to " +
                        "$what this device's identity key",
                )
            try {
                return produced.getByteArray(0, output.cbData)
            } finally {
                heap.LocalFree(produced)
            }
        } finally {
            // Half of what passes through here is a private key, so the
            // buffer is zeroed rather than left for a collector.
            buffer.clear()
        }
    }

    /// Prove the adapter before offering it. DPAPI that is present but
    /// refusing is worth finding here, where the answer is an honest
    /// `none` in the settings screen, rather than at enrolment where it
    /// is a vault that would not open.
    private fun roundTrips(): Boolean = runCatching {
        val probe = byteArrayOf(0x67, 0x69, 0x6C, 0x75, 0x6E, 0x65)
        unwrap(wrap(probe)).contentEquals(probe)
    }.getOrDefault(false)

    companion object {
        /// The name the kernel records and the settings screen shows.
        /// `SyncStatus.keyProtection` carries it verbatim.
        const val PROTECTION_NAME = "dpapi-user"

        /// `CRYPTPROTECT_UI_FORBIDDEN`. Nothing here may raise a dialog:
        /// this runs while a vault is opening, and a modal from a native
        /// library is not something the shell could dismiss.
        ///
        /// `CRYPTPROTECT_LOCAL_MACHINE` is deliberately absent — machine
        /// scope would make the key readable by every account on the box,
        /// which is the one protection user scope actually buys.
        private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

        /// The adapter this desktop can honestly offer, or none.
        fun available(): DpapiKeyWrap? {
            if (!isWindows()) return null
            return runCatching {
                DpapiKeyWrap(
                    Native.load("Crypt32", Crypt32::class.java),
                    Native.load("Kernel32", Kernel32Heap::class.java),
                )
            }.getOrNull()?.takeIf { it.roundTrips() }
        }

        private fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}

/// What this desktop hands the kernel to protect its identity key with.
///
/// Null is a real answer, not a failure: on a desktop with no DPAPI the
/// kernel records `none` and the settings screen says the key is stored
/// unprotected. Pretending otherwise would be the one outcome ADR 0019
/// § 4 rules out.
fun platformKeyWrap(): KeyWrap? = DpapiKeyWrap.available()
