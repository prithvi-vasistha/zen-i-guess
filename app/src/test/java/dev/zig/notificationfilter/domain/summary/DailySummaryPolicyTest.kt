package dev.zig.notificationfilter.domain.summary

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailySummaryPolicyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun instant(local: String, targetZone: ZoneId = zone) = LocalDateTime.parse(local).atZone(targetZone).toInstant()
    private fun next(local: String, attempted: String? = null) = DailySummaryPolicy.next(instant(local), zone, LocalTime.of(20, 0), attempted)

    @Test fun beforeChosenTimeSchedulesToday() {
        assertEquals(instant("2026-10-09T20:00:00").toEpochMilli(), next("2026-10-09T19:59:00").scheduledAt)
    }
    @Test fun atChosenTimeSchedulesTomorrow() {
        assertEquals(instant("2026-10-10T20:00:00").toEpochMilli(), next("2026-10-09T20:00:00").scheduledAt)
    }
    @Test fun afterChosenTimeSchedulesTomorrow() {
        assertEquals(instant("2026-10-10T20:00:00").toEpochMilli(), next("2026-10-09T23:00:00").scheduledAt)
    }
    @Test fun anAttemptedDateCannotSendAnotherRecapAfterTimeChange() {
        assertEquals("2026-10-10", next("2026-10-09T19:00:00", "2026-10-09").date.toString())
    }
    @Test fun previousDateDoesNotSuppressToday() {
        assertEquals("2026-10-09", next("2026-10-09T19:00:00", "2026-10-08").date.toString())
    }
    @Test fun springClockChangeKeepsTheLocalTimeWithA23HourInterval() {
        val newYork = ZoneId.of("America/New_York")
        val now = instant("2026-03-07T20:00:00", newYork)
        val target = DailySummaryPolicy.next(now, newYork, LocalTime.of(20, 0), null)
        assertEquals(23L * 60 * 60 * 1000, target.scheduledAt - now.toEpochMilli())
        assertEquals(LocalTime.of(20, 0), Instant.ofEpochMilli(target.scheduledAt).atZone(newYork).toLocalTime())
    }
    @Test fun autumnClockChangeKeepsTheLocalTimeWithA25HourInterval() {
        val newYork = ZoneId.of("America/New_York")
        val now = instant("2026-10-31T20:00:00", newYork)
        assertEquals(25L * 60 * 60 * 1000,
            DailySummaryPolicy.next(now, newYork, LocalTime.of(20, 0), null).scheduledAt - now.toEpochMilli())
    }
    @Test fun timezoneChangeUsesTheNewZone() {
        val now = Instant.parse("2026-10-09T10:00:00Z")
        val utc = DailySummaryPolicy.next(now, ZoneId.of("UTC"), LocalTime.of(20, 0), null)
        val india = DailySummaryPolicy.next(now, zone, LocalTime.of(20, 0), null)
        assertEquals(5L * 60 * 60 * 1000 + 30L * 60 * 1000, utc.scheduledAt - india.scheduledAt)
    }
    @Test fun aLateAlarmRetainsItsOriginalDateAndWindow() {
        val occurrence = DailySummaryOccurrence("late", instant("2026-10-09T20:00:00").toEpochMilli(), zone.id)
        assertTrue(DailySummaryPolicy.isDue(occurrence, instant("2026-10-10T01:00:00")))
        assertEquals("2026-10-09", occurrence.date.toString())
        assertEquals(instant("2026-10-09T00:00:00").toEpochMilli(), occurrence.startOfDay)
    }
    @Test fun alarmNeverPublishesBeforeItsTarget() {
        val occurrence = next("2026-10-09T19:00:00")
        assertFalse(DailySummaryPolicy.isDue(occurrence, instant("2026-10-09T19:59:59")))
        assertTrue(DailySummaryPolicy.isDue(occurrence, instant("2026-10-09T20:00:00")))
    }
    @Test fun anOldAlarmExpires() {
        val occurrence = next("2026-10-09T19:00:00")
        assertFalse(DailySummaryPolicy.isDue(occurrence, instant("2026-10-10T20:00:01")))
    }
}
