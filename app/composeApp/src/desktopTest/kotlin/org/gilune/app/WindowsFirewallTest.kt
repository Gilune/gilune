package org.gilune.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsFirewallTest {
    private val launcher = File("""C:\Users\Pat\AppData\Local\Programs\Gilune\Gilune.exe""")

    // `reg query` output for the rules key, as Windows writes it: one
    // string per rule, fields separated by `|`, Profile repeated per
    // profile, absent when the rule covers them all.
    private fun query(vararg rules: String) = buildString {
        appendLine()
        appendLine("""HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\SharedAccess\Parameters\FirewallPolicy\FirewallRules""")
        rules.forEachIndexed { index, rule -> appendLine("    {0000-$index}    REG_SZ    $rule") }
        appendLine()
    }

    @Test fun aRuleStringIsReadFieldByField() {
        val rules = parseFirewallRules(
            query("""v2.33|Action=Block|Active=TRUE|Dir=In|Profile=Public|Profile=Private|App=C:\x\Gilune.exe|Name=OpenJDK Platform binary|Desc=d|EmbedCtxt=e|"""),
        )
        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("OpenJDK Platform binary", rule.name)
        assertEquals("Block", rule.action)
        assertEquals(true, rule.active)
        assertEquals("In", rule.direction)
        assertEquals(setOf("Public", "Private"), rule.profiles)
        assertEquals("""C:\x\Gilune.exe""", rule.program)
    }

    @Test fun theVerdictIsAboutPrivateInboundRulesForThisLauncherOnly() {
        val mine = """App=c:\users\pat\appdata\local\programs\gilune\gilune.exe"""
        val allowPrivate = """v2.33|Action=Allow|Active=TRUE|Dir=In|Profile=Private|$mine|Name=Gilune|"""
        val blockPublic = """v2.33|Action=Block|Active=TRUE|Dir=In|Profile=Public|$mine|Name=Gilune|"""
        val blockAll = """v2.33|Action=Block|Active=TRUE|Dir=In|$mine|Name=OpenJDK Platform binary|"""
        val blockPrivateInactive = """v2.33|Action=Block|Active=FALSE|Dir=In|Profile=Private|$mine|Name=old|"""
        val someoneElse = """v2.33|Action=Block|Active=TRUE|Dir=In|App=C:\Program Files\Other\other.exe|Name=Other|"""
        val outbound = """v2.33|Action=Block|Active=TRUE|Dir=Out|Profile=Private|$mine|Name=out|"""

        fun verdict(vararg rules: String) = firewallVerdict(parseFirewallRules(query(*rules)), launcher)

        assertEquals(FirewallVerdict.MISSING, verdict())
        assertEquals(FirewallVerdict.MISSING, verdict(someoneElse, outbound, blockPrivateInactive))
        // What Windows itself writes when only Private is ticked: allowed.
        assertEquals(FirewallVerdict.ALLOWED, verdict(allowPrivate, blockPublic))
        // Cancel on the prompt: a Block for every profile, which wins.
        assertEquals(FirewallVerdict.BLOCKED, verdict(allowPrivate, blockAll))
        assertEquals(FirewallVerdict.BLOCKED, verdict(blockAll))
        // Case of the stored path is Windows's business, not the verdict's.
        assertEquals(FirewallVerdict.ALLOWED, verdict(allowPrivate.replace(mine, "App=" + launcher.path.uppercase())))
    }

    @Test fun environmentTokensInStoredPathsAreExpanded() {
        val env = mapOf("ProgramFiles" to """C:\Program Files""")
        assertEquals(
            """C:\Program Files\Gilune\Gilune.exe""",
            expandEnvironment("""%ProgramFiles%\Gilune\Gilune.exe""") { env[it] },
        )
        assertEquals("""%Nope%\x.exe""", expandEnvironment("""%Nope%\x.exe""") { env[it] })
    }
}
