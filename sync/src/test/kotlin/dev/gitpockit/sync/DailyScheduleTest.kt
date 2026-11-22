package dev.gitpockit.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyScheduleTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin") // has daylight saving time

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)

    @Test fun picksNextTimeToday() {
        val next = DailySchedule.next(listOf(9 * 60, 21 * 60 + 30), at(kolkata, 2026, 11, 22, 12, 0))
        assertEquals(at(kolkata, 2026, 11, 22, 21, 30), next)
    }

    @Test fun rollsOverToTomorrow() {
        val next = DailySchedule.next(listOf(9 * 60, 21 * 60 + 30), at(kolkata, 2026, 11, 22, 22, 0))
        assertEquals(at(kolkata, 2026, 11, 23, 9, 0), next)
    }

    @Test fun exactlyNowSchedulesTheNextOne() {
        // The alarm that fires at 09:00 must schedule the following one, not itself again.
        val next = DailySchedule.next(listOf(9 * 60), at(kolkata, 2026, 11, 22, 9, 0))
        assertEquals(at(kolkata, 2026, 11, 23, 9, 0), next)
    }

    @Test fun unsortedDuplicateAndInvalidTimesAreHandled() {
        val next = DailySchedule.next(listOf(1290, 540, 540, -5, 9999), at(kolkata, 2026, 11, 22, 10, 0))
        assertEquals(at(kolkata, 2026, 11, 22, 21, 30), next)
    }

    @Test fun noTimesMeansNoSchedule() {
        assertNull(DailySchedule.next(emptyList(), at(kolkata, 2026, 11, 22, 10, 0)))
    }

    @Test fun springForwardGapMovesLater() {
        // Berlin skips 02:00–03:00 on 2026-03-29.
        val next = DailySchedule.next(listOf(2 * 60 + 30), at(berlin, 2026, 3, 29, 1, 0))!!
        assertEquals(3, next.hour)
        assertEquals(30, next.minute)
        assertEquals(29, next.dayOfMonth)
    }

    @Test fun fallBackRunsOnce() {
        // 02:30 happens twice on 2026-10-25 in Berlin; after the first one, the next run is tomorrow.
        val first = DailySchedule.next(listOf(2 * 60 + 30), at(berlin, 2026, 10, 25, 1, 0))!!
        val second = DailySchedule.next(listOf(2 * 60 + 30), first)!!
        assertEquals(26, second.dayOfMonth)
    }

    @Test fun formatsTimes() {
        assertEquals("09:05", DailySchedule.format(545))
        assertEquals("00:00", DailySchedule.format(0))
    }
}
