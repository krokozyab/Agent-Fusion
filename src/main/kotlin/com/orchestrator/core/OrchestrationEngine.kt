package com.orchestrator.core

import com.orchestrator.domain.*
import com.orchestrator.modules.consensus.ConsensusOutcome
import com.orchestrator.modules.consensus.ConsensusService
import com.orchestrator.modules.consensus.DefaultConsensusService
import com.orchestrator.modules.context.ContextModule.TaskContext
import com.orchestrator.modules.context.ContextModule.UpdateResult
import com.orchestrator.modules.context.ContextService
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.modules.context.DefaultContextService
import com.orchestrator.modules.routing.RoutingModule
import com.orchestrator.storage.repositories.TaskRepository
import com.orchestrator.workflows.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Event types published by the orchestration engine.
 */
sealed class OrchestrationEvent {
    data class TaskCreated(val taskId: TaskId, val timestamp: Instant = Instant.now()) : OrchestrationEvent()
    data class TaskRouted(val taskId: TaskId, val decision: RoutingDecision, val timestamp: Instant = Instant.now()) : OrchestrationEvent()
    data class WorkflowStarted(val taskId: TaskId, val strategy: RoutingStrategy, val timestamp: Instant = Instant.now()) : OrchestrationEvent()
    data class WorkflowCompleted(val taskId: TaskId, val result: WorkflowResult, val timestamp: Instant = Instant.now()) : OrchestrationEvent()
    data class WorkflowFailed(val taskId: TaskId, val error: String, val timestamp: Instant = Instant.now()) : OrchestrationEvent()
    data class StateTransition(val taskId: TaskId, val from: TaskStatus, val to: TaskStatus, val timestamp: Instant = Instant.now()) : OrchestrationEvent()
}

class OrchestrationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Main orchestration engine coordinating task routing, workflow execution, and module integration.
 *
 * Responsibilities:
 * - Route tasks to appropriate workflows based on routing decisions
 * - Manage task lifecycle state transitions
 * - Coordinate routing, consensus, and context modules
 * - Handle workflow errors robustly
 * - Publish events for observability
 */
class OrchestrationEngine(
    private val agentRegistry: AgentRegistry,
    private val routingModule: RoutingModule = RoutingModule(agentRegistry),
    private val taskRepository: TaskRepository = TaskRepository,
    private val consensusService: ConsensusService = DefaultConsensusService(),
    private val contextService: ContextService = DefaultContextService(),
    private val eventChannel: Channel<OrchestrationEvent> = Channel(Channel.UNLIMITED)
) {
    private val workflows = mutableMapOf<RoutingStrategy, WorkflowExecutor>()
    private val taskLocks = ConcurrentHashMap<TaskId, Mutex>()
    
    init {
        // Register default workflows
        registerWorkflow(SoloWorkflow(agentRegistry))
        registerWorkflow(ConsensusWorkflow(agentRegistry))
        registerWorkflow(SequentialWorkflow(agentRegistry))
    }

    /**
     * Event stream for observability.
     */
    val events: Flow<OrchestrationEvent> = eventChannel.receiveAsFlow()

    /**
     * Register a workflow executor for specific routing strategies.
     */
    fun registerWorkflow(executor: WorkflowExecutor) {
        executor.supportedStrategies.forEach { strategy ->
            workflows[strategy] = executor
        }
    }

    /**
     * Execute a task: route, select workflow, execute, handle errors.
     */
    suspend fun executeTask(task: Task, directive: UserDirective = UserDirective(originalText = "")): WorkflowResult {
        val mutex = lockFor(task.id)
        return mutex.withLock {
            publishEvent(OrchestrationEvent.TaskCreated(task.id))

            var persistedTask = task
            var inserted = false

            try {
                persistNewTask(task)
                inserted = true

                val routingDecision = routingModule.routeTaskWithDirective(task, directive)
                publishEvent(OrchestrationEvent.TaskRouted(task.id, routingDecision))

                val mergedMetadata = if (routingDecision.metadata.isEmpty()) {
                    task.metadata
                } else {
                    task.metadata + routingDecision.metadata
                }

                persistedTask = task.copy(
                    routing = routingDecision.strategy,
                    assigneeIds = routingDecision.participantAgentIds.toSet(),
                    metadata = mergedMetadata
                )

                persistTaskUpdate(persistedTask)

                val workflow = workflows[routingDecision.strategy]
                    ?: return@withLock failWithoutWorkflow(persistedTask, "No workflow registered for strategy: ${routingDecision.strategy}", inserted)

                executeWorkflow(persistedTask, workflow)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                handleTopLevelFailure(persistedTask, ex, inserted)
            }
        }
    }

    /**
     * Execute a workflow with state management and error handling.
     * Uses WorkflowRuntime for mutable state, workflows return WorkflowStep.
     */
    private suspend fun executeWorkflow(task: Task, workflow: WorkflowExecutor): WorkflowResult {
        val runtime = WorkflowRuntime(
            task = task,
            currentStatus = task.status
        )

        return try {
            // Transition to IN_PROGRESS
            transitionState(runtime, TaskStatus.IN_PROGRESS)
            publishEvent(OrchestrationEvent.WorkflowStarted(task.id, task.routing))

            // Execute workflow - returns WorkflowStep
            val step = workflow.execute(runtime)

            // Handle WorkflowStep result
            when (step) {
                is WorkflowStep.Success -> {
                    transitionState(runtime, TaskStatus.COMPLETED)
                    publishEvent(OrchestrationEvent.WorkflowCompleted(task.id, createResult(runtime, step)))
                    createResult(runtime, step)
                }
                is WorkflowStep.Failure -> {
                    transitionState(runtime, TaskStatus.FAILED)
                    publishEvent(OrchestrationEvent.WorkflowFailed(task.id, step.error))
                    createResult(runtime, step)
                }
                is WorkflowStep.WaitingInput -> {
                    transitionState(runtime, TaskStatus.WAITING_INPUT)
                    createResult(runtime, step)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val error = e.message ?: e.toString()
            runCatching { transitionState(runtime, TaskStatus.FAILED) }
                .onFailure { log("Failed to record FAILED transition for task ${task.id.value}: ${it.message ?: it::class.simpleName}") }
            publishEvent(OrchestrationEvent.WorkflowFailed(task.id, error))

            WorkflowResult(
                taskId = task.id,
                status = TaskStatus.FAILED,
                error = error,
                startedAt = runtime.startedAt,
                completedAt = Instant.now()
            )
        }
    }

    /**
     * Convert WorkflowStep to WorkflowResult.
     */
    private fun createResult(runtime: WorkflowRuntime, step: WorkflowStep): WorkflowResult {
        val now = Instant.now()
        return when (step) {
            is WorkflowStep.Success -> WorkflowResult(
                taskId = runtime.task.id,
                status = TaskStatus.COMPLETED,
                output = step.output,
                artifacts = step.artifacts,
                metrics = mapOf(
                    "total_tokens" to runtime.totalTokens(),
                    "duration_ms" to (now.toEpochMilli() - runtime.startedAt.toEpochMilli())
                ),
                checkpoints = emptyList(), // Retrieved separately if needed
                startedAt = runtime.startedAt,
                completedAt = now,
                error = null
            )
            is WorkflowStep.Failure -> WorkflowResult(
                taskId = runtime.task.id,
                status = TaskStatus.FAILED,
                output = null,
                artifacts = emptyMap(),
                metrics = mapOf(
                    "total_tokens" to runtime.totalTokens(),
                    "duration_ms" to (now.toEpochMilli() - runtime.startedAt.toEpochMilli())
                ),
                checkpoints = emptyList(),
                startedAt = runtime.startedAt,
                completedAt = now,
                error = step.error
            )
            is WorkflowStep.WaitingInput -> WorkflowResult(
                taskId = runtime.task.id,
                status = TaskStatus.WAITING_INPUT,
                output = null,
                artifacts = emptyMap(),
                metrics = mapOf("total_tokens" to runtime.totalTokens()),
                checkpoints = emptyList(),
                startedAt = runtime.startedAt,
                completedAt = now,
                error = null
            )
        }
    }

    /**
     * Transition task state with validation and persistence.
     * Uses WorkflowRuntime to maintain live status.
     */
    private fun transitionState(runtime: WorkflowRuntime, newStatus: TaskStatus) {
        val stateMachineStatus = StateMachine.getCurrentState(runtime.task.id)
        val oldStatus = stateMachineStatus ?: runtime.currentStatus

        if (!StateMachine.isValidTransition(oldStatus, newStatus)) {
            val message = "Invalid state transition: $oldStatus -> $newStatus for task ${runtime.task.id.value}"
            log(message)
            throw OrchestrationException(message)
        }

        val persisted = try {
            taskRepository.updateStatus(runtime.task.id, newStatus, setOf(oldStatus))
        } catch (e: Exception) {
            val message = "Failed to persist state transition ${oldStatus} -> ${newStatus} for task ${runtime.task.id.value}: ${e.message ?: e::class.simpleName}"
            log(message)
            throw OrchestrationException(message, e)
        }

        if (!persisted) {
            val message = "Concurrent modification detected while updating task ${runtime.task.id.value} status to $newStatus"
            log(message)
            throw OrchestrationException(message)
        }

        runtime.currentStatus = newStatus

        if (!StateMachine.transition(runtime.task.id, oldStatus, newStatus)) {
            log("StateMachine rejected transition $oldStatus -> $newStatus for task ${runtime.task.id.value} even after validation")
        }

        publishEvent(OrchestrationEvent.StateTransition(runtime.task.id, oldStatus, newStatus))
    }

    /**
     * Get current workflow state for a task.
     */
    fun getWorkflowState(taskId: TaskId, strategy: RoutingStrategy): WorkflowState? {
        return workflows[strategy]?.currentState(taskId)
    }

    /**
     * Get state transition history for a task.
     */
    fun getStateHistory(taskId: TaskId): List<StateTransition> {
        return StateMachine.getHistory(taskId)
    }

    /**
     * Resume a task from a checkpoint.
     */
    suspend fun resumeTask(task: Task, checkpointId: String? = null): WorkflowResult {
        val mutex = lockFor(task.id)
        return mutex.withLock {
            val workflow = workflows[task.routing]
                ?: return@withLock failWithoutWorkflow(task, "No workflow registered for strategy: ${task.routing}")

            val runtime = WorkflowRuntime(
                task = task,
                currentStatus = task.status
            )

            try {
                publishEvent(OrchestrationEvent.WorkflowStarted(task.id, task.routing))
                val step = workflow.resume(runtime, checkpointId)

                when (step) {
                    is WorkflowStep.Success -> {
                        transitionState(runtime, TaskStatus.COMPLETED)
                        val result = createResult(runtime, step)
                        publishEvent(OrchestrationEvent.WorkflowCompleted(task.id, result))
                        result
                    }
                    is WorkflowStep.Failure -> {
                        transitionState(runtime, TaskStatus.FAILED)
                        val result = createResult(runtime, step)
                        publishEvent(OrchestrationEvent.WorkflowFailed(task.id, step.error))
                        result
                    }
                    is WorkflowStep.WaitingInput -> {
                        transitionState(runtime, TaskStatus.WAITING_INPUT)
                        createResult(runtime, step)
                    }
                }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                handleTopLevelFailure(task, ex, wasPersisted = true)
            }
        }
    }

    /**
     * Access to routing module for external use.
     */
    fun route(task: Task, directive: UserDirective = UserDirective(originalText = "")): RoutingDecision {
        return routingModule.routeTaskWithDirective(task, directive)
    }

    /**
     * Access to consensus module for external use.
     */
    fun runConsensus(
        taskId: TaskId,
        strategyOrder: List<com.orchestrator.modules.consensus.strategies.ConsensusStrategyType> = emptyList()
    ): ConsensusOutcome {
        return consensusService.decide(taskId, strategyOrder, Duration.ZERO)
    }

    /**
     * Access to context module for external use.
     */
    fun getTaskContext(taskId: TaskId, maxTokens: Int = 4000): TaskContext {
        return contextService.getTaskContext(taskId, maxTokens)
    }

    /**
     * Update task context.
     */
    fun updateTaskContext(taskId: TaskId, updates: ContextModule.ContextUpdates): UpdateResult {
        return contextService.updateContext(taskId, updates)
    }

    private fun lockFor(taskId: TaskId): Mutex = taskLocks.computeIfAbsent(taskId) { Mutex() }

    private fun persistNewTask(task: Task) {
        try {
            taskRepository.insert(task)
        } catch (e: Exception) {
            val message = "Failed to persist task ${task.id.value}: ${e.message ?: e::class.simpleName}"
            log(message)
            throw OrchestrationException(message, e)
        }
    }

    private fun persistTaskUpdate(task: Task) {
        try {
            taskRepository.update(task)
        } catch (e: Exception) {
            val message = "Failed to update task ${task.id.value}: ${e.message ?: e::class.simpleName}"
            log(message)
            throw OrchestrationException(message, e)
        }
    }

    private fun failWithoutWorkflow(task: Task, message: String, wasPersisted: Boolean = true): WorkflowResult {
        return handleTopLevelFailure(task, OrchestrationException(message), wasPersisted)
    }

    private fun handleTopLevelFailure(task: Task, error: Throwable, wasPersisted: Boolean): WorkflowResult {
        val message = error.message ?: error.toString()
        log("Task ${task.id.value} failed: $message")
        val now = Instant.now()

        if (wasPersisted) {
            runCatching {
                taskRepository.updateStatus(task.id, TaskStatus.FAILED, setOf(task.status))
            }.onFailure {
                log("Failed to mark task ${task.id.value} as FAILED: ${it.message ?: it::class.simpleName}")
            }
        }

        publishEvent(OrchestrationEvent.WorkflowFailed(task.id, message, now))

        return WorkflowResult(
            taskId = task.id,
            status = TaskStatus.FAILED,
            error = message,
            startedAt = task.createdAt,
            completedAt = now
        )
    }

    private fun failTask(task: Task, error: String): WorkflowResult {
        val now = Instant.now()
        runCatching {
            val failed = task.copy(status = TaskStatus.FAILED, updatedAt = now)
            taskRepository.update(failed)
        }.onFailure {
            log("Failed to persist FAILED status for task ${task.id.value}: ${it.message ?: it::class.simpleName}")
        }
        return WorkflowResult(
            taskId = task.id,
            status = TaskStatus.FAILED,
            error = error,
            startedAt = now,
            completedAt = now
        )
    }

    private fun publishEvent(event: OrchestrationEvent) {
        val result = eventChannel.trySend(event)
        if (result.isFailure) {
            val cause = result.exceptionOrNull()
            val reason = cause?.message ?: if (result.isClosed) "channel closed" else "buffer full"
            val taskId = when (event) {
                is OrchestrationEvent.TaskCreated -> event.taskId.value
                is OrchestrationEvent.TaskRouted -> event.taskId.value
                is OrchestrationEvent.WorkflowStarted -> event.taskId.value
                is OrchestrationEvent.WorkflowCompleted -> event.taskId.value
                is OrchestrationEvent.WorkflowFailed -> event.taskId.value
                is OrchestrationEvent.StateTransition -> event.taskId.value
            }
            log("Failed to publish event ${event::class.simpleName} for task $taskId: $reason")
        }
    }

    private fun log(message: String) {
        println("[OrchestrationEngine] $message")
    }

    /**
     * Shutdown the engine and clean up resources.
     */
    fun shutdown() {
        eventChannel.close()
        consensusService.shutdown()
        contextService.shutdown()
    }
}
