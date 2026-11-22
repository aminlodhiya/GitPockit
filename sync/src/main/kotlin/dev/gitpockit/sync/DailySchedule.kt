package dev.gitpockit.sync

import java.time.LocalTime
import java.time.ZonedDateTime

/** Works out when a "daily at these times" schedule fires next. */
object DailySchedule {

    /**
     * The first of [times] (minutes after midnight, local time) strictly after [now].
     * Rolls over to tomorrow when today's times have passed. Times that fall into a
     * daylight-saving gap are moved forward (02:30 on spring-forward day runs at 03:30).
     */
    fun next(times: List<Int>, now: ZonedDateTime): ZonedDateTime? {
        val valid = times.filter { it in 0 until 24 * 60 }.distinct().sorted()
        if (valid.isEmpty()) return null
        for (dayOffset in 0L..1L) {
            val day = now.toLocalDate().plusDays(dayOffset)
            for (minutes in valid) {
                val candidate = ZonedDateTime.of(day, LocalTime.of(minutes / 60, minutes % 60), now.zone)
                if (candidate.isAfter(now)) return candidate
            }
        }
        return null // unreachable: tomorrow's first time is always after now
    }

    fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}
