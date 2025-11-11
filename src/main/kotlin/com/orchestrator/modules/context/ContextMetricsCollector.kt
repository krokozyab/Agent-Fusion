package com.orchestrator.modules.context

import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.modules.context.ContextRetrievalModule.TaskContext
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Persists lightweight telemetry for context retrieval so tools can surface aggregate statistics.
 */
class ContextMetricsCollector : ContextMetricsRecorder {

    data class UsageStats(
        val totalRecords: Int,
        val totalContextTokens: Long,
        val averageLatencyMs: Double
    )

    data class Snapshot(
        val averageSnippets: Double,
        val averageTokens: Double,
        val averageLatencyMs: Double
    )

    data class DeltaReport(
        val baseline: Snapshot,
        val current: Snapshot,
        val deltaLatencyMs: Double
    )

    override fun record(task: Task, agentId: AgentId, context: TaskContext, duration: Duration) {
        val snippetCount = context.snippets.size
        val tokensFromSnippets = context.snippets.sumOf { estimateTokens(it) }
        val totalTokens = max(context.diagnostics.tokensUsed, tokensFromSnippets).toLong()
        val latencyMs = duration.toMillis().toInt()

        ContextDatabase.transaction { conn ->
            val id = nextId(conn)
            conn.prepareStatement(
                """
                INSERT INTO usage_metrics (
                    metric_id, task_id, snippets_returned, total_tokens, retrieval_latency_ms, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, id)
                ps.setString(2, task.id.value)
                ps.setInt(3, snippetCount)
                ps.setInt(4, totalTokens.toInt())
                ps.setInt(5, latencyMs)
                ps.setTimestamp(6, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    fun getStats(): UsageStats =
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) AS records,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       COALESCE(AVG(retrieval_latency_ms), 0) AS avg_latency
                FROM usage_metrics
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    UsageStats(
                        totalRecords = rs.getInt("records"),
                        totalContextTokens = rs.getLong("total_tokens"),
                        averageLatencyMs = rs.getDouble("avg_latency")
                    )
                }
            }
        }

    fun compareBeforeAfter(): DeltaReport {
        val metrics = ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                "SELECT snippets_returned, total_tokens, retrieval_latency_ms FROM usage_metrics ORDER BY created_at"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<Triple<Int, Int, Int>>()
                    while (rs.next()) {
                        rows += Triple(
                            rs.getInt("snippets_returned"),
                            rs.getInt("total_tokens"),
                            rs.getInt("retrieval_latency_ms")
                        )
                    }
                    rows
                }
            }
        }

        if (metrics.isEmpty()) {
            val empty = Snapshot(0.0, 0.0, 0.0)
            return DeltaReport(empty, empty, 0.0)
        }

        val split = max(1, metrics.size / 2)
        val baselineRows = metrics.subList(0, split)
        val currentRows = metrics.subList(split, metrics.size)

        val baseline = summarise(baselineRows)
        val current = summarise(currentRows.ifEmpty { baselineRows })

        val delta = current.averageLatencyMs - baseline.averageLatencyMs
        return DeltaReport(baseline, current, delta)
    }

    private fun summarise(rows: List<Triple<Int, Int, Int>>): Snapshot {
        if (rows.isEmpty()) return Snapshot(0.0, 0.0, 0.0)
        val count = rows.size.toDouble()
        val avgSnippets = rows.sumOf { it.first }.toDouble() / count
        val avgTokens = rows.sumOf { it.second }.toDouble() / count
        val avgLatency = rows.sumOf { it.third }.toDouble() / count
        return Snapshot(avgSnippets, avgTokens, avgLatency)
    }

    private fun estimateTokens(snippet: ContextSnippet): Int =
        max(1, snippet.metadata["token_estimate"]?.toIntOrNull() ?: snippet.text.length / 4)

    private fun nextId(conn: java.sql.Connection): Long {
        conn.prepareStatement("SELECT nextval('usage_metrics_seq')").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }
}
