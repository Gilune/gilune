package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val PEOPLE_PLUGIN = "org.memory.people"
const val PERSON_TYPE = "$PEOPLE_PLUGIN/person"
const val CONNECTION_TYPE = "$PEOPLE_PLUGIN/connection-definition"
const val PEOPLE_PANEL = "people-directory"
const val CONNECTIONS_PANEL = "people-connections"
const val PERSON_KIND = "$PEOPLE_PLUGIN.person.kind"
const val PERSON_HANDLE = "$PEOPLE_PLUGIN.person.handle"
const val HANDLE_DISPLAY = "$PEOPLE_PLUGIN.person.handle-display"
const val PERSON_NAME = "$PEOPLE_PLUGIN.person.name"
const val PERSON_NAME_KEY = "$PEOPLE_PLUGIN.person.name-key"
const val PROBLEM = "$PEOPLE_PLUGIN.definition.problem"
const val CONNECTION_KIND = "$PEOPLE_PLUGIN.definition.kind"
const val KEYWORD = "$PEOPLE_PLUGIN.definition.keyword"
const val LABEL = "$PEOPLE_PLUGIN.definition.label"
const val RETIRED = "$PEOPLE_PLUGIN.definition.retired"
const val COLOUR = "$PEOPLE_PLUGIN.definition.colour"

/// The colours a connection's pill may take, by the name its page writes
/// (owner, 2026-10-26). No name, or one not on this list, is the pill's
/// ordinary accent; `gray` is forgiven for `grey`.
val CONNECTION_COLOURS = listOf("blue", "green", "amber", "red", "purple", "grey")

fun connectionColour(value: String?): String? = when (val name = value?.trim()?.lowercase()) {
    "gray" -> "grey"
    in CONNECTION_COLOURS -> name
    else -> null
}

const val CONNECTION_SHEET = "$PEOPLE_PLUGIN.connection-sheet"

/// What a connection page shows in its properties section (owner,
/// 2026-10-26): its label and retired flag to edit, its pill colour to pick,
/// and its keyword to read — renaming a keyword changes every note that
/// uses it, so that goes through Rename references.
fun connectionPropertySheet() = PagePropertySheet(
    id = CONNECTION_SHEET,
    pageTypeId = CONNECTION_TYPE,
    title = "People connection",
    properties = listOf(
        PageProperty.Text("label", "Label", required = true),
        PageProperty.ReadOnly("keyword", "Keyword", renameReferences = true),
        PageProperty.Switch("retired", "Retired"),
        PageProperty.Choice("colour", "Pill colour", connectionColourOptions()),
    ),
)

const val PERSON_SHEET = "$PEOPLE_PLUGIN.person-sheet"

/// What a person's page shows in its properties section (owner,
/// 2026-10-26: "same style as with connections"): the name to edit, and the
/// handle to read — every mention is written with it, so changing it goes
/// through Rename references.
fun personPropertySheet() = PagePropertySheet(
    id = PERSON_SHEET,
    pageTypeId = PERSON_TYPE,
    title = "Person",
    properties = listOf(
        PageProperty.Text("name", "Name", required = true),
        PageProperty.ReadOnly("handle", "Handle", renameReferences = true),
    ),
)

/// Default writes nothing (the property is removed), then the six names.
fun connectionColourOptions(): List<PageChoiceOption> =
    listOf(PageChoiceOption(null, "Default", PillTone.ACCENT)) +
        CONNECTION_COLOURS.map { PageChoiceOption(it, it.replaceFirstChar(Char::uppercase), connectionTone(it)) }

fun connectionTone(colour: String?): PillTone = when (connectionColour(colour)) {
    "blue" -> PillTone.BLUE
    "green" -> PillTone.GREEN
    "amber" -> PillTone.AMBER
    "red" -> PillTone.RED
    "purple" -> PillTone.PURPLE
    "grey" -> PillTone.GREY
    else -> PillTone.ACCENT
}

/** Whitespace and reserved Markdown/path punctuation are explicit codepoints.
 * Other Unicode, including combining marks and emoji, remains user-authored text. */
fun validToken(value: String): Boolean = value.isNotEmpty() && value.length <= 120 &&
    !value.startsWith('.') && !value.endsWith('.') && value.all(::peopleTokenChar)

internal fun peopleTokenChar(it: Char): Boolean = !(
        it.code <= 32 || it.code == 127 || it in "@:,;[]{}()<>/\\\"'`!?#+=|*" ||
            it.code in 0x80..0xA0 || it.code == 0x1680 || it.code in 0x2000..0x200F ||
            it.code in 0x2028..0x202F || it.code in 0x205F..0x206F || it.code == 0x3000 || it.code == 0xFEFF
    )

fun personDraft(values: Map<String, String>, template: String = "# About\n\n"): PageDraft {
    val name = values["name"].orEmpty().trim()
    val handle = values["handle"].orEmpty().trim().removePrefix("@")
    require(name.isNotEmpty()) { "Enter the person's name." }
    require(validToken(handle)) { "Use a handle without spaces or Markdown punctuation, for example alice.smith." }
    return PageDraft("${casefoldNfc(handle)}.md", pageTemplateWithProperties(template,
        mapOf("name" to pageJsonString(name), "handle" to pageJsonString(handle))), casefoldNfc(handle))
}
fun connectionDraft(values: Map<String, String>, template: String = "# Notes\n\n"): PageDraft {
    val keyword = values["keyword"].orEmpty().trim().removeSuffix(":")
    val label = values["label"].orEmpty().trim().ifEmpty { keyword }
    val retired = values["retired"].orEmpty().ifEmpty { "false" }
    val colourWritten = values["colour"].orEmpty().trim()
    val colour = connectionColour(colourWritten)
    require(validToken(keyword)) { "Use a connection keyword without spaces or Markdown punctuation." }
    require(retired in listOf("true", "false")) { "Retired must be true or false." }
    require(colourWritten.isEmpty() || colour != null) { "Colour must be one of ${CONNECTION_COLOURS.joinToString(", ")}, or left empty." }
    val properties = mapOf("keyword" to pageJsonString(keyword), "label" to pageJsonString(label), "retired" to retired) +
        (colour?.let { mapOf("colour" to pageJsonString(it)) } ?: emptyMap())
    return PageDraft("${casefoldNfc(keyword)}.md", pageTemplateWithProperties(template, properties), casefoldNfc(keyword))
}

fun peopleExtensions(): BundledPluginExtensions {
    val operators = peopleSearchOperators()
    val collections = listOf(
        PageCollectionRegistration(PEOPLE_PANEL, PERSON_TYPE, "person", "People", "New person",
            listOf(PageField("name", "Name"), PageField("handle", "Handle", hint = "alice.smith")), ::personDraft,
            { facts, fallback ->
                fun value(key: String) = facts.firstOrNull { it.key == key }?.value
                PageRow(value(PERSON_NAME) ?: fallback, value(HANDLE_DISPLAY)?.let { "@$it" }.orEmpty(), value(PROBLEM))
            }, identityPredicate = PERSON_HANDLE, lookupProviderId = PEOPLE_LOOKUP, folderCreateLabel = "Create person"),
        PageCollectionRegistration(CONNECTIONS_PANEL, CONNECTION_TYPE, "person-connection", "People connections", "New connection",
            listOf(PageField("keyword", "Keyword", hint = "reviewer"), PageField("label", "Display label"), PageField("retired", "Retired", "false", "true or false"),
                PageField("colour", "Pill colour", hint = CONNECTION_COLOURS.joinToString(", "))), ::connectionDraft,
            { facts, fallback ->
                fun value(key: String) = facts.firstOrNull { it.key == key }?.value
                PageRow(value(LABEL) ?: value(KEYWORD) ?: fallback,
                    value(KEYWORD).orEmpty() + if (value(RETIRED) == "true") " · retired" else "", value(PROBLEM))
            }, identityPredicate = KEYWORD, folderCreateLabel = "Create connection"))
    val declaration = PluginDeclaration(PEOPLE_PLUGIN, "People", "domain", "bundled", false, null, null,
        emptyList(), listOf(PAGE_CREATE_CAPABILITY),
        listOf(
            pageTypeContribution(PERSON_TYPE, "Person", defaultFolder = "People", template = "# About\n\n"),
            pageTypeContribution(CONNECTION_TYPE, "People connection", defaultFolder = "People/Connections", template = "# Notes\n\n"),
            pageTypeContribution("$PEOPLE_PLUGIN/person-defaults", "Person defaults", defaultFolder = "Settings/Page defaults",
                template = "", defaultsFor = PERSON_TYPE),
            pageTypeContribution("$PEOPLE_PLUGIN/connection-defaults", "Connection defaults", defaultFolder = "Settings/Page defaults",
                template = "", defaultsFor = CONNECTION_TYPE),
            pageTypeContribution("$PEOPLE_PLUGIN/catalogue-initialization", "People catalogue initialization",
                defaultFolder = "Settings/Initialization", template = ""),
            pageInitializerContribution("$PEOPLE_PLUGIN/catalogue", "Initial people connections", "$PEOPLE_PLUGIN/catalogue-initialization", "people-catalogue.md",
                listOf("assign" to "Assign", "owner" to "Owner").map { (keyword, label) ->
                    InitialPage(keyword, CONNECTION_TYPE, "$keyword.md", mapOf("keyword" to pageJsonString(keyword), "label" to pageJsonString(label), "retired" to "false"))
                }),
            PluginContribution(Slots.SEARCH_TYPES, "person", "People"),
            PluginContribution(Slots.INDEXED_LOOKUP, PEOPLE_LOOKUP, "Find people"),
            PluginContribution(Slots.EDITOR_COMPLETIONS, PEOPLE_EDITOR, "People and connections"),
            PluginContribution(Slots.DOCUMENT_VIEWS, PEOPLE_VIEW, "People links"),
            PluginContribution(Slots.DOCUMENT_REFACTORINGS, PEOPLE_REFACTORING, "Rename people and connections"),
            PluginContribution(Slots.SEARCH_TYPES, "person-connection", "People connections"),
            PluginContribution(Slots.EDITOR_DECORATIONS, PEOPLE_CONNECTION_STYLE, "Connections drawn as pills"),
            PluginContribution(Slots.EDITOR_DECORATIONS, PEOPLE_MENTION_STYLE, "Mentions styled as links"),
            PluginContribution(Slots.EDITOR_DEFINITIONS, PEOPLE_DEFINITION, "Go to a person's page"),
            PluginContribution(PAGE_PROPERTIES_SLOT, PERSON_SHEET, "Person properties"),
            PluginContribution(PAGE_PROPERTIES_SLOT, CONNECTION_SHEET, "Connection properties"),
            vaultFolderContribution(PEOPLE_FOLDER, "People"),
        ) + operators.map { PluginContribution(Slots.SEARCH_OPERATORS, it.contributionId, it.name) } + collections.flatMap { c ->
            listOf(PluginContribution(PAGE_COLLECTIONS_SLOT, c.id, c.title)) +
                if (c.id == PEOPLE_PANEL) listOf(PluginContribution(Slots.NAVIGATION, c.id, c.title, "{\"panel\":${pageJsonString(c.id)}}"))
                else emptyList()
        })
    return BundledPluginExtensions(declaration,
        indexedLookups = listOf(peopleLookupRegistration()),
        editorCompletions = listOf(peopleEditorRegistration()),
        documentViews = listOf(peopleViewRegistration()),
        refactorings = listOf(peopleRefactoringRegistration()),
        editorDecorations = listOf(peopleConnectionDecoration(), peopleMentionDecoration()),
        definitions = listOf(peopleDefinitionRegistration()),
        pagePropertySheets = listOf(personPropertySheet(), connectionPropertySheet()),
        searchOperators = operators,
        searchTypes = listOf(
            SearchTypeRegistration("person", "person") { personSearchType() },
            SearchTypeRegistration("person-connection", "person-connection") { connectionSearchType() }),
        // Version 4 (2026-10-26) records a connection's pill colour, so
        // an upgraded vault re-derives the namespace and colours its pills.
        scopedIndexer = ScopedIndexerRegistration(4, emptyList(), createLocal = { PeopleIndexer() }) { PeopleIndexer() }, pageCollections = collections)
}
fun personSearchType() = SearchType("person", indexed = true,
    constraints = listOf(Constraint.FactAt(PERSON_KIND, ConstraintOp.EQ, "person", FactLocality.DOCUMENT_ROOT)), indexNamespaces = listOf(PEOPLE_PLUGIN))
fun connectionSearchType() = SearchType("person-connection", indexed = true,
    constraints = listOf(Constraint.FactAt(CONNECTION_KIND, ConstraintOp.EQ, "connection", FactLocality.DOCUMENT_ROOT)), indexNamespaces = listOf(PEOPLE_PLUGIN))

class PeopleIndexer : ScopedIndexer, LocalScopedIndexer {
    override fun indexLocations(relPath: String, source: SourceTextSnapshot, locations: List<SourceLocation>): List<ScopedFact> {
        val selected = locations.toSet()
        return index(relPath, source.forLocations(selected)).filter { it.location in selected }
    }
    fun index(relPath: String, source: String): List<ScopedFact> = index(relPath, inspectSourceText(source))

    override fun index(relPath: String, source: SourceTextSnapshot): List<ScopedFact> {
        val view = SourceView(source)
        val properties = view.metadata()
        fun property(key: String) = properties[key]
        fun string(key: String) = property(key)?.string()
        val facts = mutableListOf<ScopedFact>()
        fun fact(key: String, value: String) { facts += ScopedFact(SourceLocation.DocumentRoot, key, value) }
        when (string("gilune-type")) {
            PERSON_TYPE -> {
                fact(PERSON_KIND, "person")
                val name = string("name")
                val handle = string("handle")
                if (!name.isNullOrBlank()) { fact(PERSON_NAME, name); fact(PERSON_NAME_KEY, casefoldNfc(name)) }
                if (handle != null && validToken(handle)) { fact(PERSON_HANDLE, casefoldNfc(handle)); fact(HANDLE_DISPLAY, handle) }
                if (name.isNullOrBlank() || handle == null || !validToken(handle) || string("gilune-id").isNullOrBlank()) {
                    fact(PROBLEM, "Check this page's name, handle and identity in its Markdown properties.")
                }
            }
            CONNECTION_TYPE -> {
                fact(CONNECTION_KIND, "connection")
                val keyword = string("keyword")
                val label = string("label")
                val retired = property("retired")
                if (keyword != null && validToken(keyword)) fact(KEYWORD, casefoldNfc(keyword))
                if (!label.isNullOrBlank()) fact(LABEL, label)
                if (retired?.kind == "boolean") fact(RETIRED, retired.value!!)
                // A colour the pill cannot draw is left out, not a problem:
                // the connection still works, its pill takes the accent.
                connectionColour(string("colour"))?.let { fact(COLOUR, it) }
                if (keyword == null || !validToken(keyword) || label.isNullOrBlank() || retired?.kind != "boolean" || string("gilune-id").isNullOrBlank()) {
                    fact(PROBLEM, "Check this connection's keyword, label, retired flag and identity in Markdown.")
                }
            }
        }
        facts += peopleReferenceFacts(view)
        return facts.distinct()
    }
}
