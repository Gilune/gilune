// The notice an upgrade leaves behind (ADR 0018 § 3), on the screen.
//
// `MoonshineCaptionTest` pins when the notice is *raised* and that the flag
// survives a write; this pins the half a person actually meets — that
// Settings ▸ Plugins draws it while it stands, that pressing the button
// takes it away, and that a phone with nothing to apologise for shows
// nothing at all.
//
// The store is shared with the running app, so every test here leaves it
// dismissed — which is also its default.

package org.gilune.app

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import kotlin.test.assertFalse

class ProviderNoticeUiTest {

    private val resources = UiTestResources()
    private val rule = createDeviceComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(rule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun leaveTheStoreAsItWasFound() {
        runBlocking { MobilePrefs(context).dismissProviderOffNotice() }
    }

    private fun show(state: GiluneState = resources.state(resources.directory("gilune-notice").path)) {
        rule.setContent {
            GiluneTheme(false) {
                SettingsScreen(
                    state = state,
                    route = SettingsRoute.Plugins,
                    onNavigate = {},
                    theme = ThemeChoice.System,
                    onThemeChange = {},
                    idleTimeout = IdleTimeout.Default,
                    onIdleTimeoutChange = {},
                    onExport = {},
                )
            }
        }
    }

    @Test
    fun aPhoneWithNothingToApologiseForShowsNoNotice() {
        runBlocking { MobilePrefs(context).dismissProviderOffNotice() }
        show()
        rule.onNodeWithTag("provider-off-notice").assertDoesNotExist()
    }

    @Test
    fun aStandingNoticeIsDrawnAndGoesAwayWhenItIsDismissed() {
        runBlocking { MobilePrefs(context).raiseProviderOffNotice() }
        show()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("provider-off-notice").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("provider-off-notice").assertExists()
        rule.onNodeWithTag("provider-off-dismiss").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("provider-off-notice").fetchSemanticsNodes().isEmpty()
        }
        // And it stays dismissed: the flag is persisted, not composition
        // state, so a second visit to this screen must not bring it back.
        runBlocking { assertFalse(MobilePrefs(context).providerOffNotice.first()) }
    }
}
