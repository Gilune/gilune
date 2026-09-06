package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.gilune.plugins.fixture.*
import uniffi.gilune_ffi.Gilune

class SourceIndexerTest {
    @Test fun unrelatedSdkConsumerIndexesAliasedHeaderPropertiesAndSurvivesRebuild() {
        val root = Files.createTempDirectory("gilune-header-fixture").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(headerFixture()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            val file = File(root, "fixture.md")
            file.writeText("---\r\nbase: &base {label: Important}\r\n<<: *base\r\n---\r\nordinary body\r\n")
            kernel.reconcilePath("fixture.md")
            val query = compileSearch("", types = registry.searchTypes(kernel.plugins()), maxLines = 0)
                ?: compileSearch("file:fixture", types = registry.searchTypes(kernel.plugins()), maxLines = 0)!!
            fun value() = query.page(kernel).hits.single().facts.single { it.key == "$HEADER_FIXTURE.label" }.value
            assertEquals("Important", value())
            file.writeText(file.readText().replace("Important", "Updated"))
            assertEquals("Important", value(), "query does not read external changes")
            kernel.reconcilePath("fixture.md")
            assertEquals("Updated", value())
            kernel.rebuildIndex(HEADER_FIXTURE)
            assertEquals("Updated", value())
        }
    }
}
