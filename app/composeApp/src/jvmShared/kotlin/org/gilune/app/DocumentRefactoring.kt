package org.gilune.app

import java.io.File
import java.nio.file.Files
import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

internal data class BoundRefactoring(val owner: PluginInfo, val registration: RefactoringRegistration)

internal fun refactoringConfiguration(values: Map<String, String>) = values.entries.joinToString(",", "{", "}") { (key, value) -> "${pageJsonString(key)}:${pageJsonString(value)}" }
internal fun refactoringConfiguration(raw: String): Map<String, String> = SourceView(inspectSourceText("---\n$raw\n---\n"))
    .metadata().entries().associate { (key, value) -> requireNotNull(key.string()) to requireNotNull(value.string()) }
internal fun Map<String, String>.intent() = RefactoringIntent(getValue("operation"), getValue("before"), getValue("after"), getValue("merge").toBooleanStrict())

/** Worker-only orchestration. Search discovers indexed candidates; explicit
 * reconciliation/source reads establish the reviewed edit scope. Neither a
 * provider nor an indexed query gets a filesystem walker. */
internal fun prepareDocumentRefactoring(kernel: Gilune, vault: String, registry: PluginExtensions,
    binding: BoundRefactoring, definitionPath: String, intent: RefactoringIntent, folder: String = "",
    previous: RefactoringPlan? = null, cancelled: () -> Boolean = { false },
    progress: (String) -> Unit = {}): RefactoringPlan {
    check(registry.refactorings(kernel.plugins()).contains(binding)) { "The refactoring provider changed." }
    require(folder.isEmpty() || (!folder.startsWith('/') && ':' !in folder && '\\' !in folder && folder.trimEnd('/').split('/').none { it in listOf("", ".", "..", ".gilune") })) { "Choose a folder inside this vault." }
    kernel.reconcile()
    // Reconciliation may need to publish a fresh revision marker before a
    // defining page can supply a verified snapshot. Projection preserves edits.
    kernel.project()
    val definition = kernel.documentSnapshot(definitionPath)
    val actions = binding.registration.actions(definition.content)
    val previousConfig = previous?.let { refactoringConfiguration(it.configuration) }
    val continuing = previous != null && previous.provider == binding.registration.id && previousConfig?.intent() == intent &&
        previousConfig["definitionIdentity"] == definition.source.documentKey
    val definitionRenamed = continuing && actions.any { it.operation == intent.operation && it.current == intent.after }
    require(definitionRenamed || actions.any { it.operation == intent.operation && it.current == intent.before }) { "The defining page changed. Reopen its rename action." }
    val validation = binding.registration.validate(intent)
    fun lookup(query: LookupRequest) = kernel.lookup(query.copy(pageSize = 2u, cursor = null)).also {
        if (!it.indexComplete) throw SearchIndexPending(it.issues)
    }
    val original = lookup(validation.original)
    require((definitionRenamed && original.totalHits == 0uL) || (original.totalHits == 1uL && original.hits.single().relPath == definitionPath)) { "The original identifier is missing or ambiguous." }
    val destination = lookup(validation.destination)
    val sameDefinition = destination.totalHits == 1uL && destination.hits.single().relPath == definitionPath
    require(destination.totalHits == 0uL || sameDefinition || (intent.merge && destination.totalHits == 1uL)) { "The destination already exists or is ambiguous. Choose an explicit merge where supported." }
    require(!intent.merge || (destination.totalHits == 1uL && !sameDefinition)) { "A merge needs a different existing definition." }
    val config = refactoringConfiguration(mapOf("operation" to intent.operation, "before" to intent.before, "after" to intent.after,
        "merge" to intent.merge.toString(), "definition" to definitionPath, "definitionVersion" to definition.source.version,
        "definitionIdentity" to definition.source.documentKey,
        "originalPath" to original.hits.singleOrNull()?.relPath.orEmpty(),
        "destinationPath" to destination.hits.singleOrNull()?.relPath.orEmpty(),
        "destinationVersion" to destination.hits.singleOrNull()?.let { kernel.documentSnapshot(it.relPath).source.version }.orEmpty(),
        "settings" to binding.owner.settings, "folder" to folder))
    val plan = kernel.createRefactoringPlan(binding.registration.id, binding.registration.version, config,
        "${if (intent.merge) "Merge" else "Rename"} ${intent.before} → ${intent.after}")
    val candidates = linkedSetOf<String>()
    var cursor: String? = null
    try { do {
        val page = kernel.queryLocations(binding.registration.candidates(intent).copy(cursor = cursor, pageSize = 100u))
        if (!page.indexComplete) throw SearchIndexPending(page.issues)
        candidates += page.matches.map { it.relPath }
        cursor = page.nextCursor
    } while (cursor != null && !cancelled()) } catch (e: Exception) {
        kernel.addRefactoringIssue(plan.id, "(discovery)", e.message ?: "Discovery did not finish. Prepare a new preview.")
        return kernel.sealRefactoringPlan(plan.id)
    }
    candidates += definitionPath
    val root = File(vault).canonicalFile
    val files = root.walkTopDown().onEnter { directory ->
        directory == root || (directory.name != ".gilune" && !Files.isSymbolicLink(directory.toPath()) &&
            directory.canonicalFile.toPath().startsWith(root.toPath()))
    }.onFail { file, error -> kernel.addRefactoringIssue(plan.id, file.relativeTo(root).invariantSeparatorsPath, error.message ?: "Unreadable directory") }
    val seen = mutableSetOf<String>()
    fun inScope(path: String) = path == definitionPath || folder.isEmpty() || path.startsWith(folder.trimEnd('/') + "/")
    fun prepare(path: String) {
        seen += path
        if (cancelled()) return
        if (!inScope(path)) return
        progress(path)
        try {
            check(registry.refactorings(kernel.plugins()).contains(binding)) { "The provider changed during discovery." }
            check(!DocumentEditGate.dirty(vault, path)) { "Unsaved editor buffer: save it and prepare a new preview." }
            val snapshot = kernel.documentSnapshot(path)
            check(snapshot.source.state in listOf(DocumentState.MANAGED, DocumentState.UNMANAGED)) { "Document needs resolution: ${snapshot.source.state}" }
            if (path in candidates) {
                val edits = binding.registration.propose(intent, snapshot.content)
                if (edits.isNotEmpty()) kernel.addRefactoringEntry(plan.id, path, snapshot.source.version, edits)
            }
        } catch (e: Exception) { kernel.addRefactoringIssue(plan.id, path, e.message ?: "This document could not be reviewed.") }
    }
    files.takeWhile { !cancelled() }.filter { it.isFile && it.extension.equals("md", true) }.forEach { file ->
        val path = file.relativeTo(root).invariantSeparatorsPath
        if (path != definitionPath) prepare(path)
    }
    prepare(definitionPath)
    if (cancelled()) kernel.addRefactoringIssue(plan.id, "(discovery)", "Discovery was cancelled. Prepare a new preview to review the full scope.")
    else (candidates - seen).filter(::inScope).forEach { kernel.addRefactoringIssue(plan.id, it, "Indexed candidate is missing or inaccessible. Resolve it and prepare a new preview.") }
    return kernel.sealRefactoringPlan(plan.id)
}

internal fun applyDocumentRefactoring(kernel: Gilune, vault: String, registry: PluginExtensions, planId: String,
    selected: Set<String>? = null, excluded: Set<String> = emptySet(), cancelled: () -> Boolean = { false }, progress: (RefactoringEntry) -> Unit = {}): RefactoringPlan {
    var plan = kernel.refactoringPlan(planId)
    if (plan.state == "completed") return plan
    val token = requireNotNull(plan.reviewToken) { "Discovery has not completed." }
    // Undo plans and text replacements (ADR 0031 § 4) have no defining page
    // or identifier lookups to re-check: the journal's version checks are
    // the whole of their guard.
    val generic = plan.provider == "memory:refactoring.undo" || plan.provider == org.gilune.plugins.findreplace.FIND_REPLACE_PLUGIN
    val config = if (generic) emptyMap() else refactoringConfiguration(plan.configuration)
    fun currentProvider() {
        if (generic) return
        val binding = registry.refactorings(kernel.plugins()).singleOrNull { it.registration.id == plan.provider }
        check(binding != null && binding.registration.version == plan.providerVersion && binding.owner.settings == config.getValue("settings")) {
            "The provider or its configuration changed. Applied edits are retained; prepare a new preview to continue."
        }
        val validation = binding.registration.validate(config.intent())
        for ((query, key) in listOf(validation.original to "originalPath", validation.destination to "destinationPath")) {
            val result = kernel.lookup(query.copy(pageSize = 2u, cursor = null))
            if (!result.indexComplete) throw SearchIndexPending(result.issues)
            val expected = config.getValue(key)
            check(if (expected.isEmpty()) result.totalHits == 0uL else result.totalHits == 1uL && result.hits.single().relPath == expected) {
                "An identifier definition changed or became ambiguous. Prepare a new preview."
            }
        }
        config.getValue("destinationPath").takeIf { it.isNotEmpty() }?.let { path ->
            check(kernel.documentSnapshot(path).source.version == config.getValue("destinationVersion")) { "The destination definition changed. Prepare a new preview." }
        }
    }
    currentProvider()
    kernel.setRefactoringCancelled(planId, false)
    val definition = config["definition"]
    var definingEntry: RefactoringEntry? = null
    var unresolved = false
    fun apply(entry: RefactoringEntry): Boolean {
        if (cancelled()) { kernel.setRefactoringCancelled(planId, true); return false }
        if (entry.state != "pending" || entry.id in excluded || (selected != null && entry.id !in selected)) return entry.state == "applied"
        currentProvider()
        if (!generic && entry.relPath != definition) {
            check(kernel.documentSnapshot(config.getValue("definition")).source.version == config.getValue("definitionVersion")) {
                "The defining document changed after preview. Prepare a new plan."
            }
        }
        val applied = synchronized(DocumentEditGate.monitor) {
            check(!DocumentEditGate.dirty(vault, entry.relPath)) { "${entry.relPath} has unsaved edits. Save them and prepare a new preview." }
            kernel.applyRefactoringEntry(planId, token, entry.id)
        }
        progress(applied)
        return applied.state == "applied"
    }
    var offset = 0uL
    do {
        val page = kernel.refactoringEntries(planId, offset, 20u)
        offset += page.size.toULong()
        for (entry in page) {
            if (entry.relPath == definition) definingEntry = entry
            else if (!apply(entry)) unresolved = true
            if (cancelled()) break
        }
    } while (page.size == 20 && !cancelled())
    if (!unresolved && !cancelled()) definingEntry?.let { apply(it) }
    if (cancelled()) kernel.setRefactoringCancelled(planId, true)
    plan = kernel.refactoringPlan(planId)
    return plan
}
