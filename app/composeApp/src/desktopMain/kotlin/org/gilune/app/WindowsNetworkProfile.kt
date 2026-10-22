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

// ------------------------------------------------------- firewall rules
//
// The second thing Windows can do to a listening Gilune, found the same
// day as the first: a **Block** rule for the runtime. The firewall
// prompt creates one when a person clicks Cancel, or when it appears for
// somebody who is not an administrator, and a Block rule wins over every
// allow rule on every profile — so the owner's notebook, set to Private
// and allowed on Private, still dropped every connection to its sync
// port, silently, for weeks. Nothing on its screen said so. The app
// cannot remove the rule (that takes an administrator); it can read it,
// and say.

/// One inbound firewall rule naming a program under this app's own
/// install.
data class FirewallRule(val name: String, val action: String, val profile: String, val program: String)

/// Inbound rules Windows holds for the programs this app runs as: the
/// bundled runtime's `javaw.exe`/`java.exe` and the launcher beside it.
/// Empty when none, when this is not Windows, or when the question could
/// not be asked.
fun firewallRulesForThisApp(): List<FirewallRule> {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return emptyList()
    val runtime = java.io.File(System.getProperty("java.home").orEmpty())
    val root = runtime.parentFile ?: return emptyList()
    val output = runCatching { netFirewallRulesUnder(root) }.getOrDefault("")
    return rulesForPrograms(parseFirewallRules(output), programsOfThisApp(runtime))
}

/// The programs a rule could name and mean this app: the runtime's two
/// launchers and the exe that starts it. Lower-cased absolute paths.
internal fun programsOfThisApp(runtime: java.io.File): Set<String> {
    val root = runtime.parentFile ?: runtime
    return setOf(
        java.io.File(runtime, "bin\\javaw.exe"),
        java.io.File(runtime, "bin\\java.exe"),
        java.io.File(root, "Gilune.exe"),
    ).map { it.absolutePath.lowercase() }.toSet()
}

/// Rules whose program is one of ours. A rule's program may be written
/// with an environment token (`%ProgramFiles%\...`), so tokens are
/// expanded before comparing; case never matters on Windows.
internal fun rulesForPrograms(
    rules: List<FirewallRule>,
    programs: Set<String>,
    env: (String) -> String? = System::getenv,
): List<FirewallRule> = rules.filter { expandEnv(it.program, env).lowercase() in programs }

internal fun expandEnv(path: String, env: (String) -> String?): String =
    Regex("%([^%]+)%").replace(path) { m -> env(m.groupValues[1]) ?: m.value }

/// `Get-NetFirewallApplicationFilter` is one query over every filter, and
/// only the handful naming a program under the install root are followed
/// to their rules — enumerating every rule and asking each for its filter
/// took over a minute on the owner's desktop.
private fun netFirewallRulesUnder(root: java.io.File): String {
    val like = root.absolutePath.replace("'", "''") + "\\*"
    val script =
        "Get-NetFirewallApplicationFilter | Where-Object { \$_.Program -like '$like' } | " +
            "ForEach-Object { \$p = \$_.Program; \$_ | Get-NetFirewallRule | " +
            "Where-Object { \$_.Direction -eq 'Inbound' -and \$_.Enabled -eq 'True' } | " +
            "ForEach-Object { \$_.DisplayName + '|' + \$_.Action + '|' + \$_.Profile + '|' + \$p } }"
    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(FIREWALL_QUERY_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return ""
    }
    return if (process.exitValue() == 0) text else ""
}

private const val FIREWALL_QUERY_SECONDS = 15L

/// Read `name|action|profile|program` lines; anything else is skipped.
/// The program is the last field because a rule's name may itself hold
/// a bar.
internal fun parseFirewallRules(output: String): List<FirewallRule> =
    output.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            val program = parts.last().trim()
            val profile = parts[parts.size - 2].trim()
            val action = parts[parts.size - 3].trim()
            val name = parts.dropLast(3).joinToString("|").trim()
            if (name.isEmpty() || action.isEmpty() || program.isEmpty()) null
            else FirewallRule(name, action, profile, program)
        }
        .toList()

/// What the sync screen says when a Block rule stands, or null. Names the
/// rule and its profile, so the person who opens the firewall console can
/// find the row, and says that no network setting will get round it.
internal fun firewallBlockWarning(rules: List<FirewallRule>): String? {
    val blocks = rules.filter { it.action.equals("Block", ignoreCase = true) }
    if (blocks.isEmpty()) return null
    val named = blocks.joinToString(" and ") { "“${it.name}” (${it.profile})" }
    return "Windows holds a firewall rule that blocks Gilune's runtime: $named. A Block " +
        "rule wins over every allowance on every network, so other devices cannot reach " +
        "this machine until it is removed — it is usually left behind by clicking Cancel " +
        "on the firewall prompt, or by the prompt appearing for someone who is not an " +
        "administrator. An administrator can delete that row under Windows Defender " +
        "Firewall ▸ Advanced settings ▸ Inbound Rules and allow Gilune once; the user " +
        "guide has the exact commands. Syncing from this machine to the others still works."
}

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
