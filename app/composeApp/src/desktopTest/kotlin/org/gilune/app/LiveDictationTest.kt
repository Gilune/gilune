// Live dictation's editor rules (ADR 0027 § 1), without an engine: lines
// arrive as the kernel would send them, and the buffer, the anchor and the
// grey run are checked after each.

package org.gilune.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import uniffi.gilune_ffi.LiveLine
import uniffi.gilune_ffi.LiveTranscriptionInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveDictationTest {
    private fun line(id: Long, text: String, complete: Boolean) = LiveLine(id.toULong(), text, complete)

    private fun editorWith(body: String, caret: Int): EditorState =
        EditorState().apply { text = TextFieldValue(body, TextRange(caret)) }

    // ------------------------------------------------------------ anchors

    @Test
    fun anEditBeforeTheAnchorCarriesItAlong() {
        assertEquals(10, mapAnchor("hello world", "hey, hello world", 5))
        assertEquals(2, mapAnchor("hello world", "lo world", 5))
    }

    @Test
    fun anEditAfterTheAnchorLeavesItWhereItIs() {
        assertEquals(5, mapAnchor("hello world", "hello wide world", 5))
    }

    @Test
    fun typingAtTheDictationPointKeepsTheTypingBeforeTheWords() {
        assertEquals(7, mapAnchor("ab", "ab, and", 2))
    }

    @Test
    fun anEditAcrossTheAnchorPutsItAfterTheReplacement() {
        assertEquals(5, mapAnchor("one two three", "one 2 three", 6))
    }

    // ------------------------------------------------------------- landing

    @Test
    fun provisionalWordsAreNeverInTheBuffer() {
        val editor = editorWith("Notes:", 6)
        val session = editor.beginLive()
        editor.acceptLive(session, listOf(line(0, "met the savela", false)))
        assertEquals("Notes:", editor.text.text)
        assertFalse(editor.dirty, "nothing to save until a word settles")
        assertEquals(" met the savela", session.ghost(editor.text.text))
    }

    @Test
    fun settledWordsLandAtTheAnchorAndTheNextOnesFollowThem() {
        val editor = editorWith("Notes:", 6)
        val session = editor.beginLive()
        editor.acceptLive(session, listOf(line(0, "Met the", true), line(1, "surveyor on", false)))
        assertEquals("Notes: Met the", editor.text.text)
        assertTrue(editor.dirty)
        editor.acceptLive(session, listOf(line(1, "surveyor on site.", true), line(2, "", false)))
        assertEquals("Notes: Met the surveyor on site.", editor.text.text)
        assertNull(session.ghost(editor.text.text))
        assertEquals(editor.text.text.length, editor.text.selection.start, "a caret at the dictation point moves with the words")
    }

    @Test
    fun aSettledLineIsInsertedOnceHoweverOftenItIsRepeated() {
        val editor = editorWith("", 0)
        val session = editor.beginLive()
        editor.acceptLive(session, listOf(line(0, "once", true)))
        editor.acceptLive(session, listOf(line(0, "once", true), line(0, "twice?", true)))
        assertEquals("once", editor.text.text)
    }

    @Test
    fun wordsKeepLandingAtTheirPlaceWhileSomeoneTypesAboveIt() {
        // The step past the obvious: the person edits *before* the dictation
        // point between two settles. A stale offset would put the second
        // phrase in the middle of what they typed.
        val editor = editorWith("Title\n\nBody", 11)
        val session = editor.beginLive()
        editor.acceptLive(session, listOf(line(0, "first", true)))
        assertEquals("Title\n\nBody first", editor.text.text)
        editor.text = editor.text.copy(text = "My title\n\nBody first", selection = TextRange(8))
        editor.acceptLive(session, listOf(line(1, "second", true)))
        assertEquals("My title\n\nBody first second", editor.text.text)
        assertEquals(8, editor.text.selection.start, "a caret away from the dictation stays where it was put")
    }

    @Test
    fun aSelectionIsReplacedByTheDictation() {
        val editor = editorWith("keep this bit", 5)
        editor.text = editor.text.copy(selection = TextRange(5, 9))
        val session = editor.beginLive()
        assertEquals("keep  bit", editor.text.text)
        editor.acceptLive(session, listOf(line(0, "that", true)))
        assertEquals("keep that bit", editor.text.text)
    }

    @Test
    fun aPausedSessionShowsNothingGrey() {
        val editor = editorWith("", 0)
        val session = editor.beginLive()
        editor.acceptLive(session, listOf(line(0, "half heard", false)))
        session.paused = true
        assertNull(session.ghost(editor.text.text))
    }

    // ------------------------------------------------------------ drawing

    @Test
    fun theGreyRunIsDrawnAtTheAnchorAndOffsetsStepOverIt() {
        val t = DictationTransformation(VisualTransformation.None, anchor = 5, ghost = " there", style = SpanStyle())
        val out = t.filter(AnnotatedString("hello world"))
        assertEquals("hello there world", out.text.text)
        val map = out.offsetMapping
        assertEquals(5, map.originalToTransformed(5), "the caret at the dictation point sits before the grey words")
        assertEquals(12, map.originalToTransformed(6))
        assertEquals(17, map.originalToTransformed(11))
        assertEquals(5, map.transformedToOriginal(8), "a click inside the grey words means the dictation point")
        assertEquals(6, map.transformedToOriginal(12))
        for (o in 0..11) assertEquals(o, map.transformedToOriginal(map.originalToTransformed(o)), "round trip at $o")
    }

    @Test
    fun theGreyRunComposesWithTheMarkdownDecorations() {
        // A checkbox glyph shortens the displayed line; the grey words must
        // still land after the source offset they belong to.
        val palette = MarkdownPalette(Color.Gray, Color.Blue, Color.Black, Color.Blue, Color.LightGray, Color.Blue)
        val base = MarkdownEditTransformation(palette)
        val source = "- [ ] buy milk"
        val inner = base.filter(AnnotatedString(source))
        val t = DictationTransformation(base, anchor = source.length, ghost = " and eggs", style = SpanStyle())
        val out = t.filter(AnnotatedString(source))
        assertEquals(inner.text.text + " and eggs", out.text.text)
        assertEquals(source.length, out.offsetMapping.transformedToOriginal(out.text.length))
    }

    // ------------------------------------------------------------- thread

    private class SlowEngine(private val decodeMs: Long, private val script: List<List<LiveLine>>) : LiveTranscriptionInterface {
        val accepted = AtomicInteger()
        val updates = AtomicInteger()
        val finished = CountDownLatch(1)
        override fun accept(pcm: ByteArray) { accepted.addAndGet(pcm.size) }
        override fun update(): List<LiveLine> {
            val n = updates.getAndIncrement()
            Thread.sleep(decodeMs)
            return script.getOrElse(n) { emptyList() }
        }
        override fun finish(): List<LiveLine> {
            finished.countDown()
            return listOf(LiveLine(99u, "the end", true))
        }
    }

    private val second = ByteArray(32_000)

    @Test
    fun theMicrophoneNeverWaitsForADecode() {
        val engine = SlowEngine(decodeMs = 2_000, script = emptyList())
        val run = LiveDictationRun(engine, deliver = {}, fellBehind = {})
        run.start()
        val started = System.nanoTime()
        repeat(20) { run.offer(second) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 200, "offering 20 s of audio took $tookMs ms")
        run.stop { }
    }

    @Test
    fun updatesArriveAndTheStopSettlesTheRest() {
        val engine = SlowEngine(decodeMs = 0, script = listOf(listOf(LiveLine(0u, "hello", false))))
        val delivered = mutableListOf<List<LiveLine>>()
        val done = CountDownLatch(1)
        var last: List<LiveLine> = emptyList()
        val run = LiveDictationRun(engine, deliver = { synchronized(delivered) { delivered += it } }, fellBehind = {})
        run.start()
        run.offer(second)
        Thread.sleep(500)
        run.offer(ByteArray(8_000))
        run.stop { last = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("hello", synchronized(delivered) { delivered.flatten().first().text })
        assertEquals("the end", last.single().text)
        assertEquals(40_000, engine.accepted.get(), "every byte heard, including what came after the last update")
    }

    @Test
    fun anEngineThatFallsBehindIsToldOnceAndAskedNoMore() {
        val engine = SlowEngine(decodeMs = 1_500, script = emptyList())
        val warnings = mutableListOf<String>()
        val run = LiveDictationRun(engine, deliver = {}, fellBehind = { synchronized(warnings) { warnings += it } })
        run.start()
        // Five seconds of speech arrive while the first decode is running.
        run.offer(second)
        Thread.sleep(100)
        repeat(5) { run.offer(second) }
        Thread.sleep(2_500)
        repeat(3) { run.offer(second) }
        val done = CountDownLatch(1)
        run.stop { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(1, synchronized(warnings) { warnings.size }, "said once: $warnings")
        assertTrue("keep up" in warnings.single())
        assertEquals(1, engine.updates.get(), "no more decodes once it has fallen behind")
        assertEquals(9 * 32_000, engine.accepted.get(), "but every byte still reaches the engine for the stop")
    }
}
