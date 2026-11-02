package dev.zig.notificationfilter.domain.summary

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

internal data class DailySummaryOccurrence(val id: String, val scheduledAt: Long, val zoneId: String) {
    val date: LocalDate get() = Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.of(zoneId)).toLocalDate()
    val startOfDay: Long get() = date.atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
}

internal object DailySummaryPolicy {
    const val MAX_LATENESS_MS = 24L * 60L * 60L * 1_000L

    fun next(now: Instant, zone: ZoneId, time: LocalTime, attemptedDate: String?): DailySummaryOccurrence {
        val localNow = now.atZone(zone)
        var date = localNow.toLocalDate()
        var target = date.atTime(time).atZone(zone)
        if (!target.toInstant().isAfter(now) || date.toString() == attemptedDate) {
            date = date.plusDays(1)
            target = date.atTime(time).atZone(zone)
        }
        return DailySummaryOccurrence(UUID.randomUUID().toString(), target.toInstant().toEpochMilli(), zone.id)
    }

    fun isDue(occurrence: DailySummaryOccurrence, now: Instant): Boolean {
        val age = now.toEpochMilli() - occurrence.scheduledAt
        return age in 0..MAX_LATENESS_MS
    }
}
