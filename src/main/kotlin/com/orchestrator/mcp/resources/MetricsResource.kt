package com.orchestrator.mcp.resources

import com.orchestrator.storage.repositories.MetricsRepository
import com.orchestrator.storage.repositories.MetricsRepository.Aggregation
import com.orchestrator.storage.repositories.MetricsRepository.Interval
import com.orchestrator.storage.repositories.MetricsRepository.TimeRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.Duration
import java.time.format.DateTimeParseException

/**
 * MCP Resource: metrics://
 *
 * Provides aggregated metrics and optional time-bucketed series in JSON.
 *
 * Query parameters:
 * - name: metric name or comma-separated list of names (required)
 * - from: ISO-8601 instant, inclusive (default: now-24h)
 * - to: ISO-8601 instant, inclusive (default: now)
 * - agg: COUNT|SUM|AVG|MIN|MAX|P50|P90|P99 (default: SUM)
 * - interval: minute|hour|day (optional; if present returns time series in addition to overall aggregate)
 *
 * Returns JSON like:
 * {
 *   "uri": "metrics://?name=latency.ms&from=...",
 *   "range": {"from":"...","to":"..."},
 *   "aggregation": "SUM",
 *   "interval": "HOUR", // only if provided
 *   "metrics": {
 *     "latency.ms": {
 *       "value": 123.4,
 *       "series": [{"t":"...","value": 1.2}, ...] // only if interval provided
 *     }
 *   }
 * }
 */

@Serializable
data class TimeRangeDto(val from: String, val to: String)

@Serializable
data class TimeSeriesPoint(val t: String, val value: Double?)

@Serializable
data class MetricData(val value: Double?, val series: List<TimeSeriesPoint>? = null)

@Serializable
data class MetricsResponse(
    val uri: String,
    val range: TimeRangeDto,
    val aggregation: String,
    val interval: String? = null,
    val metrics: Map<String, MetricData>
)

class MetricsResource {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    fun query(query: Map<String, String>): String {
        val names = parseNames(query["name"]) ?: throw IllegalArgumentException("Missing required 'name' query parameter")
        val (from, to) = parseRange(query["from"], query["to"])
        val agg = parseAggregation(query["agg"]) ?: Aggregation.SUM
        val interval = parseInterval(query["interval"]) // nullable

        val timeRange = TimeRange(start = from, end = to)

        // Build metrics map
        val metricsMap = names.associateWith { name ->
            val value = MetricsRepository.aggregateMetrics(name, timeRange, agg)
            val normalizedValue = value?.let { normalizeDouble(it) }

            val series = interval?.let { iv ->
                MetricsRepository.aggregateByInterval(name, timeRange, agg, iv)
                    .map { (instant, dbl) ->
                        TimeSeriesPoint(
                            t = instant.toString(),
                            value = dbl?.let { normalizeDouble(it) }
                        )
                    }
            }

            MetricData(value = normalizedValue, series = series)
        }

        val response = MetricsResponse(
            uri = buildUri(query),
            range = TimeRangeDto(from = from.toString(), to = to.toString()),
            aggregation = agg.name,
            interval = interval?.name,
            metrics = metricsMap
        )

        return json.encodeToString(response)
    }

    // region helpers
    private fun parseNames(raw: String?): List<String>? {
        val v = raw?.trim() ?: return null
        if (v.isEmpty()) return null
        val filtered = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (filtered.isEmpty()) return null  // Reject empty lists like "name=,"
        return filtered
    }

    private fun parseRange(fromRaw: String?, toRaw: String?): Pair<Instant, Instant> {
        val now = Instant.now()
        val from = parseInstantOrNull(fromRaw) ?: now.minus(Duration.ofHours(24))
        val to = parseInstantOrNull(toRaw) ?: now
        require(!to.isBefore(from)) { "'to' must be >= 'from'" }
        return from to to
    }

    private fun parseInstantOrNull(v: String?): Instant? {
        val s = v?.trim() ?: return null
        if (s.isEmpty()) return null
        return try {
            Instant.parse(s)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Invalid timestamp '$s'. Expected ISO-8601 instant, e.g., 2025-01-01T00:00:00Z")
        }
    }

    private fun parseAggregation(v: String?): Aggregation? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try { Aggregation.valueOf(s.uppercase()) } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid agg '$s'. Allowed: ${Aggregation.values().joinToString(",")} ")
        }
    }

    private fun parseInterval(v: String?): Interval? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (s.lowercase()) {
            "minute", "min", "minutes" -> Interval.MINUTE
            "hour", "hr", "hours" -> Interval.HOUR
            "day", "d", "days" -> Interval.DAY
            else -> throw IllegalArgumentException("Invalid interval '$s'. Allowed: minute|hour|day")
        }
    }

    /**
     * Normalize Double values for JSON serialization.
     * Converts NaN and Infinity to null since they're not valid JSON.
     */
    private fun normalizeDouble(d: Double): Double? {
        return if (d.isFinite()) d else null
    }

    private fun buildUri(query: Map<String, String>): String {
        if (query.isEmpty()) return "metrics://"
        val qp = query.entries.joinToString("&") { (k, v) ->
            encode(k) + "=" + encode(v)
        }
        return "metrics://?" + qp
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)
    // endregion
}
