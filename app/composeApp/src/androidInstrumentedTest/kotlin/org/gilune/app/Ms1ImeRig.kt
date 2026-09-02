// MS1's rig: the honest route to a *real* `InputConnection`, and the
// ownership of the input session that makes driving it deterministic.
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
//
// WHO ELSE IS ON THE CONNECTION. The rig's first version assumed it was the
// only actor. It is not: the emulator image carries Google LatinIME, it binds
// to the field the moment the field takes focus, and on the LEGACY Compose
// field it calls `finishComposingText` of its own accord 150–860 ms after
// every edit (measured; see `spikes/ms1-ime/README.md`). `waitForIdle()`
// waits only for this process and returns 50–150 ms in, so every assertion
// about a composing region was a race against another process — which is why
// the suite failed in both directions with different members each run.
//
// The rig therefore OWNS the input session. [Ms1InputSessionRule] disables
// the device's input methods for the duration of a row and restores them
// afterwards, so the only actor on the connection is the test. The rows that
// exist to measure the keyboard's own behaviour opt back in with
// [WithSystemKeyboard], and they synchronise on the interjection as an event
// rather than racing it.

package org.gilune.app

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

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

/// Marks a row that must run with the device's own keyboard BOUND, because
/// what the row measures IS the keyboard's behaviour. Every other row runs
/// with the input session owned exclusively by the test.
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class WithSystemKeyboard

/// Gives each row the input session it needs and always hands the device
/// back the way it found it.
///
/// Ordering matters: this must be the OUTER rule, so the input methods are
/// already in the right state before the Compose rule launches an activity
/// and a field takes focus. Every MS1 class wires it with a `RuleChain`
/// rather than a bare `@Rule`, because plain `@Rule` ordering is undefined.
class Ms1InputSessionRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val asFound = Ms1Ime.asFound()
                if (description.getAnnotation(WithSystemKeyboard::class.java) != null) {
                    // Self-healing: if an earlier crash left the device with
                    // no keyboard, put the real one back before measuring it.
                    Ms1Ime.restore(asFound)
                    base.evaluate()
                    return
                }
                Ms1Ime.disableAll(asFound)
                try {
                    base.evaluate()
                } finally {
                    // Runs on failure too. A device left with no input method
                    // would break every later suite on the same emulator.
                    Ms1Ime.restore(asFound)
                }
            }
        }
}

/// The device's input methods, driven through the instrumentation's own
/// shell. `ime enable/disable/set` is the same command the CLI offers; it
/// runs with the shell UID, which is what `UiAutomation` provides.
internal object Ms1Ime {

    /// What the device looked like before MS1 touched it, captured once.
    /// The shutdown hook is belt and braces for a hard kill mid-row.
    private var captured: State? = null

    data class State(val enabled: List<String>, val default: String)

    @Synchronized
    fun asFound(): State = captured ?: capture().also {
        captured = it
        Runtime.getRuntime().addShutdownHook(Thread { restore(it) })
    }

    private fun capture(): State = State(
        enabled = shell("ime list -s").lines().map { it.trim() }.filter { it.isNotEmpty() },
        default = shell("settings get secure default_input_method").trim(),
    )

    fun disableAll(state: State) {
        for (ime in state.enabled) shell("ime disable $ime")
    }

    fun restore(state: State) {
        for (ime in state.enabled) shell("ime enable $ime")
        if (state.default.isNotEmpty() && state.default != "null") shell("ime set ${state.default}")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }
}

/// Waits for an observable condition, and returns the moment it holds.
///
/// This is not a sleep. It is the rig's answer to a second process acting on
/// the same connection: where a fixed delay would encode one machine's
/// timings, this returns as soon as the event has actually happened and only
/// gives up at the bound — so a loaded emulator waits longer and a fast one
/// waits barely at all. A row that depends on the event asserts the return
/// value, so a platform that stops producing it fails the row rather than
/// passing it quietly.
internal fun awaitCondition(timeoutMs: Long, pollMs: Long = 10, condition: () -> Boolean): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(pollMs)
    }
    return condition()
}

/// Watches an observable for `windowMs` without touching it, and returns
/// every distinct value somebody ELSE wrote. The negative assertion — that
/// nothing changed — is only as strong as the window, so callers state the
/// window they proved.
internal fun observeFor(windowMs: Long, pollMs: Long = 10, snapshot: () -> String): List<String> {
    val seen = mutableListOf<String>()
    var last = snapshot()
    val deadline = SystemClock.uptimeMillis() + windowMs
    while (SystemClock.uptimeMillis() < deadline) {
        val now = snapshot()
        if (now != last) {
            seen += now
            last = now
        }
        Thread.sleep(pollMs)
    }
    return seen
}
