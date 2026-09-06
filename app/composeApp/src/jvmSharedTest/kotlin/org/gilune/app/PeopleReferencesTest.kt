package org.gilune.app

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.gilune.plugin.sdk.*
import org.gilune.plugins.people.*
import uniffi.gilune_ffi.*

class PeopleReferencesTest {
    private fun recognise(text: String) = recognisePeople(SourceView(inspectSourceText(text)))
    private fun facts(text: String) = PeopleIndexer().index("fixture.md", text)
    private fun eq(predicate: String, value: String, locality: FactLocality = FactLocality.EXACT) =
        Constraint.FactAt(predicate, ConstraintOp.EQ, value, locality)

    @Test fun repeatedUnicodeTokensAndMoreThan512DistinctHandlesKeepTheSameFacts() {
        val handles = List(600) { "Straße.$it" }
        val text = handles.joinToString("\r\n") { "RÉVISEUR: @$it réviseur: @${it.uppercase(java.util.Locale.ROOT)}" }
        val expected = handles.flatMapIndexed { index, handle ->
            val location = SourceLocation.BodyLine((index + 1).toUInt())
            val canonical = casefoldNfc(handle)
            listOf(
                ScopedFact(location, PERSON_REFERENCE, canonical),
                ScopedFact(location, PERSON_CONNECTION, connectionValue("réviseur", handle)),
                ScopedFact(location, CONNECTION_KEYWORD, "réviseur"),
                ScopedFact(location, CONNECTION_PERSON, canonical),
            )
        }
        assertEquals(expected, facts(text))
        assertEquals(expected, facts(text)) // A later pass does not depend on earlier cache state.
    }

    @Test fun recognisesPairsUnlabelledMentionsUnicodeAndExactByteRanges() {
        val source = "😀 owner: @ALICE.smith reviewer: @Straße and @BÓB.\r\n"
        val references = recognise(source)
        assertEquals(listOf("alice.smith", "strasse", "bób"), references.map { it.handle })
        assertEquals(listOf("owner", "reviewer", null), references.map { it.keyword })
        val bytes = source.toByteArray(Charsets.UTF_8)
        assertEquals(listOf("ALICE.smith", "Straße", "BÓB"), references.map {
            bytes.copyOfRange(it.handleRange.start.toInt(), it.handleRange.end.toInt()).toString(Charsets.UTF_8)
        })
        assertTrue(references.all { it.location == SourceLocation.BodyLine(1u) })
        val indexed = facts(source)
        assertEquals(setOf("[\"owner\",\"alice.smith\"]", "[\"reviewer\",\"strasse\"]"), indexed.filter { it.predicate == PERSON_CONNECTION }.map { it.value }.toSet())
        assertEquals(listOf("bób"), indexed.filter { it.predicate == PERSON_MENTION }.map { it.value })
        assertEquals(connectionValue("Équipe", "Straße"), connectionValue("E\u0301QUIPE", "STRASSE"))
    }

    @Test fun excludesCodeEscapesUrlsEmailsHtmlAndLinkDestinations() {
        val source = """
            `owner: @inline`
            ```md
            owner: @fenced
            ```

                owner: @indented

            \@escaped owner\: @unlabelled
            person@example.com @mail@example.com https://example/@url
            <https://example/@autolink> mailto:@address www.example/@web
            [@visible](https://example/@destination) **@bold**
            <!-- @comment -->
            <div>
            @html
            </div>
        """.trimIndent()
        assertEquals(listOf("unlabelled", "visible", "bold"), recognise(source).map { it.handle })
        assertTrue(recognise(source).all { it.keyword == null })
        assertTrue(recognise("@ @.bad @${"a".repeat(121)} name@person \\@escape").isEmpty())
    }

    @Test fun continuationsBelongToTheirItemsAndNestedItemsStaySeparate() {
        val text = "---\r\ntitle: Example\r\n---\r\n- Heading\r\n  owner: @alice\r\n  - reviewer: @bob\r\n- owner: @carol\r\n\r\n@outside\r\n"
        val snapshot = inspectSourceText(text)
        val refs = recognisePeople(SourceView(snapshot))
        assertEquals(snapshot.items.map { SourceLocation.MarkdownItem(it.key) } + SourceLocation.BodyLine(6u), refs.map { it.location })
        val first = snapshot.items.first()
        val nested = snapshot.items[1]
        assertTrue(first.ownRanges.none { nested.range.start >= it.start && nested.range.start < it.end })
    }

    @Test fun pageConnectionsUseGenericStructuredYamlAndKeepInvalidValuesVisible() {
        val text = """
            ---
            person: &person "@ALICE"
            connections:
              owner: *person
              reviewer: ["@bob", "@CAROL"]
              broken: [true, "@valid", "no-prefix"]
              42: "@not-a-key"
            other: {nested: [1, false, null]}
            ---
            - owner: @body
        """.trimIndent()
        val all = facts(text)
        val root = all.filter { it.location == SourceLocation.DocumentRoot }
        assertEquals(setOf("alice", "bob", "carol", "valid"), root.filter { it.predicate == PERSON_REFERENCE }.map { it.value }.toSet())
        assertTrue(root.any { it.predicate == REFERENCE_PROBLEM })
        assertFalse(root.any { it.predicate == PERSON_MENTION })
        assertEquals("mapping", SourceView(inspectSourceText(text)).metadata()["other"]?.kind)
        assertEquals(listOf("number", "boolean", "null"), SourceView(inspectSourceText(text)).metadata()["other"]?.get("nested")?.children()?.map { it.kind })
        assertTrue(facts("---\nconnections: [wrong]\n---\n@body").any { it.predicate == REFERENCE_PROBLEM })
    }

    @Test fun unknownKeywordsAndUnresolvedPeopleRemainLexicalAndDeduplicateFacts() {
        val indexed = facts("invented: @unknown invented: @unknown @unknown")
        assertEquals(1, indexed.count { it.predicate == PERSON_CONNECTION })
        assertEquals(1, indexed.count { it.predicate == PERSON_REFERENCE })
        assertEquals(1, indexed.count { it.predicate == PERSON_MENTION })
        assertEquals("[\"invented\",\"unknown\"]", indexed.single { it.predicate == PERSON_CONNECTION }.value)
    }

    @Test fun kernelQueriesPairsAcrossItemsAndPagesWithoutTasksOrQueryTimeFileReads() {
        val root = Files.createTempDirectory("gilune-person-facts").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            val registry = PluginExtensions(listOf(peopleExtensions()))
            registry.registerPlugins(kernel); registry.registerIndexers(kernel)
            val text = "---\nconnections: {owner: \"@page\"}\n---\n- Heading\n  owner: @alice reviewer: @bob\n  - owner: @bob\n- owner: @carol\n"
            val file = File(root, "fixture.md")
            file.writeText(text)
            kernel.reconcilePath("fixture.md")
            fun query(constraints: List<Constraint>, kind: LocationKind = LocationKind.MARKDOWN_ITEM, cursor: String? = null) =
                kernel.queryLocations(LocationQuery(constraints, kind, listOf(PEOPLE_PLUGIN), 1u, cursor))
            val pair = eq(PERSON_CONNECTION, connectionValue("owner", "alice"))
            val first = query(listOf(pair))
            assertTrue(first.indexComplete, first.issues.joinToString())
            assertEquals(1, first.matches.size)
            assertTrue(first.nextCursor == null)
            assertEquals(inspectSourceText(text).items.first().key, (first.matches.single().location as SourceLocation.MarkdownItem).key)
            assertTrue(query(listOf(eq(PERSON_CONNECTION, connectionValue("reviewer", "alice")))).matches.isEmpty())
            assertTrue(query(listOf(eq(PERSON_CONNECTION, connectionValue("owner", "page")))).matches.isEmpty())
            assertEquals(1, query(listOf(eq(PERSON_CONNECTION, connectionValue("owner", "page"))), LocationKind.DOCUMENT_ROOT).matches.size)
            val membership = Constraint.FactIn(PERSON_CONNECTION, listOf(connectionValue("owner", "alice"), connectionValue("owner", "bob")), FactLocality.EXACT)
            val p1 = query(listOf(membership))
            val p2 = query(listOf(membership), cursor = p1.nextCursor)
            assertNotEquals(p1.matches.single().location, p2.matches.single().location)
            assertNull(p2.nextCursor)
            file.writeText("unreconciled replacement")
            assertEquals(first.matches, query(listOf(pair)).matches)
            file.writeText(text)
            kernel.rebuildIndex(null)
            assertEquals(first.matches, query(listOf(pair)).matches)
            kernel.removePlugin(PEOPLE_PLUGIN)
            assertTrue(query(listOf(pair)).matches.isEmpty())
            assertEquals(text, file.readText())
        }
    }
}
