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
    private fun plugin(
        id: String,
        state: String,
        ungranted: List<String> = emptyList(),
        granted: List<String> = emptyList(),
    ) = PluginInfo(
        pluginId = id,
        name = id,
        `class` = "provider",
        profile = "bundled",
        required = false,
        state = state,
        processorId = null,
        subscribes = null,
        emits = emptyList(),
        capabilities = granted + ungranted,
        granted = granted,
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
        assertEquals(Transcription.Absent, transcriptionState("", none, TranscriptionPhase.Ready))
        assertEquals(
            Transcription.Absent,
            transcriptionState("", off, TranscriptionPhase.Ready),
            "an off plugin will never transcribe it",
        )
        assertEquals(Transcription.Pending, transcriptionState("", on, TranscriptionPhase.Ready))
        assertEquals(
            Transcription.Done,
            transcriptionState("the words", none, TranscriptionPhase.Absent),
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
        assertEquals(Transcription.Absent, transcriptionState("", awaitingConsent, TranscriptionPhase.Absent))
        assertEquals(
            "recorded · 0:42 · needs the transcription model",
            voiceStatus(42_000, transcriptionState("", awaitingConsent, TranscriptionPhase.Absent)),
        )
    }

    @Test
    fun consentedIsNotTheSameAsAbleEither() {
        // The gate one level deeper (ADR 0017 § 4). Between the yes and a
        // loaded model there are tens of megabytes, and for all of it the
        // plugin is enabled AND granted AND nothing can run. Only
        // `enableWhisper` returning earns "transcribing…".
        val granted = listOf(
            plugin(TRANSCRIPTION_PLUGIN, "enabled", granted = listOf(NETWORK_CAPABILITY)),
        )
        assertEquals(
            Transcription.Absent,
            transcriptionState("", granted, TranscriptionPhase.Unknown),
            "a granted plugin with no model loaded is not transcribing anything",
        )
        assertEquals(
            Transcription.Downloading,
            transcriptionState("", granted, TranscriptionPhase.Downloading),
        )
        assertEquals(
            Transcription.Absent,
            transcriptionState("", granted, TranscriptionPhase.Failed),
            "a failed load must not read as work in progress",
        )
        assertEquals(Transcription.Pending, transcriptionState("", granted, TranscriptionPhase.Ready))
    }

    @Test
    fun aDownloadSaysSoRatherThanClaimingToNeedWhatIsArriving() {
        assertEquals(
            "recorded · 0:42 · downloading the transcription model…",
            voiceStatus(42_000, Transcription.Downloading),
        )
    }

    @Test
    fun aWhitespaceOnlyPreviewIsNotATranscript() {
        assertEquals(Transcription.Absent, transcriptionState("   \n ", emptyList(), TranscriptionPhase.Ready))
    }

    // ------------------------------------------------------------------
    // A recording with no speech in it (ADR 0017 amendment 2026-09-04)
    //
    // Found on the owner's phone: a recording of silence was transcribed
    // in six seconds, the job finished, the body stayed empty, and the row
    // said "transcribing…" for ever. An empty body was being read as *not
    // yet*, which is a guess; whether anything is coming is a fact the
    // kernel records (`SourceSummary.interpreted`).
    // ------------------------------------------------------------------

    @Test
    fun aFinishedTranscriptionWithNoWordsSaysNoSpeechHeard() {
        val on = listOf(plugin(TRANSCRIPTION_PLUGIN, "enabled"))
        assertEquals(Transcription.Silent, transcriptionState("", on, TranscriptionPhase.Ready, interpreted = true))
        assertEquals(
            "recorded · 0:01 · no speech heard",
            voiceStatus(1_000, Transcription.Silent),
        )
    }

    /// **Second-order.** The same empty body, the same enabled plugin, the
    /// same loaded model — and the job has *not* run yet. That one is
    /// still "transcribing…", and reading it as silence would be the new
    /// wording lying in the direction the old one did.
    @Test
    fun aRecordingWhoseJobHasNotRunYetIsStillTranscribing() {
        val on = listOf(plugin(TRANSCRIPTION_PLUGIN, "enabled"))
        assertEquals(Transcription.Pending, transcriptionState("", on, TranscriptionPhase.Ready, interpreted = false))
    }

    /// The other two second-order cases. Words already landed are words,
    /// whatever else is true; and a recording made while the transcriber
    /// was off has had nothing over it, so it keeps § 4's "needs" wording
    /// rather than being called silent.
    @Test
    fun silenceIsNotClaimedForWordsOrForWorkThatNeverRan() {
        val on = listOf(plugin(TRANSCRIPTION_PLUGIN, "enabled"))
        val off = listOf(plugin(TRANSCRIPTION_PLUGIN, "disabled"))
        assertEquals(
            Transcription.Done,
            transcriptionState("the words", on, TranscriptionPhase.Ready, interpreted = true),
        )
        assertEquals(
            Transcription.Absent,
            transcriptionState("", off, TranscriptionPhase.Ready, interpreted = false),
            "nothing has been over it, so it still needs a transcriber",
        )
    }

    /// Work already done is not undone by switching the transcriber off
    /// afterwards: the row must not go back to asking for a model it does
    /// not need.
    @Test
    fun aSilentRecordingStaysSilentWhenTheTranscriberIsSwitchedOff() {
        val off = listOf(plugin(TRANSCRIPTION_PLUGIN, "disabled"))
        assertEquals(
            Transcription.Silent,
            transcriptionState("", off, TranscriptionPhase.Absent, interpreted = true),
        )
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
