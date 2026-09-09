package org.gilune.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.rules.RuleChain
import java.time.LocalDate
import kotlin.test.Test

class AgendaLifecycleTest {
    private val resources = UiTestResources()
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resources).around(compose)

    @Test
    fun loadingAndEmptyAgendasSurviveRepeatedActivityDestruction() {
        val vault = resources.indexed("gilune-agenda-lifecycle")
        val scope = resources.scope
        val state = GiluneState(vault.dir, scope)
        state.gilune.value = vault.gilune
        state.plugins.add(installedTasksPlugin())
        vault.write("done.md", "- [x] done\n- [ ] far away due:${LocalDate.now().plusDays(30)}\n")
        try {
            repeat(30) { iteration ->
                compose.activityRule.scenario.onActivity { activity ->
                    activity.setContent {
                        GiluneTheme(dark = false) { AgendaScreen(state, {}, modifier = Modifier.fillMaxSize()) }
                    }
                }
                if (iteration % 2 == 0) {
                    compose.waitUntil(5_000) {
                        compose.onAllNodesWithText("Nothing open").fetchSemanticsNodes().isNotEmpty()
                    }
                }
                // Alternate complete queries with recreation during loading.
                // Destroy the whole composition, as the Android test rule does.
                compose.activityRule.scenario.recreate()
            }
        } finally {
            compose.activityRule.scenario.close()
        }
    }
}
