package com.orchestrator.modules.metrics

import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.DecisionRepository
import com.orchestrator.storage.repositories.MetricsRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Decision outcome record for tracking accuracy.
 */
data class DecisionOutcome(
    val decisionId: DecisionId,
    val taskId: TaskId,
    val strategy: RoutingStrategy,
    val confidence: Double,
    val success: Boolean,
    val timestamp: Instant = Instant.now()
)

/**
 * Strategy performance metrics.
 */
data class StrategyMetrics(
    val strategy: RoutingStrategy,
    val totalDecisions: Int,
    val successCount: Int,
    val failureCount: Int,
    val successRate: Double,
    val avgConfidence: Double
)

/**
 * Confidence calibration data.
 */
data class ConfidenceCalibration(
    val confidenceRange: String,
    val predictedSuccess: Double,
    val actualSuccess: Double,
    val calibrationError: Double,
    val sampleSize: Int
)

/**
 * Decision analytics report.
 */
data class DecisionReport(
    val routingAccuracy: Double,
    val strategyMetrics: List<StrategyMetrics>,
    val confidenceCalibration: List<ConfidenceCalibration>,
    val optimalStrategies: Map<TaskType, RoutingStrategy>,
    val patterns: List<String>,
    val period: Pair<Instant, Instant>
)

/**
 * Minimal decision analytics.
 * 
 * Features:
 * - Track routing accuracy
 * - Measure confidence vs outcome
 * - Identify optimal strategies
 * - Learn from patterns
 * - Generate reports
 */
object DecisionAnalytics {
    
    private val outcomes = ConcurrentHashMap<DecisionId, DecisionOutcome>()
    private val strategyStats = ConcurrentHashMap<RoutingStrategy, StrategyStats>()
    private val taskTypeStats = ConcurrentHashMap<TaskType, TaskTypeStats>()
    
    private data class StrategyStats(
        val successes: AtomicLong = AtomicLong(0),
        val failures: AtomicLong = AtomicLong(0),
        val confidences: MutableList<Double> = mutableListOf()
    )
    
    private data class TaskTypeStats(
        val strategySuccesses: ConcurrentHashMap<RoutingStrategy, AtomicLong> = ConcurrentHashMap()
    )
    
    /**
     * Record decision outcome.
     */
    fun recordOutcome(outcome: DecisionOutcome) {
        outcomes[outcome.decisionId] = outcome
        
        // Update strategy stats
        strategyStats.computeIfAbsent(outcome.strategy) { StrategyStats() }.apply {
            if (outcome.success) successes.incrementAndGet() else failures.incrementAndGet()
            synchronized(confidences) { confidences.add(outcome.confidence) }
        }
        
        // Update task type stats
        val taskType = getTaskType(outcome.taskId)
        if (taskType != null && outcome.success) {
            taskTypeStats.computeIfAbsent(taskType) { TaskTypeStats() }
                .strategySuccesses
                .computeIfAbsent(outcome.strategy) { AtomicLong(0) }
                .incrementAndGet()
        }
        
        // Persist to database (best effort)
        runCatching {
            MetricsRepository.recordMetric(
                name = "decision_outcome",
                value = if (outcome.success) 1.0 else 0.0,
                tags = mapOf(
                    "strategy" to outcome.strategy.name,
                    "confidence" to outcome.confidence.toString()
                ),
                taskId = outcome.taskId.value,
                ts = outcome.timestamp
            )
        }
    }
    
    /**
     * Record decision outcome from Decision and Task.
     */
    fun recordDecision(decision: Decision, task: Task, success: Boolean) {
        val avgConfidence = if (decision.considered.isNotEmpty()) {
            decision.considered.map { it.confidence }.average()
        } else 0.5
        
        recordOutcome(DecisionOutcome(
            decisionId = decision.id,
            taskId = decision.taskId,
            strategy = task.routing,
            confidence = avgConfidence,
            success = success,
            timestamp = decision.decidedAt
        ))
    }
    
    /**
     * Calculate routing accuracy (overall success rate).
     */
    fun getRoutingAccuracy(): Double {
        val total = outcomes.size
        if (total == 0) return 0.0
        val successes = outcomes.values.count { it.success }
        return successes.toDouble() / total
    }
    
    /**
     * Get strategy-specific metrics.
     */
    fun getStrategyMetrics(strategy: RoutingStrategy): StrategyMetrics? {
        val stats = strategyStats[strategy] ?: return null
        val successes = stats.successes.get()
        val failures = stats.failures.get()
        val total = successes + failures
        
        if (total == 0L) return null
        
        val avgConf = synchronized(stats.confidences) {
            if (stats.confidences.isEmpty()) 0.0 else stats.confidences.average()
        }
        
        return StrategyMetrics(
            strategy = strategy,
            totalDecisions = total.toInt(),
            successCount = successes.toInt(),
            failureCount = failures.toInt(),
            successRate = successes.toDouble() / total,
            avgConfidence = avgConf
        )
    }
    
    /**
     * Get all strategy metrics.
     */
    fun getAllStrategyMetrics(): List<StrategyMetrics> {
        return RoutingStrategy.values().mapNotNull { getStrategyMetrics(it) }
    }
    
    /**
     * Analyze confidence calibration (predicted vs actual success).
     */
    fun analyzeConfidenceCalibration(): List<ConfidenceCalibration> {
        val ranges = listOf(
            0.0 to 0.2,
            0.2 to 0.4,
            0.4 to 0.6,
            0.6 to 0.8,
            0.8 to 1.0
        )
        
        return ranges.mapNotNull { (low, high) ->
            val inRange = outcomes.values.filter { it.confidence >= low && it.confidence < high }
            if (inRange.isEmpty()) return@mapNotNull null
            
            val predicted = (low + high) / 2
            val actual = inRange.count { it.success }.toDouble() / inRange.size
            
            ConfidenceCalibration(
                confidenceRange = "${(low * 100).toInt()}-${(high * 100).toInt()}%",
                predictedSuccess = predicted,
                actualSuccess = actual,
                calibrationError = kotlin.math.abs(predicted - actual),
                sampleSize = inRange.size
            )
        }
    }
    
    /**
     * Identify optimal strategy for each task type.
     */
    fun identifyOptimalStrategies(): Map<TaskType, RoutingStrategy> {
        val optimal = mutableMapOf<TaskType, RoutingStrategy>()
        
        taskTypeStats.forEach { (taskType, stats) ->
            val best = stats.strategySuccesses.maxByOrNull { it.value.get() }
            if (best != null) {
                optimal[taskType] = best.key
            }
        }
        
        return optimal
    }
    
    /**
     * Identify patterns from decision data.
     */
    fun identifyPatterns(): List<String> {
        val patterns = mutableListOf<String>()
        
        // Pattern: High confidence correlates with success
        val highConfOutcomes = outcomes.values.filter { it.confidence >= 0.8 }
        if (highConfOutcomes.size >= 5) {
            val successRate = highConfOutcomes.count { it.success }.toDouble() / highConfOutcomes.size
            if (successRate >= 0.9) {
                patterns.add("High confidence (≥80%) strongly predicts success (${(successRate * 100).toInt()}% success rate)")
            }
        }
        
        // Pattern: Strategy effectiveness
        getAllStrategyMetrics().forEach { metrics ->
            if (metrics.totalDecisions >= 5 && metrics.successRate >= 0.85) {
                patterns.add("${metrics.strategy} strategy highly effective (${(metrics.successRate * 100).toInt()}% success rate over ${metrics.totalDecisions} decisions)")
            }
        }
        
        // Pattern: Confidence calibration issues
        analyzeConfidenceCalibration().forEach { cal ->
            if (cal.sampleSize >= 5 && cal.calibrationError > 0.2) {
                patterns.add("Confidence ${cal.confidenceRange} poorly calibrated (predicted ${(cal.predictedSuccess * 100).toInt()}%, actual ${(cal.actualSuccess * 100).toInt()}%)")
            }
        }
        
        // Pattern: Optimal strategies per task type
        identifyOptimalStrategies().forEach { (taskType, strategy) ->
            patterns.add("$taskType tasks perform best with $strategy strategy")
        }
        
        return patterns
    }
    
    /**
     * Generate comprehensive decision analytics report.
     */
    fun generateReport(from: Instant = Instant.EPOCH, to: Instant = Instant.now()): DecisionReport {
        return DecisionReport(
            routingAccuracy = getRoutingAccuracy(),
            strategyMetrics = getAllStrategyMetrics(),
            confidenceCalibration = analyzeConfidenceCalibration(),
            optimalStrategies = identifyOptimalStrategies(),
            patterns = identifyPatterns(),
            period = from to to
        )
    }
    
    /**
     * Get recommended strategy for task type based on historical data.
     */
    fun recommendStrategy(taskType: TaskType): RoutingStrategy? {
        return identifyOptimalStrategies()[taskType]
    }
    
    /**
     * Reset all analytics (for testing).
     */
    fun reset() {
        outcomes.clear()
        strategyStats.clear()
        taskTypeStats.clear()
    }
    
    private fun getTaskType(taskId: TaskId): TaskType? {
        // In real implementation, would query TaskRepository
        // For now, return null to avoid circular dependency
        return null
    }
}
