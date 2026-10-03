package org.gilune.app

import kotlinx.coroutines.*
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Files

/** The outer rule: Compose must dispose its UI before these resources close. */
internal class UiTestResources : TestRule {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val cleanup = mutableListOf<() -> Unit>()

    fun directory(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { root ->
        cleanup += { check(root.deleteRecursively()) { "Could not remove test vault $root" } }
    }

    fun indexed(prefix: String = "gilune-ui"): IndexedVault = IndexedVault(prefix).also { vault ->
        cleanup += { vault.close() }
    }

    fun state(vaultDir: String): GiluneState = GiluneState(vaultDir, scope).also { state ->
        // Some editor tests open their kernel asynchronously after construction.
        cleanup += { state.gilune.value?.close() }
    }

    fun <T : AutoCloseable> own(resource: T): T = resource.also {
        cleanup += { resource.close() }
    }

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            var failure: Throwable? = null
            try { base.evaluate() } catch (error: Throwable) { failure = error }
            try {
                // Cancelling alone does not wait for a native call on IO to return.
                runBlocking { withTimeout(10_000) { job.cancelAndJoin() } }
                cleanup.asReversed().forEach { close ->
                    try { close() } catch (error: Throwable) {
                        if (failure == null) failure = error else failure!!.addSuppressed(error)
                    }
                }
            } catch (error: Throwable) {
                // Leave handles alive if work could still be using them.
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
            failure?.let { throw it }
        }
    }
}
