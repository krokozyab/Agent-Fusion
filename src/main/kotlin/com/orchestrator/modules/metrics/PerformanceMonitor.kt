package com.orchestrator.modules.metrics

import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.MetricsRepository
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Performance measurement record.
 */
data class PerformanceRecord(
    val taskId: TaskId,
    val agentId: AgentId?,
    val operation: String,
    val duration: Duration,
    val success: Boolean,
    val timestamp: Instant = Instant.now()
)

/**
 * Bottleneck detection result.
 */
data class Bottleneck(
    val type: String,
    val identifier: String,
    val avgDuration: Duration,
    val failureRate: Double,
    val occurrences: Int
)

/**
 * Performance dashboard data.
 */
data class PerformanceDashboard(
    val avgTaskCompletionTime: Duration,
    val avgAgentResponseTime: Duration,
    val overallSuccessRate: Double,
    val taskSuccessRate: Map<TaskId, Double>,
    val agentSuccessRate: Map<AgentId, Double>,
    val bottlenecks: List<Bottleneck>,
    val period: Pair<Instant, Instant>
)

/**
 * Minimal performance monitor.
 * 
 * Features:
 * - Track task completion times
 * - Track agent response times
 * - Calculate success rates
 * - Identify bottlenecks
 * - Generate dashboards
 */
object PerformanceMonitor {
    
    private val records = ConcurrentHashMap<String, MutableList<PerformanceRecord>>()
    private val taskMetrics = ConcurrentHashMap<TaskId, TaskMetrics>()
    private val agentMetrics = ConcurrentHashMap<AgentId, AgentMetrics>()
    
    private data class TaskMetrics(
        val durations: MutableList<Duration> = mutableListOf(),
        val successes: AtomicLong = AtomicLong(0),
        val failures: AtomicLong = AtomicLong(0)
    )
    
    private data class AgentMetrics(
        val durations: MutableList<Duration> = mutableListOf(),
        val successes: AtomicLong = AtomicLong(0),
        val failures: AtomicLong = AtomicLong(0)
    )
    
    /**
     * Record performance measurement.
     */
    fun record(record: PerformanceRecord) {
        // Store record
        records.computeIfAbsent(record.operation) { mutableListOf() }.add(record)
        
        // Update task metrics
        taskMetrics.computeIfAbsent(record.taskId) { TaskMetrics() }.apply {
            synchronized(durations) { durations.add(record.duration) }
            if (record.success) successes.incrementAndGet() else failures.incrementAndGet()
        }
        
        // Update agent metrics
        record.agentId?.let { agentId ->
            agentMetrics.computeIfAbsent(agentId) { AgentMetrics() }.apply {
                synchronized(durations) { durations.add(record.duration) }
                if (record.success) successes.incrementAndGet() else failures.incrementAndGet()
            }
        }
        
        // Persist to database (best effort)
        runCatching {
            MetricsRepository.recordMetric(
                name = "performance",
                value = record.duration.toMillis().toDouble(),
                tags = mapOf(
                    "operation" to record.operation,
                    "success" to record.success.toString()
                ),
                taskId = record.taskId.value,
                agentId = record.agentId?.value,
                ts = record.timestamp
            )
        }
    }
    
    /**
     * Track task completion time.
     */
    fun trackTaskCompletion(taskId: TaskId, duration: Duration, success: Boolean) {
        record(PerformanceRecord(
            taskId = taskId,
            agentId = null,
            operation = "task_completion",
            duration = duration,
            success = success
        ))
    }
    
    /**
     * Track agent response time.
     */
    fun trackAgentResponse(taskId: TaskId, agentId: AgentId, duration: Duration, success: Boolean) {
        record(PerformanceRecord(
            taskId = taskId,
            agentId = agentId,
            operation = "agent_response",
            duration = duration,
            success = success
        ))
    }
    
    /**
     * Calculate overall success rate.
     */
    fun getSuccessRate(): Double {
        val allSuccesses = taskMetrics.values.sumOf { it.successes.get() }
        val allFailures = taskMetrics.values.sumOf { it.failures.get() }
        val total = allSuccesses + allFailures
        return if (total > 0) allSuccesses.toDouble() / total else 0.0
    }
    
    /**
     * Calculate success rate for specific task.
     */
    fun getTaskSuccessRate(taskId: TaskId): Double {
        val metrics = taskMetrics[taskId] ?: return 0.0
        val total = metrics.successes.get() + metrics.failures.get()
        return if (total > 0) metrics.successes.get().toDouble() / total else 0.0
    }
    
    /**
     * Calculate success rate for specific agent.
     */
    fun getAgentSuccessRate(agentId: AgentId): Double {
        val metrics = agentMetrics[agentId] ?: return 0.0
        val total = metrics.successes.get() + metrics.failures.get()
        return if (total > 0) metrics.successes.get().toDouble() / total else 0.0
    }
    
    /**
     * Get average task completion time.
     */
    fun getAvgTaskCompletionTime(): Duration {
        val durations = taskMetrics.values.flatMap { synchronized(it.durations) { it.durations.toList() } }
        return if (durations.isEmpty()) Duration.ZERO else Duration.ofMillis(durations.map { it.toMillis() }.average().toLong())
    }
    
    /**
     * Get average agent response time.
     */
    fun getAvgAgentResponseTime(): Duration {
        val durations = agentMetrics.values.flatMap { synchronized(it.durations) { it.durations.toList() } }
        return if (durations.isEmpty()) Duration.ZERO else Duration.ofMillis(durations.map { it.toMillis() }.average().toLong())
    }
    
    /**
     * Identify bottlenecks (slow operations or high failure rates).
     */
    fun identifyBottlenecks(minOccurrences: Int = 3, slowThreshold: Duration = Duration.ofSeconds(5)): List<Bottleneck> {
        val bottlenecks = mutableListOf<Bottleneck>()
        
        // Check operations
        records.forEach { (operation, recordList) ->
            if (recordList.size >= minOccurrences) {
                val avgDuration = Duration.ofMillis(recordList.map { it.duration.toMillis() }.average().toLong())
                val failures = recordList.count { !it.success }
                val failureRate = failures.toDouble() / recordList.size
                
                if (avgDuration > slowThreshold || failureRate > 0.2) {
                    bottlenecks.add(Bottleneck(
                        type = "operation",
                        identifier = operation,
                        avgDuration = avgDuration,
                        failureRate = failureRate,
                        occurrences = recordList.size
                    ))
                }
            }
        }
        
        // Check agents
        agentMetrics.forEach { (agentId, metrics) ->
            val total = metrics.successes.get() + metrics.failures.get()
            if (total >= minOccurrences) {
                val durations = synchronized(metrics.durations) { metrics.durations.toList() }
                val avgDuration = Duration.ofMillis(durations.map { it.toMillis() }.average().toLong())
                val failureRate = metrics.failures.get().toDouble() / total
                
                if (avgDuration > slowThreshold || failureRate > 0.2) {
                    bottlenecks.add(Bottleneck(
                        type = "agent",
                        identifier = agentId.value,
                        avgDuration = avgDuration,
                        failureRate = failureRate,
                        occurrences = total.toInt()
                    ))
                }
            }
        }
        
        return bottlenecks.sortedByDescending { it.failureRate }
    }
    
    /**
     * Generate performance dashboard.
     */
    fun generateDashboard(from: Instant = Instant.EPOCH, to: Instant = Instant.now()): PerformanceDashboard {
        val taskSuccessRates = taskMetrics.keys.associateWith { getTaskSuccessRate(it) }
        val agentSuccessRates = agentMetrics.keys.associateWith { getAgentSuccessRate(it) }
        
        return PerformanceDashboard(
            avgTaskCompletionTime = getAvgTaskCompletionTime(),
            avgAgentResponseTime = getAvgAgentResponseTime(),
            overallSuccessRate = getSuccessRate(),
            taskSuccessRate = taskSuccessRates,
            agentSuccessRate = agentSuccessRates,
            bottlenecks = identifyBottlenecks(),
            period = from to to
        )
    }
    
    /**
     * Reset all metrics (for testing).
     */
    fun reset() {
        records.clear()
        taskMetrics.clear()
        agentMetrics.clear()
    }
}
