// Live dictation in the editor (ADR 0027 § 1): words land in the note as
// they are spoken. Words the engine may still revise are drawn grey at the
// dictation point and are never in the buffer; words it has settled are
// inserted as ordinary edits, so autosave, the index, sync and any other
// tool reading the vault only ever see words that will not change.
//
// The engine runs in the kernel (`LiveTranscription`); this file is the
// shell's half: where the words go, how they are drawn, and the thread
// that keeps a slow decode away from both the microphone and the UI.

package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import uniffi.gilune_ffi.LiveLine
import uniffi.gilune_ffi.LiveTranscriptionInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/// Bytes of 16 kHz mono 16-bit PCM in one second.
private const val PCM_BYTES_PER_SECOND = 32_000

/// How often the engine is asked for an update, in seconds of new audio.
/// Whisper re-decodes its whole window each time, so asking more often
/// buys little and costs a full decode.
const val LIVE_STEP_SECONDS = 1.0

/// How long one decode may take, in seconds, before the words are judged
/// to be lagging too far behind the speaker (ADR 0027 amendment: measured,
/// not promised). Each decode hears everything that arrived during the
/// last one, so a slow decode makes the next step longer rather than
/// leaving audio queued; the lag *is* the decode time. Two consecutive
/// decodes over this, not counting the first — it loads the model — and
/// the grey words stop; the rest arrives on stop.
const val LIVE_MAX_LAG_SECONDS = 4.0

/// One dictation landing in one note.
class LiveDictation(anchor: Int) {
    /// Where the next settled words go: a buffer offset, carried through
    /// every edit by [EditorState.text]'s setter.
    var anchor by mutableStateOf(anchor)

    /// What the engine may still revise, drawn grey at [anchor]. Never in
    /// the buffer.
    var provisional by mutableStateOf("")

    /// The engine could not keep up; nothing grey is shown until the stop.
    var paused by mutableStateOf(false)

    /// Whether any words have been settled into the buffer yet.
    var settledAny = false
        private set

    /// The thread feeding the engine, so a stop reaches it from whichever
    /// composition the click came from.
    var run: LiveDictationRun? = null

    private var lines: List<CaptionLine> = emptyList()
    private val placed = mutableSetOf<Long>()

    /// The engine's lines through the caption fold (ADR 0018 § 1's rules,
    /// shared since ADR 0027 S0): a line that settles never changes again,
    /// so it is exactly then that it may enter the buffer — once.
    internal fun fold(update: List<LiveLine>): List<String> {
        for (line in update) {
            lines = foldCaption(
                lines,
                CaptionEvent(CaptionEvent.TEXT_CHANGED, line.id.toLong(), line.text, line.complete),
                retain = Int.MAX_VALUE,
            )
        }
        val fresh = lines.filter { it.settled && it.id !in placed && it.text.isNotBlank() }
        placed += fresh.map { it.id }
        lines = lines.filter { !it.settled }
        provisional = lines.joinToString(" ") { it.text.trim() }.trim()
        if (fresh.isNotEmpty()) settledAny = true
        return fresh.map { it.text.trim() }
    }

    /// The grey run as drawn, with the space a word needs before it.
    fun ghost(text: String): String? {
        if (paused) return null
        val words = provisional.trim().ifEmpty { return null }
        return separatorBefore(text, anchor.coerceIn(0, text.length)) + words
    }
}

/// The space dictated words need in front of them: one, unless they start
/// a line or follow whitespace already.
fun separatorBefore(text: String, at: Int): String =
    if (at > 0 && !text[at - 1].isWhitespace()) " " else ""

/// Where an offset ends up when `old` becomes `new`. The changed span is
/// found as everything between the common prefix and the common suffix: a
/// change wholly before the anchor (or ending at it — typing at the
/// dictation point) moves it by the change's length; a change after it
/// leaves it; a change across it puts it at the end of the new text.
fun mapAnchor(old: String, new: String, anchor: Int): Int {
    if (old == new) return anchor
    val shortest = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < shortest && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    val oldEnd = old.length - suffix
    val newEnd = new.length - suffix
    val mapped = when {
        oldEnd <= anchor -> anchor + (new.length - old.length)
        prefix >= anchor -> anchor
        else -> newEnd
    }
    return mapped.coerceIn(0, new.length)
}

/// Fold an engine update into the note: settled words become an edit at
/// the anchor, the rest becomes the grey run. A caret at or after the
/// anchor moves with the words, as it would had they been typed; a caret
/// elsewhere stays where the person put it.
fun EditorState.acceptLive(session: LiveDictation, update: List<LiveLine>) {
    for (words in session.fold(update)) {
        val current = text
        val at = session.anchor.coerceIn(0, current.text.length)
        val inserted = separatorBefore(current.text, at) + words
        fun shift(p: Int) = if (p >= at) p + inserted.length else p
        text = TextFieldValue(
            text = current.text.substring(0, at) + inserted + current.text.substring(at),
            selection = TextRange(shift(current.selection.start), shift(current.selection.end)),
        )
        // Set, not mapped: the words went in at the anchor, so the next
        // ones follow them, whatever the diff could read into it.
        session.anchor = at + inserted.length
        dirty = true
    }
}

/// Start a dictation at the caret. A selection is replaced, as typing
/// would replace it, so the words that land are the ones that replace it.
fun EditorState.beginLive(): LiveDictation {
    val current = text
    val from = current.selection.min.coerceIn(0, current.text.length)
    val to = current.selection.max.coerceIn(0, current.text.length)
    if (to > from) {
        text = TextFieldValue(current.text.removeRange(from, to), TextRange(from))
        dirty = true
    }
    return LiveDictation(from).also { live = it }
}

/// The grey run, drawn by wrapping the editor's own transformation: the
/// words are inserted into the *displayed* text at the anchor, and the
/// offset table steps over them, so the caret, clicks and selection all
/// keep meaning buffer offsets. Styling never changes bytes (ADR 0012) —
/// here because the bytes never contain these words at all.
class DictationTransformation(
    private val base: VisualTransformation,
    private val anchor: Int,
    private val ghost: String,
    private val style: SpanStyle,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val inner = base.filter(text)
        val at = anchor.coerceIn(0, text.length)
        val shown = inner.offsetMapping.originalToTransformed(at).coerceIn(0, inner.text.length)
        val length = ghost.length
        val drawn = AnnotatedString.Builder(inner.text.length + length).apply {
            append(inner.text.subSequence(0, shown))
            pushStyle(style)
            append(ghost)
            pop()
            append(inner.text.subSequence(shown, inner.text.length))
        }.toAnnotatedString()
        val mapping = object : OffsetMapping {
            // The caret sits before the grey words when it is at the
            // dictation point: they are what comes next.
            override fun originalToTransformed(offset: Int): Int {
                val placed = inner.offsetMapping.originalToTransformed(offset)
                return if (offset > at) placed + length else placed
            }

            override fun transformedToOriginal(offset: Int): Int = when {
                offset <= shown -> inner.offsetMapping.transformedToOriginal(offset)
                offset < shown + length -> at
                else -> inner.offsetMapping.transformedToOriginal(offset - length)
            }
        }
        return TransformedText(drawn, mapping)
    }
}

/// The thread between the microphone and the engine. The recorder only
/// ever puts bytes in a queue — it must never wait on a decode, or the
/// recording would drop audio. This thread hands them to the engine —
/// **all** of what is waiting, before every decode — asks for an update
/// once at least a second's worth is new, and passes what comes back to
/// `deliver` (which the caller routes onto the UI thread).
///
/// Whether it keeps up is measured: when decodes keep taking longer than
/// [LIVE_MAX_LAG_SECONDS], it stops asking, says so once through
/// `fellBehind`, and keeps feeding the engine so the stop can still settle
/// everything.
///
/// It used to decode as soon as one second had arrived, leaving the rest
/// queued. A decode slower than a second then left more behind it each
/// time, the first one (which loads the model) tripped the check at once,
/// and the grey words never showed (owner, 2026-10-21).
class LiveDictationRun(
    private val engine: LiveTranscriptionInterface,
    private val deliver: (List<LiveLine>) -> Unit,
    private val fellBehind: (String) -> Unit,
    private val maxLagSeconds: Double = LIVE_MAX_LAG_SECONDS,
    private val stepSeconds: Double = LIVE_STEP_SECONDS,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var stopping: ((List<LiveLine>) -> Unit)? = null
    private var behind = false
    private val worker = Thread(::loop, "gilune-live-dictation").apply { isDaemon = true }

    fun start() = worker.start()

    /// Called on the recorder's thread. Never blocks.
    fun offer(pcm: ByteArray) {
        queue.offer(pcm)
    }

    /// The microphone stopped. `done` receives the last words, on the
    /// worker thread, once everything queued has been heard.
    fun stop(done: (List<LiveLine>) -> Unit) {
        stopping = done
    }

    /// The engine refused audio — most often a model that would not load,
    /// since it loads with the first chunk. The recording goes on (the
    /// recorder never waits on this thread); only the live words stop.
    private var deaf = false

    private fun feed(pcm: ByteArray) {
        val refused = runCatching { engine.accept(pcm) }.exceptionOrNull() ?: return
        deaf = true
        GiluneLog.warn("live dictation could not hear the audio", refused)
        fellBehind("live words stopped (${refused.message}); your recording is kept")
    }

    private fun loop() {
        var fresh = 0
        var decodes = 0
        var slow = 0
        while (true) {
            val done = stopping
            if (done != null && queue.isEmpty()) {
                done(runCatching { engine.finish() }.getOrElse { emptyList() })
                return
            }
            val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (deaf) continue
            // Whatever arrived during the last decode is heard by this one.
            var next: ByteArray? = chunk
            while (next != null && !deaf) {
                feed(next)
                fresh += next.size
                next = queue.poll()
            }
            if (deaf || behind || stopping != null || fresh < stepSeconds * PCM_BYTES_PER_SECOND) continue
            fresh = 0
            val started = System.nanoTime()
            val update = runCatching { engine.update() }
            val took = (System.nanoTime() - started) / 1e9
            val failure = update.exceptionOrNull()
            if (failure != null) {
                behind = true
                fellBehind("live words stopped (${failure.message}); the rest will arrive when you stop")
                continue
            }
            deliver(update.getOrThrow())
            if (decodes++ == 0) continue
            slow = if (took > maxLagSeconds) slow + 1 else 0
            if (slow >= 2) {
                behind = true
                fellBehind(
                    "transcription could not keep up on this machine, so the words will arrive " +
                        "when you stop — a smaller model may keep up",
                )
            }
        }
    }
}

private fun onUi(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)

/// Start live words for a dictation into `editor`: the session anchors at
/// the caret, the recorder tees its PCM to the engine's thread, and each
/// update lands on the UI thread. Words for a note that has since closed
/// or changed are not lost: they go to the status line, as a finished
/// dictation's always have.
fun startLiveDictation(
    state: GiluneState,
    editor: EditorState,
    recorder: AudioRecorder,
    engine: LiveTranscriptionInterface,
    stepSeconds: Double = LIVE_STEP_SECONDS,
) {
    val session = editor.beginLive()
    val run = LiveDictationRun(
        engine,
        deliver = { update -> onUi { landLive(state, editor, session, update) } },
        fellBehind = { why -> onUi { session.paused = true; state.message.value = why } },
        stepSeconds = stepSeconds,
    )
    session.run = run
    recorder.listen(run::offer)
    run.start()
}

/// Stop live words: the engine hears out what is queued, the last words
/// settle at the anchor, and the grey run goes. `afterwards` runs on the
/// UI thread once they have landed.
fun stopLiveDictation(state: GiluneState, editor: EditorState, session: LiveDictation, afterwards: () -> Unit) {
    val run = session.run ?: return
    run.stop { last ->
        onUi {
            landLive(state, editor, session, last)
            if (editor.live === session) editor.live = null
            session.provisional = ""
            afterwards()
        }
    }
}

private fun landLive(state: GiluneState, editor: EditorState, session: LiveDictation, update: List<LiveLine>) {
    if (editor.live === session) {
        editor.acceptLive(session, update)
        return
    }
    val words = session.fold(update).joinToString(" ")
    if (words.isNotBlank()) state.message.value = "dictation finished after its note closed: $words"
}
