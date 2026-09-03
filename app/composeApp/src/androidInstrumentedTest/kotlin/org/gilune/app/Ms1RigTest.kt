// The rig's own guard rail (MS1).
//
// **The failure this closes is inherited state.** `Ms1InputSessionRule`
// disables every input method for the duration of a row and restores them
// in a `finally` plus a JVM shutdown hook. A hard kill outruns both — a
// killed gradle, a force-stopped instrumentation, an emulator yanked
// mid-row — and leaves the device with no keyboard at all. The next run
// then captured *that* as "as found": it restored nothing at the end, and
// every `@WithSystemKeyboard` row went on to measure the behaviour of a
// keyboard that was not bound. Silently, because an empty list is not an
// error to `ime disable`.
//
// The fix is in `Ms1Ime.capture`, which treats an empty list as damage from
// a previous run and puts the platform's own keyboard back before recording
// anything. What can be asserted without breaking a device is the choice it
// makes, so the choice is a pure function.
//
// These rows deliberately carry **no** `Ms1InputSessionRule`: they touch no
// input session, and wiring one would make a test about the rig depend on
// the rig.

package org.gilune.app

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Ms1RigTest {

    private val latin = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
    private val voice = "com.google.android.googlequicksearchbox/com.google.android.voicesearch.ime.VoiceInputMethodService"

    @Test
    fun theEmulatorsOwnKeyboardIsTheOnePutBack() {
        // MS1's findings are about *that* keyboard's unprompted
        // `finishComposingText`, so restoring anything else would change
        // the subject of every `@WithSystemKeyboard` row.
        assertEquals(latin, Ms1Ime.preferredDefaultIme(listOf(voice, latin)))
        assertEquals(latin, Ms1Ime.preferredDefaultIme(listOf(latin, voice)))
    }

    @Test
    fun anyKeyboardBeatsNoKeyboardWhenTheKnownOneIsAbsent() {
        // A keyboard whose behaviour is unmeasured is still better than
        // none — the rows that care say which one they measured.
        assertEquals(voice, Ms1Ime.preferredDefaultIme(listOf(voice)))
    }

    @Test
    fun nothingToRestoreIsAnswerableRatherThanGuessed() {
        // `capture` turns this into a loud failure with an explanation,
        // which is the only honest outcome: MS1 on a device with no IME
        // would produce green rows that mean nothing.
        assertNull(Ms1Ime.preferredDefaultIme(emptyList()))
        assertNull(Ms1Ime.preferredDefaultIme(listOf("", "   ")))
    }

    @Test
    fun theMatchIsOnTheIdRatherThanOnItsCase() {
        assertEquals(
            "com.example/.LATINKeyboard",
            Ms1Ime.preferredDefaultIme(listOf("com.other/.Ime", "com.example/.LATINKeyboard")),
        )
    }
}
