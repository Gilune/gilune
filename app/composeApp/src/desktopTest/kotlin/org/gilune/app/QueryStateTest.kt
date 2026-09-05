package org.gilune.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QueryStateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingTheNextPageAppendsRowsAndChangingTheQueryClearsThem() {
        val vault = IndexedVault()
        repeat(53) { vault.write("$it.md", "needle") }
        val state = GiluneState(vault.dir, CoroutineScope(Dispatchers.Default))
        state.gilune.value = vault.gilune
        val query = mutableStateOf("needle")
        compose.setContent {
            val page = rememberPagedSearch(state, query.value, listOf(NOTE_SEARCH_TYPE))
            Column {
                Text("${page.listing.hits.size}/${page.listing.total}")
                if (page.listing.next != null) TextButton(onClick = page.more) { Text("More") }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("50/53").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("More").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("53/53").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("More").assertDoesNotExist()
        compose.runOnIdle { query.value = "missing" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("0/0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("53/53").assertDoesNotExist()
    }

    @Test
    fun aSlowOldQueryCannotReplaceANewerAnswerOrBlockComposition() {
        val key = mutableStateOf("old")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            compose.setContent {
                val requested = key.value
                val uiThread = Thread.currentThread()
                val work = rememberQueryWork(requested) {
                    assertNotEquals(uiThread, Thread.currentThread())
                    if (requested == "old") {
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        finished.countDown()
                    }
                    requested
                }.value
                Text(work.value ?: "loading")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("loading").assertIsDisplayed()
            compose.runOnIdle { key.value = "new" }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("new").fetchSemanticsNodes().isNotEmpty() }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            compose.onNodeWithText("new").assertIsDisplayed()
            compose.onNodeWithText("old").assertDoesNotExist()
        } finally { release.countDown() }
    }

    @Test
    fun anIndexFailureShowsAnErrorAndRetryInsteadOfNoMatches() {
        compose.setContent {
            val day = rememberQueryDay()
            val work = rememberQueryWork(day) { throw SearchIndexPending(listOf("text: pending")) }.value
            QueryNotice(work.loading, work.error) {}
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Retry search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Search is waiting for indexing. Retry to update it.").assertIsDisplayed()
    }
}
