package org.gilune.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import java.awt.geom.AffineTransform
import java.util.concurrent.FutureTask
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayChangesTest {
    private fun <T> edt(action: () -> T): T {
        val task = FutureTask(action)
        EventQueue.invokeAndWait(task)
        return task.get()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!edt(condition)) {
            check(System.nanoTime() < deadline) { "Desktop display update did not finish" }
            Thread.sleep(10)
        }
    }

    private fun display(scale: Double = 1.0, device: String = "laptop") = DisplayConfiguration(
        device, Rectangle(0, 0, 1920, 1080), AffineTransform.getScaleInstance(scale, scale), Dimension(1920, 1080),
    )

    @Test fun monitorAndScaleChangesRefreshButOrdinaryWindowMovesDoNot() {
        val frame = edt { JFrame().apply {
            isAutoRequestFocus = false
            setSize(300, 200)
            setLocation(-10_000, -10_000)
            isVisible = true
        } }
        var current = display()
        var reads = 0
        var refreshes = 0
        val watch = edt { watchDisplayChanges(frame, { reads++; current }, {
            assertTrue(EventQueue.isDispatchThread())
            refreshes++
        }) }
        try {
            edt { repeat(10) { frame.dispatchEvent(ComponentEvent(frame, ComponentEvent.COMPONENT_MOVED)) } }
            await { reads >= 3 }
            edt { assertEquals(0, refreshes) }
            for (next in listOf(display(1.5), display(1.5, "dock"), display())) {
                val before = edt { refreshes }
                edt { current = next; frame.dispatchEvent(ComponentEvent(frame, ComponentEvent.COMPONENT_MOVED)) }
                await { refreshes == before + 2 }
            }
            val configurationListener = edt { frame.getPropertyChangeListeners("graphicsConfiguration").last() }
            edt { current = display(2.0) }
            val beforeNotification = edt { refreshes }
            configurationListener.propertyChange(java.beans.PropertyChangeEvent(frame, "graphicsConfiguration", null, null))
            await { refreshes == beforeNotification + 2 }
            edt {
                current = display(1.25)
                frame.dispatchEvent(ComponentEvent(frame, ComponentEvent.COMPONENT_MOVED))
                watch.close()
            }
            val before = edt { reads to refreshes }
            Thread.sleep(700)
            edt { assertEquals(before, reads to refreshes, "disposing cancels scheduled checks") }
        } finally { edt { watch.close(); frame.dispose() } }
    }

    @Test fun aDisplayChangeRepairsTheNativeCanvasWithoutRecreatingTheCompositionOrChangingTheDraft() {
        val draft = mutableStateOf(TextFieldValue("Unsaved note", TextRange(4)))
        var creations = 0
        var disposals = 0
        var current = display()
        var refreshes = 0
        val window = edt { ComposeWindow().apply {
            isAutoRequestFocus = false
            setSize(480, 320)
            setLocation(-10_000, -10_000)
            setContent {
                remember { creations++ }
                DisposableEffect(Unit) { onDispose { disposals++ } }
                Box(Modifier.fillMaxSize()) { BasicTextField(draft.value, { draft.value = it }) }
            }
            isVisible = true
        } }
        val watch = edt { watchDisplayChanges(window, { current }) {
            refreshDisplaySurface(window)
            refreshes++
        } }
        try {
            await { creations == 1 && descendants(window).filterIsInstance<SkiaLayer>().any { it.width > 0 } }
            val bounds = edt { Rectangle(window.bounds) }
            edt {
                val layer = descendants(window).filterIsInstance<SkiaLayer>().single()
                layer.canvas.setSize(layer.width / 2, layer.height / 2)
                assertTrue(layer.canvas.width < layer.width)
                current = display(1.5, "dock")
                window.dispatchEvent(ComponentEvent(window, ComponentEvent.COMPONENT_MOVED))
            }
            await { refreshes == 1 }
            edt {
                // A child peer may finish its DPI transition after the window.
                val layer = descendants(window).filterIsInstance<SkiaLayer>().single()
                layer.canvas.setSize(layer.width / 2, layer.height / 2)
            }
            await { refreshes == 2 }
            edt {
                val layer = descendants(window).filterIsInstance<SkiaLayer>().single()
                // Skiko may add one pixel at fractional scale to cover rounding.
                assertTrue(layer.canvas.width in layer.width..layer.width + 1)
                assertTrue(layer.canvas.height in layer.height..layer.height + 1)
                assertEquals(bounds, window.bounds)
                assertEquals(1, creations)
                assertEquals(0, disposals)
                assertEquals(TextFieldValue("Unsaved note", TextRange(4)), draft.value)
            }
        } finally { edt { watch.close(); window.dispose() } }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
