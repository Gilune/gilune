// Putting a transcriber's words and a speaker model's turns together
// (ADR 0030). The first rows use real numbers: whisper's word times and the
// speaker model's turns for the same two-voice recording, measured when
// this was built — the case the sentence rule exists for.

package org.gilune.app

import uniffi.gilune_ffi.TimedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeakerLabelsTest {
    private fun w(text: String, start: Int, end: Int) = TimedWord(text, start.toUInt(), end.toUInt())

    /// whisper (base-q5_1) on the two-voice test recording, abridged.
    private val whisperWords = listOf(
        w("Right,", 120, 840), w("shall", 840, 890), w("we", 890, 980), w("start?", 980, 4000),
        w("Yes.", 4000, 6000), w("I", 6000, 6110), w("have", 6110, 6370), w("the", 6370, 6890),
        w("numbers", 6890, 7160), w("on", 11320, 11480), w("Monday.", 11480, 12000),
        // Whisper begins "Go" 0.7 s before the voice does, in the pause.
        w("Go", 12000, 12350), w("on", 12350, 12890), w("then.", 12890, 14000),
        // And stretches "instead." 1.3 s past it, nearer the next voice.
        w("ask", 36730, 37020), w("the", 37020, 37400), w("electrician", 37400, 38380),
        w("to", 38380, 38570), w("wait", 38570, 38960), w("instead.", 38960, 40000),
        w("Agreed.", 40000, 41000),
    )

    /// The speaker model's answer for the same recording, as model numbers.
    private val modelTurns = listOf(
        SpeakerTurn(30, 3640, 0), SpeakerTurn(5060, 5670, 1), SpeakerTurn(6380, 11390, 1),
        SpeakerTurn(12740, 13480, 0), SpeakerTurn(35030, 38740, 0), SpeakerTurn(40180, 40730, 1),
    )

    @Test
    fun eachSentenceGoesToTheVoiceItOverlapsMostEvenWhereWordTimesAreLoose() {
        assertEquals(
            """
            **Speaker 1:** Right, shall we start?

            **Speaker 2:** Yes. I have the numbers on Monday.

            **Speaker 1:** Go on then. ask the electrician to wait instead.

            **Speaker 2:** Agreed.
            """.trimIndent(),
            labelSpeakers(whisperWords, modelTurns),
        )
    }

    @Test
    fun speakersAreNumberedByWhoSpeaksFirstNotByTheModel() {
        val turns = listOf(SpeakerTurn(0, 1000, 7), SpeakerTurn(1000, 2000, 2))
        val words = listOf(w("Hello.", 100, 900), w("Hi.", 1100, 1900))
        assertEquals("**Speaker 1:** Hello.\n\n**Speaker 2:** Hi.", labelSpeakers(words, turns))
    }

    @Test
    fun oneVoiceIsPlainTextWithNoLabel() {
        val turns = listOf(SpeakerTurn(0, 5000, 0), SpeakerTurn(6000, 7000, 0))
        assertNull(labelSpeakers(whisperWords.take(4), turns))
        // A voice the model heard but no word fell to is no second speaker.
        assertNull(labelSpeakers(whisperWords.take(4), turns + SpeakerTurn(20_000, 21_000, 1)))
    }

    @Test
    fun noWordsOrNoTurnsIsNoAnswer() {
        assertNull(labelSpeakers(emptyList(), modelTurns))
        assertNull(labelSpeakers(whisperWords, emptyList()))
    }

    @Test
    fun anInterruptionOfThreeWordsInsideASentenceKeepsItsOwnSpeaker() {
        val turns = listOf(SpeakerTurn(0, 3000, 0), SpeakerTurn(3000, 4500, 1), SpeakerTurn(4500, 8000, 0))
        val words = listOf(
            w("So", 0, 500), w("the", 500, 1000), w("plan", 1000, 2000), w("is", 2000, 3000),
            w("no", 3000, 3500), w("it", 3500, 4000), w("isn't", 4000, 4500),
            w("to", 4500, 5500), w("wait.", 5500, 8000),
        )
        assertEquals(
            "**Speaker 1:** So the plan is\n\n**Speaker 2:** no it isn't\n\n**Speaker 1:** to wait.",
            labelSpeakers(words, turns),
        )
    }

    @Test
    fun twoWordsPlacedOnAnotherVoiceInsideASentenceAreLooseTimesNotAnInterruption() {
        val turns = listOf(SpeakerTurn(0, 3000, 0), SpeakerTurn(3000, 4000, 1), SpeakerTurn(4000, 8000, 0))
        val words = listOf(
            w("So", 0, 1000), w("the", 1000, 3000), w("plan", 3000, 3500), w("is", 3500, 4000),
            w("to", 4000, 6000), w("wait.", 6000, 8000),
        )
        assertNull(labelSpeakers(words, turns), "one voice once the loose pair is folded back in")
    }

    @Test
    fun aTranscriptWithNoSentencePunctuationIsTakenWordByWord() {
        val turns = listOf(SpeakerTurn(0, 1000, 0), SpeakerTurn(1000, 2000, 1))
        val words = listOf(w("hello", 100, 400), w("there", 500, 900), w("hi", 1100, 1500))
        assertEquals("**Speaker 1:** hello there\n\n**Speaker 2:** hi", labelSpeakers(words, turns))
    }

    @Test
    fun aSentenceInSilenceGoesToTheNearestVoice() {
        val turns = listOf(SpeakerTurn(0, 1000, 0), SpeakerTurn(5000, 6000, 1))
        val words = listOf(w("First.", 100, 900), w("Late.", 4200, 4600), w("Second.", 5100, 5900))
        assertEquals("**Speaker 1:** First.\n\n**Speaker 2:** Late. Second.", labelSpeakers(words, turns))
    }

    @Test
    fun sherpaTokensBecomeWordsAtSpacesAndWordBoundaryMarks() {
        val tokens = arrayOf("▁Hel", "lo", "▁there", ".", " how", " are", " you")
        val starts = floatArrayOf(0.10f, 0.30f, 0.60f, 0.90f, 2.00f, 2.40f, 2.70f)
        val words = wordsFromTokens(tokens, starts, null, recordingMs = 2_800)
        assertEquals(listOf("Hello", "there.", "how", "are", "you"), words.map { it.text })
        assertEquals(listOf(100, 600, 2000, 2400, 2700), words.map { it.startMs.toInt() })
        // A beat after the last token, never past the next word or the end.
        assertEquals(listOf(600, 1200, 2300, 2700, 2800), words.map { it.endMs.toInt() })
    }

    @Test
    fun sherpaDurationsSayWhereAWordEndsWhenTheModelGivesThem() {
        val tokens = arrayOf("▁one", "▁two")
        val words = wordsFromTokens(tokens, floatArrayOf(0.0f, 2.0f), floatArrayOf(0.25f, 0.5f), recordingMs = 5_000)
        assertEquals(listOf(250, 2500), words.map { it.endMs.toInt() })
    }

    @Test
    fun theSpeakerCountIsAutomaticUnlessAPlausibleNumberIsStored() {
        assertNull(speakerCountIn("{}"))
        assertNull(speakerCountIn("""{"speakers":0}"""))
        assertNull(speakerCountIn("""{"speakers":11}"""))
        assertEquals(2, speakerCountIn("""{"speakers":2}"""))
        assertEquals(2, speakerCountIn(speakersSettingsDocument(2)))
        assertNull(speakerCountIn(speakersSettingsDocument(null)))
    }
}
