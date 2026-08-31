// The document region as a pane host (ADR 0014): a tab strip at the top
// of each pane, the note action row and body below it. This file owns
// rendering of the pane tree; TabsState owns the tree itself.
//
// No gesture here decides where a tab goes or whether it may close: each
// one asks TabsState and reports the answer, so the rules stay in one
// place and are tested without composition.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// Wide enough for a stem worth reading, narrow enough that a strip full
/// of them still scrolls: tabs keep a minimum rather than shrinking to
/// slivers (ADR 0014).
private val TAB_MIN_WIDTH = 110.dp
private val TAB_MAX_WIDTH = 200.dp

/// The dot and the close × share one fixed slot, so a tab going dirty or
/// coming under the pointer never changes its width.
private val TAB_AFFORDANCE = 16.dp

@Composable
fun PaneHost(
    state: GiluneState,
    tabs: TabsState,
    modifier: Modifier = Modifier,
    recorder: AudioRecorder? = null,
) {
    PaneNodeView(state, tabs, tabs.root, modifier.fillMaxSize(), recorder)
}

/// v0 splits side by side, so a split is a row of its children with a
/// hairline between siblings; the model carries an orientation so
/// stacking costs a renderer rather than a new model.
@Composable
private fun PaneNodeView(
    state: GiluneState,
    tabs: TabsState,
    node: PaneNode,
    modifier: Modifier,
    recorder: AudioRecorder?,
) {
    when (node) {
        is TabPane -> PaneView(state, tabs, node, modifier, recorder)
        is Split -> Row(modifier) {
            val colours = MaterialTheme.colorScheme
            node.children.forEachIndexed { index, child ->
                if (index > 0) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colours.outlineVariant))
                }
                // A weight of zero would not lay out; a layout restored
                // from disk is not trusted to hold sensible ones.
                val share = node.weights.getOrElse(index) { 0f }
                    .takeIf { it > 0f } ?: (1f / node.children.size)
                key(child) {
                    PaneNodeView(state, tabs, child, Modifier.weight(share).fillMaxHeight(), recorder)
                }
            }
        }
    }
}

@Composable
private fun PaneView(
    state: GiluneState,
    tabs: TabsState,
    pane: TabPane,
    modifier: Modifier,
    recorder: AudioRecorder?,
) {
    val colours = MaterialTheme.colorScheme
    Column(
        modifier
            // Watched on the initial pass and never consumed: touching a
            // pane anywhere brings it to the front, and the gesture the
            // user actually made still happens.
            .pointerInput(pane, tabs) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        if (tabs.activePane === pane) continue
                        // Re-selecting the pane's own front tab activates
                        // the pane without flushing anything.
                        pane.active?.let { tabs.select(pane, it) }
                    }
                }
            },
    ) {
        TabStrip(state, tabs, pane)
        HorizontalDivider(color = colours.outlineVariant)
        val tab = pane.active
        if (tab == null) {
            // Only the lone root pane is ever empty; a split pane that
            // loses its last tab dissolves instead.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("select a note, or capture a thought", color = colours.onSurfaceVariant)
            }
        } else {
            key(tab.id) {
                NoteEditor(
                    state,
                    tab.editor,
                    Modifier.fillMaxSize(),
                    recorder,
                    onRenamed = { renamed -> tabs.noteRenamed(tab.relPath, renamed) },
                    tabs = tabs,
                    tab = tab,
                    onCloseTab = { closeTab(state, tabs, pane, tab) },
                )
            }
        }
    }
}

/// The strip stands its full height even with nothing in it: the rail
/// header on the left is the same height, and the divider beneath both
/// has to run unbroken across the seam.
@Composable
private fun TabStrip(state: GiluneState, tabs: TabsState, pane: TabPane) {
    val colours = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var activeBounds by remember { mutableStateOf(Rect.Zero) }
    var overflowMenu by remember { mutableStateOf(false) }
    val overflowing = scroll.maxValue > 0 && scroll.maxValue < Int.MAX_VALUE

    // The tab in front is never left off the end of the strip. Scrolling
    // moves the bounds that fed this, so it settles once the tab is in.
    LaunchedEffect(activeBounds, viewport) {
        if (viewport.width <= 0f || activeBounds.width <= 0f) return@LaunchedEffect
        val before = viewport.left - activeBounds.left
        val after = activeBounds.right - viewport.right
        when {
            before > 0.5f -> scroll.animateScrollTo((scroll.value - before).toInt().coerceAtLeast(0))
            after > 0.5f -> scroll.animateScrollTo((scroll.value + after).toInt().coerceAtMost(scroll.maxValue))
        }
    }

    Row(
        Modifier.fillMaxWidth().height(PANE_HEADER_HEIGHT).background(colours.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onGloballyPositioned { viewport = it.boundsInRoot() }
                .horizontalScroll(scroll),
        ) {
            pane.tabs.forEachIndexed { index, tab ->
                if (index > 0) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colours.outlineVariant))
                }
                key(tab.id) {
                    TabChip(
                        state = state,
                        tabs = tabs,
                        pane = pane,
                        tab = tab,
                        active = pane.active === tab,
                        paneActive = tabs.activePane === pane,
                        onActiveBounds = { activeBounds = it },
                    )
                }
            }
        }
        if (overflowing) {
            Box {
                IconButton(onClick = { overflowMenu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "open tabs",
                        modifier = Modifier.size(16.dp),
                        tint = colours.onSurfaceVariant,
                    )
                }
                // Scrolling alone would let a tab hide off the end; the
                // menu keeps every one of them reachable.
                DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                    for (tab in pane.tabs) {
                        DropdownMenuItem(
                            text = { Text(tabs.label(tab), fontSize = 13.sp) },
                            onClick = {
                                overflowMenu = false
                                tabs.select(pane, tab)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(
    state: GiluneState,
    tabs: TabsState,
    pane: TabPane,
    tab: Tab,
    active: Boolean,
    paneActive: Boolean,
    onActiveBounds: (Rect) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val rule = colours.primary
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val label = tabs.label(tab)

    Box(Modifier.fillMaxHeight()) {
        Row(
            Modifier
                .fillMaxHeight()
                .widthIn(min = TAB_MIN_WIDTH, max = TAB_MAX_WIDTH)
                // The tab in front takes the colour of the document it
                // opens, so the two read as one surface.
                .background(if (active) colours.surface else Color.Transparent)
                // With two panes both showing a front tab, the top rule
                // names the one Ctrl+W, Ctrl+Tab and dictation land in.
                // Drawn rather than laid out: inside horizontalScroll the
                // width constraint is infinite, so fillMaxWidth cannot
                // size a sibling accent.
                .drawBehind {
                    if (active && paneActive) {
                        drawRect(rule, size = Size(size.width, 2.dp.toPx()))
                    }
                }
                .hoverable(interaction)
                .onGloballyPositioned { if (active) onActiveBounds(it.boundsInRoot()) }
                .pointerInput(tab, pane) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press) continue
                            // The close × inside the tab consumes its own
                            // press; anything left is the tab's.
                            if (event.changes.any { it.isConsumed }) continue
                            when {
                                event.buttons.isTertiaryPressed -> closeTab(state, tabs, pane, tab)
                                event.buttons.isSecondaryPressed -> menuOpen = true
                                // A press with no button is a touch, and a
                                // touch means the primary gesture.
                                else -> tabs.select(pane, tab)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                fontSize = 12.sp,
                color = if (active && paneActive) colours.onSurface else colours.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Named so a test can read the strip without meeting the
                // note title, which carries the same stem.
                modifier = Modifier.weight(1f, fill = false).testTag("tab-label"),
            )
            TabAffordance(
                dirty = tab.editor.dirty,
                stale = tab.editor.staleBuffer,
                hovered = hovered,
                label = label,
                onClose = { closeTab(state, tabs, pane, tab) },
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Split right", fontSize = 13.sp) },
                onClick = {
                    menuOpen = false
                    tabs.select(pane, tab)
                    // One buffer per note makes a split a move, so a pane
                    // holding nothing else cannot give this tab away.
                    if (!tabs.splitRight()) {
                        state.message.value = "a pane needs a second tab before it can split"
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Close", fontSize = 13.sp) },
                onClick = {
                    menuOpen = false
                    closeTab(state, tabs, pane, tab)
                },
            )
        }
    }
}

/// One slot, three faces: unsaved is the dot the note header used to
/// carry, a stale buffer takes the error colour because it is asking for
/// an answer from out of sight, and the pointer turns either into a close
/// × — as it does over a tab with nothing to say.
@Composable
private fun TabAffordance(
    dirty: Boolean,
    stale: Boolean,
    hovered: Boolean,
    label: String,
    onClose: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    Box(
        Modifier.size(TAB_AFFORDANCE).testTag("tab-affordance"),
        contentAlignment = Alignment.Center,
    ) {
        when {
            hovered -> Box(
                Modifier.size(TAB_AFFORDANCE).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "close $label",
                    modifier = Modifier.size(11.dp),
                    tint = colours.onSurfaceVariant,
                )
            }
            stale -> Box(Modifier.size(7.dp).clip(CircleShape).background(colours.error))
            dirty -> Box(Modifier.size(7.dp).clip(CircleShape).background(colours.primary))
        }
    }
}

/// A refused close is a refused save: the model has already brought the
/// tab back to the front, so all that is left is to say why it is still
/// there (ADR 0012's guard, ADR 0014's rule that nothing discards edits).
private fun closeTab(state: GiluneState, tabs: TabsState, pane: TabPane, tab: Tab) {
    if (tabs.close(pane, tab)) return
    state.message.value = "unsaved changes over a changed file — choose which version to keep"
}
