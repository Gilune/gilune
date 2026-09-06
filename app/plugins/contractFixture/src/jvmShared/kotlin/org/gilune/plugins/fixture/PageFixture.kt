package org.gilune.plugins.fixture

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.CreatePageRequest
import uniffi.gilune_ffi.PluginDeclaration

const val cardsId = "org.example.cards"
fun fixtureInitializationDeclaration(): PluginDeclaration {
    val base = fixturePageDeclaration()
    return base.copy(contributions = base.contributions + listOf(
        pageTypeContribution("$cardsId/initialization", "Card initialization", defaultFolder = "Settings", template = ""),
        pageInitializerContribution("$cardsId/starter", "Starter card", "$cardsId/initialization", "cards-initialization.md",
            listOf(InitialPage("starter", "$cardsId/card", "starter.md", mapOf("name" to pageJsonString("Starter"))))),
    ))
}
fun initializeFixturePages(service: PageCreationService) = service.initialize()

fun fixturePageDeclaration(withDefaults: Boolean = false) = PluginDeclaration(
    cardsId, "Cards fixture", "domain", "bundled", false, null, null,
    emptyList(), listOf(PAGE_CREATE_CAPABILITY),
    listOf(pageTypeContribution("$cardsId/card", "Card", defaultFolder = "Cards",
        template = "---\nname: Example\nunknown:\n  list: [a, b]\n---\n# Freeform\n")) +
        if (withDefaults) listOf(pageTypeContribution("$cardsId/defaults", "Card defaults", defaultFolder = "Settings",
            template = "", defaultsFor = "$cardsId/card")) else emptyList(),
)

/** An independent domain consumes only the public SDK and native value records. */
fun createFixturePage(service: PageCreationService, requestId: String) = service.definitions().single { it.typeId == "$cardsId/card" }.let { definition ->
    service.create(CreatePageRequest(definition.typeId, definition.version, requestId,
        "${definition.defaultFolder}/example.md", definition.template))
}
