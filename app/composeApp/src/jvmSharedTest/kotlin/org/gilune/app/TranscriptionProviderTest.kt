// One active transcriber (ADR 0018 § 3), as data.
//
// § 3 puts this rule in the shell rather than the kernel — "which provider
// is active is host policy" — so the rule is a pure function over the
// plugin list and this is where it is pinned. Nothing here opens a vault,
// loads a model, or knows what Moonshine is beyond its id.
//
// It runs in BOTH suites: `jvmSharedTest` is on the desktop source set and
// is also compiled into the instrumented one, so the policy the phone obeys
// is the policy the desktop asserts.

package org.gilune.app

import org.gilune.plugins.tasks.TASKS_PLUGIN

import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun providerPlugin(
    pluginId: String,
    state: String = "enabled",
    name: String = pluginId,
    capabilities: List<String> = emptyList(),
): PluginInfo = PluginInfo(
    pluginId = pluginId,
    name = name,
    `class` = "provider",
    profile = "bundled",
    required = false,
    state = state,
    processorId = null,
    subscribes = null,
    emits = emptyList(),
    capabilities = capabilities,
    granted = emptyList(),
    ungranted = emptyList(),
    contributions = emptyList(),
    settings = "",
)

private fun whisper(state: String = "enabled") =
    providerPlugin(TRANSCRIPTION_PLUGIN, state, name = "Local transcription")

private fun moonshine(state: String = "enabled") =
    providerPlugin(MOONSHINE_PLUGIN, state, name = "Realtime transcription (Moonshine)")

class TranscriptionProviderTest {

    // ------------------------------------------------- the declaration grew

    @Test
    fun moonshineDeclaresItsOwnProcessorAndTheEventsItSitsBetween() {
        val declaration = moonshineDeclaration()
        assertEquals(MOONSHINE_PROCESSOR, declaration.processorId)
        assertEquals("moonshine-transcribe", declaration.processorId)
        assertEquals(SOURCE_READY_EVENT, declaration.subscribes)
        assertEquals(listOf(REVISION_ADDED_EVENT), declaration.emits)
    }

    @Test
    fun theTwoProvidersNeverShareAProcessorId() {
        // § 3: a shared id puts both plugins on one subscription row —
        // disabling either would disable the other — and both would write
        // origin `processor:transcribe`, so the vault could not say which
        // engine wrote a note. Whisper's id is the kernel's own constant;
        // this asserts the two can never be confused.
        assertTrue(MOONSHINE_PROCESSOR != "transcribe")
    }

    @Test
    fun theProviderDeclaresWhatClaimingAndSubmittingActuallyDo() {
        val declared = moonshineDeclaration().capabilities
        assertEquals(listOf("blobs.read", "objects.write", NETWORK_CAPABILITY), declared)
    }

    // ------------------------------------------------------ who is active

    @Test
    fun exactlyOneProviderIsActiveAndNoneIsALegalAnswer() {
        assertNull(emptyList<PluginInfo>().activeTranscriber())
        assertNull(listOf(whisper("disabled"), moonshine("disabled")).activeTranscriber())
        assertEquals(TRANSCRIPTION_PLUGIN, listOf(whisper(), moonshine("disabled")).activeTranscriber())
        assertEquals(MOONSHINE_PLUGIN, listOf(whisper("disabled"), moonshine()).activeTranscriber())
        assertEquals(
            MOONSHINE_PLUGIN,
            listOf(whisper("removed"), moonshine()).activeTranscriber(),
            "a removed provider is not an active one",
        )
    }

    @Test
    fun bothEnabledIsTheHoleTheAdrNamesAndThisHostPicksOne() {
        // § 3's named hole: nothing in the kernel stops a CLI user or a
        // second host enabling both. The result is not corruption — both
        // transcripts would exist as revisions — but it is double battery
        // for one note. This host answers with the first in precedence
        // order, so its own provider loop claims nothing while whisper is
        // on, which narrows the hole to a race rather than closing it.
        assertEquals(TRANSCRIPTION_PLUGIN, listOf(whisper(), moonshine()).activeTranscriber())
        assertEquals(
            TRANSCRIPTION_PLUGIN,
            listOf(moonshine(), whisper()).activeTranscriber(),
            "precedence is the constant's order, not the list's",
        )
    }

    // ------------------------------------------------------ the switch rule

    @Test
    fun enablingOneProviderSwitchesTheOtherOff() {
        val switch = providerSwitch(listOf(whisper(), moonshine("disabled")), MOONSHINE_PLUGIN, enable = true)
        assertEquals(listOf(TRANSCRIPTION_PLUGIN), switch.disable)
        assertTrue(
            switch.message.contains("Local transcription switched off"),
            "§ 3: the confirmation says so in words — <${switch.message}>",
        )
        assertTrue(switch.message.contains("One transcriber writes your notes"))
    }

    @Test
    fun theRuleIsSymmetric() {
        val switch = providerSwitch(listOf(whisper("disabled"), moonshine()), TRANSCRIPTION_PLUGIN, enable = true)
        assertEquals(listOf(MOONSHINE_PLUGIN), switch.disable)
        assertTrue(switch.message.contains("Realtime transcription (Moonshine) switched off"))
    }

    @Test
    fun switchingOneOffTurnsNothingOn() {
        // Zero active transcribers is a legal state: recordings commit and
        // their jobs wait. Turning one off must never be a decision to turn
        // another on — that is a decision only a person makes.
        val switch = providerSwitch(listOf(whisper(), moonshine("disabled")), TRANSCRIPTION_PLUGIN, enable = false)
        assertEquals(emptyList(), switch.disable)
        assertEquals("Local transcription switched off", switch.message)
    }

    @Test
    fun enablingTheOnlyProviderDisplacesNothing() {
        val switch = providerSwitch(listOf(whisper("disabled")), TRANSCRIPTION_PLUGIN, enable = true)
        assertEquals(emptyList(), switch.disable)
        assertEquals("Local transcription switched on", switch.message)
        assertNull(providerSwitchWarning(listOf(whisper("disabled")), TRANSCRIPTION_PLUGIN))
    }

    @Test
    fun aPluginThatIsNotATranscriberIsUntouchedByTheRule() {
        val plugins = listOf(whisper(), installedTasksPlugin())
        val switch = providerSwitch(plugins, TASKS_PLUGIN, enable = true)
        assertEquals(emptyList(), switch.disable)
        assertNull(providerSwitchWarning(plugins, TASKS_PLUGIN))
    }

    @Test
    fun aDisabledProviderIsNotDisplacedAgain() {
        val switch = providerSwitch(listOf(whisper("disabled"), moonshine("disabled")), MOONSHINE_PLUGIN, true)
        assertEquals(emptyList(), switch.disable, "nothing to switch off")
    }

    @Test
    fun theWarningIsShownBeforeTheSwitchNotOnlyAfterIt() {
        val warning = providerSwitchWarning(listOf(whisper(), moonshine("disabled")), MOONSHINE_PLUGIN)
        assertEquals(
            "Switching this on switches Local transcription off — " +
                "one transcriber writes your notes.",
            warning,
        )
    }

    // ------------------------------------------------ consent for a grown declaration

    @Test
    fun aFreshInstallIsOfferedItsLocalCapabilitiesAndNeverTheNetwork() {
        val newly = newlyDeclaredLocalCapabilities(null, moonshineDeclaration().capabilities)
        assertEquals(listOf("blobs.read", "objects.write"), newly)
        assertTrue(NETWORK_CAPABILITY !in newly, "the 45 MB download always waits for an explicit yes")
    }

    @Test
    fun aDeclarationThatGrowsOffersOnlyWhatItJustAdded() {
        // The R1 → R3 case exactly: the installed Moonshine declared only
        // `network.request`, so nobody has ever been asked about the two
        // local capabilities the provider now needs.
        val r1 = providerPlugin(MOONSHINE_PLUGIN, capabilities = listOf(NETWORK_CAPABILITY))
        assertEquals(
            listOf("blobs.read", "objects.write"),
            newlyDeclaredLocalCapabilities(r1, moonshineDeclaration().capabilities),
        )
    }

    @Test
    fun aCapabilityAlreadyDeclaredIsNeverGrantedAgain() {
        // Which is what keeps a revoke a revoke: the installed declaration
        // named it, so a person has been offered it and their answer —
        // whatever it was — stands.
        val installed = providerPlugin(
            MOONSHINE_PLUGIN,
            capabilities = listOf("blobs.read", "objects.write", NETWORK_CAPABILITY),
        )
        assertEquals(
            emptyList(),
            newlyDeclaredLocalCapabilities(installed, moonshineDeclaration().capabilities),
        )
    }

    // ------------------------------------------------------- wordless scope

    @Test
    fun onlyRecordingsCountTowardsTheCatchUp() {
        // The catch-up's set is recordings without words; a text capture
        // has no audio to decode and is not one of them whatever its
        // preview says.
        assertTrue(summary(blob = "Captures/2026/a.wav").isRecording())
        assertTrue(summary(blob = "Captures/2026/A.WAV").isRecording())
        assertTrue(!summary(blob = "Captures/2026/a.txt").isRecording())
        assertTrue(!summary(blob = null).isRecording())
    }

    private fun summary(blob: String?) = uniffi.gilune_ffi.SourceSummary(
        objectId = "id",
        createdMs = 0,
        blobRelPath = blob,
        projectionRelPath = null,
        preview = "",
        interpreted = false,
    )
}
