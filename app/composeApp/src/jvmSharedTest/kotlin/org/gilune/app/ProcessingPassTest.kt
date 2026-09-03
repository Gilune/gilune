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
            subject.externalProcessing = { ProviderPass(transcribed = 3L) }
            val pass = subject.processAll(gilune)
            assertEquals(3L, pass.outputs)
            assertNull(pass.providerFailure)
            assertEquals("", pass.trouble())
            assertEquals(false, pass.anyFailed)
        }
    }

    // ------------------------------------------------------------------
    // The second-order case: a provider that fails without throwing
    // ------------------------------------------------------------------

    @Test
    fun aProviderThatFailsJobsWithoutThrowingStillSaysSo() {
        // The ordinary shape of a failure. The provider loop catches a
        // decode that goes wrong and calls `failJob`, so nothing propagates
        // — and the first round of this fix carried only the thrown case,
        // which left "0 transcribed" in front of a person whose recordings
        // had all failed.
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            val subject = state(vault)
            subject.externalProcessing = { ProviderPass(skipped = 1L, failed = 2L) }
            val pass = subject.processAll(gilune)
            assertEquals(0L, pass.outputs)
            assertNull(pass.providerFailure, "nothing was thrown")
            assertEquals(2L, pass.providerFailed)
            assertTrue(pass.anyFailed)
            val said = pass.trouble()
            assertTrue(said.contains("2 recordings could not be transcribed"), "'$said'")
            assertTrue(said.contains("try again"), "'$said'")
        }
    }

    @Test
    fun aProviderThatThrowsStillSaysSoAndBothCanHappenAtOnce() {
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            val subject = state(vault)
            // A pass that failed a job and then died: both facts survive,
            // and the count comes first because it is the one about the
            // person's own recordings.
            subject.externalProcessing = { error("the caption model could not be loaded") }
            val thrownOnly = subject.processAll(gilune)
            assertTrue(thrownOnly.trouble().contains("transcription provider failed"))

            val both = GiluneState.ProcessingPass(
                outputs = 0L,
                failed = 0L,
                providerFailed = 1L,
                providerFailure = "the engine went away",
            )
            val said = both.trouble()
            assertTrue(
                said.indexOf("1 recording could not be transcribed") <
                    said.indexOf("the engine went away"),
                "the count comes first: '$said'",
            )
            assertTrue(said.contains("the engine went away"), "'$said'")
        }
    }

    @Test
    fun oneFailureReadsAsOneRatherThanAsPlural() {
        assertEquals("", providerFailedSuffix(0L))
        assertTrue(providerFailedSuffix(1L).contains("1 recording could not"))
        assertTrue(providerFailedSuffix(4L).contains("4 recordings could not"))
    }
}
