// Moving a task's due date (ADR 0015 amendment 2026-10-31; the owner's ask
// after Obsidian Tasks' postpone button). One menu, offered wherever a task
// line is: the arrow beside a `due:` token, the right-click menu, the date
// shortcut, and a task row in a `filter` block. It writes exactly what the
// `/due` menu writes — the ISO token, nothing else — so a postponed task
// reads in Notepad as a task with a later date and nothing more. No
// "rescheduled" state, no count of postponements: when a line changed is
// the line history's to say (ADR 0028).
//
// Pure over the line, with `today` a parameter, as TaskDates.kt is.

package org.gilune.plugins.tasks

import org.gilune.plugin.sdk.TokenMenu
import org.gilune.plugin.sdk.TokenMenuItem

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/// The menu for one line, or null when the line is not a task the plugin
/// owns. A dated task is postponed **from its date** — or from today once
/// that date has passed, since a week later than a day already gone is
/// rarely what anyone wants. An undated task is offered a date to start
/// with. Every item says the day it writes; an item that would write the
/// date already there, or a day an earlier item already offers, is left
/// out, so no two rows do the same thing.
fun dueDateMenu(line: String, today: LocalDate, filter: String = "", weekStart: WeekStart = WeekStart.MONDAY): TokenMenu? {
    if (!isTaskLine(line, filter)) return null
    val due = taskDateIn(line, DUE_FIELD)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val from = if (due == null || due < today) today else due
    val nextWeek = today.with(TemporalAdjusters.next(weekStart.day))

    val offered = mutableSetOf<LocalDate>()
    due?.let { offered += it }
    val items = mutableListOf<TokenMenuItem>()
    // A group's first row may be one that drops out; the rule then goes
    // above the first row of that group that stays.
    var groupStarts = false
    fun offer(label: String, date: LocalDate, startsGroup: Boolean = false) {
        if (startsGroup) groupStarts = true
        if (!offered.add(date)) return
        items += TokenMenuItem(label, dayLabel(date, today), { setDue(it, date) }, groupStarts && items.isNotEmpty())
        groupStarts = false
    }

    fun postpone() {
        offer("+1 day", from.plusDays(1), startsGroup = true)
        offer("+2 days", from.plusDays(2))
        offer("+3 days", from.plusDays(3))
        offer("+1 week", from.plusWeeks(1))
        offer("+2 weeks", from.plusWeeks(2))
        offer("+1 month", from.plusMonths(1))
    }
    fun days() {
        offer("Today", today, startsGroup = true)
        offer("Tomorrow", today.plusDays(1))
        offer("Next ${weekdayName(weekStart)}", nextWeek)
    }
    // A date still ahead is moved on from itself, so its steps lead; one
    // already gone counts from today, so the named days lead and the steps
    // that would repeat them drop out.
    when {
        due == null -> {
            days()
            offer("In a week", today.plusWeeks(1))
            offer("In a month", today.plusMonths(1))
        }
        due < today -> { days(); postpone() }
        else -> { postpone(); days() }
    }
    if (due != null) items += TokenMenuItem("Remove due date", "", ::removeDue, startsGroup = true)

    val title = when {
        due == null -> "Add a due date"
        due < today -> "Overdue · moves from today"
        else -> "Postpone from ${dayLabel(due, today)}"
    }
    return TokenMenu(
        name = if (due == null) "Add a due date" else "Postpone due date",
        title = title,
        items = items,
        typed = { words -> typedDue(words, from, today)?.let { (label, date) -> TokenMenuItem(label, dayLabel(date, today), { setDue(it, date) }) } },
        typedPlaceholder = "friday, +3d, 2026-11-20",
    )
}

/// The line with its due date set to `date`: the first `due:` token's date
/// replaced where it stands, or — on a line with none — one appended, the
/// placement `/due` and the done stamp also use, since appending cannot
/// disturb a byte anybody typed.
fun setDue(line: String, date: LocalDate): String {
    val token = DUE_TOKEN_AT.find(line)
    if (token != null) return line.replaceRange(token.groups[1]!!.range, date.toString())
    return if (line.isEmpty() || line.last().isWhitespace()) "${line}due:$date" else "$line due:$date"
}

/// The line without its first due date, and the whitespace before it that
/// the token owns — the same cut `stripDone` makes.
fun removeDue(line: String): String {
    val token = DUE_TOKEN_AT.find(line) ?: return line
    return line.removeRange(token.range)
}

/// Words typed into the menu's field: whatever `/due` accepts (`friday`,
/// `tomorrow`, an ISO date — or the start of one of its phrases, so `fri`
/// is Friday), or an offset from the date being moved, `+3d`, `2w`, `+1m`.
/// The label is what the words were taken to mean.
internal fun typedDue(words: String, from: LocalDate, today: LocalDate): Pair<String, LocalDate>? {
    val typed = words.trim().lowercase(Locale.ROOT)
    if (typed.isEmpty()) return null
    OFFSET.matchEntire(typed)?.let { match ->
        val count = match.groupValues[1].toLongOrNull()?.takeIf { it in 1..999 } ?: return null
        val date = when (match.groupValues[2]) {
            "d" -> from.plusDays(count)
            "w" -> from.plusWeeks(count)
            else -> from.plusMonths(count)
        }
        val unit = when (match.groupValues[2]) { "d" -> "day"; "w" -> "week"; else -> "month" }
        return "+$count $unit${if (count == 1L) "" else "s"}" to date
    }
    resolveDatePhrase(typed, today)?.let { return typed to it }
    val candidate = dateCandidates(typed, today).firstOrNull { it.iso != null } ?: return null
    return candidate.phrase to LocalDate.parse(candidate.iso)
}

/// "Fri 6 Nov", with the year once it is not this one. English and fixed,
/// as the phrases are: the menu must read the same on every machine.
fun dayLabel(date: LocalDate, today: LocalDate): String =
    date.format(if (date.year == today.year) DAY else DAY_WITH_YEAR)

private fun weekdayName(weekStart: WeekStart): String =
    weekStart.day.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }

/// A due token, its date captured: § 1's pattern, the leading whitespace
/// part of the match so that removing the token takes it too.
private val DUE_TOKEN_AT = Regex("""\sdue:(\d{4}-\d{2}-\d{2})\b""")

private val OFFSET = Regex("""\+?\s*(\d+)\s*([dwm])""")

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val DAY_WITH_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
