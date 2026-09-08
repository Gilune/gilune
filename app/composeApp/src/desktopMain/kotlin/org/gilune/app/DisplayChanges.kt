// Reconcile the existing desktop surface after monitor/DPI changes. A dock
// transition can leave native bounds stale until the next manual resize.
package org.gilune.app

import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.AffineTransform
import java.beans.PropertyChangeListener
import javax.swing.Timer

internal data class DisplayConfiguration(
    val device: String,
    val bounds: Rectangle,
    val transform: AffineTransform,
    val pixels: Dimension,
)

private fun Window.displayConfiguration(): DisplayConfiguration? = graphicsConfiguration?.let {
    val mode = it.device.displayMode
    DisplayConfiguration(it.device.iDstring, Rectangle(it.bounds), AffineTransform(it.defaultTransform),
        Dimension(mode.width, mode.height))
}

/** Watches display transitions on the EDT. Ordinary moves/resizes do no extra
 * rendering. Closing removes listeners and cancels pending settlement checks. */
internal fun watchDisplayChanges(
    window: Window,
    readDisplay: () -> DisplayConfiguration? = { window.displayConfiguration() },
    refresh: () -> Unit = { refreshDisplaySurface(window) },
): AutoCloseable {
    check(EventQueue.isDispatchThread())
    var previous = readDisplay()
    var closed = false
    var remaining = 0
    var followUpRefresh = false
    // Windows can update a window's configuration before its child canvas.
    // Check after the event burst, then once more after the transition settles.
    val timer = Timer(500, null).apply { initialDelay = 150 }
    timer.addActionListener {
        if (!closed && window.isShowing) {
            val current = readDisplay()
            if (current != null) {
                val changed = current != previous
                if (changed || followUpRefresh) {
                    refresh()
                    previous = current
                }
                followUpRefresh = changed
            }
        }
        if (--remaining <= 0) {
            if (followUpRefresh && !closed && window.isShowing) remaining = 1 else timer.stop()
        }
    }
    fun schedule() {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater { schedule() }
            return
        }
        if (!closed) { remaining = 2; timer.restart() }
    }
    val configurationListener = PropertyChangeListener { schedule() }
    val componentListener = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = schedule()
        override fun componentResized(e: ComponentEvent) = schedule()
        override fun componentShown(e: ComponentEvent) = schedule()
    }
    val windowListener = object : WindowAdapter() {
        override fun windowActivated(e: WindowEvent) = schedule()
        override fun windowStateChanged(e: WindowEvent) = schedule()
    }
    window.addPropertyChangeListener("graphicsConfiguration", configurationListener)
    window.addComponentListener(componentListener)
    window.addWindowListener(windowListener)
    window.addWindowStateListener(windowListener)
    return AutoCloseable {
        check(EventQueue.isDispatchThread())
        closed = true
        timer.stop()
        window.removePropertyChangeListener("graphicsConfiguration", configurationListener)
        window.removeComponentListener(componentListener)
        window.removeWindowListener(windowListener)
        window.removeWindowStateListener(windowListener)
    }
}

/** Runs layout and Skiko's public scale/bounds refresh on existing components;
 * it never resizes the outer window or recreates the editor composition. */
internal fun refreshDisplaySurface(window: Window) {
    check(EventQueue.isDispatchThread())
    if (!window.isShowing) return
    val components = descendants(window).toList()
    val layers = components.filterIsInstance<SkiaLayer>().filter { it.isShowing }
    layers.forEach { it.checkContentScale() }
    components.asReversed().forEach { it.invalidate() }
    window.validate()
    layers.forEach {
        // Skiko 0.8.18 doLayout also synchronises the native renderer bounds.
        it.doLayout()
        it.needRedraw()
    }
    window.repaint()
}

private fun descendants(component: Component): Sequence<Component> = sequence {
    yield(component)
    if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
}
