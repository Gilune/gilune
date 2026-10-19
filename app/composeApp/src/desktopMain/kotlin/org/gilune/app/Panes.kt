// The document region as a pane host (ADR 0014): a tab strip at the top
// of each pane, the note action row and body below it. This file owns
// rendering of the pane tree; TabsState owns the tree itself.
//
// No gesture here decides where a tab goes or whether it may close: each
// one asks TabsState and reports the answer, so the rules stay in one
// place and are tested without composition.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import java.awt.Cursor
import kotlin.math.roundToInt

/// Wide enough for a stem worth reading, narrow enough that a strip full
/// of them still scrolls: tabs keep a minimum rather than shrinking to
/// slivers (ADR 0014).
private val TAB_MIN_WIDTH = 110.dp
private val TAB_MAX_WIDTH = 200.dp

/// The dot and the close × share one fixed slot, so a tab going dirty or
/// coming under the pointer never changes its width.
private val TAB_AFFORDANCE = 16.dp

/// How far the pointer travels with the button still held before a press
/// that has already selected a tab becomes a drag of it.
private val DRAG_SLOP = 6.dp

/// A drag hovering this close to the end of an overflowing strip scrolls
/// it, so a gap off the end of the strip can still be dropped on.
private val STRIP_EDGE = 24.dp
private const val STRIP_EDGE_STEP = 12f

/// A pane's outer quarter splits it and gives the new half to the tab
/// dropped there (ADR 0014).
private const val SPLIT_ZONE = 0.25f

/// A divider is a hairline to look at and a band to grab: the line keeps
/// its 1.dp of layout so nothing shifts, and the pointer is answered this
/// far either side of it.
private val DIVIDER_GRAB = 9.dp

/// The weight clamp backstops every drag, but 0.15 of a wide window is
/// still a pane too narrow to read a line in, so the pointer is stopped
/// at a real width first (ADR 0014 amendment).
private val MIN_PANE_WIDTH = 160.dp

/// Where a released tab would land. Every rect is in root coordinates,
/// the one space panes, strips and chips share; the overlay converts.
private sealed interface TabDropTarget {
    /// A gap in a pane's strip: the tab lands at `index` in that strip as
    /// it stands now. `gapX` is where the caret is drawn.
    data class Insert(
        val pane: TabPane,
        val index: Int,
        val gapX: Float,
        val strip: Rect,
    ) : TabDropTarget

    /// A pane's outer quarter: the pane splits and the tab takes the new
    /// half to its right.
    data class SplitInto(val pane: TabPane, val zone: Rect) : TabDropTarget
}

/// The live tab drag and the bounds it hit-tests against (ADR 0014 stage
/// M+2). Compose offers nothing to reach for: its drag-and-drop modifiers
/// carry transferable data between applications, while a tab moving
/// inside one window is a hand-written pointer gesture over bounds
/// collected from layout callbacks.
///
/// The registry is written by `onGloballyPositioned` and read only while
/// a drag is live; `boundsInRoot` is window-root based, so panes, strips
/// and chips arrive already in one another's terms.
private class TabDrag {
    var hostOrigin by mutableStateOf(Offset.Zero)
    val panes = mutableStateMapOf<TabPane, Rect>()
    val strips = mutableStateMapOf<TabPane, Rect>()
    val chips = mutableStateMapOf<Tab, Rect>()

    var tab by mutableStateOf<Tab?>(null)
        private set
    var from by mutableStateOf<TabPane?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)

    fun begin(tab: Tab, from: TabPane, tabs: TabsState, at: Offset) {
        // Bounds outlive the tabs and panes that reported them, and a
        // drag is the only moment they matter — so it is the moment to
        // forget the ones nothing owns any more.
        val live = tabs.panes()
        panes.keys.retainAll(live.toSet())
        strips.keys.retainAll(live.toSet())
        chips.keys.retainAll(live.flatMap { it.tabs }.toSet())
        this.tab = tab
        this.from = from
        pointer = at
    }

    fun end() {
        tab = null
        from = null
    }

    /// In order: a strip under the pointer names a gap; failing that, the
    /// outer quarter of a pane splits it; failing that, nothing, and the
    /// release does nothing either.
    fun targetAt(point: Offset, order: List<TabPane>): TabDropTarget? {
        for (pane in order) {
            val strip = strips[pane] ?: continue
            if (strip.contains(point)) return insertionInto(pane, strip, point)
        }
        for (pane in order) {
            val bounds = panes[pane] ?: continue
            if (!bounds.contains(point)) continue
            // A pane's only tab dropped just right of itself is the
            // layout it already has, so it is offered no highlight.
            if (pane === from && pane.tabs.size < 2) return null
            val zone = Rect(
                bounds.right - bounds.width * SPLIT_ZONE,
                bounds.top,
                bounds.right,
                bounds.bottom,
            )
            return if (zone.contains(point)) TabDropTarget.SplitInto(pane, zone) else null
        }
        return null
    }

    /// Before the first chip whose midpoint the pointer is left of, else
    /// the end. A chip scrolled out of the strip keeps its bounds, so the
    /// comparison stays in strip order whatever is on screen.
    private fun insertionInto(pane: TabPane, strip: Rect, point: Offset): TabDropTarget {
        val bounds = pane.tabs.map { chips[it] ?: Rect.Zero }
        val index = bounds.indexOfFirst { point.x < it.center.x }.takeIf { it >= 0 } ?: bounds.size
        val gap = bounds.getOrNull(index)?.left ?: bounds.lastOrNull()?.right ?: strip.left
        return TabDropTarget.Insert(
            pane = pane,
            index = index,
            gapX = gap.coerceIn(strip.left, strip.right.coerceAtLeast(strip.left)),
            strip = strip,
        )
    }
}

@Composable
fun PaneHost(
    state: GiluneState,
    tabs: TabsState,
    modifier: Modifier = Modifier,
    recorder: AudioRecorder? = null,
) {
    val drag = remember(tabs) { TabDrag() }
    Box(
        modifier
            .testTag("pane-host")
            // The host may not sit at the window origin, so the overlay
            // subtracts this to place a root-space rect in its own space.
            .onGloballyPositioned { drag.hostOrigin = it.boundsInRoot().topLeft },
    ) {
        PaneNodeView(state, tabs, tabs.root, Modifier.fillMaxSize(), recorder, drag)
        DragOverlay(tabs, drag)
    }
}

/// The drag image and the highlight beneath it, drawn above every pane at
/// the host's root: neither is clipped by the strip that scrolls or by
/// the pane whose space it names.
@Composable
private fun BoxScope.DragOverlay(tabs: TabsState, drag: TabDrag) {
    val tab = drag.tab ?: return
    val colours = MaterialTheme.colorScheme
    val label = tabs.label(tab)
    val origin = drag.hostOrigin
    val pointer = drag.pointer
    val target = drag.targetAt(pointer, tabs.panes())
    Box(
        Modifier.matchParentSize().drawBehind {
            when (target) {
                is TabDropTarget.Insert -> drawRect(
                    colours.primary,
                    topLeft = Offset(
                        target.gapX - origin.x - 1.dp.toPx(),
                        target.strip.top - origin.y,
                    ),
                    size = Size(2.dp.toPx(), target.strip.height),
                )
                is TabDropTarget.SplitInto -> drawRect(
                    colours.primary.copy(alpha = 0.12f),
                    topLeft = target.zone.topLeft - origin,
                    size = target.zone.size,
                )
                null -> Unit
            }
        },
    ) {
        Box(
            Modifier
                .offset {
                    val local = pointer - origin
                    IntOffset(
                        (local.x + 12.dp.toPx()).roundToInt(),
                        (local.y + 10.dp.toPx()).roundToInt(),
                    )
                }
                .alpha(0.85f)
                .background(colours.surfaceContainerHigh, RoundedCornerShape(4.dp))
                .border(1.dp, colours.outlineVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                label,
                fontSize = 12.sp,
                color = colours.onSurface,
                maxLines = 1,
                // Named so a test can tell a drag that is under way from
                // one that never got past the slop.
                modifier = Modifier.testTag("tab-drag-image"),
            )
        }
    }
}

/// v0 splits side by side, so a split is a row of its children with a
/// draggable hairline between siblings; the model carries an orientation
/// so stacking costs a renderer rather than a new model.
@Composable
private fun PaneNodeView(
    state: GiluneState,
    tabs: TabsState,
    node: PaneNode,
    modifier: Modifier,
    recorder: AudioRecorder?,
    drag: TabDrag,
) {
    when (node) {
        is TabPane -> PaneView(state, tabs, node, modifier, recorder, drag)
        is Split -> {
            val density = LocalDensity.current
            // A drag arrives in pixels and the model speaks fractions of
            // the split, so the row reports how wide the whole of it is.
            var extent by remember(node) { mutableStateOf(0f) }
            Row(modifier.onGloballyPositioned { extent = it.size.width.toFloat() }) {
                node.children.forEachIndexed { index, child ->
                    if (index > 0) {
                        val divider = index - 1
                        DragDivider("split-divider") { delta ->
                            if (extent <= 0f) return@DragDivider
                            val floor = with(density) { MIN_PANE_WIDTH.toPx() }
                            val moved = withinWidths(node, divider, extent, delta, floor)
                            tabs.resize(node, divider, moved / extent)
                        }
                    }
                    // A weight of zero would not lay out; a layout restored
                    // from disk is not trusted to hold sensible ones.
                    val share = node.weights.getOrElse(index) { 0f }
                        .takeIf { it > 0f } ?: (1f / node.children.size)
                    key(child) {
                        PaneNodeView(
                            state,
                            tabs,
                            child,
                            Modifier.weight(share).fillMaxHeight(),
                            recorder,
                            drag,
                        )
                    }
                }
            }
        }
    }
}

/// The pixel half of the clamp: a drag stops where either neighbour would
/// fall under a readable width, and only where the two of them are wide
/// enough between them to give both that much — on a narrow window the
/// model's weight clamp is all there is.
private fun withinWidths(
    split: Split,
    divider: Int,
    extent: Float,
    deltaPx: Float,
    floorPx: Float,
): Float {
    val a = (split.weights.getOrElse(divider) { 0f } * extent).coerceAtLeast(0f)
    val b = (split.weights.getOrElse(divider + 1) { 0f } * extent).coerceAtLeast(0f)
    val floor = if (a + b >= floorPx * 2f) floorPx else 0f
    return deltaPx.coerceIn(floor - a, b - floor)
}

/// A divider that can be dragged: the rail's (ADR 0011) and every one
/// between sibling panes (ADR 0014). The hairline keeps exactly the 1.dp
/// of layout it always had, so nothing shifts when the handle appears;
/// the grab band is a child wider than its parent, which a
/// `requiredWidth` may be without changing what the parent takes. It is
/// lifted above its siblings so the band belongs to the divider and not
/// to whichever pane it overlaps.
///
/// Live resize, not resize-on-release: the deltas arrive as they happen
/// and the caller applies each one. `draggable` consumes what it uses, so
/// the tab-drag machinery never sees a divider's gesture.
@Composable
fun DragDivider(
    tag: String,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onDelta: (Float) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    Box(
        modifier
            .zIndex(1f)
            .width(1.dp)
            .fillMaxHeight()
            // A divider under the hand says so, and nothing else changes:
            // the line is a hairline whether it is being held or not.
            .background(if (dragging) colours.primary else colours.outlineVariant),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredWidth(DIVIDER_GRAB)
                .fillMaxHeight()
                .testTag(tag)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .draggable(
                    state = rememberDraggableState { onDelta(it) },
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        dragging = true
                        onStart()
                    },
                    onDragStopped = {
                        dragging = false
                        onStop()
                    },
                ),
        )
    }
}

@Composable
private fun PaneView(
    state: GiluneState,
    tabs: TabsState,
    pane: TabPane,
    modifier: Modifier,
    recorder: AudioRecorder?,
    drag: TabDrag,
) {
    val colours = MaterialTheme.colorScheme
    Column(
        modifier
            // The pane's own bounds: a drag that misses every strip asks
            // these whether it is over an outer quarter.
            .onGloballyPositioned { drag.panes[pane] = it.boundsInRoot() }
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
        // A dissolved pane must not leave a drop target behind it.
        DisposableEffect(pane) {
            onDispose {
                drag.panes.remove(pane)
                drag.strips.remove(pane)
            }
        }
        TabStrip(state, tabs, pane, drag)
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
private fun TabStrip(state: GiluneState, tabs: TabsState, pane: TabPane, drag: TabDrag) {
    val colours = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var activeBounds by remember { mutableStateOf(Rect.Zero) }
    var overflowMenu by remember { mutableStateOf(false) }
    val overflowing = scroll.maxValue > 0 && scroll.maxValue < Int.MAX_VALUE

    // The tab in front is never left off the end of the strip. Scrolling
    // moves the bounds that fed this, so it settles once the tab is in —
    // but a live drag owns the strip's scroll, and the two effects must
    // never pull against each other.
    LaunchedEffect(activeBounds, viewport, drag.tab) {
        if (drag.tab != null) return@LaunchedEffect
        if (viewport.width <= 0f || activeBounds.width <= 0f) return@LaunchedEffect
        val before = viewport.left - activeBounds.left
        val after = activeBounds.right - viewport.right
        when {
            before > 0.5f -> scroll.animateScrollTo((scroll.value - before).toInt().coerceAtLeast(0))
            after > 0.5f -> scroll.animateScrollTo((scroll.value + after).toInt().coerceAtMost(scroll.maxValue))
        }
    }

    // -1 or 1 while a drag hovers the end of a strip holding more tabs
    // than it can show. Derived, so the pointer moving does not recompose
    // a strip that is not scrolling.
    val edge by remember(pane, density) {
        derivedStateOf {
            val strip = drag.strips[pane] ?: Rect.Zero
            val point = drag.pointer
            val margin = with(density) { STRIP_EDGE.toPx() }
            val hasOverflow = scroll.maxValue > 0 && scroll.maxValue < Int.MAX_VALUE
            when {
                drag.tab == null || !hasOverflow || !strip.contains(point) -> 0
                point.x < strip.left + margin -> -1
                point.x > strip.right - margin -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(edge) {
        if (edge == 0) return@LaunchedEffect
        while (true) {
            scroll.scrollBy(edge * STRIP_EDGE_STEP)
            delay(16)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(PANE_HEADER_HEIGHT)
            .background(colours.surfaceContainerHigh)
            // The whole strip is the drop band, chevron included: a tab
            // released over the overflow menu joins the end of the strip
            // rather than falling through to a split.
            .onGloballyPositioned { drag.strips[pane] = it.boundsInRoot() },
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
                        drag = drag,
                        onActiveBounds = { activeBounds = it },
                    )
                }
            }
        }
        if (overflowing) {
            Box {
                Tooltip("All open tabs") {
                    IconButton(onClick = { overflowMenu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "open tabs",
                            modifier = Modifier.size(16.dp),
                            tint = colours.onSurfaceVariant,
                        )
                    }
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
    drag: TabDrag,
    onActiveBounds: (Rect) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val rule = colours.primary
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var colourMenuOpen by remember { mutableStateOf(false) }
    val label = tabs.label(tab)
    val slop = with(LocalDensity.current) { DRAG_SLOP.toPx() }
    // The chip knows only its own coordinates; this is what turns them
    // into the root space every drop target is measured in.
    val bounds = remember { mutableStateOf(Rect.Zero) }

    DisposableEffect(tab) { onDispose { drag.chips.remove(tab) } }

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
                    tab.colour?.let {
                        drawRect(Color(it.argb), topLeft = Offset(0f, size.height - 3.dp.toPx()), size = Size(size.width, 3.dp.toPx()))
                    }
                    if (active && paneActive) {
                        drawRect(rule, size = Size(size.width, 2.dp.toPx()))
                    }
                }
                .hoverable(interaction)
                .onGloballyPositioned {
                    val rect = it.boundsInRoot()
                    bounds.value = rect
                    drag.chips[tab] = rect
                    if (active) onActiveBounds(rect)
                }
                .pointerInput(tab, pane, drag) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press) continue
                            // The close × inside the tab consumes its own
                            // press; anything left is the tab's.
                            if (event.changes.any { it.isConsumed }) continue
                            val down = event.changes.first()
                            when {
                                event.buttons.isTertiaryPressed -> {
                                    closeTab(state, tabs, pane, tab)
                                    event.changes.forEach { it.consume() }
                                }
                                event.buttons.isSecondaryPressed -> {
                                    menuOpen = true
                                    event.changes.forEach { it.consume() }
                                }
                                // A press with no button is a touch, and a
                                // touch means the primary gesture. It still
                                // selects on the press; what the same press
                                // does next is the drag's business.
                                else -> {
                                    tabs.select(pane, tab)
                                    event.changes.forEach { it.consume() }
                                    dragTab(tabs, drag, pane, tab, bounds, down.id, down.position, slop)
                                }
                            }
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; colourMenuOpen = false }) {
            if (colourMenuOpen) {
                DropdownMenuItem(text = { Text("Default colour") }, onClick = { tab.colour = null; menuOpen = false; colourMenuOpen = false })
                for (colour in TabColour.entries) {
                    DropdownMenuItem(text = { Text(colour.name) },
                        leadingIcon = { Box(Modifier.size(14.dp).background(Color(colour.argb))) },
                        onClick = { tab.colour = colour; menuOpen = false; colourMenuOpen = false })
                }
            } else {
                DropdownMenuItem(text = { Text("Close all but this") }, onClick = {
                    menuOpen = false
                    if (!tabs.closeOthers(pane, tab)) state.message.value = "Some tabs have unsaved changes over changed files; review them before closing."
                })
                DropdownMenuItem(text = { Text("Close to the right") }, onClick = {
                    menuOpen = false
                    if (!tabs.closeToRight(pane, tab)) state.message.value = "Some tabs have unsaved changes over changed files; review them before closing."
                })
                DropdownMenuItem(text = { Text("Pick a colour…") }, onClick = { colourMenuOpen = true })
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
            hovered -> Tooltip("Close (Ctrl+W)") {
                Box(
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
            }
            stale -> Box(Modifier.size(7.dp).clip(CircleShape).background(colours.error))
            dirty -> Box(Modifier.size(7.dp).clip(CircleShape).background(colours.primary))
        }
    }
}

/// The half of the gesture that exists only once the pointer moves. The
/// press has already selected the tab, so nothing here undoes that: it
/// follows the pointer, tells the overlay where it is, and on release
/// asks the model for the drop the overlay was drawing.
///
/// Positions arrive in the chip's own space, and the chip may be dragged
/// clean out of itself, so every one of them is reported with the chip's
/// root offset added.
private suspend fun AwaitPointerEventScope.dragTab(
    tabs: TabsState,
    drag: TabDrag,
    pane: TabPane,
    tab: Tab,
    bounds: State<Rect>,
    pointer: PointerId,
    start: Offset,
    slop: Float,
) {
    var dragging = false
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointer } ?: break
            val at = bounds.value.topLeft + change.position
            if (!change.pressed) {
                if (!dragging) break
                val target = drag.targetAt(at, tabs.panes())
                drag.end()
                dragging = false
                change.consume()
                drop(tabs, target, pane, tab)
                break
            }
            if (!dragging && (change.position - start).getDistance() >= slop) {
                dragging = true
                drag.begin(tab, pane, tabs, at)
            }
            if (dragging) {
                drag.pointer = at
                change.consume()
            }
        }
    } finally {
        // A gesture can end without a release — a recomposition, a lost
        // pointer — and must not leave a drag image behind it.
        if (dragging) drag.end()
    }
}

/// Every drag ends here, and the model decides: a drop that would leave
/// the tab where it already is asks it nothing at all.
private fun drop(tabs: TabsState, target: TabDropTarget?, from: TabPane, tab: Tab) {
    when (target) {
        is TabDropTarget.Insert -> {
            val at = from.tabs.indexOfFirst { it === tab }
            // The gap is named in the strip as it stands, so removing the
            // tab first shifts every gap after it left by one.
            val landing = if (target.index > at) target.index - 1 else target.index
            if (target.pane !== from || landing != at) {
                tabs.moveTab(tab, from, target.pane, target.index)
            }
        }
        is TabDropTarget.SplitInto -> tabs.splitWith(tab, from, target.pane)
        null -> Unit
    }
}

/// A refused close is a refused save: the model has already brought the
/// tab back to the front, so all that is left is to say why it is still
/// there (ADR 0012's guard, ADR 0014's rule that nothing discards edits).
private fun closeTab(state: GiluneState, tabs: TabsState, pane: TabPane, tab: Tab) {
    if (tabs.close(pane, tab)) return
    state.message.value = "unsaved changes over a changed file — choose which version to keep"
}
