package org.gilune.plugins.fixture

import org.gilune.plugin.sdk.BundledPluginExtensions
import org.gilune.plugin.sdk.SourceIndexerRegistration
import uniffi.gilune_ffi.*

const val HEADER_FIXTURE = "org.example.headers"

/** Independent full-source SDK consumer: no application or domain dependency. */
fun headerFixture() = BundledPluginExtensions(
    declaration = PluginDeclaration(HEADER_FIXTURE, "Header fixture", "domain", "bundled", false,
        null, null, emptyList(), emptyList(), emptyList()),
    sourceIndexer = SourceIndexerRegistration(1, emptyList()) {
        object : SourceIndexer {
            override fun index(relPath: String, source: String): List<Fact> =
                readSourceProperties(source, listOf("label")).mapNotNull { property ->
                    property.value?.takeIf { property.kind == "string" }?.let {
                        Fact(null, "$HEADER_FIXTURE.label", it)
                    }
                }
        }
    },
)
