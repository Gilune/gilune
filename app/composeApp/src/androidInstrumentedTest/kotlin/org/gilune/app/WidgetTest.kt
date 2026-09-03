// The Record widget's decisions (ADR 0017 § 4), none of which need a
// launcher to check: where a cell lands, what the inbox count counts,
// what the mandatory notification says, and what a streamed recording's
// duration reads as.

package org.gilune.app

import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class WidgetTest {
    @Test
    fun theWidgetInflatesAndCarriesItsFourCells() {
        // A RemoteViews fails only when the launcher inflates it, so this
        // is the only place a wrong id or an unsupported view shows up
        // before someone's home screen says "problem loading widget".
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = widgetViews(context).apply(context, null)
        assertNotNull(root.findViewById<android.view.View>(R.id.widget_record), "Record")
        assertNotNull(root.findViewById<android.view.View>(R.id.widget_new_note), "New note")
        assertNotNull(root.findViewById<android.view.View>(R.id.widget_search), "Search")
        assertNotNull(root.findViewById<android.view.View>(R.id.widget_inbox), "inbox count")
        // The count is drawn from the vault, not left at the layout's
        // placeholder.
        val count = root.findViewById<TextView>(R.id.widget_inbox_count).text.toString()
        assertEquals(inboxCount(vaultDir(context)).toString(), count)
        assertEquals("Record", root.findViewById<TextView>(R.id.widget_record_label).text.toString())
    }

    private fun vaultWith(vararg relPaths: String): String {
        val root = Files.createTempDirectory("gilune-widget").toFile()
        for (rel in relPaths) {
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText("---\ngilune-id: x\n---\nbody\n")
        }
        return root.absolutePath
    }

    @Test
    fun theInboxCountIsTheNotesInCaptures() {
        val vault = vaultWith(
            "Captures/capture-a.md",
            "Captures/capture-b.md",
            // The blob beside a capture is the preserved source, not a
            // second capture.
            "Captures/capture-a.wav",
            // Filed notes have left the inbox by definition.
            "Notes/filed.md",
        )
        assertEquals(2, inboxCount(vault))
    }

    @Test
    fun anEmptyOrAbsentVaultCountsZeroRatherThanFailing() {
        // The launcher redraws the widget whenever it likes, including
        // before the app has ever been opened.
        assertEquals(0, inboxCount(vaultWith()))
        assertEquals(0, inboxCount(File(vaultWith(), "nowhere").absolutePath))
    }

    @Test
    fun dotEntriesAreKernelStateAndNeverInboxItems() {
        assertEquals(0, inboxCount(vaultWith("Captures/.hidden.md")))
    }

    @Test
    fun everyCellLandsSomewhereItCanHonour() {
        assertEquals(Destination.Capture, launchFor(Routes.CAPTURE).tab)
        assertTrue(launchFor(Routes.CAPTURE).focusComposer, "New note focuses the composer")
        assertEquals(Destination.Notes, launchFor(Routes.NOTES).tab)
        // The Search cell landed on Notes until MB3 built the destination
        // it always meant; now it goes there.
        assertEquals(Destination.Search, launchFor(Routes.SEARCH).tab)
        assertTrue(launchFor(Routes.RECORD).askForMic)
        // A plain launcher tap is home, which is capture.
        assertEquals(Destination.Capture, launchFor(null).tab)
        assertTrue(!launchFor(null).focusComposer)
    }

    @Test
    fun theRecordingNotificationOpensTheNoteItMade() {
        val launch = launchFor(route = null, openNote = "Captures/capture-9k2.md")
        assertEquals("Captures/capture-9k2.md", launch.openNote)
        // An empty extra is not a note; it must not send the shell to a
        // Read screen with nothing in it.
        assertNull(launchFor(route = null, openNote = "").openNote)
    }

    @Test
    fun theNotificationSaysWhatIsHappeningAndThenWhatHappened() {
        assertEquals("Recording 0:00", recordingNotificationText(0))
        assertEquals("Recording 1:02", recordingNotificationText(62_000))
        assertEquals("Recorded 0:42 · tap to open", recordedNotificationText(42_000))
        // A duration that could not be read is omitted, never invented.
        assertEquals("Recorded · tap to open", recordedNotificationText(null))
    }

    @Test
    fun theWidgetsNotificationIsTheOnlyPlaceAFailedTranscriptionCanBeSaid() {
        // This path has no screen and no message line. Until this round the
        // provider pass was computed and dropped where it stood, so a
        // recording whose words failed was announced exactly like one whose
        // words landed. The recording itself is never in doubt — it
        // committed before a processor was asked anything — so the sentence
        // says what is safe and what is outstanding, and never that
        // something was lost.
        assertEquals("Morning notes", recordedNotificationDetail("Morning notes", 0L))
        assertEquals("Saved to your vault.", recordedNotificationDetail(null, 0L))
        val troubled = recordedNotificationDetail("Morning notes", 1L)
        assertTrue(troubled.contains("Saved"), troubled)
        assertTrue(troubled.contains("could not be written yet"), troubled)
        assertTrue(troubled.contains("try again"), troubled)
    }

    @Test
    fun aStreamedRecordingsDurationComesOffTheFileNotTheHeader() {
        // One second of the canonical shape, streamed: the header cannot
        // know its own length, so the file's length is the answer.
        val streamed = wavStreamHeader() + ByteArray(32_000)
        assertNull(wavDurationMs(streamed.copyOf(44)), "no file length, no claim")
        assertEquals(1000L, wavDurationMs(streamed.copyOf(44), streamed.size.toLong()))
    }

    @Test
    fun aWholeRecordingIsStillReadOffItsOwnHeader() {
        val whole = wavBytes(RecordedAudio(ByteArray(32_000), 16_000, 1, 16))
        // The file length must not override a header that knows better —
        // a WAV with trailing chunks would then read long.
        assertEquals(1000L, wavDurationMs(whole, whole.size + 5_000L))
    }
}
