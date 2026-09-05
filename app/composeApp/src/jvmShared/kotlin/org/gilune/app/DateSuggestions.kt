package org.gilune.app

import java.time.LocalDate

/// Existing public provider id, implemented by the bundled Tasks plugin.
/// The host resolves this through registration, without interpreting dates.
const val DATE_VALUES_PROVIDER = "org.gilune.dates"

/// Stage two for a date field: what the user typed, narrowed and
/// resolved, with `<key>:<ISO>` as the token to write.
fun dateValues(key: String): (String, LocalDate) -> List<SuggestCandidate> = { fragment, today ->
    dateCandidates(fragment, today).map { candidate ->
        SuggestCandidate(
            display = candidate.phrase,
            detail = candidate.iso ?: "",
            insert = candidate.iso?.let { "$key:$it" },
        )
    }
}

