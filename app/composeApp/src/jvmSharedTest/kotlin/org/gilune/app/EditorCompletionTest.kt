package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.*
import org.gilune.plugin.sdk.*
import org.gilune.plugins.people.*
import org.gilune.plugins.tasks.*
import uniffi.gilune_ffi.*

class EditorCompletionTest {
    private val day = LocalDate.of(2026, 9, 7)
    private fun context(text: String) = EditorContext(text, text.length, day)
    private fun fixture(run: (File, Gilune, PluginExtensions) -> Unit) {
        val root = Files.createTempDirectory("gilune-editor").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(peopleExtensions(), tasksExtensions()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            registry.initializePages(kernel)
            File(root, "alice.md").writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: alice\nname: Alice Smith\nhandle: alice.smith\n---\n")
            kernel.reconcilePath("alice.md")
            run(root, kernel, registry)
        }
    }

    @Test fun mentionOffersExactTextAndExplicitCreationWithoutWriting() = fixture { root, kernel, registry ->
        val text = "Hello @ali"
        val offers = loadEditorOffers(kernel, registry, context(text))
        val alice = offers.choices.first { it.option.insertion != null }
        assertEquals("@alice.smith ", alice.option.insertion)
        assertEquals(6, alice.proposal.start)
        assertEquals(1uL, kernel.lookup(alice.option.unique!!).totalHits)
        assertNotNull(offers.choices.single { it.option.create != null }.option.create)
        assertFalse(File(root, "People/ali.md").exists())
    }

    @Test fun markupEmailsEscapesAndUrlsDoNotOpenPeopleLookup() = fixture { _, kernel, registry ->
        for (text in listOf("email@ali", "\\@ali", "`@ali", "```\n@ali", "https://host/@ali", "    @ali")) {
            assertTrue(loadEditorOffers(kernel, registry, context(text)).choices.isEmpty(), text)
        }
    }

    @Test fun pluginActionsComposeWithoutDomainDependenciesAndDatesKeepTwoStages() = fixture { _, kernel, registry ->
        val text = "- [ ] Publish /"
        val first = loadEditorOffers(kernel, registry, context(text))
        assertTrue(first.choices.any { it.option.detail == "owner" })
        val due = first.choices.single { it.option.continueWith == "due" }
        val secondText = text + "tom"
        val second = loadEditorOffers(kernel, registry, context(secondText).copy(
            continuation = EditorContinuation(due.proposal.start, text.length, "due")), due.binding.registration.id)
        assertEquals("due:2026-09-08 ", second.choices.first().option.insertion)
        assertEquals(text.lastIndex, second.choices.first().proposal.start)
        assertFalse(loadEditorOffers(kernel, registry, context("ordinary /" )).choices.any { it.option.continueWith == "due" })
        kernel.removePlugin(PEOPLE_PLUGIN)
        assertFalse(loadEditorOffers(kernel, registry, context(text)).choices.any { it.binding.owner.pluginId == PEOPLE_PLUGIN })
        assertTrue(loadEditorOffers(kernel, registry, context(text)).choices.any { it.option.continueWith == "due" })
    }

    @Test fun duplicateDefinitionsRemainVisibleAndCannotPassUniqueAcceptance() = fixture { root, kernel, registry ->
        File(root, "other.md").writeText(File(root, "alice.md").readText().replace("gilune-id: alice", "gilune-id: other"))
        kernel.reconcilePath("other.md")
        val offers = loadEditorOffers(kernel, registry, context("@alice"))
        val rows = offers.choices.filter { it.option.insertion != null }
        assertEquals(2, rows.size)
        assertTrue(rows.all { kernel.lookup(it.option.unique!!).totalHits == 2uL })
        File(root, "other.md").writeText("removed definition")
        kernel.reconcilePath("other.md")
        assertEquals(1uL, kernel.lookup(rows.first().option.unique!!).totalHits)
    }

    @Test fun connectionSelectionWritesTheConnectionThenNarrowsPeopleByTyping() = fixture { root, kernel, registry ->
        File(root, "bob.md").writeText("---\ngilune-type: $PERSON_TYPE\ngilune-id: bob\nname: Bob Jones\nhandle: bob.jones\n---\n")
        kernel.reconcilePath("bob.md")
        val text = "Publish /ow"
        val owner = loadEditorOffers(kernel, registry, context(text)).choices.single { it.option.detail == "owner" }
        assertNull(owner.option.continueWith)
        assertEquals("owner: @", owner.option.insertion)
        val written = text.replaceRange(owner.proposal.start, owner.proposal.end, owner.option.insertion!!)
        assertEquals("Publish owner: @", written)
        fun people(typed: String) = loadEditorOffers(kernel, registry, context(written + typed)).choices
            .filter { it.option.insertion != null }.map { it.option.label }
        assertEquals(listOf("Alice Smith", "Bob Jones"), people(""))
        assertEquals(listOf("Bob Jones"), people("bo"))
        val alice = loadEditorOffers(kernel, registry, context(written + "ali")).choices.first { it.option.insertion != null }
        assertEquals("Publish owner: @alice.smith ", (written + "ali").replaceRange(alice.proposal.start, alice.proposal.end, alice.option.insertion!!))
        val create = loadEditorOffers(kernel, registry, context(written + "ali")).choices.single { it.option.create != null }.option.create!!
        assertEquals("@alicia ", create.complete!!(inspectSourceText("---\nhandle: alicia\n---\n")).insertion)
    }

    @Test fun toolbarIncludesBlankParagraphsButExcludesCodeAndMetadata() = fixture { _, kernel, registry ->
        assertTrue(loadEditorOffers(kernel, registry, context("").copy(toolbar = true)).choices.isNotEmpty())
        assertTrue(loadEditorOffers(kernel, registry, context("`code `").copy(toolbar = true, caret = 6)).choices.isEmpty())
        for (text in listOf("```\n", "---\nname: ", "    code ")) {
            assertTrue(loadEditorOffers(kernel, registry, context(text).copy(toolbar = true)).choices.isEmpty(), text)
        }
    }
}
