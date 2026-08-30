// Android entry point: the shared diagnostic screen over the app's private
// files dir. Surviving onCreate after activity recreation is itself one of
// the S1 fault-list items — the kernel must reopen cleanly.

package org.gilune.spike.s1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vaultDir = filesDir.resolve("s1-vault").absolutePath
        setContent {
            MaterialTheme { Surface { DiagnosticScreen(vaultDir) } }
        }
    }
}
