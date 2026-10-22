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
