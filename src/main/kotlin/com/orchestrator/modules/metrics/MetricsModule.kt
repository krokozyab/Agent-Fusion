package com.orchestrator.modules.metrics

import com.orchestrator.core.EventBus
import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.MetricsRepository
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aggregated metrics snapshot.
 */
data class MetricsSnapshot(
    val timestamp: Instant,
    val tokenUsage: TokenReport,
    val performance: PerformanceDashboard,
    val decisions: DecisionReport,
    val alerts: AlertSummary
)

/**
 * Alert summary data.
 */
data class AlertSummary(
    val total: Int,
    val unacknowledged: Int,
    val bySeverity: Map<AlertSeverity, Int>,
    val byType: Map<AlertType, Int>
)

/**
 * Metrics module configuration.
 */
data class MetricsConfig(
    val aggregationInterval: Duration = Duration.ofMinutes(5),
    val bufferSize: Int = 1000,
    val autoCleanup: Boolean = true,
    val cleanupInterval: Duration = Duration.ofHours(24),
    val retentionDays: Int = 7
)

/**
 * Minimal metrics module coordinating all metrics components.
 * 
 * Features:
 * - Coordinate all metrics components
 * - Unified metrics access
 * - Periodic aggregation
 * - Buffer management
 */
class MetricsModule(
    private val config: MetricsConfig = MetricsConfig(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    
    private val running = AtomicBoolean(false)
    private var aggregationJob: Job? = null
    private var cleanupJob: Job? = null
    
    /**
     * Start metrics module with periodic aggregation and cleanup.
     */
    fun start() {
        if (running.getAndSet(true)) return
        
        // Start periodic aggregation
        aggregationJob = scope.launch {
            while (isActive) {
                delay(config.aggregationInterval.toMillis())
                runCatching { aggregate() }
            }
        }
        
        // Start periodic cleanup if enabled
        if (config.autoCleanup) {
            cleanupJob = scope.launch {
                while (isActive) {
                    delay(config.cleanupInterval.toMillis())
                    runCatching { cleanup() }
                }
            }
        }
    }
    
    /**
     * Stop metrics module.
     */
    fun stop() {
        if (!running.getAndSet(false)) return
        
        aggregationJob?.cancel()
        cleanupJob?.cancel()
        aggregationJob = null
        cleanupJob = null
    }
    
    /**
     * Get current metrics snapshot.
     */
    fun getSnapshot(): MetricsSnapshot {
        return MetricsSnapshot(
            timestamp = Instant.now(),
            tokenUsage = TokenTracker.generateReport(),
            performance = PerformanceMonitor.generateDashboard(),
            decisions = DecisionAnalytics.generateReport(),
            alerts = getAlertSummary()
        )
    }
    
    /**
     * Get metrics snapshot for time range.
     */
    fun getSnapshot(from: Instant, to: Instant): MetricsSnapshot {
        return MetricsSnapshot(
            timestamp = Instant.now(),
            tokenUsage = TokenTracker.generateReport(from, to),
            performance = PerformanceMonitor.generateDashboard(from, to),
            decisions = DecisionAnalytics.generateReport(from, to),
            alerts = getAlertSummary(from, to)
        )
    }
    
    /**
     * Track task execution with all metrics.
     */
    fun trackTaskExecution(
        task: Task,
        agent: Agent,
        proposal: Proposal,
        decision: Decision,
        duration: Duration,
        success: Boolean
    ) {
        // Track tokens
        TokenTracker.trackProposal(proposal)
        
        // Track performance
        PerformanceMonitor.trackTaskCompletion(task.id, duration, success)
        
        // Track decision
        DecisionAnalytics.recordDecision(decision, task, success)

        // Record directive confidence metrics if available
        recordDirectiveMetrics(task)

        // Check alerts
        checkAlerts(task, agent)
    }
    
    /**
     * Track agent response with all metrics.
     */
    fun trackAgentResponse(
        taskId: TaskId,
        agentId: AgentId,
        duration: Duration,
        success: Boolean
    ) {
        PerformanceMonitor.trackAgentResponse(taskId, agentId, duration, success)
        checkAgentAlerts(agentId)
    }
    
    /**
     * Get unified metrics summary.
     */
    fun getSummary(): String {
        val snapshot = getSnapshot()
        
        return buildString {
            appendLine("=== Metrics Summary ===")
            appendLine()
            appendLine("Tokens:")
            appendLine("  Total: ${snapshot.tokenUsage.totalTokens}")
            appendLine("  Savings: ${snapshot.tokenUsage.savings}")
            appendLine()
            appendLine("Performance:")
            appendLine("  Avg task time: ${snapshot.performance.avgTaskCompletionTime.toMillis()}ms")
            appendLine("  Success rate: ${(snapshot.performance.overallSuccessRate * 100).toInt()}%")
            appendLine("  Bottlenecks: ${snapshot.performance.bottlenecks.size}")
            appendLine()
            appendLine("Decisions:")
            appendLine("  Routing accuracy: ${(snapshot.decisions.routingAccuracy * 100).toInt()}%")
            appendLine("  Patterns: ${snapshot.decisions.patterns.size}")
            appendLine()
            appendLine("Alerts:")
            appendLine("  Total: ${snapshot.alerts.total}")
            appendLine("  Unacknowledged: ${snapshot.alerts.unacknowledged}")
        }
    }
    
    /**
     * Reset all metrics (for testing).
     */
    fun reset() {
        TokenTracker.reset()
        PerformanceMonitor.reset()
        DecisionAnalytics.reset()
        AlertSystem.reset()
    }
    
    /**
     * Configure alert system.
     */
    fun configureAlerts(
        tokenBudget: Double = 50000.0,
        failureRateThreshold: Double = 0.3,
        performanceThreshold: Double = 10000.0
    ) {
        AlertSystem.setThreshold(AlertType.TOKEN_BUDGET_EXCEEDED, AlertSeverity.WARNING, tokenBudget)
        AlertSystem.setThreshold(AlertType.HIGH_FAILURE_RATE, AlertSeverity.ERROR, failureRateThreshold)
        AlertSystem.setThreshold(AlertType.PERFORMANCE_DEGRADATION, AlertSeverity.WARNING, performanceThreshold)
    }
    
    /**
     * Set event bus for alert delivery.
     */
    fun setEventBus(eventBus: EventBus) {
        AlertSystem.setEventBus(eventBus)
    }
    
    private fun aggregate() {
        // Periodic aggregation can trigger reports or persist snapshots
        val snapshot = getSnapshot()

        // Could persist snapshot to database here
        // Could publish aggregation event here
    }

    private fun cleanup() {
        val cutoff = Instant.now().minus(Duration.ofDays(config.retentionDays.toLong()))

        // Cleanup old acknowledged alerts
        AlertSystem.clearOldAlerts(cutoff)

        // Could cleanup old metrics here if needed
    }

    private fun recordDirectiveMetrics(task: Task) {
        if (task.metadata.isEmpty()) return
        val meta = task.metadata
        val now = Instant.now()
        val baseTags = mutableMapOf<String, String>()
        meta["directive.forceConsensus"]?.let { baseTags["forceConsensus"] = it }
        meta["directive.preventConsensus"]?.let { baseTags["preventConsensus"] = it }
        meta["directive.assignToAgent"]?.let { baseTags["assignToAgent"] = it }
        meta["directive.assignedAgents"]?.let { baseTags["assignedAgents"] = it }
        meta["directive.isEmergency"]?.let { baseTags["isEmergency"] = it }

        fun recordIfPresent(key: String, metricName: String, extraTag: String) {
            meta[key]?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { value ->
                val tags = baseTags.toMutableMap()
                tags["type"] = extraTag
                MetricsRepository.recordMetric(
                    name = metricName,
                    value = value,
                    tags = tags,
                    taskId = task.id.value,
                    ts = now
                )
            }
        }

        recordIfPresent("directive.forceConsensusConfidence", "directive_confidence", "force")
        recordIfPresent("directive.preventConsensusConfidence", "directive_confidence", "prevent")
        recordIfPresent("directive.assignmentConfidence", "directive_confidence", "assignment")
        recordIfPresent("directive.emergencyConfidence", "directive_confidence", "emergency")
    }

    private fun checkAlerts(task: Task, agent: Agent) {
        // Check token budget
        val totalTokens = TokenTracker.getTotalTokens()
        AlertSystem.check(
            type = AlertType.TOKEN_BUDGET_EXCEEDED,
            value = totalTokens.toDouble(),
            message = "Token usage: $totalTokens tokens",
            taskId = task.id
        )
        
        // Check agent performance
        checkAgentAlerts(agent.id)
    }
    
    private fun checkAgentAlerts(agentId: AgentId) {
        // Check failure rate
        val successRate = PerformanceMonitor.getAgentSuccessRate(agentId)
        val failureRate = 1.0 - successRate
        AlertSystem.check(
            type = AlertType.HIGH_FAILURE_RATE,
            value = failureRate,
            message = "Agent $agentId failure rate: ${(failureRate * 100).toInt()}%",
            agentId = agentId
        )

        // Check response time
        val avgResponseTime = PerformanceMonitor.getAvgAgentResponseTime()
        AlertSystem.check(
            type = AlertType.PERFORMANCE_DEGRADATION,
            value = avgResponseTime.toMillis().toDouble(),
            message = "Average response time: ${avgResponseTime.toMillis()}ms",
            agentId = agentId
        )
    }
    
    private fun getAlertSummary(from: Instant = Instant.EPOCH, to: Instant = Instant.now()): AlertSummary {
        val alerts = AlertSystem.getAlertsInRange(from, to)
        
        return AlertSummary(
            total = alerts.size,
            unacknowledged = alerts.count { !it.acknowledged },
            bySeverity = AlertSeverity.values().associateWith { severity ->
                alerts.count { it.severity == severity }
            },
            byType = AlertType.values().associateWith { type ->
                alerts.count { it.type == type }
            }
        )
    }
    
    companion object {
        /**
         * Global metrics module instance.
         */
        val global = MetricsModule()
    }
}
