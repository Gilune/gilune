package org.gilune.plugin.sdk

import uniffi.gilune_ffi.*

/** Portable intent, owned and validated by its provider. The shared journal
 * persists it with the provider version and defining source revision. */
data class RefactoringIntent(val operation: String, val before: String, val after: String, val merge: Boolean = false)
data class RefactoringAction(val operation: String, val label: String, val current: String, val allowMerge: Boolean = false)
data class RefactoringValidation(val original: LookupRequest, val destination: LookupRequest)

/** No file enumeration, database or host handle crosses this seam. The host
 * supplies each candidate's full immutable snapshot and validates every edit. */
class RefactoringRegistration(
    val id: String,
    val version: String,
    val actions: (SourceTextSnapshot) -> List<RefactoringAction>,
    val validate: (RefactoringIntent) -> RefactoringValidation,
    val candidates: (RefactoringIntent) -> LocationQuery,
    val propose: (RefactoringIntent, SourceTextSnapshot) -> List<SourceTextEdit>,
)

/** A minimal exact edit between two pure proposals. The kernel still validates
 * character boundaries, source version and protected ranges before admission. */
fun sourceDifference(before: String, after: String): List<SourceTextEdit> {
    if (before == after) return emptyList()
    var start = before.commonPrefixWith(after).length
    if (start > 0 && start < before.length && before[start].isLowSurrogate()) start--
    var suffix = before.substring(start).commonSuffixWith(after.substring(start)).length
    if (suffix > 0 && suffix < before.length && before[before.length - suffix].isLowSurrogate()) suffix--
    fun byte(at: Int) = before.substring(0, at).toByteArray(Charsets.UTF_8).size.toULong()
    return listOf(SourceTextEdit(SourceSpan(byte(start), byte(before.length - suffix)), after.substring(start, after.length - suffix)))
}
