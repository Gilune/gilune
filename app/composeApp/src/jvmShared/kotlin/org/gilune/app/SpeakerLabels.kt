// Speaker labels (ADR 0030): who said what in a recording, added when the
// recording is transcribed. A speaker model answers *when* each voice
// spoke; a transcriber answers *when* each word was said; this file is the
// pure part that puts the two together, and the plugin's declaration and
// settings. The models and the engine are the desktop's (`Speakers.kt`).

package org.gilune.app

import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.escapeJsonString
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginDeclaration
import uniffi.gilune_ffi.PluginInfo
import uniffi.gilune_ffi.TimedWord

const val SPEAKERS_PLUGIN = "org.memory.speakers"

/// It reads recordings (`blobs.read`, local, granted on install) and
/// downloads its models (`network.request`, always waits for a yes). It
/// writes nothing itself: the transcriber's revision carries the labels.
val SPEAKERS_CAPABILITIES = listOf("blobs.read", NETWORK_CAPABILITY)

fun speakersDeclaration(): PluginDeclaration =
    PluginDeclaration(
        pluginId = SPEAKERS_PLUGIN,
        name = "Speaker labels",
        `class` = "provider",
        profile = "bundled",
        required = false,
        processorId = null,
        subscribes = null,
        emits = emptyList(),
        capabilities = SPEAKERS_CAPABILITIES,
        contributions = listOf(
            PluginContribution(
                slot = Slots.SETTINGS_FORM,
                id = "speaker-settings",
                label = "Speaker models and count",
            ),
        ),
    )

private const val COUNT_KEY = "speakers"

/// The most speakers a person can name; past this, telling voices apart
/// is guesswork whatever the setting says.
const val MAX_SPEAKER_COUNT = 10

/// How many people speak, when somebody said: null means automatic. Absent,
/// zero, or anything out of range is automatic.
fun List<PluginInfo>.speakerCount(): Int? =
    firstOrNull { it.pluginId == SPEAKERS_PLUGIN }?.let { speakerCountIn(it.settings) }

internal fun speakerCountIn(document: String): Int? =
    Regex("\"" + Regex.escape(COUNT_KEY) + "\"\\s*:\\s*(\\d{1,3})\\b")
        .find(document)?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it in 1..MAX_SPEAKER_COUNT }

/// The settings document for a count, or for automatic when null.
fun speakersSettingsDocument(count: Int?): String =
    if (count == null) "{}" else """{"${escapeJsonString(COUNT_KEY)}":$count}"""

/// Whether speaker labels are switched on in this vault.
fun List<PluginInfo>.speakerLabelsOn(): Boolean =
    any { it.pluginId == SPEAKERS_PLUGIN && it.state == "enabled" }

/// One stretch of one voice, in milliseconds from the start of the
/// recording. Speakers are numbered by the model, in no useful order.
data class SpeakerTurn(val startMs: Int, val endMs: Int, val speaker: Int)

/// A run of this many words or more, inside a sentence, that the voices
/// put on somebody else is an interruption and keeps its own speaker. Any
/// shorter, and it is a word whose time the transcriber placed loosely.
private const val INTERRUPTION_WORDS = 3

/// The transcript split into speakers, or null when it has fewer than two:
/// one voice reads exactly as plain text does, with no label at all.
///
/// A transcriber's word times are loosest at pauses — whisper stretches a
/// sentence's last word across the silence after it — and pauses are where
/// speakers change. So the **sentence** is the unit: each goes to the voice
/// it overlaps most, and only a run of [INTERRUPTION_WORDS] or more inside
/// it that clearly belongs to another voice is split off. A transcript
/// with no sentence punctuation at all (a model that writes none) is taken
/// word by word.
///
/// Speakers are renumbered by who speaks first: Speaker 1 opens the
/// recording.
fun labelSpeakers(words: List<TimedWord>, turns: List<SpeakerTurn>): String? {
    if (words.isEmpty() || turns.isEmpty()) return null
    val labels = IntArray(words.size)
    for (sentence in sentencesOf(words)) {
        val own = sentence.map { voiceOf(words[it], turns) }
        val totals = HashMap<Int, Long>()
        for (index in sentence) {
            for (turn in turns) {
                val shared = overlap(words[index], turn)
                if (shared > 0) totals[turn.speaker] = (totals[turn.speaker] ?: 0L) + shared
            }
        }
        val main = totals.maxByOrNull { it.value }?.key
            ?: nearestVoice((words[sentence.first].startMs.toLong() + words[sentence.last].endMs.toLong()) / 2, turns)
        sentence.forEach { labels[it] = main }
        var at = 0
        while (at < own.size) {
            val voice = own[at]
            var end = at
            while (end < own.size && own[end] == voice) end++
            if (voice != null && voice != main && end - at >= INTERRUPTION_WORDS) {
                for (k in at until end) labels[sentence.first + k] = voice
            }
            at = end
        }
    }
    val order = LinkedHashMap<Int, Int>()
    for (label in labels) order.getOrPut(label) { order.size + 1 }
    if (order.size < 2) return null
    val paragraphs = mutableListOf<Pair<Int, MutableList<String>>>()
    for ((index, word) in words.withIndex()) {
        val speaker = order.getValue(labels[index])
        val text = word.text.trim()
        if (text.isEmpty()) continue
        if (paragraphs.lastOrNull()?.first != speaker) paragraphs += speaker to mutableListOf()
        paragraphs.last().second += text
    }
    return paragraphs.joinToString("\n\n") { (speaker, said) -> "**Speaker $speaker:** ${said.joinToString(" ")}" }
}

private val SENTENCE_END = Regex("[.?!…][\"'”’)\\]]*$")

/// Index ranges of the transcript's sentences, or of single words when it
/// has no sentence punctuation to go by.
private fun sentencesOf(words: List<TimedWord>): List<IntRange> {
    if (words.none { SENTENCE_END.containsMatchIn(it.text.trim()) }) return words.indices.map { it..it }
    val sentences = mutableListOf<IntRange>()
    var start = 0
    for ((index, word) in words.withIndex()) {
        if (SENTENCE_END.containsMatchIn(word.text.trim())) {
            sentences += start..index
            start = index + 1
        }
    }
    if (start < words.size) sentences += start until words.size
    return sentences
}

private fun overlap(word: TimedWord, turn: SpeakerTurn): Long {
    val from = maxOf(word.startMs.toLong(), turn.startMs.toLong())
    val to = minOf(word.endMs.toLong(), turn.endMs.toLong())
    return (to - from).coerceAtLeast(0)
}

/// The voice a word overlaps most, or null when it falls in a gap.
private fun voiceOf(word: TimedWord, turns: List<SpeakerTurn>): Int? =
    turns.map { it.speaker to overlap(word, it) }
        .filter { it.second > 0 }
        .groupBy({ it.first }, { it.second })
        .maxByOrNull { (_, shares) -> shares.sum() }
        ?.key

private fun nearestVoice(atMs: Long, turns: List<SpeakerTurn>): Int =
    turns.minBy { turn ->
        when {
            atMs < turn.startMs -> turn.startMs - atMs
            atMs > turn.endMs -> atMs - turn.endMs
            else -> 0L
        }
    }.speaker

/// Words from a sherpa-onnx result: tokens with the second each began at.
/// A token that opens with a space or SentencePiece's `▁` opens a word.
/// A word ends where its last token's `durations` entry says, when the
/// model reports durations; otherwise a beat after its last token began,
/// never past the next word's start or the end of the recording.
fun wordsFromTokens(tokens: Array<String>, starts: FloatArray, durations: FloatArray?, recordingMs: Int): List<TimedWord> {
    data class Open(val text: StringBuilder, val startMs: Int, var lastMs: Int, var endMs: Int?)
    val open = mutableListOf<Open>()
    for (index in tokens.indices) {
        if (index >= starts.size) break
        val token = tokens[index].replace('▁', ' ')
        val atMs = (starts[index] * 1000).toInt().coerceAtLeast(0)
        val endMs = durations?.getOrNull(index)?.takeIf { it > 0f }?.let { atMs + (it * 1000).toInt() }
        if (open.isEmpty() || token.startsWith(" ")) {
            open += Open(StringBuilder(token.trim()), atMs, atMs, endMs)
        } else {
            open.last().apply {
                text.append(token)
                lastMs = atMs
                if (endMs != null) this.endMs = endMs
            }
        }
    }
    return open.mapIndexedNotNull { index, word ->
        val text = word.text.toString().trim()
        if (text.isEmpty()) return@mapIndexedNotNull null
        val next = open.getOrNull(index + 1)?.startMs ?: recordingMs
        val end = (word.endMs ?: (word.lastMs + WORD_TAIL_MS)).coerceAtMost(next).coerceAtMost(recordingMs)
        TimedWord(text, word.startMs.toUInt(), maxOf(end, word.startMs).toUInt())
    }
}

/// How long a word is taken to last after its last token began, for a
/// model that does not say.
private const val WORD_TAIL_MS = 300
