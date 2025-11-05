package com.orchestrator.web.components

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class IndexDashboardTest {

    @Test
    fun `renders dashboard with all sections`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 95,
            pendingFiles = 5,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.5,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(
            metrics = metrics
        )

        val html = IndexDashboard.render(config)

        // Check for main container
        assertContains(html, "id=\"index-dashboard\"")
        assertContains(html, "class=\"index-dashboard\"")
        assertContains(html, "role=\"region\"")

        // Check for summary section
        assertContains(html, "class=\"status-summary")
        assertContains(html, "Index Status")

        // Check for metrics grid
        assertContains(html, "class=\"index-metrics")
        assertContains(html, "Index Metrics")

        // Check for refresh timestamp
        assertContains(html, "class=\"refresh-info")
        assertContains(html, "data-testid=\"refresh-timestamp\"")
    }

    @Test
    fun `displays correct metric values in summary`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 1000,
            indexedFiles = 950,
            pendingFiles = 40,
            failedFiles = 10,
            totalChunks = 5000,
            totalStorageMB = 100.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        // Check metric values
        assertContains(html, "data-testid=\"metric-total-files\"")
        assertContains(html, "data-testid=\"metric-indexed-files\"")
        assertContains(html, "data-testid=\"metric-pending-files\"")
        assertContains(html, "data-testid=\"metric-failed-files\"")
    }

    @Test
    fun `shows healthy status badge when all files indexed`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        assertContains(html, "status-badge--healthy")
        assertContains(html, "✓ Healthy")
    }

    @Test
    fun `shows degraded status badge when files are pending`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 90,
            pendingFiles = 10,
            failedFiles = 0,
            totalChunks = 450,
            totalStorageMB = 45.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.DEGRADED
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        assertContains(html, "status-badge--degraded")
        assertContains(html, "⚠ Degraded")
    }

    @Test
    fun `shows critical status badge when files have failed`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 85,
            pendingFiles = 5,
            failedFiles = 10,
            totalChunks = 425,
            totalStorageMB = 42.5,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.CRITICAL
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        assertContains(html, "status-badge--critical")
        assertContains(html, "✕ Critical")
    }

    @Test
    fun `displays index metrics correctly`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 95,
            pendingFiles = 5,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.5,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        // Check for metric cards
        assertContains(html, "data-testid=\"metric-storage\"")
        assertContains(html, "Total Storage")

        assertContains(html, "data-testid=\"metric-chunks\"")
        assertContains(html, "Total Chunks")

        assertContains(html, "data-testid=\"metric-progress\"")
        assertContains(html, "Index Progress")

        assertContains(html, "data-testid=\"metric-success-rate\"")
        assertContains(html, "Success Rate")
    }

    @Test
    fun `calculates index progress percentage correctly`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 75,
            pendingFiles = 25,
            failedFiles = 0,
            totalChunks = 375,
            totalStorageMB = 37.5,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.DEGRADED
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        // Should show 75% progress
        assertContains(html, "75%")
    }

    @Test
    fun `renders provider health section when providers present`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val providers = listOf(
            IndexDashboard.ProviderHealthMetric(
                id = "semantic",
                name = "Semantic",
                status = StatusCard.Status.HEALTHY,
                weight = 1.0
            ),
            IndexDashboard.ProviderHealthMetric(
                id = "symbol",
                name = "Symbol",
                status = StatusCard.Status.HEALTHY,
                weight = 0.8
            )
        )

        val config = IndexDashboard.Config(
            metrics = metrics,
            providers = providers
        )

        val html = IndexDashboard.render(config)

        // Check provider section
        assertContains(html, "class=\"provider-health")
        assertContains(html, "Provider Health")

        // Check provider cards
        assertContains(html, "data-testid=\"provider-semantic\"")
        assertContains(html, "data-testid=\"provider-symbol\"")
        assertContains(html, "Semantic")
        assertContains(html, "Symbol")
    }

    @Test
    fun `does not render provider section when no providers`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(
            metrics = metrics,
            providers = emptyList()
        )

        val html = IndexDashboard.render(config)

        assertFalse(html.contains("Provider Health"))
    }

    @Test
    fun `displays performance metrics section`() {
        val performance = IndexDashboard.PerformanceMetrics(
            avgQueryTime = 45,
            p95QueryTime = 120,
            p99QueryTime = 250
        )

        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(
            metrics = metrics,
            performance = performance
        )

        val html = IndexDashboard.render(config)

        assertContains(html, "class=\"performance-metrics")
        assertContains(html, "Performance")
        assertContains(html, "data-testid=\"metric-avg-query-time\"")
        assertContains(html, "data-testid=\"metric-p95-query-time\"")
        assertContains(html, "data-testid=\"metric-p99-query-time\"")
    }

    @Test
    fun `displays refresh timestamp when available`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T14:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        assertContains(html, "Last updated:")
        assertContains(html, "2025-10-25 14:30:00 UTC")
    }

    @Test
    fun `shows never for last refresh when null`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = null,
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        assertContains(html, "Never")
    }

    @Test
    fun `has SSE swap attributes for live updates`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        assertContains(html, "sse-swap=\"indexDashboard\"")
        assertContains(html, "hx-swap=\"outerHTML\"")
    }

    @Test
    fun `renders custom SSE swap ID when provided`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(
            metrics = metrics,
            sseSwapId = "customIndexSwap"
        )

        val html = IndexDashboard.render(config)

        assertContains(html, "sse-swap=\"customIndexSwap\"")
    }

    @Test
    fun `handles zero files gracefully`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 0,
            indexedFiles = 0,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 0,
            totalStorageMB = 0.0,
            lastRefresh = null,
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        // Should render without errors
        assertContains(html, "index-dashboard")
        assertContains(html, "0%") // 0/0 should show 0%
    }

    @Test
    fun `formats large numbers with thousand separators`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 1000000,
            indexedFiles = 950000,
            pendingFiles = 50000,
            failedFiles = 0,
            totalChunks = 5000000,
            totalStorageMB = 500.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        // Numbers should be formatted with commas
        assertContains(html, "1,000,000")
        assertContains(html, "950,000")
    }

    @Test
    fun `includes accessibility labels`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(metrics = metrics)
        val html = IndexDashboard.render(config)

        assertContains(html, "aria-label=\"Index status dashboard\"")
        assertContains(html, "role=\"region\"")
    }

    @Test
    fun `renders provider cards with health status styling`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val providers = listOf(
            IndexDashboard.ProviderHealthMetric(
                id = "healthy-provider",
                name = "Healthy Provider",
                status = StatusCard.Status.HEALTHY,
                weight = 1.0
            ),
            IndexDashboard.ProviderHealthMetric(
                id = "degraded-provider",
                name = "Degraded Provider",
                status = StatusCard.Status.DEGRADED,
                weight = 0.5
            )
        )

        val config = IndexDashboard.Config(
            metrics = metrics,
            providers = providers
        )

        val html = IndexDashboard.render(config)

        // Check provider status styling
        assertContains(html, "provider-card--healthy")
        assertContains(html, "provider-card--degraded")
    }

    @Test
    fun `custom dashboard ID and aria-label work together`() {
        val metrics = IndexDashboard.IndexMetrics(
            totalFiles = 100,
            indexedFiles = 100,
            pendingFiles = 0,
            failedFiles = 0,
            totalChunks = 500,
            totalStorageMB = 50.0,
            lastRefresh = "2025-10-25T12:30:00Z",
            health = IndexDashboard.HealthStatus.HEALTHY
        )

        val config = IndexDashboard.Config(
            id = "custom-dashboard-id",
            metrics = metrics,
            ariaLabel = "Custom dashboard label"
        )

        val html = IndexDashboard.render(config)

        assertContains(html, "id=\"custom-dashboard-id\"")
        assertContains(html, "aria-label=\"Custom dashboard label\"")
    }
}
