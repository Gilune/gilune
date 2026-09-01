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

/// The launcher's long-press shortcuts (ADR 0017 § 8, MB3): the same three
/// verbs the widget offers, for a home screen with no widget on it.
///
/// They are **actions rather than extras** because a static `shortcuts.xml`
/// has no way to carry an extra — the element does not exist — and
/// smuggling the route into the data URI would invent a second scheme for
/// one string. The intents are explicit (`targetPackage`/`targetClass`),
/// so these actions need no intent filter and open nothing but this app.
const val ACTION_SHORTCUT_RECORD = "org.gilune.app.action.SHORTCUT_RECORD"
const val ACTION_SHORTCUT_NEW_NOTE = "org.gilune.app.action.SHORTCUT_NEW_NOTE"
const val ACTION_SHORTCUT_SEARCH = "org.gilune.app.action.SHORTCUT_SEARCH"

/// Which route a shortcut's action asks for, or null for anything else —
/// including `MAIN`, which is an ordinary launcher tap and must stay one.
fun routeForAction(action: String?): String? = when (action) {
    ACTION_SHORTCUT_RECORD -> Routes.RECORD
    ACTION_SHORTCUT_NEW_NOTE -> Routes.CAPTURE
    ACTION_SHORTCUT_SEARCH -> Routes.SEARCH
    else -> null
}

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
/// **Search lands on Search** as of MB3 (ADR 0017 § 8) — the destination
/// the widget cell and the shortcut always meant now exists, so neither
/// has to settle for the notes list any more.
fun launchFor(route: String?, openNote: String? = null): Launch = when {
    !openNote.isNullOrBlank() -> Launch(Destination.Capture, openNote = openNote)
    route == Routes.RECORD -> Launch(Destination.Capture, askForMic = true)
    route == Routes.CAPTURE -> Launch(Destination.Capture, focusComposer = true)
    route == Routes.NOTES -> Launch(Destination.Notes)
    route == Routes.SEARCH -> Launch(Destination.Search)
    else -> Launch(Destination.Capture)
}

/// The same decision, taken off an intent. A distinct name rather than an
/// overload: `launchFor(null)` should mean "a plain launcher tap", and
/// with two overloads it means nothing decidable.
///
/// The extra is consulted first and the action second, so a widget cell
/// and a shortcut answering the same route agree, and an intent carrying
/// neither is the plain launcher tap it looks like.
fun launchFrom(intent: Intent?): Launch = launchFor(
    intent?.getStringExtra(EXTRA_ROUTE) ?: routeForAction(intent?.action),
    intent?.getStringExtra(EXTRA_OPEN_NOTE),
)

/// An intent that opens the shell somewhere. `SINGLE_TOP` beside the
/// activity's `singleTop` launch mode so a widget tap reaches the running
/// app through `onNewIntent` rather than stacking a second copy of it.
fun shellIntent(context: Context, route: String? = null, openNote: String? = null): Intent =
    Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        route?.let { putExtra(EXTRA_ROUTE, it) }
        openNote?.let { putExtra(EXTRA_OPEN_NOTE, it) }
    }
