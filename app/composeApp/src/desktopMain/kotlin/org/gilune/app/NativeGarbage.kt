// Skia's text layouts live in native memory, and a layout is freed only when
// the Java collector finds its wrapper dead and Skia's cleaner thread runs.
// The collector cannot see that memory. A small heap allocates slowly while
// Skia allocates fast, so collections came far apart: typing 150 letters
// into a 6 KB note took the app from 142 to 271 MB, and into a 64 KB note
// past 450 MB; once typing stopped nothing collected at all, and the dead
// layouts stayed (measured 2026-11-01, ADR 0032).
//
// While typing, most are freed by the young collections the editor's own
// allocation brings: the launch flags give the survivor spaces room for a
// layout to die young (ADR 0032, 2026-11-02). What is still promoted waits
// for a full collection, so the editor reports each layout it replaces and
// one is forced per [BUDGET_BYTES] (16 MB) of replaced layout — on the 64 KB
// note about one every keystroke and a half, where the old 4 MB budget with
// default survivors forced one every keystroke — and once more when editing
// pauses, since nothing collects in a quiet app. Each takes 25–40 ms, on
// this thread rather than the UI's.
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

    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Gilune native memory").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    /// How much replaced layout may pile up before a collection is forced
    /// mid-typing. Measured 2026-11-02 typing 150 letters fast into the
    /// 64 KB note, with the survivor flags: no budget peaked at 463 MB (some
    /// layouts are still promoted under sustained typing), 4 MB at 286 MB
    /// for 37.8 s of CPU, 16 MB at 292 MB for 32.3 s, 32 MB at 311 MB.
    /// Run-to-run noise is about ±20 MB.
    const val BUDGET_BYTES = 16L shl 20

    /// Native bytes a laid-out character costs, roughly: a relayout of the
    /// 64 KB note left ~10–13 MB behind.
    const val BYTES_PER_CHAR = 200L

    private var owed = 0L

    /// A layout of `chars` characters has just replaced another.
    @Synchronized
    fun layoutReplaced(chars: Int) {
        owed += chars * BYTES_PER_CHAR
        if (owed >= BUDGET_BYTES) collect(0L) else soon()
    }

    /// Something holding layouts was put away (a note closed, a view left),
    /// or a layout was replaced: collect once things are still. Each call
    /// moves the collection later.
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
