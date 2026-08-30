// Desktop entry point: window plus the shared diagnostic screen.

package org.gilune.spike.s1

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Gilune S1 boundary diagnostics") {
        val vaultDir = System.getProperty("s1.vault")
            ?: (System.getProperty("java.io.tmpdir") + "/s1-vault")
        MaterialTheme { Surface { DiagnosticScreen(vaultDir) } }
    }
}
