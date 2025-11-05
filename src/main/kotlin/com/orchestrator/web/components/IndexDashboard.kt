package com.orchestrator.web.components

import com.orchestrator.web.dto.IndexStatusDTO
import com.orchestrator.web.rendering.Fragment
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.p
import kotlinx.html.span
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * IndexDashboard component for displaying index status and health metrics.
 *
 * Features:
 * - Real-time status cards with live updates via SSE
 * - Index size metrics (files, chunks, storage)
 * - Performance metrics (query time)
 * - Visual status indicators (green, yellow, red)
 * - Responsive grid layout
 * - Accessible ARIA labels and regions
 */
object IndexDashboard {

    enum class HealthStatus {
        HEALTHY,
        DEGRADED,
        CRITICAL
    }

    data class ProviderHealthMetric(
        val id: String,
        val name: String,
        val status: StatusCard.Status,
        val weight: Double
    )

    data class PerformanceMetrics(
        val avgQueryTime: Long = 0, // milliseconds
        val p95QueryTime: Long = 0,
        val p99QueryTime: Long = 0
    )

    data class IndexMetrics(
        val totalFiles: Int,
        val indexedFiles: Int,
        val pendingFiles: Int,
        val failedFiles: Int,
        val totalChunks: Int,
        val totalStorageMB: Double,
        val lastRefresh: String?,
        val health: HealthStatus
    )

    data class Config(
        val id: String = "index-dashboard",
        val metrics: IndexMetrics,
        val providers: List<ProviderHealthMetric> = emptyList(),
        val performance: PerformanceMetrics = PerformanceMetrics(),
        val sseSwapId: String = "indexDashboard",
        val ariaLabel: String = "Index status dashboard"
    )

    private val displayFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC)

    fun render(config: Config): String = Fragment.render {
        dashboard(config)
    }

    fun FlowContent.dashboard(config: Config) {
        div(classes = "index-dashboard") {
            attributes["id"] = config.id
            attributes["sse-swap"] = config.sseSwapId
            attributes["hx-swap"] = "outerHTML"
            attributes["role"] = "region"
            attributes["aria-label"] = config.ariaLabel

            // Summary section with key metrics
            summarySection(config.metrics)

            // Index metrics grid
            metricsGrid(config.metrics)

            // Provider health section
            if (config.providers.isNotEmpty()) {
                providerHealthSection(config.providers)
            }

            // Performance metrics section
            performanceSection(config.performance)

            // Last refresh timestamp
            refreshTimestamp(config.metrics.lastRefresh)
        }
    }

    private fun FlowContent.summarySection(metrics: IndexMetrics) {
        div(classes = "status-summary mt-lg") {
            div(classes = "flex items-center justify-between mb-md") {
                h3(classes = "mt-0 mb-0") { +"Index Status" }

                // Overall health badge
                span(classes = "status-badge status-badge--${metrics.health.name.lowercase()}") {
                    attributes["role"] = "status"
                    attributes["aria-live"] = "polite"
                    +metrics.health.toLabel()
                }
            }

            // Quick stats
            div(classes = "grid grid-cols-2 grid-cols-md-4 gap-md") {
                with(StatusCard) {
                    card(
                        StatusCard.Config(
                            id = "metric-total-files",
                            title = "Total Files",
                            value = formatNumber(metrics.totalFiles),
                            label = "Total Files",
                            status = StatusCard.Status.HEALTHY,
                            testId = "metric-total-files"
                        )
                    )
                    card(
                        StatusCard.Config(
                            id = "metric-indexed-files",
                            title = "Indexed Files",
                            value = formatNumber(metrics.indexedFiles),
                            label = "Indexed",
                            status = if (metrics.indexedFiles == metrics.totalFiles) {
                                StatusCard.Status.HEALTHY
                            } else {
                                StatusCard.Status.DEGRADED
                            },
                            testId = "metric-indexed-files"
                        )
                    )
                    card(
                        StatusCard.Config(
                            id = "metric-pending-files",
                            title = "Pending Files",
                            value = formatNumber(metrics.pendingFiles),
                            label = "Pending",
                            status = if (metrics.pendingFiles == 0) {
                                StatusCard.Status.HEALTHY
                            } else {
                                StatusCard.Status.DEGRADED
                            },
                            testId = "metric-pending-files"
                        )
                    )
                    card(
                        StatusCard.Config(
                            id = "metric-failed-files",
                            title = "Failed Files",
                            value = formatNumber(metrics.failedFiles),
                            label = "Failed",
                            status = if (metrics.failedFiles == 0) {
                                StatusCard.Status.HEALTHY
                            } else {
                                StatusCard.Status.CRITICAL
                            },
                            testId = "metric-failed-files"
                        )
                    )
                }
            }
        }
    }

    private fun FlowContent.metricsGrid(metrics: IndexMetrics) {
        div(classes = "index-metrics mt-lg") {
            h3(classes = "mt-0 mb-md") { +"Index Metrics" }

            div(classes = "grid grid-cols-1 grid-cols-md-2 gap-md") {
                // Storage metric
                div(classes = "metric-card") {
                    attributes["data-testid"] = "metric-storage"
                    span(classes = "metric-label") { +"Total Storage" }
                    span(classes = "metric-value") { +formatSize(metrics.totalStorageMB) }
                }

                // Chunks metric
                div(classes = "metric-card") {
                    attributes["data-testid"] = "metric-chunks"
                    span(classes = "metric-label") { +"Total Chunks" }
                    span(classes = "metric-value") { +formatNumber(metrics.totalChunks) }
                }

                // Index progress
                div(classes = "metric-card") {
                    attributes["data-testid"] = "metric-progress"
                    span(classes = "metric-label") { +"Index Progress" }
                    val progress = if (metrics.totalFiles > 0) {
                        (metrics.indexedFiles * 100) / metrics.totalFiles
                    } else {
                        0
                    }
                    span(classes = "metric-value") { +"$progress%" }
                }

                // Success rate
                div(classes = "metric-card") {
                    attributes["data-testid"] = "metric-success-rate"
                    span(classes = "metric-label") { +"Success Rate" }
                    val successRate = if (metrics.totalFiles > 0) {
                        (metrics.indexedFiles * 100) / metrics.totalFiles
                    } else {
                        0
                    }
                    span(classes = "metric-value") { +"$successRate%" }
                }
            }
        }
    }

    private fun FlowContent.providerHealthSection(providers: List<ProviderHealthMetric>) {
        div(classes = "provider-health mt-lg") {
            h3(classes = "mt-0 mb-md") { +"Provider Health" }

            div(classes = "grid grid-cols-1 grid-cols-md-2 grid-cols-lg-3 gap-md") {
                providers.forEach { provider ->
                    div(classes = "provider-card provider-card--${provider.status.name.lowercase()}") {
                        attributes["data-testid"] = "provider-${provider.id}"
                        attributes["role"] = "status"
                        attributes["aria-label"] = "${provider.name} status: ${provider.status.name.lowercase()}"

                        div(classes = "provider-header") {
                            span(classes = "provider-name") { +provider.name }
                            span(classes = "provider-status") {
                                attributes["class"] += " status-badge--${provider.status.name.lowercase()}"
                                +provider.status.toLabel()
                            }
                        }

                        div(classes = "provider-weight") {
                            span(classes = "provider-label") { +"Weight" }
                            span(classes = "provider-value") {
                                +"%.2f".format(Locale.US, provider.weight)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.performanceSection(performance: PerformanceMetrics) {
        div(classes = "performance-metrics mt-lg") {
            h3(classes = "mt-0 mb-md") { +"Performance" }

            div(classes = "grid grid-cols-1 grid-cols-md-3 gap-md") {
                div(classes = "metric-card") {
                    attributes["data-testid"] = "metric-avg-query-time"
                    span(classes = "metric-label") { +"Avg Query Time" }
                    span(classes = "metric-value") { +"${performance.avgQueryTime}ms" }
                }

                div(classes = "metric-card") {
                    attributes["data-testid"] = "metric-p95-query-time"
                    span(classes = "metric-label") { +"P95 Query Time" }
                    span(classes = "metric-value") { +"${performance.p95QueryTime}ms" }
                }

                div(classes = "metric-card") {
                    attributes["data-testid"] = "metric-p99-query-time"
                    span(classes = "metric-label") { +"P99 Query Time" }
                    span(classes = "metric-value") { +"${performance.p99QueryTime}ms" }
                }
            }
        }
    }

    private fun FlowContent.refreshTimestamp(lastRefresh: String?) {
        div(classes = "refresh-info mt-lg text-muted") {
            attributes["data-testid"] = "refresh-timestamp"
            p(classes = "mb-0") {
                +"Last updated: "
                if (lastRefresh != null) {
                    +formatTimestamp(lastRefresh)
                } else {
                    +"Never"
                }
            }
        }
    }

    private fun HealthStatus.toLabel(): String = when (this) {
        HealthStatus.HEALTHY -> "✓ Healthy"
        HealthStatus.DEGRADED -> "⚠ Degraded"
        HealthStatus.CRITICAL -> "✕ Critical"
    }

    private fun StatusCard.Status.toLabel(): String = when (this) {
        StatusCard.Status.HEALTHY -> "Healthy"
        StatusCard.Status.DEGRADED -> "Degraded"
        StatusCard.Status.CRITICAL -> "Critical"
    }

    private fun formatNumber(number: Int): String = "%,d".format(Locale.US, number)

    private fun formatSize(sizeBytes: Double): String {
        if (sizeBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(sizeBytes) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = sizeBytes / 1024.0.pow(digitGroups.toDouble())
        return "%.1f %s".format(Locale.US, value, units[digitGroups])
    }

    private fun formatSize(sizeBytes: Long): String = formatSize(sizeBytes.toDouble())

    private fun formatTimestamp(value: String?): String {
        if (value.isNullOrBlank()) return "Never"
        return runCatching { displayFormatter.format(Instant.parse(value)) }.getOrElse { value }
    }
}
