// The `filter` block (ADR 0015 § 4, T4): what a block *is* before it is
// evaluated, and what it lists when it is. Pure — a temp vault on disk and
// an injected `today`, no screen and no clock, so nothing here rots on the
// day it was written.
//
// The removal story is asserted from the same entry the shell calls: with
// the tasks plugin absent, `type:task` is a type nobody registered, and
// the block says so rather than showing an empty list.

package org.gilune.app

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import uniffi.gilune_ffi.PluginInfo

class FilterBlockTest {
    /// A Thursday, and the day every assertion below is written against.
    private val today = LocalDate.of(2026, 9, 3)

    private val withTasks: List<PluginInfo> = listOf(installedTasksPlugin())
    private val withoutTasks: List<PluginInfo> = emptyList()

    private val types: List<SearchType> = searchTypesFor(withTasks)
    private val typesWithoutTasks: List<SearchType> = searchTypesFor(withoutTasks)

    private val indexed: IndexedVault by lazy { buildVault() }
    private val vault: String get() = indexed.dir
    private val gilune get() = indexed.gilune

    private fun buildVault(): IndexedVault {
        val built = IndexedVault("gilune-filter-block")
        fun write(relPath: String, text: String) {
            built.write(relPath, text)
        }
        write(
            "Work/project.md",
            """
            ---
            gilune-id: id-project
            tags: work
            ---
            Notes about the project.
            - [ ] renew the passport due:2026-09-05
            - [ ] file the return due:2026-09-01
            - [x] post the letter due:2026-09-02 done:2026-09-02
            - [ ] someday tidy the loft
            """.trimIndent(),
        )
        write(
            "Home/kitchen.md",
            """
            ---
            gilune-id: id-kitchen
            ---
            - [ ] buy milk due:2026-09-05
            Some prose about milk.
            """.trimIndent(),
        )
        // A note documenting the syntax. § 1's fence rule and § 4's block
        // meet here: the example inside the fence is documentation, and
        // must never become somebody's agenda.
        write(
            "Docs/filters.md",
            """
            ---
            gilune-id: id-docs
            ---
            How to write one:

            ```filter
            type:task
            - [ ] the documented example due:2026-09-04
            ```
            """.trimIndent(),
        )
        return built
    }

    private fun run(block: String, cap: Int = FILTER_ROW_CAP, registry: List<SearchType> = types) =
        evaluateFilterBlock(gilune, vault, block, registry, today, cap)

    /// The task's own words. The panel's `taskLabel` says this properly
    /// and is tested where it lives; it is desktop code and this test is
    /// shared, so the rows are read plainly here instead.
    private fun words(result: FilterResult): List<String> =
        (result as FilterResult.Tasks).rows.map { it.text.substringAfter("] ").substringBefore(" due:").trim() }

    // ------------------------------------------------------------------
    // Classification: what a block is, decided before anything is run
    // ------------------------------------------------------------------

    @Test
    fun anEmptyBlockIsEmptyRatherThanAQueryForEverything() {
        assertEquals(FilterBlock.Empty, classifyFilterBlock("", types))
        assertEquals(FilterBlock.Empty, classifyFilterBlock("\n\n   \n\t\n", types))
    }

    @Test
    fun aBlockWithoutATypeIsNeverGuessedAt() {
        assertEquals(FilterBlock.NoType, classifyFilterBlock("due:week", types))
        assertEquals(FilterBlock.NoType, classifyFilterBlock("path:Work\n-done:none", types))
    }

    @Test
    fun aTypeNobodyRegisteredIsItsOwnAnswer() {
        assertEquals(FilterBlock.UnknownType("widget"), classifyFilterBlock("type:widget", types))
    }

    @Test
    fun aRegisteredTypeCarriesItsRegisteredSpelling() {
        assertEquals(
            FilterBlock.Runnable("type:task due:week", TASK_SEARCH_TYPE),
            classifyFilterBlock("type:task\ndue:week", types),
        )
    }

    @Test
    fun theTypeTermIsReadExactlyAsTheEngineReadsIt() {
        // Case-insensitive, and the first occurrence wins — a query says
        // what it is looking at once.
        assertEquals(
            FilterBlock.Runnable("TYPE:Task type:note", TASK_SEARCH_TYPE),
            classifyFilterBlock("TYPE:Task\ntype:note", types),
        )
        // A bracketed metadata term is a front-matter term, never a type.
        assertEquals(FilterBlock.NoType, classifyFilterBlock("[type:task]", types))
        // `-type:` is not a form: negating what a hit *is* means nothing,
        // so it degrades to prose exactly as the engine degrades it.
        assertEquals(FilterBlock.NoType, classifyFilterBlock("-type:task", types))
        // An empty argument is not a type term either.
        assertEquals(FilterBlock.NoType, classifyFilterBlock("type:", types))
    }

    @Test
    fun noteAnswersWhetherOrNotTheCallerListedIt() {
        // `searchVault` always resolves `note`, so classification must
        // never call unknown a block the engine would happily run.
        assertEquals(
            FilterBlock.Runnable("type:note milk", NOTE_SEARCH_TYPE.key),
            classifyFilterBlock("type:note milk", emptyList()),
        )
    }

    // ------------------------------------------------------------------
    // Line joining *is* the AND rule (§ 4)
    // ------------------------------------------------------------------

    @Test
    fun linesAreTrimmedBlanksDroppedAndTheRestJoinedIntoOneQuery() {
        assertEquals("type:task due:week path:Work", filterQuery("  type:task \n\n\tdue:week\n path:Work  \n"))
        assertEquals("", filterQuery("   \n \n"))
    }

    @Test
    fun eachLineNarrowsTheOneBefore() {
        // One line: every open and closed task in the vault.
        assertEquals(5, (run("type:task") as FilterResult.Tasks).rows.size)
        // Two: the same query, narrowed to one note.
        assertEquals(
            listOf("file the return", "post the letter", "renew the passport", "someday tidy the loft"),
            words(run("type:task\npath:Work")).sorted(),
        )
        // Three: and narrowed again to the one with no due date at all.
        assertEquals(listOf("someday tidy the loft"), words(run("type:task\npath:Work\ndue:none")))
    }

    @Test
    fun whitespaceInsideALineAndsJustAsAJoinDoes() {
        assertEquals(
            words(run("type:task\npath:Work\ndue:none")),
            words(run("type:task path:Work due:none")),
            "the DSL's own semantics applied twice over — one filter per line is not a second grammar",
        )
    }

    // ------------------------------------------------------------------
    // What a block lists
    // ------------------------------------------------------------------

    @Test
    fun aTaskBlockListsTheLinesItsFiltersKeepInTheOneOrderAnAgendaNeeds() {
        val rows = (run("type:task\ndue:week") as FilterResult.Tasks).rows
        // Both are due the 5th, so path order decides; the overdue and the
        // done-and-dusted are outside the week and are not here.
        assertEquals(listOf("buy milk", "renew the passport"), words(FilterResult.Tasks(rows, 0)))
        assertEquals(listOf("Home/kitchen.md", "Work/project.md"), rows.map { it.relPath })
        assertEquals(listOf(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5)), rows.map { it.due })
    }

    @Test
    fun aRowCarriesTheLineItCameFromSoAClickHasSomewhereToGo() {
        val row = (run("type:task\ndue:2026-09-05\npath:Home") as FilterResult.Tasks).rows.single()
        assertEquals("Home/kitchen.md", row.relPath)
        assertEquals("kitchen", row.name)
        assertEquals(1, row.line, "body-relative and 1-based, so the editor can address it")
    }

    @Test
    fun aNoteBlockListsNotes() {
        val rows = (run("type:note\nmilk") as FilterResult.Notes).rows
        assertEquals(listOf(NoteRow("kitchen", "Home/kitchen.md")), rows)
    }

    @Test
    fun aBlockNeverMatchesTheExampleInsideItsOwnFence() {
        // § 1: not inside a fenced code block, so a documented `filter`
        // never becomes somebody's agenda.
        val texts = words(run("type:task"))
        assertFalse(texts.any { it.contains("documented example") }, "the fenced example is documentation: $texts")
    }

    @Test
    fun aQueryThatMatchesNothingIsAnEmptyResultRatherThanAHint() {
        val result = run("type:task\npath:NoSuchFolder")
        assertTrue(result is FilterResult.Tasks && result.rows.isEmpty() && result.more == 0)
    }

    // ------------------------------------------------------------------
    // § 4's cap, which is on rendering and is not optional
    // ------------------------------------------------------------------

    @Test
    fun theCapIsOnRowsDrawnAndTheRestAreCounted() {
        val result = run("type:task", cap = 3) as FilterResult.Tasks
        assertEquals(3, result.rows.size)
        assertEquals(2, result.more, "the footer reports what it is not showing")
    }

    @Test
    fun aResultInsideTheCapCountsNothingExtra() {
        val result = run("type:task\ndue:week") as FilterResult.Tasks
        assertEquals(2, result.rows.size)
        assertEquals(0, result.more)
    }

    @Test
    fun fiftyIsTheShippedCap() {
        val many = IndexedVault("gilune-filter-cap")
        many.write("many.md", (1..60).joinToString("\n") { "- [ ] task $it due:2026-09-05" })
        val result =
            evaluateFilterBlock(many.gilune, many.dir, "type:task", types, today) as FilterResult.Tasks
        assertEquals(FILTER_ROW_CAP, result.rows.size)
        assertEquals(10, result.more)
    }

    // ------------------------------------------------------------------
    // Edit mode (owner, 2026-09-01): the block renders there too, on the
    // editor's own line grid — so it has its own cap, and a count of the
    // lines it needs that the transformation can reserve
    // ------------------------------------------------------------------

    @Test
    fun theEditModeCapIsEightRowsAndTheRestAreCounted() {
        val many = IndexedVault("gilune-filter-edit-cap")
        many.write("many.md", (1..20).joinToString("\n") { "- [ ] task $it due:2026-09-05" })
        val result = evaluateFilterBlock(
            many.gilune,
            many.dir,
            "type:task",
            types,
            today,
            FILTER_EDIT_ROW_CAP,
        ) as FilterResult.Tasks
        assertEquals(8, FILTER_EDIT_ROW_CAP, "a block must not eat the note it is written in")
        assertEquals(8, result.rows.size)
        assertEquals(12, result.more)
        assertEquals(9, filterBlockLines(result), "eight rows, and the line that counts the rest")
    }

    @Test
    fun aBlockReservesOneLinePerRowItDraws() {
        assertEquals(1, filterBlockLines(FilterResult.Hint("anything")))
        assertEquals(1, filterBlockLines(FilterResult.Tasks(emptyList(), 0)), "no matches is still a line saying so")
        assertEquals(1, filterBlockLines(FilterResult.Notes(emptyList(), 0)))
        assertEquals(2, filterBlockLines(run("type:task\ndue:week")), "two rows, two lines")
        assertEquals(1, filterBlockLines(run("type:note\nmilk")))
        assertEquals(4, filterBlockLines(FilterResult.Notes(List(3) { NoteRow("n", "n.md") }, 2)))
    }

    // ------------------------------------------------------------------
    // The three degenerate blocks, and the words they say
    // ------------------------------------------------------------------

    @Test
    fun anEmptyBlockRunsNoQueryAndSaysItMatchesNothing() {
        assertEquals(FilterResult.Hint("an empty filter matches nothing"), run("\n  \n"))
    }

    @Test
    fun aBlockWithNoTypeIsToldWhichTypesThereAre() {
        assertEquals(
            FilterResult.Hint("a filter names its type: — try type:note or type:task"),
            run("due:week"),
        )
        // With the contributor gone the hint offers only what is left,
        // rather than naming a type the block could not run either.
        assertEquals(
            FilterResult.Hint("a filter names its type: — try type:note"),
            run("due:week", registry = typesWithoutTasks),
        )
    }

    @Test
    fun anUnregisteredTypeIsNamedAndTheLikelyReasonGiven() {
        assertEquals(
            FilterResult.Hint("no type called widget is registered — is its plugin on?"),
            run("type:widget"),
        )
    }

    // ------------------------------------------------------------------
    // Removal (§ 4): the fence is the shell's, the type was the plugin's
    // ------------------------------------------------------------------

    @Test
    fun withoutTheTasksPluginTheTaskTypeIsOneNobodyRegistered() {
        assertEquals(FilterBlock.UnknownType("task"), classifyFilterBlock("type:task\ndue:week", typesWithoutTasks))
        assertEquals(
            FilterResult.Hint("no type called task is registered — is its plugin on?"),
            run("type:task\ndue:week", registry = typesWithoutTasks),
            "the honest answer: the type it wanted is gone, and an empty list would not have said so",
        )
    }

    @Test
    fun theSameBlockRunsAgainAsSoonAsThePluginIsBack() {
        // Nothing was rewritten while it was off, so switching back on is
        // the whole of the repair.
        assertTrue(run("type:task\ndue:week", registry = typesWithoutTasks) is FilterResult.Hint)
        assertTrue(run("type:task\ndue:week", registry = types) is FilterResult.Tasks)
    }

    @Test
    fun theLivePluginListIsWhatDecidesAndTheShellsEntryReadsIt() {
        // The entry the renderer calls: types come from the plugins, so
        // the removal story cannot be got wrong by a caller.
        assertTrue(runFilterBlock(gilune, vault, "type:task", withTasks, today) is FilterResult.Tasks)
        assertTrue(runFilterBlock(gilune, vault, "type:task", withoutTasks, today) is FilterResult.Hint)
    }

    @Test
    fun aDisabledPluginIsAsGoneAsARemovedOne() {
        val disabled: List<PluginInfo> = listOf(installedTasksPlugin(state = "disabled"))
        assertEquals(
            FilterResult.Hint("no type called task is registered — is its plugin on?"),
            runFilterBlock(gilune, vault, "type:task", disabled, today),
        )
    }

    // ------------------------------------------------------------------
    // The seam itself (§ 5): a table, and whose registration it is
    // ------------------------------------------------------------------

    @Test
    fun anInfoStringNobodyRegisteredIsNotInTheTable() {
        val table = BlockRenderers(listOf(BlockRegistration(FILTER_FENCE) {}))
        assertTrue(table[FILTER_FENCE] != null)
        // Matched case-insensitively and trimmed, as a fence info string
        // arrives from the parser.
        assertTrue(table["Filter"] != null)
        assertTrue(table[" filter "] != null)
        // Everything else falls through to the code box, which is what T4
        // is not done without.
        assertTrue(table["sql"] == null)
        assertTrue(table["tasks"] == null)
        assertTrue(table[null] == null, "an unfenced code block carries no info string at all")
        // And with nothing registered, every fence is a code block again.
        assertTrue(BlockRenderers()[FILTER_FENCE] == null)
    }

    @Test
    fun theFenceIsTheShellsRegistrationAndNotThePluginsContribution() {
        // § 4: "what plugins contribute is the type, not the fence". The
        // tasks plugin declares a `search.types` contribution and nothing
        // at all in `editor.blocks`, which is why removing it leaves the
        // block rendering — as a hint row naming the type that has gone.
        val contributions = tasksDeclaration().contributions
        assertTrue(contributions.none { it.slot == Slots.EDITOR_BLOCKS })
        assertTrue(contributions.any { it.slot == Slots.SEARCH_TYPES })
    }

    // ------------------------------------------------------------------
    // The global filter (§ 1) reaches the block through the one setting
    // ------------------------------------------------------------------

    @Test
    fun theGlobalFilterNarrowsWhatABlockCallsATask() {
        val filtered: List<PluginInfo> = listOf(installedTasksPlugin(settings = tasksSettingsDocument("#task")))
        val result = runFilterBlock(gilune, vault, "type:task", filtered, today) as FilterResult.Tasks
        assertTrue(result.rows.isEmpty(), "no line in the vault carries #task, so none of them is one")
    }
}
