package org.gilune.app

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

class EffectThreadTest {
    @get:Rule val compose = createDeviceComposeRule()

    @Test
    fun backgroundCompletionResumesCompositionEffectsOnTheUiThread() {
        val completion = CompletableDeferred<Unit>()
        val effectThread = AtomicLong(-1)
        val uiThread = compose.runOnUiThread { Thread.currentThread().id }
        compose.setContent {
            LaunchedEffect(Unit) {
                completion.await()
                effectThread.set(Thread.currentThread().id)
            }
        }
        val worker = Thread { completion.complete(Unit) }
        worker.start()
        worker.join(5_000)
        compose.waitUntil(5_000) { effectThread.get() != -1L }
        assertEquals(uiThread, effectThread.get(), "A background result must not move a UI effect onto its producer thread")
    }
}
