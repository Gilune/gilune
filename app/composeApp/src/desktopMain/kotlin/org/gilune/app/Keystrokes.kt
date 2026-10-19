// How the desktop spells a keystroke a plugin can be bound to (owner,
// 2026-10-19: go to definition is F12 by default and the plugin's to
// change). One spelling for capturing a binding and for matching one, so a
// key recorded in settings is exactly the key that fires it: modifiers in a
// fixed order, then the key's own name — `F12`, `Ctrl+Alt+G`, `Shift+F12`.

package org.gilune.app

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import java.awt.event.KeyEvent as AwtKeyEvent

private val MODIFIER_CODES = setOf(
    AwtKeyEvent.VK_SHIFT, AwtKeyEvent.VK_CONTROL, AwtKeyEvent.VK_ALT, AwtKeyEvent.VK_META,
    AwtKeyEvent.VK_ALT_GRAPH, AwtKeyEvent.VK_WINDOWS,
)

/// The keystroke an event is, or null for a lone modifier (still being
/// held on the way to a chord).
fun keystrokeOf(event: KeyEvent): String? =
    keystroke(event.key.nativeKeyCode, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)

internal fun keystroke(code: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): String? {
    if (code in MODIFIER_CODES || code == AwtKeyEvent.VK_UNDEFINED) return null
    return buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (meta) add("Meta")
        add(AwtKeyEvent.getKeyText(code))
    }.joinToString("+")
}

/// Whether a binding as settings hold it names this keystroke. Case and
/// spaces around the `+` are forgiven, so a hand-edited `ctrl + e` works.
fun keystrokeMatches(binding: String, pressed: String): Boolean =
    binding.split('+').joinToString("+") { it.trim() }.equals(pressed, ignoreCase = true)
