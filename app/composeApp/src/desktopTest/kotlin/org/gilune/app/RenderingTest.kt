// The window draws on the CPU unless the person asks for the graphics card
// (Rendering.kt, owner decision 2026-11-01), and a renderer named at launch
// always wins — probes and fault-finding depend on it.

package org.gilune.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenderingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theProcessorDrawsUnlessTheGraphicsCardIsAskedFor() {
        assertEquals(SOFTWARE_RENDERER, rendererFor(explicit = null, gpuDrawing = false))
        assertNull(rendererFor(explicit = null, gpuDrawing = true), "the graphics card is Skiko's own choice")
    }

    @Test
    fun aRendererNamedAtLaunchIsLeftAlone() {
        assertNull(rendererFor(explicit = "DIRECT3D", gpuDrawing = false))
        assertNull(rendererFor(explicit = "OPENGL", gpuDrawing = true))
        assertEquals(SOFTWARE_RENDERER, rendererFor(explicit = " ", gpuDrawing = false), "a blank value names nothing")
    }

    @Test
    fun theSwitchSaysWhenItTakesEffect() {
        var stored = false
        compose.setContent {
            MaterialTheme {
                var gpu by remember { mutableStateOf(false) }
                DrawingSetting(gpu, atStart = false) {
                    gpu = it
                    stored = it
                }
            }
        }
        compose.onNodeWithTag("gpu-drawing").assertIsOff()
        compose.onNodeWithTag("gpu-drawing-note").assert(!hasText("next starts", substring = true))
        compose.onNodeWithTag("gpu-drawing").performClick()
        compose.onNodeWithTag("gpu-drawing").assertIsOn()
        compose.onNodeWithTag("gpu-drawing-note").assertTextContains("Takes effect when Gilune next starts", substring = true)
        assertEquals(true, stored)
        // Switched back to what this run is drawing with: nothing to wait for.
        compose.onNodeWithTag("gpu-drawing").performClick()
        compose.onNodeWithTag("gpu-drawing-note").assert(!hasText("next starts", substring = true))
    }
}
