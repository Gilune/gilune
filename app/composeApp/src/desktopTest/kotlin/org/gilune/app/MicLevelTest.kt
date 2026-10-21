// The microphone level bars (owner, 2026-10-21): the loudness they show,
// and the warning when they stay flat.

package org.gilune.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Rule
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MicLevelTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pcm(seconds: Double = 0.1, sample: (Int) -> Double): ByteArray {
        val n = (seconds * WAV_SAMPLE_RATE).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sample(i) * 32767).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Test
    fun silenceReadsZeroAndFullScaleReadsOne() {
        assertEquals(0f, pcmLevel(pcm { 0.0 }))
        assertEquals(0f, pcmLevel(ByteArray(0)))
        assertTrue(pcmLevel(pcm { i -> if (i % 2 == 0) 1.0 else -1.0 }) > 0.99f)
    }

    @Test
    fun speechLoudnessSitsOnADecibelScale() {
        // A sine at -20 dBFS peak is about -23 dBFS RMS: 0.62 of the way
        // from the -60 dB floor to full scale. A linear meter would show 0.07.
        val sine = pcm { i -> 0.1 * sin(2 * PI * 440 * i / WAV_SAMPLE_RATE) }
        assertTrue(abs(pcmLevel(sine) - 0.62f) < 0.02f, "got ${pcmLevel(sine)}")
        // Room hiss below the floor is nothing.
        assertEquals(0f, pcmLevel(pcm { i -> if (i % 2 == 0) 0.0005 else -0.0005 }))
    }

    @Test
    fun theOldestBarDropsOffTheLeft() {
        val history = (1..7).map { it / 10f }
        assertEquals(listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 1f), pushLevel(history, 3f))
    }

    /// Ticks the test hands over one by one: each is one sample.
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    private fun tick(count: Long) {
        repeat(count.toInt()) { ticks.trySend(Unit) }
        compose.waitForIdle()
    }

    private val perSecond = 1_000 / LEVEL_SAMPLE_MS

    @Test
    fun aMicrophoneThatHearsNothingIsReportedOnce() {
        var warnings = 0
        compose.setContent { MicLevelBars({ 0f }, onSilence = { warnings++ }, ticks = ticks.receiveAsFlow()) }
        tick(QUIET_WARNING_MS / LEVEL_SAMPLE_MS - 1)
        assertEquals(0, warnings, "not before three seconds of nothing")
        tick(1)
        assertEquals(1, warnings)
        tick(10 * perSecond)
        assertEquals(1, warnings, "said once, not every three seconds")
    }

    @Test
    fun aVoiceKeepsTheWarningAwayAndPausesDoNotAddUp() {
        // The step past the obvious: two quiet stretches of two seconds
        // either side of speech are not four seconds of silence.
        var warnings = 0
        var level = 0f
        compose.setContent { MicLevelBars({ level }, onSilence = { warnings++ }, ticks = ticks.receiveAsFlow()) }
        tick(2 * perSecond)
        level = 0.5f
        tick(perSecond / 2)
        level = 0f
        tick(2 * perSecond)
        assertEquals(0, warnings)
        compose.onAllNodesWithTag("mic-level").assertCountEquals(1)
    }
}
