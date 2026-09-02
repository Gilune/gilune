// Android microphone adapter over AudioRecord. 16 kHz mono 16-bit PCM —
// matches the desktop adapter so captures are uniform across devices.

package org.gilune.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class AndroidAudioRecorder : AudioRecorder {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val buffer = ByteArrayOutputStream()

    /// The live caption's tee (ADR 0018 § 1: "a tee off that buffer, not a
    /// second microphone"). Set while a caption session is running, null
    /// otherwise, and read on the recording thread.
    ///
    /// **The recording does not depend on it and cannot be harmed by it.**
    /// The bytes reach [buffer] first, the call is wrapped so a throw
    /// cannot break the read loop, and the tee's own contract is to return
    /// immediately — `MoonshineSession.feed` converts and hands off to a
    /// bounded queue, because the transcriber decodes on its caller's
    /// thread and that must never be this one.
    @Volatile
    var tee: ((ByteArray, Int) -> Unit)? = null

    // The caller gates on RECORD_AUDIO being granted (MainActivity).
    @SuppressLint("MissingPermission")
    override fun start() {
        check(record == null) { "already recording" }
        val minBuffer = AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val opened = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, 8192),
        )
        check(opened.state == AudioRecord.STATE_INITIALIZED) { "microphone init failed" }
        buffer.reset()
        opened.startRecording()
        record = opened
        worker = Thread {
            val chunk = ByteArray(4096)
            while (record === opened) {
                val read = opened.read(chunk, 0, chunk.size)
                if (read > 0) {
                    // The capture first, always. The caption sees the
                    // bytes only after they are safely in the buffer the
                    // recording will be made from, and a tee that throws
                    // loses the caption, never the recording.
                    synchronized(buffer) { buffer.write(chunk, 0, read) }
                    runCatching { tee?.invoke(chunk, read) }
                }
                if (read < 0) break
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun stop(): RecordedAudio {
        val opened = checkNotNull(record) { "not recording" }
        record = null
        worker?.join(1_000)
        worker = null
        opened.stop()
        opened.release()
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        return RecordedAudio(pcm, 16_000, 1, 16)
    }
}
