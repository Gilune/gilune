// The seam between the entry points that are not the app — the Record
// widget, its notification, the Quick Settings tile — and the shell
// (ADR 0017 § 4).
//
// Everything here is pure or nearly so, because the interesting half is a
// decision: *what should be on screen because of this tap*. That decision
// is `launchFor`, and it is tested without an Activity.

package org.gilune.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/// Where the vault lives on this device: app-private internal storage
/// (ADR 0017 § 5). One function so the activity, the widget and the
/// recording service can never disagree about the path.
fun vaultDir(context: Context): String =
    context.filesDir.resolve("vault").absolutePath

fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/// What a recording needs granted before it can start: the microphone,
/// and from Android 13 the right to show the notification the foreground
/// service is required to carry. Asking for both at once is honest — the
/// notification is not optional decoration, it is how the recording is
/// stopped and how the note is reached (§ 4).
fun recordingPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

/// What an outside tap asks the shell to show.
object Routes {
    const val CAPTURE = "capture"
    const val NOTES = "notes"
    const val SEARCH = "search"

    /// The widget's Record cell when the microphone has not been granted
    /// yet. A widget cannot ask for a permission, so it hands the ask to
    /// the app — and the app starts the recording once the answer is yes.
    const val RECORD = "record"
}

const val EXTRA_ROUTE = "org.gilune.app.extra.ROUTE"
const val EXTRA_OPEN_NOTE = "org.gilune.app.extra.OPEN_NOTE"

/// Who asked for a recording. The service refuses anything that is not
/// one of these two, which is the mechanical half of § 4's "anything
/// background-initiated is refused": there is no third value, no boot
/// receiver and no scheduler that could supply one.
const val EXTRA_SOURCE = "org.gilune.app.extra.SOURCE"
const val SOURCE_WIDGET = "widget"
const val SOURCE_APP = "app"

/// What the shell shows because of one incoming intent.
data class Launch(
    val tab: Destination,
    val focusComposer: Boolean = false,
    val openNote: String? = null,
    val askForMic: Boolean = false,
)

/// The same [Launch] can arrive twice — two taps on New note are two
/// requests to focus the composer — so a request carries a nonce and the
/// shell reacts to the request, not to the state it describes.
data class LaunchRequest(val nonce: Int, val launch: Launch)

/// Pure: intent extras in, screen out.
///
/// **Search lands on Notes today.** The Search destination arrives with
/// MB3 (ADR 0017 § 8); until it exists the cell goes to the nearest true
/// thing — the list of notes — rather than to a tab that would have to
/// apologise for itself.
fun launchFor(route: String?, openNote: String? = null): Launch = when {
    !openNote.isNullOrBlank() -> Launch(Destination.Capture, openNote = openNote)
    route == Routes.RECORD -> Launch(Destination.Capture, askForMic = true)
    route == Routes.CAPTURE -> Launch(Destination.Capture, focusComposer = true)
    route == Routes.NOTES -> Launch(Destination.Notes)
    route == Routes.SEARCH -> Launch(Destination.Notes)
    else -> Launch(Destination.Capture)
}

/// The same decision, taken off an intent. A distinct name rather than an
/// overload: `launchFor(null)` should mean "a plain launcher tap", and
/// with two overloads it means nothing decidable.
fun launchFrom(intent: Intent?): Launch =
    launchFor(intent?.getStringExtra(EXTRA_ROUTE), intent?.getStringExtra(EXTRA_OPEN_NOTE))

/// An intent that opens the shell somewhere. `SINGLE_TOP` beside the
/// activity's `singleTop` launch mode so a widget tap reaches the running
/// app through `onNewIntent` rather than stacking a second copy of it.
fun shellIntent(context: Context, route: String? = null, openNote: String? = null): Intent =
    Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        route?.let { putExtra(EXTRA_ROUTE, it) }
        openNote?.let { putExtra(EXTRA_OPEN_NOTE, it) }
    }
