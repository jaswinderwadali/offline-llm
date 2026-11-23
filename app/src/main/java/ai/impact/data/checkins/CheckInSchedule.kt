package ai.impact.data.checkins

import java.util.Calendar
import java.util.TimeZone

/**
 * When check-ins go out. After the user leaves the app they get at most three, each at the time
 * of day they usually chat: the next day, then two days later, then four days after that. Then
 * nothing until they're back, so it never turns into nagging.
 */
object CheckInSchedule {

    /** Days after the user left that each check-in goes out. */
    val dayOffsets = listOf(1, 3, 7)

    /** No notifications between these times; one due in them moves to the nearest allowed time. */
    const val QUIET_START = 21 * 60 + 30
    const val QUIET_END = 8 * 60 + 30

    /** Keep at least this long between leaving the app and the first check-in. */
    private const val MIN_GAP = 10 * 60 * 60_000L

    /**
     * The minute of the day the user tends to open the app. An hour they've come back to often
     * enough wins; otherwise it's simply when they last used it.
     */
    fun habitualMinute(recentOpens: List<Int>, lastOpen: Int): Int {
        if (recentOpens.size < 3) return lastOpen
        val byHour = recentOpens.groupBy { it / 60 }
        val (_, opens) = byHour.maxWith(compareBy<Map.Entry<Int, List<Int>>> { it.value.size }
            // Ties go to the hour used most recently.
            .thenBy { entry -> recentOpens.lastIndexOf(entry.value.last()) })
        if (opens.size < 3 || opens.size * 10 < recentOpens.size * 3) return lastOpen
        return opens.average().toInt()
    }

    /** Moves a time inside quiet hours to the closest edge: late evening earlier, early morning later. */
    fun outsideQuietHours(minute: Int): Int = when {
        minute >= QUIET_START -> QUIET_START - 60
        minute < QUIET_END -> QUIET_END + 30
        else -> minute
    }

    /** When check-in number [step] goes out, or null once all of them have. */
    fun triggerAt(leftAt: Long, step: Int, minuteOfDay: Int, zone: TimeZone = TimeZone.getDefault()): Long? {
        val days = dayOffsets.getOrNull(step) ?: return null
        val minute = outsideQuietHours(minuteOfDay)
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = leftAt
            add(Calendar.DAY_OF_YEAR, days)
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis - leftAt < MIN_GAP) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    fun minuteOfDay(time: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = time }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }
}
