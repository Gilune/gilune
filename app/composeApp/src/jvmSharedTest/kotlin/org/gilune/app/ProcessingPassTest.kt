// The host's own processors, and what happens when one of them throws
// (ADR 0018 § 2).
//
// `runProcessing` counts what the kernel's processors wrote; `GiluneState`
// adds what this host's external providers wrote, so the shell's messages
// can count words from both routes without knowing which engine produced
// them. Until this round the external half was `runCatching { … }
// .getOrNull() ?: 0L` — a provider that threw was indistinguishable from a
// vault with nothing to transcribe, and the sentence a person read was
// "0 transcribed", which says *there was nothing to do*.
//
// Through the real kernel, so the pass is a real pass and the zero is a
// real zero.

package org.gilune.app

import kotlinx.coroutines.MainScope
import uniffi.gilune_ffi.Gilune
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessingPassTest {

    private fun scratch(): String = Files.createTempDirectory("gilune-pass").toString()

    private fun state(vault: String) = GiluneState(vault, MainScope())

    @Test
    fun aHostWithNoExternalProcessorsIsUnchangedByAnyOfThis() {
        // The desktop keeps whisper inside the kernel and never sets the
        // hook, so its behaviour is unchanged by construction rather than
        // by care. That is a property worth a test rather than a comment.
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            gilune.captureText("nothing external here")
            val pass = state(vault).processAll(gilune)
            assertNull(pass.providerFailure)
            assertEquals(0L, pass.outputs)
        }
    }

    @Test
    fun aProviderThatThrowsIsReportedRatherThanCountedAsZero() {
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            gilune.captureText("words that will not arrive")
            val subject = state(vault)
            subject.externalProcessing = { error("the caption model could not be loaded") }
            val pass = subject.processAll(gilune)
            assertEquals(0L, pass.outputs, "a provider that threw wrote nothing")
            assertTrue(
                pass.providerFailure?.contains("could not be loaded") == true,
                "and the reason must survive to the sentence: ${pass.providerFailure}",
            )
        }
    }

    @Test
    fun aFailureWithNoMessageStillNamesSomething() {
        // `message` is nullable on the JVM and an exception thrown from a
        // provider is not ours to shape, so the empty case must not
        // produce a sentence that trails off.
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            val subject = state(vault)
            subject.externalProcessing = { throw IllegalStateException() }
            val failure = subject.processAll(gilune).providerFailure
            assertTrue(!failure.isNullOrBlank(), "'$failure'")
            assertTrue(
                providerFailureSuffix(failure).contains("IllegalStateException"),
                "the class is the only name there is: '$failure'",
            )
        }
    }

    @Test
    fun aProviderThatRanAddsItsOutputsToTheKernelsAndReportsNoFailure() {
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            val subject = state(vault)
            subject.externalProcessing = { 3L }
            val pass = subject.processAll(gilune)
            assertEquals(3L, pass.outputs)
            assertNull(pass.providerFailure)
            assertEquals("", providerFailureSuffix(pass.providerFailure))
        }
    }
}
