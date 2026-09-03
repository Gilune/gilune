// When the 45 MB caption model is fetched, and when it deliberately is not
// (ADR 0018 § 4).
//
// **The defect these pin.** R1 gated the download on the caption switch,
// because the caption was the model's only use. R3 dropped that gate,
// because the same model now decodes the committed transcript and gating on
// the caption would leave somebody with a transcriber that can never
// transcribe. Both were right about their own half — and dropping the
// condition outright made the fetch unconditional for an enabled plugin, so
// an R1 user who had turned the caption off got a silent 45 MB download on
// upgrade. § 4's whole posture is that nothing is fetched because the app
// opened; a fetch for a feature nobody has switched on is the same fault by
// a slower route.
//
// The honest condition is the union: the caption is on, **or** this is the
// engine writing the transcripts.
//
// Pure over its inputs, so the matrix is a table rather than a device.

package org.gilune.app

import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoonshinePlanTest {

    private fun row(
        pluginId: String,
        state: String = "enabled",
        granted: List<String> = emptyList(),
        settings: String = "",
    ): PluginInfo = PluginInfo(
        pluginId = pluginId,
        name = pluginId,
        `class` = "provider",
        profile = "bundled",
        required = false,
        state = state,
        processorId = null,
        subscribes = null,
        emits = emptyList(),
        capabilities = emptyList(),
        granted = granted,
        ungranted = emptyList(),
        contributions = emptyList(),
        settings = settings,
    )

    /// Caption off, and everything else left at its default.
    private fun captionOff(model: String = DEFAULT_MOONSHINE_MODEL) =
        moonshineSettingsDocument(model, caption = false, wifiOnly = true)

    private fun captionOn(wifiOnly: Boolean = true, model: String = DEFAULT_MOONSHINE_MODEL) =
        moonshineSettingsDocument(model, caption = true, wifiOnly = wifiOnly)

    private fun consented(settings: String, state: String = "enabled") =
        row(MOONSHINE_PLUGIN, state, listOf(NETWORK_CAPABILITY), settings)

    private val absent: (MoonshineModel) -> Boolean = { false }
    private val present: (MoonshineModel) -> Boolean = { true }

    // ------------------------------------------------------------------
    // The condition that came back
    // ------------------------------------------------------------------

    @Test
    fun anR1UserWhoTurnedTheCaptionOffGetsNoSilentDownloadOnUpgrade() {
        // Moonshine enabled and whisper still the transcriber, caption
        // off: nothing here would use the model, so nothing fetches it.
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOff()))
        assertFalse(moonshineModelWanted(registry))
        val plan = moonshinePlan(registry, absent, metered = false)
        assertIs<MoonshinePlan.Unused>(plan, "45 MB must not be fetched for nothing")
    }

    @Test
    fun theCaptionAloneIsEnoughToWantTheModel() {
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOn()))
        assertTrue(moonshineModelWanted(registry))
        assertIs<MoonshinePlan.Download>(moonshinePlan(registry, absent, metered = false))
    }

    @Test
    fun beingTheTranscriberIsEnoughEvenWithTheCaptionOff() {
        // R3's own argument, kept: an engine that writes the committed
        // transcript needs its model whether or not anybody watches words
        // arrive.
        val registry = listOf(row(TRANSCRIPTION_PLUGIN, "disabled"), consented(captionOff()))
        assertEquals(MOONSHINE_PLUGIN, registry.activeTranscriber())
        assertTrue(moonshineModelWanted(registry))
        assertIs<MoonshinePlan.Download>(moonshinePlan(registry, absent, metered = false))
    }

    @Test
    fun anAbsentCaptionSettingReadsAsOnAndSoAsWanted() {
        // Absent means on (§ 5), and a settings document written before the
        // key existed must not read as a switch nobody ever saw holding
        // the feature back.
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), consented(settings = ""))
        assertTrue(moonshineModelWanted(registry))
    }

    // ------------------------------------------------------------------
    // The order of the questions
    // ------------------------------------------------------------------

    @Test
    fun aModelAlreadyOnDiskIsReadyWhateverTheSwitchesSay() {
        // Nothing to fetch means nothing to decide.
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOff()))
        assertIs<MoonshinePlan.Ready>(moonshinePlan(registry, present, metered = true))
    }

    @Test
    fun somebodyWhoWouldNotUseTheModelIsNeverAskedToAllowItsDownload() {
        // "Unused" is decided before consent deliberately: a person who
        // has switched the feature off should not meet a request to allow
        // network access for it.
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), row(MOONSHINE_PLUGIN, settings = captionOff()))
        assertIs<MoonshinePlan.Unused>(moonshinePlan(registry, absent, metered = false))
    }

    @Test
    fun consentIsStillAPositiveGrantForSomebodyWhoWouldUseIt() {
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), row(MOONSHINE_PLUGIN, settings = captionOn()))
        assertIs<MoonshinePlan.NeedsConsent>(moonshinePlan(registry, absent, metered = false))
    }

    @Test
    fun aMeteredConnectionStillWaitsForWifiWhenTheModelIsWanted() {
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOn(wifiOnly = true)))
        assertIs<MoonshinePlan.WaitingForWifi>(moonshinePlan(registry, absent, metered = true))
        // And the setting is what decides it, not the connection alone.
        val anyConnection = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOn(wifiOnly = false)))
        assertIs<MoonshinePlan.Download>(moonshinePlan(anyConnection, absent, metered = true))
    }

    @Test
    fun aProviderThatIsOffOrAbsentIsStillNoProviderAtAll() {
        assertIs<MoonshinePlan.NoProvider>(
            moonshinePlan(listOf(row(TRANSCRIPTION_PLUGIN)), absent, metered = false),
        )
        assertIs<MoonshinePlan.NoProvider>(
            moonshinePlan(
                listOf(row(MOONSHINE_PLUGIN, "disabled", settings = captionOn())),
                absent,
                metered = false,
            ),
        )
    }

    // ------------------------------------------------------------------
    // The trigger: what makes the shell ask again
    // ------------------------------------------------------------------

    @Test
    fun theTriggerChangesWhenTheOtherTranscriberIsSwitchedOff() {
        // The subtle one. `moonshineModelWanted` asks who is active, so
        // switching *whisper* off changes this plan without touching
        // Moonshine's own row — and the hand-rolled key, which read only
        // Moonshine's row, would never have re-run.
        val before = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOff()))
        val after = listOf(row(TRANSCRIPTION_PLUGIN, "disabled"), consented(captionOff()))
        assertNotEquals(moonshineTrigger(before, metered = false), moonshineTrigger(after, metered = false))
    }

    @Test
    fun theTriggerChangesWithTheConnectionSoWaitingForWifiCanEnd() {
        // "Waiting for Wi-Fi to download the caption model" was a true
        // sentence that never stopped being true: the connection was read
        // once, and joining Wi-Fi asked nobody anything.
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOn()))
        assertNotEquals(
            moonshineTrigger(registry, metered = true),
            moonshineTrigger(registry, metered = false),
        )
    }

    @Test
    fun theTriggerAlsoFollowsTheGrantTheStateAndTheSettings() {
        val base = listOf(row(TRANSCRIPTION_PLUGIN), row(MOONSHINE_PLUGIN, settings = captionOn()))
        val granted = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOn()))
        val off = listOf(row(TRANSCRIPTION_PLUGIN), row(MOONSHINE_PLUGIN, "disabled", settings = captionOn()))
        val other = listOf(row(TRANSCRIPTION_PLUGIN), row(MOONSHINE_PLUGIN, settings = captionOff()))
        val keys = listOf(base, granted, off, other).map { moonshineTrigger(it, metered = false) }
        assertEquals(keys.size, keys.toSet().size, "each input the plan reads must move the key")
    }

    @Test
    fun anUnchangedRegistryProducesTheSameTriggerSoNothingReRuns() {
        // The other half: a key that changed on every recomposition would
        // start a download per frame.
        val registry = listOf(row(TRANSCRIPTION_PLUGIN), consented(captionOn()))
        assertEquals(
            moonshineTrigger(registry, metered = false),
            moonshineTrigger(registry.toList(), metered = false),
        )
    }

    @Test
    fun thereIsNoTriggerBeforeAVaultHasProducedARegistry() {
        // So the effect never runs against an empty list and concludes
        // there is no provider.
        assertNull(moonshineTrigger(emptyList(), metered = false))
        assertNull(moonshineTrigger(listOf(row(TRANSCRIPTION_PLUGIN)), metered = false))
    }
}
