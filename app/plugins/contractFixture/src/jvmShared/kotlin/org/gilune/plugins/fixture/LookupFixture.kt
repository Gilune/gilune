package org.gilune.plugins.fixture

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val LOOKUP_FIXTURE = "org.example.label-lookup"
const val LABEL_LOOKUP = "$LOOKUP_FIXTURE.find"

/** Independent query/presentation consumer compiled against only the SDK. */
fun lookupFixture() = BundledPluginExtensions(
    declaration = PluginDeclaration(LOOKUP_FIXTURE, "Label lookup fixture", "domain", "bundled", false,
        null, null, emptyList(), emptyList(), listOf(PluginContribution(Slots.INDEXED_LOOKUP, LABEL_LOOKUP, "Find labels"))),
    scopedIndexer = ScopedIndexerRegistration(1, emptyList()) {
        object : ScopedIndexer {
            override fun index(relPath: String, source: SourceTextSnapshot): List<ScopedFact> =
                SourceView(source).metadata()["label"]?.string()?.let {
                    listOf(ScopedFact(SourceLocation.DocumentRoot, "$LOOKUP_FIXTURE.label", it))
                }.orEmpty()
        }
    },
    indexedLookups = listOf(IndexedLookupRegistration(LABEL_LOOKUP) {
        object : IndexedLookupProvider {
            override fun query(fragment: String) = LookupRequest(
                listOf(Constraint.FactAt("$LOOKUP_FIXTURE.label", ConstraintOp.PREFIX, fragment, FactLocality.DOCUMENT_ROOT)),
                emptyList(), listOf("$LOOKUP_FIXTURE.label"), listOf("$LOOKUP_FIXTURE.label"), listOf(LOOKUP_FIXTURE), 30u, null,
            )
            override fun present(hit: LookupHit): LookupRow {
                val value = hit.facts.single().value
                return LookupRow(value, hit.relPath, "label:$value ")
            }
        }
    }),
)
