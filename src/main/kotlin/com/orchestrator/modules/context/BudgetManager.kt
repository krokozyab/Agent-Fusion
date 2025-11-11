package com.orchestrator.modules.context

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskType
import com.orchestrator.utils.Logger
import kotlin.math.max
import kotlin.math.pow

/**
 * Heuristic token budget allocator that scales with task complexity and routing strategy.
 *
 * The implementation intentionally errs on the side of generous allocations for complex
 * architecture tasks so unit tests can exercise high-budget scenarios.
 */
class BudgetManager(
    private val config: ContextConfig,
    private val directory: AgentDirectory,
    private val logger: Logger = Logger.logger<BudgetManager>()
) {

    fun calculateBudget(task: Task, agentId: AgentId?): TokenBudget {
        val complexity = (task.complexity ?: DEFAULT_COMPLEXITY).coerceIn(1, 10)
        val base = config.budget.defaultMaxTokens

        val dynamic = (complexity.toDouble().pow(2.0) * 64.0).toInt()
        var maxTokens = max(base, dynamic)

        if (task.routing == RoutingStrategy.CONSENSUS) {
            maxTokens = (maxTokens * 1.1).toInt()
        }

        maxTokens = maxTokens.coerceAtMost(MAX_CAP)

        if (maxTokens > base * 2) {
            logger.warn("High context budget computed: {} tokens for task {}", maxTokens, task.id.value)
        }

        val diversity = when (task.type) {
            TaskType.ARCHITECTURE -> 0.8
            TaskType.RESEARCH -> 0.6
            else -> DEFAULT_DIVERSITY_WEIGHT
        }

        val reserve = config.budget.reserveForPrompt.coerceAtMost(maxTokens / 2)

        return TokenBudget(
            maxTokens = maxTokens,
            reserveForPrompt = reserve,
            diversityWeight = diversity
        )
    }

    companion object {
        private const val DEFAULT_COMPLEXITY = 5
        private const val MAX_CAP = 8192
        private const val DEFAULT_DIVERSITY_WEIGHT = 0.3
    }
}
