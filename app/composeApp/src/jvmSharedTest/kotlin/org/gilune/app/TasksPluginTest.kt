// The tasks plugin as data (ADR 0015 §§ 1, 3, 7): what it declares, what
// its registrations style, which fields survive the both-enabled gate, and
// what the global filter means. All of it is pure over strings — a
// declaration, a JSON config, a settings document and a line of Markdown —
// so none of it needs a screen.

package org.gilune.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import uniffi.gilune_ffi.PluginContribution
import uniffi.gilune_ffi.PluginInfo
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// A plugin as the kernel reports it back. Shared with the desktop UI
/// test, which has to put one in front of the editor.
internal fun installedPlugin(
    pluginId: String,
    state: String = "enabled",
    contributions: List<PluginContribution> = emptyList(),
    settings: String = "",
): PluginInfo = PluginInfo(
    pluginId = pluginId,
    name = pluginId,
    `class` = "domain",
    profile = "bundled",
    required = false,
    state = state,
    processorId = null,
    subscribes = null,
    emits = emptyList(),
    capabilities = emptyList(),
    granted = emptyList(),
    ungranted = emptyList(),
    contributions = contributions,
    settings = settings,
)

/// The shipped plugin, registered: its own declaration's contributions,
/// in whatever state and with whatever settings the test wants.
internal fun installedTasksPlugin(state: String = "enabled", settings: String = ""): PluginInfo =
    installedPlugin(TASKS_PLUGIN, state, tasksDeclaration().contributions, settings)

class TasksPluginTest {
    private val palette = MarkdownPalette(
        muted = Color(0xFF888888),
        marker = Color(0xFF3355FF),
        heading = Color(0xFF111111),
        code = Color(0xFF007700),
        codeBackground = Color(0xFFEEEEEE),
        link = Color(0xFF0000FF),
    )

    private val today = LocalDate.of(2026, 9, 1)

    // ------------------------------------------------------------------
    // The declaration (§ 7)
    // ------------------------------------------------------------------

    @Test
    fun theDeclarationIsABundledDomainPluginThatAsksForNothing() {
        val declaration = tasksDeclaration()
        assertEquals(TASKS_PLUGIN, declaration.pluginId)
        assertEquals("domain", declaration.`class`)
        assertEquals("bundled", declaration.profile)
        assertTrue(!declaration.required, "removable, or removability is not being proven")
        assertEquals(emptyList(), declaration.capabilities, "§ 7: no capabilities — consent is the enable switch")
        assertEquals(null, declaration.processorId, "no processor")
        assertEquals(null, declaration.subscribes, "no subscription")
        assertEquals(emptyList(), declaration.emits, "no events")
    }

    @Test
    fun theDeclarationCoversTheExecutableProviderAndItsConsumers() {
        val slots = tasksDeclaration().contributions.associate { it.slot to it.id }
        assertEquals(
            mapOf(
                Slots.SUGGEST_VALUES to DATE_VALUES_PROVIDER,
                TASK_FIELDS_SLOT to TASKS_DUE_FIELD,
                Slots.EDITOR_DECORATIONS to TASKS_DECORATIONS_CONTRIBUTION,
                Slots.EDITOR_SUGGEST to TASKS_SUGGEST_CONTRIBUTION,
                Slots.SEARCH_TYPES to "task",
                Slots.NAVIGATION to TASKS_NAVIGATION_CONTRIBUTION,
            ),
            slots,
        )
    }

    // ------------------------------------------------------------------
    // The navigation slot and the panel's off switch (§ 5, § 6 T3)
    // ------------------------------------------------------------------

    @Test
    fun theNavigationContributionNamesThePanelTheRailShows() {
        val panels = navigationPanels(listOf(installedTasksPlugin()))
        assertEquals(listOf(TASKS_RAIL_PANEL), panels.map { it.key })
        assertEquals("Tasks", panels[0].label, "the tab announces itself by the plugin's own label")
    }

    @Test
    fun aDisabledOrRemovedPluginContributesNoRailTab() {
        for (state in listOf("disabled", "removed")) {
            assertTrue(
                navigationPanels(listOf(installedTasksPlugin(state = state))).isEmpty(),
                "state <$state> should contribute no destination",
            )
        }
        assertTrue(navigationPanels(emptyList()).isEmpty(), "and a vault without the plugin has none")
    }

    @Test
    fun theShippedPanelSettingIsOnSoTheTabIsThereWithoutAnyoneAskingForIt() {
        assertTrue(listOf(installedTasksPlugin()).tasksPanelEnabled(), "absent means on")
        assertTrue(
            listOf(installedTasksPlugin(settings = """{"filter":"#task"}""")).tasksPanelEnabled(),
            "a settings document written before the key existed still reads as on",
        )
        assertTrue(emptyList<PluginInfo>().tasksPanelEnabled(), "and so does a vault without the plugin")
    }

    @Test
    fun switchingThePanelOffMeansTheRailTabIsNotContributed() {
        val off = installedTasksPlugin(settings = tasksSettingsDocument("", panel = false))
        assertTrue(!listOf(off).tasksPanelEnabled())
        assertTrue(
            navigationPanels(listOf(off)).isEmpty(),
            "§ 6: while it is off the shell does not compose the tab",
        )
        // Everything else the plugin contributes is untouched: the switch
        // is over the panel, not over the plugin.
        assertEquals(listOf("due"), taskFields(listOf(off)).map { it.key })
    }

    @Test
    fun theTwoSettingsAreOneDocumentAndNeitherWriteDropsTheOther() {
        val both = tasksSettingsDocument("#task", panel = false)
        assertEquals("#task", listOf(installedTasksPlugin(settings = both)).tasksFilter())
        assertTrue(!listOf(installedTasksPlugin(settings = both)).tasksPanelEnabled())
        // What each control in the settings screen writes: the value it
        // owns, and the stored value of the one it does not.
        val panelToggledBackOn = tasksSettingsDocument("#task", panel = true)
        assertEquals("#task", listOf(installedTasksPlugin(settings = panelToggledBackOn)).tasksFilter())
        val filterCleared = tasksSettingsDocument("", panel = false)
        assertTrue(!listOf(installedTasksPlugin(settings = filterCleared)).tasksPanelEnabled())
    }

    @Test
    fun thePanelSettingIsReadableWhileThePluginIsOff() {
        val off = installedTasksPlugin(state = "disabled", settings = tasksSettingsDocument("", panel = false))
        assertTrue(!listOf(off).tasksPanelEnabled(), "its own screen shows what is stored; the rail is what gates")
    }

    @Test
    fun anotherPluginsNavigationContributionIsReadThroughTheSameSlot() {
        val other = installedPlugin(
            "org.example.calendar",
            contributions = listOf(
                PluginContribution(Slots.NAVIGATION, "calendar", "Calendar", """{"panel":"calendar"}"""),
            ),
        )
        assertEquals(
            listOf(TASKS_RAIL_PANEL, "calendar"),
            navigationPanels(listOf(installedTasksPlugin(), other)).map { it.key },
        )
        // …and one naming no panel is dropped rather than offered as a tab
        // that goes nowhere.
        val nameless = installedPlugin(
            "org.example.broken",
            contributions = listOf(PluginContribution(Slots.NAVIGATION, "x", "X", "{}")),
        )
        assertTrue(navigationPanels(listOf(nameless)).isEmpty())
    }

    // ------------------------------------------------------------------
    // The field registry and the both-enabled gate (§ 3)
    // ------------------------------------------------------------------

    @Test
    fun dueIsTheFirstRegistrationIntoTheSlotThisPluginPublishes() {
        val fields = taskFields(listOf(installedTasksPlugin()))
        assertEquals(1, fields.size, "the plugin's own field list is that registry, one entry long")
        assertEquals("due", fields[0].key)
        assertEquals("Due date", fields[0].label)
        // The config's third string is a provider id, and this is what
        // resolving it bought: stage two's rows, with ISO dates.
        val rows = fields[0].values("tomorrow", today)
        assertEquals("due:2026-09-02", rows.first().insert)
    }

    @Test
    fun anotherPluginsFieldArrivesThroughTheSameDoor() {
        val fields = taskFields(listOf(installedTasksPlugin(), contributorFixture()))
        assertEquals(listOf("due", "assignee"), fields.map { it.key })
    }

    @Test
    fun theGateNeedsThePublisherEnabledAsWellAsTheContributor() {
        // The contributor is on; the publisher is not. § 3: a contribution
        // into a plugin-published slot is live only while both are.
        val fields = taskFields(listOf(installedTasksPlugin(state = "disabled"), contributorFixture()))
        assertEquals(emptyList(), fields.map { it.key }, "the publisher is off, so no field is live")
    }

    @Test
    fun aRemovedPublisherTakesEveryFieldWithIt() {
        val fields = taskFields(listOf(installedTasksPlugin(state = "removed"), contributorFixture()))
        assertTrue(fields.isEmpty())
        assertTrue(taskFields(listOf(contributorFixture())).isEmpty(), "and so does one that was never installed")
    }

    @Test
    fun theGateStillDropsADisabledContributor() {
        val fields = taskFields(listOf(installedTasksPlugin(), contributorFixture(state = "disabled")))
        assertEquals(listOf("due"), fields.map { it.key }, "the publisher's own field survives; the other does not")
    }

    @Test
    fun aValueProviderThisHostCannotServeIsDroppedRatherThanOffered() {
        val people = installedPlugin(
            "org.example.people",
            contributions = listOf(
                PluginContribution(
                    slot = TASK_FIELDS_SLOT,
                    id = "assignee",
                    label = "Assignee",
                    config = """{"key":"assignee","label":"Assignee","values":"org.people.contacts"}""",
                ),
            ),
        )
        assertEquals(
            listOf("due"),
            taskFields(listOf(installedTasksPlugin(), people)).map { it.key },
            "an unknown provider id offers nothing, rather than a row that does nothing",
        )
    }

    @Test
    fun aConfigMissingItsThreeStringsIsDropped() {
        val broken = listOf(
            "",
            "{}",
            """{"label":"Assignee","values":"$DATE_VALUES_PROVIDER"}""",
            """{"key":"assignee","values":"$DATE_VALUES_PROVIDER"}""",
            """{"key":"assignee","label":"Assignee"}""",
            "not json at all",
        )
        for (config in broken) {
            val plugin = installedPlugin(
                "org.example.broken",
                contributions = listOf(PluginContribution(TASK_FIELDS_SLOT, "x", "X", config)),
            )
            assertEquals(
                listOf("due"),
                taskFields(listOf(installedTasksPlugin(), plugin)).map { it.key },
                "config <$config> should offer nothing",
            )
        }
    }

    // ------------------------------------------------------------------
    // The global filter (§ 1)
    // ------------------------------------------------------------------

    @Test
    fun theShippedFilterIsEmptySoEveryCheckboxIsATask() {
        assertEquals("", listOf(installedTasksPlugin()).tasksFilter())
        assertEquals("", emptyList<PluginInfo>().tasksFilter(), "and a vault without the plugin has no filter")
    }

    @Test
    fun theFilterIsReadBackFromTheSettingsDocumentItWasWrittenTo() {
        for (wanted in listOf("#task", "", "TODO", """a "quoted" \ one""", "two words")) {
            val plugin = installedTasksPlugin(settings = tasksSettingsDocument(wanted))
            assertEquals(wanted, listOf(plugin).tasksFilter(), "round trip of <$wanted>")
        }
    }

    @Test
    fun theFilterIsReadableWhileThePluginIsOff() {
        // Its own settings screen must show what is stored; the features
        // that read it are gated instead.
        val off = installedTasksPlugin(state = "disabled", settings = tasksSettingsDocument("#task"))
        assertEquals("#task", listOf(off).tasksFilter())
    }

    @Test
    fun anotherPluginsSettingsAreNotMistakenForTheFilter() {
        val whisper = installedPlugin("org.memory.transcription", settings = """{"filter":"en"}""")
        assertEquals("", listOf(whisper, installedTasksPlugin()).tasksFilter())
    }

    // ------------------------------------------------------------------
    // The decorations (§ 1)
    // ------------------------------------------------------------------

    @Test
    fun dueAndDoneAreStyledDifferentlyOnATaskLine() {
        val source = "notes\n- [x] file the return due:2026-09-01 done:2026-08-30"
        assertEquals(listOf(palette.marker), coloursOn(source, "due:2026-09-01"))
        assertEquals(listOf(palette.muted), coloursOn(source, "done:2026-08-30"))
    }

    @Test
    fun aStyledTokenChangesNoByte() {
        val source = "notes\n- [ ] pay the bill due:2026-09-05"
        val transformed = transform(source).text.text
        // The checkbox slice still renders its glyph, so the transformed
        // text differs there and nowhere else — the date token is
        // StyleOnly and is present verbatim.
        assertTrue(transformed.contains("due:2026-09-05"), "the token renders as itself")
        assertEquals(source.length - 2, transformed.length, "only the three-character marker moved")
    }

    @Test
    fun aDateInProseIsTextThePluginNeverSees() {
        assertTrue(coloursOn("notes\ndue:2026-09-05 is when the passport expires", "due:2026-09-05").isEmpty())
        assertTrue(
            coloursOn("notes\n- a bullet, not a task, due:2026-09-05", "due:2026-09-05").isEmpty(),
            "a plain list item is not a checklist item",
        )
    }

    @Test
    fun aQuotedExampleNeverBecomesSomebodysAgenda() {
        val source = "notes\n```\n- [ ] quoted due:2026-09-05\n```"
        assertTrue(coloursOn(source, "due:2026-09-05").isEmpty(), "§ 1: recognisers do not fire inside a fence")
    }

    @Test
    fun aHalfWrittenOrLooseDateIsNotATokenAtAll() {
        for (line in listOf("- [ ] milk due:2026-9-5", "- [ ] milk due:tomorrow", "- [ ] milkdue:2026-09-05")) {
            val source = "notes\n$line"
            assertTrue(
                transform(source).text.spanStyles.none { it.item.color == palette.marker && it.end - it.start > 4 },
                "<$line> carries no due token",
            )
        }
    }

    @Test
    fun theRecognisersReadTheGlobalFilterToo() {
        val plain = "notes\n- [ ] milk due:2026-09-05"
        val tagged = "notes\n- [ ] milk #task due:2026-09-05"
        assertEquals(
            listOf(palette.marker),
            coloursOn(plain, "due:2026-09-05"),
            "empty filter: every checkbox is a task",
        )
        assertTrue(
            coloursOn(plain, "due:2026-09-05", filter = "#task").isEmpty(),
            "with a filter set, an unmarked checkbox is not a task and its date is prose",
        )
        assertEquals(listOf(palette.marker), coloursOn(tagged, "due:2026-09-05", filter = "#task"))
    }

    @Test
    fun theTokenRegistrationsStyleAndNothingElse() {
        // Three registrations: two style a token and answer no click, and
        // the third — the stamper — bears the plugin's only activation but
        // never renders. So the click a user makes is still the checkbox's.
        val decorations = tasksDecorations(today = today)
        assertEquals(3, decorations.size)
        assertEquals(
            listOf("org.memory.tasks.done-stamp"),
            decorations.filter { it.activate != null }.map { it.id },
        )
        val source = "notes\n- [ ] milk due:2026-09-05"
        val hit = assertNotNull(
            decorationAt(source, transform(source).text.text.indexOf("☐"), 0, chain()),
            "the checkbox still answers",
        )
        assertEquals("gilune.checkbox", hit.decoration.id)
        val token = transform(source).text.text.indexOf("due:") + 2
        assertEquals(null, decorationAt(source, token, 0, chain()), "the date does not")
    }

    // ------------------------------------------------------------------
    // The done date on toggle (§ 1's write-on-action, § 5's chain)
    // ------------------------------------------------------------------

    @Test
    fun tickingATaskStampsTodayAfterWhateverTheLineAlreadyCarries() {
        assertEquals(
            "- [x] file the return due:2026-09-05 done:2026-09-01",
            activateLine("- [ ] file the return due:2026-09-05", chain()),
            "core flips the marker, the stamper then reads the line core wrote",
        )
    }

    @Test
    fun untickingStripsTheDoneTokenAndNothingElse() {
        assertEquals(
            "- [ ] file the return due:2026-09-05",
            activateLine("- [x] file the return due:2026-09-05 done:2026-08-30", chain()),
            "the done date goes, the due date the user typed stays",
        )
    }

    @Test
    fun tickingALineThatAlreadyCarriesADoneDateLeavesOneToken() {
        assertEquals(
            "- [x] milk done:2026-08-30",
            activateLine("- [ ] milk done:2026-08-30", chain()),
            "write-on-action, not rewrite-on-action: a stamped line is left as it stands",
        )
    }

    @Test
    fun proseIsNotATaskAndIsLeftAlone() {
        assertNull(activateLine("done:2026-08-30 is when the passport arrived", chain()))
        assertNull(activateLine("- a bullet, not a checklist item", chain()))
    }

    @Test
    fun theStamperIsGatedOutOfAFenceLikeEveryOtherTaskRegistration() {
        // The shell owns fence state, so the gate is provable where the
        // shell composes: alone, the stamper answers on an ordinary task
        // line and answers nothing inside a fence. (In the shipped chain
        // the checkbox takes that range anyway, and it opts in, because
        // presentation of a GFM element is not task metadata.)
        val plugin = tasksDecorations(today = today)
        val plain = "notes\n- [ ] quoted"
        val fenced = "notes\n```\n- [ ] quoted\n```"
        assertNotNull(
            decorationAt(plain, plain.indexOf("[ ]"), 0, plugin),
            "an ordinary task line is the stamper's",
        )
        assertNull(
            decorationAt(fenced, fenced.indexOf("[ ]"), 0, plugin),
            "§ 1: a documented example never becomes somebody's agenda",
        )
    }

    @Test
    fun aFilteredOutLineStillTogglesAndIsNotStamped() {
        val filtered = chain(filter = "#task")
        assertEquals(
            "- [x] milk",
            activateLine("- [ ] milk", filtered),
            "the core toggle is not the plugin's to hold back",
        )
        assertEquals(
            "- [x] milk #task done:2026-09-01",
            activateLine("- [ ] milk #task", filtered),
            "and the line the filter admits is stamped",
        )
    }

    @Test
    fun removingThePluginLeavesAToggleThatStillToggles() {
        assertEquals("- [x] milk", activateLine("- [ ] milk", builtInDecorations()))
        assertEquals(
            "- [ ] milk done:2026-08-30",
            activateLine("- [x] milk done:2026-08-30", builtInDecorations()),
            "and a date already written stays written — removal changes no byte",
        )
    }

    @Test
    fun aRegistrationThatLosesTheContestedRangeIsStillFoldedIntoTheActivation() {
        // The stamper claims the marker, which the checkbox claims first
        // and wins, so it renders nothing at all …
        val source = "notes\n- [ ] file the return"
        assertEquals("notes\n- ☐ file the return", transform(source).text.text)
        assertEquals(
            "gilune.checkbox",
            assertNotNull(decorationAt(source, transform(source).text.text.indexOf("☐"), 0, chain())).decoration.id,
        )
        // … and is folded into the activation regardless: participation is
        // by recognition of the evolving line, never by winning a range.
        assertEquals("- [x] file the return done:2026-09-01", activateLine("- [ ] file the return", chain()))
    }

    // ------------------------------------------------------------------
    // The two rewrites, on their own (§ 1)
    // ------------------------------------------------------------------

    @Test
    fun stampDoneAppendsOnlyToATickedTaskLackingOne() {
        assertEquals("- [x] milk done:2026-09-01", stampDone("- [x] milk", today))
        assertEquals("  * [X] milk done:2026-09-01", stampDone("  * [X] milk", today), "indented, starred, GFM's [X]")
        assertEquals("- [ ] milk", stampDone("- [ ] milk", today), "unticked: the wrong marker state")
        assertEquals("milk done", stampDone("milk done", today), "prose is not a task line")
        assertEquals(
            "- [x] milk done:2026-08-30",
            stampDone("- [x] milk done:2026-08-30", today),
            "already stamped, so idempotent",
        )
        assertEquals(
            "- [x] milk done:2026-08-30 later",
            stampDone("- [x] milk done:2026-08-30 later", today),
            "position-independent: a token mid-line is still a token",
        )
    }

    @Test
    fun stripDoneRemovesExactlyOneTokenFromAnUntickedTaskLine() {
        assertEquals("- [ ] milk", stripDone("- [ ] milk done:2026-08-30"))
        assertEquals(
            "- [ ] milk due:2026-09-05",
            stripDone("- [ ] milk done:2026-08-30 due:2026-09-05"),
            "the token is taken from where it sits, with the space it owns",
        )
        assertEquals(
            "- [ ] milk done:2026-08-29",
            stripDone("- [ ] milk done:2026-08-30 done:2026-08-29"),
            "exactly one",
        )
        assertEquals("- [ ] milk", stripDone("- [ ] milk"), "nothing to strip")
        assertEquals("- [x] milk done:2026-08-30", stripDone("- [x] milk done:2026-08-30"), "ticked: the wrong state")
        assertEquals("done:2026-08-30 in prose", stripDone("done:2026-08-30 in prose"), "prose is not a task line")
        assertEquals(
            "- [ ] milk done:2026-8-30",
            stripDone("- [ ] milk done:2026-8-30"),
            "a half-written date is not a token",
        )
    }

    // ------------------------------------------------------------------

    /// A second producer for the published slot. It borrows the date
    /// provider because that is the only one this host serves — the
    /// honest T1 state (§ 3: a shipped second producer waits for a real
    /// People plugin).
    private fun contributorFixture(state: String = "enabled") = installedPlugin(
        "org.example.people",
        state = state,
        contributions = listOf(
            PluginContribution(
                slot = TASK_FIELDS_SLOT,
                id = "assignee",
                label = "Assignee",
                config = """{"key":"assignee","label":"Assignee","values":"$DATE_VALUES_PROVIDER"}""",
            ),
        ),
    )

    /// Through the real transformation, as the editor composes it: the
    /// shell's registrations then the plugin's, with the caret parked on
    /// line 0 so every line under test is decorated.
    private fun transform(source: String, filter: String = "") =
        MarkdownEditTransformation(palette, caretLine = 0, decorations = chain(filter))
            .filter(AnnotatedString(source))

    /// The chain the editor folds, with the date fixed so a test never
    /// depends on the day it runs.
    private fun chain(filter: String = "") = builtInDecorations() + tasksDecorations(filter, today)

    /// Every colour applied to exactly the span `token` occupies in the
    /// rendered text. Empty means nothing styled it.
    private fun coloursOn(source: String, token: String, filter: String = ""): List<Color> {
        val result = transform(source, filter)
        val at = result.text.text.indexOf(token)
        if (at < 0) return emptyList()
        return result.text.spanStyles
            .filter { it.start == at && it.end == at + token.length }
            .map { it.item.color }
    }
}
