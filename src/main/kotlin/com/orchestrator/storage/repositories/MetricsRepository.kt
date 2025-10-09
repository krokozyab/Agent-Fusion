package com.orchestrator.storage.repositories

import com.orchestrator.storage.Database
import java.sql.Timestamp
import java.time.Instant

/**
 * Repository for time-series metrics stored in metrics_timeseries.
 * Focuses on efficient time-range queries, correct aggregations, and high-volume inserts.
 */
object MetricsRepository {
    // region Public API
    data class Metric(
        val id: Long?,
        val taskId: String?,
        val agentId: String?,
        val name: String,
        val ts: Instant,
        val value: Double?,
        val tags: Map<String, String>
    )

    data class TimeRange(val start: Instant, val end: Instant)

    enum class Aggregation { COUNT, SUM, AVG, MIN, MAX, P50, P90, P99 }

    /**
     * Record a single metric sample. taskId/agentId/tags are optional.
     * ts defaults to now() if not provided to minimize caller overhead.
     */
    fun recordMetric(
        name: String,
        value: Double?,
        tags: Map<String, String> = emptyMap(),
        taskId: String? = null,
        agentId: String? = null,
        ts: Instant = Instant.now()
    ) = Database.withConnection { conn ->
        val newId = generateId()
        val sql = """
            INSERT INTO metrics_timeseries (id, task_id, agent_id, metric_name, ts, value, tags)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setLong(i++, newId)
            ps.setString(i++, taskId)
            ps.setString(i++, agentId)
            ps.setString(i++, name)
            ps.setTimestamp(i++, Timestamp.from(ts))
            if (value == null) ps.setObject(i++, null) else ps.setDouble(i++, value)
            ps.setString(i, mapToJson(tags))
            ps.executeUpdate()
        }
    }

    /**
     * Batch insert for high-volume writes. Uses JDBC batching to minimize round trips.
     */
    fun recordMetricsBatch(entries: List<MetricInsert>) = Database.withConnection { conn ->
        if (entries.isEmpty()) return@withConnection
        val sql = """
            INSERT INTO metrics_timeseries (id, task_id, agent_id, metric_name, ts, value, tags)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            for (e in entries) {
                var i = 1
                ps.setLong(i++, generateId())
                ps.setString(i++, e.taskId)
                ps.setString(i++, e.agentId)
                ps.setString(i++, e.name)
                ps.setTimestamp(i++, Timestamp.from(e.ts))
                if (e.value == null) ps.setObject(i++, null) else ps.setDouble(i++, e.value)
                ps.setString(i, mapToJson(e.tags))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    data class MetricInsert(
        val name: String,
        val value: Double?,
        val tags: Map<String, String> = emptyMap(),
        val taskId: String? = null,
        val agentId: String? = null,
        val ts: Instant = Instant.now()
    )

    /**
     * Query metrics by name and time range. Results ordered by ts ascending.
     */
    fun queryMetrics(name: String, timeRange: TimeRange): List<Metric> = Database.withConnection { conn ->
        val sql = """
            SELECT id, task_id, agent_id, metric_name, ts, value, tags
            FROM metrics_timeseries
            WHERE metric_name = ? AND ts >= ? AND ts <= ?
            ORDER BY ts ASC
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, name)
            ps.setTimestamp(2, Timestamp.from(timeRange.start))
            ps.setTimestamp(3, Timestamp.from(timeRange.end))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Metric>()
                while (rs.next()) {
                    out += Metric(
                        id = rs.getLongOrNull("id"),
                        taskId = rs.getString("task_id"),
                        agentId = rs.getString("agent_id"),
                        name = rs.getString("metric_name"),
                        ts = rs.getTimestamp("ts").toInstant(),
                        value = rs.getDoubleOrNull("value"),
                        tags = jsonToMap(rs.getString("tags"))
                    )
                }
                out
            }
        }
    }

    fun queryMetricsByTask(name: String, timeRange: TimeRange, taskId: String): List<Metric> = Database.withConnection { conn ->
        val sql = """
            SELECT id, task_id, agent_id, metric_name, ts, value, tags
            FROM metrics_timeseries
            WHERE metric_name = ? AND task_id = ? AND ts >= ? AND ts <= ?
            ORDER BY ts ASC
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, name)
            ps.setString(2, taskId)
            ps.setTimestamp(3, Timestamp.from(timeRange.start))
            ps.setTimestamp(4, Timestamp.from(timeRange.end))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Metric>()
                while (rs.next()) {
                    out += Metric(
                        id = rs.getLongOrNull("id"),
                        taskId = rs.getString("task_id"),
                        agentId = rs.getString("agent_id"),
                        name = rs.getString("metric_name"),
                        ts = rs.getTimestamp("ts").toInstant(),
                        value = rs.getDoubleOrNull("value"),
                        tags = jsonToMap(rs.getString("tags"))
                    )
                }
                out
            }
        }
    }

    fun queryMetricsByAgent(name: String, timeRange: TimeRange, agentId: String): List<Metric> = Database.withConnection { conn ->
        val sql = """
            SELECT id, task_id, agent_id, metric_name, ts, value, tags
            FROM metrics_timeseries
            WHERE metric_name = ? AND agent_id = ? AND ts >= ? AND ts <= ?
            ORDER BY ts ASC
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, name)
            ps.setString(2, agentId)
            ps.setTimestamp(3, Timestamp.from(timeRange.start))
            ps.setTimestamp(4, Timestamp.from(timeRange.end))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Metric>()
                while (rs.next()) {
                    out += Metric(
                        id = rs.getLongOrNull("id"),
                        taskId = rs.getString("task_id"),
                        agentId = rs.getString("agent_id"),
                        name = rs.getString("metric_name"),
                        ts = rs.getTimestamp("ts").toInstant(),
                        value = rs.getDoubleOrNull("value"),
                        tags = jsonToMap(rs.getString("tags"))
                    )
                }
                out
            }
        }
    }

    /**
     * Aggregate metric values over a time range.
     * For COUNT, NULL values are not counted by DuckDB count(value), which is desired.
     */
    fun aggregateMetrics(name: String, timeRange: TimeRange, aggregation: Aggregation): Double? = Database.withConnection { conn ->
        val aggSql = when (aggregation) {
            Aggregation.COUNT -> "count(value)"
            Aggregation.SUM -> "sum(value)"
            Aggregation.AVG -> "avg(value)"
            Aggregation.MIN -> "min(value)"
            Aggregation.MAX -> "max(value)"
            Aggregation.P50 -> "quantile(value, 0.50)"
            Aggregation.P90 -> "quantile(value, 0.90)"
            Aggregation.P99 -> "quantile(value, 0.99)"
        }
        val sql = """
            SELECT $aggSql AS agg
            FROM metrics_timeseries
            WHERE metric_name = ? AND ts >= ? AND ts <= ?
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, name)
            ps.setTimestamp(2, Timestamp.from(timeRange.start))
            ps.setTimestamp(3, Timestamp.from(timeRange.end))
            ps.executeQuery().use { rs ->
                return@withConnection if (rs.next()) rs.getDoubleOrNull("agg") else null
            }
        }
    }

    /**
     * Aggregate by fixed time buckets using DuckDB date_trunc (minute/hour/day).
     * Returns pairs of bucketStart -> value.
     */
    fun aggregateByInterval(
        name: String,
        timeRange: TimeRange,
        aggregation: Aggregation,
        interval: Interval
    ): List<Pair<Instant, Double?>> = Database.withConnection { conn ->
        val aggSql = when (aggregation) {
            Aggregation.COUNT -> "count(value)"
            Aggregation.SUM -> "sum(value)"
            Aggregation.AVG -> "avg(value)"
            Aggregation.MIN -> "min(value)"
            Aggregation.MAX -> "max(value)"
            Aggregation.P50 -> "quantile(value, 0.50)"
            Aggregation.P90 -> "quantile(value, 0.90)"
            Aggregation.P99 -> "quantile(value, 0.99)"
        }
        val truncUnit = when (interval) {
            Interval.MINUTE -> "minute"
            Interval.HOUR -> "hour"
            Interval.DAY -> "day"
        }
        val sql = """
            SELECT date_trunc('$truncUnit', ts) AS bucket, $aggSql AS agg
            FROM metrics_timeseries
            WHERE metric_name = ? AND ts >= ? AND ts <= ?
            GROUP BY 1
            ORDER BY 1 ASC
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, name)
            ps.setTimestamp(2, Timestamp.from(timeRange.start))
            ps.setTimestamp(3, Timestamp.from(timeRange.end))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Pair<Instant, Double?>>()
                while (rs.next()) {
                    val bucket = rs.getTimestamp("bucket").toInstant()
                    val v = rs.getDoubleOrNull("agg")
                    out += bucket to v
                }
                out
            }
        }
    }

    enum class Interval { MINUTE, HOUR, DAY }
    // endregion

    // region Small helpers
    private fun java.sql.ResultSet.getLongOrNull(column: String): Long? {
        val v = this.getLong(column)
        return if (this.wasNull()) null else v
        }

    private fun java.sql.ResultSet.getDoubleOrNull(column: String): Double? {
        val v = this.getDouble(column)
        return if (this.wasNull()) null else v
    }

    private fun mapToJson(map: Map<String, String>): String? {
        if (map.isEmpty()) return null
        val escaped = map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return escaped
    }

    private fun jsonToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val result = java.util.LinkedHashMap<String, String>()
        var i = 0
        fun skipWs() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun parseString(): String? {
            skipWs()
            if (i >= json.length || json[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i++]
                when (c) {
                    '\\' -> if (i < json.length) {
                        val esc = json[i++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> if (i + 4 <= json.length) {
                                val hex = json.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    '"' -> return sb.toString()
                    else -> sb.append(c)
                }
            }
            return null
        }
        fun expect(ch: Char): Boolean { skipWs(); return if (i < json.length && json[i] == ch) { i++; true } else false }
        skipWs(); if (!expect('{')) return emptyMap()
        skipWs()
        if (i < json.length && json[i] == '}') { i++; return result }
        while (i < json.length) {
            val key = parseString() ?: break
            skipWs(); if (!expect(':')) break
            val value = parseString() ?: break
            result[key] = value
            skipWs()
            if (i < json.length && json[i] == ',') { i++; continue }
            if (i < json.length && json[i] == '}') { i++; break }
            break
        }
        return result
    }

    private fun escapeJson(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> append(c)
        }
    }

    private fun generateId(): Long {
        val base = System.currentTimeMillis() * 1000
        val rand = (Math.random() * 1000).toLong()
        return base + rand
    }
    // endregion
}
