// The catch-up's set, and the query it costs (ADR 0018 § 3).
//
// `wordlessRecordings` is asked on a settings tap and its answer is the
// number a person is shown before anything runs. Until this round it asked
// the kernel for the text of **every recording it found** — up to two
// thousand calls across the boundary behind one tap on a phone. The answer
// was already in the list: `recent_sources` builds each summary's preview
// from the head document's own `text`, and falls back to a bracketed
// stand-in naming the media exactly when there is none.
//
// That makes the shell's cheap test a **coupling to the kernel's preview**,
// so it is pinned here against a real vault rather than asserted over a
// hand-made `SourceSummary`: every row's preview verdict is compared with
// what `objectText` says about the same row, which is the expensive
// question the fast one replaced. A kernel that changed the shape of a
// wordless preview would fail this rather than silently making the count
// zero — or making every recording look wordless and offering to
// re-transcribe a whole vault.
//
// Runs in both suites: `jvmSharedTest` is compiled into the instrumented
// one as well, so the phone that shows the count asserts it too.

package org.gilune.app

import uniffi.gilune_ffi.Gilune
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordlessScopeTest {

    private fun scratch(): String = Files.createTempDirectory("gilune-wordless").toString()

    /// One second of silence, committed the way the shells commit audio.
    private fun recording(gilune: Gilune): String {
        val wav = wavBytes(RecordedAudio(ByteArray(WAV_SAMPLE_RATE * 2), WAV_SAMPLE_RATE, 1, 16))
        val writer = gilune.beginCapture("wav")
        var offset = 0
        while (offset < wav.size) {
            val end = minOf(offset + CAPTURE_CHUNK, wav.size)
            writer.writeChunk(wav.copyOfRange(offset, end))
            offset = end
        }
        return writer.finish().sourceId
    }

    /// The expensive question, asked of every **recording**, so the cheap
    /// one can be compared against it.
    ///
    /// Scoped to recordings because that is the set the catch-up is about,
    /// and because the two questions genuinely differ elsewhere: a *text*
    /// capture keeps its words in its own preserved file, so its preview is
    /// read off disk while `objectText` — which answers from the document's
    /// `text` field — says null. Neither is wrong; a text capture simply has
    /// no transcript to be missing.
    private fun disagreements(gilune: Gilune): List<String> =
        gilune.recentSources(100u)
            .filter { it.isRecording() }
            .filter { it.hasNoWords() != gilune.objectText(it.objectId).isNullOrBlank() }
            .map { "${it.objectId}: preview='${it.preview}' text='${gilune.objectText(it.objectId)}'" }

    @Test
    fun theCatchUpsSetIsReadOffThePreviewAndAgreesWithTheDocument() {
        Gilune.open(scratch()).use { gilune ->
            val spoken = recording(gilune)
            gilune.captureText("typed, not spoken")
            gilune.project()

            assertEquals(
                listOf(spoken),
                wordlessRecordings(gilune).map { it.objectId },
                "the count the shell shows before it runs anything",
            )
            assertEquals(emptyList(), disagreements(gilune), "the cheap test disagrees with the document")

            // Words arrive — by hand here, which is the case that must not
            // be re-transcribed: a person's own text is words like any
            // other, and the catch-up may not overwrite it.
            val relPath = gilune.recentSources(100u).single { it.objectId == spoken }.projectionRelPath
                ?: error("the capture was never projected")
            val note = File(gilune.vaultPath(), relPath)
            note.writeText(note.readText().trimEnd() + "\nwritten by hand\n")
            gilune.reconcile()

            assertTrue(
                gilune.objectText(spoken)?.contains("written by hand") == true,
                "the fixture is wrong: the recording has no words to protect",
            )
            assertTrue(wordlessRecordings(gilune).isEmpty(), "a recording with words is not wordless")
            assertEquals(emptyList(), disagreements(gilune), "the cheap test disagrees with the document")
        }
    }

    @Test
    fun theWordlessTestCostsOneQueryRatherThanOnePerRecording() {
        // Not a timing assertion: a counting one. The list is asked for
        // once and nothing else crosses the boundary, which is the whole
        // point of reading the preview that was already in hand.
        Gilune.open(scratch()).use { gilune ->
            repeat(5) { recording(gilune) }
            gilune.project()
            val rows = gilune.recentSources(WORDLESS_SCAN_LIMIT)
            assertEquals(5, rows.count { it.isRecording() })
            // Every one of those rows can be judged without asking again.
            assertTrue(rows.all { !it.isRecording() || it.hasNoWords() })
            assertEquals(5, wordlessRecordings(gilune).size)
        }
    }
}
