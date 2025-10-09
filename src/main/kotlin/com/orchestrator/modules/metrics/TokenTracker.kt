package com.orchestrator.modules.metrics

import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.MetricsRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Token usage record for tracking.
 */
data class TokenRecord(
    val taskId: TaskId,
    val agentId: AgentId,
    val inputTokens: Int,
    val outputTokens: Int,
    val timestamp: Instant = Instant.now()
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

/**
 * Budget alert configuration.
 */
data class BudgetAlert(
    val threshold: Int,
    val handler: (usage: Int, threshold: Int) -> Unit
)

/**
 * Token usage report.
 */
data class TokenReport(
    val totalTokens: Int,
    val byTask: Map<TaskId, Int>,
    val byAgent: Map<AgentId, Int>,
    val savings: Int,
    val period: Pair<Instant, Instant>
)

/**
 * Minimal token usage tracker.
 * 
 * Features:
 * - Track tokens per task and agent
 * - Calculate savings from consensus
 * - Alert on budget limits
 * - Generate reports
 */
object TokenTracker {
    
    private val taskTokens = ConcurrentHashMap<TaskId, AtomicLong>()
    private val agentTokens = ConcurrentHashMap<AgentId, AtomicLong>()
    private val totalTokens = AtomicLong(0)
    private val savedTokens = AtomicLong(0)
    private val alerts = mutableListOf<BudgetAlert>()
    
    /**
     * Track token usage for a task/agent.
     */
    fun track(record: TokenRecord) {
        val tokens = record.totalTokens.toLong()
        
        // Update counters
        taskTokens.computeIfAbsent(record.taskId) { AtomicLong(0) }.addAndGet(tokens)
        agentTokens.computeIfAbsent(record.agentId) { AtomicLong(0) }.addAndGet(tokens)
        val newTotal = totalTokens.addAndGet(tokens)
        
        // Persist to database (best effort)
        runCatching {
            MetricsRepository.recordMetric(
                name = "tokens",
                value = tokens.toDouble(),
                tags = mapOf(
                    "type" to "usage",
                    "input" to record.inputTokens.toString(),
                    "output" to record.outputTokens.toString()
                ),
                taskId = record.taskId.value,
                agentId = record.agentId.value,
                ts = record.timestamp
            )
        }
        
        // Check alerts
        checkAlerts(newTotal.toInt())
    }
    
    /**
     * Track token usage from proposal.
     */
    fun trackProposal(proposal: Proposal) {
        track(TokenRecord(
            taskId = proposal.taskId,
            agentId = proposal.agentId,
            inputTokens = proposal.tokenUsage.inputTokens,
            outputTokens = proposal.tokenUsage.outputTokens,
            timestamp = proposal.createdAt
        ))
    }
    
    /**
     * Record token savings from consensus (avoided processing).
     */
    fun recordSavings(tokens: Int) {
        savedTokens.addAndGet(tokens.toLong())
    }
    
    /**
     * Get total tokens used.
     */
    fun getTotalTokens(): Int = totalTokens.get().toInt()
    
    /**
     * Get tokens used by task.
     */
    fun getTaskTokens(taskId: TaskId): Int = taskTokens[taskId]?.get()?.toInt() ?: 0
    
    /**
     * Get tokens used by agent.
     */
    fun getAgentTokens(agentId: AgentId): Int = agentTokens[agentId]?.get()?.toInt() ?: 0
    
    /**
     * Get total saved tokens.
     */
    fun getSavedTokens(): Int = savedTokens.get().toInt()
    
    /**
     * Register budget alert.
     */
    fun addAlert(threshold: Int, handler: (usage: Int, threshold: Int) -> Unit) {
        synchronized(alerts) {
            alerts.add(BudgetAlert(threshold, handler))
        }
    }
    
    /**
     * Clear all alerts.
     */
    fun clearAlerts() {
        synchronized(alerts) {
            alerts.clear()
        }
    }
    
    /**
     * Generate usage report.
     */
    fun generateReport(from: Instant = Instant.EPOCH, to: Instant = Instant.now()): TokenReport {
        val byTask = taskTokens.mapValues { it.value.get().toInt() }
        val byAgent = agentTokens.mapValues { it.value.get().toInt() }
        
        return TokenReport(
            totalTokens = getTotalTokens(),
            byTask = byTask,
            byAgent = byAgent,
            savings = getSavedTokens(),
            period = from to to
        )
    }
    
    /**
     * Reset all counters (for testing).
     */
    fun reset() {
        taskTokens.clear()
        agentTokens.clear()
        totalTokens.set(0)
        savedTokens.set(0)
    }
    
    private fun checkAlerts(currentUsage: Int) {
        synchronized(alerts) {
            alerts.forEach { alert ->
                if (currentUsage >= alert.threshold) {
                    try {
                        alert.handler(currentUsage, alert.threshold)
                    } catch (e: Exception) {
                        // Don't let handler errors break tracking
                        System.err.println("Alert handler error: ${e.message}")
                    }
                }
            }
        }
    }
}
