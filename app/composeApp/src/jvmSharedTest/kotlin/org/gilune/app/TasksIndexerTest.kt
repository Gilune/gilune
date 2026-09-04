// What the tasks plugin says about a note (ADR 0020 § 2.3), asserted as
// the pure function it is: text in, facts out, no vault and no kernel.
//
// The rules that matter here are the ones a wrong indexer would break
// silently — a task quoted in a documentation fence becoming somebody's
// agenda, a `due:` written in prose becoming a date, a line number that
// does not address the buffer a person sees.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import uniffi.gilune_ffi.Fact

class TasksIndexerTest {
    private fun facts(text: String) = taskFacts(text).map { Triple(it.line, it.predicate, it.value) }

    private fun predicates(text: String) = taskFacts(text).map { it.predicate }

    @Test
    fun aTaskLineSaysItsStateAndWhicheverOfItsTwoDatesItCarries() {
        assertEquals(
            listOf(
                Triple(1u, TASK_STATE_PREDICATE, TASK_STATE_OPEN),
                Triple(1u, TASK_DUE_PREDICATE, "2026-09-05"),
            ),
            facts("- [ ] renew the passport due:2026-09-05"),
        )
        assertEquals(
            listOf(
                Triple(1u, TASK_STATE_PREDICATE, TASK_STATE_DONE),
                Triple(1u, TASK_DUE_PREDICATE, "2026-09-01"),
                Triple(1u, TASK_DONE_PREDICATE, "2026-09-02"),
            ),
            facts("- [x] file the return due:2026-09-01 done:2026-09-02"),
        )
        // A ticked box, GFM's capital included, and one with no dates.
        assertEquals(
            listOf(Triple(1u, TASK_STATE_PREDICATE, TASK_STATE_DONE)),
            facts("* [X] post the letter"),
        )
    }

    @Test
    fun linesAreOneBasedWithinTheBodyAndCountTheBlankOnes() {
        // The kernel promises an indexer the note's body with front
        // matter split off, and `TaskHit.line` and `MatchedLine.number`
        // already mean the same thing — so a fact addresses the buffer
        // somebody will see, blank lines and all.
        val body = "a heading\n\n- [ ] the first task\nprose\n- [ ] the second task\n"
        assertEquals(
            listOf(3u, 5u),
            taskFacts(body).filter { it.predicate == TASK_STATE_PREDICATE }.map { it.line },
        )
    }

    @Test
    fun aTaskQuotedInsideAFenceIsDocumentationAndNotSomebodysAgenda() {
        // ADR 0015 § 1's fence rule. The `filter` blocks of § 4 are
        // written as examples in the guides, and an indexer that read
        // them would put every documented task into every agenda.
        val body = """
            How to write one:

            ```filter
            type:task
            - [ ] the documented example due:2026-09-04
            ```

            - [ ] a real one due:2026-09-05
        """.trimIndent()
        assertEquals(
            listOf(8u),
            taskFacts(body).filter { it.predicate == TASK_STATE_PREDICATE }.map { it.line },
        )
        // The second-order case: the delimiters themselves are inside the
        // block, and an unterminated fence swallows what follows it —
        // which is what the shipped extractor does, so the index and the
        // language agree about a note somebody is halfway through
        // writing.
        assertTrue(taskFacts("```\n- [ ] never closed\n").isEmpty())
    }

    @Test
    fun aDueTokenInProseIsProse() {
        // § 1: only on a task line, and only with whitespace before the
        // key, so the token is a token and not the tail of a word.
        assertTrue(taskFacts("the plasterer is due:2026-09-05, he says").isEmpty())
        assertEquals(
            listOf(TASK_STATE_PREDICATE),
            predicates("- [ ] a task whose line also says overdue:2026-09-05"),
        )
        // A date that is well-shaped and does not exist is not a date.
        assertEquals(
            listOf(TASK_STATE_PREDICATE),
            predicates("- [ ] the thirtieth of february due:2026-02-30"),
        )
    }

    @Test
    fun aLineWithTwoDatesIsReadLeftToRightExactlyAsTheOperatorReadsIt() {
        // The operator takes the first token — "a line with two due dates
        // is the user's to sort out" — so the fact has to be the same
        // one, or `due:` in a query and `due:` on the line would disagree.
        assertEquals(
            listOf(Triple(1u, TASK_DUE_PREDICATE, "2026-09-01")),
            facts("- [ ] which is it due:2026-09-01 due:2026-09-09")
                .filter { it.second == TASK_DUE_PREDICATE },
        )
    }

    @Test
    fun theGlobalFilterIsNotAppliedWhenTheFactsAreDerived() {
        // Deliberate (see `taskFacts`): the filter is a setting somebody
        // changes, and facts derived under it would have to be thrown
        // away and re-derived every time they did. It narrows at query
        // time instead, where `taskLines` decides exactly.
        assertEquals(
            listOf(TASK_STATE_PREDICATE),
            predicates("- [ ] a line with no hash on it"),
        )
    }

    @Test
    fun theIndexerIsTheSameFunctionAtTheBoundary() {
        val text = "- [ ] buy milk due:2026-09-05"
        assertEquals(taskFacts(text), TasksNoteIndexer().index("Home/kitchen.md", text))
    }

    @Test
    fun everyPredicateItWritesIsDeclaredWithTheKindThatOrdersIt() {
        // A date stored as text would sort as text, and `due:before:` is
        // a range. A predicate the registration forgot would be typed
        // `Text` by default and quietly stop answering a range at all.
        val declared = taskPredicateKinds().associate { it.predicate to it.kind }
        val written = listOf(
            "- [ ] one due:2026-09-05 done:2026-09-06",
        ).flatMap(::taskFacts).map { it.predicate }.toSet()
        assertEquals(declared.keys, written)
        assertEquals(uniffi.gilune_ffi.ValueKind.DATE, declared[TASK_DUE_PREDICATE])
        assertEquals(uniffi.gilune_ffi.ValueKind.DATE, declared[TASK_DONE_PREDICATE])
        assertEquals(uniffi.gilune_ffi.ValueKind.TEXT, declared[TASK_STATE_PREDICATE])
    }

    @Test
    fun theNamespaceIsThePluginsSoRemovingThePluginTakesIt() {
        assertEquals(TASKS_PLUGIN, TASKS_INDEX_NAMESPACE)
        assertTrue(TASK_STATE_PREDICATE.startsWith("$TASKS_PLUGIN."))
        assertTrue(TASK_DUE_PREDICATE.startsWith("$TASKS_PLUGIN."))
        assertTrue(TASK_DONE_PREDICATE.startsWith("$TASKS_PLUGIN."))
    }

    @Test
    fun anEmptyNoteSaysNothing() {
        assertEquals(emptyList<Fact>(), taskFacts(""))
        assertEquals(emptyList<Fact>(), taskFacts("just prose\nand more prose\n"))
    }
}
