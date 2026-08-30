// Desktop entry point: window plus the shared diagnostic screen.

package org.gilune.spike.s1

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    // Packaged distributables carry the kernel dylib as an app resource;
    // point JNA there before the first kernel touch. Dev runs already get
    // jna.library.path from Gradle and are unaffected.
    System.getProperty("compose.application.resources.dir")?.let { resources ->
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing == null) resources else "$resources;$existing",
        )
    }
    runApp()
}

private fun runApp() = application {
    Window(onCloseRequest = ::exitApplication, title = "Gilune S1 boundary diagnostics") {
        val vaultDir = System.getProperty("s1.vault")
            ?: (System.getProperty("java.io.tmpdir") + "/s1-vault")
        MaterialTheme { Surface { DiagnosticScreen(vaultDir) } }
    }
}
