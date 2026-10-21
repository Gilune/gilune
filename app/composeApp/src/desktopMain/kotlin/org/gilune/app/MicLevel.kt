// The microphone's level beside a recording mic (owner, 2026-10-21): a few
// bars that move with the voice, so a person can see the microphone is
// hearing them before any words arrive — and see at once when it is not.
// It shows what the microphone hears, not what any model understands, so
// it is the same whichever transcriber is on, and whether or not one is.

package org.gilune.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/// How many bars, newest on the right.
const val LEVEL_BARS = 7

/// How often the level is sampled, in milliseconds.
const val LEVEL_SAMPLE_MS = 50L

/// Below this the microphone is taken to hear nothing at all.
const val QUIET_LEVEL = 0.08f

/// How long a recording may hear nothing before the person is told.
const val QUIET_WARNING_MS = 3_000L

/// One tick per sample, in real time. Off the composition's own clock on
/// purpose: an endless delay loop there keeps a UI test from ever being
/// idle, so the ticks come from outside and a test can supply its own.
fun levelTicks(periodMs: Long = LEVEL_SAMPLE_MS): Flow<Unit> =
    flow { while (true) { delay(periodMs); emit(Unit) } }.flowOn(Dispatchers.Default)

/// The bars after one more sample: the oldest drops off the left.
fun pushLevel(history: List<Float>, level: Float, bars: Int = LEVEL_BARS): List<Float> =
    (history + level.coerceIn(0f, 1f)).takeLast(bars)

/// The level as bars, sampled from `level` while this is on screen. Calls
/// `onSilence` once if nothing has been heard for [QUIET_WARNING_MS] —
/// a muted microphone or the wrong input device, caught before somebody
/// talks for a minute to nothing.
@Composable
fun MicLevelBars(
    level: () -> Float,
    onSilence: () -> Unit,
    modifier: Modifier = Modifier,
    ticks: Flow<Unit> = remember { levelTicks() },
) {
    var history by remember { mutableStateOf(List(LEVEL_BARS) { 0f }) }
    val currentLevel by rememberUpdatedState(level)
    val currentOnSilence by rememberUpdatedState(onSilence)
    LaunchedEffect(Unit) {
        var quietMs = 0L
        var warned = false
        ticks.collect {
            val now = currentLevel()
            history = pushLevel(history, now)
            quietMs = if (now >= QUIET_LEVEL) 0L else quietMs + LEVEL_SAMPLE_MS
            if (!warned && quietMs >= QUIET_WARNING_MS) {
                warned = true
                currentOnSilence()
            }
        }
    }
    val colour = MaterialTheme.colorScheme.error
    val loudest = history.maxOrNull() ?: 0f
    Canvas(
        modifier
            .size(width = 28.dp, height = 16.dp)
            .testTag("mic-level")
            .semantics { contentDescription = if (loudest >= QUIET_LEVEL) "microphone hearing sound" else "microphone hearing nothing" },
    ) {
        val gap = size.width / (LEVEL_BARS * 3 - 1)
        val bar = gap * 2
        val floor = 2.dp.toPx()
        history.forEachIndexed { i, value ->
            val height = floor + (size.height - floor) * value
            drawRoundRect(
                color = colour,
                topLeft = Offset(i * (bar + gap), (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2, bar / 2),
            )
        }
    }
}

/// What a person is told when the bars have stayed flat.
const val QUIET_WARNING =
    "the microphone is not hearing anything — check it is not muted and that the right " +
        "input device is chosen in your sound settings"
