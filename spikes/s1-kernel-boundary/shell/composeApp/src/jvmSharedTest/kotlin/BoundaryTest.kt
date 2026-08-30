// Machine-checked S1 boundary evidence: each test is one ADR 0001
// acceptance-gate item exercised from Kotlin through the UniFFI boundary.
// Shared verbatim by the desktop JVM suite and the Android instrumented
// suite. Timing prints feed the README's measurement table.

package org.gilune.spike.s1

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import uniffi.spike_s1_kernel.EventListener
import uniffi.spike_s1_kernel.InternalException
import uniffi.spike_s1_kernel.Kernel
import uniffi.spike_s1_kernel.KernelEvent
import uniffi.spike_s1_kernel.KernelException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BoundaryTest {
    private fun freshKernel(): Pair<Kernel, LinkedBlockingQueue<KernelEvent>> {
        val dir = Files.createTempDirectory("s1-boundary").toString()
        val events = LinkedBlockingQueue<KernelEvent>()
        val kernel = Kernel.open(dir)
        kernel.subscribe(object : EventListener {
            override fun onEvent(event: KernelEvent) {
                events.put(event)
            }
        })
        return kernel to events
    }

    @Test
    fun openMigratesAndReportsInfo() {
        lateinit var kernel: Kernel
        val dir = Files.createTempDirectory("s1-boundary").toString()
        val coldOpen = measureNanoTime { kernel = Kernel.open(dir) }
        lateinit var firstCallInfo: uniffi.spike_s1_kernel.KernelInfo
        val firstCall = measureNanoTime { firstCallInfo = kernel.info() }
        assertEquals(2L, firstCallInfo.schemaVersion)
        println("MEASURE cold-open %.2f ms; first-call %.2f ms".format(coldOpen / 1e6, firstCall / 1e6))
    }

    @Test
    fun recordRoundtripWithOptionalsAndEvents() {
        val (kernel, events) = freshKernel()
        val put = measureNanoTime {
            val r = kernel.putRecord("hello boundary", null)
            assertNull(r.note)
        }
        val withNote = kernel.putRecord("second", "a note")
        assertEquals("a note", kernel.getRecord(withNote.id)?.note)
        assertNull(kernel.getRecord("no-such-id"))
        assertEquals(2uL, kernel.recordCount())
        val committed = events.poll(5, TimeUnit.SECONDS)
        assertEquals("record-committed", assertNotNull(committed).kind)
        println("MEASURE put-record %.2f ms".format(put / 1e6))
    }

    @Test
    fun typedErrorsMapToSealedExceptions() {
        val (kernel, _) = freshKernel()
        assertFailsWith<KernelException.Invalid> { kernel.putRecord("", null) }
        assertFailsWith<KernelException.NotFound> { kernel.openBlobRead("absent.bin") }
        assertFailsWith<KernelException.Invalid> { kernel.openBlobWrite("../escape.bin") }
    }

    @Test
    fun rustPanicSurfacesAsInternalExceptionAndKernelSurvives() {
        val (kernel, _) = freshKernel()
        val e = assertFailsWith<InternalException> { kernel.triggerPanic() }
        assertTrue("boundary diagnostic" in (e.message ?: ""), "panic message should cross: ${e.message}")
        kernel.putRecord("still alive", null)
        assertEquals(1uL, kernel.recordCount())

        // Symbolication: the panic hook's report must cross the boundary.
        // Symbol quality differs per platform; the printed report is the
        // evidence the README assesses.
        val report = uniffi.spike_s1_kernel.lastPanicReport()
        assertNotNull(report)
        assertTrue("deliberate spike-s1 panic" in report, "report should carry the message")
        assertTrue("backtrace:" in report, "report should carry a backtrace section")
        println("PANIC REPORT (truncated):\n" + report.take(1200))
    }

    @Test
    fun streamRoundtripHashesAgreeAcrossLanguages() {
        val (kernel, _) = freshKernel()
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val mib = 64
        val digest = MessageDigest.getInstance("SHA-256")
        val writer = kernel.openBlobWrite("stream.bin")
        val wrote = measureNanoTime {
            repeat(mib * 16) {
                writer.writeChunk(chunk)
                digest.update(chunk)
            }
        }
        val handle = writer.finish()
        val sent = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals(sent, handle.sha256, "Kotlin and Rust must hash the same bytes")
        assertEquals((mib * 16 * chunk.size).toLong(), handle.bytes.toLong())

        val back = MessageDigest.getInstance("SHA-256")
        val reader = kernel.openBlobRead("stream.bin")
        var total = 0L
        val read = measureNanoTime {
            while (true) {
                val c = reader.readChunk(64u * 1024u) ?: break
                back.update(c)
                total += c.size
            }
        }
        assertEquals(handle.bytes.toLong(), total)
        assertEquals(sent, back.digest().joinToString("") { "%02x".format(it) })
        println(
            "MEASURE stream $mib MiB across FFI: write %.1f MiB/s, read %.1f MiB/s"
                .format(mib / (wrote / 1e9), mib / (read / 1e9))
        )
    }

    @Test
    fun slowConsumerIsValidNotAnError() {
        val (kernel, _) = freshKernel()
        val writer = kernel.openBlobWrite("slow.bin")
        val chunk = ByteArray(64 * 1024) { 7 }
        repeat(16, { writer.writeChunk(chunk) })
        val handle = writer.finish()

        val reader = kernel.openBlobRead("slow.bin")
        var total = 0L
        while (true) {
            val c = reader.readChunk(4096u) ?: break // deliberately tiny chunks
            total += c.size
            Thread.sleep(1) // deliberately slow consumer
        }
        assertEquals(handle.bytes.toLong(), total)
    }

    @Test
    fun asyncCompletesAndCancellationDropsTheRustFuture() = runBlocking {
        val (kernel, events) = freshKernel()
        assertEquals(5L, kernel.slowAdd(2, 3, 100uL))
        assertEquals("async-completed", awaitKind(events, "async-completed"))

        val job = launch(Dispatchers.IO) { kernel.slowAdd(1, 1, 60_000uL) }
        delay(300)
        job.cancel()
        job.join()
        // The Rust-side guard must observe the drop: cancellation crossed.
        assertEquals("async-dropped", awaitKind(events, "async-dropped"))
    }

    private fun awaitKind(events: LinkedBlockingQueue<KernelEvent>, kind: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val e = events.poll(1, TimeUnit.SECONDS) ?: continue
            if (e.kind == kind) return e.kind
        }
        fail("did not observe event '$kind' within 10 s")
    }
}
