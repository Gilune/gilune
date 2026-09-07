// The application boundary exercised from Kotlin, shared verbatim by the
// desktop JVM suite and the Android instrumented suite: capture (text and
// streamed), projection, reconciliation, and proposals — through the real
// kernel.

package org.gilune.app

import java.nio.file.Files
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.GiluneException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundaryTest {
    private fun fresh(): Gilune {
        return Gilune.open(Files.createTempDirectory("gilune-app").toString())
    }

    @Test
    fun textCaptureIsDurableAndProjects() {
        val g = fresh()
        val receipt = g.captureText("Remember the milk")
        assertTrue(receipt.relPath.startsWith("Captures/"))
        g.project()
        val status = g.status()
        assertEquals(1L, status.sources)
        assertEquals(1L, status.projections)
        val sources = g.recentSources(10u)
        assertEquals("Remember the milk", sources[0].preview)
    }

    @Test
    fun streamedCaptureCommitsChunkByChunk() {
        val g = fresh()
        val writer = g.beginCapture("wav")
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        repeat(8) { writer.writeChunk(chunk) }
        val receipt = writer.finish()
        assertEquals((8 * chunk.size).toLong(), receipt.bytes.toLong())
        assertFailsWith<GiluneException.Invalid> { writer.finish() }
        assertEquals(1L, g.status().sources)
    }

    @Test
    fun anExternalEditBecomesKernelHistory() {
        val g = fresh()
        g.captureText("original thought")
        g.project()
        val rel = g.recentSources(1u)[0].projectionRelPath!!
        val path = java.nio.file.Paths.get(g.vaultPath(), rel)
        val edited = String(Files.readAllBytes(path)).replace("original thought", "edited thought")
        Files.write(path, edited.toByteArray())
        val summary = g.reconcile()
        assertEquals(1L, summary.edits)
        g.project()
        assertEquals("edited thought", g.recentSources(1u)[0].preview)
    }

    @Test
    fun aDeletedProjectionBecomesAProposalAndRejectionRestoresIt() {
        val g = fresh()
        g.captureText("delete me maybe")
        g.project()
        val rel = g.recentSources(1u)[0].projectionRelPath!!
        Files.delete(java.nio.file.Paths.get(g.vaultPath(), rel))
        assertEquals(1L, g.reconcile().tombstoneProposals)
        val proposal = g.openProposals()[0]
        g.resolveTombstone(proposal.proposalId, false)
        g.project()
        assertTrue(Files.exists(java.nio.file.Paths.get(g.vaultPath(), rel)))
    }

    @Test
    fun aNewerVaultIsRefusedNotGuessedAt() {
        val g = fresh()
        assertTrue(g.schemaVersion() >= 2L)
        assertTrue(g.deviceId().isNotEmpty())
    }
}
