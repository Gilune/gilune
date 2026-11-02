// Whisper's engines against the shell's whisper catalogue (ADR 0033 § 2).
//
// The whisper plugin's declaration is the kernel's to make, so its
// `transcription.engines` entries are written in Rust (`whisper.rs`), while
// the files those engines need are downloaded by the shell from
// `WHISPER_MODELS`. An engine the catalogue cannot download would be a row
// on Settings ▸ Transcription that can never become Ready, and a catalogue
// model with no engine could never be chosen. Test builds carry no
// whisper, so the declaration cannot be read back from a kernel here; the
// source is read instead, as `AlertKindCouplingTest` reads alerts.rs.
// Desktop-only for that test's reason: the repository is not on a phone.

package org.gilune.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhisperEnginesCouplingTest {
    private fun whisperSource(): File {
        var here: File? = File(System.getProperty("user.dir")).absoluteFile
        while (here != null) {
            val candidate = File(here, "kernel/crates/gilune-ffi/src/whisper.rs")
            if (candidate.isFile) return candidate
            here = here.parentFile
        }
        error(
            "whisper.rs was not found above ${System.getProperty("user.dir")} — this test must fail " +
                "loudly rather than skip, or the coupling it exists for is unchecked",
        )
    }

    @Test
    fun whisperPublishesAnEngineForEveryCatalogueModelInTheSameOrder() {
        val source = whisperSource().readText()
        val start = source.indexOf("const ENGINES:")
        assertTrue(start >= 0, "whisper.rs has no ENGINES list — the test has gone stale")
        val block = source.substring(start, source.indexOf("];", start))
        // Each entry opens `(` then the key as its first string.
        val keys = Regex("""\(\s*"([^"]+)"\s*,""").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(WHISPER_MODELS.map { it.key }, keys)
    }

    @Test
    fun whisperPublishesItsEnginesInTheSlotTheShellReads() {
        val source = whisperSource().readText()
        assertTrue(
            "const ENGINES_SLOT: &str = \"${org.gilune.plugin.sdk.Slots.TRANSCRIPTION_ENGINES}\";" in source,
            "whisper.rs names a different slot from Slots.TRANSCRIPTION_ENGINES",
        )
        // The step past the key: the config spells the role and engine
        // fields as the shell parses them.
        for (field in listOf("\"engine\"", "\"roles\"", "\"wordTimes\"", "\"download\"", "\"guidance\"")) {
            assertTrue(field in source, "whisper.rs no longer writes $field")
        }
        assertTrue("[\"live\", \"recordings\"]" in source, "whisper's engines name both roles as the shell spells them")
    }
}
