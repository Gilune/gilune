// Natural-language dates for the `/` menu (ADR 0015 § 2). Natural
// language is UI, never storage: every phrase here resolves to an
// absolute local date, and only the ISO form ever reaches the file. A
// phrase that cannot be resolved yields no date at all, so nothing
// unresolvable can be inserted.
//
// `today` is a parameter everywhere and nothing here reads a clock, so
// the module is deterministic: the caller decides what "today" means and
// the tests fix it. Resolution therefore happens wherever the caller
// says — for the `/` menu, at the instant of insertion.
//
// Pure Kotlin over strings, so it lives in `jvmShared` and a mobile
// reader gets the same answers as the desktop editor (ADR 0006).

package org.gilune.plugins.tasks

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/// One row of the `/` menu's stage two: the words the user sees, and the
/// date they stand for.
///
/// `iso` is **nullable on purpose**. A date being typed digit by digit —
/// `2`, `20`, `2026`, `2026-09` — is not yet a date, but the menu must
/// survive those keystrokes: § 2 dismisses on an empty candidate set, so
/// returning nothing would close the menu at the first digit and make a
/// typed ISO date unreachable. Such a row is displayed and cannot be
/// accepted; `null` is what makes "nothing unresolvable is inserted"
/// enforceable rather than hoped for.
data class DateCandidate(val phrase: String, val iso: String?)

/// The one resolver. Accepts, case- and padding-insensitively:
///
/// - `today`, `tomorrow`
/// - a full weekday name — the **next** occurrence, never today itself
/// - `next week`, `in a week` — both seven days on
/// - a zero-padded ISO `yyyy-MM-dd`, taken verbatim and validated
///
/// Anything else is null: an unrecognised phrase is the user's prose, not
/// a date this feature may guess at.
///
/// **Why a weekday never means today.** Said on a Tuesday, "tuesday" is
/// the Tuesday a week away. The reading is deliberate and it is the one
/// every calendar app takes: today is already reachable by the word
/// `today`, which sits at the top of the same menu, so a weekday that
/// could also mean today would make the two rows ambiguous and would put
/// a date in the past — as far as the rest of the day is concerned —
/// behind the word for the day ahead.
fun resolveDatePhrase(phrase: String, today: LocalDate): LocalDate? {
    val cleaned = normalise(phrase)
    if (cleaned.isEmpty()) return null
    if (ISO_DATE.matches(cleaned)) {
        return try {
            LocalDate.parse(cleaned)
        } catch (_: Exception) {
            // A well-shaped date that does not exist (`2026-02-30`).
            null
        }
    }
    return when (cleaned) {
        "today" -> today
        "tomorrow" -> today.plusDays(1)
        "next week", "in a week" -> today.plusWeeks(1)
        else -> WEEKDAYS[cleaned]?.let { nextOccurrence(it, today) }
    }
}

/// Stage two's rows for what has been typed so far.
///
/// The full list, in this order, is `today`, `tomorrow`, the seven
/// weekdays in calendar order starting with tomorrow's, `next week`,
/// `in a week` — eleven rows that read as the week ahead, and that
/// resolve in ascending date order.
///
/// A fragment narrows it case-insensitively: rows the fragment is a
/// **prefix** of come first, in the canonical order, then rows that
/// merely **contain** it. Typing `t` therefore offers `today` before
/// `saturday`.
///
/// A fragment that is, or is on its way to being, an ISO date replaces
/// the list with one row for the date itself (see [DateCandidate] on why
/// a half-typed one still counts). `2026-9-1` is not that date: the
/// stored form is zero-padded, and a form we would not write is not a
/// form we accept.
fun dateCandidates(fragment: String, today: LocalDate): List<DateCandidate> {
    val typed = normalise(fragment)
    if (typed.isEmpty()) return canonicalPhrases(today).mapNotNull { it.candidate(today) }
    if (ISO_PREFIX.matches(typed)) {
        return listOf(DateCandidate(typed, resolveDatePhrase(typed, today)?.toString()))
    }
    val all = canonicalPhrases(today)
    val prefix = all.filter { it.startsWith(typed) }
    val contains = all.filter { !it.startsWith(typed) && it.contains(typed) }
    return (prefix + contains).mapNotNull { it.candidate(today) }
}

/// The eleven phrases, in menu order, for a given today.
private fun canonicalPhrases(today: LocalDate): List<String> =
    listOf("today", "tomorrow") + weekdaysFrom(today.plusDays(1)) + listOf("next week", "in a week")

/// The seven weekday names in calendar order beginning with `from`'s, so
/// the block reads forwards from tomorrow rather than always from Monday.
private fun weekdaysFrom(from: LocalDate): List<String> =
    (0 until 7).map { WEEKDAY_NAMES[from.plusDays(it.toLong()).dayOfWeek]!! }

private fun String.candidate(today: LocalDate): DateCandidate? =
    resolveDatePhrase(this, today)?.let { DateCandidate(this, it.toString()) }

/// Strictly after today: a weekday that lands on today is a week away.
private fun nextOccurrence(day: DayOfWeek, today: LocalDate): LocalDate {
    val ahead = ((day.value - today.dayOfWeek.value) + 7) % 7
    return today.plusDays(if (ahead == 0) 7L else ahead.toLong())
}

/// Trimmed, lower-cased and with runs of whitespace collapsed, so
/// `  NEXT  week ` is the phrase `next week`. Locale.ROOT because the
/// vocabulary is English and must not shift with the machine's locale
/// (Turkish would otherwise dot the `i` in `in a week`).
private fun normalise(phrase: String): String =
    phrase.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

private val WHITESPACE = Regex("""\s+""")

/// The stored form, and the only date form accepted verbatim.
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/// A date on its way to being typed: a year, optionally a month,
/// optionally a day, each partial. A complete date matches this too, so
/// one branch handles the whole of typing one.
private val ISO_PREFIX = Regex("""\d{1,4}(-\d{0,2}(-\d{0,2})?)?""")

/// Fixed English names in both directions. `DayOfWeek.getDisplayName`
/// is locale-dependent and would silently change what the menu accepts
/// on a machine set to another language.
private val WEEKDAY_NAMES: Map<DayOfWeek, String> = mapOf(
    DayOfWeek.MONDAY to "monday",
    DayOfWeek.TUESDAY to "tuesday",
    DayOfWeek.WEDNESDAY to "wednesday",
    DayOfWeek.THURSDAY to "thursday",
    DayOfWeek.FRIDAY to "friday",
    DayOfWeek.SATURDAY to "saturday",
    DayOfWeek.SUNDAY to "sunday",
)

private val WEEKDAYS: Map<String, DayOfWeek> = WEEKDAY_NAMES.entries.associate { (day, name) -> name to day }
