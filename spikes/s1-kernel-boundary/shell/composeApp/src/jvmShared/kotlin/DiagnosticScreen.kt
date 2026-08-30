// Spike S1 diagnostic screen, shared by the desktop and Android shells: no
// domain UI, just one button per boundary mechanism plus a log pane. Every
// action reports elapsed time so the numbers in the README come from this
// screen, not ad-hoc harnesses.

package org.gilune.spike.s1

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.spike_s1_kernel.EventListener
import uniffi.spike_s1_kernel.Kernel
import uniffi.spike_s1_kernel.KernelEvent
import java.security.MessageDigest
import kotlin.system.measureNanoTime

const val CHUNK = 64 * 1024
const val BLOB_MIB = 64

@Composable
fun DiagnosticScreen(vaultDir: String) {
    var kernel by remember { mutableStateOf<Kernel?>(null) }
    var asyncJob by remember { mutableStateOf<Job?>(null) }
    val log = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    fun say(line: String) {
        log.add(0, line)
        println("S1DIAG $line") // reaches logcat on Android, console on desktop
    }

    fun ms(nanos: Long) = "%.2f ms".format(nanos / 1e6)

    fun openKernel() {
        scope.launch(Dispatchers.IO) {
            try {
                lateinit var k: Kernel
                val elapsed = measureNanoTime { k = Kernel.open(vaultDir) }
                k.subscribe(object : EventListener {
                    override fun onEvent(event: KernelEvent) {
                        say("EVENT ${event.kind}: ${event.detail}")
                    }
                })
                val info = k.info()
                kernel = k
                say("open: ${ms(elapsed)}; schema v${info.schemaVersion}; ${info.dbPath}")
            } catch (e: Throwable) {
                say("open FAILED: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    // Auto-open so a kernel is live from the first frame: activity
    // recreation and process death then exercise reopen, not a blank app.
    androidx.compose.runtime.LaunchedEffect(Unit) { openKernel() }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openKernel() }) { Text("Open kernel") }

            Button(onClick = {
                val k = kernel ?: return@Button say("open the kernel first")
                scope.launch(Dispatchers.IO) {
                    try {
                        lateinit var id: String
                        val put = measureNanoTime {
                            id = k.putRecord("diagnostic record", "optional note").id
                        }
                        val get = measureNanoTime { checkNotNull(k.getRecord(id)) }
                        say("put: ${ms(put)}; get: ${ms(get)}; count=${k.recordCount()}")
                    } catch (e: Throwable) {
                        say("record FAILED: ${e::class.simpleName}: ${e.message}")
                    }
                }
            }) { Text("Put + get record") }

            Button(onClick = {
                val k = kernel ?: return@Button say("open the kernel first")
                scope.launch(Dispatchers.IO) {
                    try {
                        val chunk = ByteArray(CHUNK) { (it % 251).toByte() }
                        val digest = MessageDigest.getInstance("SHA-256")
                        val writer = k.openBlobWrite("stream.bin")
                        val chunks = BLOB_MIB * 16
                        val wrote = measureNanoTime {
                            repeat(chunks) {
                                writer.writeChunk(chunk)
                                digest.update(chunk)
                            }
                        }
                        val handle = writer.finish()
                        val sent = digest.digest().joinToString("") { "%02x".format(it) }
                        val back = MessageDigest.getInstance("SHA-256")
                        val reader = k.openBlobRead("stream.bin")
                        var total = 0L
                        val read = measureNanoTime {
                            while (true) {
                                val c = reader.readChunk(CHUNK.toUInt()) ?: break
                                back.update(c)
                                total += c.size
                            }
                        }
                        val got = back.digest().joinToString("") { "%02x".format(it) }
                        val okay = handle.sha256 == sent && sent == got && total == handle.bytes.toLong()
                        say(
                            "stream $BLOB_MIB MiB: write %.1f MiB/s, read %.1f MiB/s, hashes %s"
                                .format(BLOB_MIB / (wrote / 1e9), BLOB_MIB / (read / 1e9), if (okay) "match" else "MISMATCH")
                        )
                    } catch (e: Throwable) {
                        say("stream FAILED: ${e::class.simpleName}: ${e.message}")
                    }
                }
            }) { Text("Stream $BLOB_MIB MiB") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val k = kernel ?: return@Button say("open the kernel first")
                asyncJob = scope.launch(Dispatchers.IO) {
                    try {
                        say("slowAdd(2, 3, 5000 ms) started; cancel to test cancellation")
                        val sum = k.slowAdd(2, 3, 5000uL)
                        say("slowAdd completed: $sum")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        say("slowAdd coroutine cancelled (watch for Rust async-dropped event)")
                        throw e
                    } catch (e: Throwable) {
                        say("slowAdd FAILED: ${e::class.simpleName}: ${e.message}")
                    }
                }
            }) { Text("Async slow add (5 s)") }

            Button(onClick = { asyncJob?.cancel(); say("cancel requested") }) { Text("Cancel async") }

            Button(onClick = {
                val k = kernel ?: return@Button say("open the kernel first")
                scope.launch(Dispatchers.IO) {
                    try {
                        k.triggerPanic()
                        say("panic DID NOT propagate — that is a failure")
                    } catch (e: Throwable) {
                        say("panic surfaced as ${e::class.simpleName}: ${e.message}")
                    }
                    // The kernel must still serve calls after a contained panic.
                    withContext(Dispatchers.IO) {
                        say("post-panic count=${k.recordCount()} (kernel still serving)")
                    }
                }
            }) { Text("Trigger Rust panic") }
        }

        Text("log (newest first)", fontSize = 12.sp)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(log) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
