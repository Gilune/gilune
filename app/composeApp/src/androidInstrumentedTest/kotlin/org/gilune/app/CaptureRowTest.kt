// What a capture row is allowed to say (ADR 0017 § 4). The pending state
// is shown and is never written to the file, so these strings are the
// whole of the promise — and they must be honest about which state the
// device is actually in.

package org.gilune.app

import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureRowTest {
    private fun plugin(id: String, state: String, ungranted: List<String> = emptyList()) = PluginInfo(
        pluginId = id,
        name = id,
        `class` = "provider",
        profile = "bundled",
        required = false,
        state = state,
        processorId = null,
        subscribes = null,
        emits = emptyList(),
        capabilities = emptyList(),
        granted = emptyList(),
        ungranted = ungranted,
        contributions = emptyList<PluginContribution>(),
        settings = "",
    )

    @Test
    fun onlyAudioIsARecording() {
        // Every source has a blob — a typed thought's bytes are one too —
        // so a text capture used to draw a play button and offer to
        // transcribe itself (found by eye on the emulator, 2026-09-01).
        assertTrue(isRecording("Captures/capture-01a054cc.wav"))
        assertTrue(isRecording("Captures/CAPTURE.WAV"), "an extension is not case")
        assertTrue(!isRecording("Captures/capture-01a054cc.md"), "a typed thought is not a recording")
        assertTrue(!isRecording("Captures/notes.txt"))
        assertTrue(!isRecording(null))
    }

    @Test
    fun beforeThereIsATranscriberTheRowSaysSo() {
        assertEquals(
            "recorded · 0:42 · needs the transcription model",
            voiceStatus(42_000, Transcription.Absent),
        )
    }

    @Test
    fun whileAJobRunsTheRowSaysThat() {
        assertEquals("recorded · 0:42 · transcribing…", voiceStatus(42_000, Transcription.Pending))
    }

    @Test
    fun onceThereAreWordsTheStatusStepsAside() {
        assertEquals("", voiceStatus(42_000, Transcription.Done), "the transcript is the row, not a status line")
    }

    @Test
    fun anUnreadableHeaderOmitsTheDurationRatherThanInventingOne() {
        assertEquals("recorded · needs the transcription model", voiceStatus(null, Transcription.Absent))
    }

    @Test
    fun theStateIsReadOffTheKernelNotGuessed() {
        val none = emptyList<PluginInfo>()
        val off = listOf(plugin(TRANSCRIPTION_PLUGIN, "disabled"))
        val on = listOf(plugin(TRANSCRIPTION_PLUGIN, "enabled"))
        assertEquals(Transcription.Absent, transcriptionState("", none))
        assertEquals(Transcription.Absent, transcriptionState("", off), "an off plugin will never transcribe it")
        assertEquals(Transcription.Pending, transcriptionState("", on))
        assertEquals(
            Transcription.Done,
            transcriptionState("the words", none),
            "words already landed are words, whatever the plugin does now",
        )
    }

    @Test
    fun enabledIsNotTheSameAsAble() {
        // The bundled transcriber is switched on out of the box, but its
        // model download waits on `network.request`. Found by eye on the
        // emulator (2026-09-01): the row claimed "transcribing…" when
        // nothing could possibly transcribe.
        val awaitingConsent = listOf(plugin(TRANSCRIPTION_PLUGIN, "enabled", listOf(NETWORK_CAPABILITY)))
        assertEquals(Transcription.Absent, transcriptionState("", awaitingConsent))
        assertEquals(
            "recorded · 0:42 · needs the transcription model",
            voiceStatus(42_000, transcriptionState("", awaitingConsent)),
        )
    }

    @Test
    fun aWhitespaceOnlyPreviewIsNotATranscript() {
        assertEquals(Transcription.Absent, transcriptionState("   \n ", emptyList()))
    }

    @Test
    fun durationsReadAsMinutesAndSeconds() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9_400))
        assertEquals("1:02", formatDuration(62_000))
        assertEquals("10:00", formatDuration(600_000))
        assertEquals("0:00", formatDuration(-5), "a nonsense duration reads as nothing, never as a negative")
    }

    @Test
    fun aTextRowShowsItsFirstNonBlankLine() {
        assertEquals("Remember the milk", firstLine("Remember the milk\nand the bread"))
        assertEquals("second", firstLine("\n\n  second\nthird"))
        assertEquals("", firstLine("   \n\t"))
    }

    @Test
    fun aCapturedWavAnnouncesItsOwnLength() {
        // One second of the canonical capture format: 16 kHz mono 16-bit.
        val pcm = ByteArray(16_000 * 2)
        val wav = wavBytes(RecordedAudio(pcm, 16_000, 1, 16))
        assertEquals(1000L, wavDurationMs(wav))
        assertEquals("recorded · 0:01 · needs the transcription model", voiceStatus(wavDurationMs(wav), Transcription.Absent))
    }

    @Test
    fun theHeaderAloneIsEnoughToReadTheLength() {
        val wav = wavBytes(RecordedAudio(ByteArray(32_000), 16_000, 1, 16))
        assertEquals(
            1000L,
            wavDurationMs(wav.copyOf(44)),
            "the row must not read a whole recording to say how long it is",
        )
    }

    @Test
    fun anythingThatIsNotAWavAnswersNothing() {
        assertEquals(null, wavDurationMs(ByteArray(0)))
        assertEquals(null, wavDurationMs("not a wav at all".toByteArray()))
        assertEquals(null, wavDurationMs(ByteArray(64)))
    }

    @Test
    fun aTimestampIsAlwaysRenderable() {
        assertTrue(captureStamp(System.currentTimeMillis()).isNotBlank())
    }
}
