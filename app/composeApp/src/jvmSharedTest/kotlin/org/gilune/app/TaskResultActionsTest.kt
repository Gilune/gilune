package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.gilune.plugins.tasks.TaskHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskResultActionsTest {
    private val task = "- [ ] Parent"
    private val original = "---\ngilune-id: source\ncustom: preserved\n---\n$task\n  owner: @alice\n  - [ ] Child\n"
    private val hit = TaskHit("source.md", "Source", 1, "$task\n  owner: @alice", null, null)

    private fun fixture(run: (GiluneState, File) -> Unit) {
        val root = Files.createTempDirectory("gilune-task-result").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = root.resolve("source.md")
        file.writeText(original)
        try { run(GiluneState(root.path, scope), file) }
        finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test
    fun completingAResultPreservesFrontmatterAndChildrenAndARepeatedStaleClickCannotUndoIt() = fixture { state, file ->
        toggleTaskResult(state, hit)
        assertEquals(original.replace(task, "- [x] Parent"), file.readText())
        toggleTaskResult(state, hit)
        assertEquals(original.replace(task, "- [x] Parent"), file.readText())
        assertTrue(state.message.value.contains("changed"))
        toggleTaskResult(state, hit.copy(text = "- [x] Parent\n  owner: @alice"))
        assertEquals(original, file.readText())
    }

    @Test
    fun aMovedOrChangedTaskRefusesTheClickWithoutTouchingTheFile() = fixture { state, file ->
        val changed = original.replace(task, "intro\n- [ ] Renamed")
        file.writeText(changed)
        toggleTaskResult(state, hit)
        assertEquals(changed, file.readText())
        assertTrue(state.message.value.contains("changed"))
    }

    @Test
    fun anUnsavedSourceBufferPreventsAnOverviewWrite() = fixture { state, file ->
        val buffer = object : LiveDocumentBuffer {
            override val documentFile = file.absoluteFile.normalize().path
            override val hasUnsavedText = true
        }
        DocumentEditGate.register(buffer)
        toggleTaskResult(state, hit)
        assertEquals(original, file.readText())
        assertTrue(state.message.value.contains("unsaved edits"))
        assertTrue(buffer.hasUnsavedText)
    }
}
