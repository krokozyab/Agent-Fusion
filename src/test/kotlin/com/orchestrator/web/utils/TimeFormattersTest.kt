package com.orchestrator.web.utils

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormattersTest {

    @Test
    fun `formats past durations into human readable text`() {
        val base = Instant.parse("2025-01-01T12:00:00Z")
        val past = base.minusSeconds(3600)

        val relative = TimeFormatters.relativeTime(past, base, ZoneId.of("UTC"))

        assertEquals("1 hour ago", relative.humanized)
        assertEquals("2025-01-01 11:00 UTC", relative.absolute)
    }

    @Test
    fun `formats future durations into human readable text`() {
        val base = Instant.parse("2025-01-01T12:00:00Z")
        val future = base.plusSeconds(120)

        val relative = TimeFormatters.relativeTime(future, base, ZoneId.of("UTC"))

        assertEquals("in 2 minutes", relative.humanized)
    }
}
