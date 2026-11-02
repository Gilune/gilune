// Speaker labels for a long recording, heard a window at a time (memory
// round 2026-11-02), with a stand-in for the speaker models: the windowing,
// the times and the matching of voices across windows need no model.

package org.gilune.app

import uniffi.gilune_ffi.TimedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakerWindowsTest {
    /// Two people taking turns a minute at a time, Alice first. Each
    /// voice's samples carry its own value, so the stand-in can tell who a
    /// stretch of sound is.
    private fun conversation(minutes: Int): FloatArray =
        FloatArray((minutes * MINUTE).toInt()) { i -> if ((i / MINUTE) % 2 == 0L) ALICE else BOB }

    /// Hears each minute as one turn, numbering voices afresh in every call
    /// and the opposite way round in alternate calls, as a model may; a
    /// voice's embedding says which value its samples carry.
    private class StandIn : Diarizer {
        val heard = mutableListOf<Pair<Int, Int?>>()
        var voices = 0

        override fun turns(samples: FloatArray, count: Int?): List<SpeakerTurn> {
            heard += samples.size to count
            val flip = heard.size % 2 == 0
            return (0 until (samples.size + MINUTE.toInt() - 1) / MINUTE.toInt()).map { m ->
                val value = samples[(m * MINUTE).toInt()]
                val speaker = if ((value == ALICE) != flip) 0 else 1
                SpeakerTurn((m * 60_000), minOf((m + 1) * 60_000, (samples.size * 1000L / WAV_SAMPLE_RATE).toInt()), speaker)
            }
        }

        override fun voiceOf(samples: FloatArray): FloatArray {
            voices++
            return if (samples.all { it == ALICE }) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
    }

    private fun hear(engine: Diarizer, sound: FloatArray, count: Int?): List<SpeakerTurn>? {
        val windows = SpeakerWindows(engine, count, sound.size.toLong())
        var at = 0
        while (at < sound.size) {
            val to = minOf(at + 32_768, sound.size)
            windows.push(sound.copyOfRange(at, to))
            at = to
        }
        return windows.finish()
    }

    @Test
    fun aRecordingOfOneWindowIsHeardWholeWithThePersonsCount() {
        val engine = StandIn()
        val turns = hear(engine, conversation(12), count = 2)!!
        assertEquals<List<Pair<Int, Int?>>>(listOf((12 * MINUTE).toInt() to 2), engine.heard, "one hearing of all of it, as before")
        assertEquals(0, engine.voices, "nothing to match across windows")
        assertEquals(12, turns.size)
    }

    @Test
    fun aLongRecordingIsHeardAWindowAtATimeAndEachPersonKeepsTheirNumber() {
        val engine = StandIn()
        val turns = hear(engine, conversation(30), count = null)!!
        assertEquals<List<Pair<Int, Int?>>>(List(3) { (10 * MINUTE).toInt() to null }, engine.heard, "three windows, never more held")
        assertEquals(30, turns.size)
        assertEquals((0 until 30).map { it * 60_000 }, turns.map { it.startMs }, "times count from the start")
        val alice = turns.filterIndexed { m, _ -> m % 2 == 0 }.map { it.speaker }.toSet()
        val bob = turns.filterIndexed { m, _ -> m % 2 == 1 }.map { it.speaker }.toSet()
        assertEquals(1, alice.size, "Alice is one person in every window: $alice")
        assertEquals(1, bob.size, "and so is Bob: $bob")
        assertTrue(alice != bob)
    }

    @Test
    fun aLongRecordingsLabelsNameTheSamePeopleThroughout() {
        val sound = conversation(30)
        val words = (0 until 30).map { m -> TimedWord("Minute $m.", (m * 60_000 + 1_000).toUInt(), (m * 60_000 + 2_000).toUInt()) }
        val labeller = DesktopSpeakerLabeller(StandIn()) { null }
        val windows = labeller.windows(sound.size.toLong())!!
        var at = 0
        while (at < sound.size) {
            val to = minOf(at + 32_768, sound.size)
            windows.push(sound.copyOfRange(at, to))
            at = to
        }
        val text = labeller.labelWith(windows, words)!!
        val paragraphs = text.split("\n\n")
        assertEquals(30, paragraphs.size)
        assertTrue(paragraphs.withIndex().all { (m, p) -> p.startsWith(if (m % 2 == 0) "**Speaker 1:**" else "**Speaker 2:**") }, text)
    }

    @Test
    fun aSpeakerModelThatFailsLeavesThePlainTranscript() {
        val failing = object : Diarizer {
            override fun turns(samples: FloatArray, count: Int?): List<SpeakerTurn> = error("out of memory, say")
            override fun voiceOf(samples: FloatArray): FloatArray? = null
        }
        assertNull(hear(failing, conversation(30), count = null))
    }

    private companion object {
        const val MINUTE = 60L * WAV_SAMPLE_RATE
        const val ALICE = 0.25f
        const val BOB = -0.5f
    }
}
