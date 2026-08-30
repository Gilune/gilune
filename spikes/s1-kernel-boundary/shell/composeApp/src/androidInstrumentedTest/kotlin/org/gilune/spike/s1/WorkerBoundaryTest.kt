// Android-only: enqueue the kernel probe through real WorkManager and wait
// for it to succeed. Repeats once so the worker path also proves reopen.

package org.gilune.spike.s1

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class WorkerBoundaryTest {
    private fun runProbe(): WorkInfo {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<KernelProbeWorker>().build()
        wm.enqueue(request).result.get()
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val info = wm.getWorkInfoById(request.id).get()
            if (info != null && info.state.isFinished) return info
            Thread.sleep(250)
        }
        fail("worker did not finish within 30 s")
    }

    @Test
    fun backgroundWorkerOpensKernelCommitsAndSurvivesRestart() {
        val first = runProbe()
        assertEquals(WorkInfo.State.SUCCEEDED, first.state, "worker failed: ${first.outputData.getString("error")}")
        assertEquals(1L, first.outputData.getLong("count", -1))

        // Second run is a fresh worker instance reopening the same vault.
        val second = runProbe()
        assertEquals(WorkInfo.State.SUCCEEDED, second.state, "reopen failed: ${second.outputData.getString("error")}")
        assertEquals(2L, second.outputData.getLong("count", -1))
    }
}
