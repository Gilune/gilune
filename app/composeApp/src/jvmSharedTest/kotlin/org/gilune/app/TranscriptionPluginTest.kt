// The transcription provider's settings as data: the model catalogue, the
// fallback a stored key gets when this build has never heard of it, and
// the one document the language and the model share. All of it is pure
// over strings — a settings document and a catalogue row — so none of it
// needs a screen, and none of it downloads anything.

package org.gilune.app

import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// The transcription plugin as the kernel reports it back, with whatever
/// settings document the test wants stored against it.
private fun installedTranscriptionPlugin(state: String = "enabled", settings: String = ""): PluginInfo =
    installedPlugin(TRANSCRIPTION_PLUGIN, state, settings = settings)

class TranscriptionPluginTest {

    // ------------------------------------------------------------------
    // The catalogue (owner benchmark, 2026-09-01)
    // ------------------------------------------------------------------

    @Test
    fun theCatalogueOffersTheFourMeasuredModelsFastestFirst() {
        assertEquals(
            listOf("tiny-q5_1", "tiny", "base-q5_1", "base"),
            WHISPER_MODELS.map { it.key },
            "the order the settings screen lists, which is the order they were measured in",
        )
    }

    @Test
    fun everyEntryNamesItsGgmlFileAndDerivesItsUrlFromIt() {
        assertEquals(
            mapOf(
                "tiny-q5_1" to "ggml-tiny-q5_1.bin",
                "tiny" to "ggml-tiny.bin",
                "base-q5_1" to "ggml-base-q5_1.bin",
                "base" to "ggml-base.bin",
            ),
            WHISPER_MODELS.associate { it.key to it.fileName },
        )
        for (model in WHISPER_MODELS) {
            assertEquals(
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${model.fileName}",
                model.url,
                "<${model.key}> downloads from the file it stores",
            )
        }
    }

    @Test
    fun everyEntryCarriesTheDownloadSizeThatWasMeasured() {
        assertEquals(
            mapOf(
                "tiny-q5_1" to "31 MiB",
                "tiny" to "75 MiB",
                "base-q5_1" to "57 MiB",
                "base" to "142 MiB",
            ),
            WHISPER_MODELS.associate { it.key to it.size },
            "the recommendation's whole point is that 57 is smaller than 75",
        )
    }

    @Test
    fun theLabelNamesTheModelAndWhatChoosingItCosts() {
        assertEquals("base-q5_1 — recommended, 57 MiB", whisperModel("base-q5_1").label)
        assertEquals("tiny-q5_1 — fastest, 31 MiB", whisperModel("tiny-q5_1").label)
        assertEquals("tiny — 75 MiB", whisperModel("tiny").label, "no note, so no dangling comma")
        assertEquals("base — 142 MiB", whisperModel("base").label)
    }

    @Test
    fun eachKeyAppearsOnceSoTheRadioColumnCannotShowTwoOfAnything() {
        assertEquals(WHISPER_MODELS.size, WHISPER_MODELS.map { it.key }.toSet().size)
        assertEquals(WHISPER_MODELS.size, WHISPER_MODELS.map { it.fileName }.toSet().size)
    }

    // ------------------------------------------------------------------
    // The default, and what an unreadable setting falls back to
    // ------------------------------------------------------------------

    @Test
    fun theShippedDefaultIsTheOneTheBenchmarkChose() {
        assertEquals("base-q5_1", DEFAULT_WHISPER_MODEL)
        assertTrue(
            WHISPER_MODELS.any { it.key == DEFAULT_WHISPER_MODEL },
            "the default must be a row of the catalogue, or nothing can resolve it",
        )
        assertEquals("ggml-base-q5_1.bin", whisperModel(null).fileName)
    }

    @Test
    fun anAbsentOrUnknownModelSettingFallsBackToTheDefaultRatherThanFailing() {
        // A vault written by a later version, a hand-edited document, a
        // key with the wrong case: none of them may be what stops speech
        // being transcribed.
        for (stored in listOf(null, "", "   ", "large-v3", "TINY", "ggml-base.bin", "base q5_1")) {
            assertEquals(
                DEFAULT_WHISPER_MODEL,
                whisperModel(stored).key,
                "stored <$stored> should resolve to the default",
            )
        }
        // …and a stored key that is real, surrounded by whitespace, is
        // still that model.
        assertEquals("tiny", whisperModel(" tiny ").key)
    }

    @Test
    fun aPluginWithNothingStoredYetGetsTheDefaultModelAndAutoLanguage() {
        for (settings in listOf("", "{}", """{"language":"en"}""", "not json at all")) {
            val plugins = listOf(installedTranscriptionPlugin(settings = settings))
            assertEquals(
                DEFAULT_WHISPER_MODEL,
                plugins.transcriptionModel().key,
                "settings <$settings> should read as the default model",
            )
        }
        assertEquals(
            DEFAULT_TRANSCRIPTION_LANGUAGE,
            listOf(installedTranscriptionPlugin()).transcriptionLanguage(),
        )
        assertEquals(
            DEFAULT_WHISPER_MODEL,
            emptyList<PluginInfo>().transcriptionModel().key,
            "and a vault whose kernel carries no transcriber has no setting to read",
        )
    }

    // ------------------------------------------------------------------
    // The one settings document (the tasks precedent)
    // ------------------------------------------------------------------

    @Test
    fun theModelIsReadBackFromTheSettingsDocumentItWasWrittenTo() {
        for (wanted in WHISPER_MODELS.map { it.key }) {
            val plugin = installedTranscriptionPlugin(settings = transcriptionSettingsDocument("auto", wanted, true))
            assertEquals(wanted, listOf(plugin).transcriptionModel().key, "round trip of <$wanted>")
        }
    }

    @Test
    fun theLanguageIsReadBackFromTheSameDocument() {
        for (wanted in listOf("auto", "en", "sv", """a "quoted" \ one""")) {
            val plugin = installedTranscriptionPlugin(settings = transcriptionSettingsDocument(wanted, "tiny", true))
            assertEquals(wanted, listOf(plugin).transcriptionLanguage(), "round trip of <$wanted>")
        }
    }

    @Test
    fun theTwoSettingsAreOneDocumentAndNeitherWriteDropsTheOther() {
        // What each control in the settings screen writes: the value it
        // owns, and the stored value of the one it does not.
        val languageApplied = transcriptionSettingsDocument("sv", "base", true)
        assertEquals("sv", listOf(installedTranscriptionPlugin(settings = languageApplied)).transcriptionLanguage())
        assertEquals(
            "base",
            listOf(installedTranscriptionPlugin(settings = languageApplied)).transcriptionModel().key,
            "setting a language must not revert the model somebody chose",
        )
        val modelPicked = transcriptionSettingsDocument("sv", "tiny-q5_1", true)
        assertEquals(
            "sv",
            listOf(installedTranscriptionPlugin(settings = modelPicked)).transcriptionLanguage(),
            "and picking a model must not revert the language",
        )
        assertEquals("tiny-q5_1", listOf(installedTranscriptionPlugin(settings = modelPicked)).transcriptionModel().key)
    }

    @Test
    fun wordsAsYouDictateAreOnUnlessSomebodyTurnedThemOff() {
        // ADR 0027 amendment: absent means on, so a vault written before
        // the switch existed dictates live.
        assertTrue(listOf(installedTranscriptionPlugin(settings = "")).transcriptionLive())
        assertTrue(listOf(installedTranscriptionPlugin(settings = """{"language":"en","model":"base"}""")).transcriptionLive())
        val off = listOf(installedTranscriptionPlugin(settings = transcriptionSettingsDocument("en", "base", false)))
        assertFalse(off.transcriptionLive())
        // The step past: changing the language or the model afterwards must
        // carry the switch as stored, not reset it to the default.
        val relanguaged = transcriptionSettingsDocument("sv", off.transcriptionModel().key, off.transcriptionLive())
        assertFalse(listOf(installedTranscriptionPlugin(settings = relanguaged)).transcriptionLive())
    }

    @Test
    fun theDocumentKeepsTheKeyNameTheKernelReadsLanguageFrom() {
        // `enableWhisper` picks "language" out of this document itself,
        // so the shell may add keys beside it but never rename it.
        assertTrue(
            transcriptionSettingsDocument("en", "base-q5_1", true).contains(""""language":"en""""),
            "the kernel's read is part of this contract",
        )
    }

    @Test
    fun bothSettingsAreReadableWhileThePluginIsOff() {
        val off = installedTranscriptionPlugin(
            state = "disabled",
            settings = transcriptionSettingsDocument("en", "tiny", true),
        )
        assertEquals("tiny", listOf(off).transcriptionModel().key, "its own screen shows what is stored")
        assertEquals("en", listOf(off).transcriptionLanguage())
    }

    @Test
    fun anotherPluginsSettingsAreNotMistakenForTheTranscriptionOnes() {
        val tasks = installedTasksPlugin(settings = """{"model":"tiny","language":"sv"}""")
        assertEquals(DEFAULT_WHISPER_MODEL, listOf(tasks).transcriptionModel().key)
        assertEquals(DEFAULT_TRANSCRIPTION_LANGUAGE, listOf(tasks).transcriptionLanguage())
        // …and with both installed, each reads its own document.
        val both = listOf(tasks, installedTranscriptionPlugin(settings = transcriptionSettingsDocument("en", "base", true)))
        assertEquals("base", both.transcriptionModel().key)
        assertEquals("en", both.transcriptionLanguage())
    }
}
