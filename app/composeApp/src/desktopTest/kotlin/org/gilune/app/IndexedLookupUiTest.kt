package org.gilune.app

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.gilune.plugin.sdk.*
import org.gilune.plugins.fixture.*
import uniffi.gilune_ffi.*
import kotlin.test.*

class IndexedLookupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slowLookupCannotPublishIntoANewerBufferRequestOrBlockTheUi() {
        val root = Files.createTempDirectory("gilune-lookup-publication").toFile()
        val kernel = Gilune.open(root.absolutePath)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val base = lookupFixture()
        val uiThread = arrayOfNulls<Thread>(1)
        val gated = BundledPluginExtensions(base.declaration, scopedIndexer = base.scopedIndexer,
            indexedLookups = listOf(IndexedLookupRegistration(LABEL_LOOKUP) { settings ->
                val delegate = base.indexedLookups.single().create(settings)
                object : IndexedLookupProvider {
                    override fun query(fragment: String): LookupRequest {
                        assertNotEquals(uiThread[0], Thread.currentThread())
                        if (fragment == "old") {
                            started.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            finished.countDown()
                        }
                        return delegate.query(fragment)
                    }
                    override fun present(hit: LookupHit) = delegate.present(hit)
                }
            }))
        val registry = PluginExtensions(listOf(gated))
        registry.registerPlugins(kernel); registry.registerIndexers(kernel)
        for (label in listOf("old", "new")) {
            File(root, "$label.md").writeText("---\nlabel: $label\n---\n")
            kernel.reconcilePath("$label.md")
        }
        val context = mutableStateOf(LookupContext(1, "old-buffer", 3, LABEL_LOOKUP, "old"))
        val plugins = mutableStateOf(kernel.plugins())
        try {
            compose.setContent {
                uiThread[0] = Thread.currentThread()
                val work = rememberIndexedLookup(kernel, registry, context.value, plugins.value, 0).value
                Text(work.value?.rows?.single()?.row?.label ?: work.error ?: "loading")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("loading").assertIsDisplayed()
            compose.runOnIdle { context.value = LookupContext(2, "new-buffer", 8, LABEL_LOOKUP, "new") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("new").fetchSemanticsNodes().isNotEmpty() }
            release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            compose.onNodeWithText("new").assertIsDisplayed()
            compose.onNodeWithText("old").assertDoesNotExist()
            kernel.removePlugin(LOOKUP_FIXTURE)
            compose.runOnIdle { plugins.value = kernel.plugins() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("new").fetchSemanticsNodes().isEmpty() }
        } finally { release.countDown() }
    }
}
