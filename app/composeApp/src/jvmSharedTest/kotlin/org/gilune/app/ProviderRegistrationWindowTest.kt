// Registering the second transcriber, and the window that used to exist
// between registering it and switching it off (ADR 0018 § 3).
//
// The kernel defaults a plugin it has never seen to Enabled, so the shipped
// order was: register (on) → read the registry → switch off. In between,
// this **vault** has two enabled transcribers and a live subscription for
// the new one — and the vault is shared. The recording service holds its
// own handle, and multi-instance on one vault is supported use (owner
// decision, 2026-08-31), so a capture committed by that other handle inside
// the window queues a job for a provider that is about to be switched off.
// A job queued for a disabled provider is parked rather than run, which
// makes it an orphan: nobody claims it, and Settings ▸ About counts it as
// work waiting.
//
// So this runs against a **real vault through the real FFI**, and asserts
// the state at the instant registration returns rather than at the end of
// the sequence — the end was always right; it was the middle that was
// wrong.
//
// In `jvmSharedTest`, so the desktop suite asserts the rule the phone obeys.

package org.gilune.app

import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.PluginDeclaration
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRegistrationWindowTest {

    private fun scratch(): String = Files.createTempDirectory("gilune-window").toString()

    /// The incumbent transcriber, declared by the test rather than found.
    ///
    /// On a phone, whisper's own registration happens inside `Gilune::open`
    /// — it is a **Rust** plugin and the mobile build carries it — so an
    /// Android vault has an active transcriber the moment it opens. The
    /// desktop test build does not carry that engine, so the row is simply
    /// not there, and a fixture that assumed it would have been asserting
    /// against a vault no shipped phone ever has.
    ///
    /// Declaring it here is the honest stand-in: the shell's rule reads the
    /// **registry**, not the engine, so what matters is that a plugin with
    /// whisper's id is installed and enabled — which is exactly the state
    /// `open` leaves behind wherever the engine is compiled in.
    private fun incumbent(gilune: Gilune) {
        gilune.registerPlugin(
            PluginDeclaration(
                pluginId = TRANSCRIPTION_PLUGIN,
                name = "Local transcription",
                `class` = "provider",
                profile = "bundled",
                required = false,
                processorId = "transcribe",
                subscribes = SOURCE_READY_EVENT,
                emits = listOf(REVISION_ADDED_EVENT),
                capabilities = emptyList(),
                contributions = emptyList(),
            ),
        )
    }

    @Test
    fun aSecondTranscriberIsNeverEvenBrieflyEnabled() {
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            // An installed, enabled whisper is the state every existing
            // vault on a phone is in.
            incumbent(gilune)
            assertEquals(
                TRANSCRIPTION_PLUGIN,
                gilune.plugins().activeTranscriber(),
                "the fixture is wrong: nothing was writing the transcripts",
            )
            val declaration = moonshineDeclaration()
            val installed = gilune.plugins().firstOrNull { it.pluginId == MOONSHINE_PLUGIN }
            assertNull(installed, "the fixture is wrong: this vault already knows the provider")

            val state = registerProviderRespectingTheRule(gilune, declaration, installed)

            // **At the instant registration returns.** Nothing has run the
            // one-transcriber rule yet, and there is nothing for it to fix.
            assertEquals("disabled", state, "the provider came up enabled")
            assertEquals(
                "disabled",
                gilune.plugins().single { it.pluginId == MOONSHINE_PLUGIN }.state,
            )
            assertEquals(
                TRANSCRIPTION_PLUGIN,
                gilune.plugins().activeTranscriber(),
                "two transcribers were active at once",
            )

            // And the subscription is dead with it: a capture committed at
            // this instant by another handle — the recording service's,
            // say — queues nothing for the provider nobody has agreed to.
            //
            // Proven by switching the provider on **afterwards** and
            // finding nothing to claim: switching on resumes parked work
            // but never backfills (M6a), so an empty claim here means no
            // job was ever queued rather than one being hidden.
            gilune.captureText("a capture that lands inside the old window")
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, true)
            gilune.registerExternalProcessor(MOONSHINE_PLUGIN, MOONSHINE_PROCESSOR)
            assertNull(
                gilune.claimJob(MOONSHINE_PROCESSOR),
                "a job was queued for a provider that was switched off when the capture landed",
            )
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, false)

            // The rule that used to close the window afterwards now finds
            // nothing to do, which is the point: it stays for the *other*
            // case and is not what makes this one safe.
            assertNull(applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN))
        }
    }

    @Test
    fun aVaultWithNoOtherTranscriberGetsTheProviderTheOrdinaryWay() {
        // The rule is "another transcriber is already writing", not
        // "always off": with whisper switched off, a person who installs
        // the provider gets a provider, and registering it must not
        // second-guess that.
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            incumbent(gilune)
            gilune.setPluginEnabled(TRANSCRIPTION_PLUGIN, false)
            assertNull(gilune.plugins().activeTranscriber())
            val state = registerProviderRespectingTheRule(gilune, moonshineDeclaration(), null)
            assertEquals("enabled", state)
            assertEquals(MOONSHINE_PLUGIN, gilune.plugins().activeTranscriber())
        }
    }

    @Test
    fun aVaultThatAlreadyKnowsTheProviderKeepsWhateverStateItRecorded() {
        // Registration is idempotent and never a decision: a person who
        // chose the provider keeps it through every later open, and a
        // vault that has it switched off keeps that too. The upgrade case
        // — installed, enabled, and another transcriber active — is
        // `applyOneTranscriberRule`'s, because there the state is a
        // decision the vault already recorded and registration may not
        // quietly overwrite it.
        val vault = scratch()
        Gilune.open(vault).use { gilune ->
            incumbent(gilune)
            gilune.registerPlugin(moonshineDeclaration())
            gilune.setPluginEnabled(MOONSHINE_PLUGIN, true)
            val installed = gilune.plugins().single { it.pluginId == MOONSHINE_PLUGIN }
            assertEquals("enabled", registerProviderRespectingTheRule(gilune, moonshineDeclaration(), installed))
            assertEquals(
                "enabled",
                gilune.plugins().single { it.pluginId == MOONSHINE_PLUGIN }.state,
                "registering changed a state the vault had recorded",
            )
            // And *that* is where the one-transcriber rule earns its keep.
            assertTrue(applyOneTranscriberRule(gilune, MOONSHINE_PLUGIN) != null)
            assertEquals(
                "disabled",
                gilune.plugins().single { it.pluginId == MOONSHINE_PLUGIN }.state,
            )
        }
    }
}
