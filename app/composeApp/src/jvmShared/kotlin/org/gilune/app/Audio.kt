// Platform-neutral audio contract. Each shell supplies a recorder; the
// shared screen only ever sees PCM frames and turns them into a WAV
// capture. Capture must not depend on any platform quirk beyond "give me
// microphone bytes".

package org.gilune.app

class RecordedAudio(
    val pcm: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
)

interface AudioRecorder {
    fun start()
    fun stop(): RecordedAudio
}

/// A minimal canonical WAV container (PCM, little-endian). Pure function
/// so the byte layout is testable without a microphone.
fun wavBytes(audio: RecordedAudio): ByteArray {
    val byteRate = audio.sampleRate * audio.channels * audio.bitsPerSample / 8
    val blockAlign = audio.channels * audio.bitsPerSample / 8
    val dataSize = audio.pcm.size
    val riffSize = 36 + dataSize

    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(riffSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1) // PCM
    header.putShort(audio.channels.toShort())
    header.putInt(audio.sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort(audio.bitsPerSample.toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)

    return header.array() + audio.pcm
}
