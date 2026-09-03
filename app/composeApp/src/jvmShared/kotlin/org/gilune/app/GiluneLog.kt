// Where shared code puts a warning.
//
// `jvmShared` compiles for both the desktop JVM and Android, so it cannot
// name `android.util.Log` — and until this round the consequence was that
// shared code with something to report simply swallowed it. A hook is the
// smallest thing that fixes that without the shared set learning what a
// platform is: the Android shell points it at `Log.w`, the desktop leaves
// it alone and gets `System.err`.
//
// Deliberately **not** a user-facing surface. Anything a person has to act
// on goes on `GiluneState.message`; this is for the second half of a
// failure — the stack that says which provider threw and why — which
// belongs in a log rather than under the composer.

package org.gilune.app

object GiluneLog {
    /// Set once by a shell that has a platform log. Null everywhere else,
    /// which is a supported configuration and not a missing one.
    @Volatile
    var sink: ((String, Throwable?) -> Unit)? = null

    fun warn(message: String, cause: Throwable? = null) {
        val hook = sink
        if (hook != null) {
            hook(message, cause)
            return
        }
        System.err.println("gilune: $message" + (cause?.let { ": $it" } ?: ""))
    }
}

/// What a message that reported a processing pass must add when the host's
/// own provider threw.
///
/// **Why it exists**: `runProcessing` counts what the kernel's processors
/// wrote, and the external route adds what this host's providers wrote
/// (ADR 0018 § 2). A provider that threw wrote nothing, so the outputs are
/// zero — and "0 transcribed" reads as *there was nothing to do*, which is
/// the opposite of what happened. Naming it is the whole fix.
///
/// Pure, so the wording is asserted without a kernel.
fun providerFailureSuffix(failure: String?): String =
    if (failure.isNullOrBlank()) "" else " — the transcription provider failed: $failure"

/// And what it must add when the provider ran and **failed jobs** without
/// throwing — which is the ordinary shape of a failure, not the exotic one.
///
/// The provider loop catches a decode that goes wrong and calls `failJob`,
/// so nothing propagates and [providerFailureSuffix] stays empty: a pass
/// that failed every recording it was handed produced "0 transcribed" and
/// read as *there was nothing to do*. The jobs are not lost — a failed job
/// backs off and is retried, and is quarantined only after repeated
/// attempts — so the sentence says both halves: it did not happen, and it
/// will be tried again.
fun providerFailedSuffix(failed: Long): String = when {
    failed <= 0L -> ""
    failed == 1L -> " — 1 recording could not be transcribed; Gilune will try again"
    else -> " — $failed recordings could not be transcribed; Gilune will try again"
}
