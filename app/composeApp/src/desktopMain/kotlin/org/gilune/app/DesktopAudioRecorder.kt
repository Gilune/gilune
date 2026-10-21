// Desktop microphone adapter over javax.sound. 16 kHz mono 16-bit PCM —
// the transcription-friendly baseline.

package org.gilune.app

import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

class DesktopAudioRecorder : AudioRecorder {
    private val format = AudioFormat(16_000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    private val buffer = ByteArrayOutputStream()

    @Volatile
    private var listener: ((ByteArray) -> Unit)? = null

    override fun listen(listener: ((ByteArray) -> Unit)?) {
        this.listener = listener
    }

    override fun start() {
        check(line == null) { "already recording" }
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val opened = AudioSystem.getLine(info) as TargetDataLine
        opened.open(format)
        opened.start()
        buffer.reset()
        line = opened
        worker = Thread {
            val chunk = ByteArray(4096)
            while (opened.isOpen) {
                val read = opened.read(chunk, 0, chunk.size)
                if (read > 0) {
                    // The recording first; the tee after, and never at its cost.
                    synchronized(buffer) { buffer.write(chunk, 0, read) }
                    listener?.let { tee -> runCatching { tee(chunk.copyOf(read)) } }
                }
                if (read < 0) break
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun stop(): RecordedAudio {
        val opened = checkNotNull(line) { "not recording" }
        opened.stop()
        opened.close()
        worker?.join(1_000)
        line = null
        worker = null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        return RecordedAudio(pcm, 16_000, 1, 16)
    }
}
