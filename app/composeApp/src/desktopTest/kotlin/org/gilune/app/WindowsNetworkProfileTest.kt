// The Public-network diagnostic, pinned without PowerShell (ADR 0019 § 2).
//
// The parse is pinned because it reads a child process's output and a
// line of PowerShell noise must never become a network; the sentence is
// pinned because it is the whole deliverable — it names the network to
// change and where, and it says that the other direction still works, so
// nobody concludes their notes are stuck.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsNetworkProfileTest {

    @Test
    fun profilesAreReadOneNameAndCategoryPerLineAndNoiseIsSkipped() {
        val read = parseNetworkProfiles(
            """
            Network 4|Private
            TP-Link_EXT|Public

            some warning text without a separator
            Corp|DomainAuthenticated
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                NetworkProfile("Network 4", "Private"),
                NetworkProfile("TP-Link_EXT", "Public"),
                NetworkProfile("Corp", "DomainAuthenticated"),
            ),
            read,
        )
        assertTrue(parseNetworkProfiles("").isEmpty())
        // A network whose name itself carries a bar still parses: the
        // category is after the last one.
        assertEquals(listOf(NetworkProfile("Home|Guest", "Public")), parseNetworkProfiles("Home|Guest|Public"))
    }

    @Test
    fun onlyPublicProfilesAreNamedAndCaseDoesNotMatter() {
        assertEquals(
            listOf("TP-Link_EXT"),
            publicNetworkNames(
                listOf(
                    NetworkProfile("Network 4", "Private"),
                    NetworkProfile("TP-Link_EXT", "public"),
                    NetworkProfile("Corp", "DomainAuthenticated"),
                ),
            ),
        )
    }

    // ------------------------------------------------------ firewall rules

    @Test
    fun firewallRulesAreReadWithTheProgramLastSoABarInANameSurvives() {
        val read = parseFirewallRules(
            """
            OpenJDK Platform binary|Block|Public|C:\program files\gilune\runtime\bin\javaw.exe
            Gilune|Allow|Private|C:\Program Files\Gilune\Gilune.exe
            Odd|name|Allow|Any|C:\Program Files\Gilune\runtime\bin\java.exe

            not a rule line at all
            """.trimIndent(),
        )
        assertEquals(3, read.size, "$read")
        assertEquals(
            FirewallRule("OpenJDK Platform binary", "Block", "Public", "C:\\program files\\gilune\\runtime\\bin\\javaw.exe"),
            read[0],
        )
        assertEquals("Odd|name", read[2].name, "the program is the last field, the name is the rest")
        assertTrue(parseFirewallRules("").isEmpty())
    }

    @Test
    fun onlyRulesNamingThisAppsOwnProgramsCountWhateverTheirCaseOrTokens() {
        val runtime = java.io.File("C:\\Program Files\\Gilune\\runtime")
        val ours = programsOfThisApp(runtime)
        assertTrue("c:\\program files\\gilune\\runtime\\bin\\javaw.exe" in ours, "$ours")
        assertTrue("c:\\program files\\gilune\\gilune.exe" in ours, "the launcher beside the runtime: $ours")

        val rules = listOf(
            FirewallRule("prompt", "Block", "Public", "C:\\PROGRAM FILES\\Gilune\\runtime\\bin\\JAVAW.EXE"),
            FirewallRule("token", "Allow", "Private", "%ProgramFiles%\\Gilune\\Gilune.exe"),
            FirewallRule("another jdk", "Block", "Any", "C:\\Program Files\\Eclipse Adoptium\\jdk-11\\bin\\java.exe"),
        )
        val matched = rulesForPrograms(rules, ours, env = { if (it == "ProgramFiles") "C:\\Program Files" else null })
        assertEquals(listOf("prompt", "token"), matched.map { it.name }, "a different JDK's rule is not ours")
        assertEquals(
            "C:\\Program Files\\Gilune\\Gilune.exe",
            expandEnv("%ProgramFiles%\\Gilune\\Gilune.exe") { if (it == "ProgramFiles") "C:\\Program Files" else null },
        )
        assertEquals("%Nope%\\x", expandEnv("%Nope%\\x") { null }, "an unknown token is left as written")
    }

    @Test
    fun aBlockRuleIsNamedWithItsProfileAndSaidToBeatEveryNetworkSetting() {
        assertNull(firewallBlockWarning(emptyList()))
        assertNull(
            firewallBlockWarning(listOf(FirewallRule("Gilune", "Allow", "Private", "x"))),
            "an allow rule is the healthy state and gets no warning",
        )
        val said = firewallBlockWarning(
            listOf(
                FirewallRule("Gilune", "Allow", "Private", "x"),
                FirewallRule("OpenJDK Platform binary", "Block", "Private, Public", "x"),
            ),
        )!!
        assertTrue(said.contains("\u201cOpenJDK Platform binary\u201d (Private, Public)"), said)
        assertTrue(said.contains("wins over every allowance"), "why Private did not help: $said")
        assertTrue(said.contains("Cancel"), "how it got there: $said")
        assertTrue(said.contains("Inbound Rules"), "where to remove it: $said")
        assertTrue(said.contains("still works"), "the unaffected direction: $said")
    }

    @Test
    fun theWarningNamesTheNetworkWhereToChangeItAndThatTheOtherDirectionStillWorks() {
        assertNull(publicNetworkWarning(emptyList()), "no Public network, no warning")
        val one = publicNetworkWarning(listOf("TP-Link_EXT"))!!
        assertTrue(one.contains("“TP-Link_EXT”"), one)
        assertTrue(one.contains("as Public"), one)
        assertTrue(one.contains("set to Private"), "the fix is named: $one")
        assertTrue(one.contains("Settings"), "and where to do it: $one")
        assertTrue(one.contains("still works"), "and the direction that is unaffected: $one")
        val two = publicNetworkWarning(listOf("A", "B"))!!
        assertTrue(two.contains("networks “A” and “B”"), two)
    }
}
