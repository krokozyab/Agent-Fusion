package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.providers.ContextProviderRegistry
import com.orchestrator.modules.context.ContextMetricsCollector
import com.orchestrator.modules.context.ContextMetricsCollector.UsageStats
import com.orchestrator.context.storage.ContextDatabase
import java.sql.Timestamp
import java.time.Instant

class GetContextStatsTool(
    private val config: ContextConfig,
    private val metricsCollector: ContextMetricsCollector
) {

    data class Params(val recentLimit: Int = 10)

    data class ProviderStatus(
        val id: String,
        val enabled: Boolean,
        val weight: Double,
        val type: String?
    )

    data class StorageStats(
        val files: Long,
        val chunks: Long,
        val embeddings: Long,
        val totalSizeBytes: Long
    )

    data class LanguageStat(
        val language: String,
        val fileCount: Long
    )

    data class ActivityEntry(
        val taskId: String?,
        val snippets: Int,
        val tokens: Int,
        val latencyMs: Int,
        val recordedAt: Instant
    )

    data class PerformanceStats(
        val totalRecords: Int,
        val totalContextTokens: Long,
        val averageLatencyMs: Double
    )

    data class Result(
        val providerStatus: List<ProviderStatus>,
        val storage: StorageStats,
        val languageDistribution: List<LanguageStat>,
        val recentActivity: List<ActivityEntry>,
        val performance: PerformanceStats?
    )

    fun execute(params: Params = Params()): Result {
        val providerStatus = config.providers.entries.map { (id, cfg) ->
            val provider = ContextProviderRegistry.getProvider(id)
            ProviderStatus(
                id = id,
                enabled = cfg.enabled,
                weight = cfg.weight,
                type = provider?.type?.name
            )
        }

        val storage = ContextDatabase.withConnection { conn ->
            val files = conn.prepareStatement("SELECT COUNT(*) FROM file_state WHERE is_deleted = FALSE").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            val chunks = conn.prepareStatement("SELECT COUNT(*) FROM chunks").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            val embeddings = conn.prepareStatement("SELECT COUNT(*) FROM embeddings").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            val totalSize = conn.prepareStatement("SELECT COALESCE(SUM(size_bytes), 0) FROM file_state WHERE is_deleted = FALSE").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            StorageStats(files = files, chunks = chunks, embeddings = embeddings, totalSizeBytes = totalSize)
        }

        val languageDistribution = ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT COALESCE(language, 'unknown') AS lang, COUNT(*) AS count
                FROM file_state
                WHERE is_deleted = FALSE
                GROUP BY lang
                ORDER BY count DESC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val stats = mutableListOf<LanguageStat>()
                    while (rs.next()) {
                        stats += LanguageStat(
                            language = rs.getString("lang"),
                            fileCount = rs.getLong("count")
                        )
                    }
                    stats
                }
            }
        }

        val recentActivity = ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT task_id, snippets_returned, total_tokens, retrieval_latency_ms, created_at
                FROM usage_metrics
                ORDER BY created_at DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, params.recentLimit.coerceAtLeast(1))
                ps.executeQuery().use { rs ->
                    val entries = mutableListOf<ActivityEntry>()
                    while (rs.next()) {
                        entries += ActivityEntry(
                            taskId = rs.getString("task_id"),
                            snippets = rs.getInt("snippets_returned"),
                            tokens = rs.getInt("total_tokens"),
                            latencyMs = rs.getInt("retrieval_latency_ms"),
                            recordedAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH
                        )
                    }
                    entries
                }
            }
        }

        val usageStats: UsageStats = metricsCollector.getStats()
        val performance = PerformanceStats(
            totalRecords = usageStats.totalRecords,
            totalContextTokens = usageStats.totalContextTokens,
            averageLatencyMs = usageStats.averageLatencyMs
        )

        return Result(
            providerStatus = providerStatus,
            storage = storage,
            languageDistribution = languageDistribution,
            recentActivity = recentActivity,
            performance = performance
        )
    }
}
