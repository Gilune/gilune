// What the person sees of updates: one strip under the top bar — the
// one-time question, then "a new version is available" — and an Updates
// block in Settings ▸ About with the switch and Check now. The machinery
// is `Updates.kt`; nothing here reaches the network itself.

package org.gilune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.net.URI

/// The status in words, one sentence, shared by the strip and About.
/// `transition` is an install under Program Files moving to the person's
/// own account, which asks Windows once.
fun updateStatusWords(status: UpdateStatus, transition: Boolean = false): String = when (status) {
    UpdateStatus.Idle -> ""
    UpdateStatus.Checking -> "Checking for updates…"
    is UpdateStatus.UpToDate ->
        if (status.latest == null) "No release is published yet." else "Gilune is up to date (latest release ${status.latest})."
    is UpdateStatus.Available -> "Gilune ${status.release.version} is available."
    is UpdateStatus.Downloading -> "Downloading Gilune ${status.release.version}… ${(status.fraction * 100).toInt()}%"
    is UpdateStatus.Failed -> status.words.replaceFirstChar { it.uppercase() } + "."
    is UpdateStatus.Installing ->
        if (transition) {
            "Installing Gilune ${status.release.version} — Windows asks once to remove the copy installed for " +
                "all users, then Gilune reopens from your own account, where updates no longer ask."
        } else {
            "Installing Gilune ${status.release.version} — it reopens when the installer is done."
        }
}

private fun openPage(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

/// The strip under the top bar, or nothing. It asks the one-time question
/// first; after that it speaks only when there is something to do.
@Composable
fun UpdateBanner(updater: Updater) {
    val colours = MaterialTheme.colorScheme
    val status = updater.status
    val release = when (status) {
        is UpdateStatus.Available -> status.release
        is UpdateStatus.Downloading -> status.release
        is UpdateStatus.Installing -> status.release
        is UpdateStatus.Failed -> status.release
        else -> null
    }
    val asking = updater.shouldAsk
    if (!asking && (release == null || updater.dismissed)) return

    Row(
        Modifier.fillMaxWidth().background(colours.secondaryContainer)
            .padding(horizontal = 14.dp, vertical = 4.dp).testTag("update-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val words = if (asking) {
            "Gilune can check GitHub for new versions and install them for you. " +
                "You can change this in Settings ▸ About."
        } else {
            updateStatusWords(status, updater.transitionsToPerUser)
        }
        Text(words, fontSize = 12.sp, color = colours.onSecondaryContainer, modifier = Modifier.weight(1f))
        if (status is UpdateStatus.Downloading && !asking) {
            LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.width(120.dp))
        }
        when {
            asking -> {
                TextButton(onClick = { updater.answer(true) }) { Text("Check for updates", fontSize = 12.sp) }
                TextButton(onClick = { updater.answer(false) }) { Text("No thanks", fontSize = 12.sp) }
            }
            release != null && (status is UpdateStatus.Available || status is UpdateStatus.Failed) -> {
                if (updater.canInstall) {
                    TextButton(onClick = { updater.update(release) }) {
                        Text(if (status is UpdateStatus.Failed) "Try again" else "Update now", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { openPage(release.pageUrl) }) {
                    Text(if (updater.canInstall) "What's new" else "Download page", fontSize = 12.sp)
                }
                TextButton(onClick = { updater.dismissed = true }) { Text("Later", fontSize = 12.sp) }
            }
        }
    }
    HorizontalDivider(color = colours.outlineVariant)
}

/// Settings ▸ About's Updates block: what this build is, the switch for
/// looking automatically, Check now, and what the last look found.
@Composable
fun UpdatesSection(updater: Updater) {
    val colours = MaterialTheme.colorScheme
    HorizontalDivider(color = colours.outlineVariant)
    Text("Updates", style = MaterialTheme.typography.titleSmall)
    Text(
        "This is ${updater.describeBuild}. Gilune looks for new versions on GitHub, " +
            "downloads the installer, checks it is exactly the file GitHub published, " +
            "then closes, installs and reopens. " +
            if (updater.transitionsToPerUser) {
                "This copy is installed for all users, so the next update asks Windows once to remove " +
                    "it and installs Gilune for your account instead; after that, updates ask nothing."
            } else {
                "Installed for your account, an update asks for no permission."
            },
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colours.onSurfaceVariant,
    )
    if (updater.staleAllUsersCopy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "A copy of Gilune installed for all users is still on this computer. It no longer " +
                    "updates; removing it asks Windows once for an administrator's approval.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colours.error,
                modifier = Modifier.weight(1f).testTag("stale-all-users"),
            )
            OutlinedButton(onClick = { updater.removeStaleAllUsersCopy() }, enabled = !updater.removingStaleCopy) {
                Text("Remove it", fontSize = 12.sp)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(
            checked = updater.consent == true,
            onCheckedChange = { updater.answer(it) },
            modifier = Modifier.testTag("update-automatic"),
        )
        Text("Check for updates automatically", fontSize = 13.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { updater.check() },
            enabled = updater.status !is UpdateStatus.Checking &&
                updater.status !is UpdateStatus.Downloading &&
                updater.status !is UpdateStatus.Installing,
        ) { Text("Check now", fontSize = 12.sp) }
        val status = updater.status
        if (status is UpdateStatus.Available) {
            if (updater.canInstall) {
                TextButton(onClick = { updater.update(status.release) }) { Text("Update now", fontSize = 12.sp) }
            }
            TextButton(onClick = { openPage(status.release.pageUrl) }) {
                Text(if (updater.canInstall) "What's new" else "Download page", fontSize = 12.sp)
            }
        }
    }
    val words = updateStatusWords(updater.status, updater.transitionsToPerUser)
    if (words.isNotEmpty()) {
        Text(words, fontSize = 12.sp, color = colours.onSurfaceVariant, modifier = Modifier.testTag("update-status"))
    }
}
