package org.gilune.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Rule
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticsThreadTest {
    @get:Rule val compose = createDeviceComposeRule()

    @Test fun selectorsInspectLiveNodesOnTheUiThread() {
        compose.setContent { Text("Thread ownership probe") }
        val uiThread = compose.runOnUiThread { Thread.currentThread().id }
        val selectorThread = AtomicLong(-1)
        compose.onAllNodes(SemanticsMatcher("records the selection thread") {
            selectorThread.set(Thread.currentThread().id)
            true
        }).fetchSemanticsNodes()
        assertEquals(uiThread, selectorThread.get(), "Live semantics selection must not race main-thread drawing")
    }
}
