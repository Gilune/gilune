// What a person sees of updates: the strip asks once and then keeps quiet
// until there is something newer, and Settings ▸ About checks on demand.
// The network is a fixture; nothing here reaches GitHub.

package org.gilune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class UpdateViewsUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val temp: File = Files.createTempDirectory("gilune-update-ui").toFile()

    @AfterTest fun tearDown() {
        compose.setContent {}
        scope.cancel()
        temp.deleteRecursively()
    }

    private fun updater(latest: String, consent: Boolean? = null, saved: MutableList<Boolean> = mutableListOf()) = Updater(
        scope = scope,
        current = AppVersion(1, 0, 8),
        installed = InstalledApp(File(temp, "Gilune.exe"), emptyList()),
        downloads = File(temp, "updates"),
        fetchText = { releaseJson(latest) },
        openDownload = { error("no downloads in this test") },
        launch = { error("nothing is installed in this test") },
        saveConsent = { saved += it },
        consent = consent,
    )

    private fun banners() = compose.onAllNodesWithTag("update-banner").fetchSemanticsNodes().size

    @Test
    fun theStripAsksOnceAndANoIsKept() {
        val saved = mutableListOf<Boolean>()
        val updater = updater("v1.0.9", saved = saved)
        compose.setContent { GiluneTheme(dark = false) { Column { UpdateBanner(updater) } } }

        compose.onNodeWithText("Gilune can check GitHub", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No thanks").performClick()
        compose.waitForIdle()

        assertEquals(listOf(false), saved)
        assertEquals(0, banners(), "asked once, and nothing was looked up")
        assertEquals(UpdateStatus.Idle, updater.status)
    }

    @Test
    fun aYesLooksAndANewerReleaseIsOfferedUntilLater() {
        val updater = updater("v1.0.9")
        compose.setContent { GiluneTheme(dark = false) { Column { UpdateBanner(updater) } } }

        compose.onNodeWithText("Check for updates").performClick()
        compose.waitUntil(10_000) { updater.status is UpdateStatus.Available }
        compose.onNodeWithText("Gilune 1.0.9 is available.").assertIsDisplayed()
        compose.onNodeWithText("Update now").assertIsDisplayed()

        compose.onNodeWithText("Later").performClick()
        compose.waitForIdle()
        assertEquals(0, banners())
    }

    @Test
    fun aboutChecksOnDemandWithoutTurningAutomaticChecksOn() {
        val root = File(temp, "vault").apply { mkdirs() }
        val state = GiluneState(root.path, scope)
        val saved = mutableListOf<Boolean>()
        val updater = updater("v1.0.8", consent = false, saved = saved)
        compose.setContent {
            GiluneTheme(dark = false) {
                SettingsPane(state, root.path, {}, ThemeChoice.Light, {}, {}, initialSection = "About", updater = updater)
            }
        }
        compose.onNodeWithText("Check for updates automatically").assertIsDisplayed()
        compose.onNodeWithText("Check now").performClick()
        compose.waitUntil(10_000) { updater.status is UpdateStatus.UpToDate }
        compose.onNodeWithText("Gilune is up to date (latest release 1.0.8).").assertIsDisplayed()
        assertEquals(emptyList(), saved, "Check now is a yes for that one look only")
    }
}
