// The phone shell on a device (ADR 0017 MB1). Android had no UI-test net
// before MB0; these are the first four knots in it, and they cover the
// claims the stage actually makes: home is a capture surface, a typed
// thought reaches the feed, a tick from Read reaches the bytes, and the
// plugin screens are rendered from the registry rather than hard-coded.

package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileShellUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun scratchVault(): String = Files.createTempDirectory("gilune-ui").toString()

    private fun openedState(): GiluneState {
        val state = GiluneState(scratchVault(), scope)
        state.openVault()
        return state
    }

    @Test
    fun homeIsAComposerWithTheFeedAboveIt() {
        val state = openedState()
        rule.setContent { GiluneTheme(dark = false) { Home(state) } }
        rule.onNodeWithTag("composer").assertIsDisplayed()
        rule.onNodeWithTag("commit").assertExists()
        // Nothing captured yet, so the feed says so rather than sitting blank.
        rule.onNodeWithText("Nothing captured yet.").assertExists()
    }

    @Test
    fun aTypedCaptureLandsInTheFeed() {
        val state = openedState()
        rule.setContent { GiluneTheme(dark = false) { Home(state) } }
        rule.waitUntil(WAIT) { state.gilune.value != null }

        rule.onNodeWithTag("composer").performTextInput("Remember the milk")
        rule.onNodeWithTag("commit").performClick()

        rule.waitUntil(WAIT) {
            rule.onAllNodes(hasText("Remember the milk")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("feed").assertExists()
        assertEquals(1, state.recent.size)
    }

    @Test
    fun aTickFromReadReachesTheBytes() {
        val state = GiluneState(scratchVault(), scope)
        val note = File(state.vaultDir, "list.md")
        note.parentFile?.mkdirs()
        note.writeText("---\ngilune-id: abc\n---\n# Shopping\n\n- [ ] milk\n- [ ] bread\n")

        rule.setContent { GiluneTheme(dark = false) { ReadScreen(state, "list.md") } }

        rule.onNodeWithText("Shopping").assertExists()
        rule.onAllNodes(hasContentDescription("task not done"))[0].performClick()

        rule.waitUntil(WAIT) { note.readText().contains("- [x] milk") }
        val after = note.readText()
        assertTrue(after.contains("- [x] milk"), after)
        assertTrue(after.contains("- [ ] bread"), "only the tapped line moved")
        assertTrue(after.startsWith("---\ngilune-id: abc\n---\n"), "front matter survived the tick")
        rule.onNodeWithContentDescription("task done").assertExists()
    }

    @Test
    fun settingsOpensAPluginsOwnScreenFromTheRegistry() {
        val state = openedState()
        rule.waitUntil(WAIT) { state.plugins.isNotEmpty() }
        rule.setContent {
            GiluneTheme(dark = false) {
                var route by remember { mutableStateOf<SettingsRoute>(SettingsRoute.Plugins) }
                SettingsScreen(
                    state = state,
                    route = route,
                    onNavigate = { route = it },
                    theme = ThemeChoice.System,
                    onThemeChange = {},
                    idleTimeout = IdleTimeout.Default,
                    onIdleTimeoutChange = {},
                    onExport = {},
                )
            }
        }
        // The list is data: the shell declares Voice capture on open, so
        // the row exists because the registry says so.
        rule.onNodeWithText("Voice capture").assertExists()
        rule.onNodeWithText("Voice capture").performClick()
        // Its own screen shows the permission the desktop policy governs.
        rule.onNodeWithText("Permissions").assertExists()
        rule.onNodeWithText("platform.audio-record").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun Home(state: GiluneState) {
        var draft by remember { mutableStateOf("") }
        CaptureScreen(
            state = state,
            draft = draft,
            onDraftChange = { draft = it },
            onCommit = {
                val text = draft.trim()
                draft = ""
                if (text.isNotEmpty()) state.captureText(text)
            },
            onOpenNote = {},
        )
    }

    private companion object {
        const val WAIT = 10_000L
    }
}
