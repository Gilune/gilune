// Desktop entry point. The vault lives in the user's home directory by
// default (override with -Dgilune.vault=<dir>); packaged distributables
// load the kernel dylib from their app resources.

package org.gilune.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    System.getProperty("compose.application.resources.dir")?.let { resources ->
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing == null) resources else "$resources;$existing",
        )
    }
    val vaultDir = System.getProperty("gilune.vault")
        ?: (System.getProperty("user.home") + "/Gilune")
    application {
        Window(onCloseRequest = ::exitApplication, title = "Gilune") {
            MaterialTheme { Surface { AppScreen(vaultDir, DesktopAudioRecorder()) } }
        }
    }
}
