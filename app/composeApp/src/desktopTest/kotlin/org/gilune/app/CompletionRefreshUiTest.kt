package org.gilune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Rule
import org.junit.rules.RuleChain
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** Holds native query work until the test explicitly lets it finish. */
private class HeldQueries : CoroutineDispatcher() {
    val pending = LinkedBlockingQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
    fun completeNext() { checkNotNull(pending.poll()) { "Expected a queued query" }.run() }
}

class CompletionRefreshUiTest {
    private val resources = UiTestResources()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    @Test fun refreshingTheSameQueryKeepsItsOptionUntilTheNewResultArrives() {
        val vault = resources.indexed()
        val state = GiluneState(vault.dir, resources.scope)
        state.gilune.value = vault.gilune
        state.plugins.addAll(vault.gilune.plugins())
        val queries = HeldQueries()
        compose.setContent {
            CompositionLocalProvider(LocalQueryDispatcher provides queries) {
                GiluneTheme(dark = false) {
                    val ui = rememberEditorCompletions(state, "- [ ] Work /", 12, true) { _, _, _ -> }
                    Column { EditorCompletionMenu(ui, state) }
                }
            }
        }
        compose.waitUntil(5_000) { queries.pending.isNotEmpty() }
        queries.completeNext()
        compose.waitForFirstEditorOption("Due date")
        compose.runOnIdle { state.searchRefresh.value++ }
        compose.waitUntil(5_000) { queries.pending.isNotEmpty() }
        // A real save/index refresh must not remove a row under a pending click.
        compose.onNodeWithTag("completion-0").assertExists().assertHasClickAction()
        queries.completeNext()
        compose.waitForFirstEditorOption("Due date")
    }

    @Test fun changingTheTextImmediatelyRemovesTheOldOptions() {
        val vault = resources.indexed()
        val state = GiluneState(vault.dir, resources.scope)
        state.gilune.value = vault.gilune
        state.plugins.addAll(vault.gilune.plugins())
        val queries = HeldQueries()
        var text by mutableStateOf("- [ ] Work /")
        var inserted = ""
        var chooseOld: ((Int) -> Unit)? = null
        compose.setContent {
            CompositionLocalProvider(LocalQueryDispatcher provides queries) {
                GiluneTheme(dark = false) {
                    val ui = rememberEditorCompletions(state, text, text.length, true) { _, _, value -> inserted = value }
                    SideEffect { if (chooseOld == null && ui.offers != null) chooseOld = ui.choose }
                    EditorCompletionMenu(ui, state)
                }
            }
        }
        compose.waitUntil(5_000) { queries.pending.isNotEmpty() }
        queries.completeNext()
        compose.waitForFirstEditorOption("Due date")
        compose.runOnIdle { text = "different text" }
        compose.waitUntil(5_000) { queries.pending.isNotEmpty() }
        compose.onNodeWithTag("completion-0").assertDoesNotExist()
        // A callback captured before the edit cannot insert into the new text.
        compose.runOnIdle { checkNotNull(chooseOld).invoke(0) }
        queries.completeNext()
        compose.waitForIdle()
        assertEquals("", inserted)
    }
}
