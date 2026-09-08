// The document region's pane tree (ADR 0014). Desktop only: mobile shows
// one note, and the shared source set must never learn what a tab is.
//
// `TabsState` is the only thing that opens, finds and closes buffers. A
// note is open in at most one tab in v0, because two buffers over one
// file would race — each holds its own hash-at-open, each autosaves on
// idle, and whichever writes first makes the other permanently stale.
// That rule is why splitting and `Open to the side` MOVE a tab rather
// than copy it.
//
// Only a pane's active tab is composed, so only its autosave loop runs:
// leaving a tab flushes it through the same guarded write the editor
// uses, and a tab whose flush was refused refuses to close. No gesture
// here discards an edit.
//
// The ADR calls a leaf a pane; the type is `TabPane` because the
// settings screen already owns `Pane` for its own navigation.

package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import java.io.File

/// The rail header and every pane's tab strip stand this tall, so the
/// divider beneath them runs unbroken across the seam where they meet
/// (owner-reported misalignment, 2026-08-31).
val PANE_HEADER_HEIGHT = 36.dp

/// No pane is ever dragged shut: a divider stops where its neighbour
/// would fall below this share of the split (ADR 0014 amendment). A pane
/// leaves by losing its last tab, never by being squeezed out of sight.
const val MIN_PANE_WEIGHT = 0.15f

/// v0 splits side by side; the tree carries the orientation so stacking
/// costs a renderer rather than a new model (ADR 0014).
enum class SplitOrientation { Horizontal, Vertical }

sealed interface PaneNode

/// A leaf: an ordered strip of tabs and the one in front. A pane with no
/// tabs does not exist, save the single pane of a window with nothing
/// open — that one is the placeholder.
class TabPane : PaneNode {
    val tabs = mutableStateListOf<Tab>()
    var active by mutableStateOf<Tab?>(null)
}

/// An interior node. `weights` runs parallel to `children` and sums to 1.
class Split(
    children: List<PaneNode>,
    weights: List<Float>,
    val orientation: SplitOrientation = SplitOrientation.Horizontal,
) : PaneNode {
    val children = mutableStateListOf<PaneNode>().apply { addAll(children) }
    val weights = mutableStateListOf<Float>().apply { addAll(weights) }
}

/// A stable identity plus the note it shows. The buffer belongs to the
/// tab and travels with it between panes; `id` is allocated by
/// `TabsState`, which is the only thing that should construct one.
class Tab(val id: Long, relPath: String) {
    var relPath by mutableStateOf(relPath)
    var colour by mutableStateOf<TabColour?>(null)
    val editor = EditorState()
}

enum class TabColour(val argb: Long) {
    Red(0xFFE57373), Orange(0xFFFFB74D), Yellow(0xFFFFD54F),
    Green(0xFF81C784), Blue(0xFF64B5F6), Purple(0xFFBA68C8),
}

private const val LAYOUT_HEADER = "gilune-tabs 1"

class TabsState(val vaultDir: String) {
    var root: PaneNode by mutableStateOf<PaneNode>(TabPane())
    var activePane: TabPane by mutableStateOf(root as TabPane)

    /// Where a save made by the tab machinery — a deactivation, a close —
    /// is announced. The shell sets it to `GiluneState::noteSaved`; the
    /// model is testable without one, which is why it is a hook rather
    /// than a handle on the state.
    var onNoteSaved: ((String) -> Unit)? = null

    /// The dictation session's target: one at a time, so the words land
    /// in the note that started it even after a switch. Starting and
    /// stopping belong to the UI; this only remembers whose caret it is.
    var dictationTabId: Long? by mutableStateOf(null)

    private var nextId = 1L

    val activeTab: Tab? get() = activePane.active

    val activeEditor: EditorState? get() = activeTab?.editor

    fun openForEditing(relPath: String): Tab = open(relPath).also {
        it.editor.reading = false
        it.editor.focusTitle = true
    }

    fun panes(): List<TabPane> = panesOf(root)

    /// The one rule for every opener — tree, quick open, search, capture,
    /// creation: reuse, else a new tab after the active one. An open
    /// never replaces a tab; there are no preview tabs.
    fun open(relPath: String): Tab {
        findTab(relPath)?.let { (pane, tab) ->
            select(pane, tab)
            return tab
        }
        val pane = activePane
        val tab = newTab(relPath)
        insertAfterActive(pane, tab)
        select(pane, tab)
        return tab
    }

    /// `Open to the side`. One buffer per note, so a note already open
    /// moves; if the pane to the right does not exist yet it is created
    /// around the arriving tab, since a pane cannot exist empty.
    fun openToSide(relPath: String): Tab {
        val found = findTab(relPath)
        val side = paneRightOf(activePane)
        if (found != null && found.first === side) {
            select(side, found.second)
            return found.second
        }
        // Moving a pane's only tab out would empty it, and the pane
        // would dissolve straight back into the space it just left.
        if (found != null && side == null && found.first === activePane && activePane.tabs.size < 2) {
            select(activePane, found.second)
            return found.second
        }
        val tab = found?.second ?: newTab(relPath)
        found?.let { (pane, moved) -> detach(pane, moved) }
        if (side != null) {
            insertAfterActive(side, tab)
            select(side, tab)
        } else {
            val fresh = TabPane()
            fresh.tabs.add(tab)
            insertRightOf(activePane, fresh)
            select(fresh, tab)
        }
        return tab
    }

    fun select(pane: TabPane, tab: Tab) {
        val leaving = pane.active
        pane.active = tab
        activePane = pane
        // The tab that left the front stops being composed, so nothing
        // would save it. A refused flush leaves it dirty and stale — it
        // will refuse to close — but the switch still happens.
        if (leaving != null && leaving !== tab) flushEditor(vaultDir, leaving.editor, onNoteSaved)
    }

    /// Ctrl+1…9: the nth tab of the active pane, counting from one.
    fun selectIndex(n: Int) {
        val tab = activePane.tabs.getOrNull(n - 1) ?: return
        select(activePane, tab)
    }

    /// Ctrl+Tab: strip order, wrapping, active pane only.
    fun cycle(forward: Boolean) {
        val pane = activePane
        if (pane.tabs.isEmpty()) return
        val at = pane.tabs.indexOfFirst { it === pane.active }.coerceAtLeast(0)
        val step = if (forward) 1 else -1
        select(pane, pane.tabs[(at + step).mod(pane.tabs.size)])
    }

    /// False when the buffer could not be saved: the tab is brought to
    /// the front to ask for an answer instead of being closed.
    fun close(pane: TabPane, tab: Tab): Boolean {
        if (pane.tabs.none { it === tab }) return false
        if (!flushEditor(vaultDir, tab.editor, onNoteSaved)) {
            select(pane, tab)
            return false
        }
        if (dictationTabId == tab.id) dictationTabId = null
        detach(pane, tab)
        return true
    }

    fun closeActive(): Boolean {
        val tab = activePane.active ?: return false
        return close(activePane, tab)
    }

    fun closeOthers(pane: TabPane, keep: Tab): Boolean {
        if (keep !in pane.tabs) return false
        return pane.tabs.filter { it !== keep }.all { close(pane, it) }
    }

    fun closeToRight(pane: TabPane, keep: Tab): Boolean {
        val at = pane.tabs.indexOf(keep)
        if (at < 0) return false
        return pane.tabs.drop(at + 1).all { close(pane, it) }
    }

    /// Ctrl+\: the active tab moves into a new pane to the right. One
    /// buffer per note makes this a move, so a pane holding nothing else
    /// cannot split — it would be left with no tabs, which a pane may
    /// not be.
    fun splitRight(): Boolean {
        val pane = activePane
        val tab = pane.active ?: return false
        if (pane.tabs.size < 2) return false
        splitOff(tab, pane, pane)
        return true
    }

    /// Dragging a tab (ADR 0014 stage M+2). `index` names a gap in `to`'s
    /// strip as it stands before the move, so a same-pane drop accounts
    /// for the gap the tab itself is about to vacate.
    ///
    /// A reorder within one strip changes nothing else: not the tab in
    /// front, not a buffer. Across panes the tab arrives in front, which
    /// deactivates whatever was there — flushed through the same
    /// discipline as any other switch, and left dirty and stale if that
    /// write is refused. The tab that moved is never flushed: it was in
    /// front and stays in front.
    fun moveTab(tab: Tab, from: TabPane, to: TabPane, index: Int) {
        val at = from.tabs.indexOfFirst { it === tab }
        if (at < 0) return
        if (from === to) {
            val gap = index.coerceIn(0, from.tabs.size)
            val landing = if (gap > at) gap - 1 else gap
            if (landing == at) return
            from.tabs.removeAt(at)
            from.tabs.add(landing, tab)
            return
        }
        detach(from, tab)
        to.tabs.add(index.coerceIn(0, to.tabs.size), tab)
        select(to, tab)
    }

    /// Dropping a tab on a pane's outer quarter: a new pane opens to the
    /// right of `target` holding it, halving that pane's share exactly as
    /// `splitRight` does. False where the result would be the layout it
    /// started with — a pane's only tab moved to just right of itself
    /// would dissolve the pane it left and land where it already was. A
    /// pane's only tab dropped on a *different* pane is legitimate: the
    /// source dissolves and the new pane appears at the target.
    fun splitWith(tab: Tab, from: TabPane, target: TabPane): Boolean {
        if (from.tabs.none { it === tab }) return false
        if (from === target && from.tabs.size < 2) return false
        splitOff(tab, from, target)
        return true
    }

    /// Dragging the divider between siblings (ADR 0014 amendment).
    /// Divider `i` sits between children `i` and `i+1`, and `delta` is a
    /// signed fraction of the whole split's extent: what one neighbour
    /// gains the other gives up, and nothing else in the split moves.
    ///
    /// A pointer that runs past the clamp is not refused — the move is
    /// applied as far as it may go and the divider stops there, which is
    /// what a divider held against the end of its travel should do.
    ///
    /// The weights are snapshot state and are written in place, so the
    /// panes relay out and the layout is persisted without either side
    /// being told.
    fun resize(split: Split, divider: Int, delta: Float) {
        if (divider < 0 || divider + 1 >= split.children.size) return
        if (divider + 1 >= split.weights.size) return
        val a = split.weights[divider]
        val b = split.weights[divider + 1]
        val low = MIN_PANE_WEIGHT - a
        val high = b - MIN_PANE_WEIGHT
        // Both neighbours already under the floor: a restored layout is
        // not trusted to hold sensible weights, and no legal move exists.
        if (low > high) return
        val move = delta.coerceIn(low, high)
        if (move == 0f) return
        // The pair's total is recomputed rather than subtracted from, so
        // the split's shares still sum to what they summed to before.
        val total = a + b
        split.weights[divider] = a + move
        split.weights[divider + 1] = total - split.weights[divider]
    }

    fun noteRenamed(oldRel: String, newRel: String) {
        for (tab in allTabs()) {
            if (tab.relPath != oldRel) continue
            tab.relPath = newRel
            if (tab.editor.openRelPath == oldRel) tab.editor.openRelPath = newRel
        }
    }

    /// The note is gone because the user destroyed it, so there is
    /// nothing to preserve and nothing to flush it to.
    fun noteDeleted(relPath: String) {
        for (pane in panes()) {
            for (tab in pane.tabs.filter { it.relPath == relPath }) {
                if (dictationTabId == tab.id) dictationTabId = null
                detach(pane, tab)
            }
        }
    }

    /// The file stem, and where open tabs share one, the folder holding
    /// it — a note at the vault root wears a bare "/" for that folder.
    fun label(tab: Tab): String {
        val stem = stemOf(tab.relPath)
        val shared = allTabs().count { stemOf(it.relPath) == stem } > 1
        if (!shared) return stem
        val folder = tab.relPath.substringBeforeLast('/', "").substringAfterLast('/')
        return if (folder.isEmpty()) "/$stem" else "$folder/$stem"
    }

    /// The layout as a line per node and a line per tab, paths last so
    /// they never need escaping. Buffers are not written: they were
    /// flushed, and a restored tab re-reads from disk.
    fun serialise(): String {
        val nodes = StringBuilder()
        val tabs = StringBuilder()
        val indices = mutableMapOf<PaneNode, Int>()
        var next = 0
        fun emit(node: PaneNode, parent: Int, weight: Float) {
            val at = next++
            indices[node] = at
            when (node) {
                is TabPane -> {
                    val active = node.active?.let { " ${it.relPath}" } ?: ""
                    nodes.append("P $at $parent $weight$active\n")
                    node.tabs.forEach {
                        tabs.append("T $at ${it.relPath}\n")
                        it.colour?.let { colour -> tabs.append("C $at ${colour.name} ${it.relPath}\n") }
                    }
                }
                is Split -> {
                    val axis = if (node.orientation == SplitOrientation.Vertical) "V" else "H"
                    nodes.append("S $at $parent $weight $axis\n")
                    node.children.forEachIndexed { i, child ->
                        emit(child, at, node.weights.getOrElse(i) { 1f / node.children.size })
                    }
                }
            }
        }
        emit(root, -1, 1f)
        return "$LAYOUT_HEADER\n$nodes$tabs" + "A ${indices[activePane] ?: 0}\n"
    }

    private fun newTab(relPath: String): Tab =
        Tab(nextId++, relPath).also { it.editor.open(vaultDir, relPath) }

    private fun insertAfterActive(pane: TabPane, tab: Tab) {
        val at = pane.tabs.indexOfFirst { it === pane.active }
        pane.tabs.add(if (at < 0) pane.tabs.size else at + 1, tab)
    }

    /// The tree surgery behind every split: the tab leaves the pane
    /// holding it and lands alone in a fresh pane to the right of
    /// `target`. The moved tab is never flushed — it goes straight to the
    /// front of the new pane — and the source follows the neighbour rule,
    /// dissolving if that was its last tab.
    private fun splitOff(tab: Tab, from: TabPane, target: TabPane) {
        detach(from, tab)
        val fresh = TabPane()
        fresh.tabs.add(tab)
        insertRightOf(target, fresh)
        select(fresh, tab)
    }

    /// Removal without a flush: closing flushes first, and a move keeps
    /// the buffer alive in another pane.
    private fun detach(pane: TabPane, tab: Tab) {
        val at = pane.tabs.indexOfFirst { it === tab }
        if (at < 0) return
        pane.tabs.removeAt(at)
        if (pane.active === tab) pane.active = pane.tabs.getOrNull(at) ?: pane.tabs.getOrNull(at - 1)
        if (pane.tabs.isEmpty()) dissolve(pane)
    }

    private fun dissolve(pane: TabPane) {
        if (pane === root) return
        val heir = neighbourPane(pane)
        root = prune(root) ?: TabPane()
        if (activePane === pane) activePane = heir ?: firstPaneOf(root)
        if (panes().none { it === activePane }) activePane = firstPaneOf(root)
    }

    private fun insertRightOf(pane: TabPane, fresh: TabPane) {
        val parent = parentOf(root, pane)
        if (parent == null) {
            root = Split(listOf(pane, fresh), listOf(0.5f, 0.5f))
            return
        }
        val at = parent.children.indexOfFirst { it === pane }
        // The new pane takes half of what the pane it came from held.
        val share = parent.weights.getOrElse(at) { 1f / parent.children.size } / 2f
        parent.weights[at] = share
        parent.children.add(at + 1, fresh)
        parent.weights.add(at + 1, share)
    }

    private fun findTab(relPath: String): Pair<TabPane, Tab>? = panes().firstNotNullOfOrNull { pane ->
        pane.tabs.firstOrNull { it.relPath == relPath }?.let { pane to it }
    }

    private fun allTabs(): List<Tab> = panes().flatMap { it.tabs }

    private fun panesOf(node: PaneNode): List<TabPane> = when (node) {
        is TabPane -> listOf(node)
        is Split -> node.children.flatMap { panesOf(it) }
    }

    private fun parentOf(node: PaneNode, child: PaneNode): Split? = when (node) {
        is TabPane -> null
        is Split ->
            if (node.children.any { it === child }) node
            else node.children.firstNotNullOfOrNull { parentOf(it, child) }
    }

    /// Splits never hold no children — `prune` sees to that — so the
    /// leftmost pane of any subtree exists.
    private fun firstPaneOf(node: PaneNode): TabPane = when (node) {
        is TabPane -> node
        is Split -> firstPaneOf(node.children.first())
    }

    private fun neighbourPane(pane: TabPane): TabPane? {
        val parent = parentOf(root, pane) ?: return null
        val at = parent.children.indexOfFirst { it === pane }
        val sibling = parent.children.getOrNull(at + 1) ?: parent.children.getOrNull(at - 1) ?: return null
        return firstPaneOf(sibling)
    }

    /// v0 splits side by side, so the pane to the right is the leftmost
    /// pane of the next sibling and nothing walks further up the tree.
    private fun paneRightOf(pane: TabPane): TabPane? {
        val parent = parentOf(root, pane) ?: return null
        val at = parent.children.indexOfFirst { it === pane }
        val next = parent.children.getOrNull(at + 1) ?: return null
        return firstPaneOf(next)
    }

    /// Drops panes left with no tabs and the splits left holding one
    /// child or none, so a dissolved pane's space goes to its sibling.
    /// Null when nothing under `node` survives.
    private fun prune(node: PaneNode): PaneNode? = when (node) {
        is TabPane -> if (node.tabs.isEmpty()) null else node
        is Split -> {
            val kept = mutableListOf<PaneNode>()
            val weights = mutableListOf<Float>()
            node.children.forEachIndexed { i, child ->
                prune(child)?.let {
                    kept += it
                    weights += node.weights.getOrElse(i) { 0f }
                }
            }
            when (kept.size) {
                0 -> null
                1 -> kept[0]
                else -> Split(kept, normalised(weights), node.orientation)
            }
        }
    }

    companion object {
        /// More tabs than a session has any use for, and few enough that
        /// the layout still fits a `Preferences` value.
        private const val MAX_RESTORED_TABS = 50

        /// Defensive by contract (ADR 0014): anything unparseable, and
        /// anything left holding nothing, is one empty pane.
        fun restore(vaultDir: String, serialised: String?): TabsState {
            if (serialised.isNullOrBlank()) return TabsState(vaultDir)
            return runCatching { parse(vaultDir, serialised) }.getOrNull() ?: TabsState(vaultDir)
        }

        private fun parse(vaultDir: String, text: String): TabsState {
            val lines = text.lines().filter { it.isNotBlank() }
            require(lines.firstOrNull() == LAYOUT_HEADER)
            val state = TabsState(vaultDir)
            val built = LinkedHashMap<Int, PaneNode>()
            val parents = mutableMapOf<Int, Int>()
            val weights = mutableMapOf<Int, Float>()
            val activeRel = mutableMapOf<Int, String>()
            val order = mutableListOf<Int>()
            val tabLines = mutableListOf<Pair<Int, String>>()
            val tabColours = mutableMapOf<Pair<Int, String>, TabColour>()
            var activePaneId = 0
            for (line in lines.drop(1)) {
                when (line.substringBefore(' ')) {
                    "P", "S" -> {
                        val parts = line.split(' ', limit = 5)
                        val id = parts[1].toInt()
                        parents[id] = parts[2].toInt()
                        weights[id] = parts[3].toFloat()
                        built[id] = if (parts[0] == "P") {
                            parts.getOrNull(4)?.let { activeRel[id] = it }
                            TabPane()
                        } else {
                            val axis =
                                if (parts.getOrNull(4)?.trim() == "V") SplitOrientation.Vertical
                                else SplitOrientation.Horizontal
                            Split(emptyList(), emptyList(), axis)
                        }
                        order += id
                    }
                    "T" -> {
                        val rest = line.removePrefix("T ")
                        tabLines += rest.substringBefore(' ').toInt() to rest.substringAfter(' ')
                    }
                    "A" -> activePaneId = line.removePrefix("A ").trim().toInt()
                    "C" -> {
                        val parts = line.split(' ', limit = 4)
                        val colour = TabColour.entries.firstOrNull { it.name == parts.getOrNull(2) }
                        val paneId = parts.getOrNull(1)?.toIntOrNull()
                        if (colour != null && paneId != null && parts.size == 4) tabColours[paneId to parts[3]] = colour
                    }
                }
            }
            for (id in order) {
                val parentId = parents[id] ?: continue
                val parent = built[parentId] as? Split ?: continue
                parent.children.add(checkNotNull(built[id]))
                parent.weights.add(weights[id] ?: 0f)
            }
            // Strip order is the only order recorded, so an oversized
            // layout keeps its tail rather than its head.
            for ((paneId, rel) in tabLines.takeLast(MAX_RESTORED_TABS)) {
                val pane = built[paneId] as? TabPane ?: continue
                if (!File(vaultDir, rel).isFile) continue
                pane.tabs.add(state.newTab(rel).also { it.colour = tabColours[paneId to rel] })
            }
            for ((id, node) in built) {
                if (node !is TabPane) continue
                node.active = node.tabs.firstOrNull { it.relPath == activeRel[id] } ?: node.tabs.firstOrNull()
            }
            val rootId = checkNotNull(order.firstOrNull { parents[it] == -1 })
            state.root = state.prune(checkNotNull(built[rootId])) ?: TabPane()
            val wanted = built[activePaneId] as? TabPane
            state.activePane =
                if (wanted != null && state.panes().any { it === wanted }) wanted
                else state.firstPaneOf(state.root)
            return state
        }
    }
}

private fun stemOf(relPath: String): String = relPath.substringAfterLast('/').removeSuffix(".md")

private fun normalised(weights: List<Float>): List<Float> {
    val total = weights.sum()
    return if (total <= 0f) List(weights.size) { 1f / weights.size } else weights.map { it / total }
}

/// The editor's guarded write, apart from any screen so leaving a tab
/// and closing one save exactly as autosave does. False means the file
/// changed under the buffer: nothing is written, and the buffer stays
/// dirty and stale until someone answers for it (ADR 0012).
fun flushEditor(vaultDir: String, editor: EditorState, onSaved: ((String) -> Unit)? = null): Boolean = synchronized(DocumentEditGate.monitor) {
    if (!editor.dirty) return true
    val relPath = editor.openRelPath ?: return true
    val file = File(vaultDir, relPath)
    val document = editor.document()
    val onDisk = if (file.exists()) contentHash(file.readBytes()) else null
    if (!mayOverwrite(editor.hashAtOpen, onDisk)) {
        editor.staleBuffer = true
        return false
    }
    file.writeText(document)
    editor.hashAtOpen = contentHash(document)
    editor.dirty = false
    editor.staleBuffer = false
    // A tab losing the front, a tab closing, a window closing: all of
    // them write, and until this round none of them said so. The note
    // reached the disk and nothing else in the app knew, which is the
    // same silence `write` was fixed for and one call further out.
    onSaved?.invoke(relPath)
    true
}
