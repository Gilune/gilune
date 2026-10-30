// The date language of `due:` and `done:` (owner, 2026-10-30): every value a
// query may give one of them, resolved to the span of days it means.
//
// Two kinds of time frame, kept apart because people mean both:
//
// - **rolling** — counted back or on from today: `-7d`, `+3d`, `-2w`,
//   `-1m`, `-1y`, or after a day word: `today-3d`, `yesterday`.
// - **calendar** — the week, work week, month, quarter or year a day falls
//   in: `thisweek`, `thisworkweek`, `thismonth`, `thisquarter`, `thisyear`,
//   `lastweek`, `lastmonth`, `lastquarter`, `lastyear`, and the absolute
//   `2026-10`, `2026`, `2026-Q4`. An offset after a period moves the whole
//   period: `thismonth-1m` is all of last month.
//
// Names never contain `-` or `+`, so those only ever mean arithmetic.
// Comparisons wrap any of it: `before:` and `after:` leave their date out,
// `since:` and `until:` keep it; `a..b` is from the start of `a` to the end
// of `b`. The older `week` (today and the six days after), `overdue` and
// `none` keep their meaning.
//
// Pure over strings and an injected `today`, like the rest of the plugin's
// search: nothing reads a clock, so a relative value resolves at evaluation
// and never becomes a stored date.

package org.gilune.plugins.tasks

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.Locale

/// The day a week starts on — a tasks setting, Monday unless changed.
enum class WeekStart(val day: DayOfWeek, val token: String) {
    MONDAY(DayOfWeek.MONDAY, "monday"),
    SUNDAY(DayOfWeek.SUNDAY, "sunday");

    companion object {
        fun of(token: String?): WeekStart = entries.firstOrNull { it.token == token?.trim()?.lowercase(Locale.ROOT) } ?: MONDAY
    }
}

/// What a date value asks of a task's date.
sealed interface DateQuestion {
    /// The task has no such date.
    data object None : DateQuestion

    /// The date falls within `from..to`, both ends included; a null end is
    /// unbounded on that side.
    data class Span(val from: LocalDate?, val to: LocalDate?) : DateQuestion {
        fun contains(date: LocalDate): Boolean =
            (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
    }
}

/// A value that means no date at all, said in words a person can act on.
class DateValueError(message: String) : IllegalArgumentException(message)

/// Resolves one `due:`/`done:` value. Throws [DateValueError] for anything it
/// does not understand — a typo must be said, never answered with silence.
fun resolveDateQuestion(value: String, today: LocalDate, weekStart: WeekStart = WeekStart.MONDAY): DateQuestion {
    val text = value.trim().lowercase(Locale.ROOT)
    if (text.isEmpty()) throw DateValueError("A date is missing after the colon.")
    return when {
        text == NONE_WORD -> DateQuestion.None
        text == OVERDUE_WORD -> DateQuestion.Span(null, today.minusDays(1))
        text == NEXT_SEVEN_WORD -> DateQuestion.Span(today, today.plusDays(6))
        text.startsWith(BEFORE) -> term(text.removePrefix(BEFORE), today, weekStart).let { DateQuestion.Span(null, it.from.minusDays(1)) }
        text.startsWith(AFTER) -> term(text.removePrefix(AFTER), today, weekStart).let { DateQuestion.Span(it.to.plusDays(1), null) }
        text.startsWith(SINCE) -> term(text.removePrefix(SINCE), today, weekStart).let { DateQuestion.Span(it.from, null) }
        text.startsWith(UNTIL) -> term(text.removePrefix(UNTIL), today, weekStart).let { DateQuestion.Span(null, it.to) }
        RANGE in text -> {
            val (start, end) = text.split(RANGE, limit = 2)
            val from = term(start, today, weekStart).from
            val to = term(end, today, weekStart).to
            if (to.isBefore(from)) throw DateValueError("The range ‘$value’ ends before it starts.")
            DateQuestion.Span(from, to)
        }
        else -> term(text, today, weekStart).let { DateQuestion.Span(it.from, it.to) }
    }
}

/// The question in words, e.g. "26 Oct – 1 Nov 2026", for the line under a
/// search that says which days it took.
fun describeDateQuestion(question: DateQuestion): String = when (question) {
    DateQuestion.None -> "no date"
    is DateQuestion.Span -> {
        val from = question.from
        val to = question.to
        when {
            from != null && to != null && from == to -> day(from, withYear = true)
            from != null && to != null -> "${day(from, withYear = from.year != to.year)} – ${day(to, withYear = true)}"
            from != null -> "from ${day(from, withYear = true)}"
            to != null -> "up to ${day(to, withYear = true)}"
            else -> "any date"
        }
    }
}

/// A closed span of days a single term names.
private data class Days(val from: LocalDate, val to: LocalDate)

/// The calendar units a term can be: a day, or the period a day sits in.
private enum class Unit { DAY, WEEK, WORKWEEK, MONTH, QUARTER, YEAR }

/// A term before its offset is applied: the unit, and a day inside it.
private data class Anchor(val unit: Unit, val day: LocalDate)

private fun term(raw: String, today: LocalDate, weekStart: WeekStart): Days {
    val text = raw.trim()
    if (text.isEmpty()) throw DateValueError("A date is missing after the colon.")
    val offset = OFFSET.find(text)
    val base = if (offset == null) text else text.substring(0, offset.range.first)
    var anchor = if (base.isEmpty()) {
        if (offset == null) throw DateValueError("A date is missing after the colon.")
        Anchor(Unit.DAY, today)
    } else anchorOf(base, today) ?: throw unknown(raw, base)
    if (offset != null) {
        val sign = if (offset.groupValues[1] == "-") -1L else 1L
        val amount = sign * offset.groupValues[2].toLong()
        val moved = when (offset.groupValues[3]) {
            "d" -> anchor.day.plusDays(amount)
            "w" -> anchor.day.plusWeeks(amount)
            "m" -> anchor.day.plusMonths(amount)
            else -> anchor.day.plusYears(amount)
        }
        anchor = anchor.copy(day = moved)
    }
    return span(anchor, weekStart)
}

/// The words and absolute forms a term may start with.
private fun anchorOf(base: String, today: LocalDate): Anchor? {
    ISO_DAY.matchEntire(base)?.let { return runCatching { Anchor(Unit.DAY, LocalDate.parse(base)) }.getOrNull() }
    ISO_MONTH.matchEntire(base)?.let { match ->
        val month = match.groupValues[2].toInt()
        return if (month in 1..12) Anchor(Unit.MONTH, LocalDate.of(match.groupValues[1].toInt(), month, 1)) else null
    }
    ISO_YEAR.matchEntire(base)?.let { return Anchor(Unit.YEAR, LocalDate.of(base.toInt(), 1, 1)) }
    ISO_QUARTER.matchEntire(base)?.let { match ->
        val quarter = match.groupValues[2].toInt()
        return if (quarter in 1..4) Anchor(Unit.QUARTER, LocalDate.of(match.groupValues[1].toInt(), quarter * 3 - 2, 1)) else null
    }
    return when (base) {
        "today" -> Anchor(Unit.DAY, today)
        "yesterday" -> Anchor(Unit.DAY, today.minusDays(1))
        "tomorrow" -> Anchor(Unit.DAY, today.plusDays(1))
        "thisweek" -> Anchor(Unit.WEEK, today)
        "thisworkweek" -> Anchor(Unit.WORKWEEK, today)
        "thismonth" -> Anchor(Unit.MONTH, today)
        "thisquarter" -> Anchor(Unit.QUARTER, today)
        "thisyear" -> Anchor(Unit.YEAR, today)
        "lastweek" -> Anchor(Unit.WEEK, today.minusWeeks(1))
        "lastmonth" -> Anchor(Unit.MONTH, today.minusMonths(1))
        "lastquarter" -> Anchor(Unit.QUARTER, today.minusMonths(3))
        "lastyear" -> Anchor(Unit.YEAR, today.minusYears(1))
        else -> {
            // `lastmonday` … `lastsunday`: the most recent one, today included.
            WEEKDAY.entries.firstOrNull { (name, _) -> base == "last$name" }?.let { (_, day) ->
                val back = ((today.dayOfWeek.value - day.value) + 7) % 7
                return Anchor(Unit.DAY, today.minusDays(back.toLong()))
            }
            // A bare weekday keeps the `/` menu's meaning: the next one.
            resolveDatePhrase(base, today)?.let { Anchor(Unit.DAY, it) }
        }
    }
}

private fun span(anchor: Anchor, weekStart: WeekStart): Days {
    val day = anchor.day
    return when (anchor.unit) {
        Unit.DAY -> Days(day, day)
        Unit.WEEK -> weekOf(day, weekStart).let { Days(it, it.plusDays(6)) }
        Unit.WORKWEEK -> {
            val days = (0L..6L).map { weekOf(day, weekStart).plusDays(it) }
                .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            Days(days.first(), days.last())
        }
        Unit.MONTH -> day.withDayOfMonth(1).let { Days(it, it.plusMonths(1).minusDays(1)) }
        Unit.QUARTER -> {
            val first = day.withMonth((day.get(IsoFields.QUARTER_OF_YEAR) - 1) * 3 + 1).withDayOfMonth(1)
            Days(first, first.plusMonths(3).minusDays(1))
        }
        Unit.YEAR -> day.withDayOfYear(1).let { Days(it, it.plusYears(1).minusDays(1)) }
    }
}

private fun weekOf(day: LocalDate, weekStart: WeekStart): LocalDate =
    day.minusDays((((day.dayOfWeek.value - weekStart.day.value) + 7) % 7).toLong())

/// The error for a term nobody recognises, with the nearest word when one
/// is close — a person (or a program) can correct a typo in one step.
private fun unknown(raw: String, base: String): DateValueError {
    val nearest = VOCABULARY.minByOrNull { distance(it, base) }?.takeIf { distance(it, base) <= 2 }
    return DateValueError(
        "Unknown date ‘${raw.trim()}’" + (nearest?.let { " — did you mean $it?" } ?: "") +
            " Dates look like 2026-10-30, today, -7d, thisweek or 2026-10.",
    )
}

/// Edit distance, for the suggestion only.
private fun distance(a: String, b: String): Int {
    val row = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var previous = row[0]
        row[0] = i
        for (j in 1..b.length) {
            val held = row[j]
            row[j] = minOf(row[j] + 1, row[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
            previous = held
        }
    }
    return row[b.length]
}

private fun day(date: LocalDate, withYear: Boolean): String =
    "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}" + if (withYear) " ${date.year}" else ""

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private val WEEKDAY = linkedMapOf(
    "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
    "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
    "sunday" to DayOfWeek.SUNDAY,
)

/// Every word a term may be, for suggestions.
private val VOCABULARY = listOf(
    "today", "yesterday", "tomorrow", "thisweek", "thisworkweek", "thismonth", "thisquarter", "thisyear",
    "lastweek", "lastmonth", "lastquarter", "lastyear", NONE_WORD, OVERDUE_WORD, NEXT_SEVEN_WORD,
) + WEEKDAY.keys + WEEKDAY.keys.map { "last$it" }

private const val NONE_WORD = "none"
private const val OVERDUE_WORD = "overdue"
private const val NEXT_SEVEN_WORD = "week"
private const val BEFORE = "before:"
private const val AFTER = "after:"
private const val SINCE = "since:"
private const val UNTIL = "until:"
private const val RANGE = ".."

/// A trailing `+N` or `-N` with its unit. The unit is required, so the
/// `-10` of `2026-10` is never read as an offset.
private val OFFSET = Regex("""([+-])(\d{1,4})([dwmy])$""")
private val ISO_DAY = Regex("""\d{4}-\d{2}-\d{2}""")
private val ISO_MONTH = Regex("""(\d{4})-(\d{2})""")
private val ISO_YEAR = Regex("""\d{4}""")
private val ISO_QUARTER = Regex("""(\d{4})-q(\d)""")
