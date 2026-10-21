// Registering a second transcriber, and the things it must not do
// (ADR 0018 § 3, § 6).
//
// **The defect these pin.** The kernel defaults a plugin it has never seen
// to Enabled — right for a plugin that is the only one of its kind, wrong
// for the second transcriber. Bundling Moonshine therefore switched it on
// for everybody: on a fresh install, and on an R1 → R3 upgrade where the
// row already existed. The visible half was a Plugins screen showing two
// transcribers on at once; the invisible half was every recording queueing
// a moonshine job that nothing in the process would ever claim, climbing
// for as long as the phone was used.
//
// Pure, and in `jvmSharedTest`, so the rule the phone obeys is the rule the
// desktop suite asserts.

package org.gilune.app

import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun plugin(
    pluginId: String,
    state: String = "enabled",
    name: String = pluginId,
    processorId: String? = null,
): PluginInfo = PluginInfo(
    pluginId = pluginId,
    name = name,
    `class` = "provider",
    profile = "bundled",
    required = false,
    state = state,
    processorId = processorId,
    subscribes = null,
    emits = emptyList(),
    capabilities = emptyList(),
    granted = emptyList(),
    ungranted = emptyList(),
    contributions = emptyList(),
    settings = "",
)

private fun whisperRow(state: String = "enabled") =
    plugin(TRANSCRIPTION_PLUGIN, state, "Local transcription", "transcribe")

private fun moonshineRow(state: String = "enabled") =
    plugin(MOONSHINE_PLUGIN, state, "Transcription (Moonshine)", MOONSHINE_PROCESSOR)

class ProviderRegistrationTest {

    // ------------------------------------------------------------------
    // The rule itself
    // ------------------------------------------------------------------

    @Test
    fun aFreshInstallLeavesExactlyOneTranscriberOnAndItIsTheIncumbent() {
        // Both rows enabled is what the kernel hands back after
        // registering Moonshine on a vault that has whisper. § 6 keeps
        // whisper the incumbent until MS2 has measured both engines on
        // real hardware, so Moonshine is the one that goes off.
        val registry = listOf(whisperRow(), moonshineRow())
        val decision = providerRegistration(registry, MOONSHINE_PLUGIN)
        assertTrue(decision.switchOff, "a second transcriber must not switch itself on")

        val after = registry.map {
            if (it.pluginId == MOONSHINE_PLUGIN) moonshineRow("disabled") else it
        }
        assertEquals(
            1,
            after.count { it.pluginId in TRANSCRIPTION_PROVIDERS && it.state == "enabled" },
            "exactly one transcription plugin is enabled at a time",
        )
        assertEquals(TRANSCRIPTION_PLUGIN, after.activeTranscriber())
    }

    @Test
    fun theSentenceSaysWhichEngineIsWritingAndHowToChangeIt() {
        val said = providerRegistration(listOf(whisperRow(), moonshineRow()), MOONSHINE_PLUGIN)
            .message
        assertTrue(said != null && said.contains("switched off"), "'$said'")
        assertTrue(said.contains("Local transcription"), "it must name the incumbent: '$said'")
        assertTrue(
            said.contains("One transcriber writes your notes"),
            "the same sentence the switch uses: '$said'",
        )
        assertTrue(said.contains("Settings"), "and where to change it: '$said'")
    }

    @Test
    fun aPersonWhoChoseMoonshineKeepsThatChoiceThroughEveryLaterOpen() {
        // The rule runs on every open, not only the first, so this is the
        // property that stops it being a rule that undoes a decision:
        // Moonshine on with whisper off means Moonshine IS the active one,
        // and there is nothing to do.
        val registry = listOf(whisperRow("disabled"), moonshineRow())
        assertFalse(providerRegistration(registry, MOONSHINE_PLUGIN).switchOff)
        assertNull(providerRegistration(registry, MOONSHINE_PLUGIN).message)
        assertEquals(MOONSHINE_PLUGIN, registry.activeTranscriber())
    }

    @Test
    fun anAlreadyDisabledProviderIsLeftWhereItIs() {
        // Off is off. Re-registering must not flip a switch in either
        // direction, and the decision here is what stops the "switch it
        // off" path from being run against something already off.
        val registry = listOf(whisperRow(), moonshineRow("disabled"))
        assertFalse(providerRegistration(registry, MOONSHINE_PLUGIN).switchOff)
    }

    @Test
    fun theIncumbentIsNeverTheOneSwitchedOffByItsOwnRegistration() {
        // Whisper registers on every open through `openVault`. Precedence
        // makes it the active one whenever it is enabled, so its own
        // registration can never displace it.
        assertFalse(
            providerRegistration(listOf(whisperRow(), moonshineRow()), TRANSCRIPTION_PLUGIN)
                .switchOff,
        )
    }

    @Test
    fun aPluginThatIsNotATranscriberIsNoneOfThisRulesBusiness() {
        val registry = listOf(whisperRow(), plugin("org.memory.tasks", name = "Tasks"))
        assertFalse(providerRegistration(registry, "org.memory.tasks").switchOff)
        // Nor is one the registry has never heard of.
        assertFalse(providerRegistration(registry, MOONSHINE_PLUGIN).switchOff)
    }

    @Test
    fun aRegistryWithNoTranscriberOnAtAllSwitchesNothingOn() {
        // Nobody active is a legal state — recordings commit and their
        // jobs wait — and this rule only ever switches off, so a provider
        // registered into it stays as it came.
        val registry = listOf(whisperRow("disabled"), moonshineRow("disabled"))
        assertFalse(providerRegistration(registry, MOONSHINE_PLUGIN).switchOff)
        assertNull(registry.activeTranscriber())
    }

    // ------------------------------------------------------------------
    // What "waiting jobs" means once something is parked
    // ------------------------------------------------------------------

    @Test
    fun aParkedProvidersJobsAreExplainedRatherThanCountedAsABacklog() {
        // Disabled means parked, not destroyed (§ 3), so the count in
        // Settings ▸ About can hold jobs nobody will ever claim. An
        // upgraded vault that ran both transcribers for a while carries
        // exactly those, and a bare number reads as work to clear.
        val note = waitingJobsNote(listOf(whisperRow(), moonshineRow("disabled")), 4L, 3L)
        assertTrue(note != null && note.contains("parked"), "'$note'")
        assertTrue(note.contains("Transcription (Moonshine)"), "name it: '$note'")
        assertTrue(note.contains("nothing is waiting on you"), "'$note'")
    }

    @Test
    fun bothNumbersAreSaidRatherThanHedgedAsSome() {
        // **The second-order case.** The first round said "some are parked"
        // because the shell could see which plugins were off but not how
        // much work belonged to them — a hedge in front of a number a
        // person is looking at. The kernel counts parked work now, so the
        // sentence says how many wait on a switch and how many do not.
        val mixed = waitingJobsNote(listOf(whisperRow(), moonshineRow("disabled")), 4L, 3L)!!
        assertTrue(mixed.startsWith("3 parked"), "'$mixed'")
        assertTrue(mixed.contains("1 more is waiting on Gilune"), "'$mixed'")
        assertFalse(mixed.contains("some are parked"), "the hedge came back: '$mixed'")

        // Every waiting job parked: there is no "more" to promise.
        val all = waitingJobsNote(listOf(whisperRow(), moonshineRow("disabled")), 3L, 3L)!!
        assertTrue(all.contains("nothing else is waiting"), "'$all'")

        // Plural, because 2 of them is not "1 more".
        val two = waitingJobsNote(listOf(whisperRow(), moonshineRow("disabled")), 5L, 3L)!!
        assertTrue(two.contains("2 more are waiting"), "'$two'")
    }

    @Test
    fun nothingParkedAndNothingWaitingBothSayNothing() {
        assertNull(waitingJobsNote(listOf(whisperRow(), moonshineRow("disabled")), 0L, 0L))
        assertNull(waitingJobsNote(listOf(whisperRow(), moonshineRow()), 7L, 0L))
        // A vault with work waiting and none of it parked has nothing to
        // explain — the jobs are simply queued and will run.
        assertNull(waitingJobsNote(listOf(whisperRow(), moonshineRow("disabled")), 7L, 0L))
        // A switched-off plugin with no processor parks nothing, and the
        // kernel agrees by counting none.
        assertNull(waitingJobsNote(listOf(plugin("org.memory.tasks", "disabled")), 7L, 0L))
    }

    @Test
    fun parkedWorkIsStillSaidWhenThisBuildCannotNameThePluginHoldingIt() {
        // The kernel's count is the fact; naming the plugin is a courtesy.
        // A vault carrying a plugin this build has never heard of — or one
        // removed since its jobs were queued — must not silence a number.
        val note = waitingJobsNote(emptyList(), 2L, 2L)!!
        assertTrue(note.startsWith("2 parked"), "'$note'")
        assertTrue(note.contains("switched off"), "'$note'")
    }

    // ------------------------------------------------------------------
    // A provider that threw (ADR 0018 § 2)
    // ------------------------------------------------------------------

    @Test
    fun aProviderThatThrewIsNamedRatherThanCountedAsZero() {
        // The external route's outputs are added to the kernel's, so a
        // provider that never ran leaves the count at zero — and "0
        // transcribed" reads as *there was nothing to do*, which is the
        // opposite of what happened.
        val said = providerFailureSuffix("the model could not be loaded")
        assertTrue(said.contains("transcription provider failed"), "'$said'")
        assertTrue(said.contains("the model could not be loaded"), "'$said'")
    }

    @Test
    fun aPassWithNothingWrongAddsNothingToTheSentence() {
        assertEquals("", providerFailureSuffix(null))
        assertEquals("", providerFailureSuffix(""))
        assertEquals("", providerFailureSuffix("   "))
    }

    // ------------------------------------------------------------------
    // Projection after an exchange
    // ------------------------------------------------------------------

    @Test
    fun anExchangeThatReceivedNothingHasNothingToProject() {
        assertFalse(syncNeedsProjection(0L, 0L))
        assertTrue(syncNeedsProjection(1L, 0L))
        // Sending is not receiving: what this device sent is already on
        // its own disk.
        assertFalse(syncNeedsProjection(0L, 0L))
    }

    @Test
    fun aSessionThatOnlyCompletedBlobsStillProjects() {
        // The second-order case the first round of this fix missed: an
        // operation applies in one session and the bytes it refers to
        // arrive in the next, which applies nothing at all. Without a pass
        // the note goes on pointing at a recording the vault already holds.
        assertTrue(syncNeedsProjection(0L, 1L))
        assertTrue(syncNeedsProjection(2L, 3L))
    }

    // ------------------------------------------------------------------
    // The one enrolment failure with a way out (ADR 0019 § 4)
    // ------------------------------------------------------------------

    @Test
    fun aSealedIdentityWhoseKeyIsGoneIsRecognisedByItsTypeNotItsWording() {
        // The kernel raises its own error variant for this and for nothing
        // else, so the question is the type.
        assertTrue(
            identityUnreadable(
                uniffi.gilune_ffi.GiluneException.IdentityUnreadable(
                    "identity unreadable: the Keystore key that sealed this device's " +
                        "identity is no longer on this phone",
                ),
            ),
        )
        // **The second-order case.** Reword the kernel's sentence to
        // something that shares not one phrase with the old prose matcher,
        // and the way out must still be offered. Under the shipped string
        // matching this returned false and the button silently vanished.
        assertTrue(
            identityUnreadable(
                uniffi.gilune_ffi.GiluneException.IdentityUnreadable("sealed elsewhere"),
            ),
        )
    }

    @Test
    fun everyOtherFailureIsLeftAloneRatherThanOfferedAReset() {
        // A reset throws away a pairing. Offering it for a fault it cannot
        // fix would cost somebody their roster for nothing.
        assertFalse(identityUnreadable(null))
        assertFalse(identityUnreadable(uniffi.gilune_ffi.GiluneException.Storage("disk full")))
        assertFalse(identityUnreadable(IllegalStateException("the port was already taken")))
        // And the other side of the same coin: a storage failure whose text
        // happens to read like the identity one is still not it. Under the
        // shipped prose matcher this offered to throw away a working
        // identity.
        assertFalse(
            identityUnreadable(
                uniffi.gilune_ffi.GiluneException.Storage(
                    "could not open the identity key file: disk error",
                ),
            ),
        )
    }

    @Test
    fun theSentenceStatesTheExactScopeBeforeAnybodyPressesTheButton() {
        val said = identityUnreadableSentence("this phone")
        assertTrue(said.contains("not damage"), "'$said'")
        assertTrue(said.contains("touches nothing in your vault"), "the scope: '$said'")
        assertTrue(said.contains("paired again"), "the cost: '$said'")
        assertTrue(said.contains("not an undo"), "§ 9's rule holds here too: '$said'")
        assertFalse(said.contains("this device"), "it says what the shell calls itself")
    }
}
