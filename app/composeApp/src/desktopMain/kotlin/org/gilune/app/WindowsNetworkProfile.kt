// Which of this machine's networks Windows treats as Public, and the
// sentence that says what that means for sync (ADR 0019 § 2).
//
// The firewall rule that lets other devices reach Gilune is for Private
// networks only — the guide asks for that, and an installer-added rule is
// the same. Windows classes every new network as Public until a person
// says otherwise, and a Wi-Fi extender's SSID is a new network. So the
// live-run failure over an extender looked like this from the other end:
// announcements arriving (multicast goes out regardless), every inbound
// connection dropped without a reply, and the notebook's own screen
// saying it was listening. It was. Windows was discarding what arrived.
//
// This is the one place the shell asks Windows a question of its own,
// and it asks through PowerShell rather than a native API because the
// question is a settings-screen diagnostic read once per visit, not a
// hot path: `Get-NetConnectionProfile` is the documented answer and a
// child process is a smaller surface than a WMI binding.

package org.gilune.app

import java.util.concurrent.TimeUnit

/// A network this machine is connected to, and the profile Windows gave it.
data class NetworkProfile(val name: String, val category: String)

/// How long to wait for PowerShell before deciding the question could not
/// be asked. Measured at well under a second on this machine; the bound
/// is for a machine where PowerShell is slow to start, not for correctness.
private const val PROFILE_QUERY_SECONDS = 8L

/// The networks Windows treats as Public, by name. Empty when there are
/// none — and empty when this is not Windows, or when the question could
/// not be asked: an absent warning is the honest state for a question
/// with no answer, where a false one would send somebody to Settings for
/// nothing.
fun publicNetworks(): List<String> {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return emptyList()
    val output = runCatching { netConnectionProfiles() }.getOrDefault("")
    return publicNetworkNames(parseNetworkProfiles(output))
}

/// One line per profile, `name|category`, which is the shape
/// `parseNetworkProfiles` reads. Separate from the parse so the parse can
/// be pinned without a shell.
private fun netConnectionProfiles(): String {
    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "Get-NetConnectionProfile | ForEach-Object { \$_.Name + '|' + \$_.NetworkCategory }",
    )
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(PROFILE_QUERY_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return ""
    }
    return if (process.exitValue() == 0) text else ""
}

/// Read `name|category` lines. Anything that is not one is skipped
/// rather than guessed at: a line of PowerShell noise is not a network.
internal fun parseNetworkProfiles(output: String): List<NetworkProfile> =
    output.lineSequence()
        .map { it.trim() }
        .filter { it.contains('|') }
        .map { line ->
            val bar = line.lastIndexOf('|')
            NetworkProfile(name = line.substring(0, bar).trim(), category = line.substring(bar + 1).trim())
        }
        .filter { it.name.isNotEmpty() && it.category.isNotEmpty() }
        .toList()

/// The names of the profiles Windows calls Public. Case does not matter;
/// `DomainAuthenticated` and `Private` both let the Private-profile rule
/// apply.
internal fun publicNetworkNames(profiles: List<NetworkProfile>): List<String> =
    profiles.filter { it.category.equals("Public", ignoreCase = true) }.map { it.name }

/// What the sync screen says when a network is Public, or null when none
/// is. Named networks, because a machine on Ethernet and an extender's
/// Wi-Fi at once needs to be told which one to change.
internal fun publicNetworkWarning(public: List<String>): String? {
    if (public.isEmpty()) return null
    val named = public.joinToString(" and ") { "“$it”" }
    val verb = if (public.size == 1) "treats the network $named" else "treats the networks $named"
    return "Windows $verb as Public, so its firewall drops what other devices send to " +
        "Gilune there — this machine cannot be reached on that network until it is set " +
        "to Private (Settings ▸ Network & internet ▸ the network's properties ▸ Private " +
        "network). Syncing from this machine to the others still works."
}
