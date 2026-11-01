// Skia's text layouts live in native memory, and a layout is freed only when
// the Java collector finds its wrapper dead and Skia's cleaner thread runs.
// The collector cannot see that memory. A small heap allocates slowly while
// Skia allocates fast, so collections came far apart: typing 150 letters
// into a 6 KB note took the app from 142 to 271 MB, and into a 64 KB note
// past 450 MB; once typing stopped nothing collected at all, and the dead
// layouts stayed (measured 2026-11-01, ADR 0032).
//
// So the editor reports each layout it replaces, sized by its text, and a
// collection is asked for once the layouts thrown away add up to
// [BUDGET_BYTES] (4 MB), and once more when editing pauses. A full collection of
// this app's ~20 MB heap takes 25–40 ms, on this thread rather than the UI's.
//
// Skia's paragraph cache is switched off as well. It keeps a copy of every
// paragraph it lays out, keyed by the paragraph's text, and an editor makes a
// new text with every keystroke: it never hit, and held ~45 MB of copies of
// texts that no longer existed.

package org.gilune.app

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal object NativeGarbage {
    /// After the last relayout, the pause that counts as editing having
    /// stopped.
    const val SETTLE_MS = 1_000L

    /// How much thrown-away layout is let pile up before collecting.
    /// Measured on fast typing into a 6 KB note: 16 MB peaked at 196 MB,
    /// 8 MB at 188, 4 MB at 180 (base + 50) for no CPU that showed.
    const val BUDGET_BYTES = 4L shl 20

    /// Native bytes a laid-out character costs, roughly: a relayout of the
    /// 64 KB note left ~10–13 MB behind.
    const val BYTES_PER_CHAR = 200L

    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Gilune native memory").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null
    private var owed = 0L

    /// A layout of `chars` characters has just replaced another.
    @Synchronized
    fun layoutReplaced(chars: Int) {
        owed += chars * BYTES_PER_CHAR
        collect(if (owed >= BUDGET_BYTES) 0L else SETTLE_MS)
    }

    /// Something holding layouts was put away (a note closed, a view left).
    @Synchronized
    fun soon() = collect(SETTLE_MS)

    private fun collect(delayMs: Long) {
        pending?.cancel(false)
        pending = timer.schedule({
            synchronized(this) { owed = 0 }
            System.gc()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /// Switches off Skia's paragraph cache in Compose's one font collection
    /// and drops what it holds. Compose keeps the collection private, so it
    /// is reached by reflection; `NativeGarbageTest` fails if a Compose
    /// upgrade moves it. True when it was found.
    fun disableParagraphCache(fontFamilyResolver: Any): Boolean {
        val cache = paragraphCache(fontFamilyResolver) ?: return false
        cache.setEnabled(false)
        cache.reset()
        return true
    }

    internal fun paragraphCache(resolver: Any): org.jetbrains.skia.paragraph.ParagraphCache? = runCatching {
        val loaderField = resolver.javaClass.getDeclaredField("platformFontLoader").apply { isAccessible = true }
        val loader = loaderField.get(resolver)
        val cacheField = loader.javaClass.getDeclaredField("fontCache\$delegate").apply { isAccessible = true }
        val fontCache = (cacheField.get(loader) as Lazy<*>).value!!
        val fonts = fontCache.javaClass.getDeclaredMethod("getFonts\$ui_text").invoke(fontCache) as org.jetbrains.skia.paragraph.FontCollection
        fonts.paragraphCache
    }.onFailure { GiluneLog.warn("Skia's paragraph cache could not be reached: $it") }.getOrNull()
}
