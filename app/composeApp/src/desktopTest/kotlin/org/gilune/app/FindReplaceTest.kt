// The matching language of find and replace (ADR 0031 § 2). The step past
// the obvious: a literal replacement keeps its `$`, a regex one fills its
// groups; whole word holds at accented letters; a pattern that can only
// match nothing finds nothing; a bad pattern is said, not thrown.

package org.gilune.app

import org.gilune.plugins.findreplace.FindMatch
import org.gilune.plugins.findreplace.FindOptions
import org.gilune.plugins.findreplace.FindOutcome
import org.gilune.plugins.findreplace.FindQuery
import org.gilune.plugins.findreplace.FindReplaceKey
import org.gilune.plugins.findreplace.findMatches
import org.gilune.plugins.findreplace.findReplaceKey
import org.gilune.plugins.findreplace.findReplaceSettingsDocument
import org.gilune.plugins.findreplace.matches
import org.gilune.plugins.findreplace.replaceAll
import org.gilune.plugins.findreplace.stepMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FindReplaceTest {
    private fun ranges(text: String, pattern: String, options: FindOptions = FindOptions()) =
        findMatches(text, FindQuery(pattern, options)).matches.map { it.range }

    @Test
    fun plainFindIgnoresCaseUnlessAsked() {
        assertEquals(listOf(0..3, 10..13), ranges("Milk and  milk", "milk"))
        assertEquals(listOf(10..13), ranges("Milk and  milk", "milk", FindOptions(matchCase = true)))
        // Case folding is Unicode, not ASCII only.
        assertEquals(listOf(0..4), ranges("ÉCOLE", "école"))
    }

    @Test
    fun plainPatternsAreLiteralEvenWhenTheyLookLikeRegex() {
        assertEquals(listOf(5..7), ranges("cost (£) of", "(£)"))
        assertEquals(emptyList(), ranges("a.b", "a\\.b"))
    }

    @Test
    fun wholeWordStopsAtLettersOfAnyAlphabet() {
        assertEquals(listOf(0..2, 14..16), ranges("cat catalogue cat", "cat", FindOptions(wholeWord = true)))
        assertEquals(emptyList(), ranges("écat", "cat", FindOptions(wholeWord = true)), "é is a letter")
        assertEquals(listOf(1..3), ranges("(cat)", "cat", FindOptions(wholeWord = true)))
    }

    @Test
    fun regexAnchorsArePerLineAndEmptyMatchesAreSkipped() {
        val regex = FindOptions(regex = true)
        assertEquals(listOf(0..1, 6..7), ranges("- a\nb\n- c", "^- ", regex))
        assertEquals(emptyList(), ranges("abc", "x*", regex), "a pattern that can only match nothing finds nothing")
        assertEquals(listOf(1..1), ranges("abc", "b*", regex), "the non-empty match among empty ones")
    }

    @Test
    fun aBrokenPatternIsSaidNotThrown() {
        val outcome = findMatches("text", FindQuery("(", FindOptions(regex = true)))
        assertIs<FindOutcome.Invalid>(outcome)
        assertEquals(emptyList(), outcome.matches)
    }

    @Test
    fun aLiteralReplacementKeepsItsDollarSign() {
        assertEquals("costs \$1 and \$1" to 2, replaceAll("costs £ and £", FindQuery("£"), "\$1"))
    }

    @Test
    fun aRegexReplacementFillsItsGroups() {
        val swap = FindQuery("(\\w+)@(\\w+)", FindOptions(regex = true))
        assertEquals("b@a then d@c" to 2, replaceAll("a@b then c@d", swap, "$2@$1"))
        val named = FindQuery("(?<day>\\d{2})/(?<month>\\d{2})", FindOptions(regex = true))
        assertEquals("due 2026-10-31" to 1, replaceAll("due 31/10", named, "2026-\${month}-\${day}"))
    }

    @Test
    fun eachMatchCarriesItsOwnReplacement() {
        val found = findMatches("x1 y22", FindQuery("[a-z](\\d+)", FindOptions(regex = true)), "<$1>").matches
        assertEquals(listOf(FindMatch(0, 2, "<1>"), FindMatch(3, 6, "<22>")), found)
    }

    @Test
    fun aReplacementNamingAMissingGroupIsSaid() {
        assertNull(replaceAll("ab", FindQuery("a", FindOptions(regex = true)), "$3"))
        assertIs<FindOutcome.Invalid>(findMatches("ab", FindQuery("a", FindOptions(regex = true)), "$3"))
    }

    @Test
    fun steppingWrapsAtBothEnds() {
        val matches = listOf(FindMatch(2, 4), FindMatch(10, 12), FindMatch(20, 22))
        assertEquals(1, stepMatch(matches, 4, forward = true))
        assertEquals(0, stepMatch(matches, 21, forward = true), "past the last wraps to the first")
        assertEquals(0, stepMatch(matches, 10, forward = false), "back from a selected match is the one before it")
        assertEquals(2, stepMatch(matches, 1, forward = false), "before the first wraps to the last")
        assertNull(stepMatch(emptyList(), 0, forward = true))
    }

    @Test
    fun everyKeyIsASettingWithADefaultAndRebindingOneKeepsTheOthers() {
        assertEquals("Ctrl+F", findReplaceKey("", FindReplaceKey.FIND))
        val rebound = findReplaceSettingsDocument("""{"replaceKey":"Ctrl+R"}""", FindReplaceKey.FIND, "Alt+F")
        assertEquals("Alt+F", findReplaceKey(rebound, FindReplaceKey.FIND))
        assertEquals("Ctrl+R", findReplaceKey(rebound, FindReplaceKey.REPLACE))
        val reset = findReplaceSettingsDocument(rebound, FindReplaceKey.FIND, null)
        assertEquals("Ctrl+F", findReplaceKey(reset, FindReplaceKey.FIND))
        assertEquals("Ctrl+R", findReplaceKey(reset, FindReplaceKey.REPLACE))
    }
}
