package com.orchestrator.workflows

import com.orchestrator.core.AgentRegistry
import com.orchestrator.core.EventBus
import com.orchestrator.domain.*
import com.orchestrator.modules.metrics.TokenTracker
import com.orchestrator.storage.repositories.MessageRepository
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant

/**
 * Parallel workflow executor.
 *
 * Executes multiple agents simultaneously on the same task, collecting independent
 * results without coordination. Unlike CONSENSUS (which selects a winner), PARALLEL
 * preserves all agent outputs for the human to review separately.
 *
 * Use cases:
 * - Research tasks requiring diverse perspectives
 * - Testing scenarios with different approaches
 * - Exploratory analysis with multiple angles
 * - Low-risk investigation with moderate complexity
 *
 * Key differences from CONSENSUS:
 * - No winner selection or voting
 * - No proposal comparison or merging
 * - All results preserved equally
 * - Human reviews all outputs independently
 * - Focus on breadth over convergence
 *
 * Responsibilities:
 * - Select multiple available agents (2-3 typically)
 * - Execute agents in parallel using coroutines
 * - Collect all results with timeout controls
 * - Persist all agent outputs without ranking
 * - Return aggregated results with metadata
 * - Track tokens for each agent independently
 */
class ParallelWorkflow(
    agentRegistry: AgentRegistry,
    stateStore: WorkflowStateStore = InMemoryWorkflowStateStore(),
    messageRepository: MessageRepository = MessageRepository,
    eventBus: EventBus = EventBus.global,
    /** Max agents to execute in parallel */
    private val maxAgents: Int = 3,
    /** Per-agent execution timeout in milliseconds */
    private val perAgentTimeoutMs: Long = 120_000L,
    /** Optional wait time after first completion before collecting results */
    private val gracePeriod: Duration = Duration.ZERO,
    /** Minimum successful agents required (0 = at least one, -1 = all must succeed) */
    private val minSuccessfulAgents: Int = 0,
    /**
     * Pluggable task executor: given a task and an agent, execute and return output.
     * Default creates a minimal placeholder output to exercise the flow.
     */
    private val agentTaskRunner: suspend (task: Task, agent: Agent) -> String = { task, agent ->
        "Parallel execution result from ${agent.displayName} for task ${task.title}"
    }
) : BaseWorkflowExecutor(
    agentRegistry = agentRegistry,
    stateStore = stateStore,
    messageRepository = messageRepository,
    eventBus = eventBus
) {

    override val supportedStrategies: Set<RoutingStrategy> = setOf(RoutingStrategy.PARALLEL)

    /**
     * Result from a single agent's parallel execution.
     */
    data class AgentResult(
        val agentId: AgentId,
        val output: String,
        val tokens: Int,
        val executionTimeMs: Long,
        val success: Boolean,
        val error: String? = null
    )

    override suspend fun execute(runtime: WorkflowRuntime): WorkflowStep {
        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "parallel:start"
        )

        // 1) Select agents for parallel execution
        val agents = selectAgents(runtime.task)
        if (agents.isEmpty()) {
            return createFailure(runtime, "No available agents for PARALLEL execution")
        }

        val selectedAgents = agents.take(maxAgents)
        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "parallel:agents_selected",
            data = mapOf("count" to selectedAgents.size.toString())
        )

        // Emit agent assignment events
        selectedAgents.forEach { agent ->
            eventBus.publish(WorkflowEvent.AgentAssigned(runtime.task.id, agent.id))
        }

        log("Executing ${selectedAgents.size} agents in parallel for task ${runtime.task.id.value}")

        // 2) Execute all agents in parallel using coroutines
        val results = coroutineScope {
            selectedAgents.map { agent ->
                async(Dispatchers.IO) {
                    executeAgent(runtime, agent)
                }
            }.awaitAll()
        }

        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "parallel:execution_completed",
            data = mapOf(
                "total" to results.size.toString(),
                "successful" to results.count { it.success }.toString(),
                "failed" to results.count { !it.success }.toString()
            )
        )

        // 3) Validate minimum success requirement
        val successfulResults = results.filter { it.success }
        val requiredSuccesses = when {
            minSuccessfulAgents < 0 -> selectedAgents.size  // All must succeed
            minSuccessfulAgents == 0 -> 1                    // At least one
            else -> minSuccessfulAgents
        }

        if (successfulResults.size < requiredSuccesses) {
            val error = "Insufficient successful executions: ${successfulResults.size}/${requiredSuccesses} required (${results.count { !it.success }} failed)"
            return createFailure(runtime, error)
        }

        // 4) Aggregate results
        val aggregatedOutput = buildAggregatedOutput(results, selectedAgents.size)
        val totalTokens = results.sumOf { it.tokens }
        val avgExecutionTime = results.map { it.executionTimeMs }.average()

        // 5) Build artifacts with detailed metrics
        val artifacts = buildArtifacts(results, totalTokens, avgExecutionTime)

        log("Parallel execution completed: ${successfulResults.size} successful, ${results.count { !it.success }} failed")

        return createSuccess(
            runtime = runtime,
            output = aggregatedOutput,
            artifacts = artifacts
        )
    }

    override suspend fun resume(runtime: WorkflowRuntime, checkpointId: String?): WorkflowStep {
        // For Parallel workflow, resume re-executes from current state
        // TODO: In future, restore execution state from checkpoint payload
        log("Resuming parallel workflow for task ${runtime.task.id.value}")
        return execute(runtime)
    }

    /**
     * Execute a single agent with timeout and error handling.
     */
    private suspend fun executeAgent(runtime: WorkflowRuntime, agent: Agent): AgentResult {
        val startTime = System.currentTimeMillis()

        return try {
            val output = withTimeout(perAgentTimeoutMs) {
                agentTaskRunner(runtime.task, agent)
            }

            val executionTime = System.currentTimeMillis() - startTime

            // Record tokens
            val tokens = recordTokens(runtime, agent.id, output, isInput = false)

            // Persist message
            persistMessage(
                runtime = runtime,
                agentId = agent.id,
                role = "agent",
                content = output,
                tokens = tokens,
                metadataJson = """{"executionTimeMs":$executionTime,"workflow":"parallel"}"""
            )

            log("Agent ${agent.displayName} completed successfully in ${executionTime}ms")

            AgentResult(
                agentId = agent.id,
                output = output,
                tokens = tokens,
                executionTimeMs = executionTime,
                success = true
            )
        } catch (e: TimeoutCancellationException) {
            val executionTime = System.currentTimeMillis() - startTime
            val error = "Timeout after ${perAgentTimeoutMs}ms"

            log("Agent ${agent.displayName} timed out after ${executionTime}ms")

            AgentResult(
                agentId = agent.id,
                output = "",
                tokens = 0,
                executionTimeMs = executionTime,
                success = false,
                error = error
            )
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            val error = e.message ?: e.toString()

            log("Agent ${agent.displayName} failed: $error")

            AgentResult(
                agentId = agent.id,
                output = "",
                tokens = 0,
                executionTimeMs = executionTime,
                success = false,
                error = error
            )
        }
    }

    /**
     * Build human-readable aggregated output from all results.
     */
    private fun buildAggregatedOutput(results: List<AgentResult>, totalAgents: Int): String {
        val successful = results.filter { it.success }
        val failed = results.filter { !it.success }

        return buildString {
            appendLine("=== Parallel Execution Results (${successful.size}/$totalAgents successful) ===")
            appendLine()

            // Successful results
            successful.forEachIndexed { index, result ->
                appendLine("--- Agent ${index + 1}: ${result.agentId.value} ---")
                appendLine("Execution Time: ${result.executionTimeMs}ms")
                appendLine("Tokens: ${result.tokens}")
                appendLine()
                appendLine(result.output)
                appendLine()
            }

            // Failed results (if any)
            if (failed.isNotEmpty()) {
                appendLine("=== Failed Executions (${failed.size}) ===")
                failed.forEach { result ->
                    appendLine("- Agent ${result.agentId.value}: ${result.error}")
                }
            }
        }
    }

    /**
     * Build detailed artifacts map for metrics and debugging.
     */
    private fun buildArtifacts(
        results: List<AgentResult>,
        totalTokens: Int,
        avgExecutionTime: Double
    ): Map<String, String> {
        val successful = results.filter { it.success }
        val failed = results.filter { !it.success }

        return mapOf(
            "totalAgents" to results.size.toString(),
            "successfulAgents" to successful.size.toString(),
            "failedAgents" to failed.size.toString(),
            "totalTokens" to totalTokens.toString(),
            "avgExecutionTimeMs" to avgExecutionTime.toLong().toString(),
            "agentIds" to results.joinToString(",") { it.agentId.value },
            "successfulAgentIds" to successful.joinToString(",") { it.agentId.value },
            "failedAgentIds" to failed.joinToString(",") { it.agentId.value },
            "executionTimes" to results.joinToString(",") { "${it.agentId.value}:${it.executionTimeMs}ms" },
            "tokenBreakdown" to results.joinToString(",") { "${it.agentId.value}:${it.tokens}" }
        )
    }

    /**
     * Select agents for parallel execution.
     * Prefers explicitly assigned ONLINE agents, falls back to all ONLINE agents.
     */
    private fun selectAgents(task: Task): List<Agent> {
        // Prefer explicitly assigned ONLINE agents
        val assigned = task.assigneeIds
            .mapNotNull { agentRegistry.getAgent(it) }
            .filter { it.status == AgentStatus.ONLINE }

        if (assigned.isNotEmpty()) return assigned

        // Otherwise, all ONLINE agents
        return agentRegistry.getAllAgents().filter { it.status == AgentStatus.ONLINE }
    }
}
