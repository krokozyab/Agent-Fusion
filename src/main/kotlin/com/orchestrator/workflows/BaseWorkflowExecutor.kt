package com.orchestrator.workflows

import com.orchestrator.core.AgentRegistry
import com.orchestrator.core.Event
import com.orchestrator.core.EventBus
import com.orchestrator.domain.*
import com.orchestrator.modules.metrics.TokenRecord
import com.orchestrator.modules.metrics.TokenTracker
import com.orchestrator.storage.repositories.MessageRepository
import com.orchestrator.utils.IdGenerator
import com.orchestrator.utils.TokenEstimator
import kotlinx.coroutines.*
import java.time.Instant

/**
 * Abstract base class providing shared workflow execution patterns.
 *
 * Eliminates code duplication by centralizing:
 * - Timeout and retry logic using coroutines
 * - Checkpoint management
 * - Token tracking via TokenTracker
 * - Message persistence
 * - Event emission
 */
abstract class BaseWorkflowExecutor(
    protected val agentRegistry: AgentRegistry,
    protected val stateStore: WorkflowStateStore = InMemoryWorkflowStateStore(),
    protected val messageRepository: MessageRepository = MessageRepository,
    protected val eventBus: EventBus = EventBus.global,
    protected val timeoutMillis: Long = 180_000L, // 3 minutes
    protected val maxRetries: Int = 2,
    protected val backoffMillis: Long = 500L
) : WorkflowExecutor {

    protected val tokenTracker = TokenTracker

    override fun currentState(taskId: TaskId): WorkflowState = stateStore.getState(taskId)

    override fun checkpoints(taskId: TaskId): List<Checkpoint> = stateStore.getCheckpoints(taskId)

    /**
     * Execute an agent task with timeout, returning the output string.
     */
    protected suspend fun <T> withTimeout(
        runtime: WorkflowRuntime,
        timeoutMs: Long = timeoutMillis,
        block: suspend CoroutineScope.() -> T
    ): T = withTimeout(timeoutMs) { block() }

    /**
     * Execute a block with retry logic and exponential backoff.
     */
    protected suspend fun <T> withRetry(
        runtime: WorkflowRuntime,
        maxAttempts: Int = maxRetries + 1,
        block: suspend (attempt: Int) -> T
    ): T {
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                emitCheckpoint(
                    runtime = runtime,
                    state = WorkflowState.RUNNING,
                    label = "attempt",
                    data = mapOf("attempt" to attempt.toString())
                )
                return block(attempt)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    delay(backoffMillis * attempt)
                }
            }
        }

        throw lastException ?: Exception("Unknown retry failure")
    }

    /**
     * Record token usage for an agent interaction.
     */
    protected fun recordTokens(
        runtime: WorkflowRuntime,
        agentId: AgentId,
        content: String,
        isInput: Boolean = false
    ): Int {
        val tokens = TokenEstimator.estimateTokens(content)
        runtime.recordTokens(agentId.value, tokens)

        // Record in TokenTracker for budget tracking
        tokenTracker.track(TokenRecord(
            taskId = runtime.task.id,
            agentId = agentId,
            inputTokens = if (isInput) tokens else 0,
            outputTokens = if (!isInput) tokens else 0
        ))

        return tokens
    }

    /**
     * Persist agent message to repository.
     */
    protected fun persistMessage(
        runtime: WorkflowRuntime,
        agentId: AgentId,
        role: String,
        content: String,
        tokens: Int,
        metadataJson: String? = null
    ) {
        runCatching {
            messageRepository.insert(
                taskId = runtime.task.id,
                role = role,
                content = content,
                tokens = tokens,
                agentId = agentId,
                metadataJson = metadataJson,
                ts = Instant.now()
            )
        }.onFailure { e ->
            log("Failed to persist message: ${e.message}")
        }
    }

    /**
     * Create and store a checkpoint.
     */
    protected fun emitCheckpoint(
        runtime: WorkflowRuntime,
        state: WorkflowState,
        label: String,
        data: Map<String, String> = emptyMap(),
        payload: String? = null
    ) {
        val checkpoint = Checkpoint(
            id = "ckpt-${IdGenerator.ulid()}",
            taskId = runtime.task.id,
            state = state,
            timestamp = Instant.now(),
            label = label,
            data = data,
            payload = payload
        )

        stateStore.addCheckpoint(checkpoint)
        stateStore.setState(runtime.task.id, state)

        // Emit event for observability
        eventBus.publish(WorkflowEvent.CheckpointCreated(checkpoint))
    }

    /**
     * Select an available agent from the task's assignees.
     */
    protected fun selectAgent(task: Task): Agent? {
        // Prefer explicitly assigned agents that are ONLINE
        if (task.assigneeIds.isNotEmpty()) {
            for (aid in task.assigneeIds) {
                val a = agentRegistry.getAgent(aid)
                if (a != null && a.status == AgentStatus.ONLINE) return a
            }
        }
        // Otherwise pick any ONLINE agent
        return agentRegistry.getAllAgents().firstOrNull { it.status == AgentStatus.ONLINE }
    }

    /**
     * Create a failure step with proper cleanup.
     */
    protected fun createFailure(
        runtime: WorkflowRuntime,
        error: String,
        isRetryable: Boolean = false
    ): WorkflowStep.Failure {
        // Update runtime status to FAILED
        runtime.currentStatus = TaskStatus.FAILED

        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.FAILED,
            label = "failed",
            data = mapOf("error" to error)
        )

        eventBus.publish(WorkflowEvent.Failed(runtime.task.id, error))

        return WorkflowStep.Failure(
            runtime = runtime,
            error = error,
            isRetryable = isRetryable
        )
    }

    /**
     * Create a success step with proper cleanup.
     */
    protected fun createSuccess(
        runtime: WorkflowRuntime,
        output: String?,
        artifacts: Map<String, String> = emptyMap()
    ): WorkflowStep.Success {
        // Update runtime status to COMPLETED
        runtime.currentStatus = TaskStatus.COMPLETED

        emitCheckpoint(
            runtime = runtime,
            state = WorkflowState.COMPLETED,
            label = "completed"
        )

        eventBus.publish(WorkflowEvent.Completed(runtime.task.id))

        return WorkflowStep.Success(
            runtime = runtime,
            output = output,
            artifacts = artifacts
        )
    }

    protected fun log(message: String) {
        println("[${this::class.simpleName}] $message")
    }
}

/**
 * Workflow-specific events for EventBus.
 */
sealed class WorkflowEvent : Event {
    data class CheckpointCreated(val checkpoint: Checkpoint, override val timestamp: Instant = Instant.now()) : WorkflowEvent()
    data class AgentAssigned(val taskId: TaskId, val agentId: AgentId, override val timestamp: Instant = Instant.now()) : WorkflowEvent()
    data class RetryAttempt(val taskId: TaskId, val attempt: Int, override val timestamp: Instant = Instant.now()) : WorkflowEvent()
    data class Failed(val taskId: TaskId, val error: String, override val timestamp: Instant = Instant.now()) : WorkflowEvent()
    data class Completed(val taskId: TaskId, override val timestamp: Instant = Instant.now()) : WorkflowEvent()
}
