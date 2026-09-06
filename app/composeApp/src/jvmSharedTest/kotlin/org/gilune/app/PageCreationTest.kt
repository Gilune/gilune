package org.gilune.app

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.gilune.plugin.sdk.PAGE_CREATE_CAPABILITY
import org.gilune.plugins.fixture.*
import uniffi.gilune_ffi.CreatePageRequest
import uniffi.gilune_ffi.DocumentState
import uniffi.gilune_ffi.Gilune
import uniffi.gilune_ffi.GiluneException

class PageCreationTest {
    @Test fun independentPluginInitializesThroughTheBoundPublicService() {
        val root = Files.createTempDirectory("gilune-page-initialization-sdk").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            kernel.registerPlugin(fixtureInitializationDeclaration())
            kernel.grantCapability(cardsId, PAGE_CREATE_CAPABILITY)
            val service = BoundPageCreation(kernel, cardsId)
            assertEquals("initialized", initializeFixturePages(service).single().status)
            kernel.project()
            val file = File(root, "Cards/starter.md")
            assertTrue(file.readText().contains("name: \"Starter\""))
            assertTrue(file.readText().contains("unknown:\n  list: [a, b]"))
            file.writeText(file.readText().replace("Starter", "My own card"))
            kernel.reconcilePath("Cards/starter.md")
            assertEquals("recorded", initializeFixturePages(service).single().status)
            assertTrue(file.readText().contains("My own card"))
            assertTrue(File(root, "Settings/cards-initialization.md").readText().contains("org.example.cards/starter"))
        }
    }

    @Test fun independentPluginUsesPortableDefaultsAndPreservesTemplateProperties() {
        val root = Files.createTempDirectory("gilune-page-defaults-sdk").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            kernel.registerPlugin(fixturePageDeclaration(withDefaults = true))
            kernel.grantCapability(cardsId, PAGE_CREATE_CAPABILITY)
            val service = BoundPageCreation(kernel, cardsId)
            val definition = service.definitions().single { it.typeId == "$cardsId/card" }
            val config = service.definitions().single { it.typeId == definition.defaultsTypeId }
            val defaults = service.create(CreatePageRequest(config.typeId, config.version, UUID.randomUUID().toString(),
                "Settings/cards.md", "---\nfolder: Custom cards\n---\n---\nname: Default # keep\nextra: {nested: true}\n---\n# Custom\n"))
            kernel.project()
            val effective = service.definitions().single { it.typeId == definition.typeId }
            assertEquals("Custom cards", effective.defaultFolder)
            assertEquals(defaults.relPath, effective.defaultsPath)
            assertNotEquals(definition.version, effective.version)
            val source = org.gilune.plugin.sdk.pageTemplateWithProperties(effective.template, mapOf("name" to "\"true\""))
            assertTrue(source.contains("name: \"true\" # keep\nextra: {nested: true}"))
            val receipt = service.create(CreatePageRequest(definition.typeId, effective.version, UUID.randomUUID().toString(),
                "${effective.defaultFolder}/custom.md", source))
            kernel.project()
            assertTrue(kernel.documentSnapshot(receipt.relPath).content.text.contains("# Custom"))
            val file = File(root, defaults.relPath)
            file.writeText(file.readText().replace("# Custom", "# External template"))
            // The bound host reconciles the known config document before opening a form.
            assertTrue(service.definitions().single { it.typeId == definition.typeId }.template.contains("# External template"))
            assertTrue(kernel.documentSnapshot(receipt.relPath).content.text.contains("# Custom"))
        }
    }

    @Test
    fun independentPluginCreatesDurablePageThroughBoundService() {
        val root = Files.createTempDirectory("gilune-page-sdk").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            kernel.registerPlugin(fixturePageDeclaration())
            kernel.grantCapability(cardsId, PAGE_CREATE_CAPABILITY)
            val service = BoundPageCreation(kernel, cardsId)
            val requestId = UUID.randomUUID().toString()
            val receipt = createFixturePage(service, requestId)
            assertFalse(File(root, receipt.relPath).exists())
            assertEquals(receipt, createFixturePage(service, requestId))
            kernel.project()
            val snapshot = kernel.documentSnapshot(receipt.relPath)
            assertEquals(DocumentState.MANAGED, snapshot.source.state)
            assertEquals(receipt.objectId, snapshot.source.objectId)
            assertEquals(receipt.revisionId, snapshot.source.revisionId)
            assertTrue(snapshot.content.text.contains("unknown:\n  list: [a, b]\n"))
            val file = File(root, receipt.relPath)
            file.writeText(file.readText().replace("name: Example", "name: Edited"))
            kernel.reconcilePath(receipt.relPath)
            kernel.project()
            createFixturePage(service, requestId)
            assertTrue(file.readText().contains("name: Edited"))
        }
    }

    @Test
    fun boundServiceRejectsForeignTypesAndMissingGrant() {
        val root = Files.createTempDirectory("gilune-page-authority").toFile()
        Gilune.open(root.absolutePath).use { kernel ->
            kernel.registerPlugin(fixturePageDeclaration())
            val service = BoundPageCreation(kernel, cardsId)
            assertFailsWith<GiluneException.DocumentUnavailable> { createFixturePage(service, UUID.randomUUID().toString()) }
            kernel.grantCapability(cardsId, PAGE_CREATE_CAPABILITY)
            val definition = service.definitions().single()
            assertFailsWith<GiluneException.DocumentInvalid> {
                service.create(CreatePageRequest("org.other/card", definition.version, UUID.randomUUID().toString(), "Cards/foreign.md", "body"))
            }
            assertFalse(File(root, "Cards/foreign.md").exists())
        }
    }
}
