package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.*
import org.gilune.plugin.sdk.*
import org.gilune.plugins.people.*
import org.gilune.plugins.tasks.*
import uniffi.gilune_ffi.*

class ItemSearchTest {
    private fun vault(run: (File,Gilune,PluginExtensions)->Unit) {
        val root=Files.createTempDirectory("gilune-item-search").toFile()
        java.io.File(root, ".gilune-local").writeText("") // local-only vault: files stay unmanaged (ADR 0007, 2026-10-22)
        Gilune.open(root.absolutePath).use { kernel ->
            val registry=PluginExtensions(listOf(tasksExtensions(),peopleExtensions()))
            registry.registerPlugins(kernel);registry.registerIndexers(kernel)
            run(root,kernel,registry)
        }
    }
    private fun query(kernel:Gilune,registry:PluginExtensions,text:String,group:Boolean=false,limit:UInt=50u):SearchPage =
        compileSearch(text,registry.searchOperators(kernel.plugins(),LocalDate.of(2026,9,5)),registry.searchTypes(kernel.plugins()),LocalDate.of(2026,9,5),maxLines=5,pageSize=limit,groupByNote=group)!!.page(kernel).requireComplete()

    @Test fun independentlyContributedTypeAndPeopleMembershipMatchWholeItems()=vault { root,kernel,registry ->
        val source="""
            ---
            connections: {owner: "@page"}
            ---
            - [ ] Prepare proposal
              assign: @alice reviewer: @bob due:2026-09-05
              - [x] Child assign: @bob due:2026-09-06
            - [ ] Other assign: @carol
            - ordinary assign: @alice
        """.trimIndent()
        File(root,"work.md").writeText(source);kernel.reconcilePath("work.md")
        val page=query(kernel,registry,"""type:task people.connection:assign IN ["@alice", "@bob"]""")
        assertEquals(2uL,page.totalHits)
        assertEquals(2,kernelTaskRows(page.hits).size)
        assertEquals("- [ ] Prepare proposal", kernelTaskRows(page.hits).single { it.line == 1 }.checkboxLine)
        val block = assertIs<FilterResult.Tasks>(runFilterBlock(kernel, root.absolutePath,
            "type:task\npeople.connection:assign IN [\"alice\", \"bob\"]", kernel.plugins(), LocalDate.of(2026,9,5), cap=1))
        assertEquals(1, block.rows.size)
        assertEquals(1, block.more)
        val parent=page.hits.single { it.items.single().firstLine==1u }.items.single()
        assertTrue(parent.text.contains("Prepare proposal\n  assign"))
        assertFalse(parent.text.contains("Child"))
        assertEquals("2026-09-05",parent.facts.single { it.key==TASK_DUE_PREDICATE }.value)
        assertEquals(1uL,query(kernel,registry,"type:task people.connection:assign:@alice due:today").totalHits)
        assertEquals(0uL,query(kernel,registry,"type:task people.connection:reviewer:@alice").totalHits)
        assertEquals(0uL,query(kernel,registry,"type:task people.connection:owner:@page").totalHits)
        assertEquals(3uL,query(kernel,registry,"type:task people.page-connection:owner:@page").totalHits)
        assertEquals(1uL,query(kernel,registry,"""type:task people.connection:assign NOT IN ["alice", "bob"]""").totalHits)
        assertEquals(0uL,query(kernel,registry,"type:task people.connection:assign:@@bad").totalHits)
        assertEquals(1uL,query(kernel,registry,"type:note people.connection:assign:@alice").totalHits)
        File(root,"work.md").writeText("unreconciled change")
        assertEquals(2uL,query(kernel,registry,"""type:task people.connection:assign IN ["alice", "bob"]""").totalHits)
        File(root,"work.md").writeText(source);kernel.rebuildIndex(null)
        assertEquals(page.hits.map { it.items },query(kernel,registry,"""type:task people.connection:assign IN ["alice", "bob"]""").hits.map { it.items })
        kernel.removePlugin(TASKS_PLUGIN)
        assertEquals(1uL,query(kernel,registry,"type:note people.connection:assign:@alice").totalHits)
    }

    @Test fun taskBoundaryUsesMarkdownStructureAndNeedsNoPeopleInstallation() {
        val kernel=IndexedVault("gilune-task-items")
        kernel.write("items.md","""
            ~~~md
            - [ ] Fenced example
            ~~~

                - [ ] Code example

            - [ ] Parent
              due:2026-09-05
              - [ ] Child due:2026-09-09
        """.trimIndent())
        val compiled=compileSearch("type:task",types=searchTypesFor(kernel.gilune.plugins()),groupByNote=false)!!
        val page=compiled.page(kernel.gilune).requireComplete()
        assertEquals(2uL,page.totalHits)
        val rows=kernelTaskRows(page.hits)
        assertEquals(listOf(LocalDate.of(2026,9,5),LocalDate.of(2026,9,9)),rows.map { it.due })
        assertTrue(kernel.gilune.plugins().none { it.pluginId==PEOPLE_PLUGIN })
        kernel.gilune.close()
    }

    @Test fun unrelatedScopedTypeConsumesPeopleOperatorsWithoutTaskRegistration() {
        val root=Files.createTempDirectory("gilune-independent-items").toFile()
        java.io.File(root, ".gilune-local").writeText("") // local-only vault: files stay unmanaged (ADR 0007, 2026-10-22)
        val id="fixture.outline"
        val plugin=BundledPluginExtensions(
            PluginDeclaration(id,"Outline","domain","bundled",false,null,null,emptyList(),emptyList(),listOf(PluginContribution(Slots.SEARCH_TYPES,"outline","Outline"))),
            searchTypes=listOf(SearchTypeRegistration("outline","outline") { SearchType("outline",indexed=true,itemScoped=true,indexNamespaces=listOf(id),constraints=listOf(Constraint.FactAt("$id.kind",ConstraintOp.EQ,"entry",FactLocality.EXACT))) }),
            scopedIndexer=ScopedIndexerRegistration(1,emptyList()) { object:ScopedIndexer {
                override fun index(relPath:String,source:SourceTextSnapshot)=source.items.map { ScopedFact(SourceLocation.MarkdownItem(it.key),"$id.kind","entry") }
            } })
        Gilune.open(root.absolutePath).use { kernel ->
            val registry=PluginExtensions(listOf(plugin,peopleExtensions()))
            registry.registerPlugins(kernel);registry.registerIndexers(kernel)
            File(root,"outline.md").writeText("1. Launch\n   owner: @alice\n2. Finish owner: @bob\n")
            kernel.reconcilePath("outline.md")
            assertEquals(1uL,query(kernel,registry,"type:outline people.connection:owner:@alice").totalHits)
            kernel.removePlugin(PEOPLE_PLUGIN)
            assertEquals(2uL,query(kernel,registry,"type:outline").totalHits)
        }
    }
}
