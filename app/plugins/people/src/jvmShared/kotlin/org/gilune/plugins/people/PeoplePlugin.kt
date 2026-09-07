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
    require(validToken(keyword)) { "Use a connection keyword without spaces or Markdown punctuation." }
    require(retired in listOf("true", "false")) { "Retired must be true or false." }
    return PageDraft("${casefoldNfc(keyword)}.md", pageTemplateWithProperties(template,
        mapOf("keyword" to pageJsonString(keyword), "label" to pageJsonString(label), "retired" to retired)), casefoldNfc(keyword))
}

fun peopleExtensions(): BundledPluginExtensions {
    val operators = peopleSearchOperators()
    val collections = listOf(
        PageCollectionRegistration(PEOPLE_PANEL, PERSON_TYPE, "person", "People", "New person",
            listOf(PageField("name", "Name"), PageField("handle", "Handle", hint = "alice.smith")), ::personDraft,
            { facts, fallback ->
                fun value(key: String) = facts.firstOrNull { it.key == key }?.value
                PageRow(value(PERSON_NAME) ?: fallback, value(HANDLE_DISPLAY)?.let { "@$it" }.orEmpty(), value(PROBLEM))
            }, identityPredicate = PERSON_HANDLE, lookupProviderId = PEOPLE_LOOKUP),
        PageCollectionRegistration(CONNECTIONS_PANEL, CONNECTION_TYPE, "person-connection", "People connections", "New connection",
            listOf(PageField("keyword", "Keyword", hint = "reviewer"), PageField("label", "Display label"), PageField("retired", "Retired", "false", "true or false")), ::connectionDraft,
            { facts, fallback ->
                fun value(key: String) = facts.firstOrNull { it.key == key }?.value
                PageRow(value(LABEL) ?: value(KEYWORD) ?: fallback,
                    value(KEYWORD).orEmpty() + if (value(RETIRED) == "true") " · retired" else "", value(PROBLEM))
            }, identityPredicate = KEYWORD))
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
        ) + operators.map { PluginContribution(Slots.SEARCH_OPERATORS, it.contributionId, it.name) } + collections.flatMap { c -> listOf(
            PluginContribution(PAGE_COLLECTIONS_SLOT, c.id, c.title),
            PluginContribution(Slots.NAVIGATION, c.id, c.title, "{\"panel\":${pageJsonString(c.id)}}"),
        ) })
    return BundledPluginExtensions(declaration,
        indexedLookups = listOf(peopleLookupRegistration()),
        editorCompletions = listOf(peopleEditorRegistration()),
        documentViews = listOf(peopleViewRegistration()),
        refactorings = listOf(peopleRefactoringRegistration()),
        searchOperators = operators,
        searchTypes = listOf(
            SearchTypeRegistration("person", "person") { personSearchType() },
            SearchTypeRegistration("person-connection", "person-connection") { connectionSearchType() }),
        scopedIndexer = ScopedIndexerRegistration(3, emptyList(), createLocal = { PeopleIndexer() }) { PeopleIndexer() }, pageCollections = collections)
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
                if (keyword == null || !validToken(keyword) || label.isNullOrBlank() || retired?.kind != "boolean" || string("gilune-id").isNullOrBlank()) {
                    fact(PROBLEM, "Check this connection's keyword, label, retired flag and identity in Markdown.")
                }
            }
        }
        facts += peopleReferenceFacts(view)
        return facts.distinct()
    }
}
