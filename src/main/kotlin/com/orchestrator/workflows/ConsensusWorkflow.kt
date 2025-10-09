package com.orchestrator.workflows

import com.orchestrator.core.AgentRegistry
import com.orchestrator.core.EventBus
import com.orchestrator.domain.*
import com.orchestrator.modules.consensus.ConsensusModule
import com.orchestrator.modules.consensus.ProposalManager
import com.orchestrator.modules.metrics.TokenTracker
import com.orchestrator.storage.repositories.MessageRepository
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant

/**
 * Consensus workflow executor.
 * Refactored to use coroutines and BaseWorkflowExecutor.
 *
 * Responsibilities:
 * - Request proposals from multiple agents (assigned or available ONLINE)
 * - Collect them asynchronously with timeout controls
 * - Execute consensus strategy chain via ConsensusModule
 * - Persist resulting Decision and update Task
 * - Return detailed WorkflowStep with checkpoints and metrics
 */
class ConsensusWorkflow(
    agentRegistry: AgentRegistry,
    stateStore: WorkflowStateStore = InMemoryWorkflowStateStore(),
    messageRepository: MessageRepository = MessageRepository,
    eventBus: EventBus = EventBus.global,
    /** Max agents to request proposals from; prevents unbounded fan-out */
    private val maxAgents: Int = 5,
    /** Per-agent proposal timeout in milliseconds */
    private val perAgentTimeoutMs: Long = 120_000L,
    /** Optional extra wait for late proposals before running consensus */
    private val waitForAdditionalProposals: Duration = Duration.ZERO,
    /** Preferred strategy order to try in ConsensusModule */
    private val strategyOrder: List<com.orchestrator.modules.consensus.strategies.ConsensusStrategyType> = listOf(
        com.orchestrator.modules.consensus.strategies.ConsensusStrategyType.VOTING,
        com.orchestrator.modules.consensus.strategies.ConsensusStrategyType.REASONING_QUALITY,
        com.orchestrator.modules.consensus.strategies.ConsensusStrategyType.CUSTOM
    ),
    /**
     * Pluggable proposal producer: given a task and an agent, generate a proposal and submit it.
     * Default creates a minimal placeholder proposal to exercise the flow.
     */
    private val proposalProducer: suspend (task: Task, agent: Agent) -> Proposal = { task, agent ->
        ProposalManager.submitProposal(
            taskId = task.id,
            agentId = agent.id,
            content = mapOf(
                "summary" to "Proposal from ${agent.displayName} for ${task.title}",
                "taskId" to task.id.value,
                "agentId" to agent.id.value
            ),
            inputType = InputType.OTHER,
            confidence = 0.6,
            tokenUsage = TokenUsage(inputTokens = 100, outputTokens = 50),
            metadata = mapOf("auto" to "true")
        )
    }
) : BaseWorkflowExecutor(
    agentRegistry = agentRegistry,
    stateStore = stateStore,
    messageRepository = messageRepository,
    eventBus = eventBus
) {

    override val supportedStrategies: Set<RoutingStrategy> = setOf(RoutingStrategy.CONSENSUS)

    override suspend fun execute(runtime: WorkflowRuntime): WorkflowStep {
        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "consensus:start"
        )

        // 1) Select agents
        val agents = selectAgents(runtime.task)
        if (agents.isEmpty()) {
            return createFailure(runtime, "No available agents for CONSENSUS execution")
        }

        val selectedAgents = agents.take(maxAgents)
        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "consensus:agents_selected",
            data = mapOf("count" to selectedAgents.size.toString())
        )

        // Emit agent assignment events
        selectedAgents.forEach { agent ->
            eventBus.publish(WorkflowEvent.AgentAssigned(runtime.task.id, agent.id))
        }

        // 2) Request proposals asynchronously using coroutines
        val proposals = coroutineScope {
            selectedAgents.map { agent ->
                async(Dispatchers.IO) {
                    try {
                        withTimeout(perAgentTimeoutMs) {
                            proposalProducer(runtime.task, agent)
                        }
                    } catch (e: Exception) {
                        log("Agent ${agent.id.value} proposal failed: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "consensus:proposals_collected",
            data = mapOf(
                "requested" to selectedAgents.size.toString(),
                "received" to proposals.size.toString()
            )
        )

        if (proposals.isEmpty()) {
            return createFailure(runtime, "No proposals received from ${selectedAgents.size} agents")
        }

        // Record tokens for all proposals
        proposals.forEach { proposal ->
            val content = proposal.content.toString()
            recordTokens(runtime, proposal.agentId, content, isInput = false)
        }

        // 3) Execute consensus (module will persist Decision)
        val outcome = try {
            ConsensusModule.decide(
                taskId = runtime.task.id,
                strategyOrder = strategyOrder,
                waitFor = waitForAdditionalProposals
            )
        } catch (e: Exception) {
            return createFailure(runtime, "Consensus decision failed: ${e.message}")
        }

        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "consensus:executed",
            data = mapOf(
                "decisionId" to outcome.decisionId.value,
                "considered" to outcome.consideredCount.toString(),
                "strategies" to outcome.strategyTrail.joinToString("->")
            )
        )

        // Record consensus savings in TokenTracker (estimated)
        // Estimate savings as avoided processing by agents that didn't win
        val avgProposalTokens = if (proposals.isNotEmpty()) {
            runtime.totalTokens() / proposals.size
        } else 0
        val savedTokens = avgProposalTokens * (proposals.size - 1).coerceAtLeast(0)

        if (savedTokens > 0) {
            tokenTracker.recordSavings(savedTokens)
        }

        // 4) Store consensus result message
        val resultContent = "Consensus decision ${outcome.decisionId.value}: ${outcome.result.reasoning}"
        val resultTokens = recordTokens(runtime, AgentId("system"), resultContent, isInput = false)
        persistMessage(
            runtime = runtime,
            agentId = AgentId("system"),
            role = "system",
            content = resultContent,
            tokens = resultTokens
        )

        return createSuccess(
            runtime = runtime,
            output = outcome.result.reasoning,
            artifacts = mapOf(
                "decisionId" to outcome.decisionId.value,
                "strategyTrail" to outcome.strategyTrail.joinToString(","),
                "proposalsConsidered" to outcome.consideredCount.toString(),
                "tokensSaved" to savedTokens.toString()
            )
        )
    }

    override suspend fun resume(runtime: WorkflowRuntime, checkpointId: String?): WorkflowStep {
        // For Consensus workflow, resume re-executes from current state
        // TODO: In future, restore proposal collection state from checkpoint payload
        log("Resuming consensus workflow for task ${runtime.task.id.value}")
        return execute(runtime)
    }

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
