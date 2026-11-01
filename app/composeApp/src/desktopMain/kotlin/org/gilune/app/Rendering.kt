// How the window is drawn: on the CPU by default, on the graphics card
// when the person asks (09 § Desktop footprint, owner decision 2026-11-01).
//
// Measured on the owner's 5120×1440 screen with a 1,500-line note
// scrolling: Skia on Direct3D sustained 75–83 frames a second at
// 413–417 MB; on the CPU, 39–43 frames at 292 MB — and with less CPU
// time, since the work is Compose laying out text, not painting it.
// Memory won: software is the default, and the graphics card is a
// setting for anyone who would rather have the frames.

package org.gilune.app

/// Skiko's renderer is fixed when its first window is made, so this runs
/// in `main` before any. A renderer named on the command line (or in
/// `JAVA_TOOL_OPTIONS`) is left alone: that is how a probe or a person
/// chasing a drawing fault picks one.
fun chooseRenderer(gpuDrawing: Boolean = Prefs.gpuDrawingAtStart) {
    rendererFor(System.getProperty(SKIKO_RENDER_API), gpuDrawing)?.let { System.setProperty(SKIKO_RENDER_API, it) }
}

/// The renderer to set, or null to leave Skiko's choice alone: an explicit
/// one wins, and the graphics card is Skiko's own default (Direct3D on
/// Windows, OpenGL on Linux).
internal fun rendererFor(explicit: String?, gpuDrawing: Boolean): String? = when {
    !explicit.isNullOrBlank() -> null
    gpuDrawing -> null
    else -> SOFTWARE_RENDERER
}

internal const val SKIKO_RENDER_API = "skiko.renderApi"
internal const val SOFTWARE_RENDERER = "SOFTWARE_FAST"
