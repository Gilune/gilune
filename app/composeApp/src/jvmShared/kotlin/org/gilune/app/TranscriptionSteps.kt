// What a transcription is doing while somebody waits for it, in the
// words the status line shows (owner ask, 2026-11-02: "transcribing with
// <model> · 42%").

package org.gilune.app

/// One step of a recording's transcription, as it happens.
sealed interface TranscriptionStep {
    /// `engine` has heard `percent` of the recording.
    data class Hearing(val engine: String, val percent: Int) : TranscriptionStep

    /// The words are in; the speaker model is telling the voices apart.
    data object LabellingSpeakers : TranscriptionStep
}

/// The status line's sentence for a step.
fun TranscriptionStep.words(): String = when (this) {
    is TranscriptionStep.Hearing -> "transcribing with $engine · ${percent.coerceIn(0, 100)}%"
    TranscriptionStep.LabellingSpeakers -> "transcribed; labelling speakers…"
}
