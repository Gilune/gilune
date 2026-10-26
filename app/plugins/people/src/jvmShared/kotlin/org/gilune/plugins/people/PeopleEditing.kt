package org.gilune.plugins.people

import org.gilune.plugin.sdk.*
import uniffi.gilune_ffi.*

const val PEOPLE_MENTION_STYLE = "$PEOPLE_PLUGIN.mention-style"
const val PEOPLE_CONNECTION_STYLE = "$PEOPLE_PLUGIN.connection-style"
const val PEOPLE_DEFINITION = "$PEOPLE_PLUGIN.definition"
const val PEOPLE_FOLDER = "$PEOPLE_PLUGIN.folder"

/** The keystroke that opens a person's page from a mention, as the host
 * spells keystrokes. F12 is Visual Studio's "go to definition". */
const val DEFAULT_DEFINITION_KEY = "F12"
private const val DEFINITION_KEY = "goToDefinitionKey"

/** A connection reads as one thing (owner, 2026-10-26): `assign: @phil` is
 * drawn as a pill, the keyword its tag and the person beside it, and goes
 * back to plain text on the line being written. Registered ahead of the
 * mention style, so the pill takes the mention inside it; on the caret's
 * line the pill gives way and the mention is coloured as any other.
 *
 * Each pill is keyed by its keyword and coloured by its connection page's
 * `colour` (owner, 2026-10-26). A keyword no live page defines — a typo, a
 * retired connection — is not answered here, so its pill is grey. */
fun peopleConnectionDecoration() = EditorDecoration(
    id = PEOPLE_CONNECTION_STYLE,
    recognise = { line ->
        connectionsInLine(line).map { (range, tag, keyword) -> DecorationMatch(range, DecorationRender.Pill(tag, keyword)) }
    },
    role = DecorationRole.LINK,
    pillTones = PillTones(
        LookupRequest(
            listOf(Constraint.FactAt(CONNECTION_KIND, ConstraintOp.EQ, "connection", FactLocality.DOCUMENT_ROOT)),
            emptyList(), listOf(KEYWORD), listOf(KEYWORD, COLOUR, RETIRED), listOf(PEOPLE_PLUGIN), 200u, null,
        ),
    ) { hit ->
        fun value(key: String) = hit.facts.firstOrNull { it.predicate == key }?.value
        val keyword = value(KEYWORD)
        if (keyword == null || value(RETIRED) == "true") null else keyword.lowercase() to connectionTone(value(COLOUR))
    },
)

/** A mention looks like what it is in the editor: a link, styled in place
 * and never rewritten. The words are found by the same rule the index uses,
 * so what is drawn as a mention is what the plugin will find. */
fun peopleMentionDecoration() = EditorDecoration(
    id = PEOPLE_MENTION_STYLE,
    recognise = { line -> mentionsInLine(line).map { DecorationMatch(it, DecorationRender.StyleOnly) } },
    role = DecorationRole.LINK,
)

/** Go to definition on a mention: the caret anywhere on `@handle` names the
 * person, by the same lookup the Links panel runs. */
fun peopleDefinitionRegistration() = DefinitionRegistration(
    id = PEOPLE_DEFINITION,
    label = "Go to a person's page",
    key = ::peopleDefinitionKey,
) { source, offset ->
    val view = SourceView(source)
    recognisePeople(view).firstOrNull { reference ->
        val start = view.charOffset(reference.handleRange.start) - 1 // the `@`
        val end = view.charOffset(reference.handleRange.end)
        offset in start..end
    }?.let { reference ->
        DocumentReference("@${reference.handle}", definitionLookup(PERSON_KIND, "person", PERSON_HANDLE, reference.handle))
    }
}

/** The keystroke the settings ask for; absent or blank is the default. */
fun peopleDefinitionKey(settings: String): String =
    jsonString(settings, DEFINITION_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_DEFINITION_KEY

/** The plugin's settings document with the keystroke set — or cleared back
 * to the default when `key` is null. Other keys a later version adds are
 * carried through untouched. */
fun peopleSettingsDocument(current: String, key: String?): String {
    val others = Regex("\"" + Regex.escape(DEFINITION_KEY) + "\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*,?")
        .replace(current.trim().ifEmpty { "{}" }, "")
        .trim().removePrefix("{").removeSuffix("}").trim().trimEnd(',')
    val entries = listOfNotNull(
        key?.let { "\"$DEFINITION_KEY\":\"${escapeJsonString(it)}\"" },
        others.takeIf { it.isNotEmpty() },
    )
    return "{" + entries.joinToString(",") + "}"
}
