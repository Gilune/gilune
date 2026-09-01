// Local transcription's decisions (ADR 0017 § 4), tested without a
// network, a microphone or a model file.
//
// Everything the phone decides about transcription is a pure function
// over the plugin registry plus one predicate ("is the file there?"), so
// the whole matrix — plugin × consent × model × phase — is reachable from
// plain assertions. What is left over is I/O, and that is the part the
// end-to-end test exercises for real.

package org.gilune.app

import androidx.compose.ui.text.TextRange
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptionTest {
    private fun plugin(
        id: String = TRANSCRIPTION_PLUGIN,
        state: String = "enabled",
        granted: List<String> = listOf(NETWORK_CAPABILITY),
        settings: String = "",
        contributions: List<PluginContribution> = emptyList(),
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
        contributions = contributions,
        settings = settings,
    )

    // ------------------------------------------------ the platform default

    @Test
    fun withNoSettingThePhoneGetsTheSmallModel() {
        // ADR 0017 § Ratification 5: tiny-q5_1 pencilled as the phone
        // default. The shared catalogue's own default stays base-q5_1 —
        // this is a read-site difference, not a catalogue edit.
        assertEquals("tiny-q5_1", androidWhisperModel(null).key)
        assertEquals("tiny-q5_1", androidWhisperModel("").key)
        assertEquals("tiny-q5_1", androidWhisperModel("   ").key)
    }

    @Test
    fun theDesktopDefaultIsUntouched() {
        // The other half of the same decision. If this ever fails, the
        // platform default was implemented by changing shared code.
        assertEquals("base-q5_1", DEFAULT_WHISPER_MODEL)
        assertEquals("base-q5_1", whisperModel(null).key, "the shared resolver is still the desktop's")
        assertEquals(
            "base-q5_1",
            listOf(plugin(settings = "")).transcriptionModel().key,
            "the shared read site must not have learned what a phone is",
        )
    }

    @Test
    fun anExplicitChoiceWins() {
        assertEquals("base", androidWhisperModel("base").key)
        assertEquals("base-q5_1", androidWhisperModel("base-q5_1").key)
        assertEquals("tiny", androidWhisperModel("tiny").key)
        assertEquals("base", androidWhisperModel(" base ").key, "a stored key is trimmed, not rejected")
    }

    @Test
    fun aKeyThisBuildHasNeverHeardOfFallsBackRatherThanFailing() {
        // A vault written by a later version, or a hand-edited settings
        // document. An unreadable setting must never be what stops speech
        // being transcribed.
        assertEquals("tiny-q5_1", androidWhisperModel("large-v3").key)
        assertEquals("tiny-q5_1", androidWhisperModel("{\"not\":\"a key\"}").key)
    }

    @Test
    fun theStoredKeyIsReadThroughThePluginRegistry() {
        val chosen = listOf(plugin(settings = transcriptionSettingsDocument("en", "base")))
        assertEquals("base", chosen.androidTranscriptionModel().key)
        assertEquals("en", chosen.transcriptionLanguage())
        assertEquals(
            "tiny-q5_1",
            listOf(plugin(settings = transcriptionSettingsDocument("en", ""))).androidTranscriptionModel().key,
            "a blank stored model is the phone default, not an error",
        )
    }

    @Test
    fun everyCatalogueEntryIsOfferableOnAPhone() {
        // The picker renders the shared catalogue, so a row with no label
        // would be a blank radio button.
        for (model in WHISPER_MODELS) {
            assertTrue(phoneModelLabel(model).startsWith(model.key))
            assertTrue(phoneModelLabel(model).contains(model.size), "every row states its download cost")
        }
        assertTrue(
            phoneModelLabel(androidWhisperModel(null)).contains("recommended on phones"),
            "the phone's recommendation is the phone's default",
        )
    }

    // -------------------------------------------------------- the plan

    @Test
    fun noPluginIsNoTranscriber() {
        assertEquals(TranscriptionPlan.NoTranscriber, transcriptionPlan(emptyList()) { true })
        assertEquals(
            TranscriptionPlan.NoTranscriber,
            transcriptionPlan(listOf(plugin(state = "disabled"))) { true },
            "an off plugin is asked for nothing, downloaded nothing and enabled nothing",
        )
        assertEquals(
            TranscriptionPlan.NoTranscriber,
            transcriptionPlan(listOf(plugin(state = "removed"))) { true },
        )
    }

    @Test
    fun nothingDownloadsBecauseTheAppOpened() {
        // § 4, and the reason this is the hinge of the whole file.
        val plan = transcriptionPlan(listOf(plugin(granted = emptyList()))) { false }
        assertTrue(plan is TranscriptionPlan.NeedsConsent, "got $plan")
        assertEquals("tiny-q5_1", (plan as TranscriptionPlan.NeedsConsent).model.key)
    }

    @Test
    fun consentIsAPositiveGrantNotTheAbsenceOfAComplaint() {
        // A plugin that declares no capabilities at all leaves both lists
        // empty. Reading consent as "nothing outstanding" would then
        // download 31 MiB on the strength of a plugin having asked for
        // nothing — so the read is `granted`, and the fail-safe direction
        // is not downloading.
        val declaresNothing = PluginInfo(
            pluginId = TRANSCRIPTION_PLUGIN,
            name = "Transcription",
            `class` = "provider",
            profile = "bundled",
            required = false,
            state = "enabled",
            processorId = null,
            subscribes = null,
            emits = emptyList(),
            capabilities = emptyList(),
            granted = emptyList(),
            ungranted = emptyList(),
            contributions = emptyList<PluginContribution>(),
            settings = "",
        )
        assertTrue(transcriptionPlan(listOf(declaresNothing)) { false } is TranscriptionPlan.NeedsConsent)
    }

    @Test
    fun consentPlusNoModelIsADownload() {
        val plan = transcriptionPlan(listOf(plugin())) { false }
        assertTrue(plan is TranscriptionPlan.Download, "got $plan")
        assertEquals("tiny-q5_1", (plan as TranscriptionPlan.Download).model.key)
    }

    @Test
    fun aModelOnDiskIsJustEnabled() {
        // Including when consent was never given: the file is already
        // there, so there is nothing to ask about. Revoking the network
        // permission does not un-download a model, and pretending
        // otherwise would leave a working transcriber switched off by a
        // permission it no longer needs.
        val plan = transcriptionPlan(listOf(plugin(granted = emptyList()))) { true }
        assertTrue(plan is TranscriptionPlan.Enable, "got $plan")
    }

    @Test
    fun theChosenModelIsTheOneCheckedForAndTheOneFetched() {
        // The bug this rules out: checking for the default's file while
        // downloading the chosen one, so a non-default choice re-downloads
        // on every launch.
        val chosen = listOf(plugin(settings = transcriptionSettingsDocument("auto", "base")))
        val asked = mutableListOf<String>()
        val plan = transcriptionPlan(chosen) { model ->
            asked += model.key
            false
        }
        assertEquals(listOf("base"), asked)
        assertEquals("base", (plan as TranscriptionPlan.Download).model.key)
        assertEquals("ggml-base.bin", plan.model.fileName)
    }

    @Test
    fun theDownloadUrlIsTheCataloguesAndCarriesNoQuery() {
        for (model in WHISPER_MODELS) {
            assertTrue(model.url.startsWith("https://"), "${model.key} must not be fetched in the clear")
            assertTrue(model.url.endsWith(model.fileName))
        }
    }

    // ------------------------------------------------- the pending wording

    @Test
    fun theProgressNoticeIsHonestAboutWhatItKnows() {
        val model = androidWhisperModel(null)
        assertEquals(
            "downloading the tiny-q5_1 transcription model (~31 MiB, one time)…",
            downloadNotice(model, null),
            "a server that did not say how big the file is gets no invented percentage",
        )
        assertEquals(
            "downloading the tiny-q5_1 transcription model — 42% of ~31 MiB",
            downloadNotice(model, 42),
        )
    }

    @Test
    fun theWholeMatrixOfWordings() {
        // consent × model × job, as the row would render it. The point of
        // writing it out is that every cell is a promise to a user about
        // something they cannot see.
        val off = listOf(plugin(state = "disabled"))
        val unconsented = listOf(plugin(granted = emptyList()))
        val consented = listOf(plugin())

        fun says(plugins: List<PluginInfo>, phase: TranscriptionPhase) =
            voiceStatus(42_000, transcriptionState("", plugins, phase))

        assertEquals("recorded · 0:42 · needs the transcription model", says(off, TranscriptionPhase.Ready))
        assertEquals("recorded · 0:42 · needs the transcription model", says(unconsented, TranscriptionPhase.Absent))
        assertEquals("recorded · 0:42 · downloading the transcription model…", says(consented, TranscriptionPhase.Downloading))
        assertEquals("recorded · 0:42 · needs the transcription model", says(consented, TranscriptionPhase.Unknown))
        assertEquals("recorded · 0:42 · needs the transcription model", says(consented, TranscriptionPhase.Failed))
        assertEquals("recorded · 0:42 · transcribing…", says(consented, TranscriptionPhase.Ready))
        assertEquals(
            "",
            voiceStatus(42_000, transcriptionState("words", off, TranscriptionPhase.Absent)),
            "words that landed are words, whatever the plugin does now",
        )
    }

    // ----------------------------------------------------------- dictation

    @Test
    fun aMicThatProducesNothingIsNeverDrawn() {
        // ADR 0017 § 3's line. Both conditions, and each alone is enough
        // to take the button.
        val contributed = listOf(
            plugin(
                id = VOICE_CAPTURE_PLUGIN,
                contributions = listOf(
                    PluginContribution(slot = Slots.NOTE_ACTIONS, id = DICTATE_ACTION, label = "Dictate"),
                ),
            ),
        )
        assertTrue(dictationOffered(contributed, TranscriptionPhase.Ready))
        assertFalse(
            dictationOffered(contributed, TranscriptionPhase.Absent),
            "a contributed mic with no transcriber would insert nothing",
        )
        assertFalse(dictationOffered(contributed, TranscriptionPhase.Downloading))
        assertFalse(
            dictationOffered(emptyList(), TranscriptionPhase.Ready),
            "removing the adapter takes the button, transcriber or not",
        )
    }

    @Test
    fun dictatedWordsLandAtTheCaret() {
        val edit = dictationEdit("hello world", TextRange(5), "there")!!
        assertEquals(5, edit.start)
        assertEquals(5, edit.end)
        assertEquals(" there", edit.replacement, "a space, because the caret was mid-word")
        assertEquals(TextRange(11), edit.selection, "the caret follows the words in")
    }

    @Test
    fun dictationNeverWeldsSentencesTogetherAndNeverOpensWithASpace() {
        assertEquals("hello", dictationEdit("", TextRange(0), "hello")!!.replacement)
        assertEquals("hello", dictationEdit("- ", TextRange(2), "hello")!!.replacement)
        assertEquals("hello", dictationEdit("a\n", TextRange(2), "hello")!!.replacement)
        assertEquals(" hello", dictationEdit("a", TextRange(1), "hello")!!.replacement)
    }

    @Test
    fun aSelectionIsReplaced() {
        val edit = dictationEdit("keep this bit", TextRange(5, 9), "that")!!
        assertEquals(5, edit.start)
        assertEquals(9, edit.end)
        assertEquals("that", edit.replacement, "the char before the selection is a space already")
        assertEquals(TextRange(9), edit.selection)
    }

    @Test
    fun silenceIsNotAnEdit() {
        // A failed or empty transcription must never write to the buffer:
        // the recording is preserved either way, and an edit that adds
        // nothing still costs an undo step and an IME reset.
        assertNull(dictationEdit("hello", TextRange(5), ""))
        assertNull(dictationEdit("hello", TextRange(5), "   \n\t "))
    }

    @Test
    fun dictationSurvivesACaretPastTheEnd() {
        // The caret is read when the words arrive, and the buffer may
        // have shrunk while the user was speaking.
        val edit = dictationEdit("hi", TextRange(99), "there")!!
        assertEquals(2, edit.start)
        assertEquals(2, edit.end)
        assertEquals(" there", edit.replacement)
    }

    @Test
    fun dictationIsAMinimalEditNotADocumentRewrite() {
        // ADR 0012's invariant, and the reason this is a BufferEdit: the
        // replaced range is the caret, never the whole buffer.
        val long = "a".repeat(500)
        val edit = dictationEdit(long, TextRange(250), "word")!!
        assertEquals(250, edit.start)
        assertEquals(250, edit.end)
    }
}
