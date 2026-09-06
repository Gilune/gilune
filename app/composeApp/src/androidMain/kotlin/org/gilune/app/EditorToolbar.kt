// The accessory row, the panel behind `more`, and the `/` suggester
// (ADR 0017 § 3).
//
// Three rules from the research are load-bearing here:
//
//  - **No command is reachable only by scrolling the row.** Obsidian's
//    single swipeable row hides commands users never discover, which is
//    why three separate plugins exist to add rows to it. Eight slots stay
//    in the row; everything else is in the panel at full touch-target
//    size.
//  - **The toolbar outlives the keyboard.** While a note is in Edit mode
//    the row is anchored at the bottom whether the IME is up or not — the
//    top-voted Obsidian toolbar complaint is that there is no way to hide
//    one without the other. `more` opens the panel *in place of* the
//    keyboard (Bear's shape), and the dismiss key lowers the keyboard and
//    leaves the row.
//  - **The `/` suggester is not caret-anchored on mobile.** The keyboard
//    owns the bottom half of the screen and the caret sits just above it,
//    so candidates render directly above the row and ↑/↓ are replaced by
//    tapping. The session machine is the shared one, unchanged.
//
// Every action writes through [BufferEdit] and `TextFieldState.edit`, so
// ADR 0012's invariant is held by arithmetic that is tested without a
// screen.

package org.gilune.app

import org.gilune.plugin.sdk.EditorDecoration

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/// How tall the panel stands. A keyboard's height is not knowable before
/// it is shown and changes with the IME, so the panel takes a height of
/// its own rather than guessing at one it would have to chase.
private val PanelHeight = 232.dp

/// The row, the panel and the candidate list, as one bottom-anchored
/// surface. The caller supplies the buffer and the live `/` session; this
/// composable owns nothing but its own open/closed state.
///
/// The opt-in is MS1's third answer arriving as a compiler warning:
/// `undoState` is where undo comes from on the modern API, and it is
/// `@ExperimentalFoundationApi` in Compose 1.7.x.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorToolbar(
    state: TextFieldState,
    decorations: List<EditorDecoration>,
    session: SuggestSession?,
    onSession: (SuggestSession?) -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    var panelOpen by remember { mutableStateOf(false) }

    val text = state.text.toString()
    val selection = state.selection

    fun run(edit: BufferEdit?) {
        state.applyEdit(edit)
        onAction()
    }

    Column(modifier.fillMaxWidth()) {
        if (session != null) {
            SuggestList(
                session = session,
                onChoose = { index -> onSession(acceptSuggestion(state, session, index)) },
            )
        }
        HorizontalDivider(color = colours.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .background(colours.surfaceContainer)
                .horizontalScroll(rememberScrollState())
                .testTag("accessory-row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton("checkbox", Icons.Outlined.CheckBox) {
                run(checkboxEdit(text, selection, decorations))
            }
            ToolButton("bold", Icons.Outlined.FormatBold) { run(emphasisEdit(text, selection, "**")) }
            ToolButton("italic", Icons.Outlined.FormatItalic) { run(emphasisEdit(text, selection, "*")) }
            ToolButton("list", Icons.AutoMirrored.Outlined.FormatListBulleted) {
                run(bulletEdit(text, selection))
            }
            // The `/` key types the trigger the shared machine listens
            // for, rather than opening a menu behind its back: on a task
            // line the session opens through exactly the same predicate a
            // typed `/` passes, and anywhere else it is one character of
            // the user's own text.
            SlashButton { run(slashEdit(text, selection)) }
            ToolButton(
                "undo",
                Icons.AutoMirrored.Outlined.Undo,
                enabled = state.undoState.canUndo,
            ) { state.undoState.undo() }
            ToolButton("more", Icons.Outlined.MoreHoriz) {
                panelOpen = !panelOpen
                if (panelOpen) keyboard?.hide()
            }
            ToolButton("dismiss-keyboard", Icons.Outlined.KeyboardHide) { keyboard?.hide() }
        }
        if (panelOpen) {
            EditorPanel(
                state = state,
                text = text,
                selection = selection,
                onEdit = { run(it) },
            )
        }
    }
}

/// Everything the row has no slot for, at full touch-target size
/// (§ 3). It replaces the keyboard rather than stacking above it.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorPanel(
    state: TextFieldState,
    text: String,
    selection: TextRange,
    onEdit: (BufferEdit?) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .height(PanelHeight)
            .background(colours.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("editor-panel"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (level in 1..3) {
                HeadingButton(level) { onEdit(headingEdit(text, selection, level)) }
            }
            ToolButton("quote", Icons.Outlined.FormatQuote) { onEdit(quoteEdit(text, selection)) }
            ToolButton("code", Icons.Outlined.Code) { onEdit(emphasisEdit(text, selection, "`")) }
            ToolButton("link", Icons.Outlined.Link) { onEdit(linkEdit(text, selection)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolButton("strike", Icons.Outlined.FormatStrikethrough) {
                onEdit(emphasisEdit(text, selection, "~~"))
            }
            ToolButton("indent", Icons.AutoMirrored.Outlined.FormatIndentIncrease) {
                onEdit(indentEdit(text, selection, outdent = false))
            }
            ToolButton("outdent", Icons.AutoMirrored.Outlined.FormatIndentDecrease) {
                onEdit(indentEdit(text, selection, outdent = true))
            }
            ToolButton("move-up", Icons.Outlined.ArrowUpward) {
                onEdit(moveLineEdit(text, selection, up = true))
            }
            ToolButton("move-down", Icons.Outlined.ArrowDownward) {
                onEdit(moveLineEdit(text, selection, up = false))
            }
            ToolButton(
                "redo",
                Icons.AutoMirrored.Outlined.Redo,
                enabled = state.undoState.canRedo,
            ) { state.undoState.redo() }
        }
    }
}

/// The candidates, directly above the row (§ 3). Tapping is what ↑/↓ and
/// Enter are on a desktop, so a row is one gesture from the buffer.
@Composable
private fun SuggestList(session: SuggestSession, onChoose: (Int) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(colours.surfaceContainerHigh)
            .heightIn(max = TouchTarget * 4)
            .verticalScroll(rememberScrollState())
            .testTag("suggest"),
    ) {
        for ((index, candidate) in session.candidates.withIndex()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(index) }
                    .heightIn(min = TouchTarget)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("suggest-row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    candidate.display,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colours.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (candidate.detail.isNotEmpty()) {
                    Text(
                        candidate.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colours.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    tag: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(TouchTarget).testTag(tag),
    ) {
        Icon(
            icon,
            contentDescription = tag,
            tint = if (enabled) colours.onSurfaceVariant else colours.outline,
        )
    }
}

@Composable
private fun SlashButton(onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(TouchTarget).testTag("slash")) {
        Text("/", style = MaterialTheme.typography.titleMedium, color = colours.onSurfaceVariant)
    }
}

@Composable
private fun HeadingButton(level: Int, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(TouchTarget).testTag("heading-$level")) {
        Text("H$level", style = MaterialTheme.typography.labelLarge, color = colours.onSurfaceVariant)
    }
}

/// Typing the trigger. A `/` only opens the menu at a word boundary
/// (`SuggestSession.triggered`), so the button supplies the separating
/// space when the caret is mid-word — the one place it writes a byte the
/// user did not press, and it writes it where a space was needed anyway.
fun slashEdit(text: String, selection: TextRange): BufferEdit {
    val from = selection.min.coerceIn(0, text.length)
    val to = selection.max.coerceIn(from, text.length)
    val needsSpace = from > 0 && !text[from - 1].isWhitespace()
    val written = if (needsSpace) " /" else "/"
    return BufferEdit(from, to, written, TextRange(from + written.length))
}

/// Accepting a candidate by tap. Returns the session that survives it:
/// stage one advances to stage two, stage two writes and ends.
///
/// The write is `Acceptance` applied literally — replace exactly that
/// range with exactly that text, caret immediately after — which is the
/// shared `replaceRange`'s contract on the modern API's primitive.
/// `SuggestSession` moves its highlight one row at a time because a
/// keyboard does; a tap names a row, so it is composed from the moves the
/// machine already has rather than reaching into it.
fun acceptSuggestion(state: TextFieldState, session: SuggestSession, index: Int): SuggestSession? {
    val chosen = session.selecting(index)
    return when (val outcome = chosen.accept()) {
        is SuggestOutcome.Open -> outcome.session
        is SuggestOutcome.Insert -> {
            val acceptance = outcome.acceptance
            val text = state.text.toString()
            val from = acceptance.replaceRange.first.coerceIn(0, text.length)
            val to = (acceptance.replaceRange.last + 1).coerceIn(from, text.length)
            state.edit {
                replace(from, to, acceptance.text)
                selection = TextRange(from + acceptance.text.length)
            }
            null
        }
        SuggestOutcome.Dismissed -> null
    }
}

/// The highlight moved to `index`, by the only means the machine offers.
fun SuggestSession.selecting(index: Int): SuggestSession {
    val wanted = index.coerceIn(0, candidates.size - 1)
    var current = this
    var guard = 0
    while (current.selected != wanted && guard < candidates.size) {
        current = current.moveDown()
        guard++
    }
    return current
}
