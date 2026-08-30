// Android entry point. The vault lives in the app's files dir for v0;
// the record button appears once RECORD_AUDIO is granted.

package org.gilune.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val micGranted = mutableStateOf(false)

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted.value) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        val vaultDir = filesDir.resolve("vault").absolutePath
        setContent {
            MaterialTheme {
                Surface {
                    AppScreen(
                        vaultDir,
                        if (micGranted.value) AndroidAudioRecorder() else null,
                    )
                }
            }
        }
    }
}
