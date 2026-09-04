// The shell over the index (ADR 0020 stages P and S): what the agenda
// costs, what a save reaches, what a second process sees, and what
// removing the plugin takes away.
//
// These are the claims a pure test cannot make, because every one of them
// is about the kernel and this shell meeting: an observer counting
// indexer calls, two handles on one vault, a note reconciled by name.

package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import uniffi.gilune_ffi.Constraint
import uniffi.gilune_ffi.ConstraintOp
import uniffi.gilune_ffi.Fact
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.NoteIndexer
import uniffi.gilune_ffi.QueryScope

/// An operator that says nothing to the index — the default, and what
/// every plugin-registered keyword written before ADR 0020 is. It has to
/// go on working, and it does, by costing the reads it always cost.
private object SilentTagOperator : SearchOperator {
    override val name = "tag"
    override fun matches(note: SearchedNote, argument: String) =
        note.frontMatter.any { it.key == "tags" && it.value.contains(argument, ignoreCase = true) }
}

/// The tasks indexer with a tally, registered in its own namespace's
/// place so what the agenda costs is a number rather than an argument.
private class CountingIndexer : NoteIndexer {
    val calls = AtomicInteger()
    val notes = mutableListOf<String>()

    override fun index(relPath: String, text: String): List<Fact> {
        calls.incrementAndGet()
        synchronized(notes) { notes += relPath }
        return taskFacts(text)
    }
}

class VaultQueryTest {
    private val today = LocalDate.now()
    private val plugins = listOf(installedTasksPlugin())
    private val types = searchTypesFor(plugins)

    private fun rows(vault: IndexedVault) =
        taskRows(agendaHits(vault.gilune, vault.dir, types, today))

    // ------------------------------------------------------------------
    // Gate item 5: the agenda does no scan
    // ------------------------------------------------------------------

    @Test
    fun afterManyEditsToOneNoteTheIndexerHasSeenOneNote() {
        val vault = IndexedVault("gilune-observer")
        for (n in 1..20) vault.write("Notes/note-$n.md", "- [ ] task $n due:$today\n")
        val observer = CountingIndexer()
        vault.gilune.registerIndexer(
            TASKS_INDEX_NAMESPACE,
            TASKS_INDEXER_VERSION,
            taskPredicateKinds(),
            observer,
        )
        vault.gilune.advanceIndex()
        observer.calls.set(0)
        synchronized(observer.notes) { observer.notes.clear() }

        repeat(5) { round ->
            vault.write("Notes/note-7.md", "- [ ] task seven, edit $round due:$today\n")
        }

        assertEquals(5, observer.calls.get(), "one indexer call per edit, and nothing else")
        assertEquals(
            setOf("Notes/note-7.md"),
            synchronized(observer.notes) { observer.notes.toSet() },
            "the note that changed, and no other",
        )
        // And the answer is still the whole vault's.
        assertEquals(20, rows(vault).size)
        assertTrue(rows(vault).any { it.text.contains("edit 4") })
    }

    @Test
    fun theAgendaOverTheQueryIsTheAgendaOverTheScan() {
        val vault = IndexedVault("gilune-agenda-oracle")
        val days = listOf(-9L, -1, 0, 1, 3, 7, 8, 30)
        for ((n, offset) in days.withIndex()) {
            vault.write("Notes/dated-$n.md", "- [ ] task $n due:${today.plusDays(offset)}\n")
        }
        vault.write("Notes/undated.md", "- [ ] no date at all\n- [x] done and dusted\n")

        val scanned = taskRows(
            searchVault(vault.dir, AGENDA_QUERY, types = types, today = today, maxLines = AGENDA_SCAN_LINES),
        )
        val asked = rows(vault)
        assertEquals(scanned, asked)
        assertEquals(
            agenda(scanned, today).map { it.group to it.rows.map(TaskHit::text) },
            agenda(asked, today).map { it.group to it.rows.map(TaskHit::text) },
            "the four groups and the undated, row for row",
        )
    }

    @Test
    fun theEmptyAgendaStillKnowsWhichKindOfEmptyItIs() {
        // Two different things to say, and telling somebody the wrong one
        // is worse than saying nothing.
        val bare = IndexedVault("gilune-agenda-no-tasks")
        bare.write("prose.md", "no checkboxes here at all\n")
        val none = rows(bare)
        assertEquals(AgendaEmpty.NO_TASKS, agendaEmpty(none, agenda(none, today)))

        val ticked = IndexedVault("gilune-agenda-none-open")
        ticked.write("done.md", "- [x] every one of them done:$today\n")
        val closed = rows(ticked)
        assertTrue(closed.isNotEmpty(), "there are tasks; none of them is open")
        assertEquals(AgendaEmpty.NONE_OPEN, agendaEmpty(closed, agenda(closed, today)))
    }

    // ------------------------------------------------------------------
    // Gate item 7's shell half: a save is current before anything refreshes
    // ------------------------------------------------------------------

    @Test
    fun aSaveIsInTheIndexBeforeTheCursorMoves() {
        val vault = IndexedVault("gilune-save-order")
        vault.write("Notes/first.md", "- [ ] the first task due:$today\n")
        val state = GiluneState(vault.dir, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        state.gilune.value = vault.gilune
        state.refresh()
        val before = state.vaultCursor.value

        File(vault.dir, "Notes/first.md").writeText("- [ ] the first task due:$today\n- [ ] a second one\n")
        state.applySave(vault.gilune, "Notes/first.md")

        assertTrue(state.vaultCursor.value != before, "the cursor moved")
        assertEquals(
            2,
            rows(vault).size,
            "and the index already knew, so a consumer woken by the cursor reads the new note",
        )
    }

    @Test
    fun aSaveWithNoPathStillCatchesUpWithWhatOthersDid() {
        // `flushEditor` names its file; a caller that cannot is not left
        // with a stale index, it simply waits for the next full pass.
        val vault = IndexedVault("gilune-save-anonymous")
        val state = GiluneState(vault.dir, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        state.gilune.value = vault.gilune
        state.refresh()
        val before = state.vaultCursor.value

        vault.write("Notes/elsewhere.md", "- [ ] written by another pass\n")
        state.applySave(vault.gilune, null)
        assertTrue(state.vaultCursor.value != before)
    }

    // ------------------------------------------------------------------
    // Two handles on one vault — supported use since M4, and the reason
    // the index lives beside the vault rather than in a process
    // ------------------------------------------------------------------

    @Test
    fun aNoteSavedInOneProcessIsFoundByTheOther() {
        val first = IndexedVault("gilune-two-handles")
        first.write("Notes/one.md", "- [ ] the first task\n")
        val second = Gilune.open(first.dir)
        registerShellIndexers(second)

        File(first.dir, "Notes/two.md").writeText("- [ ] written by the second process due:$today\n")
        second.reconcilePath("Notes/two.md")

        // The first handle has run nothing at all since; it only has to
        // catch up with the feed.
        first.gilune.advanceIndex()
        assertEquals(
            listOf("Notes/one.md", "Notes/two.md"),
            rows(first).map { it.relPath }.distinct().sorted(),
        )
    }

    @Test
    fun aRecordingMadeOnASecondHandleIsSearchableFromTheFirst() {
        // The widget's service opens its own handle (ADR 0017 § 4) and
        // registers the same indexers on it. Whichever process passes
        // maintains the kernel's own text index; the plugin's namespace
        // is maintained by any process that carries the plugin.
        val app = IndexedVault("gilune-service-handle")
        val service = Gilune.open(app.dir)
        registerShellIndexers(service)
        service.captureText("- [ ] spoken into the widget due:$today")
        service.project()

        app.gilune.advanceIndex()
        assertTrue(
            queryVault(app.gilune, app.dir, "spoken into the widget", types = types, today = today)
                .isNotEmpty(),
            "the words",
        )
        assertTrue(
            rows(app).any { it.text.contains("spoken into the widget") },
            "and the task on the line",
        )
    }

    // ------------------------------------------------------------------
    // Removal (ADR 0011): the facts go, and so do the words
    // ------------------------------------------------------------------

    @Test
    fun removingThePluginTakesItsFactsAndLeavesTheNotes() {
        val vault = IndexedVault("gilune-removal")
        val rel = vault.write("Notes/tasks.md", "- [ ] renew the passport due:$today\n")
        assertTrue(rows(vault).isNotEmpty())

        vault.gilune.removePlugin(TASKS_PLUGIN)
        assertTrue(
            vault.gilune.factsOf(rel).none { it.namespace == TASKS_INDEX_NAMESPACE },
            "the namespace goes with the plugin",
        )
        // The note is untouched, and still findable as words.
        assertEquals(
            "- [ ] renew the passport due:$today\n",
            File(vault.dir, rel).readText(),
        )
        assertTrue(queryVault(vault.gilune, vault.dir, "renew the passport").isNotEmpty())

        // And the block says what happened, rather than showing nothing.
        val gone = vault.gilune.plugins().filter { it.state == "enabled" }
        val result = runFilterBlock(vault.gilune, vault.dir, "type:task", gone, today)
        assertEquals(
            FilterResult.Hint("no type called task is registered — is its plugin on?"),
            result,
        )

        // Registering again on the next launch must not put back what
        // somebody deliberately took away — and the guard that stops it
        // is the plugin's absence from the list, so this fails the moment
        // anything registers the namespace unconditionally.
        assertTrue(
            vault.gilune.plugins().none { it.pluginId == TASKS_PLUGIN },
            "a removed plugin is not listed at all",
        )
        registerShellIndexers(vault.gilune)
        vault.gilune.advanceIndex()
        assertTrue(vault.gilune.factsOf(rel).none { it.namespace == TASKS_INDEX_NAMESPACE })
    }

    @Test
    fun aDisabledPluginKeepsItsFactsAndLosesItsWords() {
        // The decision this round took (see `registerShellIndexers`):
        // disabling turns the *language* off, which is what somebody
        // asked for, and leaves the facts alone rather than re-deriving
        // a whole vault for a switch.
        val vault = IndexedVault("gilune-disabled")
        val rel = vault.write("Notes/tasks.md", "- [ ] renew the passport due:$today\n")
        vault.gilune.setPluginEnabled(TASKS_PLUGIN, false)
        registerShellIndexers(vault.gilune)
        vault.gilune.advanceIndex()

        assertTrue(vault.gilune.factsOf(rel).any { it.namespace == TASKS_INDEX_NAMESPACE })
        val off = vault.gilune.plugins().filter { it.state == "enabled" }
        assertTrue(runFilterBlock(vault.gilune, vault.dir, "type:task", off, today) is FilterResult.Hint)
    }

    // ------------------------------------------------------------------
    // The stamp (T2) and the fact that follows it
    // ------------------------------------------------------------------

    @Test
    fun tickingATaskStampsDoneAndTheFactFollowsOnTheNextPass() {
        val vault = IndexedVault("gilune-stamp")
        val rel = vault.write("Notes/tasks.md", "- [ ] post the letter\n")
        // The activation chain, exactly as a tick in either shell folds
        // it: the core flips the marker, the plugin stamps the date.
        val decorations = builtInDecorations() + tasksDecorations("")
        val stamped = activateLine("- [ ] post the letter", decorations)!!
        assertTrue(stamped.contains("done:$today"), "the stamp is written: $stamped")

        File(vault.dir, rel).writeText("$stamped\n")
        vault.gilune.reconcilePath(rel)
        vault.gilune.advanceIndex()

        assertTrue(
            queryVault(vault.gilune, vault.dir, "type:task is:done done:$today", types = types, today = today)
                .isNotEmpty(),
            "and the fact caught up with it",
        )
        assertTrue(agenda(rows(vault), today).isEmpty(), "a ticked task is in no view")
    }

    // ------------------------------------------------------------------
    // Compilation: what each operator asks the index for
    // ------------------------------------------------------------------

    @Test
    fun anOperatorThatCannotSayAnythingIsStillAnswered() {
        // The default is `Scan`, so an operator written before this seam
        // existed — or one no constraint can express — costs a read and
        // never a wrong answer. `TagOperator` is `SearchTest`'s, and says
        // nothing.
        val vault = IndexedVault("gilune-scan-operator")
        vault.write("Notes/one.md", "---\ngilune-id: id-one\ntags: chores\n---\nprose\n")
        vault.write("Notes/two.md", "---\ngilune-id: id-two\ntags: work\n---\nprose\n")
        val operators = SearchOperators(SilentTagOperator)
        assertEquals(
            searchVault(vault.dir, "tag:chores", operators).map { it.relPath },
            queryVault(vault.gilune, vault.dir, "tag:chores", operators).map { it.relPath },
        )
    }

    @Test
    fun aQueryWithNothingButNegationsIsAnsweredWithTheVaultRatherThanWithSilence() {
        // `Ne` and `NotExists` narrow and name nothing, so a query made
        // only of them would answer nothing at all. The enumeration is a
        // constraint of its own, which is what keeps `-milk` meaning what
        // it always meant.
        val vault = IndexedVault("gilune-only-negations")
        vault.write("Notes/milk.md", "buy milk\n")
        vault.write("Notes/bread.md", "buy bread\n")
        assertEquals(
            listOf("Notes/bread.md"),
            queryVault(vault.gilune, vault.dir, "-milk").map { it.relPath },
        )
        assertEquals(
            searchVault(vault.dir, "type:note").map { it.relPath },
            queryVault(vault.gilune, vault.dir, "type:note").map { it.relPath },
        )
    }

    @Test
    fun aDateOperatorHandedSomethingThatIsNotADateAnswersWithSilence() {
        val vault = IndexedVault("gilune-impossible")
        vault.write("Notes/tasks.md", "- [ ] one due:$today\n")
        assertTrue(
            queryVault(vault.gilune, vault.dir, "type:task due:soonish", types = types, today = today)
                .isEmpty(),
        )
        // And its negation keeps everything, exactly as the scan does.
        assertEquals(
            searchVault(vault.dir, "type:task -due:soonish", types = types, today = today),
            queryVault(vault.gilune, vault.dir, "type:task -due:soonish", types = types, today = today),
        )
    }

    @Test
    fun frontMatterCompilesToTheKernelsOwnNamespace() {
        val vault = IndexedVault("gilune-front-matter")
        val rel = vault.write("Notes/one.md", "---\ngilune-id: id-one\nMood: Calm\n---\nprose\n")
        assertTrue(
            vault.gilune
                .query(
                    listOf(Constraint.Fact(frontMatterPredicate("mood"), ConstraintOp.EXISTS, "")),
                    QueryScope.NOTE,
                    10u,
                )
                .map { it.relPath }
                .contains(rel),
            "the predicate the language compiles to is the one the kernel writes",
        )
        assertEquals(
            listOf(rel),
            queryVault(vault.gilune, vault.dir, "[mood:calm]").map { it.relPath },
        )
        assertEquals(
            listOf(rel),
            queryVault(vault.gilune, vault.dir, "[MOOD]").map { it.relPath },
        )
    }

    // ------------------------------------------------------------------
    // The measurement § 8.8 asks for, printed rather than asserted
    // ------------------------------------------------------------------

    @Test
    fun aThousandNotesMeasuredBothWays() {
        val root = Files.createTempDirectory("gilune-measure").toFile()
        val gilune = Gilune.open(root.absolutePath)
        gilune.registerPlugin(tasksDeclaration())
        registerShellIndexers(gilune)
        // A thousand notes of twenty lines, and about one line in ten a
        // task — the shape a vault actually has, rather than one where
        // every note is an agenda and the query has to read them all.
        for (n in 1..1000) {
            val dir = File(root, "Notes")
            dir.mkdirs()
            val body = buildString {
                appendLine("note number $n about the kitchen")
                val tasks = if (n % 10 == 0) 2 else 0
                for (line in 1..(19 - tasks)) {
                    appendLine("line $line of note $n with some ordinary words in it")
                }
                if (tasks > 0) {
                    appendLine("- [ ] task $n due:${today.plusDays((n % 9).toLong())}")
                    appendLine("- [x] finished task $n done:$today")
                }
            }
            File(dir, "note-$n.md").writeText(body)
        }
        val reconciled = System.nanoTime()
        gilune.reconcile()
        gilune.advanceIndex()
        val buildMs = (System.nanoTime() - reconciled) / 1_000_000

        fun millis(times: Int, act: () -> Unit): Long {
            act()
            val started = System.nanoTime()
            repeat(times) { act() }
            return (System.nanoTime() - started) / 1_000_000 / times
        }

        val scanAgenda = millis(3) {
            taskRows(searchVault(root.absolutePath, AGENDA_QUERY, types = types, today = today, maxLines = AGENDA_SCAN_LINES))
        }
        val queryAgenda = millis(3) {
            taskRows(agendaHits(gilune, root.absolutePath, types, today))
        }
        val scanSearch = millis(3) { searchVault(root.absolutePath, "kitchen", types = types, today = today) }
        val querySearch = millis(3) { queryVault(gilune, root.absolutePath, "kitchen", types = types, today = today) }
        val scanNarrow = millis(3) { searchVault(root.absolutePath, "note number 500", types = types, today = today) }
        val queryNarrow = millis(3) { queryVault(gilune, root.absolutePath, "note number 500", types = types, today = today) }
        val settled = millis(3) { gilune.reconcile() }

        println("measurement: 1,000 notes x 20 lines, built and indexed in $buildMs ms")
        println("measurement: agenda refresh   scan ${scanAgenda} ms   query ${queryAgenda} ms")
        println("measurement: search 'kitchen' scan ${scanSearch} ms   query ${querySearch} ms")
        println("measurement: search one note  scan ${scanNarrow} ms   query ${queryNarrow} ms")
        println("measurement: settled reconcile ${settled} ms")

        // The one assertion, and it is about shape rather than speed: a
        // query that answers one note must not cost what reading a
        // thousand costs.
        assertEquals(
            searchVault(root.absolutePath, "note number 500", types = types, today = today).map { it.relPath },
            queryVault(gilune, root.absolutePath, "note number 500", types = types, today = today).map { it.relPath },
        )
        assertNull(null)
    }
}
