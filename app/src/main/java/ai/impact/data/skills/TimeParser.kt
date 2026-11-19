package ai.impact.data.skills

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pulls a time out of a reminder request such as "call mom tomorrow at 6pm" or "stretch in
 * 20 minutes", and what's left over becomes the reminder's title. It's deliberately rule-based:
 * small on-device models are too unreliable for dates, and this works with no model loaded.
 */
object TimeParser {

    data class Result(val triggerAt: Long, val title: String)

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "forty five" to 45, "few" to 3, "couple of" to 2
    )
    private val numberPattern = (listOf("\\d+") + numberWords.keys.sortedByDescending { it.length }).joinToString("|")

    private val relative = Regex(
        "\\b(?:in|after)\\s+($numberPattern)\\s+(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?|weeks?)\\b",
        RegexOption.IGNORE_CASE
    )
    private val halfHour = Regex("\\b(?:in|after)\\s+(?:half an hour|30 mins?)\\b", RegexOption.IGNORE_CASE)
    private val clock = Regex(
        "\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)(?=\\W|$)|\\b(?:at\\s+)?(\\d{1,2}):(\\d{2})\\b|\\bat\\s+(\\d{1,2})\\b",
        RegexOption.IGNORE_CASE
    )
    private val namedTime = Regex("\\b(?:at\\s+)?(noon|midday|midnight)\\b", RegexOption.IGNORE_CASE)
    private val partOfDay = Regex("\\b(?:this\\s+|in the\\s+)?(morning|afternoon|evening|tonight|night)\\b", RegexOption.IGNORE_CASE)
    private val dayAfterTomorrow = Regex("\\b(?:the\\s+)?day after tomorrow\\b", RegexOption.IGNORE_CASE)
    private val tomorrow = Regex("\\b(?:tomorrow|tmrw|tmr)\\b", RegexOption.IGNORE_CASE)
    private val today = Regex("\\btoday\\b", RegexOption.IGNORE_CASE)
    private val weekday = Regex(
        "\\b(?:on\\s+|next\\s+|this\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE
    )
    private val leadIns = Regex(
        "^(?:please\\s+)?(?:remind me(?:\\s+to)?|reminder(?:\\s+to)?|notify me(?:\\s+to)?|ping me(?:\\s+to)?|" +
            "alert me(?:\\s+to)?|tell me(?:\\s+to)?|set a reminder(?:\\s+to)?|schedule(?:\\s+a)?(?:\\s+notification)?(?:\\s+to)?|to)\\s+",
        RegexOption.IGNORE_CASE
    )

    /** Returns null when the text names no time at all. */
    fun parse(text: String, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Result? {
        var rest = text
        fun consume(match: MatchResult) {
            rest = rest.replaceRange(match.range, " ".repeat(match.value.length))
        }

        val cal = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = now }

        // Relative times ("in 20 minutes") stand alone.
        halfHour.find(rest)?.let { match ->
            consume(match)
            return Result(now + 30 * 60_000L, titleFrom(rest))
        }
        relative.find(rest)?.let { match ->
            consume(match)
            val amount = match.groupValues[1].lowercase().let { it.toIntOrNull() ?: numberWords[it] ?: 1 }
            val unit = match.groupValues[2].lowercase()
            val millis = when {
                unit.startsWith("s") -> 1_000L
                unit.startsWith("m") -> 60_000L
                unit.startsWith("h") -> 3_600_000L
                unit.startsWith("d") -> 86_400_000L
                else -> 7 * 86_400_000L
            }
            return Result(now + amount * millis, titleFrom(rest))
        }

        // Otherwise, a day and/or a time of day.
        var dayOffset: Int? = null
        dayAfterTomorrow.find(rest)?.let { consume(it); dayOffset = 2 }
        if (dayOffset == null) tomorrow.find(rest)?.let { consume(it); dayOffset = 1 }
        if (dayOffset == null) today.find(rest)?.let { consume(it); dayOffset = 0 }
        if (dayOffset == null) weekday.find(rest)?.let { match ->
            consume(match)
            val target = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
                .indexOf(match.groupValues[1].lowercase()) + 1
            val diff = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            dayOffset = if (diff == 0) 7 else diff
        }

        var hour: Int? = null
        var minute = 0
        var meridiemKnown = false
        clock.find(rest)?.let { match ->
            consume(match)
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> {
                    hour = g[1].toInt() % 12 + if (g[3].lowercase().startsWith("p")) 12 else 0
                    minute = g[2].toIntOrNull() ?: 0
                    meridiemKnown = true
                }
                g[4].isNotEmpty() -> {
                    hour = g[4].toInt()
                    minute = g[5].toInt()
                    meridiemKnown = hour!! == 0 || hour!! > 12
                }
                else -> hour = g[6].toInt()
            }
        }
        if (hour == null) namedTime.find(rest)?.let { match ->
            consume(match)
            hour = if (match.groupValues[1].lowercase() == "midnight") 0 else 12
            meridiemKnown = true
        }
        val part = partOfDay.find(rest)
        if (part != null) {
            consume(part)
            val word = part.groupValues[1].lowercase()
            if (hour == null) {
                hour = when (word) {
                    "morning" -> 9
                    "afternoon" -> 15
                    "evening" -> 18
                    else -> 21
                }
                meridiemKnown = true
            } else if (!meridiemKnown) {
                // "at 7 tonight" means 7pm; "at 7 in the morning" means 7am.
                if (word != "morning" && hour!! < 12) hour = hour!! + 12
                meridiemKnown = true
            }
            if (word == "tonight" && dayOffset == null) dayOffset = 0
        }

        if (hour == null && dayOffset == null) return null
        if ((hour ?: 0) > 23 || minute > 59) return null

        val target = Calendar.getInstance(zone, Locale.US).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset ?: 0)
            set(Calendar.HOUR_OF_DAY, hour ?: 9)
            set(Calendar.MINUTE, if (hour == null) 0 else minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (hour == null) {
            // A day with no time: 9am, or an hour from now if that's already passed today.
            if (target.timeInMillis <= now) target.timeInMillis = now + 3_600_000L
        } else if (target.timeInMillis <= now) {
            // "at 5" said at 3pm means 5pm; a time that's passed today means tomorrow.
            if (!meridiemKnown && hour!! < 12) target.add(Calendar.HOUR_OF_DAY, 12)
            if (target.timeInMillis <= now && dayOffset == null) target.add(Calendar.DAY_OF_YEAR, 1)
            if (target.timeInMillis <= now) return null
        }
        return Result(target.timeInMillis, titleFrom(rest))
    }

    private fun titleFrom(rest: String): String {
        var title = rest.replace(Regex("\\s+"), " ").trim()
        // Drop words left dangling by the time phrase ("at", "on", "by").
        title = title.replace(Regex("\\b(?:at|on|by|for)\\s*$", RegexOption.IGNORE_CASE), "").trim()
        title = title.replace(Regex("\\s+(?:at|on|by)\\s+(?=[,.!?]|$)", RegexOption.IGNORE_CASE), " ").trim()
        repeat(2) { title = leadIns.replaceFirst(title, "").trim() }
        title = title.trim(' ', ',', '.', '!', '?', '-', ':')
        if (title.isEmpty()) return "Reminder"
        return title.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
