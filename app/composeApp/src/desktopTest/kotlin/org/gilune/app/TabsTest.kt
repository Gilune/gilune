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
