// Windows Firewall, as the installed app sees it. Sync listens on a port
// (ADR 0019 § 2), and Windows lets other devices reach it only through an
// inbound rule for this launcher on the Private profile. A per-machine
// install adds that rule itself, and so does a per-user install someone
// started as administrator; any other per-user install has no elevated
// moment, so the app asks for it once: `Gilune.exe --allow-firewall`,
// started with administrator approval. Either way the app can read where it stands,
// because Windows keeps every rule as a string under a registry key any
// user can read, in no display language at all.

package org.gilune.app

import java.io.File

/// One rule as Windows stores it: `v2.33|Action=Allow|Active=TRUE|Dir=In|
/// Profile=Private|App=C:\...\Gilune.exe|Name=Gilune|...`. No profile means
/// every profile.
data class FirewallRule(
    val name: String,
    val action: String,
    val active: Boolean,
    val direction: String,
    val profiles: Set<String>,
    val program: String?,
) {
    fun covers(profile: String) = profiles.isEmpty() || profiles.any { it.equals(profile, ignoreCase = true) }
}

enum class FirewallVerdict {
    /// An active inbound Allow for the launcher covers Private, and no Block does.
    ALLOWED,
    /// Nothing lets the launcher through on Private yet.
    MISSING,
    /// An active inbound Block covers Private — what Cancel on the prompt,
    /// or the prompt shown to a non-administrator, leaves behind. It beats
    /// every Allow beside it.
    BLOCKED,
    /// The rules could not be read (not Windows, or the key is unreadable).
    UNKNOWN,
}

const val FIREWALL_RULES_KEY =
    """HKLM\SYSTEM\CurrentControlSet\Services\SharedAccess\Parameters\FirewallPolicy\FirewallRules"""

/// Parses `reg query` output for the rules key. Lines that are not rules
/// (the key name, blanks) are skipped.
fun parseFirewallRules(regQueryOutput: String): List<FirewallRule> =
    regQueryOutput.lineSequence().mapNotNull { line ->
        val marker = line.indexOf("REG_SZ")
        if (marker < 0) return@mapNotNull null
        val value = line.substring(marker + "REG_SZ".length).trim()
        if (!value.startsWith("v")) return@mapNotNull null
        val fields = value.split('|').drop(1).mapNotNull { field ->
            val equals = field.indexOf('=')
            if (equals <= 0) null else field.substring(0, equals) to field.substring(equals + 1)
        }
        fun first(key: String) = fields.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
        FirewallRule(
            name = first("Name").orEmpty(),
            action = first("Action").orEmpty(),
            active = first("Active")?.equals("TRUE", ignoreCase = true) ?: true,
            direction = first("Dir").orEmpty(),
            profiles = fields.filter { it.first.equals("Profile", ignoreCase = true) }.map { it.second }.toSet(),
            program = first("App")?.let(::expandEnvironment),
        )
    }.toList()

/// Windows writes some program paths with `%ProgramFiles%`-style tokens.
internal fun expandEnvironment(path: String, env: (String) -> String? = System::getenv): String =
    Regex("%([^%]+)%").replace(path) { match -> env(match.groupValues[1]) ?: match.value }

/// The verdict for one program, on the Private profile only — a Block on
/// Public is what Windows itself writes when Private alone is ticked, and
/// is no problem.
fun firewallVerdict(rules: List<FirewallRule>, program: File): FirewallVerdict {
    // As given, not absolutePath: the launcher's path is absolute already,
    // and on Linux (the hosted suite) absolutePath would put the runner's
    // working directory in front of a Windows path.
    val path = program.path
    val mine = rules.filter {
        it.active && it.direction.equals("In", ignoreCase = true) &&
            it.program != null && it.program.equals(path, ignoreCase = true) && it.covers("Private")
    }
    return when {
        mine.any { it.action.equals("Block", ignoreCase = true) } -> FirewallVerdict.BLOCKED
        mine.any { it.action.equals("Allow", ignoreCase = true) } -> FirewallVerdict.ALLOWED
        else -> FirewallVerdict.MISSING
    }
}

/// What the elevated step answered.
sealed interface FirewallOutcome {
    data object Allowed : FirewallOutcome
    /// Windows asked and the person said no (or had no administrator to ask).
    data object Declined : FirewallOutcome
    data class Failed(val words: String) : FirewallOutcome
}

object WindowsFirewall {
    fun inspect(program: File): FirewallVerdict {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return FirewallVerdict.UNKNOWN
        return runCatching {
            val process = ProcessBuilder(
                System.getenv("SystemRoot").orEmpty() + """\System32\reg.exe""", "query", FIREWALL_RULES_KEY,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.use { it.readBytes().decodeToString() }
            if (process.waitFor() != 0) FirewallVerdict.UNKNOWN else firewallVerdict(parseFirewallRules(output), program)
        }.getOrDefault(FirewallVerdict.UNKNOWN)
    }

    /// Starts `launcher --allow-firewall` with administrator approval and
    /// waits for it. Windows shows one prompt, naming Gilune.
    fun allow(launcher: File): FirewallOutcome = runCatching {
        val path = launcher.absolutePath.replace("'", "''")
        val process = ProcessBuilder(
            System.getenv("SystemRoot").orEmpty() + """\System32\WindowsPowerShell\v1.0\powershell.exe""",
            "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
            "\$p = Start-Process -FilePath '$path' -ArgumentList '--allow-firewall' -Verb RunAs -Wait -PassThru; exit \$p.ExitCode",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        when {
            process.waitFor() == 0 -> FirewallOutcome.Allowed
            output.contains("canceled by the user", ignoreCase = true) ||
                output.contains("cancelled by the user", ignoreCase = true) -> FirewallOutcome.Declined
            else -> FirewallOutcome.Failed(output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "netsh refused the rule")
        }
    }.getOrElse { FirewallOutcome.Failed(it.message ?: it.javaClass.simpleName) }
}
