
package com.orchestrator.modules.routing

import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.UserDirective

/**
 * Implements the hard-coded, non-negotiable routing rules from the MCP_TOOL_QUICK_REFERENCE.md guide.
 * This advisor acts as a pre-filter before the more flexible StrategyPicker is invoked.
 * It ensures that core workflow rules are always enforced.
 */
class McpToolAdvisor {

    /**
     * Advises on a routing strategy based on the strict rules from the guide.
     *
     * @return A RoutingStrategy if a hard-coded rule applies, otherwise null.
     */
    fun advise(task: Task, directive: UserDirective): RoutingStrategy? {
        // Rule: "I want a specific agent to do this." -> assign_task -> SOLO
        // This is handled by the strongAssignment check in StrategyPicker, but we can make it more explicit.
        if (directive.assignToAgent != null && directive.assignmentConfidence > 0.8) {
            println("[McpToolAdvisor] Rule Applied: User specified an agent. -> SOLO")
            return RoutingStrategy.SOLO
        }

        // Rule: "This is complex, high-risk, or needs multiple opinions." -> create_consensus_task -> CONSENSUS
        val classification = TaskClassifier.classify(task.description ?: task.title)
        if (classification.risk >= 7 || classification.complexity >= 7) {
            // Exception: User explicitly prevents consensus for a high-risk task (e.g., emergency)
            if (directive.preventConsensus && directive.preventConsensusConfidence > 0.8) {
                 println("[McpToolAdvisor] Rule Applied: High-risk task but user forced solo. -> SOLO")
                 return RoutingStrategy.SOLO
            }
            println("[McpToolAdvisor] Rule Applied: Task is high-risk or complex. -> CONSENSUS")
            return RoutingStrategy.CONSENSUS
        }
        
        // Rule: User explicitly requests consensus
        if (directive.forceConsensus && directive.forceConsensusConfidence > 0.8) {
            println("[McpToolAdvisor] Rule Applied: User forced consensus. -> CONSENSUS")
            return RoutingStrategy.CONSENSUS
        }

        // If no hard-coded rule applies, return null to allow StrategyPicker to decide.
        println("[McpToolAdvisor] No hard-coded rule applied. Deferring to StrategyPicker.")
        return null
    }
}
