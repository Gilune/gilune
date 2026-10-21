// The live caption's decisions (ADR 0018 § 1, § 4, § 5), tested without a
// model, a microphone or a network.
//
// Every rule the caption has is a pure function over an event, a registry
// or a manifest, so the whole matrix is reachable from plain assertions.
// What is left over is the engine itself and the recording it must not
// disturb, which is `MoonshineCaptionTest`'s.
//
// **The event trajectories below are transcribed from R0's measured
// trace** (`spikes/r0-moonshine/README.md` § Question 5), not invented. If
// a later Moonshine release changes them, these tests should be updated
// from a new measurement rather than adjusted to keep passing.

package org.gilune.app

import org.gilune.plugin.sdk.Slots
import org.gilune.plugins.tasks.tasksDeclaration

import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionTest {
    private fun plugin(
        id: String = MOONSHINE_PLUGIN,
        state: String = "enabled",
        granted: List<String> = listOf(NETWORK_CAPABILITY),
        settings: String = "",
    ) = PluginInfo(
        pluginId = id,
        name = id,
        `class` = "provider",
        profile = "bundled",
        required = false,
        state = state,
        processorId = null,
        subscribes = null,
        emits = emptyList(),
        capabilities = listOf(NETWORK_CAPABILITY),
        granted = granted,
        ungranted = listOf(NETWORK_CAPABILITY) - granted.toSet(),
        contributions = emptyList<PluginContribution>(),
        settings = settings,
    )

    // ------------------------------------------------------------ the gate

    @Test
    fun theCaptionNeedsThePluginTheSettingAndTheModel() {
        // ADR 0018 § 1: "Gated: plugin enabled AND caption setting AND
        // model present — otherwise recording behaves exactly as today."
        val on = moonshineSettingsDocument(DEFAULT_MOONSHINE_MODEL, caption = true, wifiOnly = true)
        val off = moonshineSettingsDocument(DEFAULT_MOONSHINE_MODEL, caption = false, wifiOnly = true)

        assertTrue(captionOffered(listOf(plugin(settings = on)), modelPresent = true))
        assertFalse(captionOffered(listOf(plugin(settings = on)), modelPresent = false), "no model, no caption")
        assertFalse(captionOffered(listOf(plugin(settings = off)), modelPresent = true), "the setting is off")
        assertFalse(
            captionOffered(listOf(plugin(state = "disabled", settings = on)), modelPresent = true),
            "a disabled plugin captions nothing",
        )
        assertFalse(captionOffered(emptyList(), modelPresent = true), "a removed plugin captions nothing")
        assertFalse(
            captionOffered(listOf(plugin(id = TRANSCRIPTION_PLUGIN, settings = on)), modelPresent = true),
            "another plugin's settings must not switch this one on",
        )
    }

    @Test
    fun theCaptionIsOnByDefaultWhenNobodyHasChosen() {
        // "Absent means on" (§ 5: caption on by default). A settings
        // document written before this key existed must read as the
        // shipped default, not as a switch quietly holding it back.
        assertTrue(captionOffered(listOf(plugin(settings = "")), modelPresent = true))
        assertTrue(captionOffered(listOf(plugin(settings = "{}")), modelPresent = true))
        assertTrue(listOf(plugin(settings = "")).moonshineCaption())
    }

    @Test
    fun revokingNetworkPermissionDoesNotSilenceAModelAlreadyOnDisk() {
        // Consent gates the DOWNLOAD. Revoking it means "fetch nothing
        // more", not "forget the file you have" — so the gate deliberately
        // does not consult the grant list.
        val revoked = plugin(granted = emptyList())
        assertTrue(captionOffered(listOf(revoked), modelPresent = true))
    }

    // ------------------------------------------------------------- the plan

    @Test
    fun nothingIsFetchedForAMissingOrSwitchedOffProvider() {
        val on = { plugins: List<PluginInfo> -> moonshinePlan(plugins, { false }, metered = false) }
        assertEquals(MoonshinePlan.NoProvider, on(emptyList()))
        assertEquals(MoonshinePlan.NoProvider, on(listOf(plugin(state = "disabled"))))
        assertEquals(MoonshinePlan.NoProvider, on(listOf(plugin(state = "removed"))))
    }

    @Test
    fun theCaptionSwitchIsNotTheOnlyThingThatCanWantTheModel() {
        // **R1's rule, overturned by R3, and half of it put back.** While
        // the caption was the model's only use, fetching 45 MB for a
        // switched-off feature would have been a fault. The same model now
        // writes the note's committed words (ADR 0018 § 2), so gating the
        // download on the caption alone would leave a person who turned the
        // caption off with a transcriber that can never transcribe.
        //
        // R3 dropped the condition outright, and that was the error: it
        // made the fetch unconditional for an enabled plugin. The honest
        // condition is the union, and this registry is its first half —
        // Moonshine is the only provider here, so it IS the transcriber and
        // the model is wanted whatever the caption says.
        val off = moonshineSettingsDocument(DEFAULT_MOONSHINE_MODEL, caption = false, wifiOnly = false)
        val alone = listOf(plugin(settings = off))
        assertTrue(moonshineModelWanted(alone), "it is the engine writing the transcripts")
        assertEquals(
            MoonshinePlan.Download(moonshineModel(null)),
            moonshinePlan(alone, { false }, metered = false),
        )
        // The caption's own gate is untouched: it is off, so no caption.
        assertFalse(captionOffered(alone, modelPresent = true))

        // The other half, which is where the silent 45 MB came from: put
        // whisper back and Moonshine is neither the transcriber nor
        // captioning, so nothing here would use the model and nothing
        // fetches it. `MoonshinePlanTest` holds the full matrix.
        val beside = listOf(plugin(id = TRANSCRIPTION_PLUGIN), plugin(settings = off))
        assertFalse(moonshineModelWanted(beside))
        assertEquals(
            MoonshinePlan.Unused(moonshineModel(null)),
            moonshinePlan(beside, { false }, metered = false),
        )
    }

    @Test
    fun nothingIsFetchedWithoutAnExplicitYes() {
        // § 4: consent is a positive grant. A capability that is not
        // granted at all must fail towards not downloading.
        val plan = moonshinePlan(listOf(plugin(granted = emptyList())), { false }, metered = false)
        assertEquals(MoonshinePlan.NeedsConsent(moonshineModel(null)), plan)
    }

    @Test
    fun aMeteredConnectionWaitsUnlessTheUserSaidOtherwise() {
        val wifiOnly = moonshineSettingsDocument(DEFAULT_MOONSHINE_MODEL, caption = true, wifiOnly = true)
        val anyNetwork = moonshineSettingsDocument(DEFAULT_MOONSHINE_MODEL, caption = true, wifiOnly = false)
        val model = moonshineModel(null)

        assertEquals(
            MoonshinePlan.WaitingForWifi(model),
            moonshinePlan(listOf(plugin(settings = wifiOnly)), { false }, metered = true),
        )
        assertEquals(
            MoonshinePlan.Download(model),
            moonshinePlan(listOf(plugin(settings = wifiOnly)), { false }, metered = false),
        )
        assertEquals(
            MoonshinePlan.Download(model),
            moonshinePlan(listOf(plugin(settings = anyNetwork)), { false }, metered = true),
            "an explicit override is what it says it is",
        )
    }

    @Test
    fun wifiOnlyIsTheShippedDefault() {
        // The cautious default rather than the convenient one: 45 MB over
        // somebody's mobile data because an app decided to fetch is what
        // § 4's rule exists to prevent.
        assertTrue(listOf(plugin(settings = "")).moonshineWifiOnly())
        assertEquals(
            MoonshinePlan.WaitingForWifi(moonshineModel(null)),
            moonshinePlan(listOf(plugin(settings = "")), { false }, metered = true),
        )
    }

    @Test
    fun aModelOnDiskIsReadyWhateverTheNetworkSays() {
        val plan = moonshinePlan(listOf(plugin(granted = emptyList())), { true }, metered = true)
        assertEquals(MoonshinePlan.Ready(moonshineModel(null)), plan)
    }

    // -------------------------------------------------- the settings document

    @Test
    fun everyWriteCarriesAllThreeKeys() {
        // The tasks precedent: a write that carries one key must carry the
        // others, or turning the caption off silently reverts the model
        // somebody chose and re-opens a mobile-data download.
        val document = moonshineSettingsDocument("tiny-streaming-en", caption = false, wifiOnly = false)
        val plugins = listOf(plugin(settings = document))
        assertEquals("tiny-streaming-en", plugins.moonshineModelKey())
        assertFalse(plugins.moonshineCaption())
        assertFalse(plugins.moonshineWifiOnly())

        val back = moonshineSettingsDocument("tiny-streaming-en", caption = true, wifiOnly = true)
        assertTrue(listOf(plugin(settings = back)).moonshineCaption())
        assertTrue(listOf(plugin(settings = back)).moonshineWifiOnly())
    }

    @Test
    fun anUnknownModelKeyFallsBackRatherThanFailing() {
        assertEquals(DEFAULT_MOONSHINE_MODEL, moonshineModel("from-a-later-version").key)
        assertEquals(DEFAULT_MOONSHINE_MODEL, moonshineModel(null).key)
        assertEquals(DEFAULT_MOONSHINE_MODEL, moonshineModel("  ").key)
    }

    @Test
    fun theDeclarationPromisesExactlyWhatR3Delivers() {
        // R1's load-bearing assertion was that this declaration promised
        // NOTHING — no processor, no subscription, no events — because
        // declaring any of them would have queued jobs for an
        // implementation that did not exist. R3 built the implementation,
        // so the declaration now says so, and this asserts the pair moves
        // together: `MoonshineProvider.kt` claims the id declared here.
        val declaration = moonshineDeclaration()
        assertEquals(MOONSHINE_PLUGIN, declaration.pluginId)
        assertEquals(MOONSHINE_PROCESSOR, declaration.processorId)
        assertEquals(SOURCE_READY_EVENT, declaration.subscribes)
        assertEquals(listOf(REVISION_ADDED_EVENT), declaration.emits)
        assertFalse(declaration.required)
        assertEquals("provider", declaration.`class`)
        assertEquals("bundled", declaration.profile)

        // § 5: the AAR is packaged, so the code needs no consent beyond
        // the enable switch. The two local capabilities are what claiming
        // and submitting do; the model download is the only ask.
        assertEquals(
            listOf("blobs.read", "objects.write", NETWORK_CAPABILITY),
            declaration.capabilities,
        )

        // It does NOT contribute capture.actions — recording belongs to
        // the voice adapter, so removing this plugin leaves recording
        // intact.
        assertEquals(listOf(Slots.SETTINGS_FORM), declaration.contributions.map { it.slot })
    }

    @Test
    fun theProviderIsNotRegisteredByTheSharedOpenPath() {
        // "bundled on mobile only in v1" (§ 5), enforced where it can be:
        // the shared declarations are the microphone and tasks, and the
        // desktop must never learn about this one.
        assertFalse(
            listOf(voiceCaptureDeclaration(), tasksDeclaration()).any { it.pluginId == MOONSHINE_PLUGIN },
        )
    }

    // ------------------------------------------------------------ the model

    @Test
    fun theModelIsWhatR0MeasuredNotWhatTheAdrEstimated() {
        // R0 corrected § 4: 45.23 MB across 8 files, not 77.7 MB across
        // seven. The consent ask a person is shown changes with it.
        val model = moonshineModel(null)
        assertEquals("45 MB", model.size)
        assertEquals(8, model.files)
        assertEquals("en", model.language)
        assertEquals(MOONSHINE_ARCH_TINY_STREAMING, model.arch)
        assertTrue(consentNotice(model).contains("45 MB"))
    }

    @Test
    fun theStatusLineSaysOnlyWhatIsTrue() {
        assertEquals("not downloaded", moonshineStatus(MoonshinePhase.Absent, null))
        assertEquals("downloaded and ready", moonshineStatus(MoonshinePhase.Ready, null))
        assertEquals("downloading…", moonshineStatus(MoonshinePhase.Downloading, null))
        assertEquals(
            "downloading — 50%, file 3 of 8",
            moonshineStatus(MoonshinePhase.Downloading, MoonshineProgress(3, 8, 50, 100)),
        )
        assertEquals(
            "downloading file 3 of 8…",
            moonshineStatus(MoonshinePhase.Downloading, MoonshineProgress(3, 8, 50, 0)),
            "a server that did not say how big the file is must not produce a percentage",
        )
        assertTrue(moonshineStatus(MoonshinePhase.Failed, null).contains("recordings are unaffected"))
    }

    // ------------------------------------------------------------------
    // One fold, two retentions
    //
    // The caption and the committed transcript obey the same five findings
    // and differ in exactly one thing: what they are allowed to forget.
    // They were two hand-written folds until this round, so these say what
    // "the same fold" must keep meaning.
    // ------------------------------------------------------------------

    @Test
    fun theCaptionAndTheTranscriptShareOneFoldAndDifferOnlyInWhatTheyKeep() {
        val spoken = (0 until 12).map {
            CaptionEvent(CaptionEvent.TEXT_CHANGED, it.toLong(), "line$it", true)
        }
        val capped = spoken.fold(emptyList<CaptionLine>()) { lines, event -> foldCaption(lines, event) }
        assertEquals(CAPTION_RETAINED, capped.size, "the caption is a display and is capped")
        assertEquals("line11", capped.last().text)

        val whole = spoken.fold(emptyList<CaptionLine>()) { lines, event ->
            foldCaption(lines, event, retain = Int.MAX_VALUE)
        }
        assertEquals(12, whole.size, "a transcript that forgets is a data loss")
        assertEquals("line0", whole.first().text)

        // And the two holders agree with their folds, which is what makes
        // this a statement about the shipped classes rather than about a
        // function nobody calls.
        val buffer = CaptionBuffer()
        val collector = TranscriptCollector()
        for (event in spoken) {
            buffer.accept(event)
            collector.accept(event)
        }
        assertEquals(capped.map { it.text }, buffer.lines.map { it.text })
        assertEquals(whole.joinToString(" ") { it.text }, collector.text())
    }

    @Test
    fun bothRetentionsStillRefuseASettledRewriteAndABlankOpening() {
        // The findings are the fold's, not the caption's, so raising the
        // retention must not quietly relax them.
        for (retain in listOf(CAPTION_RETAINED, Int.MAX_VALUE)) {
            var lines = emptyList<CaptionLine>()
            lines = foldCaption(lines, CaptionEvent(CaptionEvent.TEXT_CHANGED, 1, "the quick", false), retain)
            lines = foldCaption(lines, CaptionEvent(CaptionEvent.COMPLETED, 1, "the quick brown fox.", true), retain)
            lines = foldCaption(lines, CaptionEvent(CaptionEvent.TEXT_CHANGED, 1, "nonsense", false), retain)
            lines = foldCaption(lines, CaptionEvent(CaptionEvent.TEXT_CHANGED, 2, "   ", false), retain)
            lines = foldCaption(lines, CaptionEvent(CaptionEvent.ERROR, 3, "boom", false), retain)
            assertEquals(1, lines.size, "retain=$retain")
            assertEquals("the quick brown fox.", lines.single().text, "retain=$retain")
            assertTrue(lines.single().settled)
        }
    }
}
