package com.orchestrator.workflows

import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * State of a workflow execution lifecycle.
 */
enum class WorkflowState {
    NOT_STARTED,
    RUNNING,
    WAITING_INPUT,
    PAUSED,
    COMPLETED,
    FAILED
}

/**
 * A checkpoint captures a recoverable moment in the workflow execution.
 * Implementations may persist checkpoints to durable storage to resume later.
 */
data class Checkpoint(
    val id: String,
    val taskId: TaskId,
    val state: WorkflowState,
    val timestamp: Instant = Instant.now(),
    val label: String? = null,
    val data: Map<String, String> = emptyMap(),
    val payload: String? = null // JSON-serialized workflow-specific state
)

/**
 * Mutable workflow runtime context that travels with execution.
 * Engine owns this and passes it to workflows, eliminating stale Task snapshots.
 */
data class WorkflowRuntime(
    val task: Task,
    var currentStatus: TaskStatus,
    val tokenAccounting: MutableMap<String, Int> = ConcurrentHashMap(),
    val metadata: MutableMap<String, Any> = mutableMapOf(),
    val startedAt: Instant = Instant.now()
) {
    fun recordTokens(agentId: String, tokens: Int) {
        if (tokens == 0) {
            tokenAccounting.putIfAbsent(agentId, 0)
        } else {
            tokenAccounting.merge(agentId, tokens) { existing, addition -> existing + addition }
        }
    }

    fun totalTokens(): Int = tokenAccounting.values.sum()
}

/**
 * Structured result returned by workflows to the engine.
 * Engine uses this to perform state transitions and event emission.
 */
sealed class WorkflowStep {
    abstract val runtime: WorkflowRuntime

    data class Success(
        override val runtime: WorkflowRuntime,
        val output: String? = null,
        val artifacts: Map<String, String> = emptyMap()
    ) : WorkflowStep()

    data class Failure(
        override val runtime: WorkflowRuntime,
        val error: String,
        val isRetryable: Boolean = false
    ) : WorkflowStep()

    data class WaitingInput(
        override val runtime: WorkflowRuntime,
        val waitingFor: Set<String> // agent IDs
    ) : WorkflowStep()
}

/**
 * Comprehensive result of executing a workflow for a Task.
 */
data class WorkflowResult(
    val taskId: TaskId,
    val status: TaskStatus,
    val output: String? = null,
    val artifacts: Map<String, String> = emptyMap(),
    val metrics: Map<String, Number> = emptyMap(),
    val checkpoints: List<Checkpoint> = emptyList(),
    val startedAt: Instant = Instant.now(),
    val completedAt: Instant = Instant.now(),
    val error: String? = null
) {
    val success: Boolean get() = status == TaskStatus.COMPLETED
}

/**
 * Abstraction over workflow state persistence. Allows different storage backends
 * (in-memory, database, file-based) and facilitates checkpoint-based recovery.
 */
interface WorkflowStateStore {
    fun getState(taskId: TaskId): WorkflowState
    fun setState(taskId: TaskId, state: WorkflowState)

    fun addCheckpoint(checkpoint: Checkpoint)
    fun getCheckpoints(taskId: TaskId): List<Checkpoint>
    fun getCheckpoint(taskId: TaskId, checkpointId: String): Checkpoint?
}

/**
 * Minimal in-memory state store useful for tests and simple runtimes.
 */
class InMemoryWorkflowStateStore : WorkflowStateStore {
    private val states: MutableMap<TaskId, WorkflowState> = mutableMapOf()
    private val checkpoints: MutableMap<TaskId, MutableList<Checkpoint>> = mutableMapOf()

    override fun getState(taskId: TaskId): WorkflowState = states[taskId] ?: WorkflowState.NOT_STARTED

    override fun setState(taskId: TaskId, state: WorkflowState) {
        states[taskId] = state
    }

    override fun addCheckpoint(checkpoint: Checkpoint) {
        val list = checkpoints.getOrPut(checkpoint.taskId) { mutableListOf() }
        list.add(checkpoint)
    }

    override fun getCheckpoints(taskId: TaskId): List<Checkpoint> = checkpoints[taskId]?.toList() ?: emptyList()

    override fun getCheckpoint(taskId: TaskId, checkpointId: String): Checkpoint? =
        checkpoints[taskId]?.firstOrNull { it.id == checkpointId }
}

/**
 * Workflow executor defines the contract for executing different workflow types
 * (solo, sequential, parallel, consensus, etc.) against a Task.
 *
 * Implementations should:
 * - Return structured WorkflowStep results (engine handles state transitions)
 * - Create checkpoints at logical boundaries for recovery/resume
 * - Use suspend functions for async operations
 */
interface WorkflowExecutor {
    /**
     * The routing strategies this executor supports.
     */
    val supportedStrategies: Set<RoutingStrategy>

    /**
     * Execute the workflow for the given task runtime.
     * Returns WorkflowStep indicating success/failure/waiting.
     */
    suspend fun execute(runtime: WorkflowRuntime): WorkflowStep

    /**
     * Current state of the workflow for the given task.
     */
    fun currentState(taskId: TaskId): WorkflowState

    /**
     * All known checkpoints for the given task.
     */
    fun checkpoints(taskId: TaskId): List<Checkpoint>

    /**
     * Resume workflow execution from a specific checkpoint (if supported).
     * If checkpointId is null, resume from the latest checkpoint.
     */
    suspend fun resume(runtime: WorkflowRuntime, checkpointId: String? = null): WorkflowStep
}
