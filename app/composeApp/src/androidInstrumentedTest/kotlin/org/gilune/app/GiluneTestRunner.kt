package org.gilune.app

import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Preserve the runner's failure behaviour, adding Java IDs to its thread dump. */
class GiluneTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        System.setProperty("gilune.uiDiagnostics", "true")
        val listeners = listOfNotNull(arguments.getString("listener"), UiTestDiagnosticListener::class.java.name)
        arguments.putString("listener", listeners.joinToString(","))
        super.onCreate(arguments)
    }

    override fun onException(obj: Any?, error: Throwable): Boolean {
        Log.e("GiluneTest", "Crash during ${UiTestDiagnosticListener.currentTest}", error)
        Thread.getAllStackTraces().forEach { (thread, stack) ->
            Log.e("GiluneTest", "Java thread id=${thread.id} name=${thread.name} state=${thread.state}\n${stack.joinToString("\n")}")
        }
        return super.onException(obj, error)
    }
}

class UiTestDiagnosticListener : RunListener() {
    companion object { @Volatile var currentTest = "before first test" }
    override fun testStarted(description: Description) {
        currentTest = description.displayName
        val thread = Thread.currentThread()
        Log.i("GiluneTest", "Starting $currentTest Java thread id=${thread.id} name=${thread.name}")
    }
    override fun testFailure(failure: org.junit.runner.notification.Failure) {
        Log.e("GiluneTest", "Test failed: ${failure.description}", failure.exception)
    }
}
