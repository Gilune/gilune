package org.gilune.plugins.fixture

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val REFACTOR_FIXTURE = "org.example.label-refactoring"
private const val DEFINITION = "$REFACTOR_FIXTURE.definition"
private const val REFERENCE = "$REFACTOR_FIXTURE.reference"
private val labels = Regex("label=([a-z]+)")
private fun lookup(value: String) = LookupRequest(listOf(Constraint.FactAt(DEFINITION, ConstraintOp.EQ, value, FactLocality.DOCUMENT_ROOT)),
    emptyList(), emptyList(), listOf(DEFINITION), listOf(REFACTOR_FIXTURE), 2u, null)

/** Independently compiled consumer: no People, Tasks or application imports. */
fun refactoringFixture() = BundledPluginExtensions(
    declaration = PluginDeclaration(REFACTOR_FIXTURE, "Labels", "domain", "bundled", false, null, null, emptyList(), emptyList(),
        listOf(PluginContribution(Slots.DOCUMENT_REFACTORINGS, "$REFACTOR_FIXTURE.rename", "Rename label"))),
    scopedIndexer = ScopedIndexerRegistration(1, emptyList()) {
        object : ScopedIndexer {
            override fun index(relPath: String, source: SourceTextSnapshot): List<ScopedFact> {
                val view = SourceView(source)
                return buildList {
                    view.metadata()["label"]?.string()?.let { add(ScopedFact(SourceLocation.DocumentRoot, DEFINITION, it)) }
                    labels.findAll(source.text).forEach { add(ScopedFact(view.locationAt(it.range.first), REFERENCE, it.groupValues[1])) }
                }
            }
        }
    },
    refactorings = listOf(RefactoringRegistration("$REFACTOR_FIXTURE.rename", "1",
        actions = { SourceView(it).metadata()["label"]?.string()?.let { name -> listOf(RefactoringAction("label", "Rename label", name)) }.orEmpty() },
        validate = { RefactoringValidation(lookup(it.before), lookup(it.after)) },
        candidates = { LocationQuery(listOf(Constraint.FactAt(REFERENCE, ConstraintOp.EQ, it.before, FactLocality.EXACT)), LocationKind.ANY, listOf(REFACTOR_FIXTURE), 100u, null) },
        propose = { intent, source ->
            val view = SourceView(source)
            buildList {
                labels.findAll(source.text).filter { it.groupValues[1] == intent.before }.forEach { match ->
                    val range = match.groups[1]!!.range
                    add(SourceTextEdit(SourceSpan(view.byteOffset(range.first), view.byteOffset(range.last + 1)), intent.after))
                }
                if (view.metadata()["label"]?.string() == intent.before) {
                    val after = changeSourceProperties(source.text, source.contentVersion, listOf(PropertyChange("label", pageJsonString(intent.after))))
                    addAll(sourceDifference(source.text, after.text))
                }
            }
        })),
)
