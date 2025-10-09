package com.orchestrator.workflows

import com.orchestrator.core.AgentRegistry
import com.orchestrator.core.EventBus
import com.orchestrator.domain.*
import com.orchestrator.modules.metrics.TokenTracker
import com.orchestrator.storage.repositories.MessageRepository
import kotlinx.coroutines.delay

/**
 * Solo workflow executor: runs a single agent against a task with timeout, retries, and result storage.
 * Refactored to use coroutines and BaseWorkflowExecutor.
 */
class SoloWorkflow(
    agentRegistry: AgentRegistry,
    stateStore: WorkflowStateStore = InMemoryWorkflowStateStore(),
    messageRepository: MessageRepository = MessageRepository,
    eventBus: EventBus = EventBus.global,
    timeoutMillis: Long = 180_000L,
    maxRetries: Int = 2,
    backoffMillis: Long = 500L,
    /**
     * Pluggable executor for performing the actual work by an agent.
     * Default implementation just returns a simple confirmation string.
     */
    private val agentTaskRunner: suspend (task: Task, agent: Agent) -> String = { task, agent ->
        "Task ${task.id.value} executed by ${agent.displayName}"
    }
) : BaseWorkflowExecutor(
    agentRegistry = agentRegistry,
    stateStore = stateStore,
    messageRepository = messageRepository,
    eventBus = eventBus,
    timeoutMillis = timeoutMillis,
    maxRetries = maxRetries,
    backoffMillis = backoffMillis
) {

    override val supportedStrategies: Set<RoutingStrategy> = setOf(RoutingStrategy.SOLO)

    override suspend fun execute(runtime: WorkflowRuntime): WorkflowStep {
        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.RUNNING,
            label = "solo:start"
        )

        val agent = selectAgent(runtime.task)
            ?: return createFailure(runtime, "No available agent for SOLO execution")

        // Emit agent assignment event
        eventBus.publish(WorkflowEvent.AgentAssigned(runtime.task.id, agent.id))

        return try {
            val output = withRetry(runtime) { attempt ->
                withTimeout(runtime) {
                    // Execute agent task
                    val result = agentTaskRunner(runtime.task, agent)

                    // Record tokens
                    val tokens = recordTokens(runtime, agent.id, result, isInput = false)

                    // Persist message
                    persistMessage(
                        runtime = runtime,
                        agentId = agent.id,
                        role = "agent",
                        content = result,
                        tokens = tokens
                    )

                    result
                }
            }

            createSuccess(runtime, output)
        } catch (e: Exception) {
            val error = e.message ?: e.toString()
            createFailure(runtime, error, isRetryable = false)
        }
    }

    override suspend fun resume(runtime: WorkflowRuntime, checkpointId: String?): WorkflowStep {
        // For Solo workflow, resume simply re-executes from current state
        // TODO: In future, restore state from checkpoint payload for true idempotent resume
        log("Resuming solo workflow for task ${runtime.task.id.value}")
        return execute(runtime)
    }
}
