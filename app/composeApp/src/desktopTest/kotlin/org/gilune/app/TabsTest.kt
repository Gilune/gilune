// Tab and pane rules are pure, so they are tested without composition
// (ADR 0014): where an open lands, which tab takes the front when one
// closes, what a refused save does to a close, how a pane dissolves, and
// what a restored layout is allowed to believe about the disk.

package org.gilune.app

import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TabsTest {
    @Test
    fun closeToRightAndCloseOthersKeepTheChosenTabAndSaveEdits() {
        val dir = vault()
        val tabs = TabsState(dir)
        val a = tabs.open(note(dir, "a.md"))
        val b = tabs.open(note(dir, "b.md"))
        val c = tabs.open(note(dir, "c.md"))
        edit(c, "saved before closing")
        val pane = tabs.activePane
        assertTrue(tabs.closeToRight(pane, b))
        assertEquals(listOf(a, b), pane.tabs.toList())
        assertTrue(onDisk(dir, "c.md").endsWith("saved before closing"))
        assertTrue(tabs.closeOthers(pane, b))
        assertEquals(listOf(b), pane.tabs.toList())
        assertSame(b, tabs.activeTab)
    }

    @Test
    fun bulkCloseStopsAtAConflictAndPreservesTheUnsavedBuffer() {
        val dir = vault()
        val tabs = TabsState(dir)
        val keep = tabs.open(note(dir, "keep.md"))
        val conflict = tabs.open(note(dir, "conflict.md"))
        edit(conflict, "unsaved text")
        File(dir, "conflict.md").writeText("external edit")
        assertFalse(tabs.closeOthers(tabs.activePane, keep))
        assertTrue(conflict in tabs.activePane.tabs)
        assertEquals("unsaved text", conflict.editor.text.text)
        assertEquals("external edit", onDisk(dir, "conflict.md"))
    }

    @Test
    fun tabColoursSurviveRestoringPathsWithSpacesAndCanBeCleared() {
        val dir = vault()
        val tabs = TabsState(dir)
        tabs.open(note(dir, "a note.md")).colour = TabColour.Purple
        val restored = TabsState.restore(dir, tabs.serialise())
        assertEquals(TabColour.Purple, restored.activeTab?.colour)
        restored.activeTab!!.colour = null
        assertNull(TabsState.restore(dir, restored.serialise()).activeTab?.colour)
    }

    @Test
    fun creatingANoteStartsEditingRegardlessOfItsPreviousMode() {
        val dir = vault()
        val tabs = TabsState(dir)
        val tab = tabs.open(note(dir, "Untitled.md"))
        tab.editor.reading = true
        assertSame(tab, tabs.openForEditing("Untitled.md"))
        assertFalse(tab.editor.reading)
        assertTrue(tab.editor.focusTitle)
    }
    private fun vault(): String = Files.createTempDirectory("gilune-tabs").toFile().absolutePath

    private fun note(dir: String, rel: String, body: String = "body"): String {
        val file = File(dir, rel)
        file.parentFile?.mkdirs()
        file.writeText("---\ngilune-id: ${stem(rel)}\n---\n$body")
        return rel
    }

    private fun stem(rel: String) = rel.substringAfterLast('/').removeSuffix(".md")

    private fun edit(tab: Tab, body: String) {
        tab.editor.text = TextFieldValue(body)
        tab.editor.dirty = true
    }

    private fun onDisk(dir: String, rel: String) = File(dir, rel).readText()

    // The leaf type is TabPane, not Pane: the settings screen owns that
    // name in this package.
    private fun shape(state: TabsState): List<List<String>> =
        state.panes().map { pane -> pane.tabs.map { it.relPath } }

    @Test
    fun openingANoteThatIsAlreadyOpenFocusesItsTab() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        val first = tabs.open("a.md")
        tabs.open("b.md")

        val again = tabs.open("a.md")

        assertSame(first, again)
        assertEquals(2, tabs.activePane.tabs.size)
        assertSame(first, tabs.activeTab)
        assertSame(first.editor, tabs.activeEditor)
    }

    @Test
    fun aNewTabLandsImmediatelyAfterTheActiveOne() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md", "d.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        tabs.open("b.md")
        tabs.open("c.md")
        tabs.select(tabs.activePane, a)

        tabs.open("d.md")

        assertEquals(listOf("a.md", "d.md", "b.md", "c.md"), tabs.activePane.tabs.map { it.relPath })
        assertEquals("d.md", tabs.activeTab?.relPath)
    }

    @Test
    fun closingATabHandsTheFrontToTheOneAfterItThenToTheOneBefore() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        val b = tabs.open("b.md")
        val c = tabs.open("c.md")
        tabs.select(tabs.activePane, b)

        assertTrue(tabs.closeActive())
        assertSame(c, tabs.activeTab)

        assertTrue(tabs.closeActive())
        assertSame(a, tabs.activeTab)
        assertEquals(listOf("a.md"), tabs.activePane.tabs.map { it.relPath })
    }

    @Test
    fun selectingByIndexPicksTheNthTabAndIgnoresAnythingOutOfRange() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        val c = tabs.open("c.md")

        tabs.selectIndex(2)
        assertEquals("b.md", tabs.activeTab?.relPath)

        tabs.selectIndex(9)
        assertEquals("b.md", tabs.activeTab?.relPath)

        tabs.selectIndex(3)
        assertSame(c, tabs.activeTab)
    }

    @Test
    fun cyclingRunsInStripOrderAndWrapsBothWays() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        tabs.open("c.md")

        tabs.cycle(forward = true)
        assertEquals("a.md", tabs.activeTab?.relPath)

        tabs.cycle(forward = false)
        assertEquals("c.md", tabs.activeTab?.relPath)
    }

    @Test
    fun closingADirtyTabWritesItBeforeItGoes() {
        val dir = vault()
        note(dir, "a.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        edit(a, "edited")

        assertTrue(tabs.closeActive())

        assertEquals("---\ngilune-id: a\n---\nedited", onDisk(dir, "a.md"))
        assertFalse(a.editor.dirty)
    }

    @Test
    fun aTabWhoseFileChangedUnderneathRefusesToCloseAndAsksForAnAnswer() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        tabs.open("b.md")
        edit(a, "mine")
        File(dir, "a.md").writeText("theirs")

        assertFalse(tabs.close(tabs.activePane, a))

        assertTrue(a.editor.staleBuffer)
        assertTrue(a.editor.dirty)
        assertSame(a, tabs.activeTab, "the tab that refuses to close comes to the front")
        assertEquals(2, tabs.activePane.tabs.size)
        assertEquals("theirs", onDisk(dir, "a.md"))
    }

    @Test
    fun leavingATabFlushesItsEdit() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        edit(a, "typed")

        tabs.open("b.md")

        assertEquals("---\ngilune-id: a\n---\ntyped", onDisk(dir, "a.md"))
        assertFalse(a.editor.dirty)
    }

    @Test
    fun closingTheLastTabLeavesOneEmptyPane() {
        val dir = vault()
        note(dir, "a.md")
        val tabs = TabsState(dir)
        tabs.open("a.md")

        assertTrue(tabs.closeActive())

        val root = assertNotNull(tabs.root as? TabPane)
        assertTrue(root.tabs.isEmpty())
        assertSame(root, tabs.activePane)
        assertNull(tabs.activeTab)
        assertFalse(tabs.closeActive())
    }

    @Test
    fun splittingRightMovesTheActiveTabIntoANewPane() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        val b = tabs.open("b.md")

        assertTrue(tabs.splitRight())

        val root = assertNotNull(tabs.root as? Split)
        assertEquals(SplitOrientation.Horizontal, root.orientation)
        assertEquals(listOf(0.5f, 0.5f), root.weights.toList())
        assertEquals(listOf(listOf("a.md"), listOf("b.md")), shape(tabs))
        assertSame(b, tabs.activeTab, "the moved tab is the same tab, still in front")
        assertSame(a, (root.children[0] as TabPane).active)
    }

    @Test
    fun aPaneHoldingOneTabCannotSplitItAway() {
        val dir = vault()
        note(dir, "a.md")
        val tabs = TabsState(dir)
        tabs.open("a.md")

        assertFalse(tabs.splitRight())

        assertTrue(tabs.root is TabPane)
    }

    @Test
    fun openingToTheSideMovesTheTabRatherThanCopyingIt() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        tabs.open("b.md")

        val moved = tabs.openToSide("a.md")

        assertSame(a, moved, "the note keeps its one buffer")
        assertEquals(listOf(listOf("b.md"), listOf("a.md")), shape(tabs))
        assertEquals(2, tabs.panes().sumOf { it.tabs.size }, "moved, never copied")
        assertSame(a, tabs.activeTab)
        assertSame(a, tabs.panes()[1].active)
    }

    @Test
    fun openingToTheSideAgainReusesTheSidePane() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        tabs.openToSide("a.md")
        tabs.select(tabs.panes()[0], tabs.panes()[0].tabs.first())

        val c = tabs.openToSide("c.md")

        assertEquals(listOf(listOf("b.md"), listOf("a.md", "c.md")), shape(tabs))
        assertSame(c, tabs.panes()[1].active)
    }

    @Test
    fun aSplitPaneEmptiedOfTabsDissolvesAndItsSiblingTakesTheSpace() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        tabs.open("b.md")
        tabs.splitRight()

        assertTrue(tabs.closeActive())

        val root = assertNotNull(tabs.root as? TabPane)
        assertEquals(listOf("a.md"), root.tabs.map { it.relPath })
        assertSame(root, tabs.activePane)
        assertSame(a, tabs.activeTab)
    }

    @Test
    fun tabsSharingAStemGainTheFolderHoldingThem() {
        val dir = vault()
        note(dir, "Projects/plan.md")
        note(dir, "Work/plan.md")
        note(dir, "plan.md")
        note(dir, "solo.md")
        val tabs = TabsState(dir)
        val projects = tabs.open("Projects/plan.md")
        val work = tabs.open("Work/plan.md")
        val root = tabs.open("plan.md")
        val solo = tabs.open("solo.md")

        assertEquals("Projects/plan", tabs.label(projects))
        assertEquals("Work/plan", tabs.label(work))
        assertEquals("/plan", tabs.label(root), "a clashing note at the vault root wears a bare slash")
        assertEquals("solo", tabs.label(solo))

        assertTrue(tabs.close(tabs.activePane, work))
        assertTrue(tabs.close(tabs.activePane, root))
        assertEquals("plan", tabs.label(projects), "the last of a name needs no folder")
    }

    @Test
    fun renamingANoteFollowsThroughToItsTabAndItsBuffer() {
        val dir = vault()
        note(dir, "a.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")

        tabs.noteRenamed("a.md", "Projects/renamed.md")

        assertEquals("Projects/renamed.md", a.relPath)
        assertEquals("Projects/renamed.md", a.editor.openRelPath)
    }

    @Test
    fun deletingANoteClosesItsTabWithoutWritingTheBufferBack() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        tabs.open("b.md")
        edit(a, "never saved")

        tabs.noteDeleted("a.md")

        assertEquals(listOf("b.md"), tabs.activePane.tabs.map { it.relPath })
        // The file stands in for one the user has just destroyed: had the
        // close flushed, this edit would have recreated its content.
        assertEquals("---\ngilune-id: a\n---\nbody", onDisk(dir, "a.md"))
    }

    @Test
    fun aClosedTabIsNoLongerTheDictationTarget() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        tabs.open("b.md")
        tabs.dictationTabId = a.id

        assertTrue(tabs.close(tabs.activePane, a))

        assertNull(tabs.dictationTabId)
    }

    /// Three tabs in one pane, the last of them split off to the right:
    /// the shape every drag test below starts from, as `[a, b] | [c]`.
    private fun split(dir: String): Triple<TabsState, TabPane, TabPane> {
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        tabs.open("c.md")
        assertTrue(tabs.splitRight())
        return Triple(tabs, tabs.panes()[0], tabs.panes()[1])
    }

    private fun tabIn(pane: TabPane, rel: String): Tab = pane.tabs.first { it.relPath == rel }

    @Test
    fun reorderingWithinAStripDropsTheTabAtTheGapItWasLeftOn() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        val c = tabs.open("c.md")
        val pane = tabs.activePane

        tabs.moveTab(c, pane, pane, 0)
        assertEquals(listOf("c.md", "a.md", "b.md"), pane.tabs.map { it.relPath })

        // Gap two of [c, a, b] is the space after a, and removing c first
        // shifts it left by one.
        tabs.moveTab(c, pane, pane, 2)
        assertEquals(listOf("a.md", "c.md", "b.md"), pane.tabs.map { it.relPath })

        tabs.moveTab(c, pane, pane, 9)
        assertEquals(listOf("a.md", "b.md", "c.md"), pane.tabs.map { it.relPath })

        assertSame(c, pane.active, "reordering never changes which tab is in front")
        assertSame(pane, tabs.activePane)
    }

    @Test
    fun reorderingATabThatIsNotInFrontLeavesTheFrontAndItsBufferAlone() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        tabs.open("b.md")
        val c = tabs.open("c.md")
        val pane = tabs.activePane
        edit(a, "typed")

        tabs.moveTab(a, pane, pane, 3)

        assertEquals(listOf("b.md", "c.md", "a.md"), pane.tabs.map { it.relPath })
        assertSame(c, tabs.activeTab)
        // A reorder is not a switch: nothing was deactivated, so nothing
        // was written.
        assertTrue(a.editor.dirty)
        assertEquals("---\ngilune-id: a\n---\nbody", onDisk(dir, "a.md"))
    }

    @Test
    fun movingATabToAnotherPaneBringsItAndThatPaneToTheFront() {
        val dir = vault()
        val (tabs, left, right) = split(dir)
        val a = tabIn(left, "a.md")
        val b = tabIn(left, "b.md")

        tabs.moveTab(a, left, right, 0)

        assertEquals(listOf(listOf("b.md"), listOf("a.md", "c.md")), shape(tabs))
        assertSame(a, right.active)
        assertSame(right, tabs.activePane)
        assertSame(b, left.active, "the pane it left keeps the neighbour it fell back to")
    }

    @Test
    fun arrivingInAPaneFlushesTheTabItPushesOffTheFront() {
        val dir = vault()
        val (tabs, left, right) = split(dir)
        val c = tabIn(right, "c.md")
        edit(c, "typed")

        tabs.moveTab(tabIn(left, "a.md"), left, right, 0)

        assertEquals("---\ngilune-id: c\n---\ntyped", onDisk(dir, "c.md"))
        assertFalse(c.editor.dirty)
    }

    @Test
    fun aRefusedFlushOnTheDisplacedTabMarksItStaleAndTheMoveStillHappens() {
        val dir = vault()
        val (tabs, left, right) = split(dir)
        val a = tabIn(left, "a.md")
        val c = tabIn(right, "c.md")
        edit(c, "mine")
        File(dir, "c.md").writeText("theirs")

        tabs.moveTab(a, left, right, 0)

        assertTrue(c.editor.staleBuffer)
        assertTrue(c.editor.dirty)
        assertEquals("theirs", onDisk(dir, "c.md"), "a refused flush writes nothing")
        assertEquals(listOf(listOf("b.md"), listOf("a.md", "c.md")), shape(tabs))
        assertSame(a, right.active, "the drop is not undone by the buffer it displaced")
    }

    @Test
    fun movingAPanesOnlyTabDissolvesThePaneItLeaves() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        assertTrue(tabs.splitRight())
        val left = tabs.panes()[0]
        val right = tabs.panes()[1]
        val a = tabIn(left, "a.md")

        tabs.moveTab(a, left, right, 1)

        val root = assertNotNull(tabs.root as? TabPane)
        assertSame(right, root)
        assertEquals(listOf("b.md", "a.md"), root.tabs.map { it.relPath })
        assertSame(a, tabs.activeTab)
        assertSame(right, tabs.activePane)
    }

    @Test
    fun splittingWithADraggedTabHalvesTheTargetAndTakesTheTab() {
        val dir = vault()
        val (tabs, left, right) = split(dir)
        val a = tabIn(left, "a.md")

        assertTrue(tabs.splitWith(a, left, right))

        assertEquals(listOf(listOf("b.md"), listOf("c.md"), listOf("a.md")), shape(tabs))
        val root = assertNotNull(tabs.root as? Split)
        listOf(0.5f, 0.25f, 0.25f).forEachIndexed { i, expected ->
            assertEquals(expected, root.weights[i], 0.0001f)
        }
        assertSame(a, tabs.activeTab)
        assertSame(tabs.panes()[2], tabs.activePane)
    }

    @Test
    fun splittingAPanesOnlyTabOntoAnotherPaneDissolvesTheSource() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        assertTrue(tabs.splitRight())
        val left = tabs.panes()[0]
        val right = tabs.panes()[1]

        assertTrue(tabs.splitWith(tabIn(left, "a.md"), left, right))

        val root = assertNotNull(tabs.root as? Split)
        assertEquals(2, root.children.size, "the source dissolved as the new pane appeared")
        assertEquals(listOf(listOf("b.md"), listOf("a.md")), shape(tabs))
        assertEquals("a.md", tabs.activeTab?.relPath)
    }

    @Test
    fun splittingAPanesOnlyTabAwayFromItselfIsTheLayoutItAlreadyHas() {
        val dir = vault()
        note(dir, "a.md")
        val tabs = TabsState(dir)
        val a = tabs.open("a.md")
        val pane = tabs.activePane

        assertFalse(tabs.splitWith(a, pane, pane))

        assertTrue(tabs.root is TabPane)
        assertEquals(listOf(listOf("a.md")), shape(tabs))
        assertSame(pane, tabs.activePane)
        assertSame(a, tabs.activeTab)
    }

    @Test
    fun aMovedTabIsStillTheDictationTarget() {
        val dir = vault()
        val (tabs, left, right) = split(dir)
        val a = tabIn(left, "a.md")
        tabs.dictationTabId = a.id

        tabs.moveTab(a, left, right, 0)
        assertEquals(a.id, tabs.dictationTabId)

        tabs.moveTab(a, right, right, 2)
        assertEquals(a.id, tabs.dictationTabId, "a reorder is not a close")
    }

    @Test
    fun aLayoutBuiltByDraggingRoundTripsThroughSerialisation() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md", "d.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        tabs.open("c.md")
        tabs.open("d.md")
        assertTrue(tabs.splitRight())
        val left = tabs.panes()[0]
        val right = tabs.panes()[1]
        tabs.moveTab(tabIn(left, "a.md"), left, right, 0)
        assertTrue(tabs.splitWith(tabIn(left, "b.md"), left, right))

        val restored = TabsState.restore(dir, tabs.serialise())

        assertEquals(listOf(listOf("c.md"), listOf("a.md", "d.md"), listOf("b.md")), shape(restored))
        val root = assertNotNull(restored.root as? Split)
        listOf(0.5f, 0.25f, 0.25f).forEachIndexed { i, expected ->
            assertEquals(expected, root.weights[i], 0.0001f)
        }
        assertEquals("b.md", restored.activeTab?.relPath)
    }

    /// a | b | c, a pane each, at the 0.25 / 0.25 / 0.5 the two splits
    /// that built it left behind.
    private fun threeAcross(dir: String): TabsState {
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        tabs.open("a.md")
        val b = tabs.open("b.md")
        tabs.open("c.md")
        assertTrue(tabs.splitRight())
        tabs.select(tabs.panes()[0], b)
        assertTrue(tabs.splitRight())
        return tabs
    }

    @Test
    fun draggingADividerMovesWeightBetweenItsNeighboursAndNoOneElse() {
        val dir = vault()
        val tabs = threeAcross(dir)
        val root = assertNotNull(tabs.root as? Split)

        tabs.resize(root, 0, 0.05f)

        assertEquals(0.30f, root.weights[0], 1e-5f)
        assertEquals(0.20f, root.weights[1], 1e-5f)
        assertEquals(0.50f, root.weights[2], 1e-5f, "the third pane is no part of this drag")
        assertEquals(1f, root.weights.sum(), 1e-6f, "the split still fills itself")
    }

    @Test
    fun aDividerHeldPastTheClampMovesAsFarAsItMay() {
        val dir = vault()
        val tabs = threeAcross(dir)
        val root = assertNotNull(tabs.root as? Split)

        tabs.resize(root, 0, 5f)

        assertEquals(0.35f, root.weights[0], 1e-5f, "the move is applied, not refused")
        assertEquals(MIN_PANE_WEIGHT, root.weights[1], 1e-5f)

        tabs.resize(root, 0, -5f)

        assertEquals(MIN_PANE_WEIGHT, root.weights[0], 1e-5f)
        assertEquals(0.35f, root.weights[1], 1e-5f)
        assertEquals(1f, root.weights.sum(), 1e-6f)
    }

    @Test
    fun resizingAnInnerSplitLeavesThePaneTreeAboveItAlone() {
        val dir = vault()
        val tabs = TabsState(dir)
        // Every split the shell can build is flat, so the nested tree is
        // built by hand: `resize` reads nothing but the split it is given.
        val inner = Split(listOf(TabPane(), TabPane()), listOf(0.5f, 0.5f))
        val outer = Split(listOf(TabPane(), inner), listOf(0.4f, 0.6f))
        tabs.root = outer

        tabs.resize(inner, 0, 0.2f)

        assertEquals(0.7f, inner.weights[0], 1e-5f)
        assertEquals(0.3f, inner.weights[1], 1e-5f)
        assertEquals(listOf(0.4f, 0.6f), outer.weights.toList(), "the parent is untouched")
    }

    @Test
    fun aDividerThatIsNotThereIsNotADrag() {
        val dir = vault()
        val tabs = threeAcross(dir)
        val root = assertNotNull(tabs.root as? Split)
        val before = root.weights.toList()

        listOf(-1, 2, 9).forEach { tabs.resize(root, it, 0.2f) }

        assertEquals(before, root.weights.toList())
    }

    @Test
    fun aResizedLayoutComesBackAtTheWidthsItWasLeftAt() {
        val dir = vault()
        val tabs = threeAcross(dir)
        val root = assertNotNull(tabs.root as? Split)
        tabs.resize(root, 1, 0.2f)

        val restored = TabsState.restore(dir, tabs.serialise())

        assertEquals(listOf(listOf("a.md"), listOf("b.md"), listOf("c.md")), shape(restored))
        val back = assertNotNull(restored.root as? Split)
        listOf(0.25f, 0.45f, 0.30f).forEachIndexed { i, expected ->
            assertEquals(expected, back.weights[i], 1e-5f)
        }
    }

    @Test
    fun aLayoutRoundTripsThroughSerialisation() {
        val dir = vault()
        listOf("a.md", "b.md", "c.md").forEach { note(dir, it) }
        val tabs = TabsState(dir)
        tabs.open("a.md")
        val b = tabs.open("b.md")
        tabs.open("c.md")
        tabs.splitRight()
        tabs.select(tabs.panes()[0], b)
        tabs.splitRight()

        val restored = TabsState.restore(dir, tabs.serialise())

        assertEquals(listOf(listOf("a.md"), listOf("b.md"), listOf("c.md")), shape(restored))
        val root = assertNotNull(restored.root as? Split)
        assertEquals(3, root.children.size)
        listOf(0.25f, 0.25f, 0.5f).forEachIndexed { i, expected ->
            assertEquals(expected, root.weights[i], 0.0001f)
        }
        assertEquals("b.md", restored.activeTab?.relPath, "the pane in front comes back in front")
        assertEquals("body", restored.activeTab?.editor?.text?.text, "a restored tab re-reads the file")
    }

    @Test
    fun restoringDropsATabWhoseFileHasGoneAndDissolvesThePaneItEmptied() {
        val dir = vault()
        note(dir, "a.md")
        note(dir, "b.md")
        val tabs = TabsState(dir)
        tabs.open("a.md")
        tabs.open("b.md")
        tabs.splitRight()
        val layout = tabs.serialise()
        assertTrue(File(dir, "b.md").delete())

        val restored = TabsState.restore(dir, layout)

        assertTrue(restored.root is TabPane)
        assertEquals(listOf(listOf("a.md")), shape(restored))
        assertEquals("a.md", restored.activeTab?.relPath)
        assertSame(restored.root, restored.activePane)
    }

    @Test
    fun restoringSomethingUnreadableYieldsOneEmptyPane() {
        val dir = vault()
        for (value in listOf(null, "", "not a layout at all", "gilune-tabs 1\nP one two three\n")) {
            val restored = TabsState.restore(dir, value)
            val root = assertNotNull(restored.root as? TabPane, "restored <$value>")
            assertTrue(root.tabs.isEmpty())
            assertSame(root, restored.activePane)
            assertNull(restored.activeTab)
        }
    }

    @Test
    fun restoringKeepsTheMostRecentTabsAndNoMore() {
        val dir = vault()
        val tabs = TabsState(dir)
        repeat(60) { tabs.open(note(dir, "n%02d.md".format(it))) }

        val restored = TabsState.restore(dir, tabs.serialise())

        val kept = restored.activePane.tabs.map { it.relPath }
        assertEquals(50, kept.size)
        assertEquals("n10.md", kept.first())
        assertEquals("n59.md", kept.last())
    }

    @Test
    fun theGuardedFlushRefusesOverAChangedFileAndSavesOnceTheBufferIsReopened() {
        val dir = vault()
        note(dir, "a.md")
        val editor = EditorState()

        assertTrue(flushEditor(dir, editor), "nothing open is nothing to save")

        editor.open(dir, "a.md")
        editor.text = TextFieldValue("mine")
        editor.dirty = true
        File(dir, "a.md").writeText("theirs")

        assertFalse(flushEditor(dir, editor))
        assertTrue(editor.staleBuffer)
        assertEquals("theirs", onDisk(dir, "a.md"))

        editor.open(dir, "a.md")
        editor.text = TextFieldValue("mine again")
        editor.dirty = true

        assertTrue(flushEditor(dir, editor))
        assertEquals("mine again", onDisk(dir, "a.md"))
        assertFalse(editor.dirty)
        assertFalse(editor.staleBuffer)
        assertNotNull(editor.openRelPath)
    }
}
