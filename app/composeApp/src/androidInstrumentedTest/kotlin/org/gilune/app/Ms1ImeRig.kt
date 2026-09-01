// MS1's rig: the honest route to a *real* `InputConnection`.
//
// An IME never touches a Compose text field directly. The framework asks the
// focused host View for a connection — `View.onCreateInputConnection` — and
// the keyboard then speaks only through that object. Compose's host view
// answers it out of whichever text-input session is live, so the connection
// obtained here is the same one Gboard would hold: `setComposingText`,
// `setComposingRegion`, `commitText`, `finishComposingText`,
// `deleteSurroundingText` and `setSelection` all land on the field's real
// edit path, composing regions and all.
//
// This is deliberately not `performTextInput`. That helper drives the
// semantics `InsertTextAtCursor` action, which never opens a composing
// region — so it cannot exercise the one thing ADR 0017 § 8 asks about.

package org.gilune.app

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/// The decor view of the activity the Compose rule launched into. Must be
/// called on the UI thread — the lifecycle monitor asserts it.
internal fun resumedDecorView(): View =
    ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED)
        .firstOrNull()
        ?.window
        ?.decorView
        ?: error("no resumed activity")

/// The first connection any view in the tree will hand out, and `info`
/// filled in exactly as the platform would fill it for a keyboard. A view
/// with no text-input session answers null, so this finds the focused field
/// without needing to name Compose's internal host class.
internal fun imeConnection(view: View, info: EditorInfo): InputConnection? {
    view.onCreateInputConnection(info)?.let { return it }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            imeConnection(view.getChildAt(index), info)?.let { return it }
        }
    }
    return null
}
