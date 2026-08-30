package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals

class WavTest {
    @Test
    fun wavHeaderIsCanonicalPcm() {
        val pcm = ByteArray(1000) { 7 }
        val wav = wavBytes(RecordedAudio(pcm, 16_000, 1, 16))
        assertEquals(44 + 1000, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        assertEquals("data", wav.copyOfRange(36, 40).decodeToString())
        fun le32(offset: Int) =
            (wav[offset].toInt() and 0xff) or
                ((wav[offset + 1].toInt() and 0xff) shl 8) or
                ((wav[offset + 2].toInt() and 0xff) shl 16) or
                ((wav[offset + 3].toInt() and 0xff) shl 24)
        assertEquals(36 + 1000, le32(4))
        assertEquals(1000, le32(40))
        assertEquals(16_000, le32(24))
        assertEquals(32_000, le32(28)) // byte rate: 16 kHz * 1ch * 16bit/8
    }
}
