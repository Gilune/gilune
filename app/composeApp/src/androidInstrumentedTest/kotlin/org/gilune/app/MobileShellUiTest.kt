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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileShellUiTest {
    private val resources = UiTestResources()
    private val rule = createDeviceComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(rule)

    private fun freshState(): GiluneState = resources.state(resources.directory("gilune-ui").path)

    private fun awaitText(text: String) {
        rule.waitUntil(WAIT) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun dismissComposer() {
        val dismiss = rule.onNodeWithTag("composer-dismiss")
        rule.waitUntil(WAIT) { dismiss.isDisplayed() }
        dismiss.performClick()
    }

    private fun openedState(): GiluneState {
        val state = freshState()
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
        awaitText("Nothing captured yet.")
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

    // ------------------------------------------------------------------
    // Letting go of the composer (ADR 0017 amendment 2026-09-04)
    //
    // What these assert is **focus**, which is the link this round adds.
    // The bottom bar's own rule is `!isImeVisible` (`MainActivity`), and
    // whether a keyboard is on screen is the platform's answer to where
    // focus is — the one thing MS1 measured as the least deterministic
    // part of this rig. So the chain is pinned at the end we own, and the
    // platform's half is left to the platform.
    // ------------------------------------------------------------------

    @Test
    fun committingLetsGoOfTheComposer() {
        val state = openedState()
        rule.setContent { GiluneTheme(dark = false) { Home(state) } }
        rule.waitUntil(WAIT) { state.gilune.value != null }

        rule.onNodeWithTag("composer").performTextInput("Remember the milk")
        rule.onNodeWithTag("composer").assertIsFocused()
        rule.onNodeWithTag("commit").performClick()

        rule.waitUntil(WAIT) { state.recent.size == 1 }
        // The caret is gone, so the keyboard goes with it and the bottom
        // bar comes back. Before this, the composer kept focus and the
        // only way out was the system back gesture.
        rule.onNodeWithTag("composer").assertIsNotFocused()
        rule.onNodeWithTag("composer-dismiss").assertDoesNotExist()
    }

    @Test
    fun theDismissControlIsDrawnOnlyWhileTheComposerHasFocus() {
        val state = openedState()
        rule.setContent { GiluneTheme(dark = false) { Home(state) } }

        // Not a permanent button: with nothing being typed there is
        // nothing for it to dismiss.
        rule.onNodeWithTag("composer-dismiss").assertDoesNotExist()
        rule.onNodeWithTag("composer").performClick()
        rule.onNodeWithTag("composer-dismiss").assertIsDisplayed()
    }

    @Test
    fun dismissingKeepsTheDraftAndCapturesNothing() {
        val state = openedState()
        rule.setContent { GiluneTheme(dark = false) { Home(state) } }
        rule.waitUntil(WAIT) { state.gilune.value != null }

        rule.onNodeWithTag("composer").performTextInput("half a thought")
        dismissComposer()

        rule.onNodeWithTag("composer").assertIsNotFocused()
        rule.onNodeWithTag("composer").assertTextContains("half a thought")
        assertTrue(state.recent.isEmpty(), "dismissing is not committing")
    }

    /// **Second-order.** Dismissing must not be a one-way door: the
    /// composer takes focus again, the control comes back with it, and
    /// the words continue where they stopped.
    @Test
    fun theComposerCanBeTypedIntoAgainAfterDismissing() {
        val state = openedState()
        rule.setContent { GiluneTheme(dark = false) { Home(state) } }
        rule.waitUntil(WAIT) { state.gilune.value != null }

        rule.onNodeWithTag("composer").performTextInput("half")
        dismissComposer()
        rule.onNodeWithTag("composer").assertIsNotFocused()

        rule.onNodeWithTag("composer").performTextInput(" a thought")
        rule.onNodeWithTag("composer").assertIsFocused()
        rule.onNodeWithTag("composer").assertTextContains("half a thought")
        rule.onNodeWithTag("composer-dismiss").assertIsDisplayed()
    }

    /// **Adversarial.** A tick with nothing to commit captures nothing
    /// *and takes nothing away*: somebody who taps it mid-thought keeps
    /// their keyboard. The button is disabled, which is how the rule is
    /// kept; the guard inside its click is what keeps it if the button
    /// is ever enabled for another reason.
    @Test
    fun aTickWithNothingToCommitTakesNothingAway() {
        val state = openedState()
        rule.setContent { GiluneTheme(dark = false) { Home(state) } }
        rule.waitUntil(WAIT) { state.gilune.value != null }

        rule.onNodeWithTag("composer").performClick()
        rule.onNodeWithTag("composer").assertIsFocused()
        rule.onNodeWithTag("commit").assertIsNotEnabled()
        rule.onNodeWithTag("commit").performClick()

        rule.onNodeWithTag("composer").assertIsFocused()
        rule.onNodeWithTag("composer-dismiss").assertIsDisplayed()
        assertTrue(state.recent.isEmpty())
    }

    @Test
    fun aTickFromReadReachesTheBytes() {
        val state = freshState()
        val note = File(state.vaultDir, "list.md")
        note.parentFile?.mkdirs()
        note.writeText("---\ngilune-id: abc\n---\n# Shopping\n\n- [ ] milk\n- [ ] bread\n")

        rule.setContent { GiluneTheme(dark = false) { ReadScreen(state, "list.md") } }

        awaitText("Shopping")
        rule.onNodeWithText("Shopping").assertExists()
        rule.onAllNodes(hasContentDescription("task not done"))[0].performClick()

        rule.waitUntil(WAIT) { note.readText().contains("- [x] milk") }
        val after = note.readText()
        assertTrue(after.contains("- [x] milk"), after)
        assertTrue(after.contains("- [ ] bread"), "only the tapped line moved")
        assertTrue(after.startsWith("---\ngilune-id: abc\n---\n"), "front matter survived the tick")
        assertFalse(after.contains("done:"), "no plugin, no stamp — the flip is all the shell does")
        rule.onNodeWithContentDescription("task done").assertExists()
    }

    /// The other half of that gate, and the bug MB3 found: with the tasks
    /// plugin on, a tick from **Read** must stamp `done:` exactly as a
    /// tick in either editor does. Read used to take the default chain and
    /// write the flip alone, so the same box ticked one screen along
    /// produced different bytes.
    @Test
    fun aTickFromReadStampsDoneWhenTheTasksPluginIsOn() {
        val state = freshState()
        state.plugins.add(installedTasksPlugin())
        val note = File(state.vaultDir, "list.md")
        note.parentFile?.mkdirs()
        note.writeText("---\ngilune-id: abc\n---\n# Shopping\n\n- [ ] milk\n- [ ] bread\n")

        rule.setContent { GiluneTheme(dark = false) { ReadScreen(state, "list.md") } }

        awaitText("Shopping")
        rule.onNodeWithText("Shopping").assertExists()
        rule.onAllNodes(hasContentDescription("task not done"))[0].performClick()

        val today = LocalDate.now().toString()
        rule.waitUntil(WAIT) { note.readText().contains("done:") }
        val after = note.readText()
        assertTrue(after.contains("- [x] milk done:$today"), after)
        assertTrue(after.contains("- [ ] bread"), "only the tapped line moved")
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

    /// The product's mark is in the app, not only on the launcher
    /// (ADR 0017 amendment 2026-09-04). About is where it says who this
    /// is, so the mark belongs beside the words that say it.
    @Test
    fun aboutWearsTheProductsMark() {
        val state = openedState()
        rule.setContent {
            GiluneTheme(dark = false) {
                SettingsScreen(
                    state = state,
                    route = SettingsRoute.About,
                    onNavigate = {},
                    theme = ThemeChoice.System,
                    onThemeChange = {},
                    idleTimeout = IdleTimeout.Default,
                    onIdleTimeoutChange = {},
                    onExport = {},
                )
            }
        }
        rule.onNodeWithTag("logo").assertIsDisplayed()
        // And the wording it stands beside is unchanged.
        rule.onNodeWithText("Gilune").assertExists()
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
