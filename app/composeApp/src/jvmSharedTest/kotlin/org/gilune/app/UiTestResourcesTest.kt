package org.gilune.app

import kotlinx.coroutines.*
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class UiTestResourcesTest {
    @Test fun uiDisposesAndJobsFinishBeforeNativeResourcesClose() {
        val resources = UiTestResources()
        val disposed = AtomicBoolean()
        val stopped = AtomicBoolean()
        var closed = false
        resources.own(AutoCloseable {
            assertTrue(disposed.get())
            assertTrue(stopped.get())
            closed = true
        })
        resources.apply(object : Statement() {
            override fun evaluate() {
                resources.scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { stopped.set(true) }
                }
                // An inner Compose rule completes disposal before returning.
                disposed.set(true)
            }
        }, Description.EMPTY).evaluate()
        assertTrue(closed)
    }

    @Test fun cleanupFailureDoesNotReplaceTheOriginalTestFailure() {
        val resources = UiTestResources()
        val original = AssertionError("original assertion")
        resources.own(AutoCloseable { error("cleanup failure") })
        val actual = assertFailsWith<AssertionError> {
            resources.apply(object : Statement() {
                override fun evaluate() { throw original }
            }, Description.EMPTY).evaluate()
        }
        assertSame(original, actual)
        assertEquals("cleanup failure", actual.suppressed.single().message)
    }
}
