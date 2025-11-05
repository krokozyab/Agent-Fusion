package com.orchestrator.web.utils

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object TimeFormatters {
    private val absoluteFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

    fun relativeTime(
        from: Instant,
        reference: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): RelativeTime {
        val duration = Duration.between(from, reference)
        val isFuture = duration.isNegative
        val absolute = ZonedDateTime.ofInstant(from, zoneId).format(absoluteFormatter)

        val humanized = when {
            duration.abs() < Duration.ofMinutes(1) -> "just now"
            duration.abs() < Duration.ofHours(1) -> format(duration, Duration.ofMinutes(1), "minute", isFuture)
            duration.abs() < Duration.ofDays(1) -> format(duration, Duration.ofHours(1), "hour", isFuture)
            duration.abs() < Duration.ofDays(30) -> format(duration, Duration.ofDays(1), "day", isFuture)
            duration.abs() < Duration.ofDays(365) -> format(duration, Duration.ofDays(30), "month", isFuture)
            else -> format(duration, Duration.ofDays(365), "year", isFuture)
        }

        return RelativeTime(humanized = humanized, absolute = absolute)
    }

    private fun format(duration: Duration, unit: Duration, label: String, future: Boolean): String {
        val amount = (duration.abs().toMillis() / unit.toMillis()).coerceAtLeast(1)
        val plural = if (amount == 1L) label else "${label}s"
        return if (future) {
            "in $amount $plural"
        } else {
            "$amount $plural ago"
        }
    }

    data class RelativeTime(
        val humanized: String,
        val absolute: String
    )
}
