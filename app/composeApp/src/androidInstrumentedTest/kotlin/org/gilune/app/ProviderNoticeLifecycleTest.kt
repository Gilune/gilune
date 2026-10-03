package org.gilune.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import kotlin.test.assertFalse

class ProviderNoticeLifecycleTest {
    private val resources = UiTestResources()
    private val compose = createDeviceAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    @Test
    fun dismissingTheNoticeSurvivesRepeatedActivityDestruction() {
        val prefs = MobilePrefs(InstrumentationRegistry.getInstrumentation().targetContext)
        val state = resources.state(resources.directory("gilune-notice-lifecycle").path)
        try {
            repeat(30) {
                runBlocking { prefs.raiseProviderOffNotice() }
                compose.activityRule.scenario.onActivity { activity ->
                    activity.setContent {
                        GiluneTheme(false) {
                            SettingsScreen(state, SettingsRoute.Plugins, {}, ThemeChoice.System,
                                {}, IdleTimeout.Default, {}, {})
                        }
                    }
                }
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("provider-off-notice").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("provider-off-dismiss").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("provider-off-notice").fetchSemanticsNodes().isEmpty()
                }
                runBlocking { assertFalse(prefs.providerOffNotice.first()) }
                compose.activityRule.scenario.recreate()
            }
        } finally {
            runBlocking { prefs.dismissProviderOffNotice() }
            compose.activityRule.scenario.close()
        }
    }
}
