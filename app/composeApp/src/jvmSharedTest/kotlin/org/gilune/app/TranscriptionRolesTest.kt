// Transcription roles (ADR 0033): which engines the plugins offer, which
// fills each role, and what the page says — read from declarations and
// settings only, so every rule here is tested without a kernel.

package org.gilune.app

import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.jsonString
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// A declaration standing in for the kernel's whisper plugin, with the
/// engines `whisper.rs` publishes (keys from the shared catalogue, which
/// `WhisperEnginesCouplingTest` pins to the Rust list). Test builds carry
/// no whisper, so tests that need it register this.
internal fun whisperStandInDeclaration(): PluginDeclaration =
    nemoDeclaration().copy(
        pluginId = TRANSCRIPTION_PLUGIN,
        name = "Transcription (Whisper)",
        processorId = "transcribe",
        contributions = WHISPER_MODELS.map {
            transcriptionEngineContribution(
                id = "whisper-${it.key}",
                label = "Whisper ${it.key}",
                engine = it.key,
                roles = setOf(TranscriptionRole.LIVE, TranscriptionRole.RECORDINGS),
                wordTimes = true,
                downloadBytes = 1_000_000L,
                guidance = "A whisper model.",
            )
        },
    )

internal fun transcriberRow(
    declaration: PluginDeclaration,
    state: String = "enabled",
    takesJobs: Boolean = true,
    settings: String = "",
    granted: List<String> = emptyList(),
) = PluginInfo(
    pluginId = declaration.pluginId,
    name = declaration.name,
    `class` = "provider",
    profile = "bundled",
    required = false,
    state = state,
    processorId = declaration.processorId,
    subscribes = declaration.subscribes,
    emits = declaration.emits,
    capabilities = declaration.capabilities,
    granted = granted,
    ungranted = declaration.capabilities - granted.toSet(),
    contributions = declaration.contributions,
    settings = settings,
    takesJobs = takesJobs,
)

class TranscriptionRolesTest {
    private fun whisper(state: String = "enabled", takesJobs: Boolean = true, settings: String = "") =
        transcriberRow(whisperStandInDeclaration(), state, takesJobs, settings)

    private fun nemo(state: String = "enabled", takesJobs: Boolean = true, settings: String = "", granted: List<String> = emptyList()) =
        transcriberRow(nemoDeclaration(), state, takesJobs, settings, granted)

    // ------------------------------------------------------------ engines

    @Test
    fun everyInstalledProvidersEnginesAreListedIncludingSwitchedOffOnes() {
        val engines = listOf(whisper(), nemo(state = "disabled")).transcriptionEngines()
        assertEquals(WHISPER_MODELS.size + NEMO_MODELS.size, engines.size)
        assertEquals(WHISPER_MODELS.map { it.key }, engines.filter { it.pluginId == TRANSCRIPTION_PLUGIN }.map { it.engine })
        val parakeet = engines.single { it.engine == "parakeet-tdt-0.6b-v2" }
        assertEquals("Transcription (NVIDIA NeMo)", parakeet.pluginName, "a switched-off plugin is offered — choosing switches it on")
        assertEquals(482_468_385L, parakeet.downloadBytes)
        assertTrue(parakeet.wordTimes)
    }

    @Test
    fun aWholeRecordingModelIsOfferedForRecordingsOnly() {
        val engines = listOf(nemo()).transcriptionEngines()
        assertEquals(
            listOf("nemotron-0.6b", "fastconformer"),
            engines.forRole(TranscriptionRole.LIVE).map { it.engine },
        )
        assertEquals(
            listOf("nemotron-0.6b", "fastconformer", "parakeet-tdt-0.6b-v2"),
            engines.forRole(TranscriptionRole.RECORDINGS).map { it.engine },
        )
    }

    @Test
    fun aRemovedProviderOffersNothingAndAnUnreadableEntryIsDropped() {
        assertEquals(emptyList(), listOf(nemo(state = "removed")).transcriptionEngines())
        // One step past: a broken entry beside good ones drops alone.
        val broken = nemo().copy(
            contributions = nemoDeclaration().contributions +
                PluginContribution(Slots.TRANSCRIPTION_ENGINES, "broken", "Broken", "{not json") +
                PluginContribution(Slots.TRANSCRIPTION_ENGINES, "roleless", "Roleless", """{"engine":"x","roles":[]}"""),
        )
        assertEquals(NEMO_MODELS.size, listOf(broken).transcriptionEngines().size)
    }

    @Test
    fun moonshinePublishesOneStreamingEngineWithoutWordTimes() {
        val engine = listOf(transcriberRow(moonshineDeclaration())).transcriptionEngines().single()
        assertEquals(setOf(TranscriptionRole.LIVE, TranscriptionRole.RECORDINGS), engine.roles)
        assertFalse(engine.wordTimes)
        assertEquals(DEFAULT_MOONSHINE_MODEL, engine.engine)
    }

    // ---------------------------------------------------------- recordings

    @Test
    fun recordingsAreTheEnabledProviderThatTakesJobs() {
        assertEquals(
            EngineChoice(NEMO_PLUGIN, "parakeet-tdt-0.6b-v2"),
            listOf(
                whisper(takesJobs = false),
                nemo(settings = """{"recordingsEngine":"parakeet-tdt-0.6b-v2"}"""),
            ).recordingsChoice(),
        )
        // Taking jobs while switched off is not taking them.
        assertNull(listOf(whisper(state = "disabled"), nemo(takesJobs = false)).recordingsChoice())
        // Precedence decides the hole both-take-jobs leaves.
        assertEquals(TRANSCRIPTION_PLUGIN, listOf(nemo(), whisper()).recordingsChoice()?.pluginId)
    }

    @Test
    fun recordingsCarryOverTheModelChosenBeforeRoles() {
        val before = whisper(settings = transcriptionSettingsDocument("sv", "tiny", true))
        assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, "tiny"), listOf(before).recordingsChoice())
        assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, DEFAULT_WHISPER_MODEL), listOf(whisper()).recordingsChoice())
        // A key this build never heard of falls back, never to nothing.
        assertEquals(
            EngineChoice(NEMO_PLUGIN, DEFAULT_NEMO_MODEL),
            listOf(nemo(settings = """{"recordingsEngine":"gone"}""")).recordingsChoice(),
        )
    }

    // ----------------------------------------------------- while you speak

    @Test
    fun neverChosenLiveIsTheOldTranscribersModelWhenItsSwitchWasOn() {
        assertEquals(EngineChoice(TRANSCRIPTION_PLUGIN, "base"), listOf(whisper(settings = transcriptionSettingsDocument("en", "base", true))).liveChoice())
        assertNull(listOf(whisper(settings = transcriptionSettingsDocument("en", "base", false))).liveChoice(), "its switch was off")
        // A model that cannot stream was never live, whatever its switch said.
        assertNull(
            listOf(whisper(state = "disabled"), nemo(settings = nemoSettingsDocument("parakeet-tdt-0.6b-v2", true))).liveChoice(),
        )
        assertEquals(
            EngineChoice(NEMO_PLUGIN, "nemotron-0.6b"),
            listOf(whisper(state = "disabled"), nemo(settings = nemoSettingsDocument("nemotron-0.6b", true))).liveChoice(),
        )
        assertNull(emptyList<PluginInfo>().liveChoice())
    }

    @Test
    fun neverChosenLiveFollowsTheRecordingsTakerNotTheFirstSwitchedOn() {
        // Whisper switched on beside NeMo takes no jobs; the engine NeMo
        // was showing words with must not move to whisper because of it.
        val plugins = listOf(whisper(takesJobs = false), nemo(settings = nemoSettingsDocument("fastconformer", true)))
        assertEquals(EngineChoice(NEMO_PLUGIN, "fastconformer"), plugins.liveChoice())
    }

    @Test
    fun aChosenLiveEngineWinsAndOffIsAChoice() {
        val chosen = listOf(
            whisper(settings = """{"model":"base","liveEngine":""}"""),
            nemo(takesJobs = false, settings = """{"liveEngine":"nemotron-0.6b"}"""),
        )
        assertEquals(EngineChoice(NEMO_PLUGIN, "nemotron-0.6b"), chosen.liveChoice())
        val off = listOf(whisper(settings = """{"liveEngine":""}"""), nemo(settings = """{"liveEngine":""}"""))
        assertTrue(off.liveChosen())
        assertNull(off.liveChoice(), "every provider carrying \"\" is Off, not never-chosen")
        assertNull(off.liveHolderSwitchedOff())
    }

    @Test
    fun aLiveEngineWhosePluginIsSwitchedOffLeavesTheRoleEmptyAndSaysWhose() {
        val plugins = listOf(
            whisper(settings = """{"liveEngine":""}"""),
            nemo(state = "disabled", settings = """{"liveEngine":"nemotron-0.6b"}"""),
        )
        assertNull(plugins.liveChoice())
        assertEquals(NEMO_PLUGIN, plugins.liveHolderSwitchedOff()?.pluginId)
    }

    // ------------------------------------------------------------ settings

    @Test
    fun choosingAnEngineKeepsEveryOtherKey() {
        val before = transcriptionSettingsDocument("sv", "tiny", false)
        val after = settingsWith(before, mapOf(RECORDINGS_ENGINE_KEY to "base", LIVE_ENGINE_KEY to ""))
        assertEquals("sv", jsonString(after, "language"))
        assertEquals("tiny", jsonString(after, "model"))
        assertEquals("base", jsonString(after, RECORDINGS_ENGINE_KEY))
        assertEquals("", jsonString(after, LIVE_ENGINE_KEY))
        assertEquals(false, org.gilune.plugin.sdk.jsonBoolean(after, "live"), "the old boolean is left alone")
        // One step past: a second write over the first still keeps them.
        val again = settingsWith(after, mapOf(LIVE_ENGINE_KEY to "tiny"))
        assertEquals("sv", jsonString(again, "language"))
        assertEquals("base", jsonString(again, RECORDINGS_ENGINE_KEY))
        assertEquals("tiny", jsonString(again, LIVE_ENGINE_KEY))
        assertEquals("""{"liveEngine":"x"}""", settingsWith("not json", mapOf(LIVE_ENGINE_KEY to "x")))
    }

    // ---------------------------------------------------------- the page

    @Test
    fun eachRowSaysWhatChoosingItCosts() {
        val parakeet = listOf(nemo()).transcriptionEngines().single { it.engine == "parakeet-tdt-0.6b-v2" }
        assertEquals(EngineReadiness.SWITCHED_OFF, engineReadiness(parakeet, listOf(nemo(state = "disabled")), onDisk = true))
        assertEquals(EngineReadiness.READY, engineReadiness(parakeet, listOf(nemo()), onDisk = true))
        assertEquals(EngineReadiness.ALLOW_NETWORK, engineReadiness(parakeet, listOf(nemo()), onDisk = false))
        val allowed = engineReadiness(parakeet, listOf(nemo(granted = listOf(NETWORK_CAPABILITY))), onDisk = false)
        assertEquals("Download 482 MB", allowed.words(parakeet))
    }

    @Test
    fun theSpeakerLineFollowsTheRecordingsEngine() {
        val speakers = installedPlugin(SPEAKERS_PLUGIN)
        assertEquals("Speaker labels: not installed.", speakerLabelsLine(listOf(whisper())))
        assertTrue(speakerLabelsLine(listOf(whisper(), speakers.copy(state = "disabled"))).startsWith("Speaker labels: off."))
        assertTrue("work with Whisper base-q5_1" in speakerLabelsLine(listOf(whisper(), speakers)))
        val moonshine = transcriberRow(moonshineDeclaration())
        assertTrue(
            "does not report when each word was said" in speakerLabelsLine(listOf(moonshine, speakers)),
            speakerLabelsLine(listOf(moonshine, speakers)),
        )
        assertTrue("nothing is chosen" in speakerLabelsLine(listOf(whisper(takesJobs = false), speakers)))
    }

    @Test
    fun aProvidersScreenSaysWhichRolesItFills() {
        val plugins = listOf(
            whisper(settings = """{"liveEngine":"","recordingsEngine":"base"}"""),
            nemo(takesJobs = false, settings = """{"liveEngine":"nemotron-0.6b"}"""),
        )
        assertEquals("Used for recordings (Whisper base).", rolesFilledBy(plugins, TRANSCRIPTION_PLUGIN))
        assertEquals("Used for while you speak (Nemotron 0.6B).", rolesFilledBy(plugins, NEMO_PLUGIN))
        assertNull(rolesFilledBy(listOf(whisper(), nemo(state = "disabled")), NEMO_PLUGIN))
    }
}
