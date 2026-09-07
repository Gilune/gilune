// The tree owns the pointer gesture so scrolling a source row out of the
// lazy list cannot cancel its drag. All hit testing uses root coordinates.
package org.gilune.app

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal class VaultTreeDrag {
    val rows = mutableStateMapOf<VaultEntry, Rect>()
    var origin = Offset.Zero
    var viewport by mutableStateOf(Rect.Zero)
    var rootTarget by mutableStateOf(Rect.Zero)
    var source by mutableStateOf<VaultEntry?>(null)
    var pointer by mutableStateOf(Offset.Zero)

    fun rowAt(point: Offset): VaultEntry? =
        if (viewport.contains(point)) rows.entries.firstOrNull { it.value.contains(point) }?.key else null

    val destination: String?
        get() {
            val moving = source ?: return null
            val row = rowAt(pointer)
            val folder = when {
                rootTarget.contains(pointer) -> ""
                !viewport.contains(pointer) -> return null
                row == null -> ""
                row.isDir -> row.relPath
                else -> row.relPath.substringBeforeLast('/', "")
            }
            return folder.takeIf { canMoveInto(moving.relPath, it) }
        }
}

@Composable
internal fun treeDragModifier(
    drag: VaultTreeDrag,
    tree: TreeState,
    list: LazyListState,
    onDrop: (VaultEntry, String) -> Unit,
): Modifier {
    val drop by rememberUpdatedState(onDrop)
    val destination = drag.destination
    LaunchedEffect(drag.source, destination) {
        if (!destination.isNullOrEmpty() && destination !in tree.expanded) {
            delay(650)
            tree.expanded.add(destination)
        }
    }
    val edge = with(LocalDensity.current) { 28.dp.toPx() }
    val scrollDirection by remember(drag, list, edge) {
        derivedStateOf {
            val bounds = drag.viewport
            val point = drag.pointer
            when {
                drag.source == null || !bounds.contains(point) -> 0
                point.y < bounds.top + edge && list.canScrollBackward -> -1
                point.y > bounds.bottom - edge && list.canScrollForward -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(scrollDirection) {
        if (scrollDirection == 0) return@LaunchedEffect
        while (true) {
            list.scrollBy(scrollDirection * edge / 3)
            delay(16)
        }
    }
    return Modifier.onGloballyPositioned { drag.origin = it.boundsInRoot().topLeft }
        .pointerInput(drag) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!event.buttons.isPrimaryPressed) continue
                    val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                    val entry = drag.rowAt(drag.origin + down.position) ?: continue
                    if (entry.kind == VaultEntryKind.ATTACHMENT) continue
                    try {
                        while (true) {
                            val next = awaitPointerEvent(PointerEventPass.Initial)
                            val change = next.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed && drag.source == null) break
                            drag.pointer = drag.origin + change.position
                            if (!change.pressed) {
                                if (drag.source != null) {
                                    val target = drag.destination
                                    change.consume()
                                    drag.source = null
                                    if (target != null) drop(entry, target)
                                }
                                break
                            }
                            if (drag.source == null &&
                                (change.position - down.position).getDistance() >= viewConfiguration.touchSlop
                            ) drag.source = entry
                            if (drag.source != null) change.consume()
                        }
                    } finally {
                        drag.source = null
                    }
                }
            }
        }
}
