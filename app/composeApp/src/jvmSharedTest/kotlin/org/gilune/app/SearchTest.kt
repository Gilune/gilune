// The query language is a contract with the user's fingers: what they
// type must keep meaning the same thing. These run against a real vault on
// disk, because the walk — dot-directories, attachments, front matter — is
// half of what search has to get right.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// A plugin's operator: `tags` front matter read as a list, which the
/// built-in `[tags:x]` substring form deliberately cannot do. Registering
/// this is the whole extensibility contract.
private object TagOperator : SearchOperator {
    override val name = "tag"
    override fun matches(note: SearchedNote, argument: String) = note.frontMatter
        .filter { it.key == "tags" }
        .flatMap { it.value.split(',') }
        .any { it.trim().equals(argument, ignoreCase = true) }
}

/// A plugin's operator scoped to a type, which is how ADR 0015 § 4 says
/// operators arrive: `starts:` is a keyword under `line` and prose
/// anywhere else. Under the narrowed-note rule the body it reads is one
/// line, so "starts with" means the line's start.
private object StartsOperator : SearchOperator {
    override val name = "starts"
    override fun matches(note: SearchedNote, argument: String) =
        note.body.startsWith(argument, ignoreCase = true)
}

/// A plugin's *type*: a hit is a line, not a note. The other half of the
/// registry, and deliberately generic — the engine's line-level rule is
/// not the tasks plugin's, and this fixture proves it without one.
private val LineType = SearchType(
    key = "line",
    operators = { listOf(StartsOperator) },
    extract = { note ->
        note.lines.mapIndexedNotNull { index, text ->
            if (text.isBlank()) null else CandidateLine(index + 1, text)
        }
    },
)

class SearchTest {
    private val vault: String by lazy { buildVault() }

    private fun buildVault(): String {
        val root = Files.createTempDirectory("gilune-search").toFile()
        fun write(relPath: String, text: String) {
            val file = File(root, relPath)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        write(
            "Kitchen.md",
            """
            ---
            gilune-id: id-kitchen
            gilune-type: source
            tags: home, chores
            ---
            Buy milk and eggs.
            The tap drips onto the floor.
            Ring the plumber tomorrow.
            """.trimIndent(),
        )
        write(
            "Journal/monday.md",
            """
            ---
            gilune-id: id-monday
            gilune-type: interpretation
            mood: bright
            ---
            Walked past the kitchen window twice.
            The kitchen is not mine.
            Coffee afterwards.
            """.trimIndent(),
        )
        write(
            "Journal/tuesday.md",
            """
            ---
            gilune-id: id-tuesday
            tags: work
            ---
            Milk and eggs again.
            A long walk in the rain.
            TODO:call the dentist.
            """.trimIndent(),
        )
        write(
            "Notes/shopping.md",
            """
            Eggs first, then milk.
            The kitchen is small.
            Bread too.
            """.trimIndent(),
        )
        write("Notes/repeat.md", (1..7).joinToString("\n") { "mint line $it" })
        write("Notes/readme.txt", "milk and eggs and mint")
        write(".trash/hidden.md", "milk and eggs and mint")
        return root.toString()
    }

    private fun find(query: String, operators: SearchOperators = SearchOperators()): List<String> =
        searchVault(vault, query, operators).map { it.relPath }

    private fun hit(query: String, relPath: String): SearchHit =
        searchVault(vault, query).first { it.relPath == relPath }

    @Test
    fun aBareTermMatchesNoteContent() {
        assertEquals(listOf("Kitchen.md"), find("plumber"))
    }

    @Test
    fun aBareTermMatchesTheNoteName() {
        val hits = searchVault(vault, "shopping")
        assertEquals(listOf("Notes/shopping.md"), hits.map { it.relPath })
        assertEquals("shopping", hits[0].name)
        assertTrue(hits[0].nameMatched)
        assertTrue(hits[0].lines.isEmpty(), "the name matched, the body did not")
    }

    @Test
    fun whitespaceSeparatedTermsAreAnded() {
        assertEquals(
            listOf("Journal/tuesday.md", "Kitchen.md", "Notes/shopping.md"),
            find("milk eggs").sorted(),
        )
        assertEquals(listOf("Kitchen.md"), find("milk plumber"))
        assertEquals(emptyList(), find("milk dentist plumber"))
    }

    @Test
    fun aQuotedPhraseMatchesOnlyWhereTheWordsAreAdjacent() {
        assertEquals(
            listOf("Journal/tuesday.md", "Kitchen.md"),
            find("\"milk and eggs\"").sorted(),
            "shopping.md has both words but not the phrase",
        )
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(find("kitchen"), find("KITCHEN"))
        assertEquals(
            listOf("Journal/tuesday.md", "Kitchen.md"),
            find("\"MILK AND EGGS\"").sorted(),
        )
    }

    @Test
    fun aLeadingMinusExcludesNotesTheTermMatches() {
        assertEquals(
            listOf("Journal/tuesday.md", "Notes/shopping.md"),
            find("milk -tap").sorted(),
        )
    }

    @Test
    fun negationAppliesToOperatorsToo() {
        assertEquals(listOf("Kitchen.md", "Notes/shopping.md"), find("milk -path:Journal").sorted())
    }

    @Test
    fun fileMatchesTheNoteStem() {
        assertEquals(listOf("Journal/monday.md"), find("file:monday"))
    }

    @Test
    fun pathMatchesTheRelativePath() {
        assertEquals(
            listOf("Journal/monday.md", "Journal/tuesday.md"),
            find("path:Journal/").sorted(),
        )
    }

    @Test
    fun contentSearchesTheBodyAndNotTheName() {
        assertEquals(
            listOf("Journal/monday.md", "Notes/shopping.md"),
            find("content:kitchen").sorted(),
            "Kitchen.md is named for it but never says it",
        )
    }

    @Test
    fun frontMatterIsNotSearchedAsBodyContent() {
        assertEquals(emptyList(), find("content:bright"))
        assertEquals(emptyList(), find("bright"), "bare terms read prose, not metadata")
    }

    @Test
    fun bracketKeyValueMatchesAFrontMatterEntry() {
        assertEquals(listOf("Kitchen.md"), find("[tags:chores]"))
        assertEquals(listOf("Journal/tuesday.md"), find("[tags:work]"))
        assertEquals(emptyList(), find("[tags:kitchen]"))
    }

    @Test
    fun bracketKeyValueAddressesSystemKeys() {
        assertEquals(listOf("Kitchen.md"), find("[gilune-type:source]"))
        assertEquals(listOf("Journal/monday.md"), find("[gilune-type:interpretation]"))
    }

    @Test
    fun bracketKeyAloneMatchesNotesThatHaveTheKey() {
        assertEquals(listOf("Journal/monday.md"), find("[mood]"))
        assertEquals(
            listOf("Journal/monday.md", "Journal/tuesday.md", "Kitchen.md"),
            find("[gilune-id]").sorted(),
        )
    }

    @Test
    fun aNoteMatchedOnlyByMetadataHasNoMatchedLines() {
        val hits = searchVault(vault, "[mood]")
        assertEquals(1, hits.size)
        assertFalse(hits[0].nameMatched)
        assertTrue(hits[0].lines.isEmpty())
        assertEquals(0, hits[0].matchCount)
    }

    @Test
    fun anUnknownOperatorDegradesToABareTerm() {
        assertEquals(
            listOf("Journal/tuesday.md"),
            find("todo:call"),
            "an unregistered keyword is prose, not an empty result set",
        )
    }

    @Test
    fun matchedLineNumbersAreBodyRelativeAndRangesCoverTheMatch() {
        val hit = hit("milk", "Kitchen.md")
        assertEquals(1, hit.lines.size)
        assertEquals(1, hit.lines[0].number, "line 1 of the body, line 6 of the file")
        assertEquals("Buy milk and eggs.", hit.lines[0].text)
        assertEquals(listOf(4..7), hit.lines[0].ranges)
    }

    @Test
    fun everyNeedleOccurrenceIsARangeOnTheMatchedLine() {
        val hit = hit("milk eggs", "Journal/tuesday.md")
        assertEquals(1, hit.lines.size)
        assertEquals("Milk and eggs again.", hit.lines[0].text)
        assertEquals(listOf(0..3, 9..12), hit.lines[0].ranges)
        assertEquals(2, hit.matchCount)
    }

    @Test
    fun repeatedMatchesOnSeparateLinesAreSeparateLines() {
        val hit = hit("kitchen", "Journal/monday.md")
        assertEquals(listOf(1, 2), hit.lines.map { it.number })
        assertEquals(2, hit.matchCount)
        assertEquals(0, hit.moreMatches)
    }

    @Test
    fun matchedLinesAreCappedAndTheRestAreCounted() {
        val hit = hit("mint", "Notes/repeat.md")
        assertEquals(MAX_MATCHED_LINES, hit.lines.size)
        assertEquals(listOf(1, 2, 3, 4, 5), hit.lines.map { it.number })
        assertEquals(2, hit.moreMatches)
        assertEquals(7, hit.matchCount, "the cap hides lines, it does not lose them")
    }

    @Test
    fun aNameMatchOutranksAContentMatch() {
        val hits = searchVault(vault, "kitchen")
        assertEquals("Kitchen.md", hits[0].relPath)
        assertTrue(hits[0].nameMatched)
        assertTrue(hits[0].lines.isEmpty())
    }

    @Test
    fun moreContentMatchesRankHigher() {
        val hits = searchVault(vault, "kitchen").filterNot { it.nameMatched }
        assertEquals(listOf("Journal/monday.md", "Notes/shopping.md"), hits.map { it.relPath })
        assertEquals(listOf(2, 1), hits.map { it.matchCount })
    }

    @Test
    fun aRegisteredOperatorExtendsTheLanguage() {
        val operators = SearchOperators(TagOperator)
        assertTrue("tag" in operators.names)
        assertEquals(listOf("Kitchen.md"), find("tag:chores", operators))
        assertEquals(listOf("Kitchen.md"), find("tag:HOME", operators))
        assertEquals(
            emptyList(),
            find("tag:hom", operators),
            "the plugin's predicate matches whole tags, not substrings",
        )
    }

    @Test
    fun aRegisteredOperatorComposesWithNegation() {
        assertEquals(
            listOf("Journal/tuesday.md", "Notes/shopping.md"),
            find("milk -tag:chores", SearchOperators(TagOperator)).sorted(),
        )
    }

    @Test
    fun anUnregisteredOperatorNameIsJustText() {
        assertFalse("tag" in SearchOperators().names)
        assertEquals(emptyList(), find("tag:chores"))
    }

    @Test
    fun dotDirectoriesAndNonMarkdownFilesAreNotVaultContent() {
        assertEquals(listOf("Notes/repeat.md"), find("mint"))
    }

    @Test
    fun anEmptyQueryFindsNothingRatherThanEverything() {
        assertEquals(emptyList(), find(""))
        assertEquals(emptyList(), find("   "))
        assertEquals(emptyList(), find("\"\""))
    }

    @Test
    fun aMissingVaultDirectoryFindsNothing() {
        assertEquals(emptyList(), searchVault("$vault/nowhere", "milk"))
    }

    // -----------------------------------------------------------------
    // `type:` — what a hit is (ADR 0015 § 4).
    // -----------------------------------------------------------------

    /// Hits, not just paths: the point is that naming the default type
    /// changes nothing at all, ranking and quoted lines included.
    private fun hitsOf(query: String, types: List<SearchType> = listOf(NOTE_SEARCH_TYPE)): List<SearchHit> =
        searchVault(vault, query, SearchOperators(), types)

    private fun findAs(
        query: String,
        types: List<SearchType>,
        operators: SearchOperators = SearchOperators(),
        maxLines: Int = MAX_MATCHED_LINES,
    ): List<String> =
        searchVault(vault, query, operators, types, LocalDate.of(2026, 9, 3), maxLines)
            .flatMap { hit -> hit.lines.map { "${hit.relPath}:${it.number}" } }

    @Test
    fun theDefaultTypeIsNoteAndNamingItChangesNothing() {
        assertEquals(hitsOf("milk"), hitsOf("type:note milk"))
        assertEquals(hitsOf("kitchen"), hitsOf("type:note kitchen"))
    }

    @Test
    fun theTypeKeywordIsCaseInsensitiveAndTheFirstOneWins() {
        assertEquals(hitsOf("milk"), hitsOf("TYPE:NOTE milk"))
        assertEquals(hitsOf("milk"), hitsOf("type:note type:line milk"))
    }

    @Test
    fun aTypeNobodyRegisteredFindsNothing() {
        assertEquals(emptyList(), find("type:person milk"))
        assertEquals(
            emptyList(),
            hitsOf("type:line milk"),
            "the line type exists, but this caller did not register it",
        )
    }

    @Test
    fun aNegatedTypeIsProseRatherThanASelector() {
        assertEquals(
            hitsOf("milk"),
            hitsOf("-type:note milk"),
            "negating what a hit is means nothing, so it degrades like any unknown keyword",
        )
    }

    @Test
    fun aTypeAloneIsEveryHitOfThatTypeButAnEmptyQueryIsStillNothing() {
        assertEquals(
            listOf("Journal/monday.md", "Journal/tuesday.md", "Kitchen.md", "Notes/repeat.md", "Notes/shopping.md"),
            hitsOf("type:note").map { it.relPath }.sorted(),
        )
        assertEquals(emptyList(), find(""), "no type and no terms is still nothing rather than everything")
    }

    // -----------------------------------------------------------------
    // Line-level types: the narrowed-note rule.
    // -----------------------------------------------------------------

    private val lineTypes = listOf(NOTE_SEARCH_TYPE, LineType)

    @Test
    fun underALineTypeEachMatchingLineIsTheHit() {
        assertEquals(
            listOf("Journal/tuesday.md:1", "Kitchen.md:1", "Notes/shopping.md:1"),
            findAs("type:line milk", lineTypes).sorted(),
        )
    }

    @Test
    fun textTermsBindToTheLineWhileNoteTermsBindToTheNote() {
        // Both words are in Kitchen.md, so the note matches; no single
        // line holds both, so no line does.
        assertEquals(listOf("Kitchen.md"), find("milk plumber"))
        assertEquals(emptyList(), findAs("type:line milk plumber", lineTypes))
        // `path:` is about the note, and narrows which lines are offered.
        assertEquals(listOf("Notes/shopping.md:1"), findAs("type:line milk path:Notes", lineTypes))
    }

    @Test
    fun aRegisteredOperatorIsScopedToItsType() {
        assertEquals(
            listOf("Journal/monday.md:2", "Kitchen.md:2", "Notes/shopping.md:2"),
            findAs("type:line starts:The", lineTypes).sorted(),
        )
        assertEquals(
            emptyList(),
            findAs("starts:The", lineTypes),
            "under `note` the keyword is nobody's, so it is prose — and no note says `starts:The`",
        )
    }

    @Test
    fun everyKeptLineIsAHitLineEvenWithNothingToHighlight() {
        val hits = searchVault(vault, "type:line tag:chores", SearchOperators(TagOperator), lineTypes)
        assertEquals(listOf("Kitchen.md"), hits.map { it.relPath })
        assertEquals(listOf(1, 2, 3), hits[0].lines.map { it.number }, "the tag matched the note, so every line is a hit")
        assertTrue(hits[0].lines.all { it.ranges.isEmpty() })
        assertEquals(0, hits[0].matchCount)
    }

    @Test
    fun theLineCapIsTheCallersToRaise() {
        assertEquals(5, findAs("type:line line", lineTypes).size, "the panel's default cap")
        val raised = findAs("type:line line", lineTypes, maxLines = 50)
        assertEquals((1..7).map { "Notes/repeat.md:$it" }, raised, "T4 renders rows, so it lifts the cap")
    }
}
