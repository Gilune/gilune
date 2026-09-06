package org.gilune.plugin.sdk

import uniffi.gilune_ffi.CreatePageRequest
import uniffi.gilune_ffi.PageDefinition
import uniffi.gilune_ffi.PageReceipt
import uniffi.gilune_ffi.PluginContribution

/** The host binds this service to one plugin. No caller-supplied owner, filesystem,
 * registry mutation or general object-write handle crosses this contract. Creation
 * returns a durable receipt; the host's normal projection pass publishes the file. */
interface PageCreationService {
    fun definitions(): List<PageDefinition>
    fun create(request: CreatePageRequest): PageReceipt
    fun initialize(): List<uniffi.gilune_ffi.PageInitialization>
}

const val PAGE_TYPES_SLOT = "pages.types"
const val PAGE_CREATE_CAPABILITY = "pages.create"
const val PAGE_INITIALIZERS_SLOT = "pages.initializers"

data class InitialPage(val key: String, val typeId: String, val relativeFile: String, val yamlProperties: Map<String, String>)

/** One-time initial content, backed by a visible receipt of a dedicated owned
 * page type. Existing target-type history preserves the user's entire catalogue. */
fun pageInitializerContribution(id: String, label: String, receiptType: String, receiptFile: String, pages: List<InitialPage>) =
    PluginContribution(PAGE_INITIALIZERS_SLOT, id, label,
        "{\"version\":1,\"receipt_type\":${pageJsonString(receiptType)},\"receipt_file\":${pageJsonString(receiptFile)},\"pages\":[" +
            pages.joinToString(",") { p ->
                "{\"key\":${pageJsonString(p.key)},\"type_id\":${pageJsonString(p.typeId)},\"relative_file\":${pageJsonString(p.relativeFile)},\"properties\":{" +
                    p.yamlProperties.entries.joinToString(",") { (key, value) -> "${pageJsonString(key)}:${pageJsonString(value)}" } + "}}"
            } + "]}")

/** Literal Markdown template and default folder. User overrides become explicit
 * creation input; they must live in portable documents, not private SDK settings. */
fun pageTypeContribution(
    typeId: String,
    label: String,
    schema: Long = 1,
    defaultFolder: String,
    template: String,
    defaultsFor: String? = null,
): PluginContribution = PluginContribution(
    PAGE_TYPES_SLOT, typeId, label,
    "{\"version\":1,\"schema\":$schema,\"default_folder\":${pageJsonString(defaultFolder)},\"template\":${pageJsonString(template)}" +
        (defaultsFor?.let { ",\"defaults_for\":${pageJsonString(it)}" } ?: "") + "}",
)

/** Form properties take precedence over template properties. All other source is
 * retained by the shared document service; unsupported YAML edits fail visibly. */
fun pageTemplateWithProperties(template: String, yamlValues: Map<String, String>): String {
    val sheet = uniffi.gilune_ffi.inspectSourceProperties(template)
    return uniffi.gilune_ffi.changeSourceProperties(template, sheet.contentVersion,
        yamlValues.map { (key, value) -> uniffi.gilune_ffi.PropertyChange(key, value) }).text
}

fun pageJsonString(value: String): String = buildString {
    append('"')
    for (char in value) when {
        char == '"' -> append("\\\"")
        char == '\\' -> append("\\\\")
        char.code < 32 -> append("\\u" + char.code.toString(16).padStart(4, '0'))
        else -> append(char)
    }
    append('"')
}
