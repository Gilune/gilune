package org.gilune.plugins.tasks

import org.gilune.plugin.sdk.SuggestCandidate

import java.time.LocalDate

/// The provider behind the `high` and `low` fields (ADR 0015 amendment
/// 2026-10-23): exactly one candidate for the field's own level, so a host
/// that honours a single-value field inserts it in one step — `/high`,
/// Enter, done — and a host that does not shows a one-row second stage.
const val PRIORITY_VALUES_PROVIDER = "org.gilune.priority"

fun priorityValues(key: String): (String, LocalDate) -> List<SuggestCandidate> = { _, _ ->
    TaskPriority.entries.filter { it.token == key }.map { level ->
        SuggestCandidate("${level.label} priority", "priority:${level.token}", "priority:${level.token}")
    }
}
