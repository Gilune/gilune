// S1 fault-list item: a platform background worker reopening the kernel.
// WorkManager runs this on its own executor, detached from any activity;
// the kernel must open, migrate, commit, and close cleanly there.

package org.gilune.spike.s1

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import uniffi.spike_s1_kernel.Kernel

class KernelProbeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val dir = applicationContext.filesDir.resolve("s1-worker-vault").absolutePath
        return try {
            val kernel = Kernel.open(dir)
            val record = kernel.putRecord("worker probe", null)
            val count = kernel.recordCount()
            kernel.close()
            Result.success(workDataOf("recordId" to record.id, "count" to count.toLong()))
        } catch (e: Throwable) {
            Result.failure(workDataOf("error" to "${e::class.simpleName}: ${e.message}"))
        }
    }
}
