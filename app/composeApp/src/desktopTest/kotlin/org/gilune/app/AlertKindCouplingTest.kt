// The shell's alert table against the kernel's own list of kinds.
//
// `SyncAlerts.kt` keeps a copy of every kind the kernel can raise, because
// the FFI carries a kind as a **string** — there is no enum to exhaust, and
// the wording table is a `when` over those strings with a fallback. That
// fallback is deliberately visible and deliberately vague, which makes the
// failure mode quiet: a kind the kernel adds and this table has never heard
// of does not crash and does not blank the screen, it just says less than
// it could, to somebody who needed it to say more.
//
// So the coupling is asserted against the source of truth by **reading it**.
// This is a desktop-only test on purpose: `jvmSharedTest` is compiled into
// the instrumented suite too, where the repository is not on the device and
// the check could only skip — and a skip that looks like a pass is the same
// silence in a different place.
//
// It reads `kernel/crates/gilune-kernel/src/alerts.rs`, which is outside
// this agent's own files and is the point: the shell follows the kernel, so
// the kernel's file is the fixture. If the kernel ever exports the kinds
// over the FFI as an enum, delete this and exhaust the enum instead.

package org.gilune.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertKindCouplingTest {

    /// `pub const NAME: &str = "kind";` — the only shape `alerts.rs` uses
    /// to declare one.
    private val declaration = Regex("""pub const [A-Z_]+: &str = "([a-z0-9-]+)";""")

    private fun alertsSource(): File {
        var here: File? = File(System.getProperty("user.dir")).absoluteFile
        while (here != null) {
            val candidate = File(here, "kernel/crates/gilune-kernel/src/alerts.rs")
            if (candidate.isFile) return candidate
            here = here.parentFile
        }
        error(
            "alerts.rs was not found above ${System.getProperty("user.dir")} — this test " +
                "must fail loudly rather than skip, or the coupling it exists for is unchecked",
        )
    }

    @Test
    fun everyKindTheKernelCanRaiseHasWordsInThisBuild() {
        val kinds = declaration.findAll(alertsSource().readText()).map { it.groupValues[1] }.toList()
        assertTrue(kinds.size >= 9, "only ${kinds.size} kinds parsed — the regex has gone stale")

        val missing = kinds - ALERT_KINDS.toSet()
        assertEquals(
            emptyList(),
            missing,
            "the kernel raises these and `SyncAlerts.kt` has never heard of them, so a " +
                "person meeting one gets the vague fallback instead of a sentence",
        )

        // And nothing here is invented: a kind in the table that the kernel
        // cannot raise is dead wording that will never be seen and will
        // never be corrected.
        assertEquals(
            emptyList(),
            ALERT_KINDS - kinds.toSet(),
            "these are worded here but no longer exist in the kernel",
        )
    }

    @Test
    fun everyKernelKindGetsItsOwnSentenceRatherThanTheFallback() {
        val kinds = declaration.findAll(alertsSource().readText()).map { it.groupValues[1] }.toList()
        // The fallback's opening words. Any kind that produces them has no
        // wording of its own, whatever the list above says.
        val fallbackOpening = "A change from another device was set aside by a part of Gilune newer"
        for (kind in kinds) {
            val said = alertSentence(kind, "subject", "detail", DESKTOP_ALERT_WORDS)
            assertTrue(
                !said.startsWith(fallbackOpening),
                "$kind falls through to the unknown-kind sentence",
            )
        }
    }
}
