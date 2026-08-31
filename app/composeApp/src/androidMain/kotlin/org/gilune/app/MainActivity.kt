// Android entry point. The vault lives in the app's files dir for v0;
// the record button appears once RECORD_AUDIO is granted. Transcription
// happens on a paired coordinator, not on the phone (v0 profile).

package org.gilune.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                    val scope = rememberCoroutineScope()
                    val state = remember { GiluneState(vaultDir, scope) }
                    LaunchedEffect(Unit) { state.openVault() }
                    Column(Modifier.fillMaxSize()) {
                        CaptureBar(
                            state,
                            if (micGranted.value) AndroidAudioRecorder() else null,
                        )
                        RecentList(state, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
